package com.jaribom.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** F6-2: AAOS GEAR_SELECTION(VehicleGear 상수) → 앱의 [Gear]. P/R/N/D 외 값은 "모름"이고, 모름이면 정차로 보지 않는다. */
class GearMappingTest {

    @Test
    fun `P R N D 상수를 매핑한다`() {
        assertEquals(Gear.PARK, GearMapping.fromCar(GearMapping.CAR_GEAR_PARK))
        assertEquals(Gear.REVERSE, GearMapping.fromCar(GearMapping.CAR_GEAR_REVERSE))
        assertEquals(Gear.NEUTRAL, GearMapping.fromCar(GearMapping.CAR_GEAR_NEUTRAL))
        assertEquals(Gear.DRIVE, GearMapping.fromCar(GearMapping.CAR_GEAR_DRIVE))
    }

    @Test
    fun `P R N D 가 아닌 값은 모름 - 숫자 기어·알 수 없는 값·null`() {
        assertEquals(Gear.UNKNOWN, GearMapping.fromCar(0x10))     // GEAR_FIRST: 예전에는 D 로 봤지만 이제 모름
        assertEquals(Gear.UNKNOWN, GearMapping.fromCar(999))
        assertEquals(Gear.UNKNOWN, GearMapping.fromCar(0))
        assertEquals(Gear.UNKNOWN, GearMapping.fromCar(null))
    }

    @Test
    fun `모름 기어는 속도 신호가 없으면 정차로 보지 않는다`() {
        val s = VehicleSnapshot(gear = GearMapping.fromCar(999), timestampMs = 5_000)
        assertFalse(VehicleMotion.isStopped(s))
        assertFalse(VehicleMotion.voiceAllowed(s))
    }
}
