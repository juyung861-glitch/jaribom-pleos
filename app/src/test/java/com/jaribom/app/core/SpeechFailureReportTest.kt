package com.jaribom.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F9-4: 음성이 실패해도 전달을 보장한다.
 *  - 운전자 보고: 경고 없이 음성으로만 낸 보고는 음성이 실패하면 그때 알림 1건을 남긴다(F12-2). 중복은 없다.
 *  - 동승자 하차 안내: 실패하면 그 하차의 운전자 보고 알림 끝에 한 줄을 붙인다. 의도적으로 버린 경우(달리는 중)는 실패가 아니라 붙이지 않는다.
 *  - Stats.speechFailures 로 센다(통계 JSON 13키에는 없다).
 */
class SpeechFailureReportTest {

    private val seat = SeatId.ROW_1_RIGHT
    private val line = "(동승자 안내 음성은 재생되지 못했어요)"

    private fun snap(t: Long, s: SeatState, speed: Float? = null) = VehicleSnapshot(
        seats = SeatId.entries.associateWith { SeatState() } + (seat to s), gear = Gear.PARK, speedMps = speed,
        slowSinceMs = if (speed == null || speed <= VehicleMotion.STOP_MPS) 0L else null, timestampMs = t,
    )

    /** 탐 → 일어남 → 문 열림(동승자 안내 Speak) → 닫힘(하차 확정). 정리 결과를 돌려주기 전까지의 이벤트와 Execute. */
    private class Alight(val events: List<RoutineEvent>, val exec: RoutineEvent.Execute)

    private fun beginAlight(e: CabinRoutineEngine, speed: Float? = null): Alight {
        val base = SeatState(windowOpenPercent = 30, heaterLevel = 0, ventilationLevel = 0, doorOpen = false)
        val out = mutableListOf<RoutineEvent>()
        out += e.onSnapshot(snap(0, base.copy(occupied = true, beltBuckled = true), speed))
        out += e.onSnapshot(snap(5_000, base.copy(occupied = true, beltBuckled = true), speed))
        out += e.onSnapshot(snap(10_000, base.copy(occupied = false, beltBuckled = false), speed))
        out += e.onSnapshot(snap(11_000, base.copy(occupied = false, doorOpen = true), speed))
        val closed = e.onSnapshot(snap(12_000, base.copy(occupied = false, doorOpen = false), speed))
        out += closed
        return Alight(out, closed.filterIsInstance<RoutineEvent.Execute>().single())
    }

    private fun finish(e: CabinRoutineEngine, a: Alight) = e.onJobFinished(
        seat, a.exec.commands.map { CommandResult(it, CommandResult.Status.OK) }, snap(20_000, SeatState(windowOpenPercent = 0)),
    ).filterIsInstance<RoutineEvent.CleanupNotice>()

    private fun List<RoutineEvent>.passengerText() = filterIsInstance<RoutineEvent.Speak>().first { "두고 가시는" in it.text }.text

    private val reasons = listOf("ENGINE_ERROR", "INIT_FAILED", "TIMEOUT", "FOCUS_DENIED")

    @Test
    fun `운전자 보고 음성이 실패하면 그때 알림 1건만 남긴다 - 실패 4종`() {
        for (reason in reasons) {
            val e = CabinRoutineEngine()
            val a = beginAlight(e)
            assertTrue("$reason: 음성으로 낸 성공 보고는 알림이 없다", finish(e, a).isEmpty())
            val first = e.onSpeechFailed("조수석 창문을 닫았어요.", reason).filterIsInstance<RoutineEvent.CleanupNotice>()
            assertEquals("조수석 창문을 닫았어요.", first.single().text)
            val again = e.onSpeechFailed("조수석 창문을 닫았어요.", reason)
            assertTrue("$reason: 두 번째 알림이 생기면 안 됨", again.filterIsInstance<RoutineEvent.CleanupNotice>().isEmpty())
            assertEquals(2, e.stats.speechFailures)
        }
    }

    @Test
    fun `동승자 안내 실패가 보고 전에 오면 알림 끝에 한 줄이 붙는다`() {
        val e = CabinRoutineEngine()
        val a = beginAlight(e)
        val extra = e.onSpeechFailed(a.events.passengerText(), "ENGINE_ERROR")
        assertTrue(extra.filterIsInstance<RoutineEvent.CleanupNotice>().isEmpty())     // 아직 보고 전: 알림을 만들지 않고 표시만
        val n = finish(e, a).single()
        assertTrue(n.text, n.text.endsWith("\n$line"))
        assertEquals(1, e.stats.speechFailures)
        // 운전자 보고 음성에는 이 줄이 들어가지 않는다
        assertFalse(e.lastResult!!.report.contains("재생되지 못했어요"))
    }

    @Test
    fun `동승자 안내 실패가 보고 뒤에 오면 같은 알림을 한 줄 붙여 갱신한다 - 두 번 붙지 않는다`() {
        val e = CabinRoutineEngine()
        val a = beginAlight(e)
        assertTrue(finish(e, a).isEmpty())                       // 음성으로 낸 성공 보고: 알림 없음(F12-2)
        val updated = e.onSpeechFailed(a.events.passengerText(), "TIMEOUT").filterIsInstance<RoutineEvent.CleanupNotice>().single()
        assertTrue(updated.text.startsWith("조수석 창문을 닫았어요."))
        assertTrue(updated.text, updated.text.endsWith("\n$line"))
        assertTrue(e.onSpeechFailed(a.events.passengerText(), "TIMEOUT").filterIsInstance<RoutineEvent.CleanupNotice>().isEmpty())
        assertEquals(2, e.stats.speechFailures)
    }

    @Test
    fun `달리는 중이라 의도적으로 버린 동승자 안내는 실패가 아니라 한 줄이 없다`() {
        val e = CabinRoutineEngine()
        val a = beginAlight(e, speed = 3f)
        assertTrue("버린 안내는 Speak 가 없다", a.events.none { it is RoutineEvent.Speak && "두고 가시는" in it.text })
        assertTrue(finish(e, a).none { it.text.contains("재생되지 못했어요") })
        assertFalse(e.lastResult!!.report.contains("재생되지 못했어요"))
        assertEquals(0, e.stats.speechFailures)
    }
}
