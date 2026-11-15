package com.jaribom.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 6차 F6-9: 반복 알림 상한. 숫자는 [AlertPolicy] 한 곳에 있다. 상한에 걸려 안 보낸 알림은 suppressedAlerts 로 센다. */
class AlertPolicyTest {

    private val right = SeatId.ROW_2_RIGHT
    private val left = SeatId.ROW_2_LEFT

    private var day = "2026-11-03"
    private fun engine() = CabinRoutineEngine().apply {
        taxiMode = true
        alertLimiter = AlertLimiter { day }
    }

    /** 아이 좌석 보조 판정(하루 5번 상한 대상). 택시 모드는 하루 상한이 없다(F10-1). */
    private fun childEngine() = CabinRoutineEngine().apply {
        childSeats = setOf(right, left)
        alertLimiter = AlertLimiter { day }
    }

    private fun snap(t: Long, gear: Gear, seat: SeatId, s: SeatState) = VehicleSnapshot(
        seats = SeatId.entries.associateWith { SeatState() } + (seat to s), gear = gear, timestampMs = t,
    )

    /** 택시 모드 1인 패턴(탐→벨트 풂→문 열림→닫힘→P→D). [t0] 부터 시작해 끝에 좌석이 빈 상태로 P 로 돌아온다. */
    private fun ride(e: CabinRoutineEngine, seat: SeatId, t0: Long): List<RoutineEvent> {
        val base = SeatState(occupied = true, beltBuckled = true, doorOpen = false)
        val out = mutableListOf<RoutineEvent>()
        out += e.onSnapshot(snap(t0, Gear.PARK, seat, base))
        out += e.onSnapshot(snap(t0 + 5_000, Gear.PARK, seat, base))
        out += e.onSnapshot(snap(t0 + 6_000, Gear.PARK, seat, base.copy(beltBuckled = false)))
        out += e.onSnapshot(snap(t0 + 7_000, Gear.PARK, seat, base.copy(beltBuckled = false, doorOpen = true)))
        out += e.onSnapshot(snap(t0 + 8_000, Gear.PARK, seat, base.copy(beltBuckled = false, doorOpen = false)))
        out += e.onSnapshot(snap(t0 + 9_000, Gear.DRIVE, seat, base.copy(beltBuckled = false, doorOpen = false)))
        // 내려서 비움 + P
        out += e.onSnapshot(snap(t0 + 15_000, Gear.PARK, seat, SeatState(occupied = false, beltBuckled = false, doorOpen = false)))
        return out
    }

    private fun List<RoutineEvent>.notices() =
        filterIsInstance<RoutineEvent.CleanupNotice>().filter { it.title?.endsWith("확인 필요") == true }

    @Test
    fun `상수는 한 곳에 모여 있다`() {
        assertEquals(1, AlertPolicy.LEFTOVER_PER_SEAT_EPISODE)
        assertEquals(1, AlertPolicy.ASSIST_PER_SEAT_EPISODE)
        assertEquals(5, AlertPolicy.ASSIST_PER_DAY)
        assertEquals(10 * 60 * 1000L, AlertPolicy.FAILURE_DEDUPE_MS)
    }

    @Test
    fun `같은 운행이어도 그 좌석 문이 다시 열린 다음 손님은 알린다 - 좌석 에피소드(F7-3)`() {
        // F6-9 때는 같은 운행 두 번째를 억제했다. F7-3 에서 상한 단위가 좌석 에피소드로 바뀌었다(ride 는 문을 열고 탄다).
        val e = engine()
        assertEquals(1, ride(e, right, 0).notices().size)
        assertEquals(1, ride(e, right, 100_000).notices().size)
        assertEquals(2, e.stats.taxiLeftoverAlerts)
        assertEquals(0, e.stats.suppressedAlerts)
    }

