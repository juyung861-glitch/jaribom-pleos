package com.jaribom.app.core

import java.time.Clock
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 7차 F11-2: 아이 좌석별 기본 물건 + 요일별 물건. 오늘 요일 먼저, 중복 제거, 최대 3개, 예전 저장값 이전, 저장 형식. */
class ChildWeekdayItemsTest {

    private val right = SeatId.ROW_2_RIGHT
    private val left = SeatId.ROW_2_LEFT
    private val zone = ZoneId.of("Asia/Seoul")

    /** 2026-11-03 은 화요일, 11-04 는 수요일 */
    private fun clockOf(date: String) = Clock.fixed(Instant.parse("${date}T03:00:00Z"), zone)
    private val tuesday = clockOf("2026-11-03")
    private val wednesday = clockOf("2026-11-04")

    private val profile = ChildProfile(
        baseItems = listOf("물통", "지갑"),
        dayItems = mapOf(DayOfWeek.TUESDAY to listOf("수영 가방")),
    )

    @Test
    fun `Clock 으로 요일을 판정한다`() {
        assertEquals(DayOfWeek.TUESDAY, ChildProfiles.today(tuesday))
        assertEquals(DayOfWeek.WEDNESDAY, ChildProfiles.today(wednesday))
        // 기기 로컬 시간 기준: UTC 로는 월요일 20시여도 서울은 화요일 05시
        assertEquals(DayOfWeek.TUESDAY, ChildProfiles.today(Clock.fixed(Instant.parse("2026-11-02T20:00:00Z"), zone)))
    }

    @Test
    fun `오늘 요일 물건을 먼저 그다음 기본 물건`() {
        assertEquals(listOf("수영 가방", "물통", "지갑"), ChildProfiles.spokenItems(profile, DayOfWeek.TUESDAY))
        assertEquals(listOf("물통", "지갑"), ChildProfiles.spokenItems(profile, DayOfWeek.WEDNESDAY))
        assertEquals(emptyList<String>(), ChildProfiles.spokenItems(null, DayOfWeek.TUESDAY))
        assertEquals(emptyList<String>(), ChildProfiles.spokenItems(ChildProfile(), DayOfWeek.TUESDAY))
    }

    @Test
    fun `중복은 빼고 최대 3개까지만 말한다`() {
        val p = ChildProfile(
            baseItems = listOf("물통", "지갑", "수영 가방", "마스크"),
            dayItems = mapOf(DayOfWeek.TUESDAY to listOf("수영가방", "리코더")),     // 공백 차이는 같은 물건
        )
        assertEquals(listOf("수영가방", "리코더", "물통"), ChildProfiles.spokenItems(p, DayOfWeek.TUESDAY))
        assertEquals(3, ChildProfiles.spokenItems(p, DayOfWeek.FRIDAY).size)
    }

    @Test
    fun `엔진은 화요일에 요일 물건을 기본 물건보다 먼저 말한다`() {
        fun said(clock: Clock): String {
            val e = CabinRoutineEngine().apply { childSeats = setOf(right); childProfiles = mapOf(right to profile); this.clock = clock }
            return open(e)
        }
        assertEquals("수영 가방, 물통이랑 지갑 챙겼지? 조심히 내려.", said(tuesday))
        assertEquals("물통이랑 지갑 챙겼지? 조심히 내려.", said(wednesday))
    }

    @Test
    fun `비 오는 날 우산은 날씨 문장에만 나오고 목록에서 빠진다`() {
        val e = CabinRoutineEngine().apply {
            childSeats = setOf(right); clock = tuesday
            childProfiles = mapOf(right to ChildProfile(baseItems = listOf("우산", "물통")))
            weather = WeatherInfo(rainingNow = true)
        }
        assertEquals("밖에 비 와. 우산 챙겨. 물통 챙겼지? 조심히 내려.", open(e))
    }

