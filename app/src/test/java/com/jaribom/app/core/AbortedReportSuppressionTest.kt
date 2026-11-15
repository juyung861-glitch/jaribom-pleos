package com.jaribom.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** F9-1 리뷰: 실패 보고 10분 억제가 "창문을 닫다가 멈췄어요" 안내를 삼키지 않는다. 정지 실패는 문구에 드러난다. */
class AbortedReportSuppressionTest {

    private val seat = SeatId.ROW_1_RIGHT

    private fun snap(t: Long, s: SeatState) = VehicleSnapshot(
        seats = SeatId.entries.associateWith { SeatState() } + (seat to s), gear = Gear.PARK, timestampMs = t,
    )

    /** 한 손님: 탐 → 일어남 → 문 열림 → 닫힘(하차 확정) → 정리 결과. 창문 40·열선 2 라서 창문·열선 두 명령이 나온다. */
    private fun alight(e: CabinRoutineEngine, t0: Long, statusOf: (VehicleCommand) -> Pair<CommandResult.Status, String>): List<RoutineEvent> {
        val base = SeatState(windowOpenPercent = 40, heaterLevel = 2, ventilationLevel = 0, doorOpen = false)
        val out = mutableListOf<RoutineEvent>()
        out += e.onSnapshot(snap(t0, base.copy(occupied = true, beltBuckled = true)))
        out += e.onSnapshot(snap(t0 + 5_000, base.copy(occupied = true, beltBuckled = true)))
        out += e.onSnapshot(snap(t0 + 10_000, base.copy(occupied = false, beltBuckled = false)))
        out += e.onSnapshot(snap(t0 + 11_000, base.copy(occupied = false, doorOpen = true)))
        val exec = e.onSnapshot(snap(t0 + 12_000, base.copy(occupied = false, doorOpen = false))).also { out += it }
            .filterIsInstance<RoutineEvent.Execute>().single()
        out += e.onJobFinished(seat, exec.commands.map { c -> statusOf(c).let { CommandResult(c, it.first, it.second) } }, snap(t0 + 20_000, base))
        return out
    }

    @Test
    fun `같은 실패가 10분 안에 되풀이돼도 창문 중단 안내는 억제되지 않는다`() {
        val e = CabinRoutineEngine()
        // 1번 손님: 창문·열선 모두 실패 → 실패 보고(열선 실패가 기록됨)
        val first = alight(e, 0) { CommandResult.Status.FAILED to "" }.filterIsInstance<RoutineEvent.CleanupNotice>()
        assertEquals(1, first.size)
        // 2번 손님(1분 뒤): 창문은 닫다가 멈춤, 열선은 같은 실패. 실제로 된 일은 없다.
        val second = alight(e, 60_000) { c -> if (c is VehicleCommand.SetWindow) CommandResult.Status.ABORTED to "DOOR" else CommandResult.Status.FAILED to "" }
            .filterIsInstance<RoutineEvent.CleanupNotice>()
        assertEquals("중단 안내가 나가야 한다", 1, second.size)
        assertTrue(second.single().text, second.single().text.contains("조수석 창문을 닫다가 문이 열려 멈췄어요."))
    }

    @Test
    fun `같은 실패만 되풀이한 보고는 여전히 억제한다`() {
        val e = CabinRoutineEngine()
        alight(e, 0) { CommandResult.Status.FAILED to "" }
        val second = alight(e, 60_000) { CommandResult.Status.FAILED to "" }.filterIsInstance<RoutineEvent.CleanupNotice>()
        assertTrue(second.isEmpty())
        assertEquals(1, e.stats.suppressedAlerts)
    }

    @Test
    fun `정지 쓰기까지 실패하면 문구에 직접 확인을 붙인다`() {
        val e = CabinRoutineEngine()
        val n = alight(e, 0) { c -> if (c is VehicleCommand.SetWindow) CommandResult.Status.ABORTED to "DOOR:STOPFAIL" else CommandResult.Status.OK to "" }
            .filterIsInstance<RoutineEvent.CleanupNotice>().single()
        assertTrue(n.text, n.text.contains("조수석 창문을 닫다가 문이 열려 멈췄어요. 정지 명령이 실패했어요. 직접 확인해 주세요."))
    }
}
