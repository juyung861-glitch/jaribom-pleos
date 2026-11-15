package com.jaribom.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExitMessageRulesTest {

    private fun snap(seats: Map<SeatId, SeatState> = emptyMap(), cabin: Float? = null) = VehicleSnapshot(
        seats = SeatId.entries.associateWith { SeatState() } + seats,
        hvac = HvacState(cabinTempC = cabin), gear = Gear.PARK,
    )

    private fun msg(
        weather: WeatherInfo = WeatherInfo(),
        items: List<CarryItem> = emptyList(),
        quiet: ExitMessageRules.QuietPolicy = ExitMessageRules.QuietPolicy.SHORT_PHRASE,
    ) = ExitMessageRules.passengerMessage(weather, snap(), items, quietPolicy = quiet)

    // ── ① 문이 열릴 때: 동승자 안내 ──────────────────────────────
    @Test
    fun `입력이 없어도 기본 멘트는 나온다`() {
        assertEquals(listOf("두고 가시는 물건은 없나요?"), msg().sentences)
    }

    @Test
    fun `비가 오면 우산 안내가 붙는다`() {
        val m = msg(weather = WeatherInfo(rainingNow = true))
        assertTrue(m.sentences.contains("밖에 비가 와요. 우산 챙기세요."))
        assertTrue(m.hasReminder)
    }

    @Test
    fun `바깥이 추우면 따뜻하게 가라고 한다`() {
        assertTrue(msg(weather = WeatherInfo(outdoorTempC = -3f)).sentences.contains("밖이 많이 추워요. 따뜻하게 가세요."))
    }

    @Test
    fun `등록 물건은 조건이 맞을 때만 붙고 비 올 때 우산은 중복되지 않는다`() {
        val items = listOf(CarryItem(1, "우산", ItemCondition.RAIN), CarryItem(2, "지갑", ItemCondition.ALWAYS))
        assertTrue(msg(items = items).sentences.contains("지갑 챙기셨어요?"))
        val wet = msg(items = items, weather = WeatherInfo(rainingNow = true)).sentences
        assertTrue(wet.contains("지갑 챙기셨어요?"))
        assertEquals(1, wet.count { it.contains("우산") })
    }

    @Test
    fun `무음 정책이면 기본 멘트만 있는 경우 아무 말도 하지 않는다`() {
        assertTrue(msg(quiet = ExitMessageRules.QuietPolicy.SILENT).sentences.isEmpty())
        assertFalse(msg(quiet = ExitMessageRules.QuietPolicy.SILENT, weather = WeatherInfo(rainingNow = true)).sentences.isEmpty())
    }

    // ── ② 문이 닫힌 뒤: 운전자 보고 ─────────────────────────────
    private val seat = SeatId.ROW_2_LEFT

    @Test
    fun `실제로 원복한 것만 보고한다`() {
        val r = listOf(
            CommandResult(VehicleCommand.SetWindow(seat, 0), CommandResult.Status.OK),
            CommandResult(VehicleCommand.SetSeatHeater(seat, 0), CommandResult.Status.OK),
        )
        assertEquals(listOf("뒷좌석 왼쪽 창문을 닫고 열선을 껐어요."), ExitMessageRules.driverReport(seat, r, snap(), true))
    }

    @Test
    fun `거부된 명령은 이유와 함께 그대로 뒀다고 말한다`() {
        val r = listOf(CommandResult(VehicleCommand.SetWindow(seat, 0), CommandResult.Status.REJECTED, "사람이 다시 앉아 있음"))
        assertEquals(listOf("사람이 다시 앉아 있어서 뒷좌석 왼쪽 창문은 그대로 뒀어요."), ExitMessageRules.driverReport(seat, r, snap(), true))
    }

    @Test
    fun `실패하면 직접 확인을 부탁한다`() {
        val r = listOf(CommandResult(VehicleCommand.SetSeatHeater(seat, 0), CommandResult.Status.FAILED))
        assertTrue(ExitMessageRules.driverReport(seat, r, snap(), true).single().contains("직접 확인해 주세요"))
    }

    @Test
    fun `운전석 창문은 알리기만 하고 더운 실내는 환기를 권한다`() {
        // A12: 운전석 창문 알림은 비가 오거나 1시간 안에 비 소식이 있을 때만
        val out = ExitMessageRules.driverReport(
            seat, emptyList(), snap(mapOf(SeatId.ROW_1_LEFT to SeatState(windowOpenPercent = 50)), cabin = 33f), true,
            weather = WeatherInfo(rainWithinHour = true),
        )
        assertTrue(out.contains("운전석 창문이 열려 있어요."))
        assertTrue(out.contains("실내가 33℃예요. 다음 탑승 전에 환기해 주세요."))
    }

    @Test
    fun `비 소식이 없으면 운전석 창문이 열려 있어도 말하지 않는다`() {
        val s = snap(mapOf(SeatId.ROW_1_LEFT to SeatState(windowOpenPercent = 50)))
        assertTrue(ExitMessageRules.driverReport(seat, emptyList(), s, true, weather = WeatherInfo(rainingNow = false, rainWithinHour = false)).isEmpty())
        assertTrue(ExitMessageRules.driverReport(seat, emptyList(), s, true).isEmpty())       // 날씨를 모를 때도
    }

    @Test
    fun `이 좌석에서만 물건은 그 좌석이 내릴 때만 붙는다`() {
        val items = listOf(CarryItem(1, "카시트 가방", ItemCondition.SEAT, SeatId.ROW_2_RIGHT))
        val other = ExitMessageRules.passengerMessage(WeatherInfo(), snap(), items, seat = SeatId.ROW_1_RIGHT)
        assertEquals(listOf("두고 가시는 물건은 없나요?"), other.sentences)
        val mine = ExitMessageRules.passengerMessage(WeatherInfo(), snap(), items, seat = SeatId.ROW_2_RIGHT)
        assertTrue(mine.sentences.contains("카시트 가방 챙기셨어요?"))
    }

    @Test
    fun `붙일 물건·날씨 문장이 없으면 기본 멘트는 이미 말했을 때 다시 하지 않는다`() {
        assertTrue(ExitMessageRules.passengerMessage(WeatherInfo(), snap(), emptyList(), baseAlreadySaid = true).sentences.isEmpty())
        val rain = ExitMessageRules.passengerMessage(WeatherInfo(rainingNow = true), snap(), emptyList(), baseAlreadySaid = true)
        assertTrue(rain.sentences.contains("밖에 비가 와요. 우산 챙기세요."))                 // 붙일 문장이 있으면 그대로
    }
}