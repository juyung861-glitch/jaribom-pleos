package com.jaribom.app.speech

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.jaribom.app.JbLog
import java.util.Locale

/** 음성 출력 추상화. pleos 플레이버는 Pleos TextToSpeech SDK, mock 은 Android 기본 TTS. */
interface Announcer {
    /**
     * [dropIf] 는 큐에서 이 문장의 재생 차례가 됐을 때 불린다(F8-1). 이유를 돌려주면 재생하지 않고 [onDropped] 로 알린다.
     * 재생 중인 문장은 끊지 않는다.
     */
    fun speak(text: String, dropIf: (() -> String?)? = null)
    fun release()

    /** 재생 차례에 [speak] 의 dropIf 로 일부러 버린 문장과 이유(F8-1). 음성 실패가 아니다. 메인 스레드에서 불린다. */
    var onDropped: ((text: String, reason: String) -> Unit)?

    /**
     * 문장이 말해지지 못했을 때(엔진 오류·초기화 실패·타임아웃·포커스 거부, 6차 F9-4) 그 문장과 이유를 알린다.
     * 메인 스레드에서 불린다. 정상 완료·정지는 알리지 않는다.
     */
    var onFailed: ((text: String, reason: SpeechQueue.Failure) -> Unit)?

    /** 문장이 끝까지 말해졌다(F12-2). 메인 스레드에서 불린다. */
    var onSpoken: ((text: String) -> Unit)?

    /** 포커스를 잃거나 해제돼 말하지 못하고 버려진 문장(F12-2). 음성 실패는 아니다. 메인 스레드에서 불린다. */
    var onAbandoned: ((text: String) -> Unit)?
}

/**
 * 오디오 포커스. Pleos 오디오 포커스 가이드: 모든 스트림은 재생 전에 포커스를 요청하고,
 * 포커스 변경 리스너를 반드시 구현한다(AAOS 에서는 선택이지만 Pleos 에서는 필수).
 *  - 요청: AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK — 짧은 안내라 음악은 끄지 않고 소리만 줄인다(duck).
 *  - LOSS / LOSS_TRANSIENT(Gleo 음성 호출, 통화 등): 즉시 멈추고 포커스를 반납한다.
 *  - LOSS_TRANSIENT_CAN_DUCK: 계속 말한다(시스템이 볼륨을 낮춘다).
 */
class AudioFocusHelper(context: Context, private val onLost: () -> Unit) {

    companion object {
        private const val TAG = "JariBomAudio"
        /**
         * 안내 음성의 usage(6차 F6-6): USAGE_ASSISTANCE_NAVIGATION_GUIDANCE. 알림 볼륨이 0 이어도 안내가 들리게 한다.
         * (예전에는 NOTIFICATION 이었다. ANNOUNCEMENT 는 시스템 API 라 서드파티 앱이 쓸 수 없다.)
         * 포커스 요청은 여전히 GAIN_TRANSIENT_MAY_DUCK 라 내비 안내처럼 음악을 끄지 않고 소리만 줄인다.
         */
        const val USAGE = AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE
        /** 선행 묵음(Pleos 가이드: 최소 150ms, 기본 150~200ms). 새 안내 세션의 첫 문장 앞에만 넣는다. */
        const val PREROLL_MS = 150L
    }

    val attributes: AudioAttributes = AudioAttributes.Builder()
        .setUsage(USAGE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private val am = context.getSystemService(AudioManager::class.java)
    private val listener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                JbLog.i(TAG, "포커스 잃음($change) → 안내 중단")
                held = false
                onLost()
            }
            else -> Unit
        }
    }
    private val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(attributes)
        .setOnAudioFocusChangeListener(listener, Handler(Looper.getMainLooper()))
        .build()

    @Volatile var held = false
        private set

    /** 포커스를 얻으면 true. 거부되면(통화·긴급 상황 등) false → 이번 안내는 말하지 않는다. */
    fun request(): Boolean {
        if (held) return true
        held = am.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if (held) JbLog.i(TAG, "포커스 획득(GAIN_TRANSIENT_MAY_DUCK) → ${PREROLL_MS}ms 묵음 뒤 말하기")
        else JbLog.w(TAG, "포커스 거부 → 음성 생략")
        return held
    }

    fun abandon() {
        if (!held) return
        am.abandonAudioFocusRequest(request)
        held = false
        JbLog.i(TAG, "포커스 반납")
    }
}

