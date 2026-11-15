package com.jaribom.app.core

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 7차 F11-1: 하차 한마디 변주. 말투별 문장 풀, 직전 문장 피하기, 택시 모드 존댓말 고정, 물건 조사. */
class PhrasePoolTest {

    private val seat = SeatId.ROW_2_RIGHT
    private val snap = VehicleSnapshot()
    private val clear = WeatherInfo()
    private val rain = WeatherInfo(rainingNow = true)
    private val soon = WeatherInfo(rainWithinHour = true)
    private fun item(i: Int, n: String) = CarryItem(i.toLong(), n, ItemCondition.ALWAYS)

    private fun child(w: WeatherInfo, items: List<CarryItem>, p: PhrasePicker) =
        ExitMessageRules.childMessage(w, snap, items, seat, picker = p).sentences.joinToString(" ")

    private fun polite(w: WeatherInfo, items: List<CarryItem>, p: PhrasePicker, s: SeatId = seat) =
        ExitMessageRules.passengerMessage(w, snap, items, seat = s, picker = p).sentences.joinToString(" ")

    @Test
    fun `풀은 모두 3~5문장이고 0번이 예전 문장이다`() {
        (ExitPhrasePool.allChild + ExitPhrasePool.allPolite).forEach { assertTrue(it.id, it.sentences.size in 3..5) }
        assertEquals("두고 가는 거 없지? 조심히 내려.", ExitMessageRules.CHILD_BASE_PHRASE)
        assertEquals(ExitMessageRules.CHILD_BASE_PHRASE, ExitPhrasePool.Child.base.sentences.first())
        assertEquals(ExitMessageRules.BASE_PHRASE, ExitPhrasePool.Polite.base.sentences.first())
    }

    @Test
    fun `선택기를 안 넣으면 예전 문장 그대로다`() {
        assertEquals("밖에 비 와. 우산 챙겨. 두고 가는 거 없지? 조심히 내려.", child(rain, emptyList(), PhrasePicker.LEGACY))
        assertEquals("두고 가시는 물건은 없나요? 지갑 챙기셨어요?", polite(clear, listOf(item(1, "지갑")), PhrasePicker.LEGACY))
    }

    @Test
    fun `같은 상황에서 연속 두 번 같은 문장이 나오지 않는다`() {
        val situations = listOf(
            clear to emptyList<CarryItem>(), rain to emptyList(), soon to emptyList(),
            clear to listOf(item(1, "지갑")), rain to listOf(item(1, "지갑"), item(2, "수영복")),
        )
        for ((w, items) in situations) {
            for (tone in listOf("child", "polite")) {
                val p = PhrasePicker(Random(7))
                var prev: String? = null
                repeat(60) {
                    val now = if (tone == "child") child(w, items, p) else polite(w, items, p)
                    assertNotEquals("$tone 연속 같은 문장: $now", prev, now)
                    prev = now
                }
            }
        }
    }

    @Test
    fun `풀의 문장이 실제로 여러 가지 나온다`() {
        val p = PhrasePicker(Random(3))
        val seen = (1..60).map { child(clear, emptyList(), p) }.toSet()
        assertEquals(ExitPhrasePool.Child.base.sentences.toSet(), seen)
    }

    @Test
    fun `시드가 같으면 결과가 같다`() {
        fun run(seed: Int) = PhrasePicker(Random(seed)).let { p -> (1..10).map { child(rain, listOf(item(1, "지갑")), p) } }
        assertEquals(run(42), run(42))
    }

    @Test
    fun `좌석마다 직전 문장을 따로 기억한다`() {
        val p = PhrasePicker(Random(1))
        val a = p.pick(SeatId.ROW_2_LEFT, ExitPhrasePool.Child.base)
        // 다른 좌석을 몇 번 거쳐도 왼쪽 좌석의 직전 문장 기록은 그대로다.
        repeat(30) { p.pick(SeatId.ROW_2_RIGHT, ExitPhrasePool.Child.base) }
        val b = p.pick(SeatId.ROW_2_LEFT, ExitPhrasePool.Child.base)
        assertNotEquals(a, b)
    }

