package com.jaribom.app.core

import com.jaribom.app.vehicle.FakeVehicleGateway
import com.jaribom.app.vehicle.WindowCloser
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F9 리뷰 반영(안전): 벨트가 곧 착좌 신호인 BELT_DOOR 좌석의 [창문 닫기] 버튼은 눌렀을 때 벨트를 맨 사람이 있거나 문이 열려 있으면 닫지 않는다.
 * 닫는 도중 벨트 체결·문 열림은 F9-1 중단 처리가 막는다(아래 ④로 고정).
 */
class WindowCloseButtonGuardTest {

    private val seat = SeatId.ROW_2_LEFT

    private fun snap(s: SeatState) = VehicleSnapshot(seats = SeatId.entries.associateWith { SeatState() } + (seat to s), timestampMs = 1_000)
    private val e = CabinRoutineEngine().apply { seatModes = mapOf(seat to SeatSignalMode.BELT_DOOR) }

    @Test
    fun `벨트가 체결돼 있으면 닫지 않고 문구를 낸다 - 쓰기 0번`() {
        val s = snap(SeatState(beltBuckled = true, doorOpen = false, windowOpenPercent = 40))
        assertEquals("벨트를 맨 사람이 있어 창문을 닫지 않았어요.", e.windowCloseBlock(seat, s))
        val n = e.onWindowCloseBlocked(910_001, seat, e.windowCloseBlock(seat, s)!!)
        assertEquals("벨트를 맨 사람이 있어 창문을 닫지 않았어요.", n.text)
        assertTrue("차단 알림에는 닫기 버튼이 없다", n.windowClose.isEmpty())
    }

    @Test
    fun `문이 열려 있으면 닫지 않는다`() {
        assertEquals("문이 열려 있어 창문을 닫지 않았어요.", e.windowCloseBlock(seat, snap(SeatState(beltBuckled = false, doorOpen = true, windowOpenPercent = 40))))
    }

    @Test
    fun `벨트 풀림·문 닫힘이면 닫을 수 있다`() {
        assertNull(e.windowCloseBlock(seat, snap(SeatState(beltBuckled = false, doorOpen = false, windowOpenPercent = 40))))
        assertNull(e.windowCloseBlock(seat, snap(SeatState(beltBuckled = null, doorOpen = false, windowOpenPercent = 40))))
    }

    @Test
    fun `닫는 도중 벨트가 체결되면 정지한다 - 중단 처리가 막는다`() = runBlocking {
        val g = FakeVehicleGateway().apply {
            windowMove = FakeVehicleGateway.WindowMove(stepPct = 10, stepMs = 100)
            setWindow(seat, 40)
            setBelt(seat, false)
        }
        val closing = async { WindowCloser.close(g, VehicleCommand.SetWindow(seat, 0)) }
        delay(250)
        g.setBelt(seat, true)
        val o = closing.await()
        assertEquals(WindowAbortReason.BELT, (o as WindowCloser.Outcome.Aborted).reason)
        assertEquals(1, g.stopWindowCalls)
    }
}
