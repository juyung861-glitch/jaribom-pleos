package com.jaribom.app.vehicle

import com.jaribom.app.core.CabinRoutineEngine
import com.jaribom.app.core.CommandResult
import com.jaribom.app.core.LinkedSnapshotFeed
import com.jaribom.app.core.ReconnectReconcile
import com.jaribom.app.core.RoutineEvent
import com.jaribom.app.core.SafetyGate
import com.jaribom.app.core.SeatId
import com.jaribom.app.core.VehicleCommand
import com.jaribom.app.core.VehicleSnapshot
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F13-3·F14-2: 차량 서비스가 끊겼다 다시 붙을 때 상태 재조정.
 * 컨트롤러의 연결부(끊긴 동안 스냅샷 버림, 다시 붙은 순간 한 번 넣기)는 [LinkedSnapshotFeed] 로 분리돼 있어(F14-2, 동작 변경 없음)
 * 여기서는 컨트롤러가 쓰는 그 클래스를 그대로 쓴다. 게이트웨이는 [FakeVehicleGateway](사망·재연결 포함), 판정은 실제 [CabinRoutineEngine].
 * 정리 쓰기는 컨트롤러와 같게 쓰기 직전 [SafetyGate] 를 거쳐 게이트웨이에 쓰고 결과를 엔진에 돌려준다(1.5초 지연 대기만 생략).
 */
class ReconnectReconcileTest {

    private val left = SeatId.ROW_2_LEFT
    private val right = SeatId.ROW_2_RIGHT

    private class Harness(val g: FakeVehicleGateway, val e: CabinRoutineEngine) {
        var t = 0L
        val events = mutableListOf<RoutineEvent>()
        /** 게이트웨이에 실제로 쓴 정리 명령 */
        val writes = mutableListOf<VehicleCommand>()
        private var lastUp = g.linked.value
        private var last: VehicleSnapshot? = null
        private val feed = LinkedSnapshotFeed(g.linked.value) { snap ->
            val ev = e.onSnapshot(snap)
            events += ev
            runCleanups(ev)
        }

        private fun VehicleSnapshot.content() = copy(timestampMs = 0)

        private fun runCleanups(new: List<RoutineEvent>) {
            for (ev in new) {
                if (ev !is RoutineEvent.Execute || ev.preset || ev.seat == null) continue
                val seat = ev.seat
                if (!e.cleanupStillValid(seat)) { events += e.cancelCleanup(seat, g.snapshot.value); continue }
                val results = ev.commands.map { c ->
                    val v = SafetyGate.check(c, g.snapshot.value, e.controlledSeats)
                    if (!v.allowed) CommandResult(c, CommandResult.Status.REJECTED, v.reason)
                    else {
                        writes += c
                        val r = runBlocking { g.execute(c) }
                        CommandResult(c, if (r.isSuccess) CommandResult.Status.OK else CommandResult.Status.FAILED)
                    }
                }
                events += e.onJobFinished(ev.seat, results, g.snapshot.value.copy(timestampMs = t + 5_000))
            }
        }

        /** 1초 흐른 뒤 게이트웨이 상태를 컨트롤러가 받는 방식으로 넣는다(값이 바뀐 때만 StateFlow 처럼, 연결이 바뀌면 연결부로). */
        fun pump() {
            t += 1_000
            val up = g.linked.value
            val cur = g.snapshot.value
            if (up != lastUp) { feed.onLinkChanged(up, cur, t); lastUp = up }
            else if (cur.content() != last?.content()) feed.onSnapshot(cur.copy(timestampMs = t), up)
            last = cur
        }

        /** 같은 값이 한 번 더 온다(재연결 직후 리스너 중복 등) */
        fun duplicate() { feed.onSnapshot(g.snapshot.value.copy(timestampMs = t), g.linked.value) }

        /** 시간만 흐른다(새 신호 없이) */
        fun idle(ms: Long) { t += ms; feed.onSnapshot(g.snapshot.value.copy(timestampMs = t), g.linked.value) }

        fun cleanups(seat: SeatId) = events.filterIsInstance<RoutineEvent.Execute>().filter { !it.preset && it.seat == seat }
        fun driverReports() = events.filterIsInstance<RoutineEvent.Speak>().filter { it.text.contains("창문을 닫았어요") }
    }

    /** 탑승(벨트) → 일어남 → 문 열림(하차 안내까지). 창문 40% 라 하차 정리에 창문 닫기가 있다. */
    private fun rideToDoorOpen(h: Harness) {
        val g = h.g
        g.setWindow(left, 40); h.pump()
        g.setOccupied(left, true); g.setBelt(left, true); h.pump()
        assertTrue(h.events.any { it is RoutineEvent.Boarded && it.seat == left })
        g.setBelt(left, false); g.setOccupied(left, false); h.pump()
        g.simulateDoorOpened(left, true); h.pump()
        assertTrue(h.events.any { it is RoutineEvent.DoorOpened && it.seat == left })
        assertEquals(0, h.cleanups(left).size)
    }

    /** 사망 → 재연결 대기 동안 [during] → 재연결. 대기 중에도 pump 해서 "끊긴 동안 버림"을 그대로 겪는다. */
    private suspend fun dieAndReconnect(h: Harness, during: FakeVehicleGateway.() -> Unit) {
        h.g.connectFailures = 1
        h.g.sleepFn = { h.g.during(); h.pump() }
        h.g.simulateServiceDeath()
        assertTrue(h.g.linked.value)
        h.pump()
    }

    private fun harness() = runBlocking { Harness(FakeVehicleGateway().apply { connect() }, CabinRoutineEngine()) }

