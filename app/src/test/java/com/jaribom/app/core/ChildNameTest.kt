package com.jaribom.app.core

import kotlin.random.Random
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 7차 F11-3: 아이 이름 부르기. 호격 조사(아/야), 비한글·빈 이름, 택시 모드, 로그·알림에 이름이 남지 않음. */
class ChildNameTest {

    private val right = SeatId.ROW_2_RIGHT
    private val snap = VehicleSnapshot()

    @After fun clearRedactor() = LogRedactor.setNames(emptyList())

    @Test
    fun `호격 - 받침 있으면 아 없으면 야`() {
        assertEquals("지민아", KoreanPostposition.vocative("지민"))
        assertEquals("수아야", KoreanPostposition.vocative("수아"))
        assertEquals("서준아", KoreanPostposition.vocative("서준"))
        assertEquals("하윤아", KoreanPostposition.vocative("하윤"))
        assertEquals("도하야", KoreanPostposition.vocative("도하"))
    }

    @Test
    fun `한글이 아니면 이름만 쓰고 조사는 붙이지 않는다`() {
        assertEquals("Emma", KoreanPostposition.vocative("Emma"))
        assertEquals("Leo3", KoreanPostposition.vocative("Leo3"))
        assertEquals("Emma 민아", KoreanPostposition.vocative("Emma 민"))     // 마지막 글자가 한글이면 한글 규칙
    }

    @Test
    fun `빈 이름은 부르지 않는다`() {
        assertEquals("", KoreanPostposition.vocative(""))
        assertEquals("", KoreanPostposition.vocative("   "))
        val none = ExitMessageRules.childMessage(WeatherInfo(), snap, emptyList(), right, name = "  ").sentences
        assertEquals(listOf(ExitMessageRules.CHILD_BASE_PHRASE), none)
        assertEquals(listOf(ExitMessageRules.CHILD_BASE_PHRASE), ExitMessageRules.childMessage(WeatherInfo(), snap, emptyList(), right, name = null).sentences)
    }

    @Test
    fun `이름은 첫 문장 앞에 한 번만 붙는다`() {
        val clear = ExitMessageRules.childMessage(WeatherInfo(), snap, emptyList(), right, name = "지민").sentences
        assertEquals(listOf("지민아, 두고 가는 거 없지? 조심히 내려."), clear)
        val rain = ExitMessageRules.childMessage(WeatherInfo(rainingNow = true), snap, emptyList(), right, name = "수아").sentences
        assertEquals(listOf("수아야, 밖에 비 와. 우산 챙겨.", "두고 가는 거 없지? 조심히 내려."), rain)
        val en = ExitMessageRules.childMessage(WeatherInfo(), snap, emptyList(), right, name = "Emma").sentences
        assertEquals(listOf("Emma, 두고 가는 거 없지? 조심히 내려."), en)
    }

    @Test
    fun `늦게 합류한 아이 좌석 한 마디에도 이름이 붙는다`() {
        assertEquals("지민아, 두고 가는 거 없지? 조심히 내려.", ExitMessageRules.childJoinedMessage(emptyList(), right, name = "지민"))
        assertEquals("두고 가는 거 없지? 조심히 내려.", ExitMessageRules.childJoinedMessage(emptyList(), right))
    }

    @Test
    fun `풀에서 무작위로 골라도 이름이 항상 맨 앞이다`() {
        val p = PhrasePicker(Random(9))
        repeat(30) {
            val said = ExitMessageRules.childMessage(WeatherInfo(), snap, listOf(CarryItem(1, "지갑", ItemCondition.ALWAYS)), right, picker = p, name = "지민").sentences.joinToString(" ")
            assertTrue(said, said.startsWith("지민아, "))
            assertEquals(said, 1, Regex("지민").findAll(said).count())
        }
    }

    // ── 엔진 ──
    private fun speak(e: CabinRoutineEngine, speed: Float? = null): List<RoutineEvent> {
        fun s(t: Long, st: SeatState) = VehicleSnapshot(
            seats = SeatId.entries.associateWith { SeatState() } + (right to st), gear = if (speed == null) Gear.PARK else Gear.DRIVE,
            speedMps = speed, timestampMs = t,
        )
        val seated = SeatState(occupied = true, beltBuckled = true, windowOpenPercent = 0, heaterLevel = 0, ventilationLevel = 0)
        e.onSnapshot(s(0, seated)); e.onSnapshot(s(5_000, seated))
        val off = seated.copy(occupied = false, beltBuckled = false)
        e.onSnapshot(s(10_000, off))
        return e.onSnapshot(s(11_000, off.copy(doorOpen = true))) + e.onSnapshot(s(12_000, off.copy(doorOpen = false)))
    }

