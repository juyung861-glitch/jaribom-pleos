package com.jaribom.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F12-6(대체안): 순정 SEA 상태를 읽을 API 가 없어 후측방 경고는 만들지 않는다.
 * 설정 "도로변 하차 때 뒤 차 확인"(기본 꺼짐)을 켜면 D·N 에서 문이 열린 하차 안내 맨 앞에 한 문장을 더한다.
 * 키즈락이면 아이 말투, 택시 모드는 존댓말. 동승자 안내와 같은 규칙(하차 멘트 설정, 달리는 중 생략, 하루 상한 없음)을 따른다.
 */
class RoadsideRearCheckTest {

    private val seat = SeatId.ROW_2_RIGHT
    private val base = SeatState(windowOpenPercent = 0, heaterLevel = 0, ventilationLevel = 0, doorOpen = false)

    private fun snap(t: Long, s: SeatState, gear: Gear, speed: Float? = null) = VehicleSnapshot(
        seats = SeatId.entries.associateWith { SeatState() } + (seat to s), gear = gear, speedMps = speed,
        slowSinceMs = if (speed != null && speed <= VehicleMotion.STOP_MPS) 0L else null, timestampMs = t,
    )

    /** 탑승 → 일어남 → [gear] 에서 문 열림. 동승자 안내 문장 */
    private fun speech(e: CabinRoutineEngine, gear: Gear, speed: Float? = null, t0: Long = 0): String? {
        e.onSnapshot(snap(t0, base.copy(occupied = true, beltBuckled = true), gear, speed))
        e.onSnapshot(snap(t0 + 10_000, base.copy(occupied = false), gear, speed))
        return e.onSnapshot(snap(t0 + 11_000, base.copy(occupied = false, doorOpen = true), gear, speed))
            .filterIsInstance<RoutineEvent.Speak>().firstOrNull()?.text
    }

    private fun engine(on: Boolean = true) = CabinRoutineEngine().apply { roadsideRearCheck = on }

    @Test
    fun `기본은 꺼져 있고 꺼져 있으면 D 에서도 말하지 않는다`() {
        assertFalse(CabinRoutineEngine().roadsideRearCheck)
        assertEquals("두고 가시는 물건은 없나요?", speech(engine(on = false), Gear.DRIVE))
    }

    @Test
    fun `켜면 D·N 하차 안내 맨 앞에 붙고 P 에서는 붙지 않는다`() {
        assertEquals("${ExitMessageRules.REAR_CHECK_POLITE} 두고 가시는 물건은 없나요?", speech(engine(), Gear.DRIVE))
        assertTrue(speech(engine(), Gear.NEUTRAL)!!.startsWith(ExitMessageRules.REAR_CHECK_POLITE))
        assertEquals("두고 가시는 물건은 없나요?", speech(engine(), Gear.PARK))
    }

    @Test
    fun `키즈락 좌석은 아이 말투이고 이름을 부르면 그 문장 앞에 붙는다`() {
        val e = engine().apply {
            childSeats = setOf(seat); childProfiles = mapOf(seat to ChildProfile(name = "지민"))
        }
        val s = speech(e, Gear.DRIVE)!!
        assertTrue(s, s.startsWith("지민아, ${ExitMessageRules.REAR_CHECK_CHILD}"))
    }

    @Test
    fun `택시 모드는 키즈락 좌석이어도 존댓말이다`() {
        val e = engine().apply { childSeats = setOf(seat); taxiMode = true }
        assertTrue(speech(e, Gear.DRIVE)!!.startsWith(ExitMessageRules.REAR_CHECK_POLITE))
    }

    @Test
    fun `운행당 1번인 기본 멘트를 이미 말했어도 뒤 차 확인은 매번 말한다`() {
        val e = engine()
        speech(e, Gear.DRIVE)
        assertEquals(ExitMessageRules.REAR_CHECK_POLITE, speech(e, Gear.DRIVE, t0 = 100_000))
    }

