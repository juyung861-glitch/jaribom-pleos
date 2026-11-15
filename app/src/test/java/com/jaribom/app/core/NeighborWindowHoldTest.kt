package com.jaribom.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F13-2: 하차 정리에서 좌석 X 의 창문을 자동으로 닫기 전에, 같은 열의 다른 좌석(운전석 제외)에 착좌나 벨트 체결이 읽히면
 * 자동으로 닫지 않고 [창문 닫기] 버튼과 "옆자리에 사람이 있어 {좌석} 창문은 닫지 않았어요."를 남긴다.
 * 옆자리 신호가 둘 다 null 이면 지금 규칙 그대로다. 열선·통풍 끄기와 잠금 복원은 그대로 한다. 조수석은 영향이 없다.
 */
class NeighborWindowHoldTest {

    private val left = SeatId.ROW_2_LEFT
    private val right = SeatId.ROW_2_RIGHT
    private val front = SeatId.ROW_1_RIGHT
    private val driver = SeatId.ROW_1_LEFT
    private val base = SeatState(doorOpen = false, windowOpenPercent = 40, heaterLevel = 2, ventilationLevel = 0)
    private val line = "옆자리에 사람이 있어 뒷좌석 왼쪽 창문은 닫지 않았어요."

    private fun snap(t: Long, seats: Map<SeatId, SeatState>) = VehicleSnapshot(
        seats = SeatId.entries.associateWith { SeatState() } + seats, gear = Gear.PARK, timestampMs = t,
    )

    /**
     * [who] 좌석이 탑승 → 일어남 → 문 열림 → 문 닫힘. [others] 는 그동안 다른 좌석 상태(시각별로 바꾸려면 [othersAt]).
     */
    private fun ride(
        e: CabinRoutineEngine, who: SeatId, others: Map<SeatId, SeatState>,
        othersAt: (Long) -> Map<SeatId, SeatState> = { others },
    ): List<RoutineEvent> {
        val out = mutableListOf<RoutineEvent>()
        val seated = base.copy(occupied = true, beltBuckled = true)
        val up = base.copy(occupied = false, beltBuckled = false)
        out += e.onSnapshot(snap(0, othersAt(0) + (who to seated)))
        out += e.onSnapshot(snap(5_000, othersAt(5_000) + (who to seated)))
        out += e.onSnapshot(snap(10_000, othersAt(10_000) + (who to up)))
        out += e.onSnapshot(snap(11_000, othersAt(11_000) + (who to up.copy(doorOpen = true))))
        out += e.onSnapshot(snap(12_000, othersAt(12_000) + (who to up)))
        out += e.onSnapshot(snap(13_000, othersAt(13_000) + (who to up)))
        return out
    }

    private fun List<RoutineEvent>.cleanup(seat: SeatId) = filterIsInstance<RoutineEvent.Execute>().single { !it.preset && it.seat == seat }

    /** 정리 결과를 넘기고 묶음 보고 알림을 받는다. */
    private fun finish(e: CabinRoutineEngine, seat: SeatId, exec: RoutineEvent.Execute, others: Map<SeatId, SeatState>): List<RoutineEvent> =
        e.onJobFinished(seat, exec.commands.map { CommandResult(it, CommandResult.Status.OK) }, snap(20_000, others + (seat to base.copy(occupied = false, heaterLevel = 0))))

    @Test
    fun `뒷좌석 왼쪽 하차 + 오른쪽 착좌 - 창문 자동 닫기 없음, 열선은 끄고, 버튼과 문장`() {
        val others = mapOf(right to SeatState(occupied = true, beltBuckled = true, doorOpen = false))
        val e = CabinRoutineEngine()
        val exec = ride(e, left, others).cleanup(left)
        assertTrue("자동으로 닫으면 안 된다: ${exec.commands}", exec.commands.none { it is VehicleCommand.SetWindow })
        assertTrue(exec.commands.any { it is VehicleCommand.SetSeatHeater })
        val n = finish(e, left, exec, others).filterIsInstance<RoutineEvent.CleanupNotice>().single()
        assertEquals(listOf(VehicleCommand.SetWindow(left, 0)), n.windowClose)
        assertTrue(n.text, n.text.contains(line))
    }

    @Test
    fun `오른쪽 벨트만 체결이어도 같다`() {
        val others = mapOf(right to SeatState(occupied = false, beltBuckled = true, doorOpen = false))
        val e = CabinRoutineEngine()
        val exec = ride(e, left, others).cleanup(left)
        assertTrue(exec.commands.none { it is VehicleCommand.SetWindow })
        val n = finish(e, left, exec, others).filterIsInstance<RoutineEvent.CleanupNotice>().single()
        assertEquals(listOf(VehicleCommand.SetWindow(left, 0)), n.windowClose)
    }

