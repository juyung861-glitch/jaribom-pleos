package com.jaribom.app.vehicle

import com.jaribom.app.core.SeatId
import com.jaribom.app.core.VehicleCommand
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F9 리뷰 반영: 서비스 stop 때 닫는 중인 창문이 있으면 정지 쓰기를 먼저 보내고 최대 [ShutdownOrder.GRACE_MS] 기다린 뒤 disconnect 한다.
 * 정지가 그 안에 끝나지 않아도 disconnect 는 진행하고 로그를 남긴다.
 */
class ShutdownOrderTest {

    private val front = SeatId.ROW_1_RIGHT

    @Test
    fun `닫는 중 stop - 정지 쓰기가 disconnect 보다 먼저 끝난다`() = runBlocking {
        val g = FakeVehicleGateway().apply {
            windowMove = FakeVehicleGateway.WindowMove(stepPct = 10, stepMs = 100)
            setWindow(front, 40)
        }
        val events = mutableListOf<String>()
        val closing = launch { WindowCloser.close(g, VehicleCommand.SetWindow(front, 0)) }
        delay(250)
        val pending = listOf(closing)
        closing.cancel()                                   // stop(): cancelChildren
        ShutdownOrder.run(pending, graceMs = 1_000, log = { events += "log:$it" }) { events += "disconnect(stopCalls=${g.stopWindowCalls})" }
        assertEquals(listOf("disconnect(stopCalls=1)"), events)       // 정지 호출 1번 뒤에 disconnect
    }

    @Test
    fun `정지가 무응답이면 유예 시간 뒤 disconnect 를 진행하고 로그를 남긴다`() = runBlocking {
        val never = async(start = CoroutineStart.LAZY) { delay(60_000) }
        never.start()
        val events = mutableListOf<String>()
        val t0 = System.currentTimeMillis()
        ShutdownOrder.run(listOf(never), graceMs = 300, log = { events += "log:$it" }) { events += "disconnect" }
        val waited = System.currentTimeMillis() - t0
        never.cancel()
        assertTrue("유예 시간만큼 기다린다: $waited", waited in 280..1_500)
        assertEquals(2, events.size)
        assertTrue(events[0].startsWith("log:"))
        assertEquals("disconnect", events[1])
    }

    @Test
    fun `진행 중인 닫기가 없으면 바로 disconnect`() = runBlocking {
        val events = mutableListOf<String>()
        ShutdownOrder.run(emptyList(), graceMs = 1_000, log = { events += "log:$it" }) { events += "disconnect" }
        assertEquals(listOf("disconnect"), events)
    }
}
