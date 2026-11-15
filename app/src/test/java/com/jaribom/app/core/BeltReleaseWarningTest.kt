package com.jaribom.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F14-4: 키즈락 모드로 탄 좌석은 D·N 에서 정차 중(속도 값이 있고 정차 기준 이하) 벨트가 체결→풀림으로 바뀌는 순간,
 * 문이 열리기 전에 "뒤에 차 오는지 꼭 보고 내려."만 먼저 말한다. 좌석 에피소드당 1번이다.
 * 말했으면 그 뒤 30초 안의 문 열림 안내에서는 뒤 차 문장을 빼고(물건·날씨·기본 문장만), 아니면 F13-1대로 문 열릴 때 붙인다.
 * 속도 null·벨트 null·주행 중·P·택시 모드·어른 전환·키즈락 모드 끔에서는 말하지 않는다.
 * (에뮬레이터 콘솔에는 벨트 항목이 없어 단위 테스트로만 확인한다.)
 */
class BeltReleaseWarningTest {

    private val seat = SeatId.ROW_2_RIGHT
    private val warn = ExitMessageRules.REAR_CHECK_CHILD
    private val base = SeatState(windowOpenPercent = 0, heaterLevel = 0, ventilationLevel = 0, doorOpen = false)

    private fun snap(t: Long, s: SeatState, gear: Gear = Gear.DRIVE, speed: Float? = 0f) = VehicleSnapshot(
        seats = SeatId.entries.associateWith { SeatState() } + (seat to s), gear = gear, speedMps = speed,
        slowSinceMs = if (speed != null && speed <= VehicleMotion.STOP_MPS) 0L else null, timestampMs = t,
    )

    private fun seated(belt: Boolean?) = base.copy(occupied = true, beltBuckled = belt)
    private val up = base.copy(occupied = false, beltBuckled = false)

    private fun List<RoutineEvent>.speech() = filterIsInstance<RoutineEvent.Speak>().map { it.text }

    private fun child(configure: CabinRoutineEngine.() -> Unit = {}) = CabinRoutineEngine().apply { childSeats = setOf(seat); configure() }

    /** 벨트를 매고 탑승 확정 → [gear]·[speed] 에서 벨트 풀림. 풀림 순간의 음성 */
    private fun beltOff(e: CabinRoutineEngine, gear: Gear = Gear.DRIVE, speed: Float? = 0f): List<String> {
        e.onSnapshot(snap(0, seated(true), gear, speed))
        e.onSnapshot(snap(2_000, seated(true), gear, speed))
        return e.onSnapshot(snap(5_000, seated(false), gear, speed)).speech()
    }

    /** 일어남(10초 쪽) → [openAt] 에 문 열림. 문 열림 때의 동승자 안내 음성 */
    private fun doorOpen(e: CabinRoutineEngine, openAt: Long, gear: Gear = Gear.DRIVE, speed: Float? = 0f): List<String> {
        e.onSnapshot(snap(openAt - 1_000, up, gear, speed))
        return e.onSnapshot(snap(openAt, up.copy(doorOpen = true), gear, speed)).speech()
    }

    @Test
    fun `D 속도 0 벨트 풀림 - 경고 1회, 10초 뒤 문 열림 안내에는 뒤 차 문장이 없고 기본 문장은 있다`() {
        val e = child()
        assertEquals(listOf(warn), beltOff(e))
        val door = doorOpen(e, 11_000).single()
        assertFalse(door, door.contains(warn))
        assertTrue(door, door.contains("두고 가는 거 없지?"))
    }

    @Test
    fun `경고 40초 뒤 문 열림 - 30초가 지났으니 F13-1대로 뒤 차 문장이 앞에 붙는다`() {
        val e = child()
        assertEquals(listOf(warn), beltOff(e))
        val door = doorOpen(e, 45_000).single()
        assertTrue(door, door.startsWith(warn))
    }

    @Test
    fun `N 에서도 말한다`() {
        assertEquals(listOf(warn), beltOff(child(), gear = Gear.NEUTRAL))
    }

