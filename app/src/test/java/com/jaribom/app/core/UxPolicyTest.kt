package com.jaribom.app.core

import com.jaribom.app.vehicle.FakeVehicleGateway
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 6차 F6-3 (B안): 안전 판정은 자체 판정이 1차, CarUxRestrictionsManager 는 화면 편집 제한에만 쓴다. */
class UxPolicyTest {

    private fun snap(gear: Gear) = VehicleSnapshot(gear = gear, timestampMs = 10_000L)

    @Test
    fun `매니저가 없으면 제한을 더하지 않는다 - 정차 중 편집 허용`() {
        val locked = UxPolicy.screenLocked(carUx = null, snap = snap(Gear.PARK))
        assertFalse(locked)
        assertTrue(UxPolicy.canEdit(locked))
    }

    @Test
    fun `매니저가 주의 분산 최적화를 요구하면 정차 중이어도 편집을 막는다`() {
        val locked = UxPolicy.screenLocked(carUx = true, snap = snap(Gear.PARK))
        assertTrue(locked)
        assertFalse(UxPolicy.canEdit(locked))
    }

    @Test
    fun `자체 판정이 주행이면 매니저가 false 여도 잠근다 - 안전은 자체 판정이 1차`() {
        assertTrue(UxPolicy.screenLocked(carUx = false, snap = snap(Gear.DRIVE)))
        assertTrue(UxPolicy.screenLocked(carUx = null, snap = snap(Gear.DRIVE)))
    }

    @Test
    fun `제한 중이면 알림 문구는 짧은 판 - 첫 줄만, 아니면 그대로`() {
        val long = "조수석 창문을 닫고 열선을 껐어요.\n이번 달 창문 닫음 3 · 열선 끔 2"
        assertEquals("조수석 창문을 닫고 열선을 껐어요.", UxPolicy.notificationText(long, locked = true))
        assertEquals(long, UxPolicy.notificationText(long, locked = false))
        assertEquals("정차 후 바꿀 수 있어요", UxPolicy.LOCK_MESSAGE)
    }

    @Test
    fun `주행 중에도 익명 통계 끄기는 되고 켜기는 정차 후에만 - 화면에서 스위치는 켜져 있을 때만 누를 수 있다`() {
        val driving = UxPolicy.screenLocked(carUx = null, snap = snap(Gear.DRIVE))
        assertTrue("끄기(옵트아웃)", UxPolicy.canSetTelemetry(on = false, locked = driving))
        assertFalse("켜기는 정차 후", UxPolicy.canSetTelemetry(on = true, locked = driving))
        assertTrue(UxPolicy.canSetTelemetry(on = true, locked = false))
        assertTrue(UxPolicy.canSetTelemetry(on = false, locked = false))
        // 화면: 주행 중에는 스위치가 켜져 있을 때(누르면 끔)만 누를 수 있다
        assertTrue(UxPolicy.telemetrySwitchEnabled(currentlyOn = true, locked = true))
        assertFalse(UxPolicy.telemetrySwitchEnabled(currentlyOn = false, locked = true))
        assertTrue(UxPolicy.telemetrySwitchEnabled(currentlyOn = false, locked = false))
    }

    @Test
    fun `주행 중 안내 문구는 실제 동작에 맞는다`() {
        assertEquals("주행 중에는 설정을 바꿀 수 없어요. 안내는 알림으로 남고, 달리는 중에는 동승자 음성을 쉬어요.", UxPolicy.DRIVING_NOTE)
        assertFalse(UxPolicy.DRIVING_NOTE.contains("음성으로만"))
    }

    @Test
    fun `가짜 게이트웨이의 제한 신호가 정책에 그대로 들어간다`() {
        val g = FakeVehicleGateway()
        assertNull(g.uxRestricted.value)                 // 매니저 없음 = 기본
        g.setUxRestricted(true)
        assertTrue(UxPolicy.screenLocked(g.uxRestricted.value, snap(Gear.PARK)))
        g.setUxRestricted(false)
        assertFalse(UxPolicy.screenLocked(g.uxRestricted.value, snap(Gear.PARK)))
    }
}
