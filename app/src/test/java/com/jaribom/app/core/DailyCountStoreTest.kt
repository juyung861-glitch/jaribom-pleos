package com.jaribom.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** F7-4: 하루 보조 알림 횟수를 날짜와 함께 저장해 앱을 다시 시작해도 이어서 센다. 날짜가 바뀐 값은 0 으로 읽는다. */
class DailyCountStoreTest {

    /** SharedPreferences 대신 쓰는 메모리 저장소(ItemStore 와 같은 계약) */
    private class MemStore : DailyCountStore {
        var saved: Pair<String, Int>? = null
        override fun load(): Pair<String, Int>? = saved
        override fun save(day: String, count: Int) { saved = day to count }
        override fun clear() { saved = null }
    }

    private val right = SeatId.ROW_2_RIGHT

    @Test
    fun `3번 보낸 뒤 저장소를 다시 열어도 3이고 이어서 센다`() {
        val store = MemStore()
        val day = "2026-11-04"
        val first = AlertLimiter(store) { day }
        repeat(3) { first.onSeatDoorOpened(right); assertTrue(first.tryAssist(right, it.toLong())) }
        assertEquals(day to 3, store.saved)

        val restarted = AlertLimiter(store) { day }          // 앱을 다시 켬
        assertEquals(3, restarted.assistCountToday(0))
        val more = (0 until 3).map { restarted.onSeatDoorOpened(right); restarted.tryAssist(right, 10L + it) }
        assertEquals(listOf(true, true, false), more)         // 하루 5번에서 멈춤
    }

    @Test
    fun `날짜가 바뀐 저장값은 0으로 읽는다`() {
        val store = MemStore().apply { saved = "2026-11-03" to 5 }
        val l = AlertLimiter(store) { "2026-11-04" }
        assertEquals(0, l.assistCountToday(0))
        assertTrue(l.tryAssist(right, 0))
        assertEquals("2026-11-04" to 1, store.saved)
    }

    @Test
    fun `기록 초기화는 저장값과 메모리 횟수를 모두 0으로 만든다`() {
        val store = MemStore()
        val l = AlertLimiter(store) { "2026-11-04" }
        repeat(5) { l.onSeatDoorOpened(right); l.tryAssist(right, it.toLong()) }
        l.onSeatDoorOpened(right)
        assertFalse(l.tryAssist(right, 9))
        l.resetDaily()
        assertEquals(null, store.saved)
        assertEquals(0, l.assistCountToday(0))
        assertTrue(l.tryAssist(right, 10))
    }
}
