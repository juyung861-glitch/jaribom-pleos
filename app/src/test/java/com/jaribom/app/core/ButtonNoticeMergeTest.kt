package com.jaribom.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F9 리뷰 반영: 하차 보고에 합쳐진 알림에서 버튼을 눌러도 정리 결과·실패 안내·[다시 시도]가 사라지지 않는다.
 * 누른 버튼의 결과 문구만 알림 끝에 붙고(같은 버튼의 이전 결과는 대체), 원래 본문과 failed 는 그대로다.
 */
class ButtonNoticeMergeTest {

    private val right = SeatId.ROW_2_RIGHT
    private val front = SeatId.ROW_1_RIGHT

    private val heater = VehicleCommand.SetSeatHeater(right, 0)
    private val closeCmd = VehicleCommand.SetWindow(right, 0)

    /** 하차 보고(조수석 정리 일부 실패)에 뒷좌석 오른쪽 남은 물건이 합쳐진 알림 */
    private fun merged() = RoutineEvent.CleanupNotice(
        key = 7, seats = listOf(front), text = "조수석 열선 정리에 실패했어요. 직접 확인해 주세요. 뒷좌석 오른쪽에 무언가 남아 있는 것 같아요.",
        final = true, failed = mapOf(front to listOf<VehicleCommand>(VehicleCommand.SetSeatHeater(front, 0))),
        title = "조수석 하차 정리 · 뒷좌석 오른쪽 확인", climateOff = listOf(heater), windowClose = listOf(closeCmd),
    )

    private fun engine() = CabinRoutineEngine()

    @Test
    fun `합쳐진 알림에서 열선 끄기 성공 - 원래 본문과 다시 시도 대상 유지, 누른 버튼만 결과 문구로 바뀐다`() {
        val e = engine()
        val m = ButtonNoticeMerger().apply { remember(merged()) }
        val result = e.onClimateOffResult(7, listOf(CommandResult(heater, CommandResult.Status.OK))).single()
        val out = m.merge(ButtonNoticeMerger.Kind.CLIMATE, result)!!
        assertTrue(out.text, out.text.startsWith(merged().text))
        assertTrue(out.text, out.text.endsWith("뒷좌석 오른쪽 열선을 껐어요."))
        assertEquals(merged().failed, out.failed)                // [다시 시도] 대상 유지
        assertFalse(out.plain)
        assertTrue("누른 버튼은 사라진다", out.climateOff.isEmpty())
        assertEquals("누르지 않은 버튼은 그대로", listOf(closeCmd), out.windowClose)
        assertEquals(merged().title, out.title)
    }

    @Test
    fun `실패하면 같은 버튼이 남고 같은 버튼의 이전 결과 문구는 대체되며 다른 버튼의 문구는 유지된다`() {
        val e = engine()
        val m = ButtonNoticeMerger().apply { remember(merged()) }
        val fail = e.onClimateOffResult(7, listOf(CommandResult(heater, CommandResult.Status.FAILED))).single()
        val first = m.merge(ButtonNoticeMerger.Kind.CLIMATE, fail)!!
        assertEquals(listOf<VehicleCommand>(heater), first.climateOff)
        assertTrue(first.text.endsWith("뒷좌석 오른쪽 열선을 끄지 못했어요. 직접 확인해 주세요."))
        // 다시 누름 → 성공: 실패 문구는 대체
        val ok = e.onClimateOffResult(7, listOf(CommandResult(heater, CommandResult.Status.OK))).single()
        val second = m.merge(ButtonNoticeMerger.Kind.CLIMATE, ok)!!
        assertFalse(second.text.contains("끄지 못했어요"))
        assertTrue(second.text.endsWith("뒷좌석 오른쪽 열선을 껐어요."))
        // 다른 버튼(창문 닫기) 결과가 붙어도 열선 문구는 유지
        val win = e.onWindowCloseResult(7, listOf(CommandResult(closeCmd, CommandResult.Status.OK))).single()
        val third = m.merge(ButtonNoticeMerger.Kind.WINDOW, win)!!
        assertTrue(third.text.contains("뒷좌석 오른쪽 열선을 껐어요."))
        assertTrue(third.text.endsWith("뒷좌석 오른쪽 창문을 닫았어요."))
        assertEquals(merged().failed, third.failed)
    }

    @Test
    fun `원래 알림이 단독(plain) 알림이면 합치지 않고 결과를 그대로 쓴다`() {
        val e = engine()
        val m = ButtonNoticeMerger().apply { remember(merged().copy(plain = true)) }
        val result = e.onClimateOffResult(7, listOf(CommandResult(heater, CommandResult.Status.OK))).single()
        assertNull(m.merge(ButtonNoticeMerger.Kind.CLIMATE, result))
    }
}