    @Test
    fun `옆자리가 비어 있으면 지금처럼 자동으로 닫는다`() {
        val others = mapOf(right to SeatState(occupied = false, beltBuckled = false, doorOpen = false))
        val exec = ride(CabinRoutineEngine(), left, others).cleanup(left)
        assertTrue(exec.commands.contains(VehicleCommand.SetWindow(left, 0)))
    }

    @Test
    fun `옆자리 착좌와 벨트 신호가 둘 다 null 이면 기존 동작 - 자동으로 닫는다`() {
        val others = mapOf(right to SeatState(occupied = null, beltBuckled = null, doorOpen = false))
        val exec = ride(CabinRoutineEngine(), left, others).cleanup(left)
        assertTrue(exec.commands.contains(VehicleCommand.SetWindow(left, 0)))
    }

    @Test
    fun `조수석 하차는 운전석에 사람이 있어도(뒷좌석에 있어도) 자동으로 닫는다`() {
        val others = mapOf(
            driver to SeatState(occupied = true, beltBuckled = true, doorOpen = false),
            left to SeatState(occupied = true, beltBuckled = true, doorOpen = false),
        )
        val exec = ride(CabinRoutineEngine(), front, others).cleanup(front)
        assertTrue(exec.commands.contains(VehicleCommand.SetWindow(front, 0)))
    }

    @Test
    fun `보고 모드 끔이어도 버튼이 필요해 알림은 남고, 경고로 세지 않는 안내다`() {
        val others = mapOf(right to SeatState(occupied = true, beltBuckled = true, doorOpen = false))
        val e = CabinRoutineEngine().apply { reportMode = DriverReportMode.OFF }
        val exec = ride(e, left, others).cleanup(left)
        val out = finish(e, left, exec, others)
        val n = out.filterIsInstance<RoutineEvent.CleanupNotice>().single()
        assertEquals(listOf(VehicleCommand.SetWindow(left, 0)), n.windowClose)
        assertTrue(out.none { it is RoutineEvent.Speak })
        assertTrue(n.text, n.text.contains(line))
    }

    @Test
    fun `옆자리가 남은 물건으로 판정되면 남은 물건과 창문 안내가 한 묶음 보고로 합쳐진다`() {
        // 오른쪽: 탑승(벨트) → 벨트 풀고 착좌 유지(물건) → 왼쪽 하차와 같은 때 문 열렸다 닫힘
        val seatedR = SeatState(occupied = true, beltBuckled = true, doorOpen = false)
        val objR = SeatState(occupied = true, beltBuckled = false, doorOpen = false)
        val rightAt: (Long) -> Map<SeatId, SeatState> = { t ->
            mapOf(right to when {
                t < 10_000 -> seatedR
                t == 11_000L -> objR.copy(doorOpen = true)
                else -> objR
            })
        }
        val e = CabinRoutineEngine()
        val ev = ride(e, left, emptyMap(), rightAt)
        val exec = ev.cleanup(left)
        assertTrue(exec.commands.none { it is VehicleCommand.SetWindow })
        val out = finish(e, left, exec, rightAt(20_000))
        val notices = out.filterIsInstance<RoutineEvent.CleanupNotice>()
        assertEquals("한 묶음 보고 1건: $notices", 1, notices.size)
        val n = notices.single()
        assertTrue(n.text, n.text.contains("뒷좌석 오른쪽에 무언가 남아 있는 것 같아요"))
        assertTrue(n.text, n.text.contains(line))
        assertEquals(listOf(VehicleCommand.SetWindow(left, 0)), n.windowClose)
        assertFalse((ev + out).any { it is RoutineEvent.CleanupNotice && it.title == "뒷좌석 오른쪽 남은 물건 확인" })
    }

    @Test
    fun `버튼을 누를 때 옆자리에 여전히 사람이 있어도 막지 않는다 - 그 좌석 자체 조건만 본다`() {
        val e = CabinRoutineEngine()
        val raw = snap(30_000, mapOf(left to SeatState(occupied = false, beltBuckled = false, doorOpen = false, windowOpenPercent = 40), right to SeatState(occupied = true, beltBuckled = true)))
        assertEquals(null, e.windowCloseBlock(left, raw))
        assertEquals(right, e.neighborPresent(left, raw))
        val doorOpen = snap(30_000, mapOf(left to SeatState(doorOpen = true, windowOpenPercent = 40), right to SeatState(occupied = true)))
        assertEquals("문이 열려 있어 창문을 닫지 않았어요.", e.windowCloseBlock(left, doorOpen))
    }

