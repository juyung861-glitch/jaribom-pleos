package com.jaribom.app.vehicle

import com.jaribom.app.core.CabinRoutineEngine
import com.jaribom.app.core.CommandResult
import com.jaribom.app.core.Gear
import com.jaribom.app.core.OccupancyInversionDetector
import com.jaribom.app.core.RoutineEvent
import com.jaribom.app.core.SafetyGate
import com.jaribom.app.core.SeatId
import com.jaribom.app.core.SeatState
import com.jaribom.app.core.VehicleCommand
import com.jaribom.app.core.VehicleSnapshot
import com.jaribom.app.core.WindowAbortReason
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** F10-5: 창문 자동 닫기 안전 보강 — 벨트 조건, 착좌 반전 학습 조건, 반전 저장 직후 첫 운행은 버튼 모드, 정지 확인. */
class WindowSafetyF10Test {

    private val seat = SeatId.ROW_1_RIGHT
    private val close = VehicleCommand.SetWindow(seat, 0)

    private fun snap(s: SeatState) = VehicleSnapshot(seats = SeatId.entries.associateWith { SeatState() } + (seat to s), gear = Gear.PARK, timestampMs = 0)

    // ── ① 벨트 ──
    @Test
    fun `벨트가 체결돼 있으면 창문 자동 닫기는 거부된다 - 0번`() {
        val open = SeatState(windowOpenPercent = 40, occupied = false, doorOpen = false)
        assertFalse(SafetyGate.check(close, snap(open.copy(beltBuckled = true))).allowed)
        assertTrue("벨트 풀림이면 닫는다", SafetyGate.check(close, snap(open.copy(beltBuckled = false))).allowed)
        assertTrue("벨트 신호가 없는 좌석은 지금처럼(빈 좌석+문 닫힘)", SafetyGate.check(close, snap(open.copy(beltBuckled = null))).allowed)
    }

    // ── ② 반전 학습 ──
    @Test
    fun `속도가 2 m·s 이상일 때만 학습하고 속도 null 이면 학습하지 않는다`() {
        assertFalse(OccupancyInversionDetector.isLearningSpeed(null))
        assertFalse(OccupancyInversionDetector.isLearningSpeed(1.9f))
        assertTrue(OccupancyInversionDetector.isLearningSpeed(2.0f))
        assertTrue(OccupancyInversionDetector.isLearningSpeed(-3f))
        // 속도 null 로 아무리 오래 달려도(기어만 D) 판정이 나오지 않는다
        val d = OccupancyInversionDetector()
        assertNull(d.observe(0, OccupancyInversionDetector.isLearningSpeed(null), driverRaw = false))
        assertNull(d.observe(60_000, OccupancyInversionDetector.isLearningSpeed(null), driverRaw = false))
        // 2 m/s 이상이 3초 이어지면 판정한다
        val d2 = OccupancyInversionDetector()
        assertNull(d2.observe(0, OccupancyInversionDetector.isLearningSpeed(5f), driverRaw = false))
        assertEquals(true, d2.observe(3_000, OccupancyInversionDetector.isLearningSpeed(5f), driverRaw = false))
    }

    // ── ③ 반전 저장 직후 첫 운행 = 버튼 모드 ──
    private fun alightWindowOpen(e: CabinRoutineEngine): List<RoutineEvent> {
        val base = SeatState(windowOpenPercent = 30, heaterLevel = 0, ventilationLevel = 0, doorOpen = false)
        fun s(t: Long, st: SeatState) = VehicleSnapshot(seats = SeatId.entries.associateWith { SeatState() } + (seat to st), gear = Gear.PARK, timestampMs = t)
        val out = mutableListOf<RoutineEvent>()
        out += e.onSnapshot(s(0, base.copy(occupied = true, beltBuckled = true)))
        out += e.onSnapshot(s(5_000, base.copy(occupied = true, beltBuckled = true)))
        out += e.onSnapshot(s(10_000, base.copy(occupied = false, beltBuckled = false)))
        out += e.onSnapshot(s(11_000, base.copy(occupied = false, doorOpen = true)))
        out += e.onSnapshot(s(12_000, base.copy(occupied = false, doorOpen = false)))
        val exec = out.filterIsInstance<RoutineEvent.Execute>().singleOrNull()
        if (exec != null) out += e.onJobFinished(seat, exec.commands.map { CommandResult(it, CommandResult.Status.OK) }, s(20_000, base.copy(windowOpenPercent = 0)))
        else out += e.onJobFinished(seat, emptyList(), s(20_000, base))
        return out
    }

