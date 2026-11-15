package com.jaribom.app.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** F9-4: 음성 실패 4종(엔진 오류·초기화 실패·타임아웃·포커스 거부)이 어느 문장인지 함께 알려진다. 정상 완료는 알리지 않는다. */
class SpeechFailureQueueTest {

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

    private class FakeFocus(var grant: Boolean = true) : SpeechQueue.Focus {
        override fun request() = grant
        override fun abandon() {}
    }

    private class FakeEngine : SpeechQueue.Engine {
        val spoken = mutableListOf<Pair<Long, String>>()
        override fun speak(id: Long, text: String) { spoken += id to text }
        override fun stop() {}
    }

    private val sched = FakeScheduler()
    private val engine = FakeEngine()
    private val failures = mutableListOf<Pair<String, SpeechQueue.Failure>>()
    private fun queue(grant: Boolean = true) = SpeechQueue(FakeFocus(grant), engine, sched, onFailed = { t, r -> failures += t to r })

    @Test
    fun `엔진 오류와 초기화 실패는 그 문장이 실패로 알려진다`() {
        val q = queue()
        q.speak("조수석 안내"); sched.advance(150)
        q.onFinished(engine.spoken[0].first, SpeechQueue.Failure.ENGINE_ERROR)
        q.speak("뒷좌석 안내"); sched.advance(150)
        q.onFinished(engine.spoken[1].first, SpeechQueue.Failure.INIT_FAILED)
        assertEquals(listOf("조수석 안내" to SpeechQueue.Failure.ENGINE_ERROR, "뒷좌석 안내" to SpeechQueue.Failure.INIT_FAILED), failures)
    }

    @Test
    fun `끝 신호가 없어 타임아웃이면 실패로 알린다`() {
        val q = queue()
        q.speak("안내"); sched.advance(150)
        sched.advance(SpeechQueue.timeoutFor("안내") + 1)
        assertEquals(listOf("안내" to SpeechQueue.Failure.TIMEOUT), failures)
    }

    @Test
    fun `포커스를 거부당하면 큐에 모인 문장이 모두 실패로 알려진다`() {
        val q = queue(grant = false)
        q.speak("첫 안내")
        q.speak("둘째 안내")
        assertEquals(setOf("첫 안내", "둘째 안내"), failures.map { it.first }.toSet())
        assertTrue(failures.all { it.second == SpeechQueue.Failure.FOCUS_DENIED })
    }

    @Test
    fun `정상 완료는 실패로 알리지 않는다`() {
        val q = queue()
        q.speak("안내"); sched.advance(150)
        q.onFinished(engine.spoken[0].first)
        assertTrue(failures.isEmpty())
    }
}
