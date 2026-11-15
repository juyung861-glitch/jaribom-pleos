package com.jaribom.app.core

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * F7-3: 보조 판정·남은 물건 알림의 상한 단위는 "좌석 에피소드"다. 알림이 나간 뒤 그 좌석 문이 다시 열리면 새 에피소드라
 * 상한이 풀린다. 문 활동 없이 P→D 를 되풀이하면(같은 가방) 계속 억제한다. 하루 상한(5번)은 그대로다.
 */
class EpisodeLimitTest {

    private val right = SeatId.ROW_2_RIGHT
    private val left = SeatId.ROW_2_LEFT

    private fun snap(t: Long, gear: Gear, seats: Map<SeatId, SeatState>) = VehicleSnapshot(
        seats = SeatId.entries.associateWith { SeatState() } + seats, gear = gear, timestampMs = t,
    )

    private val seated = SeatState(occupied = true, beltBuckled = false, doorOpen = false)
    private val empty = SeatState(occupied = false, beltBuckled = false, doorOpen = false)

    /** 손님 한 명: 문 열고 앉고 닫음(탑승) → 문 열었다 닫음(착좌 유지·벨트 풀림) → P→D. [t0] 부터. */
    private fun passenger(e: CabinRoutineEngine, seat: SeatId, t0: Long): List<RoutineEvent> {
        val out = mutableListOf<RoutineEvent>()
        fun f(dt: Long, gear: Gear, s: SeatState) { out += e.onSnapshot(snap(t0 + dt, gear, mapOf(seat to s))) }
        f(0, Gear.PARK, empty.copy(doorOpen = true))
        f(1_000, Gear.PARK, seated.copy(doorOpen = true))
        f(2_000, Gear.PARK, seated)
        for (dt in 2_500L..6_000L step 500) f(dt, Gear.PARK, seated)          // 탑승 확정 대기(틱)
        f(13_000, Gear.PARK, seated.copy(doorOpen = true))
        f(15_000, Gear.PARK, seated)
        f(17_000, Gear.DRIVE, seated)
        return out
    }

    private fun List<RoutineEvent>.alerts() =
        filterIsInstance<RoutineEvent.CleanupNotice>().filter { it.title?.endsWith("확인 필요") == true }

    @Test
    fun `알림 뒤 그 좌석 문이 다시 열리면 새 에피소드 - 1분 안에 탄 두 번째 손님도 알린다`() {
        val e = CabinRoutineEngine().apply { taxiMode = true; alertLimiter = AlertLimiter { "2026-11-04" } }
        assertEquals(1, passenger(e, right, 0).alerts().size)
        // 첫 손님이 내림(문 열고 일어나고 닫음) — 운행 종료(5분 빈 좌석) 없이 바로 다음 손님
        e.onSnapshot(snap(20_000, Gear.PARK, mapOf(right to seated.copy(doorOpen = true))))
        e.onSnapshot(snap(21_000, Gear.PARK, mapOf(right to empty.copy(doorOpen = true))))
        e.onSnapshot(snap(22_000, Gear.PARK, mapOf(right to empty)))
        assertEquals(1, passenger(e, right, 30_000).alerts().size)
        assertEquals(2, e.stats.taxiLeftoverAlerts)
        assertEquals(0, e.stats.suppressedAlerts)
    }

    @Test
    fun `알림 뒤 문 활동 없이 P→D 를 되풀이하면 다시 알리지 않는다`() {
        val e = CabinRoutineEngine().apply { taxiMode = true; alertLimiter = AlertLimiter { "2026-11-04" } }
        assertEquals(1, passenger(e, right, 0).alerts().size)
        val again = mutableListOf<RoutineEvent>()
        for (i in 0 until 3) {
            again += e.onSnapshot(snap(30_000L + i * 4_000, Gear.PARK, mapOf(right to seated)))
            again += e.onSnapshot(snap(32_000L + i * 4_000, Gear.DRIVE, mapOf(right to seated)))
        }
        assertEquals(0, again.alerts().size)
    }
}