    @Test
    fun `반전을 새로 저장한 직후 첫 운행은 창문을 자동으로 닫지 않고 창문 닫기 버튼을 준다`() {
        val normal = alightWindowOpen(CabinRoutineEngine())
        assertTrue("평소에는 자동으로 닫는다", normal.filterIsInstance<RoutineEvent.Execute>().any { ex -> ex.commands.any { it is VehicleCommand.SetWindow } })

        val e = CabinRoutineEngine().apply { windowButtonOnly = true }
        val ev = alightWindowOpen(e)
        assertTrue("자동 닫기 쓰기가 없어야 함", ev.filterIsInstance<RoutineEvent.Execute>().none { ex -> ex.commands.any { it is VehicleCommand.SetWindow } })
        val notice = ev.filterIsInstance<RoutineEvent.CleanupNotice>().single { it.windowClose.isNotEmpty() }
        assertEquals(listOf(close), notice.windowClose)
        assertTrue(notice.text.contains("창문이 열려 있어요. 비어 있는지 확인하고 닫아 주세요."))
    }

    // ── ④⑤ 정지 확인 ──
    private fun slowFake(): FakeVehicleGateway = FakeVehicleGateway().also {
        it.setWindow(seat, 80)
        it.windowMove = FakeVehicleGateway.WindowMove(stepPct = 4, stepMs = 100)
    }

    private suspend fun FakeVehicleGateway.closeThenDoorOpens(): WindowCloser.Outcome = kotlinx.coroutines.coroutineScope {
        val job = async { WindowCloser.close(this@closeThenDoorOpens, close) }
        delay(250)
        simulateDoorOpened(seat, true)
        job.await()
    }

    @Test
    fun `정지 뒤에도 계속 움직이면 정지 쓰기를 1번 더 하고 멈추지 않았을 수 있다고 알린다`() = runBlocking {
        val g = slowFake().also { it.stopIgnoredCalls = 1 }          // 첫 정지는 받았지만 창문이 계속 움직인다
        val outcome = g.closeThenDoorOpens()
        assertTrue(outcome is WindowCloser.Outcome.Aborted)
        outcome as WindowCloser.Outcome.Aborted
        assertEquals(2, g.stopWindowCalls)
        assertTrue(outcome.notStopped)
        // 알림 문구
        val why = outcome.reason.name + WindowAbortReason.NOT_STOPPED_SUFFIX
        val n = CabinRoutineEngine().onWindowCloseResult(1, listOf(CommandResult(close, CommandResult.Status.ABORTED, why))).single()
        assertTrue(n.text, n.text.contains("${seat.label} 창문이 멈추지 않았을 수 있어요. 확인해 주세요."))
    }

    // ── F14-3: 재정지 1번 뒤에도 움직이면 더 쓰지 않고 끌 수 없는 경고 ──

    @Test
    fun `F14-3 재정지 뒤에도 계속 움직이면 정지 쓰기는 2번에서 끝이고 멈추지 않았다고 판정한다`() = runBlocking {
        val g = slowFake().also { it.stopIgnoredCalls = 99 }         // 정지 쓰기를 계속 무시하는 차
        val outcome = g.closeThenDoorOpens() as WindowCloser.Outcome.Aborted
        assertTrue(outcome.notStopped)
        assertTrue("재정지 뒤에도 움직이는 것을 판정해야 한다", outcome.stillMoving)
        assertEquals("정지 쓰기는 최대 2번", 2, g.stopWindowCalls)
        delay(700)
        assertEquals("판정 뒤에는 더 쓰지 않는다", 2, g.stopWindowCalls)
    }

