package com.jaribom.app.core

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F7-1: 음성은 두 종류다. 운전자 보고 = P+정차만. 동승자 하차 안내 = 문이 열릴 때 허용(문 열림 자체를 정차 증거로 봄),
 * 단 속도 값이 있고 |속도| > 0.5 m/s 면 금지(알림·기록만).
 */
class PassengerVoiceTest {

    private val seat = SeatId.ROW_1_RIGHT

    private fun snap(t: Long, gear: Gear, speed: Float?, s: SeatState) = VehicleSnapshot(
        seats = SeatId.entries.associateWith { SeatState() } + (seat to s), gear = gear, speedMps = speed,
        slowSinceMs = if (speed != null && speed <= VehicleMotion.STOP_MPS) 0L else null, timestampMs = t,
    )

    /** 탑승(벨트) → 일어남 → 문 열림(동승자 안내) → 문 닫힘(하차 확정) → 정리 결과. 기어·속도는 내내 같다. */
    private fun alight(gear: Gear, speed: Float?): List<RoutineEvent> {
        val e = CabinRoutineEngine()
        val base = SeatState(windowOpenPercent = 30, heaterLevel = 0, ventilationLevel = 0, doorOpen = false)
        val out = mutableListOf<RoutineEvent>()
        out += e.onSnapshot(snap(0, gear, speed, base.copy(occupied = true, beltBuckled = true)))
        out += e.onSnapshot(snap(5_000, gear, speed, base.copy(occupied = true, beltBuckled = true)))
        out += e.onSnapshot(snap(10_000, gear, speed, base.copy(occupied = false, beltBuckled = false)))
        out += e.onSnapshot(snap(11_000, gear, speed, base.copy(occupied = false, doorOpen = true)))
        val exec = e.onSnapshot(snap(12_000, gear, speed, base.copy(occupied = false, doorOpen = false))).also { out += it }
            .filterIsInstance<RoutineEvent.Execute>().single()
        out += e.onJobFinished(seat, exec.commands.map { CommandResult(it, CommandResult.Status.OK) }, snap(20_000, gear, speed, base.copy(windowOpenPercent = 0)))
        return out
    }

    private fun List<RoutineEvent>.passengerSpeech() = filterIsInstance<RoutineEvent.Speak>().filter { "두고 가시는" in it.text }
    private fun List<RoutineEvent>.reportSpeech() = filterIsInstance<RoutineEvent.Speak>().filter { "창문을 닫았어요" in it.text }
    private fun List<RoutineEvent>.notices() = filterIsInstance<RoutineEvent.CleanupNotice>()

    @Test
    fun `D·속도 null·문 열림 - 동승자 안내 음성 허용`() {
        assertTrue(alight(Gear.DRIVE, null).passengerSpeech().isNotEmpty())
    }

    @Test
    fun `D·속도 0 - 동승자 안내 음성 허용`() {
        assertTrue(alight(Gear.DRIVE, 0f).passengerSpeech().isNotEmpty())
    }

    @Test
    fun `D·속도 3 m·s - 동승자 안내 음성 금지, 알림과 기록은 남음`() {
        val ev = alight(Gear.DRIVE, 3f)
        assertTrue("동승자 음성이 나오면 안 됨: ${ev.passengerSpeech()}", ev.passengerSpeech().isEmpty())
        assertTrue(ev.notices().isNotEmpty())
        assertTrue(ev.filterIsInstance<RoutineEvent.Log>().any { "동승자 안내 음성 생략" in it.text })
    }

    @Test
    fun `판정은 VehicleMotion 한 곳 - 속도 null이면 허용, 0_5 이하 허용, 넘으면 금지`() {
        fun s(v: Float?) = VehicleSnapshot(gear = Gear.DRIVE, speedMps = v)
        assertTrue(VehicleMotion.passengerVoiceAllowed(s(null)))
        assertTrue(VehicleMotion.passengerVoiceAllowed(s(0.5f)))
        assertTrue(VehicleMotion.passengerVoiceAllowed(s(-0.3f)))
        assertTrue(!VehicleMotion.passengerVoiceAllowed(s(0.6f)))
        assertTrue(!VehicleMotion.passengerVoiceAllowed(s(-3f)))
        assertTrue(!VehicleMotion.voiceAllowed(s(null)))          // 운전자 보고는 D 에서 안 됨
    }

    @Test
    fun `D 에서 운전자 보고는 여전히 알림만`() {
        val ev = alight(Gear.DRIVE, null)
        assertTrue(ev.reportSpeech().isEmpty())
        assertTrue(ev.notices().isNotEmpty())
        assertTrue(alight(Gear.PARK, null).reportSpeech().isNotEmpty())
    }
}
