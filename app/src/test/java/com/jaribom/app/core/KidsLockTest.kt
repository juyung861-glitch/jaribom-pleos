package com.jaribom.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F12-4 키즈락 좌석.
 *  - 메인 "키즈락 모드"를 끄면 지정 좌석도 일반 좌석으로 동작한다(잠금·아이 말투·탑승 알림 없음).
 *  - [어른이 탔어요]를 P 에서 누르면 그 좌석 창문 잠금을 탑승 전 원래 상태로 되돌린다.
 *  - SafetyGate 는 사람이 앉은 좌석의 잠금 해제를 "운전자가 P 에서 직접 누른 경우"에만 허용한다. 자동·다시 시도 경로는 그대로 거부.
 */
class KidsLockTest {

    private val seat = SeatId.ROW_2_RIGHT
    private val base = SeatState(windowOpenPercent = 0, heaterLevel = 0, ventilationLevel = 0, doorOpen = false, windowLocked = false)

    private fun snap(t: Long, s: SeatState, gear: Gear = Gear.PARK) = VehicleSnapshot(
        seats = SeatId.entries.associateWith { SeatState() } + (seat to s), gear = gear, timestampMs = t,
    )

    private fun engine(on: Boolean = true) = CabinRoutineEngine().apply { childSeats = setOf(seat); kidsLockOn = on }

    private fun board(e: CabinRoutineEngine): List<RoutineEvent> =
        e.onSnapshot(snap(0, base.copy(occupied = true, beltBuckled = true))) + e.onSnapshot(snap(5_000, base.copy(occupied = true, beltBuckled = true)))

    private fun List<RoutineEvent>.adultNotice() = filterIsInstance<RoutineEvent.CleanupNotice>().first { it.adultSeat != null }

    private val lockedSeated = snap(6_000, base.copy(occupied = true, beltBuckled = true, windowLocked = true))

    // ── SafetyGate ────────────────────────────────────────────
    @Test
    fun `앉은 좌석의 잠금 해제는 운전자가 P 에서 누른 경우만 허용한다`() {
        val unlock = VehicleCommand.SetWindowLock(seat, false)
        assertTrue(SafetyGate.check(unlock, lockedSeated, driverAction = true).allowed)
        assertFalse("자동·다시 시도 경로", SafetyGate.check(unlock, lockedSeated).allowed)
        val drive = lockedSeated.copy(gear = Gear.DRIVE)
        assertEquals("P 에서만 풀 수 있음", SafetyGate.check(unlock, drive, driverAction = true).reason)
        assertFalse(SafetyGate.check(unlock, lockedSeated.copy(gear = Gear.NEUTRAL), driverAction = true).allowed)
        // 운전자 버튼이라도 운전석·관리 꺼짐은 그대로 거부
        assertFalse(SafetyGate.check(VehicleCommand.SetWindowLock(SeatId.ROW_1_LEFT, false), lockedSeated, driverAction = true).allowed)
    }

    // ── 키즈락 모드 켜기/끄기 ───────────────────────────────────
    @Test
    fun `키즈락 모드를 끄면 지정 좌석도 잠그지 않고 탑승 알림도 없고 존댓말이다`() {
        val off = engine(on = false)
        val ev = board(off)
        assertTrue(ev.filterIsInstance<RoutineEvent.Execute>().none { ex -> ex.commands.any { it is VehicleCommand.SetWindowLock } })
        assertTrue(ev.filterIsInstance<RoutineEvent.CleanupNotice>().none { it.adultSeat != null })
        assertFalse(off.isChildNow(seat))
        val on = engine(on = true)
        val evOn = board(on)
        assertTrue(evOn.filterIsInstance<RoutineEvent.Execute>().any { ex -> ex.commands.contains(VehicleCommand.SetWindowLock(seat, true)) })
        assertTrue(on.isChildNow(seat))
    }

    @Test
    fun `탑승 알림은 키즈락 모드 문구다`() {
        assertEquals("${seat.label} 키즈락 모드로 탑승을 확인했어요.", board(engine()).adultNotice().text)
    }

    // ── 어른이 탔어요 ─────────────────────────────────────────
    @Test
    fun `P 에서 누르면 탑승 전 원래 상태(풀림)로 되돌리는 명령이 나온다`() {
        val e = engine()
        val n = board(e).adultNotice()
        e.onAdultAboard(n.key, seat, n.buttonEpisodes.getValue(seat))
        assertEquals(VehicleCommand.SetWindowLock(seat, false), e.adultUnlockCommand(seat, lockedSeated))
    }

    @Test
    fun `P 가 아니면 풀지 않는다 - 탑승 전부터 잠겨 있었으면 풀지 않는다`() {
        val e = engine()
        val n = board(e).adultNotice()
        e.onAdultAboard(n.key, seat, n.buttonEpisodes.getValue(seat))
        assertNull(e.adultUnlockCommand(seat, lockedSeated.copy(gear = Gear.DRIVE)))

        val wasLocked = engine()
        val n2 = wasLocked.onSnapshot(snap(0, base.copy(occupied = true, beltBuckled = true, windowLocked = true))).adultNotice()
        wasLocked.onAdultAboard(n2.key, seat, n2.buttonEpisodes.getValue(seat))
        assertNull(wasLocked.adultUnlockCommand(seat, lockedSeated))
    }

