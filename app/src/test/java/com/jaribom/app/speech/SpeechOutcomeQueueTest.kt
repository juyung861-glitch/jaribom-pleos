package com.jaribom.app.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** F12-2: 음성이 끝까지 나간 문장(onSpoken)과 포커스를 잃어 버려진 문장(onAbandoned)을 구분해 알린다. */
class SpeechOutcomeQueueTest {

    private class Sched : SpeechQueue.Scheduler {
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

    private class Engine : SpeechQueue.Engine {
        val spoken = mutableListOf<Long>()
        override fun speak(id: Long, text: String) { spoken += id }
        override fun stop() {}
    }

    private val sched = Sched()
    private val engine = Engine()
    private val spoken = mutableListOf<String>()
    private val abandoned = mutableListOf<String>()
    private val failed = mutableListOf<String>()
    private val q = SpeechQueue(
        object : SpeechQueue.Focus { override fun request() = true; override fun abandon() {} }, engine, sched,
        onFailed = { t, _ -> failed += t }, onSpoken = { spoken += it }, onAbandoned = { abandoned += it },
    )

    @Test
    fun `정상 완료만 onSpoken 이고 실패는 onSpoken 이 아니다`() {
        q.speak("보고"); sched.advance(150)
        q.onFinished(engine.spoken[0])
        q.speak("다음"); sched.advance(150)
        q.onFinished(engine.spoken[1], SpeechQueue.Failure.ENGINE_ERROR)
        assertEquals(listOf("보고"), spoken)
        assertEquals(listOf("다음"), failed)
    }

    @Test
    fun `포커스를 잃으면 말하던 문장과 대기 문장이 onAbandoned 로 나온다`() {
        q.speak("첫째"); q.speak("둘째"); sched.advance(150)
        q.stopAll()
        assertEquals(listOf("첫째", "둘째"), abandoned)
        assertTrue(spoken.isEmpty() && failed.isEmpty())
    }
}
