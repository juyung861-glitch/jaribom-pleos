package com.jaribom.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F9-3: 2열 착좌 센서가 없는 차 대응. 좌석별 착좌·벨트 신호 지원 여부로 모드를 정한다.
 *  FULL(착좌 지원 또는 판별 못 함) / BELT_DOOR(착좌 미지원·벨트 지원: 벨트+문, 창문은 자동으로 닫지 않고 [창문 닫기] 버튼) /
 *  NONE(착좌·벨트 둘 다 미지원: 관리하지 않음).
 */
class SeatSignalModeTest {

    private val seat = SeatId.ROW_2_LEFT
    private val S = SeatSignals.Support.SUPPORTED
    private val U = SeatSignals.Support.UNSUPPORTED
    private val K = SeatSignals.Support.UNKNOWN

    @Test
    fun `판별 결과별 모드`() {
        assertEquals(SeatSignalMode.FULL, SeatSignalMode.of(SeatSignals(occupancy = S, belt = S)))
        assertEquals(SeatSignalMode.FULL, SeatSignalMode.of(SeatSignals(occupancy = S, belt = U)))
        assertEquals(SeatSignalMode.BELT_DOOR, SeatSignalMode.of(SeatSignals(occupancy = U, belt = S)))
        assertEquals(SeatSignalMode.NONE, SeatSignalMode.of(SeatSignals(occupancy = U, belt = U)))
        // 판별하지 못함(UNKNOWN)은 지원하는 것으로 본다 - 에뮬레이터 동작 보존
        assertEquals(SeatSignalMode.FULL, SeatSignalMode.of(SeatSignals(occupancy = K, belt = K)))
        assertEquals(SeatSignalMode.FULL, SeatSignalMode.of(SeatSignals(occupancy = K, belt = U)))
        assertEquals(SeatSignalMode.BELT_DOOR, SeatSignalMode.of(SeatSignals(occupancy = U, belt = K)))
        assertEquals("벨트·문으로 판단해요(창문은 확인 후 닫아요)", SeatSignalMode.BELT_DOOR.label)
        assertEquals("신호가 없어 관리하지 않아요", SeatSignalMode.NONE.label)
    }

    private fun snap(t: Long, s: SeatState, gear: Gear = Gear.PARK) = VehicleSnapshot(
        seats = SeatId.entries.associateWith { SeatState() } + (seat to s), gear = gear, timestampMs = t,
    )

    /** 착좌 미지원(occupied 는 계속 null). 벨트와 문만 변한다. 탑승 = 벨트 체결, 하차 = 벨트 풀림 + 문 열렸다 닫힘. */
    private fun beltDoorRide(e: CabinRoutineEngine): List<RoutineEvent> {
        val base = SeatState(occupied = null, doorOpen = false, windowOpenPercent = 40, heaterLevel = 2, ventilationLevel = 0)
        val out = mutableListOf<RoutineEvent>()
        out += e.onSnapshot(snap(0, base.copy(beltBuckled = true)))
        out += e.onSnapshot(snap(5_000, base.copy(beltBuckled = true)))
        out += e.onSnapshot(snap(10_000, base.copy(beltBuckled = false)))
        out += e.onSnapshot(snap(11_000, base.copy(beltBuckled = false, doorOpen = true)))
        out += e.onSnapshot(snap(12_000, base.copy(beltBuckled = false, doorOpen = false)))
        return out
    }

    private fun engine(mode: SeatSignalMode) = CabinRoutineEngine().apply { seatModes = mapOf(seat to mode) }

    @Test
    fun `벨트와 문 모드 - 벨트로 탑승 확정, 벨트 풀림과 문으로 하차 확정`() {
        val ev = beltDoorRide(engine(SeatSignalMode.BELT_DOOR))
        assertTrue(ev.any { it is RoutineEvent.Boarded })
        assertTrue(ev.any { it is RoutineEvent.DoorOpened })
        assertTrue(ev.any { it is RoutineEvent.Alighted })
    }

    @Test
    fun `벨트와 문 모드 - 창문 자동 쓰기 0번, 열선은 끄고, 알림에 창문 닫기 버튼 대상 1개`() {
        val e = engine(SeatSignalMode.BELT_DOOR)
        val ev = beltDoorRide(e)
        val exec = ev.filterIsInstance<RoutineEvent.Execute>().single { !it.preset }
        assertTrue("창문을 자동으로 닫으면 안 된다: ${exec.commands}", exec.commands.none { it is VehicleCommand.SetWindow })
        assertTrue(exec.commands.any { it is VehicleCommand.SetSeatHeater })
        val after = snap(20_000, SeatState(doorOpen = false, windowOpenPercent = 40, heaterLevel = 0))
        val n = e.onJobFinished(seat, exec.commands.map { CommandResult(it, CommandResult.Status.OK) }, after)
            .filterIsInstance<RoutineEvent.CleanupNotice>().single()
        assertEquals(listOf(VehicleCommand.SetWindow(seat, 0)), n.windowClose)
        assertTrue(n.text, n.text.contains("창문이 열려 있어요"))
    }

    @Test
    fun `창문 닫기 버튼 결과 - 닫으면 닫았어요로 갱신하고 센다, 중단과 실패도 문구가 있다`() {
        val e = engine(SeatSignalMode.BELT_DOOR)
        val cmd = VehicleCommand.SetWindow(seat, 0)
        val ok = e.onWindowCloseResult(910_001, listOf(CommandResult(cmd, CommandResult.Status.OK))).single()
        assertEquals("뒷좌석 왼쪽 창문을 닫았어요.", ok.text)
        assertTrue(ok.windowClose.isEmpty())
        assertEquals(1, e.stats.windowsClosed)
        val aborted = e.onWindowCloseResult(910_001, listOf(CommandResult(cmd, CommandResult.Status.ABORTED, "DOOR"))).single()
        assertTrue(aborted.text, aborted.text.contains("창문을 닫다가 문이 열려 멈췄어요."))
        val failed = e.onWindowCloseResult(910_001, listOf(CommandResult(cmd, CommandResult.Status.FAILED))).single()
        assertTrue(failed.text.contains("창문 닫기에 실패했어요. 직접 확인해 주세요."))
        assertEquals(cmd, failed.windowClose.single())
    }

    @Test
    fun `신호 부족 좌석은 어떤 쓰기도 없고 관리하지 않는다`() {
        val e = engine(SeatSignalMode.NONE)
        val ev = beltDoorRide(e) + e.onSnapshot(snap(30_000, SeatState(occupied = true, beltBuckled = true, doorOpen = false)))
        assertTrue(ev.none { it is RoutineEvent.Boarded || it is RoutineEvent.Execute || it is RoutineEvent.Speak })
        assertFalse(seat in e.controlledSeats)
        assertFalse(e.isManaged(seat))
    }

    @Test
    fun `지원하지만 값이 null 인 FULL 은 그대로 - 착좌 신호가 오면 전체 모드로 동작`() {
        val e = engine(SeatSignalMode.FULL)
        val base = SeatState(doorOpen = false)
        val ev = e.onSnapshot(snap(0, base.copy(occupied = true, beltBuckled = true)))
        assertTrue(ev.any { it is RoutineEvent.Boarded })
        assertTrue(seat in e.controlledSeats)
    }
}
