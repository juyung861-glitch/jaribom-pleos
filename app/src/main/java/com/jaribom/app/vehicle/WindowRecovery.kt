package com.jaribom.app.vehicle

import com.jaribom.app.core.PendingWindowWrite
import com.jaribom.app.core.RecoveryVerdict
import com.jaribom.app.core.WindowRecoveryRules
import kotlinx.coroutines.delay

/**
 * 재연결 뒤 창문 확인(7차 F8-2). 대기 목록의 좌석 창문 위치를 읽고, `recheckMs` 뒤 다시 읽어 계속 변하는지 본다.
 * 위험(문 열림·착좌·벨트)이 있는데 아직 움직이면 [VehicleGateway.stopWindow] 로 멈춘다(F9-1과 같은 정지, SafetyGate 를 거치지 않음).
 * 그 밖에는 읽기뿐이다. 새 닫기 쓰기는 없다.
 */
object WindowRecovery {
    const val RECHECK_MS = 500L

    suspend fun review(gateway: VehicleGateway, pending: List<PendingWindowWrite>, recheckMs: Long = RECHECK_MS): List<RecoveryVerdict> {
        if (pending.isEmpty()) return emptyList()
        val first = pending.associate { it.seat to gateway.snapshot.value.seat(it.seat).windowOpenPercent }
        if (recheckMs > 0) delay(recheckMs)
        return pending.map { p ->
            val now = gateway.snapshot.value.seat(p.seat)
            val moving = first[p.seat] != now.windowOpenPercent
            when (val v = WindowRecoveryRules.decide(p, now, moving)) {
                is RecoveryVerdict.Stop -> v.copy(stopFailed = gateway.stopWindow(p.seat).isFailure)
                else -> v
            }
        }
    }
}
