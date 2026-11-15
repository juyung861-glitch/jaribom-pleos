package com.jaribom.app.platform

import android.content.Context
import android.os.SystemClock
import com.jaribom.app.JbLog
import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.constant.ErrorType
import ai.pleos.playground.tts.constant.Mode
import ai.pleos.playground.tts.listener.EventListener
import ai.pleos.playground.tts.listener.OnInitializeListener
import com.jaribom.app.speech.AndroidTtsEngine
import com.jaribom.app.speech.FocusedAnnouncer
import com.jaribom.app.speech.SpeechQueue
import java.util.Locale

/**
 * Pleos TextToSpeech SDK 3.3.1 어댑터.
 *  - ON_DEVICE 모드: 앱 등록(Client ID/Secret)이 필요 없다(공식 문서 app-registration).
 *  - 권한: pleos.car.permission.TTS_SERVICE (src/pleos/AndroidManifest.xml)
 *  - 오디오 포커스·선행 묵음(150ms)·한 문장씩·반납·타임아웃은 [FocusedAnnouncer] 가 처리한다.
 *    여기서는 한 번에 한 문장만 받아 말하고, 문장이 끝나면(onDone / onError / onStop) 그 문장 ID 로
 *    [onUtteranceFinished] 를 알려 준다. EventListener 콜백에는 문장 ID 가 없으므로 지금 말하는 ID 를 기억해 쓴다.
 *    멈춘 뒤 늦게 오는 콜백은 ID 가 지난 문장이라 FocusedAnnouncer 쪽에서 무시된다.
 *  - initialize() 의 onSuccess 전에 들어온 문장은 기다렸다가 말한다(끝내 준비되지 않으면 타임아웃이 정리한다).
 *  - 초기화 실패·말하기 오류 시 Android 기본 TTS 로 넘긴다(시연 중 음성이 끊기지 않게).
 */
class PleosTtsAnnouncer(context: Context) : FocusedAnnouncer(context) {
    private val appContext = context.applicationContext
    private var fallback: AndroidTtsEngine? = null
    private val tts = TextToSpeech(appContext, Mode.ON_DEVICE)
    /** 준비 전에 들어온 문장(한 번에 한 문장만 오므로 최대 1개) */
    private var pending: Pair<Long, String>? = null
    @Volatile private var ready = false
    @Volatile private var failed = false
    /** 지금 Pleos TTS 가 말하는 문장 ID. EventListener 콜백을 이 ID 로 돌려준다. */
    @Volatile private var speakingId = 0L
    /** 조사용(기어 D 에서 끝 신호 누락): tts.speak 를 부른 시각(elapsedRealtime). 콜백 로그에 경과 시간을 붙인다. */
    @Volatile private var speakAtMs = 0L

    private fun cb(name: String) =
        JbLog.i(TAG, "콜백 $name id=$speakingId +${SystemClock.elapsedRealtime() - speakAtMs}ms thread=${Thread.currentThread().name}")

    private val events = object : EventListener {
        override fun onStart() = cb("onStart")
        override fun onReady() = cb("onReady")
        override fun onDone() { cb("onDone"); onUtteranceFinished(speakingId) }
        /** 중단(stop 또는 다른 문장에 끊김)도 문장의 끝으로 센다. */
        override fun onStop() { cb("onStop"); onUtteranceFinished(speakingId) }
        override fun onUpdatedRms(rms: Double) {}
        override fun onError(errorType: ErrorType) {
            cb("onError($errorType)")
            JbLog.w(TAG, "speak error: $errorType")
            onUtteranceFinished(speakingId, SpeechQueue.Failure.ENGINE_ERROR)
        }
    }

    init {
        try {
            // addEventListener 는 initialize 가 끝난 뒤에만 호출할 수 있다(전에 부르면 SDK 내부 lateinit 예외).
            tts.initialize(object : OnInitializeListener {
                override fun onSuccess() {
                    runCatching { tts.addEventListener(events) }
                    runCatching { tts.setLocale(Locale.KOREA) }
                    // F6-6: 포커스 요청과 같은 usage(ASSISTANCE_NAVIGATION_GUIDANCE)로 재생한다. SDK 3.3.1 에 setAudioAttributes(AudioAttributes) 가 있다(javap 확인).
                    runCatching { tts.setAudioAttributes(focus.attributes) }.onFailure { JbLog.w(TAG, "setAudioAttributes 실패: ${it.message}") }
                    ready = true
                    flush()
                }
                override fun onFailure(errorType: ErrorType) {
                    JbLog.w(TAG, "init failed: $errorType → Android TTS 로 대체")
                    failed = true
                    flush()
                }
            })
        } catch (e: Exception) {
            JbLog.w(TAG, "init threw → Android TTS 로 대체", e)
            failed = true
        }
    }

    @Synchronized
    private fun flush() {
        pending?.let { (id, text) -> pending = null; doSpeak(id, text) }
    }

    @Synchronized
    override fun doSpeak(id: Long, text: String) {
        when {
            failed -> fallback().speak(id, text)
            !ready -> pending = id to text
            else -> {
                speakingId = id
                speakAtMs = SystemClock.elapsedRealtime()
                JbLog.i(TAG, "speak 호출 id=$id len=${text.length}")
                // speak(text, isFinal): 두 번째 인자 이름은 AAR 의 Kotlin 메타데이터로 확인한 'isFinal' 이다.
                // 의미(문장 끝 표시로 보임, 앞 문장을 끊는지 여부)는 공식 문서로 미확인. 한 번에 한 문장만 넘기므로 끊길 일은 없다.
                runCatching { tts.speak(text, true) }.onFailure { fallback().speak(id, text) }
            }
        }
    }

    @Synchronized
    override fun doStop() {
        pending = null
        if (ready) runCatching { tts.stop() }   // stop() 은 TextToSpeech-3.3.1 AAR 에서 확인함(public final void stop())
        fallback?.stop()
    }

    override fun doRelease() {
        runCatching { tts.removeEventListener(events); tts.release() }
        fallback?.release()
    }

    private fun fallback(): AndroidTtsEngine =
        fallback ?: AndroidTtsEngine(appContext, focus.attributes) { id, failure -> onUtteranceFinished(id, failure) }.also { fallback = it }

    private companion object { const val TAG = "PleosTtsAnnouncer" }
}
