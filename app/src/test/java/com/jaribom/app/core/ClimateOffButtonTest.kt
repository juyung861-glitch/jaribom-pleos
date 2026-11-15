package com.jaribom.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F9-2: 남은 물건·아이 좌석·택시 모드 알림에, 그 좌석 열선이나 통풍이 켜져 있을 때만 [열선 끄기] 버튼을 붙인다.
 * 누르면 열선·통풍 끄기만 하고 창문은 건드리지 않는다. 자동으로 끄지 않는다(운전자가 고른다).
 */
class ClimateOffButtonTest {

    private val left = SeatId.ROW_2_LEFT
    private val right = SeatId.ROW_2_RIGHT

    private fun snap(t: Long, gear: Gear, seats: Map<SeatId, SeatState>) = VehicleSnapshot(
        seats = SeatId.entries.associateWith { SeatState() } + seats, gear = gear, timestampMs = t,
    )

    /** 택시 모드 패턴(탐 → 벨트 풂 → 문 열림→닫힘 → P→D). 출발 때 각 좌석의 열선·통풍 단계는 [climate] 로 준다. */
    private fun depart(e: CabinRoutineEngine, climate: Map<SeatId, Pair<Int, Int>>): List<RoutineEvent.CleanupNotice> {
        val seats = climate.keys.toList()
        fun state(open: Boolean, c: Pair<Int, Int>) = SeatState(
            occupied = true, beltBuckled = false, doorOpen = open, windowOpenPercent = 0, heaterLevel = c.first, ventilationLevel = c.second,
        )
        val out = mutableListOf<RoutineEvent>()
        fun frame(t: Long, gear: Gear, open: Boolean) { out += e.onSnapshot(snap(t, gear, seats.associateWith { state(open, climate.getValue(it)) })) }
        out += e.onSnapshot(snap(0, Gear.PARK, seats.associateWith { state(false, climate.getValue(it)).copy(beltBuckled = true) }))
        for (t in 500L..5_000L step 500) frame(t, Gear.PARK, false)
        frame(7_000, Gear.PARK, true)
        frame(8_000, Gear.PARK, false)
        frame(9_000, Gear.DRIVE, false)
        return out.filterIsInstance<RoutineEvent.CleanupNotice>().filter { it.title?.endsWith("확인 필요") == true }
    }

    private fun engine() = CabinRoutineEngine().apply { taxiMode = true }

    @Test
    fun `열선이 켜져 있으면 버튼 대상이 있고 그 좌석 열선 끄기뿐이다 - 창문 명령 0번`() {
        val n = depart(engine(), mapOf(right to (2 to 0))).single()
        assertEquals(listOf<VehicleCommand>(VehicleCommand.SetSeatHeater(right, 0)), n.climateOff)
        assertTrue(n.climateOff.none { it is VehicleCommand.SetWindow })
    }

    @Test
    fun `통풍만 켜져 있으면 통풍 끄기, 둘 다면 둘 다`() {
        assertEquals(listOf<VehicleCommand>(VehicleCommand.SetSeatVentilation(right, 0)), depart(engine(), mapOf(right to (0 to 3))).single().climateOff)
        assertEquals(
            listOf<VehicleCommand>(VehicleCommand.SetSeatHeater(right, 0), VehicleCommand.SetSeatVentilation(right, 0)),
            depart(engine(), mapOf(right to (1 to 2))).single().climateOff,
        )
    }

    @Test
    fun `열선·통풍이 꺼져 있으면 버튼이 없다`() {
        assertTrue(depart(engine(), mapOf(right to (0 to 0))).single().climateOff.isEmpty())
    }

    @Test
    fun `두 좌석 알림의 버튼은 서로 다른 좌석을 끄지 않는다`() {
        val notices = depart(engine(), mapOf(left to (3 to 0), right to (0 to 2)))
        assertEquals(2, notices.size)
        assertEquals(2, notices.map { it.key }.toSet().size)
        for (n in notices) {
            val seat = n.seats.single()
            assertTrue(n.climateOff.isNotEmpty() && n.climateOff.all { it.seat == seat })
        }
        assertEquals(listOf<VehicleCommand>(VehicleCommand.SetSeatHeater(left, 0)), notices.single { it.seats == listOf(left) }.climateOff)
        assertEquals(listOf<VehicleCommand>(VehicleCommand.SetSeatVentilation(right, 0)), notices.single { it.seats == listOf(right) }.climateOff)
    }

    @Test
    fun `끄기 성공이면 같은 알림을 껐어요로 갱신하고 버튼을 없앤다 - 센다`() {
        val e = engine()
        val n = depart(e, mapOf(right to (2 to 0))).single()
        val r = e.onClimateOffResult(n.key, n.climateOff.map { CommandResult(it, CommandResult.Status.OK) }).single()
        assertEquals(n.key, r.key)
        assertEquals("뒷좌석 오른쪽 열선을 껐어요.", r.text)
        assertTrue(r.climateOff.isEmpty())
        assertEquals(1, e.stats.leftoverHeatOffs)
    }

    @Test
    fun `끄기 실패면 실패 문구와 함께 같은 버튼을 남긴다(다시 시도) - 누른 횟수에는 센다(F10-3)`() {
        val e = engine()
        val n = depart(e, mapOf(right to (2 to 1))).single()
        val r = e.onClimateOffResult(n.key, n.climateOff.map { CommandResult(it, CommandResult.Status.FAILED) }).single()
        assertTrue(r.text, r.text.contains("뒷좌석 오른쪽 열선·통풍을 끄지 못했어요. 직접 확인해 주세요."))
        assertEquals(n.climateOff, r.climateOff)
        // F10-3: leftoverHeatOffs 는 통계 heatOffTaps(눌러서 실행한 횟수)라 실패도 센다. 예전에는 껐을 때만 셌다.
        assertEquals(1, e.stats.leftoverHeatOffs)
    }
}
