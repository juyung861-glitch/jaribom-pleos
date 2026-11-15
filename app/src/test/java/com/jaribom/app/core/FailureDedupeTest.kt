package com.jaribom.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** F6-9 리뷰 반영: 실패 보고 10분 중복 억제가 [다시 시도]와 다른 보고 내용을 삼키지 않는다. */
class FailureDedupeTest {

    private val left = SeatId.ROW_2_LEFT

    private fun snap(t: Long, s: SeatState) = VehicleSnapshot(
        seats = SeatId.entries.associateWith { SeatState() } + (left to s), gear = Gear.PARK, timestampMs = t,
    )

    /** 아이 좌석 하차 → 잠금 복원 명령이 FAILED 로 끝난 첫 알림까지. 엔진과 복원 명령을 돌려준다. */
    private fun firstFailure(): Pair<CabinRoutineEngine, List<VehicleCommand>> {
        val e = CabinRoutineEngine().apply { childSeats = setOf(left) }
        val unlocked = SeatState(windowOpenPercent = 0, heaterLevel = 0, ventilationLevel = 0, windowLocked = false)
        e.onSnapshot(snap(0, unlocked.copy(occupied = true, beltBuckled = true)))
        val locked = unlocked.copy(windowLocked = true)
        e.onSnapshot(snap(10_000, locked.copy(occupied = false, beltBuckled = false)))
        e.onSnapshot(snap(11_000, locked.copy(doorOpen = true)))
        val restore = e.onSnapshot(snap(12_000, locked.copy(doorOpen = false))).filterIsInstance<RoutineEvent.Execute>().single()
        val n = e.onJobFinished(left, restore.commands.map { CommandResult(it, CommandResult.Status.FAILED) }, snap(20_000, locked))
            .filterIsInstance<RoutineEvent.CleanupNotice>()
        assertEquals(1, n.size)
        return e to restore.commands
    }

    private val locked = SeatState(windowOpenPercent = 0, heaterLevel = 0, ventilationLevel = 0, windowLocked = true)

    @Test
    fun `다시 시도가 또 실패하면 10분 안이어도 새 실패 알림이 나온다`() {
        val (e, cmds) = firstFailure()
        e.allowFailureReportAgain(listOf(left))            // [다시 시도] 를 누름(이전 알림은 지워짐)
        val again = e.onJobFinished(left, cmds.map { CommandResult(it, CommandResult.Status.FAILED) }, snap(60_000, locked))
            .filterIsInstance<RoutineEvent.CleanupNotice>()
        assertEquals(1, again.size)
        assertTrue(again.single().failed.isNotEmpty())
    }

    @Test
    fun `같은 실패만 반복이면 억제하지만 다른 내용이 함께 있으면 보고는 낸다`() {
        val (e, cmds) = firstFailure()
        // 같은 실패만 다시: 억제
        val dup = e.onJobFinished(left, cmds.map { CommandResult(it, CommandResult.Status.FAILED) }, snap(60_000, locked))
        assertTrue(dup.filterIsInstance<RoutineEvent.CleanupNotice>().isEmpty())
        assertEquals(1, e.stats.suppressedAlerts)
        // 같은 실패 + 실제로 된 일(창문 닫음): 보고는 낸다
        val mixed = e.onJobFinished(
            left,
            cmds.map { CommandResult(it, CommandResult.Status.FAILED) } + CommandResult(VehicleCommand.SetWindow(left, 0), CommandResult.Status.OK),
            snap(90_000, locked),
        ).filterIsInstance<RoutineEvent.CleanupNotice>()
        assertEquals(1, mixed.size)
        assertEquals(1, e.stats.suppressedAlerts)
    }
}
