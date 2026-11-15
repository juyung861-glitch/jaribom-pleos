package com.jaribom.app.vehicle

import com.jaribom.app.core.VehicleCommand
import com.jaribom.app.core.VehicleSnapshot
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs

/**
 * 쓰기 → 확인(4차 심사 A3, 5차 심사 F2). Pleos 게이트웨이와 가짜 차량이 같은 규칙을 쓴다.
 *  1) 쓰기 응답을 [WRITE_TIMEOUT_MS] 까지 기다린다. 차량이 명시적으로 거절하면([Rejected]) 바로 실패다.
 *  2) 응답이 없어도(시간 초과) 끝내지 않는다. 그 값을 한 번 다시 읽고, 구독값이 목표에 닿을 때까지
 *     창문은 [WINDOW_SETTLE_MS], 열선·통풍·잠금은 [SETTLE_MS] 기다린다. 닿으면 성공(늦은 성공도 성공).
 *  3) 닿지 않으면 [VerifyFailed]. 재시도는 이 경우에만 1회 한다([shouldRetry]).
 * 쓰기 요청은 시간 초과 뒤에도 확인 창이 끝날 때까지 취소하지 않는다(차량 쪽이 늦게 반영하는 경우를 놓치지 않게).
 */
object WriteVerifier {
    const val WRITE_TIMEOUT_MS = 3_000L
    const val SETTLE_MS = 1_500L
    const val WINDOW_SETTLE_MS = 5_000L
    const val RETRY_GAP_MS = 300L

    /** 값이 목표에 닿지 않음(응답 없음 포함). 재시도 대상. */
    class VerifyFailed(message: String) : IllegalStateException(message)
    /** 차량(SDK)이 쓰기를 거절함. 재시도하지 않는다. */
    class Rejected(cause: Throwable) : IllegalStateException(cause.message ?: "쓰기 거절", cause)

    fun shouldRetry(e: Throwable): Boolean = e is VerifyFailed

    fun settleMs(cmd: VehicleCommand): Long = if (cmd is VehicleCommand.SetWindow) WINDOW_SETTLE_MS else SETTLE_MS

    /** 재시도 1회를 포함한 최악 시간. 열선·통풍·잠금 9.3초(예전 12.3초), 창문 16.3초(예전 16.3초). */
    fun worstCaseMs(cmd: VehicleCommand): Long = 2 * (WRITE_TIMEOUT_MS + settleMs(cmd)) + RETRY_GAP_MS

    fun reached(cmd: VehicleCommand, s: VehicleSnapshot): Boolean {
        val seat = s.seat(cmd.seat)
        return when (cmd) {
            is VehicleCommand.SetWindow -> seat.windowOpenPercent?.let { abs(it - cmd.openPercent) <= 1 } == true
            is VehicleCommand.SetSeatHeater -> seat.heaterLevel == cmd.level
            is VehicleCommand.SetSeatVentilation -> seat.ventilationLevel == cmd.level
            is VehicleCommand.SetWindowLock -> seat.windowLocked == cmd.locked
        }
    }

    /**
     * [write] 는 쓰기 요청(응답이 오면 끝나는 suspend), [readBack] 은 그 값 하나를 다시 읽어 [snapshot] 에 반영하는 일이다.
     */
    suspend fun run(
        cmd: VehicleCommand,
        snapshot: StateFlow<VehicleSnapshot>,
        write: suspend () -> Result<Unit>,
        readBack: suspend () -> Unit,
    ): Result<Unit> = coroutineScope {
        val writing = async { runCatching { write() }.getOrElse { Result.failure(it) } }
        val answered = withTimeoutOrNull(WRITE_TIMEOUT_MS) { writing.await() }
        if (answered != null && answered.isFailure) {
            return@coroutineScope Result.failure(Rejected(answered.exceptionOrNull()!!))
        }
        val reading = launch { runCatching { readBack() } }
        val ok = withTimeoutOrNull(settleMs(cmd)) { snapshot.first { reached(cmd, it) } } != null
        reading.cancel()
        writing.cancel()
        if (ok) Result.success(Unit)
        else Result.failure(
            VerifyFailed(
                (if (answered == null) "쓰기 응답 없음(${WRITE_TIMEOUT_MS}ms), " else "") +
                    "${settleMs(cmd)}ms 안에 목표에 닿지 않음: $cmd"
            )
        )
    }
}
