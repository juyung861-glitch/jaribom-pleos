package com.jaribom.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 5차 심사 F1: [다시 시도]는 알림별로 자기 실패만, 그사이 다시 탄 좌석은 버린다. */
class RetryBookTest {

    private val left = SeatId.ROW_2_LEFT
    private val right = SeatId.ROW_2_RIGHT

    private fun snap(t: Long, s: SeatState) = VehicleSnapshot(
        seats = SeatId.entries.associateWith { SeatState() } + (left to s), gear = Gear.PARK, timestampMs = t,
    )

    @Test
    fun `예전 알림 버튼은 자기 알림의 실패만 실행한다`() {
        val book = RetryBook()
        book.record(1, mapOf(left to listOf<VehicleCommand>(VehicleCommand.SetWindow(left, 0))))
        book.record(2, mapOf(right to listOf<VehicleCommand>(VehicleCommand.SetSeatHeater(right, 0))))
        assertEquals(setOf(left), book.take(1) { true }.keys)
        assertTrue(book.has(2))
        assertFalse(book.has(1))
        assertEquals(setOf(right), book.take(2) { true }.keys)
    }

    @Test
    fun `잠금 복원이 실패한 뒤 다시 타면 다시 시도해도 잠금을 풀지 않는다`() {
        val e = CabinRoutineEngine().apply { childSeats = setOf(left) }
        val book = RetryBook()
        val unlocked = SeatState(windowOpenPercent = 0, heaterLevel = 0, ventilationLevel = 0, windowLocked = false)
        e.onSnapshot(snap(0, unlocked.copy(occupied = true, beltBuckled = true)))
        val locked = unlocked.copy(windowLocked = true)
        e.onSnapshot(snap(10_000, locked.copy(occupied = false, beltBuckled = false)))
        e.onSnapshot(snap(11_000, locked.copy(doorOpen = true)))
        val restore = e.onSnapshot(snap(12_000, locked.copy(doorOpen = false))).filterIsInstance<RoutineEvent.Execute>().single()
        assertEquals(listOf<VehicleCommand>(VehicleCommand.SetWindowLock(left, false)), restore.commands)
        val notice = e.onJobFinished(left, restore.commands.map { CommandResult(it, CommandResult.Status.FAILED) }, snap(20_000, locked))
            .filterIsInstance<RoutineEvent.CleanupNotice>().single()
        book.record(notice.key, notice.failed)

        // 아이가 다시 탄다(문 열고 앉고 닫음 → 탑승 확정)
        e.onSnapshot(snap(30_000, locked.copy(doorOpen = true)))
        e.onSnapshot(snap(31_000, locked.copy(doorOpen = true, occupied = true)))
        e.onSnapshot(snap(32_000, locked.copy(doorOpen = false, occupied = true)))
        e.onSnapshot(snap(36_000, locked.copy(doorOpen = false, occupied = true)))

        val retry = book.take(notice.key, e::cleanupStillValid)
        assertTrue("다시 탄 좌석의 실패는 버린다: $retry", retry.isEmpty())
        // 쓰기 직전 안전 게이트도 막는다
        assertFalse(SafetyGate.check(VehicleCommand.SetWindowLock(left, false), snap(36_000, locked.copy(occupied = true))).allowed)
    }
}