    @Test
    fun `속도 null 이면 벨트 때는 말하지 않고 문 열릴 때 F13-1로 말한다`() {
        val e = child()
        assertEquals(emptyList<String>(), beltOff(e, speed = null))
        val door = doorOpen(e, 11_000, speed = null).single()
        assertTrue(door, door.startsWith(warn))
    }

    @Test
    fun `주행 중에 벨트가 풀리면 말하지 않는다`() {
        assertEquals(emptyList<String>(), beltOff(child(), speed = 8f))
    }

    @Test
    fun `벨트를 다시 채웠다가 풀어도 같은 에피소드에서는 추가 경고가 없다`() {
        val e = child()
        assertEquals(listOf(warn), beltOff(e))
        assertEquals(emptyList<String>(), e.onSnapshot(snap(6_000, seated(true))).speech())
        assertEquals(emptyList<String>(), e.onSnapshot(snap(7_000, seated(false))).speech())
    }

    @Test
    fun `P 에서는 말하지 않는다`() {
        assertEquals(emptyList<String>(), beltOff(child(), gear = Gear.PARK))
    }

    @Test
    fun `벨트 신호가 없는 좌석은 벨트 때 말하지 않고 문 열릴 때 F13-1로 말한다`() {
        val e = child()
        // 앱 시작 때 이미 앉아 있던 좌석(벨트 null)은 문 이력 없이 탑승 확정된다
        e.onSnapshot(snap(0, seated(null)))
        assertEquals(emptyList<String>(), e.onSnapshot(snap(5_000, seated(null))).speech())
        val door = doorOpen(e, 11_000).single()
        assertTrue(door, door.startsWith(warn))
    }

    @Test
    fun `택시 모드·키즈락 모드 끔·하차 멘트 끔에서는 말하지 않는다`() {
        assertEquals(emptyList<String>(), beltOff(child { taxiMode = true }))
        assertEquals(emptyList<String>(), beltOff(child { kidsLockOn = false }))
        assertEquals(emptyList<String>(), beltOff(child { exitVoice = false }))
    }

    @Test
    fun `어른이 탔어요 로 전환된 좌석은 말하지 않는다`() {
        val e = child()
        val boarded = e.onSnapshot(snap(0, seated(true)))
        val n = boarded.filterIsInstance<RoutineEvent.CleanupNotice>().first { it.adultSeat != null }
        e.onAdultAboard(n.key, seat, n.buttonEpisodes.getValue(seat))
        e.onSnapshot(snap(2_000, seated(true)))
        assertEquals(emptyList<String>(), e.onSnapshot(snap(5_000, seated(false))).speech())
    }

    @Test
    fun `일반 좌석은 말하지 않는다`() {
        assertEquals(emptyList<String>(), beltOff(CabinRoutineEngine()))
    }

    @Test
    fun `경고 뒤에는 도로변 설정이 켜져 있어도 문 열림 안내에 뒤 차 문장을 다시 붙이지 않는다`() {
        val e = child { roadsideRearCheck = true }
        assertEquals(listOf(warn), beltOff(e))
        val door = doorOpen(e, 11_000).single()
        assertFalse(door, door.contains(warn))
    }

    @Test
    fun `다음 손님(새 에피소드)은 다시 경고한다`() {
        val e = child()
        assertEquals(listOf(warn), beltOff(e))
        doorOpen(e, 11_000)
        e.onSnapshot(snap(12_000, up))                                  // 문 닫힘 → 하차 확정
        // 다음 손님: 문 열림→닫힘, 벨트 체결, 풀림
        e.onSnapshot(snap(20_000, base.copy(doorOpen = true)))
        e.onSnapshot(snap(21_000, base))
        e.onSnapshot(snap(22_000, seated(true)))
        e.onSnapshot(snap(25_000, seated(true)))
        assertEquals(listOf(warn), e.onSnapshot(snap(30_000, seated(false))).speech())
    }
}