    @Test
    fun `등록한 일반 물건은 아이 물건 뒤에 붙고 합쳐서 3개를 넘지 않는다`() {
        val e = CabinRoutineEngine().apply {
            childSeats = setOf(right); clock = tuesday; childProfiles = mapOf(right to profile)
            items = listOf(CarryItem(1, "마스크", ItemCondition.ALWAYS), CarryItem(2, "지갑", ItemCondition.ALWAYS))
        }
        assertEquals("수영 가방, 물통이랑 지갑 챙겼지? 조심히 내려.", open(e))
    }

    @Test
    fun `프로필이 없는 아이 좌석과 어른 좌석은 예전 그대로다`() {
        val e = CabinRoutineEngine().apply { childSeats = setOf(right); clock = tuesday }
        assertEquals("두고 가는 거 없지? 조심히 내려.", open(e))
        val adult = CabinRoutineEngine().apply { childProfiles = mapOf(right to profile); clock = tuesday }
        assertTrue(open(adult).startsWith("두고 가시는 물건은 없나요?"))
        assertFalse(open(adult).contains("수영 가방"))
    }

    // ── 예전 저장값 이전 ──
    private fun legacy() = listOf(
        CarryItem(1, "기저귀", ItemCondition.SEAT, right),
        CarryItem(2, "카시트 가방", ItemCondition.SEAT, left),
        CarryItem(3, "우산", ItemCondition.RAIN),
        CarryItem(4, "지갑", ItemCondition.ALWAYS),
        CarryItem(5, "물통", ItemCondition.SEAT, right),
    )

    // F11-V: 비파괴 이전. 예전 목록은 복사만, 좌석별 1회(child_migrated_seats), 기본 물건이 있으면 건너뜀.

    @Test
    fun `예전 좌석 전용 물건을 기본 물건으로 복사만 하고 예전 목록은 그대로 둔다`() {
        val items = legacy()
        val m = ChildProfiles.copyLegacy(items, setOf(right), emptyMap(), done = emptySet())
        assertEquals(listOf("기저귀", "물통"), m.profiles.getValue(right).baseItems)
        assertEquals(setOf(right), m.doneSeats)
        assertEquals(2, m.copied)
        assertEquals("예전 목록은 바뀌지 않는다", legacy(), items)
        assertFalse("아이 좌석이 아닌 좌석은 건드리지 않는다", left in m.profiles)
    }

    @Test
    fun `좌석별로 한 번만 복사한다 - 두 번 실행해도 한 번`() {
        val first = ChildProfiles.copyLegacy(legacy(), setOf(right), emptyMap(), done = emptySet())
        // 사용자가 복사된 기본 물건을 모두 지웠다. 완료 기록이 있으니 다시 채우지 않는다.
        val cleared = first.profiles + (right to ChildProfile())
        val second = ChildProfiles.copyLegacy(legacy(), setOf(right), cleared, done = first.doneSeats)
        assertEquals(cleared, second.profiles)
        assertEquals(setOf(right), second.doneSeats)
        assertEquals(0, second.copied)
    }

    @Test
    fun `그 좌석에 기본 물건이 이미 있으면 건너뛰고 완료로 기록한다`() {
        val existing = mapOf(right to ChildProfile(baseItems = listOf("도시락")))
        val m = ChildProfiles.copyLegacy(legacy(), setOf(right), existing, done = emptySet())
        assertEquals(listOf("도시락"), m.profiles.getValue(right).baseItems)
        assertEquals(setOf(right), m.doneSeats)
        assertEquals(0, m.copied)
    }

    @Test
    fun `키즈락 좌석을 뒤늦게 지정해도 그 좌석에 한 번 복사한다`() {
        val atStart = ChildProfiles.copyLegacy(legacy(), emptySet(), emptyMap(), done = emptySet())
        assertTrue(atStart.profiles.isEmpty())
        assertTrue("지정 전에는 완료로 기록하지 않는다", atStart.doneSeats.isEmpty())
        val later = ChildProfiles.copyLegacy(legacy(), setOf(left), atStart.profiles, done = atStart.doneSeats)
        assertEquals(listOf("카시트 가방"), later.profiles.getValue(left).baseItems)
        assertEquals(setOf(left), later.doneSeats)
    }