    private fun engine(name: String, taxi: Boolean = false) = CabinRoutineEngine().apply {
        childSeats = setOf(right); taxiMode = taxi
        childProfiles = mapOf(right to ChildProfile(name = name, baseItems = listOf("물통")))
    }

    private fun List<RoutineEvent>.spoken() = filterIsInstance<RoutineEvent.Speak>().joinToString(" ") { it.text }

    @Test
    fun `엔진 아이 좌석 하차 안내에 이름과 물건이 함께 나온다`() {
        assertEquals("지민아, 물통 챙겼지? 조심히 내려.", speak(engine("지민")).spoken())
        assertEquals("물통 챙겼지? 조심히 내려.", speak(engine("")).spoken())
    }

    @Test
    fun `택시 모드는 존댓말 고정이라 이름을 부르지 않는다`() {
        val said = speak(engine("지민", taxi = true)).spoken()
        assertFalse(said, said.contains("지민"))
        assertTrue(said, said.startsWith("두고 가시는 물건은 없나요?"))
    }

    @Test
    fun `로그 이벤트에 이름이 남지 않고 set unset 만 남는다`() {
        // 달리는 중(10m/s)이라 음성을 생략하고 문장을 로그로만 남기는 경로
        val named = speak(engine("지민"), speed = 10f)
        assertTrue(named.spoken().isEmpty())
        named.forEach { assertFalse("이벤트에 이름: $it", it.toString().contains("지민")) }
        val logs = named.filterIsInstance<RoutineEvent.Log>().map { it.text }
        assertTrue(logs.toString(), logs.any { it.contains("name=set") })

        val unnamed = speak(engine(""), speed = 10f).filterIsInstance<RoutineEvent.Log>().map { it.text }
        assertTrue(unnamed.toString(), unnamed.any { it.contains("name=unset") })
    }

    @Test
    fun `음성이 나가는 경우에도 Speak 말고 다른 이벤트에는 이름이 없다`() {
        val ev = speak(engine("지민"))
        assertTrue(ev.spoken().startsWith("지민아, "))
        ev.filterNot { it is RoutineEvent.Speak }.forEach { assertFalse("Speak 밖 이벤트에 이름: $it", it.toString().contains("지민")) }
    }

    @Test
    fun `로그 필터가 음성 문장 속 이름을 가린다`() {
        LogRedactor.setNames(listOf("지민"))
        val spoken = speak(engine("지민")).spoken()
        val out = LogRedactor.redact("🔊 $spoken")
        assertFalse(out, out.contains("지민"))
        assertTrue(out, out.contains(LogRedactor.MASK))
        assertEquals("name=set", LogRedactor.marker("지민"))
        assertEquals("name=unset", LogRedactor.marker(""))
        assertEquals("name=unset", LogRedactor.marker(null))
    }

    @Test
    fun `로그 필터 - 이름이 없으면 그대로이고 긴 이름이 먼저 가려진다`() {
        LogRedactor.setNames(emptyList())
        assertEquals("지민아, 안녕", LogRedactor.redact("지민아, 안녕"))
        LogRedactor.setNames(listOf("지민", "지민수"))
        assertEquals("${LogRedactor.MASK} 와 ${LogRedactor.MASK}", LogRedactor.redact("지민수 와 지민"))
    }

    @Test
    fun `이름은 저장 형식에 들어가고 통계 집계에는 문자열 칸이 없다`() {
        val p = mapOf(right to ChildProfile(name = "지민"))
        assertEquals("지민", ChildProfiles.decode(ChildProfiles.encode(p)).getValue(right).name)
        assertEquals(ChildProfiles.MAX_NAME_CHARS, ChildProfiles.cleanName("가".repeat(30)).length)
        // 통계는 Stats(숫자만)에서 만든다. 이름이 들어갈 칸이 없다.
        val statsJson = UsageTelemetry.toJson(UsageTelemetry.build("2026-11", CabinRoutineEngine.Stats(rides = 3, restores = 2), setOf(1, 2), "2026-10"))
        assertFalse(statsJson, statsJson.contains("지민"))
        assertTrue(UsageTelemetry.KEYS.none { it.contains("name", ignoreCase = true) })
    }
}