/**
 * 포커스·선행 묵음·한 문장씩·반납을 공통으로 처리하는 안내 음성의 뼈대. 순서 로직은 [SpeechQueue] 에 있다.
 * 실제 엔진(Pleos TTS / Android TTS)은 [doSpeak] / [doStop] / [doRelease] 만 구현하고,
 * 한 문장이 끝날 때마다(완료·오류·중단 모두) 받은 문장 ID 로 [onUtteranceFinished] 를 부른다.
 *
 * 흐름: speak → 큐 → (세션 시작이면) 포커스 요청 → 150ms 묵음 → 한 문장 말하기 → 끝 신호 → 다음 문장 … → 큐가 비면 포커스 반납.
 * 끝 신호가 오지 않으면 문장별 타임아웃 뒤 끝난 것으로 처리한다(포커스를 쥐고 놓지 않는 일을 막는 안전장치).
 * 모든 상태는 메인 스레드에서만 바꾼다.
 */
abstract class FocusedAnnouncer(context: Context) : Announcer {
    private companion object { const val TAG = "JariBomAudio" }

    private val main = Handler(Looper.getMainLooper())
    protected val focus: AudioFocusHelper = AudioFocusHelper(context.applicationContext) { main.post { queue.stopAll() } }
    private val queue: SpeechQueue = SpeechQueue(
        focus = object : SpeechQueue.Focus {
            override fun request(): Boolean = this@FocusedAnnouncer.focus.request()
            override fun abandon() { this@FocusedAnnouncer.focus.abandon() }
        },
        engine = object : SpeechQueue.Engine {
            override fun speak(id: Long, text: String) = doSpeak(id, text)
            override fun stop() = doStop()
        },
        scheduler = object : SpeechQueue.Scheduler {
            override fun post(delayMs: Long, action: () -> Unit): Any = Runnable(action).also { main.postDelayed(it, delayMs) }
            override fun cancel(token: Any) = main.removeCallbacks(token as Runnable)
        },
        log = { JbLog.w(TAG, it) },
        prerollMs = AudioFocusHelper.PREROLL_MS,
        onFailed = { text, reason ->
            JbLog.w(TAG, "음성 실패($reason): $text")
            onFailed?.invoke(text, reason)
        },
        onDropped = { text, reason -> onDropped?.invoke(text, reason) },
        onSpoken = { text -> onSpoken?.invoke(text) },
        onAbandoned = { text -> onAbandoned?.invoke(text) },
    )

    final override var onFailed: ((text: String, reason: SpeechQueue.Failure) -> Unit)? = null
    final override var onDropped: ((text: String, reason: String) -> Unit)? = null
    final override var onSpoken: ((text: String) -> Unit)? = null
    final override var onAbandoned: ((text: String) -> Unit)? = null

    final override fun speak(text: String, dropIf: (() -> String?)?) {
        main.post { queue.speak(text, dropIf) }
    }

    /** 엔진이 문장 [id] 를 끝냈을 때(완료·오류·중단 모두) 부른다. 어느 스레드에서 불러도 된다. */
    protected fun onUtteranceFinished(id: Long, failure: SpeechQueue.Failure? = null) {
        main.post { queue.onFinished(id, failure) }
    }

    final override fun release() {
        main.post {
            queue.stopAll()
            doRelease()
        }
    }

