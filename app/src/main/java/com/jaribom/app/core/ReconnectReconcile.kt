package com.jaribom.app.core

/**
 * 재연결 뒤 상태 재조정(F13-3).
 *
 * 차량 서비스와 끊긴 동안(linked=false)의 스냅샷은 엔진에 넣지 않는다(F7-2, 가짜 하차 방지). 게이트웨이는 다시 붙을 때 현재값을
 * 모두 다시 읽지만, 그 값이 linked=true 보다 먼저 들어오면 버려지고, 그 뒤 신호가 바뀌지 않으면 엔진은 다음 변화나 30초 폴링까지 모른다.
 * 그래서 다시 붙은 순간 지금 전체 상태(문·착좌·벨트·기어)를 한 번 엔진에 넣는다.
 *
 * 맞추는 일은 엔진 상태머신 규칙 그대로다(새 판정 없음):
 *  - 하차 안내가 나간 좌석(문 닫힘 대기)의 문이 끊긴 사이 닫혔으면 → 하차 확정 → 정리(쓰기 직전 SafetyGate 재검사).
 *  - 그 좌석에 다시 앉아 있으면 → 탑승으로 돌아가고 정리하지 않는다.
 *  - 한 번 확정한 하차는 빈 좌석이 되므로 다시 붙어도 두 번 정리하지 않는다.
 *  - 끊긴 사이 생긴 새 착좌는 문 이력이 없어 탑승으로 확정하지 않고 다음 문 이벤트를 기다린다(벨트 체결은 기존 규칙대로 즉시 확정).
 *    앱 시작 10초 예외는 적용하지 않는다(시작할 때만이다).
 */
object ReconnectReconcile {
    /** [wasLinked] → [up] 으로 바뀌어 다시 붙은 순간이면 [current] 를 [nowMs] 시각으로 돌려준다. 아니면 null. */
    fun snapshotOnLinkChange(wasLinked: Boolean, up: Boolean, current: VehicleSnapshot, nowMs: Long): VehicleSnapshot? =
        if (!wasLinked && up) current.copy(timestampMs = nowMs) else null
}