    @Test
    fun `버튼을 누르지 않았으면 해제 명령이 없다`() {
        val e = engine()
        board(e)
        assertNull(e.adultUnlockCommand(seat, lockedSeated))
    }

    @Test
    fun `해제 결과 - 성공은 알림 갱신, 실패는 다시 시도 없이 직접 풀기 안내`() {
        val e = engine()
        val n = board(e).adultNotice()
        e.onAdultAboard(n.key, seat, n.buttonEpisodes.getValue(seat))
        val cmd = e.adultUnlockCommand(seat, lockedSeated)!!
        val ok = e.onAdultUnlockResult(n.key, CommandResult(cmd, CommandResult.Status.OK))
        assertEquals("${seat.label}은 이번 운행만 어른 좌석으로 안내할게요. 창문 잠금을 탑승 전처럼 풀었어요.", ok.text)
        val fail = e.onAdultUnlockResult(n.key, CommandResult(cmd, CommandResult.Status.FAILED))
        assertTrue(fail.text.contains("창문 잠금을 풀지 못했어요"))
        assertTrue(fail.failed.isEmpty() && fail.adultSeat == null)
    }

    // ── F12-fix2: [어른이 탔어요] 문구는 실제 결과에 맞춘다. "내리면 풀려요"는 어느 경우에도 없다. ──
    private val adultLine = "${seat.label}은 이번 운행만 어른 좌석으로 안내할게요."

    @Test
    fun `탑승 전에 풀려 있던 좌석 - 누른 직후에는 안내만, 해제 성공하면 결과 문구`() {
        val e = engine()
        val n = board(e).adultNotice()
        val pressed = e.onAdultAboard(n.key, seat, n.buttonEpisodes.getValue(seat), lockedSeated)
        assertEquals(adultLine, pressed.text)
        val cmd = e.adultUnlockCommand(seat, lockedSeated)!!
        assertEquals("$adultLine 창문 잠금을 탑승 전처럼 풀었어요.", e.onAdultUnlockResult(n.key, CommandResult(cmd, CommandResult.Status.OK)).text)
    }

    @Test
    fun `탑승 전부터 잠겨 있던 좌석 - 그대로 둔다고 알린다`() {
        val e = engine()
        val n = e.onSnapshot(snap(0, base.copy(occupied = true, beltBuckled = true, windowLocked = true))).adultNotice()
        val r = e.onAdultAboard(n.key, seat, n.buttonEpisodes.getValue(seat), lockedSeated)
        assertEquals("$adultLine 탑승 전부터 잠겨 있던 창문이라 그대로 둘게요. 풀려면 운전석 창문 잠금 버튼을 써 주세요.", r.text)
        assertNull(e.adultUnlockCommand(seat, lockedSeated))
    }

    @Test
    fun `해제 실패는 경고로 직접 풀기 안내`() {
        val e = engine()
        val n = board(e).adultNotice()
        e.onAdultAboard(n.key, seat, n.buttonEpisodes.getValue(seat), lockedSeated)
        val cmd = e.adultUnlockCommand(seat, lockedSeated)!!
        val fail = e.onAdultUnlockResult(n.key, CommandResult(cmd, CommandResult.Status.FAILED))
        assertEquals("$adultLine 창문 잠금을 풀지 못했어요. 운전석 창문 잠금 버튼으로 풀어 주세요.", fail.text)
        assertEquals("${seat.label} 창문 잠금 해제 실패", fail.title)
    }

    @Test
    fun `P 가 아니면 풀지 않았다고 알리고 내리면 풀린다는 말은 어느 경우에도 없다`() {
        val e = engine()
        val n = board(e).adultNotice()
        val drive = e.onAdultAboard(n.key, seat, n.buttonEpisodes.getValue(seat), lockedSeated.copy(gear = Gear.DRIVE))
        assertEquals("$adultLine 창문 잠금은 P에서만 풀 수 있어요. 지금 풀려면 운전석 창문 잠금 버튼을 써 주세요.", drive.text)
        assertFalse(drive.text.contains("내리면 풀려요"))
        val unknown = engine().let { e2 -> val n2 = board(e2).adultNotice(); e2.onAdultAboard(n2.key, seat, n2.buttonEpisodes.getValue(seat)) }
        assertFalse("차량 상태를 모르면 안내만", unknown.text.contains("내리면 풀려요"))
    }

    @Test
    fun `해제한 뒤 내리면 잠금을 다시 바꾸지 않는다`() {
        val e = engine()
        val n = board(e).adultNotice()
        e.onAdultAboard(n.key, seat, n.buttonEpisodes.getValue(seat))
        val cmd = e.adultUnlockCommand(seat, lockedSeated)!!
        e.onAdultUnlockResult(n.key, CommandResult(cmd, CommandResult.Status.OK))
        e.onSnapshot(snap(10_000, base.copy(occupied = false, windowLocked = false)))
        e.onSnapshot(snap(11_000, base.copy(occupied = false, doorOpen = true, windowLocked = false)))
        val closed = e.onSnapshot(snap(12_000, base.copy(occupied = false, windowLocked = false)))
        assertTrue(closed.filterIsInstance<RoutineEvent.Execute>().none { ex -> ex.commands.any { it is VehicleCommand.SetWindowLock } })
    }
}
