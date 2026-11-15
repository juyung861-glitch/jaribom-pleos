package com.jaribom.app.core

import com.jaribom.app.vehicle.FakeVehicleGateway
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** F6-1: 주행 판정은 속도가 있으면 속도(1초 유지), 없으면 기어(P/N=정차, D/R=주행)로 떨어진다. 한 곳은 [VehicleMotion]. */
class VehicleMotionTest {

    private fun snap(gear: Gear, speed: Float? = null, slowSince: Long? = null, t: Long = 10_000) =
        VehicleSnapshot(gear = gear, speedMps = speed, slowSinceMs = slowSince, timestampMs = t)

    @Test
    fun `D 이고 속도 0이 1초 이상 유지되면 정차이지만 음성은 P 에서만`() {
        val s = snap(Gear.DRIVE, speed = 0f, slowSince = 8_000)
        assertTrue(VehicleMotion.isStopped(s))
        assertFalse(VehicleMotion.voiceAllowed(s))
    }

    @Test
    fun `P 인데 속도 3 m_s 면 주행이라 음성을 허용하지 않는다`() {
        val s = snap(Gear.PARK, speed = 3f)
        assertFalse(VehicleMotion.isStopped(s))
        assertTrue(VehicleMotion.isDriving(s))
        assertFalse(VehicleMotion.voiceAllowed(s))
    }

    @Test
    fun `속도 null 이면 기어로 판정 - D 는 주행 P 는 정차 N 은 정차 모름은 정차 아님`() {
        assertFalse(VehicleMotion.isStopped(snap(Gear.DRIVE)))
        assertTrue(VehicleMotion.isDriving(snap(Gear.DRIVE)))
        assertTrue(VehicleMotion.isStopped(snap(Gear.PARK)))
        assertTrue(VehicleMotion.voiceAllowed(snap(Gear.PARK)))
        assertTrue(VehicleMotion.isStopped(snap(Gear.NEUTRAL)))
        assertFalse(VehicleMotion.voiceAllowed(snap(Gear.NEUTRAL)))
        assertFalse(VehicleMotion.isStopped(snap(Gear.UNKNOWN)))
        assertFalse(VehicleMotion.isDriving(snap(Gear.UNKNOWN)))
    }

    @Test
    fun `경계 0_5 m_s 는 1초 유지해야 정차 - 999ms 는 아직, 1000ms 부터 정차, 0_51 은 주행`() {
        assertFalse(VehicleMotion.isStopped(snap(Gear.PARK, speed = 0.5f, slowSince = 9_001)))   // 999ms
        assertTrue(VehicleMotion.isStopped(snap(Gear.PARK, speed = 0.5f, slowSince = 9_000)))    // 1000ms
        assertFalse(VehicleMotion.isStopped(snap(Gear.PARK, speed = 0.51f, slowSince = 5_000)))
        assertTrue(VehicleMotion.isDriving(snap(Gear.PARK, speed = 0.51f)))
        assertTrue(VehicleMotion.isStopped(snap(Gear.PARK, speed = -0.4f, slowSince = 5_000)))   // 후진 쪽 부호도 절댓값
    }

    @Test
    fun `속도 추적기는 느려진 시각을 기억하고 빨라지면 지운다`() {
        val t = MotionTracker()
        assertNull(t.update(null, 1_000))
        assertEquals(2_000L, t.update(0.2f, 2_000))
        assertEquals(2_000L, t.update(0.5f, 2_600))       // 계속 느림 → 처음 시각 유지
        assertNull(t.update(0.6f, 3_000))                 // 빨라짐 → 지움
        assertEquals(3_500L, t.update(0.0f, 3_500))       // 다시 느려짐 → 새로 시작
        assertNull(t.update(null, 4_000))                 // 신호 끊김 → 기어로 떨어진다
    }

    @Test
    fun `가짜 차량에서 속도가 있으면 기어 D 여도 정차 판정이 되고 속도를 지우면 기어로 돌아간다`() {
        val f = FakeVehicleGateway()
        f.simulateGearSelected(Gear.DRIVE)
        assertFalse(VehicleMotion.isStopped(f.snapshot.value))                  // 속도 없음 + D = 주행
        f.setSpeedMps(0f, nowMs = 1_000)
        assertFalse(VehicleMotion.isStopped(f.snapshot.value))                  // 아직 1초 안 됨
        f.setSpeedMps(0f, nowMs = 2_100)
        assertTrue(VehicleMotion.isStopped(f.snapshot.value))
        f.setSpeedMps(null)
        assertFalse(VehicleMotion.isStopped(f.snapshot.value))                  // 다시 D = 주행
    }

    private val seat = SeatId.ROW_1_RIGHT

    @Test
    fun `P 인데 움직이면 운전자 보고는 알림만 - 음성 없음`() {
        val e = CabinRoutineEngine()
        fun s(t: Long, st: SeatState, speed: Float?, slow: Long? = null) = VehicleSnapshot(
            seats = SeatId.entries.associateWith { SeatState() } + (seat to st), gear = Gear.PARK,
            speedMps = speed, slowSinceMs = slow, timestampMs = t,
        )
        val sit = SeatState(occupied = true, beltBuckled = true, windowOpenPercent = 40, heaterLevel = 0, ventilationLevel = 0)
        e.onSnapshot(s(0, sit, 0f, 0))
        val off = sit.copy(occupied = false, beltBuckled = false)
        e.onSnapshot(s(10_000, off, 0f, 0))
        e.onSnapshot(s(11_000, off.copy(doorOpen = true), 0f, 0))
        val exec = e.onSnapshot(s(12_000, off.copy(doorOpen = false), 0f, 0)).filterIsInstance<RoutineEvent.Execute>().single()
        val after = s(16_000, off.copy(windowOpenPercent = 0), speed = 3f)                        // 견인·경사로 굴러감
        val out = e.onJobFinished(seat, exec.commands.map { CommandResult(it, CommandResult.Status.OK) }, after)
        assertTrue(out.filterIsInstance<RoutineEvent.Speak>().isEmpty())
        assertTrue(out.filterIsInstance<RoutineEvent.CleanupNotice>().isNotEmpty())
    }
}