    @Test
    fun `하루 보조 알림은 5번까지이고 6번째는 억제한다 - 아이 좌석`() {
        val e = childEngine()
        val seats = listOf(right, left, right, left, right, left)
        val sent = seats.mapIndexed { i, s ->
            e.endTrip()                                   // 매번 새 운행(좌석당 운행당 1번 제한과 따로 본다)
            ride(e, s, i * 100_000L).notices().size
        }
        assertEquals(listOf(1, 1, 1, 1, 1, 0), sent)
        assertEquals(5, e.stats.leftoverAlerts)
        assertEquals(1, e.stats.suppressedAlerts)
    }

    @Test
    fun `택시 모드는 하루 5번 상한이 없다 - 6번째 이후 손님도 알린다(F10-1)`() {
        val e = engine()
        val sent = (0 until 8).map { i -> e.endTrip(); ride(e, if (i % 2 == 0) right else left, i * 100_000L).notices().size }
        assertEquals(List(8) { 1 }, sent)
        assertEquals(8, e.stats.taxiLeftoverAlerts)
        assertEquals(0, e.stats.suppressedAlerts)
    }

    @Test
    fun `날짜가 바뀌면 하루 상한을 다시 센다 - 아이 좌석`() {
        val e = childEngine()
        val seats = listOf(right, left, right, left, right)
        seats.forEachIndexed { i, s -> e.endTrip(); ride(e, s, i * 100_000L) }
        e.endTrip()
        assertEquals(0, ride(e, left, 600_000).notices().size)        // 같은 날 6번째
        day = "2026-11-04"
        e.endTrip()
        assertEquals(1, ride(e, left, 700_000).notices().size)        // 날짜가 바뀌어 허용
        day = "2026-11-03"
    }

    @Test
    fun `실패 보고는 같은 좌석 같은 명령이면 10분 안에 1번, 10분 뒤에는 다시 허용`() {
        val l = AlertLimiter { day }
        val cmd = VehicleCommand.SetWindow(right, 0).toString()
        assertTrue(l.tryFailure(right, cmd, 0))
        assertFalse(l.tryFailure(right, cmd, 5 * 60_000L))
        assertFalse(l.tryFailure(right, cmd, 10 * 60_000L - 1))
        assertTrue(l.tryFailure(right, cmd, 10 * 60_000L))
        // 다른 좌석이나 다른 명령은 따로 센다
        assertTrue(l.tryFailure(left, cmd, 10 * 60_000L + 1))
        assertTrue(l.tryFailure(right, VehicleCommand.SetSeatHeater(right, 0).toString(), 10 * 60_000L + 2))
    }

    @Test
    fun `남은 물건 알림은 같은 좌석 한 에피소드에 1번이다`() {
        val l = AlertLimiter { day }
        assertTrue(l.tryLeftover(right))
        assertFalse(l.tryLeftover(right))
        assertTrue(l.tryLeftover(left))
        l.onSeatDoorOpened(left)                   // 다른 좌석 문은 이 좌석을 풀지 않는다
        assertFalse(l.tryLeftover(right))
        l.onSeatDoorOpened(right)                  // 이 좌석 문이 열림 = 새 에피소드
        assertTrue(l.tryLeftover(right))
        l.startTrip()
        assertTrue(l.tryLeftover(right))
    }

    @Test
    fun `보조 판정 - 다른 좌석 문 열림은 이 좌석 에피소드를 풀지 않는다`() {
        val l = AlertLimiter { day }
        assertTrue(l.tryAssist(right, 0))
        l.onSeatDoorOpened(left)
        assertFalse(l.tryAssist(right, 1))
        l.onSeatDoorOpened(right)
        assertTrue(l.tryAssist(right, 2))
    }

    @Test
    fun `하루 상한은 에피소드와 상관없이 5번이다`() {
        val l = AlertLimiter { day }
        val sent = (0 until 7).map { i -> l.onSeatDoorOpened(right); l.tryAssist(right, i.toLong()) }
        assertEquals(listOf(true, true, true, true, true, false, false), sent)
    }
}
