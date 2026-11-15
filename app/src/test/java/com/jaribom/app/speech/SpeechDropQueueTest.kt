package com.jaribom.app.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F8-1: 큐에서 재생 차례가 된 문장이 가드(dropIf)에 걸리면 재생하지 않고 버린다.
 * 재생 중인 문장은 끊지 않고, 의도적 버림은 음성 실패(onFailed)가 아니다.
 */
class SpeechDropQueueTest {

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
        var abandoned = 0
        override fun request() = true
        override fun abandon() { abandoned++ }
    }

    private class FakeEngine : SpeechQueue.Engine {
        val spoken = mutableListOf<Pair<Long, String>>()
        override fun speak(id: Long, text: String) { spoken += id to text }
        override fun stop() {}
    }

    private val sched = FakeScheduler()
    private val focus = FakeFocus()
    private val engine = FakeEngine()
    private val failures = mutableListOf<String>()
    private val drops = mutableListOf<Pair<String, String>>()
    private val q = SpeechQueue(focus, engine, sched, onFailed = { t, _ -> failures += t }, onDropped = { t, r -> drops += t to r })

    @Test
    fun `가드가 null이면 그대로 재생한다`() {
        q.speak("안내", dropIf = { null })
        sched.advance(150)
        assertEquals(listOf("안내"), engine.spoken.map { it.second })
        assertTrue(drops.isEmpty())
    }

    @Test
    fun `대기 중이던 문장은 차례가 됐을 때 가드에 걸리면 버려진다 - 재생 중인 문장은 끊지 않는다`() {
        var stale = false
        q.speak("앞 문장")
        q.speak("동승자 안내", dropIf = { if (stale) "문이 닫힌 지 6초" else null })
        sched.advance(150)
        stale = true                                   // 앞 문장이 말해지는 동안 상황이 바뀜
        assertEquals(listOf("앞 문장"), engine.spoken.map { it.second })   // 앞 문장은 끊기지 않고 계속 재생 중
        q.onFinished(engine.spoken[0].first)
        assertEquals(listOf("앞 문장"), engine.spoken.map { it.second })   // 차례가 된 문장은 재생되지 않음
        assertEquals(listOf("동승자 안내" to "문이 닫힌 지 6초"), drops)
        assertTrue("의도적 버림은 음성 실패가 아님: $failures", failures.isEmpty())
        assertTrue(!q.isSpeaking)
        assertEquals(1, focus.abandoned)               // 큐가 비어 포커스를 반납
    }

    @Test
    fun `버려진 문장 뒤의 문장은 이어서 재생한다`() {
        q.speak("앞 문장")
        q.speak("버릴 문장", dropIf = { "금지" })
        q.speak("뒤 문장")
        sched.advance(150)
        q.onFinished(engine.spoken[0].first)
        assertEquals(listOf("앞 문장", "뒤 문장"), engine.spoken.map { it.second })
        assertEquals(listOf("버릴 문장"), drops.map { it.first })
        assertTrue(failures.isEmpty())
    }
}
