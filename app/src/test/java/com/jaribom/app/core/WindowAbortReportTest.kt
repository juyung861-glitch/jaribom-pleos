package com.jaribom.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** F9-1: 중단된 창문 닫기는 성공도 실패도 아니다. 운전자에게 원인별로 알리고, '대신한 일'·실패 통계에는 넣지 않는다. */
class WindowAbortReportTest {

    private val seat = SeatId.ROW_1_RIGHT

    private fun snap(t: Long, s: SeatState) = VehicleSnapshot(
        seats = SeatId.entries.associateWith { SeatState() } + (seat to s), gear = Gear.PARK, timestampMs = t,
    )

    private fun alightWith(status: CommandResult.Status, why: String): Pair<CabinRoutineEngine, List<RoutineEvent>> {
        val e = CabinRoutineEngine()
        val base = SeatState(windowOpenPercent = 40, heaterLevel = 0, ventilationLevel = 0, doorOpen = false)
        val out = mutableListOf<RoutineEvent>()
        out += e.onSnapshot(snap(0, base.copy(occupied = true, beltBuckled = true)))
        out += e.onSnapshot(snap(5_000, base.copy(occupied = true, beltBuckled = true)))
        out += e.onSnapshot(snap(10_000, base.copy(occupied = false, beltBuckled = false)))
        out += e.onSnapshot(snap(11_000, base.copy(occupied = false, doorOpen = true)))
        val exec = e.onSnapshot(snap(12_000, base.copy(occupied = false, doorOpen = false))).also { out += it }
            .filterIsInstance<RoutineEvent.Execute>().single()
        out += e.onJobFinished(seat, exec.commands.map { CommandResult(it, status, why) }, snap(20_000, base.copy(windowOpenPercent = 20)))
        return e to out
    }

    private fun List<RoutineEvent>.notice() = filterIsInstance<RoutineEvent.CleanupNotice>().single()

    @Test
    fun `원인별 문구 - 문 열림·착좌·벨트`() {
        assertTrue(alightWith(CommandResult.Status.ABORTED, "DOOR").second.notice().text.contains("조수석 창문을 닫다가 문이 열려 멈췄어요."))
        assertTrue(alightWith(CommandResult.Status.ABORTED, "SEAT").second.notice().text.contains("조수석 창문을 닫다가 사람이 앉아 멈췄어요."))
        assertTrue(alightWith(CommandResult.Status.ABORTED, "BELT").second.notice().text.contains("조수석 창문을 닫다가 벨트가 채워져 멈췄어요."))
    }

    @Test
    fun `중단은 따로 세고 대신한 일·정리 성공·실패에는 넣지 않으며 다시 시도 대상도 아니다`() {
        val (e, ev) = alightWith(CommandResult.Status.ABORTED, "DOOR")
        assertEquals(1, e.stats.windowAborts)
        assertEquals(0, e.stats.windowsClosed)
        assertEquals(0, e.stats.restores)
        assertEquals(0, e.stats.restoreFailures)
        assertEquals(0, e.stats.doneTotal)
        assertTrue("자동 재개 없음: 다시 시도 버튼 대상이 없다", ev.notice().failed.isEmpty())
    }
}
