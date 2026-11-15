package com.jaribom.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F12-1 운전자 보고 선택(모두 / 경고만 / 끔)과 F12-2 알림 정리.
 *  - 경고(못 한 일·실패·중단·남은 물건·문 열림)는 설정과 상관없이 항상 알린다.
 *  - 성공 보고는 "모두"일 때만 말한다. 음성으로 끝까지 나갔으면 시스템 알림은 남기지 않는다(카드만).
 *  - 운전석 창문(비)·실내 온도 안내는 각각 켜기/끄기이고 경고가 아니라 안내다.
 */
class DriverReportModeTest {

    private val seat = SeatId.ROW_1_RIGHT
    private val riding = SeatState(occupied = true, beltBuckled = true, windowOpenPercent = 40, heaterLevel = 2, ventilationLevel = 0)

    private fun snap(t: Long, s: SeatState, gear: Gear = Gear.PARK, driverWindow: Int? = null, cabin: Float? = null) = VehicleSnapshot(
        seats = SeatId.entries.associateWith { SeatState() } + (seat to s) + (SeatId.ROW_1_LEFT to SeatState(windowOpenPercent = driverWindow)),
        hvac = HvacState(cabinTempC = cabin), gear = gear, timestampMs = t,
    )

    /** 탑승 → 하차. 정리 작업을 돌려준다. */
    private fun leave(e: CabinRoutineEngine): RoutineEvent.Execute {
        e.onSnapshot(snap(0, riding))
        val off = riding.copy(occupied = false, beltBuckled = false)
        e.onSnapshot(snap(10_000, off))
        e.onSnapshot(snap(11_000, off.copy(doorOpen = true)))
        return e.onSnapshot(snap(12_000, off.copy(doorOpen = false))).filterIsInstance<RoutineEvent.Execute>().single()
    }

    private val closedEmpty = SeatState(occupied = false, doorOpen = false, windowOpenPercent = 0, heaterLevel = 0)

    private fun finish(
        e: CabinRoutineEngine, status: (VehicleCommand) -> CommandResult.Status = { CommandResult.Status.OK },
        gear: Gear = Gear.PARK, after: SeatState = closedEmpty, driverWindow: Int? = null, cabin: Float? = null, why: String = "",
    ): List<RoutineEvent> {
        val ex = leave(e)
        return e.onJobFinished(ex.seat, ex.commands.map { CommandResult(it, status(it), why) }, snap(25_000, after, gear, driverWindow, cabin))
    }

    private fun engine(mode: DriverReportMode) = CabinRoutineEngine().apply { reportMode = mode }
    private fun List<RoutineEvent>.speech() = filterIsInstance<RoutineEvent.Speak>().map { it.text }
    private fun List<RoutineEvent>.notices() = filterIsInstance<RoutineEvent.CleanupNotice>()

    @Test
    fun `앱 기본값은 경고만이다`() {
        assertEquals(DriverReportMode.WARNINGS_ONLY, DriverReportMode.DEFAULT)
        assertEquals(DriverReportMode.WARNINGS_ONLY, DriverReportMode.fromName(null))
        assertEquals(DriverReportMode.WARNINGS_ONLY, DriverReportMode.fromName("없는값"))
        assertEquals(DriverReportMode.ALL, DriverReportMode.fromName("ALL"))
    }

    @Test
    fun `경고만 - 성공 보고는 말하지도 알리지도 않고 카드만 갱신한다`() {
        val e = engine(DriverReportMode.WARNINGS_ONLY)
        val out = finish(e)
        assertTrue(out.toString(), out.speech().isEmpty())
        assertTrue(out.notices().isEmpty())
        assertEquals("조수석 창문을 닫고 열선을 껐어요.", e.lastResult!!.report)
        assertEquals(1, e.stats.restores)
    }

    @Test
    fun `모두 - 성공 보고를 말하고 음성이 나갔으면 알림을 남기지 않는다`() {
        val e = engine(DriverReportMode.ALL)
        val out = finish(e)
        val said = out.speech().single()
        assertEquals("조수석 창문을 닫고 열선을 껐어요.", said)
        assertTrue(out.notices().isEmpty())
        assertTrue(e.onSpeechDone(said).none { it is RoutineEvent.CleanupNotice })
        // 끝난 뒤 늦게 온 실패 신호는 알림을 만들지 않는다(이미 처리)
        assertTrue(e.onSpeechFailed(said, "TIMEOUT").notices().isEmpty())
    }

    @Test
    fun `모두 - 성공 보고 음성이 실패하면 그때 알림을 남긴다`() {
        val e = engine(DriverReportMode.ALL)
        val said = finish(e).speech().single()
        val n = e.onSpeechFailed(said, "ENGINE_ERROR").notices().single()
        assertTrue(n.text.startsWith("조수석 창문을 닫고 열선을 껐어요."))
    }

    @Test
    fun `모두 - 포커스를 잃어 말하지 못한 보고도 알림으로 남긴다`() {
        val e = engine(DriverReportMode.ALL)
        val said = finish(e).speech().single()
        assertEquals(1, e.onSpeechAbandoned(said).notices().size)
        assertEquals(0, e.stats.speechFailures)                // 음성 실패로 세지 않는다
    }