    // ── F14-1: 키즈락 열은 옆자리 신호를 확인할 수 없으면 창문을 닫지 않는다(fail-closed) ──

    private val unknownLine = "옆자리 좌석 신호를 확인할 수 없어 뒷좌석 왼쪽 창문은 닫지 않았어요."
    private val nullSeat = SeatState(occupied = null, beltBuckled = null, doorOpen = false)

    private fun kidsEngine(vararg child: SeatId, kids: Boolean = true) = CabinRoutineEngine().apply { childSeats = child.toSet(); kidsLockOn = kids }

    @Test
    fun `F14-1 키즈락 열 + 옆자리 신호 null - 자동 닫기 없음, 버튼과 신호 확인 불가 문장`() {
        val others = mapOf(right to nullSeat)
        val e = kidsEngine(right)
        val exec = ride(e, left, others).cleanup(left)
        assertTrue("자동으로 닫으면 안 된다: ${exec.commands}", exec.commands.none { it is VehicleCommand.SetWindow })
        assertTrue("열선 끄기는 그대로", exec.commands.any { it is VehicleCommand.SetSeatHeater })
        val n = finish(e, left, exec, others).filterIsInstance<RoutineEvent.CleanupNotice>().single()
        assertEquals(listOf(VehicleCommand.SetWindow(left, 0)), n.windowClose)
        assertTrue(n.text, n.text.contains(unknownLine))
    }

    @Test
    fun `F14-1 하차 좌석 자신이 키즈락 좌석이어도 같다`() {
        val others = mapOf(right to nullSeat)
        val e = kidsEngine(left)
        val exec = ride(e, left, others).cleanup(left)
        assertTrue(exec.commands.none { it is VehicleCommand.SetWindow })
        val n = finish(e, left, exec, others).filterIsInstance<RoutineEvent.CleanupNotice>().single()
        assertEquals(listOf(VehicleCommand.SetWindow(left, 0)), n.windowClose)
        assertTrue(n.text, n.text.contains(unknownLine))
    }

    @Test
    fun `F14-1 키즈락 열 + 옆자리 신호 미지원(NONE) - 자동 닫기 없음`() {
        val others = mapOf(right to SeatState(occupied = false, beltBuckled = false, doorOpen = false))
        val e = kidsEngine(right).apply { seatModes = mapOf(right to SeatSignalMode.NONE) }
        val exec = ride(e, left, others).cleanup(left)
        assertTrue(exec.commands.none { it is VehicleCommand.SetWindow })
        val n = finish(e, left, exec, others).filterIsInstance<RoutineEvent.CleanupNotice>().single()
        assertTrue(n.text, n.text.contains(unknownLine))
    }

    @Test
    fun `F14-1 옆자리가 착좌 false 벨트 false 로 읽히면 키즈락 열이어도 자동으로 닫는다`() {
        val others = mapOf(right to SeatState(occupied = false, beltBuckled = false, doorOpen = false))
        val exec = ride(kidsEngine(right), left, others).cleanup(left)
        assertTrue(exec.commands.contains(VehicleCommand.SetWindow(left, 0)))
    }

    @Test
    fun `F14-1 착좌만 null 이고 벨트는 읽히면 확인 가능이라 자동으로 닫는다`() {
        val others = mapOf(right to SeatState(occupied = null, beltBuckled = false, doorOpen = false))
        val exec = ride(kidsEngine(right), left, others).cleanup(left)
        assertTrue(exec.commands.contains(VehicleCommand.SetWindow(left, 0)))
    }

    @Test
    fun `F14-1 키즈락 모드를 끄면 기존 F13-2 규칙 - 신호 null 이면 자동으로 닫는다`() {
        val others = mapOf(right to nullSeat)
        val exec = ride(kidsEngine(right, kids = false), left, others).cleanup(left)
        assertTrue(exec.commands.contains(VehicleCommand.SetWindow(left, 0)))
    }

    @Test
    fun `F14-1 키즈락 좌석이 없는 열은 기존 동작 - 신호 null 이면 자동으로 닫는다`() {
        val exec = ride(kidsEngine(), left, mapOf(right to nullSeat)).cleanup(left)
        assertTrue(exec.commands.contains(VehicleCommand.SetWindow(left, 0)))
    }

    @Test
    fun `F14-1 뒤열에만 키즈락이 있으면 조수석 하차는 영향이 없다`() {
        val exec = ride(kidsEngine(right), front, mapOf(left to nullSeat, right to nullSeat)).cleanup(front)
        assertTrue(exec.commands.contains(VehicleCommand.SetWindow(front, 0)))
    }
}