    @Test
    fun `묶음에 늦게 합류한 좌석의 안내에는 뒤 차 확인 문장을 다시 붙이지 않는다 - 묶음당 한 번`() {
        val front = SeatId.ROW_1_RIGHT
        val e = engine().apply { childSeats = setOf(seat); childProfiles = mapOf(seat to ChildProfile(baseItems = listOf("물통"))) }
        fun two(t: Long, f: SeatState, r: SeatState) = VehicleSnapshot(
            seats = SeatId.entries.associateWith { SeatState() } + (front to f) + (seat to r), gear = Gear.DRIVE, timestampMs = t,
        )
        val seated = base.copy(occupied = true, beltBuckled = true)
        val up = base.copy(occupied = false)
        e.onSnapshot(two(0, seated, seated))
        e.onSnapshot(two(10_000, up, up))
        val first = e.onSnapshot(two(11_000, up.copy(doorOpen = true), up)).filterIsInstance<RoutineEvent.Speak>().map { it.text }
        val joined = e.onSnapshot(two(11_100, up.copy(doorOpen = true), up.copy(doorOpen = true))).filterIsInstance<RoutineEvent.Speak>().map { it.text }
        assertTrue(first.toString(), first.single().startsWith(ExitMessageRules.REAR_CHECK_POLITE))
        assertEquals("합류한 좌석은 자기 물건만 말한다", 1, joined.size)
        assertFalse(joined.single(), joined.single().contains(ExitMessageRules.REAR_CHECK_CHILD))
        assertFalse(joined.single().contains(ExitMessageRules.REAR_CHECK_POLITE))
        assertTrue(joined.single(), joined.single().contains("물통"))
    }

    // ── F13-1: 키즈락 좌석 D·N 하차는 설정과 상관없이 뒤 차 확인(아이 말투) ──

    @Test
    fun `F13-1 키즈락 좌석 D·N 하차는 설정이 꺼져 있어도 아이 말투 뒤 차 확인이 맨 앞에 붙는다`() {
        assertEquals("뒤에 차 오는지 꼭 보고 내려.", ExitMessageRules.REAR_CHECK_CHILD)
        val d = speech(engine(on = false).apply { childSeats = setOf(seat) }, Gear.DRIVE)!!
        assertTrue(d, d.startsWith(ExitMessageRules.REAR_CHECK_CHILD))
        val n = speech(engine(on = false).apply { childSeats = setOf(seat) }, Gear.NEUTRAL)!!
        assertTrue(n, n.startsWith(ExitMessageRules.REAR_CHECK_CHILD))
    }

    @Test
    fun `F13-1 키즈락 좌석도 P 하차에는 붙지 않는다`() {
        val p = speech(engine(on = false).apply { childSeats = setOf(seat) }, Gear.PARK)!!
        assertFalse(p, p.contains(ExitMessageRules.REAR_CHECK_CHILD))
        val pOn = speech(engine(on = true).apply { childSeats = setOf(seat) }, Gear.PARK)!!
        assertFalse(pOn, pOn.contains(ExitMessageRules.REAR_CHECK_CHILD))
    }

    @Test
    fun `F13-1 어른이 탔어요 로 존댓말이 된 좌석은 설정 규칙을 따른다`() {
        fun adult(on: Boolean): String? {
            val e = engine(on).apply { childSeats = setOf(seat) }
            val out = e.onSnapshot(snap(0, base.copy(occupied = true, beltBuckled = true), Gear.PARK)) +
                e.onSnapshot(snap(5_000, base.copy(occupied = true, beltBuckled = true), Gear.PARK))
            val n = out.filterIsInstance<RoutineEvent.CleanupNotice>().first { it.adultSeat != null }
            e.onAdultAboard(n.key, seat, n.buttonEpisodes.getValue(seat))
            e.onSnapshot(snap(9_000, base.copy(occupied = true, beltBuckled = true), Gear.DRIVE))
            e.onSnapshot(snap(10_000, base.copy(occupied = false), Gear.DRIVE))
            return e.onSnapshot(snap(11_000, base.copy(occupied = false, doorOpen = true), Gear.DRIVE))
                .filterIsInstance<RoutineEvent.Speak>().firstOrNull()?.text
        }
        assertEquals("설정 꺼짐이면 없음", "두고 가시는 물건은 없나요?", adult(on = false))
        assertEquals("${ExitMessageRules.REAR_CHECK_POLITE} 두고 가시는 물건은 없나요?", adult(on = true))
    }

    @Test
    fun `F13-1 키즈락 모드를 끄면 지정 좌석도 일반 좌석 규칙이다`() {
        val e = engine(on = false).apply { childSeats = setOf(seat); kidsLockOn = false }
        assertEquals("두고 가시는 물건은 없나요?", speech(e, Gear.DRIVE))
    }

    @Test
    fun `하차 멘트를 끄거나 달리는 중이면 말하지 않는다`() {
        assertEquals(null, speech(engine().apply { exitVoice = false }, Gear.DRIVE))
        assertEquals(null, speech(engine(), Gear.DRIVE, speed = 3f))
    }
}
