package com.jaribom.app.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechQueueTest {

    /** 시간을 직접 진행시키는 가짜 스케줄러 */
    private class FakeScheduler : SpeechQueue.Scheduler {
        var now = 0L
        private class Task(val at: Long, val action: () -> Unit)
        private val tasks = mutableListOf<Task>()
        override fun post(delayMs: Long, action: () -> Unit): Any = Task(now + delayMs, action).also { tasks += it }
        override fun cancel(token: Any) { tasks.remove(token) }
        fun advance(ms: Long) {
            val until = now + ms
            while (true) {
                val next = tasks.filter { it.at <= until }.minByOrNull { it.at } ?: break
                tasks.remove(next); now = next.at; next.action()
            }
            now = until
        }
    }

    private class FakeFocus : SpeechQueue.Focus {
        var requests = 0; var abandons = 0; var held = false
        override fun request(): Boolean { requests++; held = true; return true }
        override fun abandon() { abandons++; held = false }
    }

    private class FakeEngine : SpeechQueue.Engine {
        val spoken = mutableListOf<Pair<Long, String>>()
        var stops = 0
        override fun speak(id: Long, text: String) { spoken += id to text }
        override fun stop() { stops++ }
    }

    private val sched = FakeScheduler()
    private val focus = FakeFocus()
    private val engine = FakeEngine()
    private val logs = mutableListOf<String>()
    private val q = SpeechQueue(focus, engine, sched, { logs += it }, prerollMs = 150)

    @Test
    fun `겹친 speak 2개는 순서대로 한 문장씩 말하고 끝나면 포커스를 한 번 반납한다`() {
        q.speak("조수석에 탑승을 확인했어요.")
        q.speak("뒷좌석 왼쪽에 탑승을 확인했어요.")          // 0.5초 차이로 겹침
        assertEquals(1, focus.requests)
        assertTrue(engine.spoken.isEmpty())                // 150ms 선행 묵음
        sched.advance(150)
        assertEquals(listOf("조수석에 탑승을 확인했어요."), engine.spoken.map { it.second })   // 첫 문장만 넘김

        q.onFinished(engine.spoken[0].first)
        assertEquals(2, engine.spoken.size)                // 끝나면 다음 문장(묵음 없이)
        assertEquals("뒷좌석 왼쪽에 탑승을 확인했어요.", engine.spoken[1].second)
        assertEquals(0, focus.abandons)

        q.onFinished(engine.spoken[1].first)
        assertEquals(1, focus.requests)
        assertEquals(1, focus.abandons)
        assertFalse(focus.held)
        assertFalse(q.isSpeaking)
    }

    @Test
    fun `엔진이 끝 신호를 보내지 않으면 타임아웃 뒤 끝난 것으로 보고 포커스를 반납한다`() {
        val text = "두고 가시는 물건은 없나요?"
        q.speak(text)
        sched.advance(150)
        assertEquals(1, engine.spoken.size)
        sched.advance(SpeechQueue.timeoutFor(text) - 1)
        assertEquals(0, focus.abandons)                    // 아직
        sched.advance(1)
        assertEquals(1, focus.abandons)
        assertTrue(logs.single().contains("끝 신호 없음"))
        assertFalse(q.isSpeaking)
    }

    @Test
    fun `멈춘 뒤 늦게 온 끝 신호는 새 세션의 문장을 끝내지 않는다`() {
        q.speak("첫 안내")
        sched.advance(150)
        val oldId = engine.spoken.single().first
        q.stopAll()                                        // 포커스 잃음 등으로 의도적 중단
        assertEquals(1, focus.abandons)
        assertEquals(1, engine.stops)

        q.speak("새 안내")
        sched.advance(150)
        assertEquals(2, focus.requests)
        q.onFinished(oldId)                                // 앞 문장의 늦은 onStop
        assertTrue(q.isSpeaking)                           // 새 문장은 그대로 진행 중
        assertEquals(1, focus.abandons)
        q.onFinished(engine.spoken.last().first)
        assertEquals(2, focus.abandons)
    }

    @Test
    fun `타임아웃은 글자 수 비례이고 최소 5초다`() {
        assertEquals(5_000L, SpeechQueue.timeoutFor("짧음"))
        assertEquals(40 * 150L + 3_000L, SpeechQueue.timeoutFor("가".repeat(40)))
    }
}