    @Test
    fun `지정을 해제했다가 다시 지정해도 다시 복사하지 않는다`() {
        val first = ChildProfiles.copyLegacy(legacy(), setOf(right), emptyMap(), done = emptySet())
        val edited = first.profiles + (right to ChildProfile(baseItems = listOf("물통")))   // 해제 중 사용자가 기저귀를 지움
        val again = ChildProfiles.copyLegacy(legacy(), setOf(right), edited, done = first.doneSeats)
        assertEquals(listOf("물통"), again.profiles.getValue(right).baseItems)
        assertEquals(0, again.copied)
    }

    @Test
    fun `복사 뒤 예전 목록과 기본 물건에 같은 물건이 있어도 한 번만 말한다`() {
        val m = ChildProfiles.copyLegacy(legacy(), setOf(right), emptyMap(), done = emptySet())
        val e = CabinRoutineEngine().apply {
            childSeats = setOf(right); clock = wednesday; childProfiles = m.profiles
            items = legacy() + CarryItem(6, "물 통", ItemCondition.ALWAYS)     // 공백만 다른 같은 물건도 한 번
        }
        assertEquals("기저귀, 물통이랑 지갑 챙겼지? 조심히 내려.", open(e))
    }

    @Test
    fun `아이 좌석이 없으면 이전할 것이 없다`() {
        val m = ChildProfiles.copyLegacy(legacy(), emptySet(), emptyMap(), done = emptySet())
        assertTrue(m.profiles.isEmpty())
        assertTrue(m.doneSeats.isEmpty())
    }

    // ── 저장 형식 ──
    @Test
    fun `저장했다 읽으면 그대로고 구분 문자가 들어간 이름도 안전하다`() {
        val p = mapOf(
            right to ChildProfile(
                name = "", baseItems = listOf("물통", "a;b|c,d:e~f%g"),
                dayItems = mapOf(DayOfWeek.TUESDAY to listOf("수영 가방"), DayOfWeek.SUNDAY to listOf("성경책")),
            ),
            left to ChildProfile(baseItems = listOf("도시락")),
        )
        assertEquals(p, ChildProfiles.decode(ChildProfiles.encode(p)))
        assertEquals(emptyMap<SeatId, ChildProfile>(), ChildProfiles.decode(ChildProfiles.encode(emptyMap())))
        assertEquals(emptyMap<SeatId, ChildProfile>(), ChildProfiles.decode("깨진 값 %%%"))
        assertEquals(emptyMap<SeatId, ChildProfile>(), ChildProfiles.decode(null))
    }

    @Test
    fun `읽을 때 운전석 같은 아이 좌석 후보 밖은 버린다`() {
        val raw = "ROW_1_LEFT||x|"
        assertTrue(ChildProfiles.decode(raw).isEmpty())
    }

    @Test
    fun `입력 정리 - 공백과 길이 제한`() {
        assertEquals(null, ChildProfiles.cleanItem("   "))
        assertEquals("물통", ChildProfiles.cleanItem("  물통 "))
        assertEquals(ChildProfiles.MAX_ITEM_CHARS, ChildProfiles.cleanItem("가".repeat(50))!!.length)
    }

    // ── 도우미: 좌석 문이 열려 말한 문장 ──
    private fun open(e: CabinRoutineEngine, seat: SeatId = right): String {
        fun s(t: Long, st: SeatState) = VehicleSnapshot(seats = SeatId.entries.associateWith { SeatState() } + (seat to st), gear = Gear.PARK, timestampMs = t)
        val seated = SeatState(occupied = true, beltBuckled = true, windowOpenPercent = 0, heaterLevel = 0, ventilationLevel = 0)
        e.onSnapshot(s(0, seated)); e.onSnapshot(s(5_000, seated))
        val off = seated.copy(occupied = false, beltBuckled = false)
        e.onSnapshot(s(10_000, off))
        return e.onSnapshot(s(11_000, off.copy(doorOpen = true))).filterIsInstance<RoutineEvent.Speak>().joinToString(" ") { it.text }
    }
}
