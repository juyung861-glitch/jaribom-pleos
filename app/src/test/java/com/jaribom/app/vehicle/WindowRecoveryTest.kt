package com.jaribom.app.vehicle

import com.jaribom.app.core.CabinRoutineEngine
import com.jaribom.app.core.PendingWindowWrite
import com.jaribom.app.core.RecoveryVerdict
import com.jaribom.app.core.RoutineEvent
import com.jaribom.app.core.SeatId
import com.jaribom.app.core.SeatState
import com.jaribom.app.core.UnconfirmedWindowWrites
import com.jaribom.app.core.VehicleCommand
import com.jaribom.app.core.WindowAbortReason
import com.jaribom.app.core.WindowRecoveryRules
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F8-2: 서비스 사망 때 "보냈지만 결과를 못 받은 창문 쓰기"를 남기고, 재연결 뒤 그 좌석 창문을 다시 읽어 확인한다.
 * 새 닫기 쓰기는 하지 않는다(움직이는 중 위험이 있으면 정지만).
 */
class WindowRecoveryTest {

    private val seat = SeatId.ROW_1_RIGHT
    private val pending = PendingWindowWrite(seat, target = 0, before = 40, atMs = 1_000)
    private fun st(pos: Int?, occupied: Boolean? = false, door: Boolean? = false, belt: Boolean? = false) =
        SeatState(windowOpenPercent = pos, occupied = occupied, doorOpen = door, beltBuckled = belt)

    // ── 순수 판정 ──
    @Test
    fun `목표에 닿았고 빈 좌석이면 성공 - 알림 없음`() {
        assertEquals(RecoveryVerdict.Done(seat), WindowRecoveryRules.decide(pending, st(0), moving = false))
    }

    @Test
    fun `아직 움직이는 중이고 문 열림·착좌·벨트면 정지`() {
        assertEquals(RecoveryVerdict.Stop(seat, 0, WindowAbortReason.DOOR), WindowRecoveryRules.decide(pending, st(20, door = true), moving = true))
        assertEquals(RecoveryVerdict.Stop(seat, 0, WindowAbortReason.SEAT), WindowRecoveryRules.decide(pending, st(20, occupied = true), moving = true))
        assertEquals(RecoveryVerdict.Stop(seat, 0, WindowAbortReason.BELT), WindowRecoveryRules.decide(pending, st(20, belt = true), moving = true))
    }

    @Test
    fun `직전 위치와 다른데 미도달이거나 점유·문 열림이면 알림`() {
        assertEquals(RecoveryVerdict.Notify(seat), WindowRecoveryRules.decide(pending, st(25), moving = false))        // 움직였다가 중간에 섬
        assertEquals(RecoveryVerdict.Notify(seat), WindowRecoveryRules.decide(pending, st(40, occupied = true), moving = false))
        assertEquals(RecoveryVerdict.Notify(seat), WindowRecoveryRules.decide(pending, st(0, door = true), moving = false))
    }

    @Test
    fun `위치가 그대로고 빈 좌석이면 아무것도 하지 않는다`() {
        assertEquals(RecoveryVerdict.NoChange(seat), WindowRecoveryRules.decide(pending, st(40), moving = false))
    }

    // ── 대기 목록 ──
    @Test
    fun `대기 목록 - 사망 기록이 없으면 비어 있고 사망 뒤 재연결에서 한 번만 돌려준다`() {
        val u = UnconfirmedWindowWrites()
        u.record(pending)
        assertTrue(u.takeAfterReconnect().isEmpty())               // 사망한 적 없으면 아무것도 안 함
        u.onDied(nowMs = 5_000)
        assertEquals(listOf(pending), u.takeAfterReconnect())
        assertTrue(u.takeAfterReconnect().isEmpty())
    }

    @Test
    fun `대기 목록 - 성공한 쓰기는 지워지고 오래된 기록은 버린다`() {
        val u = UnconfirmedWindowWrites(lookbackMs = 60_000)
        u.record(pending); u.confirmed(seat)
        u.record(PendingWindowWrite(SeatId.ROW_2_LEFT, 0, 50, atMs = 0))
        u.onDied(nowMs = 120_000)
        assertTrue(u.takeAfterReconnect().isEmpty())
    }

    // ── 가짜 차량: 쓰기 접수 후 사망, 재연결 뒤 값 반영 ──
    private fun fake(): FakeVehicleGateway = FakeVehicleGateway().also {
        it.setWindow(seat, 40)
        it.windowMove = FakeVehicleGateway.WindowMove(stepPct = 10, stepMs = 50)
    }

    @Test
    fun `재연결 뒤 목표에 닿아 있으면 정지도 알림도 없다`() = runBlocking {
        val g = fake()
        g.setWindow(seat, 0)
        val v = WindowRecovery.review(g, listOf(pending), recheckMs = 0)
        assertEquals(listOf<RecoveryVerdict>(RecoveryVerdict.Done(seat)), v)
        assertEquals(0, g.stopWindowCalls)
    }

    @Test
    fun `재연결 뒤 아직 움직이는데 문이 열려 있으면 정지 쓰기 1번 - 닫기 쓰기는 없다`() = runBlocking {
        val g = fake().also { it.windowMove = FakeVehicleGateway.WindowMove(stepPct = 5, stepMs = 100) }
        val write = launch { g.execute(VehicleCommand.SetWindow(seat, 0)) }     // 쓰기 접수(창문이 계속 닫히는 중, 응답은 못 받음)
        delay(150)
        g.simulateDoorOpened(seat, true)
        val v = WindowRecovery.review(g, listOf(pending), recheckMs = 250)
        write.cancel()
        assertEquals(1, g.stopWindowCalls)
        assertTrue(v.single() is RecoveryVerdict.Stop)
    }

    @Test
    fun `재연결 뒤 위치가 바뀌었는데 미도달이면 알림만 - 정지 없음`() = runBlocking {
        val g = fake()
        g.setWindow(seat, 25)
        val v = WindowRecovery.review(g, listOf(pending), recheckMs = 0)
        assertEquals(listOf<RecoveryVerdict>(RecoveryVerdict.Notify(seat)), v)
        assertEquals(0, g.stopWindowCalls)
    }

    @Test
    fun `대기 목록이 없으면 아무것도 읽지도 쓰지도 않는다`() = runBlocking {
        val g = fake()
        assertTrue(WindowRecovery.review(g, emptyList(), recheckMs = 0).isEmpty())
        assertEquals(0, g.stopWindowCalls)
    }

    // ── 엔진 알림 문구 ──
    @Test
    fun `엔진 알림 - 알림은 연결 끊긴 동안 움직였을 수 있다고 하고 정지는 F9-1 문구를 쓴다`() {
        val e = CabinRoutineEngine()
        val notify = e.onWindowRecovery(listOf(RecoveryVerdict.Notify(seat))).filterIsInstance<RoutineEvent.CleanupNotice>().single()
        assertEquals("${seat.label} 창문이 연결이 끊긴 동안 움직였을 수 있어요. 확인해 주세요.", notify.text)
        val stop = e.onWindowRecovery(listOf(RecoveryVerdict.Stop(seat, 0, WindowAbortReason.DOOR))).filterIsInstance<RoutineEvent.CleanupNotice>().single()
        assertTrue(stop.text.contains("창문을 닫다가 문이 열려 멈췄어요."))
        assertEquals(1, e.stats.windowAborts)
        assertTrue(e.onWindowRecovery(listOf(RecoveryVerdict.Done(seat), RecoveryVerdict.NoChange(seat))).none { it is RoutineEvent.CleanupNotice })
    }
}
