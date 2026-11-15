package com.jaribom.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F8-1: 대기 중이던 동승자 하차 안내 음성이 재생 차례가 됐을 때 — 그 좌석 문이 닫힌 지 5초가 넘었거나
 * 음성 금지 상태(속도 > 0.5 m/s)면 버린다. 버림은 음성 실패가 아니다(speechFailures·보고 한 줄 없음).
 */
class StalePassengerSpeechTest {

    private val seat = SeatId.ROW_1_RIGHT
    private val base = SeatState(windowOpenPercent = 30, heaterLevel = 0, ventilationLevel = 0, doorOpen = false)

    private fun snap(t: Long, s: SeatState, speed: Float? = null) = VehicleSnapshot(
        seats = SeatId.entries.associateWith { SeatState() } + (seat to s), gear = Gear.PARK, speedMps = speed,
        slowSinceMs = if (speed != null && speed <= VehicleMotion.STOP_MPS) 0L else null, timestampMs = t,
    )

    private class Run(val e: CabinRoutineEngine, val text: String, val events: MutableList<RoutineEvent>)

    /** 탑승 → 일어남 → 문 열림(동승자 안내 음성 큐에 들어감). 문이 아직 열린 상태. */
    private fun openDoor(): Run {
        val e = CabinRoutineEngine()
        val out = mutableListOf<RoutineEvent>()
        out += e.onSnapshot(snap(0, base.copy(occupied = true, beltBuckled = true)))
        out += e.onSnapshot(snap(5_000, base.copy(occupied = true, beltBuckled = true)))
        out += e.onSnapshot(snap(10_000, base.copy(occupied = false, beltBuckled = false)))
        out += e.onSnapshot(snap(11_000, base.copy(occupied = false, doorOpen = true)))
        val text = out.filterIsInstance<RoutineEvent.Speak>().single { "두고 가시는" in it.text }.text
        return Run(e, text, out)
    }

    private fun Run.closeDoor(t: Long = 12_000) = e.onSnapshot(snap(t, base.copy(occupied = false, doorOpen = false))).also { events += it }

    @Test
    fun `문이 열린 채면 재생한다`() {
        val r = openDoor()
        assertNull(r.e.passengerSpeechDropReason(r.text, 30_000, snap(30_000, base.copy(occupied = false, doorOpen = true))))
    }

    @Test
    fun `문이 닫힌 지 5초 안이면 재생하고 6초 지났으면 버린다`() {
        val r = openDoor()
        r.closeDoor(12_000)
        val closed = base.copy(occupied = false, doorOpen = false)
        assertNull(r.e.passengerSpeechDropReason(r.text, 17_000, snap(17_000, closed)))      // 정확히 5초: 아직 재생
        assertNotNull(r.e.passengerSpeechDropReason(r.text, 18_000, snap(18_000, closed)))   // 6초: 버림
        assertEquals(5_000L, AlertPolicy.PASSENGER_SPEECH_STALE_MS)
    }

    @Test
    fun `속도가 3 m·s면 문이 열려 있어도 버린다`() {
        val r = openDoor()
        val reason = r.e.passengerSpeechDropReason(r.text, 12_000, snap(12_000, base.copy(occupied = false, doorOpen = true), speed = 3f))
        assertNotNull(reason)
    }

    @Test
    fun `버려도 정리와 운전자 보고는 정상이고 음성 실패로 세지 않는다`() {
        val r = openDoor()
        val exec = r.closeDoor(12_000).filterIsInstance<RoutineEvent.Execute>().single()
        r.events += r.e.onPassengerSpeechDropped(r.text, "문이 닫힌 지 6초")
        r.events += r.e.onJobFinished(seat, exec.commands.map { CommandResult(it, CommandResult.Status.OK) }, snap(20_000, base.copy(windowOpenPercent = 0)))
        val notices = r.events.filterIsInstance<RoutineEvent.CleanupNotice>()
        // 정리 보고는 그대로 나간다. P 에서 음성으로 나간 성공 보고라 알림은 없다(F12-2).
        assertTrue("정리 보고가 나와야 함", r.events.any { it is RoutineEvent.Speak && "닫았어요" in it.text })
        assertTrue(notices.none { "재생되지 못했어요" in it.text })
        assertEquals(0, r.e.stats.speechFailures)
        assertTrue(r.events.filterIsInstance<RoutineEvent.Log>().any { "버림" in it.text && "문이 닫힌 지 6초" in it.text })
        // 버린 뒤 늦게 같은 문장의 실패 신호가 와도 보고에 붙지 않는다(이미 지운 항목)
        assertTrue(r.e.onSpeechFailed(r.text, "TIMEOUT").filterIsInstance<RoutineEvent.CleanupNotice>().isEmpty())
    }

    @Test
    fun `모르는 문장이나 운전자 보고 문장은 판정하지 않는다`() {
        val r = openDoor()
        assertNull(r.e.passengerSpeechDropReason("운전자 보고 문장", 99_000, snap(99_000, base.copy(doorOpen = false), speed = 5f)))
    }
}