    @Test
    fun `택시 모드 존댓말 문장에는 반말 풀이 절대 섞이지 않는다`() {
        val childTemplates = ExitPhrasePool.allChild.flatMap { it.sentences }.filterNot { ExitPhrasePool.ITEMS_SLOT in it }
        for (seed in 1..40) {
            val p = PhrasePicker(Random(seed))
            for (w in listOf(clear, rain, soon, WeatherInfo(outdoorTempC = -3f))) {
                for (items in listOf(emptyList(), listOf(item(1, "지갑")), listOf(item(1, "지갑"), item(2, "우산"), item(3, "마스크")))) {
                    val said = ExitMessageRules.passengerMessage(w, snap, items, seat = seat, picker = p).sentences
                    said.forEach { s ->
                        assertFalse("반말 문장 선택됨: $s", s in childTemplates)
                        assertTrue("존댓말 풀 밖 문장: $s", ExitPhrasePool.allPolite.any { pool ->
                            pool.sentences.any { t -> s == t || (ExitPhrasePool.ITEMS_SLOT in t && s.endsWith(t.substringAfter(ExitPhrasePool.ITEMS_SLOT)) && s.startsWith(t.substringBefore(ExitPhrasePool.ITEMS_SLOT))) }
                        })
                    }
                }
            }
        }
    }

    @Test
    fun `엔진 택시 모드는 아이 좌석으로 지정해도 존댓말만 말한다`() {
        val childTemplates = ExitPhrasePool.allChild.flatMap { it.sentences }.map { it.substringBefore(ExitPhrasePool.ITEMS_SLOT).trim() }.filter { it.isNotEmpty() }
        for (seed in 1..20) {
            val e = CabinRoutineEngine().apply {
                childSeats = setOf(seat); taxiMode = true; phrasePicker = PhrasePicker(Random(seed))
                items = listOf(item(1, "지갑"))
            }
            fun s(t: Long, st: SeatState) = VehicleSnapshot(seats = SeatId.entries.associateWith { SeatState() } + (seat to st), gear = Gear.PARK, timestampMs = t)
            val seated = SeatState(occupied = true, beltBuckled = true, windowOpenPercent = 0, heaterLevel = 0, ventilationLevel = 0)
            e.onSnapshot(s(0, seated)); e.onSnapshot(s(5_000, seated))
            val off = seated.copy(occupied = false, beltBuckled = false)
            e.onSnapshot(s(10_000, off))
            val said = e.onSnapshot(s(11_000, off.copy(doorOpen = true))).filterIsInstance<RoutineEvent.Speak>().joinToString(" ") { it.text }
            assertTrue(said, said.isNotEmpty())
            assertFalse("택시 모드에서 반말: $said", childTemplates.any { said.contains(it) })
            assertTrue(said, said.contains("두고 가시는") || said.contains("놓고 내리시는") || said.contains("빠뜨리신") || said.contains("내리시기 전에"))
        }
    }

    @Test
    fun `물건 0개 1개 3개 문장과 조사가 풀의 모든 틀에서 맞다`() {
        val one = listOf(item(1, "지갑"))
        val three = listOf(item(1, "지갑"), item(2, "우산"), item(3, "마스크"))
        for (seed in 1..30) {
            val p = PhrasePicker(Random(seed))
            val c0 = child(clear, emptyList(), p)
            assertTrue(c0, ExitPhrasePool.Child.base.sentences.contains(c0))
            val c1 = child(clear, one, p)
            assertTrue(c1, ExitPhrasePool.Child.items.sentences.any { c1 == ExitPhrasePool.fill(it, "지갑") })
            val c3 = child(clear, three, p)
            assertTrue(c3, ExitPhrasePool.Child.items.sentences.any { c3 == ExitPhrasePool.fill(it, "지갑, 우산이랑 마스크") })
            val p3 = polite(clear, three, p)
            assertTrue(p3, ExitPhrasePool.Polite.items.sentences.any { p3.endsWith(ExitPhrasePool.fill(it, "지갑, 우산과 마스크")) })
        }
    }

    @Test
    fun `비 오면 우산을 목록에서 빼서 날씨 문장과 겹치지 않는다`() {
        val p = PhrasePicker(Random(5))
        repeat(20) {
            val said = child(rain, listOf(item(1, "지갑"), item(2, "우산")), p)
            assertFalse(said, said.contains("우산이랑") || said.contains("지갑이랑"))
            assertTrue(said, ExitPhrasePool.Child.rainNow.sentences.any { said.startsWith(it) })
        }
    }
}