    @Test
    fun `P 가 아니면 음성 없이 알림을 남긴다`() {
        val out = finish(engine(DriverReportMode.ALL), gear = Gear.DRIVE)
        assertTrue(out.speech().isEmpty())
        assertEquals("조수석 창문을 닫고 열선을 껐어요.", out.notices().single().text)
    }

    @Test
    fun `경고만 - 실패는 말하고 다시 시도 알림을 남기며 성공 문장은 뺀다`() {
        val e = engine(DriverReportMode.WARNINGS_ONLY)
        val out = finish(e, status = { if (it is VehicleCommand.SetWindow) CommandResult.Status.FAILED else CommandResult.Status.OK })
        val said = out.speech().single()
        assertEquals("조수석 창문 정리에 실패했어요. 직접 확인해 주세요.", said)
        val n = out.notices().single()
        assertTrue(n.failed.isNotEmpty())
        assertFalse(n.text.contains("열선을 껐어요"))
        assertTrue(e.lastResult!!.report.contains("열선을 껐어요"))   // 카드는 전체
    }

    @Test
    fun `끔 - 경고는 끌 수 없다 - 음성 없이 알림으로 남는다`() {
        val out = finish(engine(DriverReportMode.OFF), status = { if (it is VehicleCommand.SetWindow) CommandResult.Status.REJECTED else CommandResult.Status.OK }, why = "문이 열려 있음",
            after = closedEmpty.copy(doorOpen = true))
        assertTrue(out.speech().isEmpty())
        val n = out.notices().single()
        assertTrue(n.text, n.text.contains("문이 열려 있어서 조수석 창문은 그대로 뒀어요."))
        assertFalse(n.text.contains("열선을 껐어요"))
        // 같은 이유를 두 번 말하지 않는다
        assertFalse(n.text.contains("조수석 문이 열려 있어요."))
    }

    @Test
    fun `끔 - 성공뿐이면 아무것도 내지 않는다`() {
        val out = finish(engine(DriverReportMode.OFF))
        assertTrue(out.speech().isEmpty() && out.notices().isEmpty())
    }

    @Test
    fun `정리 직후 그 좌석 문이 계속 열려 있으면 경고다`() {
        val out = finish(engine(DriverReportMode.WARNINGS_ONLY), after = closedEmpty.copy(doorOpen = true))
        assertEquals("조수석 문이 열려 있어요.", out.speech().single())
        assertEquals("조수석 문이 열려 있어요.", out.notices().single().text)
    }

    @Test
    fun `운전석 창문(비)·실내 온도 안내는 각각 끌 수 있고 경고가 아니다`() {
        fun run(window: Boolean, heat: Boolean): List<RoutineEvent> {
            val e = engine(DriverReportMode.WARNINGS_ONLY).apply {
                weather = WeatherInfo(rainingNow = true); driverWindowNotice = window; cabinHeatNotice = heat
            }
            return finish(e, driverWindow = 50, cabin = 31f)
        }
        assertEquals("운전석 창문이 열려 있어요. 실내가 31℃예요. 다음 탑승 전에 환기해 주세요.", run(true, true).speech().single())
        assertEquals("실내가 31℃예요. 다음 탑승 전에 환기해 주세요.", run(false, true).speech().single())
        assertEquals("운전석 창문이 열려 있어요.", run(true, false).speech().single())
        assertTrue(run(false, false).speech().isEmpty())
        // 안내만 있는 보고는 음성으로 나갔으면 알림이 없다(경고가 아님)
        assertTrue(run(true, true).notices().isEmpty())
    }

    @Test
    fun `동승자 안내 음성이 실패하면 성공만 있는 보고도 알림으로 남긴다`() {
        val e = engine(DriverReportMode.WARNINGS_ONLY)
        e.onSnapshot(snap(0, riding))
        val off = riding.copy(occupied = false, beltBuckled = false)
        e.onSnapshot(snap(10_000, off))
        val passenger = e.onSnapshot(snap(11_000, off.copy(doorOpen = true))).speech().single()
        val ex = e.onSnapshot(snap(12_000, off.copy(doorOpen = false))).filterIsInstance<RoutineEvent.Execute>().single()
        assertTrue(e.onJobFinished(ex.seat, ex.commands.map { CommandResult(it, CommandResult.Status.OK) }, snap(25_000, closedEmpty)).notices().isEmpty())
        val n = e.onSpeechFailed(passenger, "TIMEOUT").notices().single()
        assertTrue(n.text.contains("열선을 껐어요"))
        assertTrue(n.text.endsWith(CabinRoutineEngine.SPEECH_FAILED_LINE))
    }

    @Test
    fun `보고 줄 분류 - 성공·경고·안내`() {
        val perSeat = mapOf(seat to listOf(
            CommandResult(VehicleCommand.SetSeatHeater(seat, 0), CommandResult.Status.OK),
            CommandResult(VehicleCommand.SetWindow(seat, 0), CommandResult.Status.FAILED),
        ))
        val lines = ExitMessageRules.driverReportLines(perSeat, setOf(SeatId.ROW_2_LEFT), snap(0, closedEmpty, driverWindow = 50, cabin = 30f), weather = WeatherInfo(rainWithinHour = true))
        assertEquals(
            listOf(ReportKind.SUCCESS, ReportKind.WARNING, ReportKind.SUCCESS, ReportKind.DRIVER_WINDOW, ReportKind.CABIN_HEAT),
            lines.map { it.kind },
        )
    }
}
