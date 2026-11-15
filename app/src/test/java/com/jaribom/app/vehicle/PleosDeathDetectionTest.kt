package com.jaribom.app.vehicle

import com.jaribom.app.core.LinkWatchdog
import com.jaribom.app.core.SeatId
import com.jaribom.app.core.VehicleCommand
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F7-2: Pleos 차량 서비스에는 공개 연결 상태 API 가 없다(SDK 2.3.2 조사). 탐침 읽기가 연속 [LinkWatchdog.FAILS_TO_DEAD] 번
 * 실패(응답 없음·실패·null)하면 사망으로 보고 F6-4 와 같은 재연결 경로를 탄다.
 */
class PleosDeathDetectionTest {

    private fun connected() = FakeVehicleGateway().also { g -> g.sleepFn = {}; runBlocking { g.connect() } }

    @Test
    fun `탐침이 연속 3번 실패하면 3번째에 사망으로 판정한다`() = runBlocking {
        val g = connected()
        g.probeFailures = 3
        g.pollOnce(); g.pollOnce()
        assertEquals(0, g.deathsDetected)
        g.pollOnce()
        assertEquals(1, g.deathsDetected)
        assertEquals(3, LinkWatchdog.FAILS_TO_DEAD)
    }

    @Test
    fun `2번 실패한 뒤 성공하면 사망이 아니고 셈을 처음부터 한다`() = runBlocking {
        val g = connected()
        g.probeFailures = 2
        g.pollOnce(); g.pollOnce(); g.pollOnce()          // 실패, 실패, 성공
        g.probeFailures = 2
        g.pollOnce(); g.pollOnce()                        // 실패, 실패 (연속 2)
        assertEquals(0, g.deathsDetected)
    }

    @Test
    fun `값 null이나 일반 오류로 답하면 살아 있는 것이고, 시간 초과와 연결 실패만 센다`() {
        val w = LinkWatchdog()
        val P = LinkWatchdog.ProbeResult.TIMEOUT
        assertEquals(LinkWatchdog.ProbeResult.DISCONNECTED, LinkWatchdog.classifyError("Service connection failed"))
        assertEquals(LinkWatchdog.ProbeResult.ANSWERED, LinkWatchdog.classifyError("area ID: 0x1 not supported"))
        assertEquals(LinkWatchdog.ProbeResult.ANSWERED, LinkWatchdog.classifyError(null))
        assertTrue(!w.record(P)); assertTrue(!w.record(P))
        assertTrue(!w.record(LinkWatchdog.ProbeResult.ANSWERED))          // null·일반 오류 = 살아 있음 → 초기화
        assertTrue(!w.record(P)); assertTrue(!w.record(LinkWatchdog.ProbeResult.DISCONNECTED))
        assertTrue(w.record(P))                                          // 연속 3번째
        assertTrue(!w.record(P))                                         // 한 번만 알린다
    }

    @Test
    fun `첫 탐침 결과로 다음 동작 - DISCONNECT 면 initialize 를 다시, 붙는 중이면 탐침만 다시`() {
        assertEquals(LinkWatchdog.ConnectStep.CONNECTED, LinkWatchdog.nextStep(LinkWatchdog.ProbeResult.OK))
        assertEquals(LinkWatchdog.ConnectStep.CONNECTED, LinkWatchdog.nextStep(LinkWatchdog.ProbeResult.ANSWERED))
        assertEquals(LinkWatchdog.ConnectStep.REPROBE, LinkWatchdog.nextStep(LinkWatchdog.ProbeResult.TIMEOUT))
        assertEquals(LinkWatchdog.ConnectStep.REINITIALIZE, LinkWatchdog.nextStep(LinkWatchdog.ProbeResult.DISCONNECTED))
    }

    @Test
    fun `앱 쪽 동기 예외는 바인더 사망이 아니라 ERROR - initialize 를 다시 부르지 않고 탐침만 다시, 사망 카운트에는 넣지 않는다`() {
        assertEquals(LinkWatchdog.ConnectStep.REPROBE, LinkWatchdog.nextStep(LinkWatchdog.ProbeResult.ERROR))
        // ERROR 는 정보가 없어 사망 카운트를 늘리지도 지우지도 않는다(앱 쪽 버그로 끝없이 재연결하지 않게)
        val w = LinkWatchdog()
        repeat(10) { assertTrue(!w.record(LinkWatchdog.ProbeResult.ERROR)) }
        val t = LinkWatchdog.ProbeResult.TIMEOUT
        assertTrue(!w.record(t)); assertTrue(!w.record(LinkWatchdog.ProbeResult.ERROR)); assertTrue(!w.record(t))
        assertTrue(w.record(t))
    }

    @Test
    fun `첫 탐침이 계속 ERROR 면 3번째는 판정 불가로 보고 연결로 진행한다 - 구독이 영원히 안 걸리지 않게`() {
        val e = LinkWatchdog.ProbeResult.ERROR
        assertEquals(LinkWatchdog.ConnectStep.REPROBE, LinkWatchdog.nextStep(e, consecutiveErrors = 1))
        assertEquals(LinkWatchdog.ConnectStep.REPROBE, LinkWatchdog.nextStep(e, consecutiveErrors = 2))
        assertEquals(LinkWatchdog.ConnectStep.CONNECTED, LinkWatchdog.nextStep(e, consecutiveErrors = LinkWatchdog.MAX_FIRST_ERRORS))
        // TIMEOUT 은 여전히 계속 재탐침
        assertEquals(LinkWatchdog.ConnectStep.REPROBE, LinkWatchdog.nextStep(LinkWatchdog.ProbeResult.TIMEOUT, consecutiveErrors = 9))
    }

    @Test
    fun `끊긴 동안에는 linked 가 false 라 엔진에 마지막 값을 넣지 않고, 다시 붙으면 true`() = runBlocking {
        val g = connected()
        assertTrue(g.linked.value)
        var during: Boolean? = null
        g.probeFailures = 3
        g.connectFailures = 1
        g.sleepFn = { during = g.linked.value }
        repeat(3) { g.pollOnce() }
        assertEquals(false, during)
        assertTrue(g.linked.value)
    }

    @Test
    fun `사망 판정 뒤 재연결하면 리스너는 1배다`() = runBlocking {
        val g = connected()
        g.probeFailures = 3
        g.connectFailures = 2                             // 재연결도 두 번 실패한 뒤 성공
        repeat(3) { g.pollOnce() }
        assertEquals(1, g.deathsDetected)
        assertEquals(1, g.listenerCount)
        // 다시 붙은 뒤에는 정상 탐침이 사망으로 이어지지 않는다
        repeat(5) { g.pollOnce() }
        assertEquals(1, g.deathsDetected)
    }

    @Test
    fun `사망 판정 뒤 재연결 중 쓰기는 연결 없음으로 실패한다`() = runBlocking {
        val g = connected()
        var during: Result<Unit>? = null
        g.probeFailures = 3
        g.connectFailures = 1
        g.sleepFn = { during = g.execute(VehicleCommand.SetWindow(SeatId.ROW_1_RIGHT, 0)) }
        repeat(3) { g.pollOnce() }
        assertTrue(during!!.isFailure)
        assertTrue(during!!.exceptionOrNull()!!.message!!.contains("연결 없음"))
    }
}
