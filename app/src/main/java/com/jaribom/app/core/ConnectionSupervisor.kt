package com.jaribom.app.core

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** connect 재시도 간격(6차 F6-4): 1·2·4·8·16·30초. 마지막 값(30초)을 유지하고, 성공하면 처음부터 다시 센다. */
object Backoff {
    val STEPS_MS: List<Long> = listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L)
    fun delayMs(failures: Int): Long = STEPS_MS[failures.coerceIn(0, STEPS_MS.lastIndex)]
}

/**
 * 연결 사망 감지(F7-2). Pleos Vehicle SDK 2.3.2 에는 연결 상태를 알려 주는 공개 API 가 없고, 끊기면 호출이 큐에 쌓여
 * 실패 콜백도 오지 않는다. 그래서 [PROBE_INTERVAL_MS] 마다 값 하나를 읽어 보고, [PROBE_TIMEOUT_MS] 안에 답이 없거나
 * 실패·null 이면 실패로 친다. 연속 [FAILS_TO_DEAD] 번이면 사망이다(한 번 성공하면 처음부터 셈).
 */
class LinkWatchdog {
    companion object {
        const val FAILS_TO_DEAD = 3
        const val PROBE_INTERVAL_MS = 5_000L
        const val PROBE_TIMEOUT_MS = 2_000L
        /** 연결 직후 첫 탐침. SDK 의 bind 재시도(500ms×30)를 감안해 길게 기다린다. */
        const val FIRST_PROBE_TIMEOUT_MS = 5_000L
        /** SDK 가 DISCONNECT 상태에서 돌려주는 오류 문구 */
        const val CONNECTION_FAILED_MESSAGE = "Service connection failed"

        /** 읽기 실패 콜백을 분류한다. 연결 실패 문구만 끊김이고, 그 밖의 오류는 서비스가 응답한 것이다. */
        fun classifyError(message: String?): ProbeResult =
            if (message?.contains(CONNECTION_FAILED_MESSAGE) == true) ProbeResult.DISCONNECTED else ProbeResult.ANSWERED

        /**
         * 연결 시도의 첫 탐침 결과 → 다음 동작. DISCONNECTED 는 SDK 가 bind 재시도(약 15초)를 다 쓰고 멈춘 것이라
         * 탐침만 반복하면 영원히 못 붙는다 — initialize() 를 다시 불러야 한다.
         */
        /** 첫 탐침이 연속으로 ERROR(앱 쪽 예외)일 때 이 횟수째에는 판정 불가로 보고 연결로 진행한다(구독이 영원히 안 걸리는 것을 막음). */
        const val MAX_FIRST_ERRORS = 3

        fun nextStep(first: ProbeResult, consecutiveErrors: Int = 0): ConnectStep = when (first) {
            ProbeResult.OK, ProbeResult.ANSWERED -> ConnectStep.CONNECTED
            // 앱 쪽 동기 예외(ERROR)는 바인더 사망이 아니다. initialize 를 다시 부르면 연결이 살아 있어도 SDK 상태가 CONNECTING 으로 바뀌어 큐가 멈출 수 있다.
            ProbeResult.ERROR -> if (consecutiveErrors >= MAX_FIRST_ERRORS) ConnectStep.CONNECTED else ConnectStep.REPROBE
            ProbeResult.TIMEOUT -> ConnectStep.REPROBE
            ProbeResult.DISCONNECTED -> ConnectStep.REINITIALIZE
        }
    }

    /**
     * 탐침 한 번의 결과. [TIMEOUT]·[DISCONNECTED] 만 실패다.
     * 값이 null 이거나 다른 오류로 답한 것([ANSWERED])은 서비스가 살아 있다는 뜻이다(운전석 착좌 신호가 없는 차에서 끝없이 재연결하지 않게).
     */
    enum class ProbeResult { OK, ANSWERED, TIMEOUT, DISCONNECTED, ERROR }

    /** 연결 시도의 첫 탐침 결과로 정하는 다음 동작 */
    enum class ConnectStep {
        /** 서비스가 답함 → 구독·읽기 */
        CONNECTED,
        /** 아직 붙는 중(SDK bind 재시도) → 같은 인스턴스로 다음에 탐침만 다시 */
        REPROBE,
        /** SDK 가 DISCONNECT 에 멈춤(bind 재시도를 다 씀) → 다음에 initialize() 를 다시 부른다 */
        REINITIALIZE,
    }

    private var fails = 0

    /** 탐침 결과를 넣는다. 이번 결과로 사망이 됐으면 true(그 뒤 [reset] 전까지는 다시 true 를 주지 않는다). */
    fun record(r: ProbeResult): Boolean {
        if (r == ProbeResult.OK || r == ProbeResult.ANSWERED) { fails = 0; return false }
        // ERROR(앱 쪽 동기 예외)는 서비스 생사에 대한 정보가 없다: 세지도 초기화하지도 않는다.
        // 세면 앱 쪽 버그가 있는 차에서 재연결이 끝없이 반복된다. TIMEOUT·DISCONNECTED 만 실패다.
        if (r == ProbeResult.ERROR) return false
        fails++
        return fails == FAILS_TO_DEAD
    }

    fun reset() { fails = 0 }
}

/**
 * 차량 연결 감독. 순수 Kotlin 이라 JVM 테스트로 검증한다.
 *  - [connectWithRetry]: [connectOnce] 가 성공할 때까지 [Backoff] 간격으로 다시 시도한다(취소되면 멈춘다).
 *  - [onServiceDied]: 서비스가 죽었다는 신호. 쓰기를 막고([connected] = false) 구독을 [teardown] 으로 먼저 해제한 뒤 다시 붙는다
 *    → 리스너가 겹쳐 등록되지 않는다. 재연결 중에 신호가 또 와도 재연결은 하나만 돈다.
 * 연결 성공 시 [connectOnce] 가 현재값을 다시 읽어 스냅샷을 새로 채워야 한다(게이트웨이의 몫).
 */
class ConnectionSupervisor(
    private val connectOnce: suspend () -> Boolean,
    private val teardown: suspend () -> Unit,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
    private val onRetry: (failures: Int, waitMs: Long) -> Unit = { _, _ -> },
) {
    private val running = Mutex()

    @Volatile var connected: Boolean = false
        private set

    /**
     * 앞서 돌던 루프가 있으면 끝날 때까지 기다렸다가 다시 본다. stop() 의 취소는 비동기라 바로 이어지는 start() 가
     * 아직 풀리지 않은 잠금을 만날 수 있다 — 그때 그냥 돌아가면 연결 없이 남는다.
     */
    suspend fun connectWithRetry() {
        running.withLock { if (!connected) loop() }
    }

    suspend fun onServiceDied() {
        if (!running.tryLock()) return          // 이미 재연결 중
        try {
            connected = false
            teardown()
            loop()
        } finally { running.unlock() }
    }

    suspend fun stop() {
        connected = false
        teardown()
    }

    private suspend fun loop() {
        var failures = 0
        while (true) {
            if (connectOnce()) { connected = true; return }
            val wait = Backoff.delayMs(failures)
            failures++
            onRetry(failures, wait)
            sleep(wait)
        }
    }
}
