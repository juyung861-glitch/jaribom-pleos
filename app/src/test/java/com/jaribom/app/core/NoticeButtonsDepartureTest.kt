package com.jaribom.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F12-fix: 출발 순간 알림 버튼.
 *  - 에뮬레이터 재현: 속도 0 이 들어오는 차에서 P→D 순간 택시 출발 알림이 [열선 끄기]를 단 채 나갔다
 *    (자체 판정은 D·속도 0 = 정차, CarUx 제한 신호는 7ms 늦게 옴).
 *  - 기어가 P 가 아니면 속도와 상관없이 버튼을 숨긴다. 이미 낸 버튼 알림은 제한이 시작될 때 버튼만 떼고,
 *    다시 정차하면 F10-4 규칙대로 유효 에피소드만 붙인다.
 */
class NoticeButtonsDepartureTest {
    private val right = SeatId.ROW_2_RIGHT
    private val front = SeatId.ROW_1_RIGHT

    /** 속도 0 이 1초 넘게 이어진 상태(자체 판정 = 정차) */
    private fun snap(gear: Gear) = VehicleSnapshot(gear = gear, speedMps = 0f, slowSinceMs = 0L, timestampMs = 10_000L)

    @Test
    fun `P에서 D로 바뀐 직후 속도가 0이어도 버튼을 숨긴다`() {
        val d = snap(Gear.DRIVE)
        assertFalse("재현 조건: 화면 제한만 보면 D·속도 0 은 제한이 아니다", UxPolicy.screenLocked(carUx = null, snap = d))
        assertTrue(NoticeButtons.hidden(carUx = null, snap = d))
        assertTrue(NoticeButtons.hidden(carUx = false, snap = snap(Gear.NEUTRAL)))
        assertTrue(NoticeButtons.hidden(carUx = null, snap = snap(Gear.REVERSE)))
        assertTrue("기어를 모르면 숨긴다", NoticeButtons.hidden(carUx = null, snap = snap(Gear.UNKNOWN)))
    }

    @Test
    fun `P이고 제한이 없을 때만 버튼을 보인다`() {
        assertFalse(NoticeButtons.hidden(carUx = null, snap = snap(Gear.PARK)))
        assertFalse(NoticeButtons.hidden(carUx = false, snap = snap(Gear.PARK)))
        assertTrue("CarUx 제한은 그대로 숨김", NoticeButtons.hidden(carUx = true, snap = snap(Gear.PARK)))
    }

    @Test
    fun `제한 신호가 알림보다 늦게 와도 이미 낸 버튼 알림의 버튼을 뗀다`() {
        val d = DeferredNotices<String>()
        d.markShown(1, "택시 출발 알림", mapOf(front to 2))      // 제한 전에 버튼을 달고 나감
        assertEquals(listOf(1 to "택시 출발 알림"), d.hideShown()) // 제한 시작: 버튼만 떼서 다시 낼 대상
        assertTrue("한 번 뗀 것은 다시 떼지 않는다", d.hideShown().isEmpty())
        assertEquals("다시 정차: 에피소드가 같으면 버튼을 붙인다", listOf(1 to "택시 출발 알림"), d.release { 2 })
        assertEquals("다시 붙인 알림은 다음 제한 때 또 뗀다", listOf(1 to "택시 출발 알림"), d.hideShown())
    }

    @Test
    fun `뗀 뒤 그 좌석 문이 다시 열렸으면 정차해도 버튼을 붙이지 않는다`() {
        val d = DeferredNotices<String>()
        d.markShown(1, "A", mapOf(front to 2))
        d.markShown(2, "B", mapOf(right to 5))
        assertEquals(2, d.hideShown().size)
        assertEquals(listOf(2 to "B"), d.release { s -> if (s == front) 3 else 5 })
        assertEquals("버튼 없이 둔 A 는 더 따라가지 않는다", listOf(2 to "B"), d.hideShown())
    }

    @Test
    fun `지운 알림은 버튼 떼기 대상에서도 빠진다`() {
        val d = DeferredNotices<String>()
        d.markShown(1, "A", mapOf(front to 1))
        d.forget(1)
        assertTrue(d.hideShown().isEmpty())
        assertTrue(d.release { 1 }.isEmpty())
    }
}
