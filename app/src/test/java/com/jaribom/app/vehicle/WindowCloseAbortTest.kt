package com.jaribom.app.vehicle

import com.jaribom.app.core.SeatId
import com.jaribom.app.core.VehicleCommand
import com.jaribom.app.core.WindowAbortReason
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F9-1: 창문을 닫는 도중 그 좌석의 문 열림·착좌·벨트 체결(또는 연결 끊김)이 생기면 즉시 멈춘다. 자동 재개는 없다.
 * Fake 의 "천천히 움직이는 창문"(100ms 에 10%)으로 닫는 도중을 만든다.
 */
class WindowCloseAbortTest {

    private val front = SeatId.ROW_1_RIGHT
    private val rear = SeatId.ROW_2_LEFT

    private fun gateway() = FakeVehicleGateway().apply {
        windowMove = FakeVehicleGateway.WindowMove(stepPct = 10, stepMs = 100)
        setWindow(front, 40)
    }

    private fun FakeVehicleGateway.pos() = snapshot.value.seat(front).windowOpenPercent

    private suspend fun FakeVehicleGateway.closeAndTrip(trip: FakeVehicleGateway.() -> Unit): WindowCloser.Outcome = kotlinx.coroutines.coroutineScope {
        val job = async { WindowCloser.close(this@closeAndTrip, VehicleCommand.SetWindow(front, 0)) }
        delay(250)
        trip()
        job.await()
    }

    private suspend fun FakeVehicleGateway.assertStoppedForGood() {
        val stoppedAt = pos()!!
        assertTrue("중간에 멈춰야 한다: $stoppedAt", stoppedAt in 1..39)
        delay(600)
        assertEquals("멈춘 뒤 다시 움직이면 안 된다(재개 없음)", stoppedAt, pos())
    }

    @Test
    fun `닫는 중 그 좌석 문이 열리면 정지 쓰기 1번 - 재개 없음`() = runBlocking {
        val g = gateway()
        val o = g.closeAndTrip { simulateDoorOpened(front, true) }
        assertEquals(WindowAbortReason.DOOR, (o as WindowCloser.Outcome.Aborted).reason)
        assertEquals(1, g.stopWindowCalls)
        g.assertStoppedForGood()
        assertEquals(1, g.stopWindowCalls)
    }

    @Test
    fun `닫는 중 착좌하면 정지한다`() = runBlocking {
        val g = gateway()
        val o = g.closeAndTrip { setOccupied(front, true) }
        assertEquals(WindowAbortReason.SEAT, (o as WindowCloser.Outcome.Aborted).reason)
        assertEquals(1, g.stopWindowCalls)
        g.assertStoppedForGood()
    }

    @Test
    fun `닫는 중 벨트가 채워지면 정지한다`() = runBlocking {
        val g = gateway()
        val o = g.closeAndTrip { setBelt(front, true) }
        assertEquals(WindowAbortReason.BELT, (o as WindowCloser.Outcome.Aborted).reason)
        assertEquals(1, g.stopWindowCalls)
        g.assertStoppedForGood()
    }

    @Test
    fun `다른 좌석 문이 열려도 계속 닫는다`() = runBlocking {
        val g = gateway()
        val o = g.closeAndTrip { simulateDoorOpened(rear, true) }
        assertTrue(o is WindowCloser.Outcome.Done && o.result.isSuccess)
        assertEquals(0, g.stopWindowCalls)
        assertEquals(0, g.pos())
    }

    @Test
    fun `서비스 stop 으로 닫는 코루틴이 취소돼도 닫는 중이던 창문은 멈춘다`() = runBlocking {
        val g = gateway()
        val job = launch { WindowCloser.close(g, VehicleCommand.SetWindow(front, 0)) }
        delay(250)
        job.cancel()
        job.join()
        assertEquals(1, g.stopWindowCalls)
        g.assertStoppedForGood()
    }

    @Test
    fun `정지 요청 뒤에도 몇 칸 더 움직일 수 있고 최종 위치는 거기서 멈춘다`() = runBlocking {
        val g = gateway().apply { stopOvershootSteps = 1 }
        val o = g.closeAndTrip { simulateDoorOpened(front, true) }
        assertTrue((o as WindowCloser.Outcome.Aborted).stop.isSuccess)
        val p = g.pos()!!
        assertTrue("정지 뒤에 한 칸 더 움직여 중간에 멈춘다: $p", p in 1..29)
        delay(600)
        assertEquals(p, g.pos())
    }

