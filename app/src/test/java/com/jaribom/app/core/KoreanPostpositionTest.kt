package com.jaribom.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** F10-6: 하차 한마디의 물건 나열을 받침에 맞는 조사로 이어 붙인다. 영문·숫자로 끝나는 이름은 쉼표형으로 깨지지 않게 한다. */
class KoreanPostpositionTest {

    @Test
    fun `받침 판정 - 한글은 받침 여부, 영문 숫자는 모름`() {
        assertEquals(true, KoreanPostposition.hasBatchim("지갑"))
        assertEquals(true, KoreanPostposition.hasBatchim("우산"))
        assertEquals(true, KoreanPostposition.hasBatchim("휴대폰"))
        assertEquals(false, KoreanPostposition.hasBatchim("마스크"))
        assertEquals(false, KoreanPostposition.hasBatchim("카메라"))
        assertEquals(false, KoreanPostposition.hasBatchim("충전기(대)"))     // 끝의 기호는 건너뛴다
        assertNull(KoreanPostposition.hasBatchim("USB"))
        assertNull(KoreanPostposition.hasBatchim("노트북 15"))
        assertNull(KoreanPostposition.hasBatchim(""))
    }

    @Test
    fun `조사 함수 - 이랑·랑, 은·는, 이·가, 을·를, 과·와`() {
        assertEquals("지갑이랑", KoreanPostposition.iRang("지갑"))
        assertEquals("마스크랑", KoreanPostposition.iRang("마스크"))
        assertEquals("지갑은", KoreanPostposition.eunNeun("지갑"))
        assertEquals("마스크는", KoreanPostposition.eunNeun("마스크"))
        assertEquals("지갑이", KoreanPostposition.iGa("지갑"))
        assertEquals("마스크가", KoreanPostposition.iGa("마스크"))
        assertEquals("지갑을", KoreanPostposition.eulReul("지갑"))
        assertEquals("마스크를", KoreanPostposition.eulReul("마스크"))
        assertEquals("지갑과", KoreanPostposition.gwaWa("지갑"))
        assertEquals("마스크와", KoreanPostposition.gwaWa("마스크"))
        assertEquals("받침을 모르면 조사를 붙이지 않는다", "USB", KoreanPostposition.iRang("USB"))
    }

    @Test
    fun `아이 말투 나열 - 이랑·랑으로 잇고 영문은 쉼표형`() {
        fun j(vararg n: String) = KoreanPostposition.joinChild(n.toList())
        assertEquals("", j())
        assertEquals("지갑", j("지갑"))
        assertEquals("지갑이랑 우산", j("지갑", "우산"))
        assertEquals("우산이랑 지갑", j("우산", "지갑"))
        assertEquals("마스크랑 지갑", j("마스크", "지갑"))
        assertEquals("지갑, 우산이랑 마스크", j("지갑", "우산", "마스크"))
        assertEquals("USB, 지갑", j("USB", "지갑"))
        assertEquals("지갑, USB, 우산", j("지갑", "USB", "우산"))          // 조사를 붙일 이름이 영문이면 전부 쉼표형
        assertEquals("지갑이랑 USB", j("지갑", "USB"))                    // 영문이 마지막이어도 앞 이름 받침으로 충분
    }

    @Test
    fun `존댓말 나열 - 과·와로 잇고 영문은 쉼표형`() {
        fun j(vararg n: String) = KoreanPostposition.joinPolite(n.toList())
        assertEquals("지갑과 우산", j("지갑", "우산"))
        assertEquals("마스크와 지갑", j("마스크", "지갑"))
        assertEquals("지갑, 우산과 마스크", j("지갑", "우산", "마스크"))
        assertEquals("USB, 지갑", j("USB", "지갑"))
    }

    // ── 문장 단위: 물건 0·1·2·3개 × 아이 말투/존댓말 × 날씨 있음/없음 ──
    private fun item(i: Int, n: String) = CarryItem(i.toLong(), n, ItemCondition.ALWAYS)
    private val snap = VehicleSnapshot()
    private val seat = SeatId.ROW_2_RIGHT
    private val clear = WeatherInfo()
    private val rain = WeatherInfo(rainingNow = true)

    private fun polite(items: List<CarryItem>, w: WeatherInfo) = ExitMessageRules.passengerMessage(w, snap, items, seat = seat).sentences
    private fun child(items: List<CarryItem>, w: WeatherInfo) = ExitMessageRules.childMessage(w, snap, items, seat).sentences

    @Test
    fun `물건 없음 - 날씨 없음 있음 둘 다 자연스럽다`() {
        assertEquals(listOf("두고 가시는 물건은 없나요?"), polite(emptyList(), clear))
        assertEquals(listOf("두고 가시는 물건은 없나요?", "밖에 비가 와요. 우산 챙기세요."), polite(emptyList(), rain))
        assertEquals(listOf("두고 가는 거 없지? 조심히 내려."), child(emptyList(), clear))
        assertEquals(listOf("밖에 비 와. 우산 챙겨.", "두고 가는 거 없지? 조심히 내려."), child(emptyList(), rain))
    }

    @Test
    fun `물건 1·2·3개 - 존댓말은 과 와, 아이 말투는 이랑 랑`() {
        val one = listOf(item(1, "지갑"))
        val two = listOf(item(1, "지갑"), item(2, "우산"))
        val three = listOf(item(1, "지갑"), item(2, "우산"), item(3, "마스크"))
        assertEquals(listOf("두고 가시는 물건은 없나요?", "지갑 챙기셨어요?"), polite(one, clear))
        assertEquals(listOf("두고 가시는 물건은 없나요?", "지갑과 우산 챙기셨어요?"), polite(two, clear))
        assertEquals(listOf("두고 가시는 물건은 없나요?", "지갑, 우산과 마스크 챙기셨어요?"), polite(three, clear))
        assertEquals(listOf("지갑 챙겼지? 조심히 내려."), child(one, clear))
        assertEquals(listOf("지갑이랑 우산 챙겼지? 조심히 내려."), child(two, clear))
        assertEquals(listOf("지갑, 우산이랑 마스크 챙겼지? 조심히 내려."), child(three, clear))
        // 비가 오면 날씨 문장에서 우산을 이미 말했으므로 목록에서 뺀다(중복 없음)
        assertEquals(listOf("밖에 비 와. 우산 챙겨.", "지갑 챙겼지? 조심히 내려."), child(two, rain))
        assertTrue(polite(two, rain).any { it == "지갑 챙기셨어요?" })
    }

    @Test
    fun `영문 숫자로 끝나는 이름도 깨지지 않는다`() {
        val items = listOf(item(1, "USB"), item(2, "지갑"), item(3, "노트북 15"))
        // 조사를 붙이는 이름(끝에서 두 번째)이 한글이면 조사를 쓰고, 영문·숫자로 끝나는 이름 뒤에는 조사를 붙이지 않는다.
        assertEquals(listOf("USB, 지갑이랑 노트북 15 챙겼지? 조심히 내려."), child(items, clear))
        assertEquals(listOf("두고 가시는 물건은 없나요?", "USB, 지갑과 노트북 15 챙기셨어요?"), polite(items, clear))
        val allLatin = listOf(item(1, "USB"), item(2, "iPad 3"))
        assertEquals(listOf("USB, iPad 3 챙겼지? 조심히 내려."), child(allLatin, clear))
    }
}
