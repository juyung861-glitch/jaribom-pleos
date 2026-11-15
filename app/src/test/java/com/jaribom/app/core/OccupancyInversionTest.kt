package com.jaribom.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 6차 F6-5: 착좌 반전(occupancyInverted)을 주행 중 운전석 신호로 한 번 판정한다. 주행 신호가 없으면 판정하지 않는다. */
class OccupancyInversionTest {

    private fun drive(gear: Gear, speedMps: Float? = null) = VehicleSnapshot(gear = gear, speedMps = speedMps, timestampMs = 0L)

    /** 주행 상태를 [OccupancyInversionDetector.CONFIRM_MS] 이상 이어 가며 같은 신호를 준다. 판정이 나오면 그 값. */
    private fun run(d: OccupancyInversionDetector, snap: VehicleSnapshot, driverRaw: Boolean?, from: Long = 0L): Boolean? {
        var out: Boolean? = null
        for (t in from..from + OccupancyInversionDetector.CONFIRM_MS step 500) {
            out = d.observe(t, VehicleMotion.isDriving(snap), driverRaw) ?: out
        }
        return out
    }

    @Test
    fun `주행(D) 중 운전석이 비어 있다고 읽히면 반전으로 판정한다`() {
        // 앉아 있는 운전자가 '비어 있음(false)'으로 읽힌다 = 이 차는 착좌값이 거꾸로다
        assertEquals(true, run(OccupancyInversionDetector(), drive(Gear.DRIVE), driverRaw = false))
    }

    @Test
    fun `주행(D) 중 운전석이 앉음으로 읽히면 반전이 아니다`() {
        assertEquals(false, run(OccupancyInversionDetector(), drive(Gear.DRIVE), driverRaw = true))
    }

    @Test
    fun `P 만 있고 주행 신호가 없으면 판정하지 않는다`() {
        val d = OccupancyInversionDetector()
        assertNull(run(d, drive(Gear.PARK), driverRaw = false))
        assertNull(run(d, drive(Gear.PARK), driverRaw = true))
    }

    @Test
    fun `속도 신호가 있으면 속도로 주행을 본다 - 한 번만 판정하고 신호가 없으면 기다린다`() {
        val d = OccupancyInversionDetector()
        assertNull(run(d, drive(Gear.PARK, speedMps = 0.2f), driverRaw = false))        // 느림 = 주행 아님
        assertNull(d.observe(10_000, driving = true, driverRaw = null))                  // 운전석 신호 없음 = 근거 없음
        assertEquals(true, run(d, drive(Gear.DRIVE, speedMps = 8f), driverRaw = false, from = 10_500))
        assertNull(run(d, drive(Gear.DRIVE, speedMps = 8f), driverRaw = true, from = 20_000))   // 이미 판정함 - 다시 바꾸지 않는다
    }

    @Test
    fun `주행이 끊기면 확인 시간을 처음부터 다시 센다`() {
        val d = OccupancyInversionDetector()
        assertNull(d.observe(0, true, false))
        assertNull(d.observe(1_000, false, false))                                       // 멈춤 → 초기화
        assertNull(d.observe(2_000, true, false))
        assertNull(d.observe(2_000 + OccupancyInversionDetector.CONFIRM_MS - 1, true, false))
        assertEquals(true, d.observe(2_000 + OccupancyInversionDetector.CONFIRM_MS, true, false))
    }

    @Test
    fun `저장된 판정이 있으면 다시 판정하지 않는다`() {
        val d = OccupancyInversionDetector(alreadyDecided = true)
        assertNull(run(d, drive(Gear.DRIVE), driverRaw = false))
    }

    @Test
    fun `유효값 - 디버그 수동 지정이 저장 판정보다 먼저, 그다음 저장 판정, 없으면 기본값`() {
        assertEquals(false, OccupancyInversionDetector.effective(override = false, decided = true, default = true))
        assertEquals(true, OccupancyInversionDetector.effective(override = null, decided = true, default = false))
        assertEquals(true, OccupancyInversionDetector.effective(override = null, decided = null, default = true))
        assertEquals(false, OccupancyInversionDetector.effective(override = null, decided = null, default = false))
    }
}
