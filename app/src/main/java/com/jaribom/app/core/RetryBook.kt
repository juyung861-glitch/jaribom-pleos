package com.jaribom.app.core

/**
 * 알림별 [다시 시도] 대기 목록(5차 심사 F1). 알림 키마다 좌석별 실패 명령을 따로 둔다.
 *  - 버튼은 자기 알림 키의 실패만 꺼낸다(예전 알림 버튼이 최근 실패를 실행하지 않음).
 *  - 꺼낼 때 좌석마다 [take] 의 stillValid(= CabinRoutineEngine.cleanupStillValid)로 다시 확인하고,
 *    그사이 다시 탄 좌석의 명령은 버린다. 쓰기 직전에는 SafetyGate 가 한 번 더 막는다.
 */
class RetryBook {
    private val byKey = mutableMapOf<Int, Map<SeatId, List<VehicleCommand>>>()

    /** 알림 [key] 의 실패를 기록한다. 실패가 없으면(성공한 결과 알림) 그 키의 기록을 지운다. */
    fun record(key: Int, failed: Map<SeatId, List<VehicleCommand>>) {
        if (failed.isEmpty()) byKey.remove(key) else byKey[key] = failed
    }

    fun has(key: Int): Boolean = key in byKey

    /** 알림 [key] 의 실패를 꺼낸다(목록에서 지움). 아직 정리해도 되는 좌석만 돌려준다. */
    fun take(key: Int, stillValid: (SeatId) -> Boolean): Map<SeatId, List<VehicleCommand>> =
        (byKey.remove(key) ?: emptyMap()).filterKeys(stillValid)
}
