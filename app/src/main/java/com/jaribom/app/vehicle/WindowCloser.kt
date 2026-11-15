package com.jaribom.app.vehicle

import com.jaribom.app.core.SeatId
import com.jaribom.app.core.VehicleCommand
import com.jaribom.app.core.WindowAbortReason
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext

/**
 * 창문을 닫는 쓰기를 하는 동안 그 좌석을 지켜본다(6차 F9-1). SafetyGate 는 쓰기 "직전"에만 검사하므로,
 * 닫히는 최대 16초 동안 문이 열리거나 사람이 앉아도 몰랐다.
 *  - 닫기 시작 뒤 그 좌석의 문 열림·착좌·벨트 체결, 같은 열 옆자리의 착좌·벨트 체결(F13-2)([WindowAbortReason])이 생기면 쓰기를 취소하고 [VehicleGateway.stopWindow] 로 멈춘다.
 *  - 멈춘 뒤 다시 닫지 않는다(자동 재개 없음).
 *  - 연결이 끊기면([VehicleGateway.linked] = false) 쓰기가 불가능하므로 정지를 시도하지 않고 "연결 없음" 실패로 돌려준다.
 *  - 닫는 중에 이 코루틴이 취소되면(서비스 stop) 차량은 이미 받은 닫기를 계속하므로, 취소 불가 구간에서 정지를 시도한다.
 * 이 정지는 차량 자체의 끼임 방지의 보조일 뿐이다.
 */
object WindowCloser {
    /** 정지 확인(F10-5): 정지 쓰기 뒤 이 간격으로 두 번 읽어 위치가 변하면 정지를 한 번 더 쓴다 */
    const val STOP_CHECK_GAP_MS = 300L

    /**
     * 정지 쓰기 뒤 창문이 계속 움직이는지 본다. 움직이면 정지를 1번 더 쓰고 true(= 멈추지 않았을 수 있음). 멈췄으면 추가 쓰기 없이 false.
     * 위치를 읽을 수 없으면(null) 움직임을 알 수 없으므로 추가 쓰기를 하지 않는다.
     */
    private suspend fun stillMovingAfterStop(gateway: VehicleGateway, seat: SeatId): Boolean {
        if (!isMoving(gateway, seat)) return false
        gateway.stopWindow(seat)
        return true
    }

    /** 300ms 간격으로 두 번 읽어 위치가 변하면 움직이는 중. 위치를 읽을 수 없으면(null) 알 수 없으므로 false. 쓰기 없음. */
    private suspend fun isMoving(gateway: VehicleGateway, seat: SeatId): Boolean {
        val first = gateway.snapshot.value.seat(seat).windowOpenPercent
        delay(STOP_CHECK_GAP_MS)
        val second = gateway.snapshot.value.seat(seat).windowOpenPercent
        return first != null && second != null && first != second
    }

    sealed class Outcome {
        /** 끝까지 갔거나(성공) 실패했다. */
        data class Done(val result: Result<Unit>) : Outcome()
        /**
         * 도중에 멈췄다. [stop] 은 정지 쓰기의 결과(실패일 수 있다). [notStopped] 는 정지 뒤에도 창문이 계속 움직여 정지를 한 번 더 썼다는 뜻이다
         * (F10-5, 운전자에게 "멈추지 않았을 수 있어요"라고 알린다).
         */
        data class Aborted(
            val reason: WindowAbortReason, val stop: Result<Unit>, val notStopped: Boolean = false,
            /** 재정지 뒤에도 창문이 계속 움직였다(F14-3). 더 쓰지 않고 운전자에게 직접 확인을 부탁한다. */
            val stillMoving: Boolean = false,
        ) : Outcome()
    }

    private sealed class Trip {
        object LinkLost : Trip()
        data class Abort(val reason: WindowAbortReason) : Trip()
    }

    suspend fun close(gateway: VehicleGateway, cmd: VehicleCommand.SetWindow): Outcome {
        val seat: SeatId = cmd.seat
        var finished = false
        try {
            return coroutineScope {
                val startSnap = gateway.snapshot.value
                val start = startSnap.seat(seat)
                // 같은 열 옆자리(운전석 제외)도 본다(F13-2). 시작 때 이미 있던 사람은 원인이 아니다.
                val mates = SeatId.rowMates(seat).associateWith { startSnap.seat(it) }
                val exec = async { gateway.execute(cmd) }
                val watch = async {
                    combine(gateway.snapshot, gateway.linked) { s, linked ->
                        if (!linked) Trip.LinkLost
                        else (WindowAbortReason.detect(start, s.seat(seat))
                            ?: mates.firstNotNullOfOrNull { (m, st) -> WindowAbortReason.detectNeighbor(st, s.seat(m)) })?.let { Trip.Abort(it) }
                    }.first { it != null }!!
                }
                select<Outcome> {
                    exec.onAwait { r -> watch.cancel(); Outcome.Done(r) }
                    watch.onAwait { trip ->
                        exec.cancel()
                        when (trip) {
                            Trip.LinkLost -> Outcome.Done(Result.failure(IllegalStateException("연결 없음(재연결 중)")))
                            is Trip.Abort -> {
                                val stop = gateway.stopWindow(seat)
                                val reStopped = stop.isSuccess && stillMovingAfterStop(gateway, seat)
                                // F14-3: 재정지 뒤에도 움직이면 더 쓰지 않는다(정지 쓰기는 최대 2번). 읽기만 한다.
                                Outcome.Aborted(trip.reason, stop, notStopped = reStopped, stillMoving = reStopped && isMoving(gateway, seat))
                            }
                        }
                    }
                }.also { finished = true }
            }
        } catch (e: CancellationException) {
            // 서비스 stop 등으로 취소됐는데 닫기가 아직 끝나지 않았다면 차량은 계속 닫는다. 연결이 살아 있으면 멈춘다.
            if (!finished && gateway.linked.value) withContext(NonCancellable) { gateway.stopWindow(seat) }
            throw e
        }
    }
}
