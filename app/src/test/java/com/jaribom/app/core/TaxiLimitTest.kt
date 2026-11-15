package com.jaribom.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F10-1: 택시 모드 알림은 아이 좌석 보조 알림의 "하루 5번" 상한을 받지 않는다. 손님(좌석 에피소드)당 1번, 하루 상한 없음.
 * 아이 좌석 보조 알림의 하루 5번은 그대로다.
 */
class TaxiLimitTest {
    private val right = SeatId.ROW_2_RIGHT

    @Test
    fun `택시 모드는 6번째 손님도 알림이 나간다 - 하루 상한 없음`() {
        val l = AlertLimiter()
        val sent = (0 until 12).map { i -> l.onSeatDoorOpened(right); l.tryTaxi(right) }
        assertTrue("택시는 손님마다 나가야 함: $sent", sent.all { it })
    }

    @Test
    fun `아이 좌석 보조 알림은 6번째가 억제된다 - 하루 5번 그대로`() {
        val l = AlertLimiter()
        val sent = (0 until 7).map { i -> l.onSeatDoorOpened(right); l.tryAssist(right, i.toLong()) }
        assertEquals(listOf(true, true, true, true, true, false, false), sent)
        assertEquals(5, AlertPolicy.ASSIST_PER_DAY)
    }

    @Test
    fun `택시 같은 손님이 반복되면 억제된다 - 에피소드 규칙`() {
        val l = AlertLimiter()
        l.onSeatDoorOpened(right)
        assertTrue(l.tryTaxi(right))
        assertFalse(l.tryTaxi(right))                  // 문이 다시 열리지 않음 = 같은 손님
        l.onSeatDoorOpened(right)
        assertTrue(l.tryTaxi(right))                   // 다음 손님
    }

    @Test
    fun `택시 알림은 아이 좌석 하루 횟수를 쓰지 않는다`() {
        val l = AlertLimiter()
        repeat(8) { l.onSeatDoorOpened(right); l.tryTaxi(right) }
        assertEquals(0, l.assistCountToday(0))
        l.onSeatDoorOpened(right)
        assertTrue(l.tryAssist(right, 0))              // 아이 좌석 보조는 아직 하루 5번이 남아 있다
    }
}
