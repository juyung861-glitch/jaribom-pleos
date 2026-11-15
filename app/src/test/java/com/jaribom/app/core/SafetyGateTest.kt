package com.jaribom.app.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SafetyGateTest {
    private fun snap(seat: SeatId, s: SeatState, gear: Gear = Gear.PARK) = VehicleSnapshot(
        seats = SeatId.entries.associateWith { SeatState() } + (seat to s), gear = gear,
    )

    @Test
    fun `운전석 명령은 무조건 거부`() {
        val v = SafetyGate.check(VehicleCommand.SetWindow(SeatId.ROW_1_LEFT, 0), snap(SeatId.ROW_1_LEFT, SeatState()))
        assertFalse(v.allowed)
    }

    @Test
    fun `사람이 앉아 있으면 창문 닫기 거부`() {
        val v = SafetyGate.check(
            VehicleCommand.SetWindow(SeatId.ROW_2_LEFT, 0),
            snap(SeatId.ROW_2_LEFT, SeatState(occupied = true, windowOpenPercent = 50)),
        )
        assertFalse(v.allowed)
    }

    @Test
    fun `문이 열려 있으면 창문 닫기 거부, 여는 건 허용`() {
        val s = snap(SeatId.ROW_2_LEFT, SeatState(occupied = false, doorOpen = true, windowOpenPercent = 50))
        assertFalse(SafetyGate.check(VehicleCommand.SetWindow(SeatId.ROW_2_LEFT, 0), s).allowed)
        assertTrue(SafetyGate.check(VehicleCommand.SetWindow(SeatId.ROW_2_LEFT, 80), s).allowed)
    }

    @Test
    fun `기어 D 에서도 열선·통풍과 빈 좌석 창문 닫기는 허용`() {
        // A1(4차 심사): 주행 중 손대지 않는 것은 운전 기능과 도어뿐이다. 편의 기능은 기어 조건이 없다.
        val s = snap(SeatId.ROW_2_LEFT, SeatState(occupied = false, doorOpen = false, windowOpenPercent = 50), gear = Gear.DRIVE)
        assertTrue(SafetyGate.check(VehicleCommand.SetWindow(SeatId.ROW_2_LEFT, 0), s).allowed)
        assertTrue(SafetyGate.check(VehicleCommand.SetSeatHeater(SeatId.ROW_2_LEFT, 0), s).allowed)
        assertTrue(SafetyGate.check(VehicleCommand.SetSeatHeater(SeatId.ROW_2_LEFT, 2), s).allowed)
        assertTrue(SafetyGate.check(VehicleCommand.SetSeatVentilation(SeatId.ROW_2_LEFT, 0), s).allowed)
    }

    @Test
    fun `기어 D 에서도 운전석·관리 꺼짐·사람 있는 좌석·문 열린 좌석 창문 닫기는 거부`() {
        val d = Gear.DRIVE
        assertFalse(SafetyGate.check(VehicleCommand.SetSeatHeater(SeatId.ROW_1_LEFT, 0), snap(SeatId.ROW_1_LEFT, SeatState(), d)).allowed)
        val empty = snap(SeatId.ROW_2_LEFT, SeatState(occupied = false, windowOpenPercent = 50), d)
        assertFalse(SafetyGate.check(VehicleCommand.SetSeatHeater(SeatId.ROW_2_LEFT, 0), empty, managedSeats = setOf(SeatId.ROW_1_RIGHT)).allowed)
        val seated = snap(SeatId.ROW_2_LEFT, SeatState(occupied = true, windowOpenPercent = 50), d)
        assertFalse(SafetyGate.check(VehicleCommand.SetWindow(SeatId.ROW_2_LEFT, 0), seated).allowed)
        val open = snap(SeatId.ROW_2_LEFT, SeatState(occupied = false, doorOpen = true, windowOpenPercent = 50), d)
        assertFalse(SafetyGate.check(VehicleCommand.SetWindow(SeatId.ROW_2_LEFT, 0), open).allowed)
    }

    @Test
    fun `창문 잠금은 운전석·관리 꺼짐만 거부하고 사람·문 상태와 무관하다`() {
        // 잠금은 창이 움직이지 않아 끼임 조건이 없다. 아이가 앉아 있을 때 잠그는 것이 목적이다.
        val seated = snap(SeatId.ROW_2_LEFT, SeatState(occupied = true, doorOpen = true, windowLocked = false), Gear.DRIVE)
        assertTrue(SafetyGate.check(VehicleCommand.SetWindowLock(SeatId.ROW_2_LEFT, true), seated).allowed)
        assertFalse(SafetyGate.check(VehicleCommand.SetWindowLock(SeatId.ROW_1_LEFT, true), snap(SeatId.ROW_1_LEFT, SeatState())).allowed)
        assertFalse(SafetyGate.check(VehicleCommand.SetWindowLock(SeatId.ROW_2_LEFT, true), seated, managedSeats = setOf(SeatId.ROW_1_RIGHT)).allowed)
    }

    @Test
    fun `관리를 꺼 둔 좌석은 제어하지 않는다`() {
        val s = snap(SeatId.ROW_2_LEFT, SeatState(occupied = false, windowOpenPercent = 50))
        val v = SafetyGate.check(VehicleCommand.SetWindow(SeatId.ROW_2_LEFT, 0), s, managedSeats = setOf(SeatId.ROW_1_RIGHT))
        assertFalse(v.allowed)
        assertTrue(v.reason.contains("관리"))
    }
}