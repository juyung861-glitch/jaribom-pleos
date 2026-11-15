package com.jaribom.app.core

/**
 * 차에 쓰기 명령을 내리기 전에 거치는 안전 게이트. 명령을 계획할 때 한 번, 쓰기 직전에 한 번 더 검사한다
 * (그 사이에 동승자가 다시 앉거나 문을 열 수 있기 때문 — 심사위원 J3 지적).
 *
 * 원칙(4차 심사 A1: 주행 중 제어 범위)
 *  0. 운전 기능·도어 열림/닫힘(잠금·차일드락 포함)은 앱에 제어 능력이 없다. 권한·명령·호출을 추가하지 않는다.
 *     그래서 여기서 "거부"할 것도 없다. [VehicleCommand] 에는 창문·열선·통풍·창문 잠금(키즈락, 창문 도메인)뿐이다(VehicleCapabilityTest 가 고정).
 *  1. 운전석(ROW_1_LEFT)은 어떤 명령도 내리지 않는다. 운전자가 연 창문은 운전자의 의도다.
 *  2. 사용자가 관리를 꺼 둔 좌석은 제어하지 않는다.
 *  3. 열선·통풍(편의 기능)은 기어와 상관없이 허용한다. 주행 중에도 하차 정리(끄기)·탑승 프리셋(켜기)을 바로 한다.
 *  4. 창문을 "닫는" 명령은 그 좌석에 사람이 없고, 그 문이 닫혀 있고, 벨트가 풀려 있을 때만(끼임 방지, 벨트 신호가 있는 좌석). 기어 조건은 없다.
 *     여는 명령은 앱이 만들지 않는다.
 *  5. 창문 잠금(키즈락 좌석)은 창이 움직이지 않아 끼임과 무관하다. 잠그는 것은 앉아 있어도 허용하고,
 *     푸는 것은 그 좌석에 사람이 앉아 있으면 거부한다(다시 탄 아이의 잠금을 풀지 않도록, 5차 심사 F1).
 *     예외는 하나뿐이다(F12-4): 운전자가 알림 [어른이 탔어요]를 기어 P·정차에서 직접 누른 경우([driverAction]).
 *     자동 정리·탑승 프리셋·[다시 시도] 경로는 [driverAction] 을 넘기지 않으므로 앉은 좌석의 해제는 계속 거부된다.
 */
object SafetyGate {

    data class Verdict(val allowed: Boolean, val reason: String)

    /** P·정차가 아닐 때 운전자 버튼의 잠금 해제를 거부하는 이유 */
    const val UNLOCK_NEEDS_PARK = "P 에서만 풀 수 있음"

    /**
     * @param driverAction 운전자가 알림 버튼을 직접 눌러 생긴 쓰기인가. 지금은 [어른이 탔어요]의 잠금 해제만 이 값을 넘긴다.
     */
    fun check(
        cmd: VehicleCommand, snapshot: VehicleSnapshot, managedSeats: Set<SeatId> = SeatId.passengers.toSet(),
        driverAction: Boolean = false,
    ): Verdict {
        val seat = cmd.seat
        if (seat.isDriver) return Verdict(false, "운전석은 제어하지 않음")
        if (seat !in managedSeats) return Verdict(false, "관리 꺼짐")
        return when (cmd) {
            is VehicleCommand.SetWindow -> {
                val s = snapshot.seat(cmd.seat)
                val closing = cmd.isClosing(s.windowOpenPercent)
                when {
                    closing && s.occupied == true -> Verdict(false, "사람이 다시 앉아 있음")
                    // F10-5: 벨트 신호가 있는 좌석은 벨트가 풀려 있어야 한다(착좌 신호가 틀려도 벨트를 맨 사람은 걸러낸다). 신호가 없으면(null) 보지 않는다.
                    closing && s.beltBuckled == true -> Verdict(false, "벨트를 맨 사람이 있음")
                    closing && s.doorOpen == true -> Verdict(false, "문이 열려 있음")
                    else -> Verdict(true, "ok")
                }
            }
            is VehicleCommand.SetWindowLock -> when {
                cmd.locked || snapshot.seat(cmd.seat).occupied != true -> Verdict(true, "ok")
                !driverAction -> Verdict(false, "사람이 앉아 있음")
                !VehicleMotion.voiceAllowed(snapshot) -> Verdict(false, UNLOCK_NEEDS_PARK)
                else -> Verdict(true, "ok(운전자가 P 에서 직접 누름)")
            }
            is VehicleCommand.SetSeatHeater,
            is VehicleCommand.SetSeatVentilation -> Verdict(true, "ok")
        }
    }
}
