package com.jaribom.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F9 리뷰 반영: 오래된 알림의 [열선 끄기]·[창문 닫기]가 새 손님에게 작동하지 않게 한다(좌석 에피소드가 바뀌면 거부), 연타는 한 번만.
 * 버튼 대상의 에피소드는 알림을 만들 때 기록하고(알림에 담김), 누를 때 지금 에피소드와 비교한다.
 */
class ButtonStaleBusyTest {

    private val right = SeatId.ROW_2_RIGHT
    private val left = SeatId.ROW_2_LEFT

    private fun snap(t: Long, gear: Gear, open: Boolean, seats: Set<SeatId>, heater: Int = 2) = VehicleSnapshot(
        seats = SeatId.entries.associateWith { SeatState() } + seats.associateWith {
            SeatState(occupied = true, beltBuckled = false, doorOpen = open, windowOpenPercent = 0, heaterLevel = heater, ventilationLevel = 0)
        },
        gear = gear, timestampMs = t,
    )

    private fun alertNotice(e: CabinRoutineEngine): RoutineEvent.CleanupNotice {
        val out = mutableListOf<RoutineEvent>()
        out += e.onSnapshot(snap(0, Gear.PARK, false, setOf(right)).let { s -> s.copy(seats = s.seats + (right to s.seat(right).copy(beltBuckled = true))) })
        for (t in 500L..5_000L step 500) out += e.onSnapshot(snap(t, Gear.PARK, false, setOf(right)))
        out += e.onSnapshot(snap(7_000, Gear.PARK, true, setOf(right)))
        out += e.onSnapshot(snap(8_000, Gear.PARK, false, setOf(right)))
        out += e.onSnapshot(snap(9_000, Gear.DRIVE, false, setOf(right)))
        return out.filterIsInstance<RoutineEvent.CleanupNotice>().single { it.title?.endsWith("확인 필요") == true }
    }

    @Test
    fun `알림에 버튼 대상 좌석의 에피소드가 담기고, 그 좌석 문이 열리면 달라져 오래된 알림이 된다`() {
        val e = CabinRoutineEngine().apply { taxiMode = true }
        val n = alertNotice(e)
        assertEquals(setOf(right), n.buttonEpisodes.keys)
        assertFalse(ButtonGuard.isStale(n.buttonEpisodes, e::episodeOf))
        // 같은 좌석에 다음 손님: 문이 열린다
        e.onSnapshot(snap(20_000, Gear.PARK, true, setOf(right)))
        assertTrue(ButtonGuard.isStale(n.buttonEpisodes, e::episodeOf))
        val stale = e.onButtonStale(n.key, listOf(right))
        assertEquals("지난 하차 알림이라 실행하지 않았어요.", stale.text)
        assertTrue(stale.climateOff.isEmpty() && stale.windowClose.isEmpty())
    }

    @Test
    fun `다른 좌석 문이 열려도 이 알림은 오래되지 않는다`() {
        val e = CabinRoutineEngine().apply { taxiMode = true }
        val n = alertNotice(e)
        e.onSnapshot(snap(20_000, Gear.PARK, true, setOf(left)))
        assertFalse(ButtonGuard.isStale(n.buttonEpisodes, e::episodeOf))
    }

    @Test
    fun `연타 3번은 한 번만 실행하고 끝나면 다시 누를 수 있다 - 다른 좌석은 막지 않는다`() {
        val gate = ButtonGate()
        var writes = 0
        repeat(3) { if (gate.tryBegin(listOf(right))) writes++ }
        assertEquals(1, writes)
        assertTrue("다른 좌석은 막지 않는다", gate.tryBegin(listOf(left)))
        gate.end(listOf(right))
        assertTrue(gate.tryBegin(listOf(right)))
    }
}