    @Test
    fun `정지 쓰기가 실패하면 Aborted 의 stop 이 실패로 남는다`() = runBlocking {
        val g = gateway().apply { stopFails = true }
        val o = g.closeAndTrip { simulateDoorOpened(front, true) } as WindowCloser.Outcome.Aborted
        assertTrue(o.stop.isFailure)
        assertEquals(WindowAbortReason.DOOR, o.reason)
    }

    @Test
    fun `아무 일 없으면 정상 성공`() = runBlocking {
        val g = gateway()
        val o = WindowCloser.close(g, VehicleCommand.SetWindow(front, 0))
        assertTrue(o is WindowCloser.Outcome.Done && o.result.isSuccess)
        assertEquals(0, g.stopWindowCalls)
        assertEquals(0, g.pos())
    }

    // ── F13-2: 닫는 중 같은 열 옆자리(운전석 제외)에 착좌·벨트가 새로 생기면 정지 ──

    private val rearRight = SeatId.ROW_2_RIGHT

    private suspend fun FakeVehicleGateway.closeRearAndTrip(trip: FakeVehicleGateway.() -> Unit): WindowCloser.Outcome = kotlinx.coroutines.coroutineScope {
        val job = async { WindowCloser.close(this@closeRearAndTrip, VehicleCommand.SetWindow(rear, 0)) }
        delay(250)
        trip()
        job.await()
    }

    private fun rearGateway() = FakeVehicleGateway().apply {
        windowMove = FakeVehicleGateway.WindowMove(stepPct = 10, stepMs = 100)
        setWindow(rear, 40)
    }

    @Test
    fun `F13-2 뒷좌석 왼쪽을 닫는 중 오른쪽에 사람이 앉으면 정지한다`() = runBlocking {
        val g = rearGateway()
        val o = g.closeRearAndTrip { setOccupied(rearRight, true) }
        assertEquals(WindowAbortReason.NEIGHBOR, (o as WindowCloser.Outcome.Aborted).reason)
        assertEquals(1, g.stopWindowCalls)
    }

    @Test
    fun `F13-2 닫는 중 오른쪽 벨트만 채워져도 정지한다`() = runBlocking {
        val g = rearGateway()
        val o = g.closeRearAndTrip { setBelt(rearRight, true) }
        assertEquals(WindowAbortReason.NEIGHBOR, (o as WindowCloser.Outcome.Aborted).reason)
    }

    @Test
    fun `F13-2 시작 때 이미 옆자리에 있던 사람은 원인이 아니다 - 버튼으로 운전자가 직접 닫는 경우`() = runBlocking {
        val g = rearGateway().apply { setOccupied(rearRight, true) }
        val o = WindowCloser.close(g, VehicleCommand.SetWindow(rear, 0))
        assertTrue(o is WindowCloser.Outcome.Done && o.result.isSuccess)
        assertEquals(0, g.stopWindowCalls)
    }

    @Test
    fun `F13-2 조수석을 닫는 중 운전석에 앉아도 계속 닫는다 - 운전석은 옆자리로 보지 않음`() = runBlocking {
        val g = gateway()
        val o = g.closeAndTrip { setOccupied(SeatId.ROW_1_LEFT, true) }
        assertTrue(o is WindowCloser.Outcome.Done && o.result.isSuccess)
        assertEquals(0, g.stopWindowCalls)
    }

    @Test
    fun `닫는 중 사망 감지면 정지 시도 없이 연결 없음으로 처리한다`() = runBlocking {
        val g = gateway()
        g.connect()
        g.connectFailures = 1
        g.sleepFn = { delay(1_500) }
        val closing = async { WindowCloser.close(g, VehicleCommand.SetWindow(front, 0)) }
        delay(250)
        val death = launch { g.simulateServiceDeath() }          // 사망 신호(재연결 대기에 들어감)
        val o = closing.await()
        death.cancel()
        assertTrue(o is WindowCloser.Outcome.Done)
        val r = (o as WindowCloser.Outcome.Done).result
        assertTrue(r.isFailure)
        assertNotNull(r.exceptionOrNull()!!.message)
        assertTrue(r.exceptionOrNull()!!.message!!.contains("연결 없음"))
        assertEquals("쓰기 불가이므로 정지 시도를 하지 않는다", 0, g.stopWindowCalls)
    }
}