    @Test
    fun `1 문 열림과 착좌 해제 뒤 끊긴 사이 문이 닫히면 재연결 직후 하차 확정, 중복 신호에도 정리 1번과 운전자 보고 1번`() = runBlocking {
        val h = harness()
        rideToDoorOpen(h)
        dieAndReconnect(h) { simulateDoorOpened(left, false) }
        h.duplicate(); h.duplicate()
        assertEquals(1, h.events.count { it is RoutineEvent.Alighted && it.seat == left })
        val c = h.cleanups(left)
        assertEquals(1, c.size)
        assertTrue(c.single().commands.contains(VehicleCommand.SetWindow(left, 0)))
        assertEquals("게이트웨이에 쓴 창문 닫기는 1번", 1, h.writes.count { it == VehicleCommand.SetWindow(left, 0) })
        assertEquals("운전자 보고 음성 1번: ${h.events.filterIsInstance<RoutineEvent.Speak>().map { it.text }}", 1, h.driverReports().size)
        assertEquals(0, h.g.snapshot.value.seat(left).windowOpenPercent)
    }

    @Test
    fun `2 재연결이 두 번 연속이어도 같은 하차는 한 번만 정리한다`() = runBlocking {
        val h = harness()
        rideToDoorOpen(h)
        dieAndReconnect(h) { simulateDoorOpened(left, false) }
        dieAndReconnect(h) { }
        h.duplicate()
        assertEquals(1, h.events.count { it is RoutineEvent.Alighted && it.seat == left })
        assertEquals(1, h.cleanups(left).size)
        assertEquals(1, h.writes.count { it == VehicleCommand.SetWindow(left, 0) })
        assertEquals(1, h.driverReports().size)
    }

    @Test
    fun `3 끊긴 사이 자리 이동 - 문 이력 없이 앉아 있던 왼쪽이 비고 오른쪽에 앉아도 하차로 확정하지 않고 창문 쓰기도 없다`() = runBlocking {
        val h = harness()
        h.g.setWindow(left, 40)
        h.g.setOccupied(left, true); h.pump()                 // 앱 시작 때 이미 앉아 있던 좌석(문 이력 없음)
        assertTrue(h.events.any { it is RoutineEvent.Boarded && it.seat == left })
        h.idle(60_000)                                         // 시작 10초 예외가 지난 뒤
        dieAndReconnect(h) { setOccupied(left, false); setOccupied(right, true) }
        h.duplicate()
        h.idle(70_000)                                         // 하차 대기 60초가 지나도
        assertTrue("왼쪽을 하차로 확정하면 안 된다", h.events.none { it is RoutineEvent.Alighted })
        assertTrue("정리 작업이 나가면 안 된다", h.events.none { it is RoutineEvent.Execute })
        assertTrue("창문을 쓰면 안 된다: ${h.writes}", h.writes.isEmpty())
        assertEquals(40, h.g.snapshot.value.seat(left).windowOpenPercent)
        assertTrue("문 이력 없는 오른쪽 착좌는 탑승으로 확정하지 않는다", h.events.none { it is RoutineEvent.Boarded && it.seat == right })
    }

    @Test
    fun `4 끊긴 사이 다시 앉았으면 정리하지 않는다`() = runBlocking {
        val h = harness()
        rideToDoorOpen(h)
        dieAndReconnect(h) { simulateDoorOpened(left, false); setOccupied(left, true) }
        h.duplicate()
        assertTrue(h.events.none { it is RoutineEvent.Alighted })
        assertEquals(0, h.cleanups(left).size)
        assertTrue(h.writes.isEmpty())
    }

    @Test
    fun `5 끊긴 사이 생긴 새 착좌는 문 이력이 없어 탑승으로 확정하지 않는다 - 벨트를 매면 기존 규칙대로 확정`() = runBlocking {
        val h = harness()
        h.pump()
        h.idle(60_000)
        dieAndReconnect(h) { setOccupied(right, true) }
        assertTrue("문 이력 없는 착좌는 탑승이 아니다", h.events.none { it is RoutineEvent.Boarded && it.seat == right })
        h.g.setBelt(right, true); h.pump()
        assertTrue(h.events.any { it is RoutineEvent.Boarded && it.seat == right })
    }

    @Test
    fun `6 끊긴 동안의 스냅샷은 엔진에 넣지 않는다`() {
        var delivered = 0
        val feed = LinkedSnapshotFeed(true) { delivered++ }
        val s = VehicleSnapshot(timestampMs = 1)
        feed.onSnapshot(s, linked = true)
        feed.onSnapshot(s, linked = false)
        assertEquals(1, delivered)
    }

    @Test
    fun `7 연결이 그대로면 재조정 스냅샷은 없고 다시 붙은 순간에만 한 번 넣는다`() {
        val s = VehicleSnapshot(timestampMs = 1)
        assertNull(ReconnectReconcile.snapshotOnLinkChange(true, true, s, 5))
        assertNull(ReconnectReconcile.snapshotOnLinkChange(true, false, s, 5))
        assertNull(ReconnectReconcile.snapshotOnLinkChange(false, false, s, 5))
        assertEquals(5L, ReconnectReconcile.snapshotOnLinkChange(false, true, s, 5)!!.timestampMs)
        val got = mutableListOf<Long>()
        val feed = LinkedSnapshotFeed(true) { got += it.timestampMs }
        assertEquals(false, feed.onLinkChanged(true, s, 10))
        assertEquals(false, feed.onLinkChanged(false, s, 11))
        assertEquals(true, feed.onLinkChanged(true, s, 12))
        assertEquals(listOf(12L), got)
    }
}
