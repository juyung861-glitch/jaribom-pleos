package com.jaribom.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 5차 심사 F1: 아이 좌석 잠금 안전(잠금 실패 보고, 사람이 앉은 좌석 잠금 해제 금지). */
class ChildLockSafetyTest {

    private val left = SeatId.ROW_2_LEFT

    private fun snap(t: Long, s: SeatState, gear: Gear = Gear.PARK) = VehicleSnapshot(
        seats = SeatId.entries.associateWith { SeatState() } + (left to s), gear = gear, timestampMs = t,
    )

    private val unlocked = SeatState(windowOpenPercent = 0, heaterLevel = 0, ventilationLevel = 0, windowLocked = false)

    private fun boardChild(e: CabinRoutineEngine): RoutineEvent.Execute =
        e.onSnapshot(snap(0, unlocked.copy(occupied = true, beltBuckled = true))).filterIsInstance<RoutineEvent.Execute>().single()

    @Test
    fun `탑승 때 잠금이 실패하면 운전자에게 알린다`() {
        val e = CabinRoutineEngine().apply { childSeats = setOf(left) }
        val preset = boardChild(e)
        val results = preset.commands.map { CommandResult(it, CommandResult.Status.FAILED) }
        val out = e.onPresetFinished(preset.jobId, left, results)
        val notice = out.filterIsInstance<RoutineEvent.CleanupNotice>().singleOrNull()
        assertTrue(out.toString(), notice != null)
        assertEquals("뒷좌석 왼쪽 창문 잠금에 실패했어요. 직접 잠가 주세요.", notice!!.text)
    }

    @Test
    fun `잠금 실패 음성은 P 일 때만, 알림은 항상`() {
        for (gear in listOf(Gear.PARK, Gear.DRIVE)) {
            val e = CabinRoutineEngine().apply { childSeats = setOf(left) }
            val preset = boardChild(e)
            val out = e.onPresetFinished(preset.jobId, left, preset.commands.map { CommandResult(it, CommandResult.Status.FAILED) }, snap(5_000, unlocked.copy(occupied = true), gear))
            assertEquals(gear.toString(), gear == Gear.PARK, out.any { it is RoutineEvent.Speak })
            assertTrue(out.any { it is RoutineEvent.CleanupNotice })
        }
    }

    @Test
    fun `사람이 앉아 있으면 창문 잠금 해제를 거부한다`() {
        val seated = snap(0, SeatState(occupied = true, windowLocked = true))
        val v = SafetyGate.check(VehicleCommand.SetWindowLock(left, false), seated)
        assertFalse(v.allowed)
        assertEquals("사람이 앉아 있음", v.reason)
        // 잠그는 것은 앉아 있어도 허용(아이가 탄 상태에서 잠그는 것이 목적)
        assertTrue(SafetyGate.check(VehicleCommand.SetWindowLock(left, true), seated).allowed)
    }
}
