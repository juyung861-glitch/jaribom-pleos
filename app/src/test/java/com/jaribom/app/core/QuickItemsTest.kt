package com.jaribom.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F12-3 챙길 물건 간소화: "자주 쓰는 물건" 칩 한 번 탭으로 등록·해제. 칩은 기본 조건이 붙어 있다.
 * 등록이 없어도 날씨 안내(비 → 우산, 3℃ 이하 → 따뜻하게)는 기본으로 나온다. 말할 때 최대 3개, 우산 중복 제거는 그대로.
 */
class QuickItemsTest {

    private val snap = VehicleSnapshot()

    @Test
    fun `칩은 지갑·휴대폰·가방·물통·우산이고 우산만 비 올 때다`() {
        assertEquals(listOf("지갑", "휴대폰", "가방", "물통", "우산"), QuickItems.presets.map { it.name })
        assertEquals(ItemCondition.RAIN, QuickItems.presets.single { it.name == "우산" }.condition)
        assertTrue(QuickItems.presets.filter { it.name != "우산" }.all { it.condition == ItemCondition.ALWAYS })
    }

    @Test
    fun `한 번 탭하면 기본 조건으로 등록되고 다시 탭하면 빠진다`() {
        val wallet = QuickItems.presets.first()
        val added = QuickItems.toggle(emptyList(), wallet, newId = 7)
        assertEquals(listOf(CarryItem(7, "지갑", ItemCondition.ALWAYS)), added)
        assertTrue(QuickItems.isOn(added, wallet))
        assertTrue(QuickItems.toggle(added, wallet, newId = 8).isEmpty())
    }

    @Test
    fun `직접 등록한 같은 이름 물건도 칩으로 켜진 것으로 보고 끄면 함께 빠진다 - 좌석 지정 물건은 그대로`() {
        val umbrella = QuickItems.presets.single { it.name == "우산" }
        val items = listOf(
            CarryItem(1, " 우산 ", ItemCondition.ALWAYS),
            CarryItem(2, "우산", ItemCondition.SEAT, SeatId.ROW_2_LEFT),
            CarryItem(3, "마스크", ItemCondition.ALWAYS),
        )
        assertTrue(QuickItems.isOn(items, umbrella))
        val after = QuickItems.toggle(items, umbrella, newId = 9)
        assertEquals(listOf(2L, 3L), after.map { it.id })
        // 칩에 없는 물건은 '더 보기' 목록에 남는다
        assertEquals(listOf(3L), QuickItems.others(items).filter { it.condition != ItemCondition.SEAT }.map { it.id })
        assertFalse(QuickItems.isOn(after, umbrella))
    }

    @Test
    fun `등록이 없어도 비·추위는 날씨로 안내한다`() {
        val m = ExitMessageRules.passengerMessage(WeatherInfo(rainingNow = true, outdoorTempC = 1f), snap, emptyList())
        assertTrue(m.sentences.joinToString(" "), m.sentences.any { "우산" in it })
        assertTrue(m.firedRules.containsAll(listOf("WEATHER_RAIN_NOW", "OUTDOOR_COLD")))
    }

    @Test
    fun `존댓말 안내도 물건은 최대 3개이고 비 오는 날 우산 칩은 날씨 문장과 겹치지 않는다`() {
        var items = emptyList<CarryItem>()
        QuickItems.presets.forEachIndexed { i, p -> items = QuickItems.toggle(items, p, newId = i.toLong()) }
        val m = ExitMessageRules.passengerMessage(WeatherInfo(rainingNow = true), snap, items)
        val itemLine = m.sentences.last()
        assertEquals("지갑, 휴대폰과 가방 챙기셨어요?", itemLine)
        assertEquals(1, m.sentences.joinToString(" ").split("우산").size - 1)   // 우산은 날씨 문장에서 한 번만
    }
}
