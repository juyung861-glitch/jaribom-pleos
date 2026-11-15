package com.jaribom.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 5차 심사 F4: 기록 지표(아이 좌석 잠금, 남은 물건 알림, 아이 말투 안내). */
class StatsCountTest {

    private val left = SeatId.ROW_2_LEFT
    private val right = SeatId.ROW_2_RIGHT

    private fun snap(t: Long, vararg s: Pair<SeatId, SeatState>) = VehicleSnapshot(
        seats = SeatId.entries.associateWith { SeatState(occupied = false, beltBuckled = false, doorOpen = false) } + s,
        gear = Gear.PARK, timestampMs = t,
    )

    private val base = SeatState(windowOpenPercent = 0, heaterLevel = 0, ventilationLevel = 0, windowLocked = false, doorOpen = false)

    @Test
    fun `아이 좌석 잠금과 아이 말투 기본 멘트를 센다`() {
        val e = CabinRoutineEngine().apply { childSeats = setOf(left) }
        val preset = e.onSnapshot(snap(0, left to base.copy(occupied = true, beltBuckled = true))).filterIsInstance<RoutineEvent.Execute>().single()
        e.onPresetFinished(preset.jobId, left, preset.commands.map { CommandResult(it, CommandResult.Status.OK) })
        assertEquals(1, e.stats.childLocks)
        val locked = base.copy(windowLocked = true)
        e.onSnapshot(snap(10_000, left to locked))
        val opened = e.onSnapshot(snap(11_000, left to locked.copy(doorOpen = true)))
        assertTrue(opened.any { it is RoutineEvent.Speak && it.text == ExitMessageRules.CHILD_BASE_PHRASE })
        assertEquals(1, e.stats.childReminders)
        assertEquals("아이 말투 기본 멘트도 챙김 안내로 센다", 1, e.stats.reminders)
    }

    @Test
    fun `남은 물건 알림을 센다`() {
        val e = CabinRoutineEngine()
        val sit = base.copy(occupied = true, beltBuckled = true)
        e.onSnapshot(snap(0, left to sit, right to sit))
        e.onSnapshot(snap(10_000, left to base, right to sit.copy(beltBuckled = false)))
        e.onSnapshot(snap(11_000, left to base.copy(doorOpen = true), right to sit.copy(beltBuckled = false, doorOpen = true)))
        e.onSnapshot(snap(12_000, left to base, right to sit.copy(beltBuckled = false, doorOpen = true)))
        e.onSnapshot(snap(12_500, left to base, right to sit.copy(beltBuckled = false)))
        e.onSnapshot(snap(20_000, left to base, right to sit.copy(beltBuckled = false)))
        assertEquals(1, e.stats.leftoverAlerts)
    }

    @Test
    fun `이번 달 대신한 일에 키즈락 잠금을 넣고 0이면 숨긴다`() {
        // F12-4 명칭(키즈락 좌석)에 맞춘다(F12-fix2). 내부 칸 이름 childLocks 는 그대로.
        assertFalse(CabinRoutineEngine.Stats().doneSummary().contains("키즈락 잠금"))
        val s = CabinRoutineEngine.Stats(childLocks = 3)
        assertTrue(s.doneSummary(), s.doneSummary().contains("키즈락 잠금 3"))
        assertFalse(s.doneSummary().contains("아이 좌석 잠금"))
        assertEquals(3, s.doneTotal)
        assertEquals(s, (s + s) - s)
    }
}
