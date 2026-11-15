package com.jaribom.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 5차 심사 F3: 남은 물건 감지 정리(보고 합치기, 판정 창, 물건 이름, 아이 좌석 보조 판정). */
class LeftoverRevisionTest {

    private val left = SeatId.ROW_2_LEFT
    private val right = SeatId.ROW_2_RIGHT
    private val driver = SeatId.ROW_1_LEFT

    private class Script {
        val snaps = mutableListOf<VehicleSnapshot>()
        val st = mutableMapOf<SeatId, SeatState>()
        var gear = Gear.PARK
        fun at(t: Long, vararg change: Pair<SeatId, (SeatState) -> SeatState>) {
            change.forEach { (s, f) -> st[s] = f(st[s] ?: SeatState(occupied = false, beltBuckled = false, doorOpen = false, windowOpenPercent = 0, heaterLevel = 0, ventilationLevel = 0)) }
            snaps += VehicleSnapshot(
                seats = SeatId.entries.associateWith { SeatState(occupied = false, beltBuckled = false, doorOpen = false) } +
                    (SeatId.ROW_1_LEFT to SeatState(occupied = true, beltBuckled = true, doorOpen = false)) + st,
                gear = gear, timestampMs = t,
            )
        }
    }

    private fun sit(belt: Boolean = true): (SeatState) -> SeatState = { it.copy(occupied = true, beltBuckled = belt) }
    private val unbuckle: (SeatState) -> SeatState = { it.copy(beltBuckled = false) }
    private val standUp: (SeatState) -> SeatState = { it.copy(occupied = false, beltBuckled = false) }
    private val open: (SeatState) -> SeatState = { it.copy(doorOpen = true) }
    private val close: (SeatState) -> SeatState = { it.copy(doorOpen = false) }

    private fun run(e: CabinRoutineEngine, s: Script) = s.snaps.flatMap { e.onSnapshot(it) }

    private val childText = "뒷좌석 왼쪽을 확인해 주세요. 아이가 내렸다면 가방이 남아 있을 수 있어요."

    /** 혼자 탄 아이가 벨트를 풀고 문을 열었다 닫음(가방이 남아 착좌 유지) → 출발 */
    private fun childLeavesBag(belt: Boolean) = Script().apply {
        at(0, left to sit())
        at(5_000)
        at(10_000, left to { it.copy(beltBuckled = belt) })
        at(11_000, left to open); at(12_000, left to close)
        gear = Gear.DRIVE; at(20_000); at(25_000)
        gear = Gear.PARK; at(30_000)
        gear = Gear.DRIVE; at(35_000)                                    // 다시 출발해도 또 알리지 않는다
    }

    @Test
    fun `혼자 탄 아이와 가방 - P에서 D로 출발할 때 알림 한 번, 음성 없음`() {
        val e = CabinRoutineEngine().apply { childSeats = setOf(left) }
        val out = run(e, childLeavesBag(belt = false))
        val notices = out.filterIsInstance<RoutineEvent.CleanupNotice>().filter { it.text == childText }
        assertEquals(out.filterIsInstance<RoutineEvent.CleanupNotice>().toString(), 1, notices.size)
        assertTrue(out.filterIsInstance<RoutineEvent.Speak>().none { it.text == childText })
        assertTrue(out.none { it is RoutineEvent.Execute && it.seat == left })
    }

    @Test
    fun `벨트를 맨 아이는 알림이 없다`() {
        val e = CabinRoutineEngine().apply { childSeats = setOf(left) }
        val out = run(e, childLeavesBag(belt = true))
        assertTrue(out.filterIsInstance<RoutineEvent.CleanupNotice>().none { it.text == childText })
    }

    @Test
    fun `차례로 내린 두 좌석 - 5초 간격이어도 남은 물건으로 본다`() {
        val e = CabinRoutineEngine()
        val out = run(e, Script().apply {
            at(0, left to sit(), right to sit())
            at(10_000, left to standUp, right to unbuckle)
            at(11_000, left to open); at(12_000, left to close)                  // 왼쪽 하차 확정
            at(16_000, right to open); at(17_000, right to close)                // 5초 뒤 오른쪽 문(가방은 남음)
        })
        assertTrue(out.toString(), (out.filterIsInstance<RoutineEvent.Speak>().map { it.text } + out.filterIsInstance<RoutineEvent.CleanupNotice>().map { it.text })
            .any { it.contains("뒷좌석 오른쪽에 무언가 남아 있는 것 같아요.") })
    }

    @Test
    fun `남은 물건은 정리 보고에 한 문장으로 합쳐 음성 1번 알림 1건`() {
        val e = CabinRoutineEngine()
        val out = run(e, Script().apply {
            at(0, left to sit(), right to sit())
            at(5_000, left to { it.copy(windowOpenPercent = 40, heaterLevel = 2) })
            at(10_000, left to standUp, right to unbuckle)
            at(11_000, left to open, right to open); at(12_000, left to close); at(12_500, right to close)
        }).toMutableList()
        val exec = out.filterIsInstance<RoutineEvent.Execute>().single { it.seat == left }
        // F13-2: 오른쪽에 착좌(물건)가 읽혀 왼쪽 창문은 자동으로 닫지 않는다. 열선은 끈다.
        assertTrue(exec.commands.toString(), exec.commands.none { it is VehicleCommand.SetWindow } && exec.commands.any { it is VehicleCommand.SetSeatHeater })
        val snapAfter = VehicleSnapshot(gear = Gear.PARK, timestampMs = 16_000)
        out += e.onJobFinished(left, exec.commands.map { CommandResult(it, CommandResult.Status.OK) }, snapAfter)
        val speech = out.filterIsInstance<RoutineEvent.Speak>().filter { it.text.contains("남아 있는") }
        assertEquals(speech.toString(), 1, speech.size)
        assertTrue(speech.single().text, speech.single().text.contains("뒷좌석 왼쪽") && speech.single().text.contains("뒷좌석 오른쪽에 무언가 남아 있는 것 같아요."))
        val notices = out.filterIsInstance<RoutineEvent.CleanupNotice>()
        assertEquals(notices.toString(), 1, notices.size)
        assertTrue(notices.single().title.orEmpty().contains("뒷좌석 오른쪽"))
    }

    @Test
    fun `남은 물건 좌석 때문에 운행 종료가 막히지 않는다`() {
        val e = CabinRoutineEngine()
        val s = Script().apply {
            at(0, left to sit(), right to sit())
            at(10_000, left to standUp, right to unbuckle)
            at(11_000, left to open, right to open); at(12_000, left to close); at(12_500, right to close)
            at(20_000)
        }
        run(e, s)
        assertEquals(setOf(right), e.leftoverSeats)
        assertTrue("가방만 남은 좌석은 빈 좌석으로 본다", e.isTripIdle(s.snaps.last()))
    }

    @Test
    fun `그 좌석의 이 좌석에서만 물건 이름을 넣는다`() {
        val e = CabinRoutineEngine().apply { items = listOf(CarryItem(1, "수학 가방", ItemCondition.SEAT, right)) }
        val out = run(e, Script().apply {
            at(0, left to sit(), right to sit())
            at(10_000, left to standUp, right to unbuckle)
            at(11_000, left to open, right to open); at(12_000, left to close); at(12_500, right to close)
            at(20_000)
        })
        val texts = out.filterIsInstance<RoutineEvent.Speak>().map { it.text } + out.filterIsInstance<RoutineEvent.CleanupNotice>().map { it.text }
        assertTrue(texts.toString(), texts.any { it.contains("뒷좌석 오른쪽에 수학 가방이 남아 있는 것 같아요.") })
    }
}
