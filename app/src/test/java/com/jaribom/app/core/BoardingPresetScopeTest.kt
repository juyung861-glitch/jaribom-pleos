package com.jaribom.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F12-5 탑승 프리셋 좌석 범위.
 *  - 통풍 자동 켜기는 조수석만. 뒷좌석은 capability 가 지원을 보고하고 설정 "뒷좌석 통풍도 켜기"(기본 꺼짐)일 때만.
 *  - 열선은 관리 좌석 전체. 지원 안 하는 좌석은 조용히 건너뛴다. 운전석은 원래 제어하지 않는다.
 */
class BoardingPresetScopeTest {

    private val idle = SeatState(windowOpenPercent = 0, heaterLevel = 0, ventilationLevel = 0, doorOpen = false)

    private fun boardPreset(e: CabinRoutineEngine, seat: SeatId): List<VehicleCommand> =
        e.onSnapshot(VehicleSnapshot(seats = SeatId.entries.associateWith { SeatState() } + (seat to idle.copy(occupied = true, beltBuckled = true)), gear = Gear.PARK))
            .filterIsInstance<RoutineEvent.Execute>().filter { it.preset }.flatMap { it.commands }

    private fun engine(outdoor: Float, support: Map<SeatId, ClimateSupport> = emptyMap(), rearVent: Boolean = false) = CabinRoutineEngine().apply {
        weather = WeatherInfo(outdoorTempC = outdoor); climateSupport = support; rearVentOnBoard = rearVent
    }

    private val S = SeatSignals.Support.SUPPORTED
    private val U = SeatSignals.Support.UNSUPPORTED
    private val K = SeatSignals.Support.UNKNOWN

    @Test
    fun `더운 날 통풍은 조수석만 켠다 - 뒷좌석은 기본으로 켜지 않는다`() {
        assertEquals(listOf<VehicleCommand>(VehicleCommand.SetSeatVentilation(SeatId.ROW_1_RIGHT, 2)), boardPreset(engine(30f), SeatId.ROW_1_RIGHT))
        assertTrue(boardPreset(engine(30f), SeatId.ROW_2_LEFT).isEmpty())
        assertTrue(boardPreset(engine(30f, mapOf(SeatId.ROW_2_LEFT to ClimateSupport(vent = S))), SeatId.ROW_2_LEFT).isEmpty())
    }

    @Test
    fun `뒷좌석 통풍은 설정을 켜고 capability 가 지원일 때만 켠다`() {
        val sup = mapOf(SeatId.ROW_2_RIGHT to ClimateSupport(vent = S))
        assertEquals(listOf<VehicleCommand>(VehicleCommand.SetSeatVentilation(SeatId.ROW_2_RIGHT, 2)), boardPreset(engine(30f, sup, rearVent = true), SeatId.ROW_2_RIGHT))
        // 판별 못 함(UNKNOWN)·미지원은 켜지 않는다
        assertTrue(boardPreset(engine(30f, mapOf(SeatId.ROW_2_RIGHT to ClimateSupport(vent = K)), rearVent = true), SeatId.ROW_2_RIGHT).isEmpty())
        assertTrue(boardPreset(engine(30f, mapOf(SeatId.ROW_2_RIGHT to ClimateSupport(vent = U)), rearVent = true), SeatId.ROW_2_RIGHT).isEmpty())
    }

    @Test
    fun `조수석이라도 통풍이 없으면 조용히 건너뛴다`() {
        val out = boardPreset(engine(30f, mapOf(SeatId.ROW_1_RIGHT to ClimateSupport(vent = U))), SeatId.ROW_1_RIGHT)
        assertTrue(out.isEmpty())
    }

    @Test
    fun `추운 날 열선은 관리 좌석 전체 - 지원 안 하는 좌석은 건너뛴다`() {
        for (seat in SeatId.passengers) {
            assertEquals(seat.label, listOf<VehicleCommand>(VehicleCommand.SetSeatHeater(seat, 2)), boardPreset(engine(0f), seat))
        }
        assertTrue(boardPreset(engine(0f, mapOf(SeatId.ROW_2_LEFT to ClimateSupport(heater = U))), SeatId.ROW_2_LEFT).isEmpty())
    }

    @Test
    fun `설정 표시 문구 - 통풍·열선이 없는 좌석`() {
        assertEquals("이 좌석은 통풍이 없어요", ClimateSupport(vent = U).line)
        assertEquals("이 좌석은 열선이 없어요", ClimateSupport(heater = U).line)
        assertEquals("이 좌석은 열선·통풍이 없어요", ClimateSupport(heater = U, vent = U).line)
        assertNull(ClimateSupport().line)
        assertNull(ClimateSupport(vent = K).line)
    }
}
