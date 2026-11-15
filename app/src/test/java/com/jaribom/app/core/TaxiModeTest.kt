package com.jaribom.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 6차 F6-8: 택시 모드 — 아이 좌석 보조 판정(F3)을 운전석 뺀 모든 동승 좌석에 적용한다. 알림만, 음성 없음. */
class TaxiModeTest {

    private val left = SeatId.ROW_2_LEFT
    private val right = SeatId.ROW_2_RIGHT
    private val front = SeatId.ROW_1_RIGHT

    private fun snap(t: Long, gear: Gear, seat: SeatId, s: SeatState) = VehicleSnapshot(
        seats = SeatId.entries.associateWith { SeatState() } + (seat to s), gear = gear, timestampMs = t,
    )

    /**
     * 벨트를 매고 탐 → 벨트를 풂 → 문 열림 → 문 닫힘(착좌 유지) → P→D 출발. 출발 때 벨트 상태는 [beltAtDeparture].
     * 모든 이벤트를 모아 돌려준다.
     */
    private fun ride(e: CabinRoutineEngine, seat: SeatId, beltAtDeparture: Boolean = false): List<RoutineEvent> {
        val base = SeatState(occupied = true, beltBuckled = true, doorOpen = false)
        val out = mutableListOf<RoutineEvent>()
        out += e.onSnapshot(snap(0, Gear.PARK, seat, base))
        out += e.onSnapshot(snap(5_000, Gear.PARK, seat, base))
        out += e.onSnapshot(snap(6_000, Gear.PARK, seat, base.copy(beltBuckled = false)))
        out += e.onSnapshot(snap(7_000, Gear.PARK, seat, base.copy(beltBuckled = false, doorOpen = true)))
        out += e.onSnapshot(snap(8_000, Gear.PARK, seat, base.copy(beltBuckled = false, doorOpen = false)))
        out += e.onSnapshot(snap(20_000, Gear.DRIVE, seat, base.copy(beltBuckled = beltAtDeparture, doorOpen = false)))
        return out
    }

    private fun List<RoutineEvent>.taxiNotices() =
        filterIsInstance<RoutineEvent.CleanupNotice>().filter { it.title?.endsWith("확인 필요") == true }

    @Test
    fun `택시 모드 꺼짐 - 아이 좌석이 아닌 뒷좌석 오른쪽 1인 하차 패턴에 알림 없음`() {
        val e = CabinRoutineEngine()
        assertTrue(ride(e, right).taxiNotices().isEmpty())
        assertEquals(0, e.stats.taxiLeftoverAlerts)
    }

    @Test
    fun `택시 모드 켜짐 - 같은 패턴에 알림 1건, 어른 문구, 음성 없음`() {
        val e = CabinRoutineEngine().apply { taxiMode = true }
        val events = ride(e, right)
        val n = events.taxiNotices().single()
        assertEquals("뒷좌석 오른쪽 확인 필요", n.title)
        assertEquals("뒷좌석 오른쪽에 두고 내린 물건이 있는지 확인해 주세요.", n.text)
        assertTrue(n.plain)
        assertTrue(events.filterIsInstance<RoutineEvent.Speak>().none { it.text.contains("두고 내린") })
        assertEquals(1, e.stats.taxiLeftoverAlerts)
        assertEquals(0, e.stats.leftoverAlerts)
    }

    @Test
    fun `아이 좌석으로 지정된 좌석은 택시 모드에서도 기존 아이 좌석 문구를 쓴다`() {
        val e = CabinRoutineEngine().apply { taxiMode = true; childSeats = setOf(left) }
        val n = ride(e, left).taxiNotices().single()
        assertTrue(n.text, n.text.contains("아이가 내렸다면"))
        assertEquals(1, e.stats.leftoverAlerts)
        assertEquals(0, e.stats.taxiLeftoverAlerts)
    }

    @Test
    fun `조수석도 택시 모드에서는 대상이다`() {
        val e = CabinRoutineEngine().apply { taxiMode = true }
        val n = ride(e, front).taxiNotices().single()
        assertEquals("조수석 확인 필요", n.title)
        assertEquals("조수석에 두고 내린 물건이 있는지 확인해 주세요.", n.text)
    }

    @Test
    fun `콘솔 흐름 - 문 열고 앉고 닫아 탑승(벨트 늘 false) 뒤 문을 열었다 닫고 P에서 D`() {
        val e = CabinRoutineEngine().apply { taxiMode = true }
        val out = mutableListOf<RoutineEvent>()
        val seated = SeatState(occupied = true, beltBuckled = false, doorOpen = false)
        out += e.onSnapshot(snap(0, Gear.PARK, right, SeatState(occupied = false, beltBuckled = false, doorOpen = true)))
        out += e.onSnapshot(snap(1_000, Gear.PARK, right, seated.copy(doorOpen = true)))
        out += e.onSnapshot(snap(2_000, Gear.PARK, right, seated))
        for (t in 2_500L..6_000L step 500) out += e.onSnapshot(snap(t, Gear.PARK, right, seated))   // 틱(탑승 확정 대기)
        out += e.onSnapshot(snap(13_000, Gear.PARK, right, seated.copy(doorOpen = true)))
        out += e.onSnapshot(snap(15_000, Gear.PARK, right, seated))
        out += e.onSnapshot(snap(17_000, Gear.DRIVE, right, seated))
        assertEquals("알림: ${out.filterIsInstance<RoutineEvent.Log>().joinToString(" | ") { it.text }}", 1, out.taxiNotices().size)
    }

    @Test
    fun `벨트를 맨 사람은 알림이 없다`() {
        val e = CabinRoutineEngine().apply { taxiMode = true }
        assertTrue(ride(e, right, beltAtDeparture = true).taxiNotices().isEmpty())
        assertEquals(0, e.stats.taxiLeftoverAlerts)
    }
}