    /** 한 문장을 말한다. 끝나면 같은 [id] 로 [onUtteranceFinished] 를 불러야 한다. 한 번에 한 문장만 들어온다. */
    protected abstract fun doSpeak(id: Long, text: String)
    protected abstract fun doStop()
    protected abstract fun doRelease()
}

/**
 * Android 표준 TTS 엔진(포커스 처리 없음). [AndroidTtsAnnouncer] 와 Pleos TTS 실패 시 대체 엔진이 함께 쓴다.
 * 문장이 끝나거나 실패하면 그 문장 ID 로 [onDone] 을 부른다(utteranceId = 문장 ID).
 */
class AndroidTtsEngine(context: Context, private val attributes: AudioAttributes, private val onDone: (Long, SpeechQueue.Failure?) -> Unit) {
    private var ready = false
    private var failed = false
    private val pending = ArrayDeque<Pair<Long, String>>()
    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status -> onInit(status) }

    private fun onInit(status: Int) {
        ready = status == TextToSpeech.SUCCESS
        failed = !ready
        if (ready) {
            tts.language = Locale.KOREAN
            tts.setAudioAttributes(attributes)
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = cb("onStart", utteranceId)
                override fun onDone(utteranceId: String?) { cb("onDone", utteranceId); finished(utteranceId) }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) { cb("onError", utteranceId); finished(utteranceId, SpeechQueue.Failure.ENGINE_ERROR) }
                override fun onError(utteranceId: String?, errorCode: Int) { cb("onError($errorCode)", utteranceId); finished(utteranceId, SpeechQueue.Failure.ENGINE_ERROR) }
                override fun onStop(utteranceId: String?, interrupted: Boolean) { cb("onStop(interrupted=$interrupted)", utteranceId); finished(utteranceId) }
            })
            while (pending.isNotEmpty()) pending.removeFirst().let { (id, text) -> speak(id, text) }
        } else {
            // 엔진을 못 쓰면 기다리던 문장을 버리되, 포커스가 반납되도록 끝난 것으로 처리한다.
            while (pending.isNotEmpty()) onDone(pending.removeFirst().first, SpeechQueue.Failure.INIT_FAILED)
        }
    }

    /** 조사용(Pleos TTS 와 비교): tts.speak 를 부른 시각(elapsedRealtime) */
    @Volatile private var speakAtMs = 0L

    private fun cb(name: String, utteranceId: String?) =
        JbLog.i(TAG, "콜백 $name id=$utteranceId +${SystemClock.elapsedRealtime() - speakAtMs}ms thread=${Thread.currentThread().name}")

    private fun finished(utteranceId: String?, failure: SpeechQueue.Failure? = null) {
        utteranceId?.toLongOrNull()?.let { onDone(it, failure) }
    }

    fun speak(id: Long, text: String) {
        when {
            failed -> onDone(id, SpeechQueue.Failure.INIT_FAILED)
            !ready -> pending.addLast(id to text)
            else -> {
                speakAtMs = SystemClock.elapsedRealtime()
                JbLog.i(TAG, "speak 호출 id=$id len=${text.length}")
                tts.speak(text, TextToSpeech.QUEUE_ADD, null, id.toString())
            }
        }
    }

    private companion object { const val TAG = "AndroidTtsEngine" }

    fun stop() {
        pending.clear()
        if (ready) tts.stop()
    }

    fun release() = tts.shutdown()
}

/** Android 표준 TTS. mock 플레이버에서 쓴다. 포커스·묵음·순서는 [FocusedAnnouncer] 가 처리한다. */
class AndroidTtsAnnouncer(context: Context) : FocusedAnnouncer(context) {
    private val engine = AndroidTtsEngine(context, focus.attributes) { id, failure -> onUtteranceFinished(id, failure) }
    override fun doSpeak(id: Long, text: String) = engine.speak(id, text)
    override fun doStop() = engine.stop()
    override fun doRelease() = engine.release()
}
