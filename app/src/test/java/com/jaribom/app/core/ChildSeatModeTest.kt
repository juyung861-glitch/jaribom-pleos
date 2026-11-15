package com.jaribom.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 아이 좌석 모드: 탑승 때 창문 잠금, 문 열릴 때 아이 말투 안내, 하차 때 원래 잠금으로 되돌린 뒤 정리. */
class ChildSeatModeTest {

    private val left = SeatId.ROW_2_LEFT
    private val right = SeatId.ROW_2_RIGHT

    private fun snap(t: Long, vararg seats: Pair<SeatId, SeatState>) = VehicleSnapshot(
        seats = SeatId.entries.associateWith { SeatState() } + seats, gear = Gear.PARK, timestampMs = t,
    )

    private fun engine(child: Set<SeatId>) = CabinRoutineEngine().apply { childSeats = child }

    private fun List<RoutineEvent>.lockCommands() =
        filterIsInstance<RoutineEvent.Execute>().flatMap { it.commands }.filterIsInstance<VehicleCommand.SetWindowLock>()

    private fun List<RoutineEvent>.speech() = filterIsInstance<RoutineEvent.Speak>().joinToString(" ") { it.text }

    /** 벨트를 매고 앉음(즉시 탑승 확정) → [riding] 상태로 탐 → 일어남 → 문 열림 → 문 닫힘. (탑승, 문 열림, 문 닫힘) 이벤트. */
    private fun ride(
        e: CabinRoutineEngine, seat: SeatId, boarding: SeatState, riding: SeatState, t0: Long = 0,
        /** 같은 열 옆자리등 다른 좌석의 상태(F14-1: 키즈락 열은 옆자리 신호가 없으면 창문을 닫지 않는다) */
        others: Array<Pair<SeatId, SeatState>> = emptyArray(),
    ): Triple<List<RoutineEvent>, List<RoutineEvent>, List<RoutineEvent>> {
        val boarded = e.onSnapshot(snap(t0, seat to boarding.copy(occupied = true, beltBuckled = true), *others))
        e.onSnapshot(snap(t0 + 5_000, seat to riding.copy(occupied = true, beltBuckled = true), *others))
        val off = riding.copy(occupied = false, beltBuckled = false)
        e.onSnapshot(snap(t0 + 10_000, seat to off, *others))
        val opened = e.onSnapshot(snap(t0 + 11_000, seat to off.copy(doorOpen = true), *others))
        val closed = e.onSnapshot(snap(t0 + 12_000, seat to off.copy(doorOpen = false), *others))
        return Triple(boarded, opened, closed)
    }

    private val unlocked = SeatState(windowOpenPercent = 0, heaterLevel = 0, ventilationLevel = 0, windowLocked = false)

    @Test
    fun `지정한 아이 좌석만 잠그고 아이 말투로 안내한다`() {
        val e = engine(setOf(left))
        val (boardL, openL, _) = ride(e, left, unlocked, unlocked.copy(windowLocked = true))
        assertEquals(listOf(VehicleCommand.SetWindowLock(left, true)), boardL.lockCommands())
        assertTrue(openL.speech(), openL.speech().contains("두고 가는 거 없지? 조심히 내려."))

        val (boardR, openR, closeR) = ride(e, right, unlocked, unlocked, t0 = 100_000)
        assertTrue(boardR.lockCommands().isEmpty())
        assertTrue(closeR.lockCommands().isEmpty())
        assertTrue(openR.speech().startsWith("두고 가시는 물건은 없나요?"))
    }

    @Test
    fun `아이 좌석 안내에 물건을 붙인다`() {
        val e = engine(setOf(left)).apply { items = listOf(CarryItem(1, "물통", ItemCondition.ALWAYS)) }
        val (_, opened, _) = ride(e, left, unlocked, unlocked)
        assertTrue(opened.speech(), opened.speech().contains("물통 챙겼지? 조심히 내려."))
    }

    @Test
    fun `하차하면 원래 잠금으로 돌린 뒤 정리하고 한 것만 보고한다`() {
        val e = engine(setOf(left))
        // 원래 풀려 있음 → 탈 때 잠금. 타는 동안 창문 40%.
        // 옆자리(오른쪽)는 빈 좌석으로 읽힌다(F14-1: 신호가 null 이면 키즈락 열이라 창문을 자동으로 닫지 않는다)
        val emptyRight = right to SeatState(occupied = false, beltBuckled = false, doorOpen = false)
        val (_, _, closed) = ride(e, left, unlocked, unlocked.copy(windowLocked = true, windowOpenPercent = 40), others = arrayOf(emptyRight))
        val exec = closed.filterIsInstance<RoutineEvent.Execute>().single()
        assertEquals(listOf(VehicleCommand.SetWindowLock(left, false), VehicleCommand.SetWindow(left, 0)), exec.commands)
        val report = e.onJobFinished(left, exec.commands.map { CommandResult(it, CommandResult.Status.OK) }, snap(20_000, left to SeatState()))
        assertTrue(report.speech(), report.speech().contains("뒷좌석 왼쪽 창문 잠금을 풀고 창문을 닫았어요."))
    }

    @Test
    fun `잠금 되돌리기에 실패하면 실패로 보고한다`() {
        val e = engine(setOf(left))
        val (_, _, closed) = ride(e, left, unlocked, unlocked.copy(windowLocked = true))
        val exec = closed.filterIsInstance<RoutineEvent.Execute>().single()
        val report = e.onJobFinished(left, exec.commands.map { CommandResult(it, CommandResult.Status.FAILED) }, snap(20_000, left to SeatState()))
        assertTrue(report.speech(), report.speech().contains("창문 잠금 정리에 실패했어요"))
        assertTrue(report.speech().contains("잠금을 풀었어요").not())
    }

    @Test
    fun `원래 잠겨 있었으면 잠금을 건드리지 않는다`() {
        val e = engine(setOf(left))
        val locked = unlocked.copy(windowLocked = true)
        val (boarded, _, closed) = ride(e, left, locked, locked)
        assertTrue(boarded.lockCommands().isEmpty())
        assertTrue(closed.lockCommands().isEmpty())
    }

    @Test
    fun `잠금을 읽을 수 없으면 잠금 명령 없이 안내만 한다`() {
        val e = engine(setOf(left))
        val noLock = unlocked.copy(windowLocked = null)
        val (boarded, opened, closed) = ride(e, left, noLock, noLock)
        assertTrue(boarded.lockCommands().isEmpty())
        assertTrue(boarded.filterIsInstance<RoutineEvent.Log>().any { it.text.contains("안내만") })
        assertTrue(opened.speech().contains("조심히 내려."))
        assertTrue(closed.lockCommands().isEmpty())
    }

    @Test
    fun `운전석·조수석은 아이 좌석으로 지정할 수 없다`() {
        val e = engine(setOf(SeatId.ROW_1_LEFT, SeatId.ROW_1_RIGHT, right))
        assertEquals(setOf(right), e.childSeats)
        val (boarded, opened, _) = ride(e, SeatId.ROW_1_RIGHT, unlocked, unlocked)
        assertTrue(boarded.lockCommands().isEmpty())
        assertTrue(opened.speech().startsWith("두고 가시는 물건은 없나요?"))
    }
}
