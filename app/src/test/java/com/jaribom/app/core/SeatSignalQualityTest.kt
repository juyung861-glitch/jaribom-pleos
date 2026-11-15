package com.jaribom.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 7차 F11-4: 좌석 신호 상태 — 신호 없음(미지원·null) / 정상 / 신호 약함. */
class SeatSignalQualityTest {

    private val seat = SeatId.ROW_2_RIGHT
    private var t = 0L

    /** 앉은 적 없이 문만 열렸다 닫힘 한 번 */
    private fun doorOnly(q: SeatSignalQuality) {
        q.observe(seat, false, false, t); t += 1_000
        q.observe(seat, true, false, t); t += 1_000
        q.observe(seat, false, false, t); t += 20_000          // 15초 넘게 착좌가 안 잡힘 → 다음 스냅샷에서 확정
        q.observe(seat, false, false, t); t += 1_000
    }

    /** 타는 문: 문이 열렸다 닫힌 뒤 3초 안에 착좌가 잡힘 */
    private fun boarding(q: SeatSignalQuality) {
        q.observe(seat, false, false, t); t += 1_000
        q.observe(seat, true, false, t); t += 1_000
        q.observe(seat, false, false, t); t += 3_000
        q.observe(seat, false, true, t); t += 1_000
    }

    /** 내리는 문: 앉아 있다가 일어나 문이 열렸다 닫힘 */
    private fun alighting(q: SeatSignalQuality) {
        q.observe(seat, false, true, t); t += 5_000
        q.observe(seat, false, false, t); t += 1_000
        q.observe(seat, true, false, t); t += 1_000
        q.observe(seat, false, false, t); t += 30_000
        q.observe(seat, false, false, t); t += 1_000
    }

    @Test
    fun `미지원이면 신호 없음`() {
        val q = SeatSignalQuality()
        q.observe(seat, false, true, 0)
        assertEquals(SeatSignalStatus.NO_SIGNAL, q.status(seat, SeatSignalMode.BELT_DOOR))    // 착좌 미지원(capability)
        assertEquals(SeatSignalStatus.NO_SIGNAL, q.status(seat, SeatSignalMode.NONE))          // 둘 다 미지원
        // 미지원 좌석은 기록이 쌓여도 약함이 아니라 신호 없음
        repeat(8) { doorOnly(q) }
        assertEquals(SeatSignalStatus.NO_SIGNAL, q.status(seat, SeatSignalMode.BELT_DOOR))
    }

    @Test
    fun `착좌 값이 null 이면 신호 없음`() {
        val q = SeatSignalQuality()
        q.observe(seat, false, null, 0)
        assertEquals(SeatSignalStatus.NO_SIGNAL, q.status(seat, SeatSignalMode.FULL))
        // 아직 한 번도 스냅샷을 받지 못한 좌석도 신호를 모르는 상태
        assertEquals(SeatSignalStatus.NO_SIGNAL, SeatSignalQuality().status(seat, SeatSignalMode.FULL))
        // 값이 들어오면 신호 있음으로 돌아온다
        q.observe(seat, false, false, 1_000)
        assertEquals(SeatSignalStatus.NORMAL, q.status(seat, SeatSignalMode.FULL))
    }

    @Test
    fun `정상 - 타고 내릴 때 착좌가 잡히면 정상`() {
        val q = SeatSignalQuality()
        repeat(6) { boarding(q); alighting(q) }
        assertEquals(SeatSignalStatus.NORMAL, q.status(seat, SeatSignalMode.FULL))
        assertTrue(q.history(seat).all { it })
        assertEquals(SeatSignalQuality.WINDOW, q.history(seat).size)
    }

    @Test
    fun `약함 - 착좌 없이 문만 여닫기를 여러 번 하면 신호 약함`() {
        val q = SeatSignalQuality()
        repeat(SeatSignalQuality.MIN_SAMPLES + 1) { doorOnly(q) }
        assertEquals(SeatSignalStatus.WEAK, q.status(seat, SeatSignalMode.FULL))
        assertNotNull(SeatSignalStatus.WEAK.line)
        assertTrue(SeatSignalStatus.WEAK.line!!.contains("가벼운 승객"))
        assertNull(SeatSignalStatus.NORMAL.line)
    }

    @Test
    fun `표본이 적으면 판정하지 않는다`() {
        val q = SeatSignalQuality()
        repeat(SeatSignalQuality.MIN_SAMPLES - 1) { doorOnly(q) }
        assertEquals(SeatSignalStatus.NORMAL, q.status(seat, SeatSignalMode.FULL))
    }

    @Test
    fun `임계값 경계 - 10번 중 7번 이상이면 약함 6번이면 정상`() {
        fun build(missed: Int): SeatSignalQuality {
            val q = SeatSignalQuality()
            repeat(10 - missed) { alighting(q) }
            repeat(missed) { doorOnly(q) }
            return q
        }
        assertEquals(SeatSignalStatus.WEAK, build(7).status(seat, SeatSignalMode.FULL))
        assertEquals(SeatSignalStatus.NORMAL, build(6).status(seat, SeatSignalMode.FULL))
    }

    @Test
    fun `최근 10번만 본다 - 오래된 약함은 회복된다`() {
        val q = SeatSignalQuality()
        repeat(10) { doorOnly(q) }
        assertEquals(SeatSignalStatus.WEAK, q.status(seat, SeatSignalMode.FULL))
        repeat(10) { boarding(q); alighting(q) }
        assertEquals(SeatSignalStatus.NORMAL, q.status(seat, SeatSignalMode.FULL))
    }

    @Test
    fun `저장했다 복원하면 이어서 센다`() {
        val q = SeatSignalQuality()
        repeat(6) { doorOnly(q) }
        val restored = SeatSignalQuality(q.allHistory())
        restored.observe(seat, false, false, 0)
        assertEquals(SeatSignalStatus.WEAK, restored.status(seat, SeatSignalMode.FULL))
    }

    @Test
    fun `엔진에서 착좌 없이 문만 여닫으면 설정용 상태가 약함이 된다`() {
        val e = CabinRoutineEngine()
        var now = 0L
        fun snap(door: Boolean) = VehicleSnapshot(
            seats = SeatId.entries.associateWith { SeatState() } + (seat to SeatState(occupied = false, doorOpen = door)),
            gear = Gear.PARK, timestampMs = now,
        )
        e.onSnapshot(snap(false))
        repeat(7) {
            now += 2_000; e.onSnapshot(snap(true))
            now += 2_000; e.onSnapshot(snap(false))
            now += 20_000; e.onSnapshot(snap(false))
        }
        assertEquals(SeatSignalStatus.WEAK, e.signalStatuses().getValue(seat))
        // 신호가 없는 다른 좌석은 값이 null 이라 신호 없음
        assertEquals(SeatSignalStatus.NO_SIGNAL, e.signalStatuses().getValue(SeatId.ROW_1_RIGHT))
        // 착좌 미지원 모드로 바꾸면 약함이 아니라 신호 없음(capability 판별 결과를 그대로 쓴다)
        e.seatModes = mapOf(seat to SeatSignalMode.BELT_DOOR)
        assertEquals(SeatSignalStatus.NO_SIGNAL, e.signalStatuses().getValue(seat))
    }
}
