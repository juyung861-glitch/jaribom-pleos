package com.jaribom.app.core

/**
 * 차량 스냅샷을 엔진에 넣는 연결부(F14-2). 원래 RoutineController 안에 있던 두 규칙을 그대로 옮겼다(동작 변경 없음).
 *  1) 차량 서비스와 끊긴 동안(linked=false)의 스냅샷은 엔진에 넣지 않는다(F7-2, 마지막 값으로 가짜 하차를 만들지 않도록).
 *  2) 다시 붙은 순간(false→true) 지금 전체 상태를 엔진에 한 번 넣는다(F13-3, [ReconnectReconcile]).
 * 순수 Kotlin이라 JVM 테스트에서 FakeVehicleGateway 와 실제 엔진으로 재연결 흐름을 그대로 돌려 볼 수 있다.
 *
 * @param deliver 엔진에 넣는 쪽(컨트롤러의 onSnapshot)
 */
class LinkedSnapshotFeed(initiallyLinked: Boolean, private val deliver: (VehicleSnapshot) -> Unit) {
    private var wasLinked = initiallyLinked

    /** 스냅샷이 왔다. 끊긴 동안([linked]=false)이면 버린다. */
    fun onSnapshot(snapshot: VehicleSnapshot, linked: Boolean) {
        if (linked) deliver(snapshot)
    }

    /**
     * 연결 상태가 [up] 으로 바뀌었다. 다시 붙은 순간이면 [current] 를 [nowMs] 시각으로 한 번 엔진에 넣고 true 를 돌려준다.
     * (true 일 때 호출한 쪽이 재연결 뒤 창문 확인 같은 다른 후속 일을 한다.)
     */
    fun onLinkChanged(up: Boolean, current: VehicleSnapshot, nowMs: Long): Boolean {
        val reconnected = !wasLinked && up
        ReconnectReconcile.snapshotOnLinkChange(wasLinked, up, current, nowMs)?.let(deliver)
        wasLinked = up
        return reconnected
    }
}