    @Test
    fun `F14-3 재정지로 멈추면 stillMoving 이 아니다`() = runBlocking {
        val g = slowFake().also { it.stopIgnoredCalls = 1 }
        val outcome = g.closeThenDoorOpens() as WindowCloser.Outcome.Aborted
        assertTrue(outcome.notStopped)
        assertFalse(outcome.stillMoving)
        assertEquals(2, g.stopWindowCalls)
    }

    @Test
    fun `F14-3 멈추지 않았으면 경고 1번과 중단 기록, 힌트 문장이 아니라 단정 문장`() {
        val why = WindowAbortReason.DOOR.name + WindowAbortReason.NOT_STOPPED_SUFFIX + WindowAbortReason.STILL_MOVING_SUFFIX
        assertEquals("DOOR", WindowAbortReason.entries.first { it.name == why.substringBefore(':') }.name)
        assertEquals("문이 열려", WindowAbortReason.phrase(why))
        val e = CabinRoutineEngine()
        val base = SeatState(windowOpenPercent = 40, heaterLevel = 0, ventilationLevel = 0, doorOpen = false)
        fun s(t: Long, st: SeatState) = VehicleSnapshot(seats = SeatId.entries.associateWith { SeatState() } + (seat to st), gear = Gear.PARK, timestampMs = t)
        e.onSnapshot(s(0, base.copy(occupied = true, beltBuckled = true)))
        e.onSnapshot(s(5_000, base.copy(occupied = true, beltBuckled = true)))
        e.onSnapshot(s(10_000, base.copy(occupied = false, beltBuckled = false)))
        e.onSnapshot(s(11_000, base.copy(occupied = false, doorOpen = true)))
        val exec = e.onSnapshot(s(12_000, base.copy(occupied = false, doorOpen = false))).filterIsInstance<RoutineEvent.Execute>().single()
        val out = e.onJobFinished(seat, exec.commands.map { CommandResult(it, CommandResult.Status.ABORTED, why) }, s(20_000, base.copy(windowOpenPercent = 20)))
        val n = out.filterIsInstance<RoutineEvent.CleanupNotice>().single()
        assertEquals("경고 알림 1번", 1, out.count { it is RoutineEvent.CleanupNotice })
        assertTrue(n.text, n.text.contains("${seat.label} 창문이 멈추지 않았어요. 직접 확인해 주세요."))
        assertFalse("이전 힌트 문장(멈추지 않았을 수)은 쓰지 않는다", n.text.contains("멈추지 않았을 수"))
        assertEquals("중단으로 세는 것", 1, e.stats.windowAborts)
        assertEquals(0, e.stats.windowsClosed)
        // 끌 수 없는 경고: 운전자 보고 모드가 "끔"이어도 알림으로 남는다
        val off = CabinRoutineEngine().apply { reportMode = com.jaribom.app.core.DriverReportMode.OFF }
        off.onSnapshot(s(0, base.copy(occupied = true, beltBuckled = true)))
        off.onSnapshot(s(5_000, base.copy(occupied = true, beltBuckled = true)))
        off.onSnapshot(s(10_000, base.copy(occupied = false, beltBuckled = false)))
        off.onSnapshot(s(11_000, base.copy(occupied = false, doorOpen = true)))
        val ex2 = off.onSnapshot(s(12_000, base.copy(occupied = false, doorOpen = false))).filterIsInstance<RoutineEvent.Execute>().single()
        val out2 = off.onJobFinished(seat, ex2.commands.map { CommandResult(it, CommandResult.Status.ABORTED, why) }, s(20_000, base.copy(windowOpenPercent = 20)))
        assertTrue(out2.filterIsInstance<RoutineEvent.CleanupNotice>().single().text.contains("멈추지 않았어요. 직접 확인해 주세요."))
    }

    @Test
    fun `정지 뒤 멈췄으면 추가 쓰기가 없다`() = runBlocking {
        val g = slowFake()
        val outcome = g.closeThenDoorOpens() as WindowCloser.Outcome.Aborted
        delay(700)                                                   // 멈춘 뒤 더 쓰지 않는다
        assertEquals(1, g.stopWindowCalls)
        assertFalse(outcome.notStopped)
    }
}
