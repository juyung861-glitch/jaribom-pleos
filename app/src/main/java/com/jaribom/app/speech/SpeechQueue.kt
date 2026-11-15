package com.jaribom.app.speech

/**
 * 안내 음성의 순서·오디오 포커스·선행 묵음·타임아웃을 맡는 순수 로직(Android 의존 없음 → JVM 테스트 가능).
 * [FocusedAnnouncer] 가 메인 스레드에서만 부른다.
 *
 * 규칙
 *  - 한 번에 한 문장만 엔진에 넘긴다. 앞 문장이 끝나면(완료·오류·중단 = [onFinished]) 다음 문장을 넘긴다.
 *    → 엔진이 새 문장으로 앞 문장을 끊는 일이 없고, 끝 신호 하나에 문장 하나가 정확히 대응한다.
 *  - 세션(큐가 비어 있다가 첫 문장이 들어온 때)마다 포커스를 한 번 요청하고, 첫 문장 앞에만 [prerollMs] 묵음을 둔다.
 *    큐가 비면 포커스를 반납한다(세션 종료).
 *  - 문장마다 ID 를 붙인다. 지금 말하는 문장이 아닌 ID 의 끝 신호(멈춘 뒤 늦게 온 콜백 등)는 무시한다.
 *  - 안전장치: 문장마다 [timeoutFor] 만큼 기다려도 끝 신호가 없으면 끝난 것으로 보고 [log] 를 남긴다.
 */
class SpeechQueue(
    private val focus: Focus,
    private val engine: Engine,
    private val scheduler: Scheduler,
    private val log: (String) -> Unit = {},
    private val prerollMs: Long = 150L,
    /** 문장이 말해지지 못했을 때(F9-4): 그 문장과 이유. 정상 완료·정지(stopAll)는 부르지 않는다. */
    private val onFailed: (text: String, reason: Failure) -> Unit = { _, _ -> },
    /** 재생 차례에 가드([speak]의 dropIf)에 걸려 일부러 버린 문장(F8-1)과 이유. 음성 실패가 아니다. */
    private val onDropped: (text: String, reason: String) -> Unit = { _, _ -> },
    /** 문장이 끝까지 말해졌을 때(엔진이 실패 없이 끝을 알림, F12-2). 음성으로 낸 보고는 이때 알림을 남기지 않는다. */
    private val onSpoken: (text: String) -> Unit = {},
    /** [stopAll](포커스 잃음·해제)로 말하지 못하고 버려진 문장(F12-2). 음성 실패는 아니다. */
    private val onAbandoned: (text: String) -> Unit = {},
) {
    /** 음성이 실패한 이유 4종(6차 F9-4) */
    enum class Failure { ENGINE_ERROR, INIT_FAILED, TIMEOUT, FOCUS_DENIED }

    interface Focus {
        /** 포커스를 얻으면 true */
        fun request(): Boolean
        fun abandon()
    }

    interface Engine {
        /** 한 문장을 말한다. 끝나면(완료·오류·중단) [SpeechQueue.onFinished] 에 같은 [id] 를 돌려줘야 한다. */
        fun speak(id: Long, text: String)
        fun stop()
    }

    interface Scheduler {
        fun post(delayMs: Long, action: () -> Unit): Any
        fun cancel(token: Any)
    }

    private class Item(val text: String, val dropIf: (() -> String?)?)

    private val queue = ArrayDeque<Item>()
    private var nextId = 1L
    /** 지금 말하는(또는 선행 묵음을 기다리는) 문장 ID. 없으면 null. */
    private var current: Long? = null
    private var currentText: String? = null
    private var inSession = false
    private var prerollToken: Any? = null
    private var timeoutToken: Any? = null

    val isSpeaking: Boolean get() = current != null
    val pendingCount: Int get() = queue.size

    /**
     * [dropIf] 가 있으면 이 문장이 재생 차례가 됐을 때 부른다. 이유(문자열)를 돌려주면 재생하지 않고 [onDropped] 로 알린다.
     * 이미 재생 중인 문장은 끊지 않는다(가드는 차례가 된 문장에만 적용).
     */
    fun speak(text: String, dropIf: (() -> String?)? = null) {
        queue.addLast(Item(text, dropIf))
        if (current == null) startNext()
    }

    /** 엔진이 문장을 끝냈을 때(완료·오류·중단 모두). 지금 문장이 아니면 무시한다. */
    fun onFinished(id: Long, failure: Failure? = null) {
        if (id != current) return
        if (failure != null) currentText?.let { onFailed(it, failure) }
        else currentText?.let { onSpoken(it) }
        finishCurrent()
    }

    /** 포커스를 잃었거나 해제할 때: 남은 문장을 버리고 엔진을 멈추고 포커스를 반납한다. */
    fun stopAll() {
        val abandoned = listOfNotNull(currentText) + queue.map { it.text }
        queue.clear()
        cancelTimers()
        val wasSpeaking = current != null
        current = null                       // 이후 들어오는 이 문장의 늦은 끝 신호는 무시된다
        currentText = null
        if (wasSpeaking) engine.stop()
        endSession()
        abandoned.forEach(onAbandoned)
    }

    private fun startNext() {
        var item = queue.removeFirstOrNull()
        while (item != null) {
            val reason = item.dropIf?.invoke() ?: break
            log("안내 버림($reason): ${item.text}")
            onDropped(item.text, reason)
            item = queue.removeFirstOrNull()
        }
        if (item == null) {
            endSession()
            return
        }
        val text = item.text
        val id = nextId++
        current = id
        currentText = text
        if (inSession) {
            begin(id, text)                  // 같은 세션의 이어지는 문장: 묵음 없이 바로
            return
        }
        if (!focus.request()) {              // 통화·긴급 등으로 거부 → 이번에 모인 안내는 말하지 않는다
            log("포커스 거부 — 안내 ${queue.size + 1}개 생략")
            val dropped = listOf(text) + queue.map { it.text }
            queue.clear()
            current = null
            currentText = null
            dropped.forEach { onFailed(it, Failure.FOCUS_DENIED) }
            return
        }
        inSession = true
        prerollToken = scheduler.post(prerollMs) {
            prerollToken = null
            if (current == id) begin(id, text)
        }
    }

    private fun begin(id: Long, text: String) {
        timeoutToken = scheduler.post(timeoutFor(text)) {
            timeoutToken = null
            if (current == id) {
                log("끝 신호 없음(${timeoutFor(text)}ms) — 문장 #$id 를 끝난 것으로 처리")
                onFailed(text, Failure.TIMEOUT)
                finishCurrent()
            }
        }
        engine.speak(id, text)
    }

    private fun finishCurrent() {
        cancelTimers()
        current = null
        currentText = null
        startNext()
    }

    private fun endSession() {
        if (!inSession) return
        inSession = false
        focus.abandon()
    }

    private fun cancelTimers() {
        prerollToken?.let { scheduler.cancel(it) }; prerollToken = null
        timeoutToken?.let { scheduler.cancel(it) }; timeoutToken = null
    }

    companion object {
        /** 문장 타임아웃: 글자 수 × 150ms + 3초, 최소 5초 */
        fun timeoutFor(text: String): Long = maxOf(5_000L, text.length * 150L + 3_000L)
    }
}
