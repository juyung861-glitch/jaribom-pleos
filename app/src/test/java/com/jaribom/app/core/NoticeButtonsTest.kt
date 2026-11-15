package com.jaribom.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F10-4: 주행 중(화면 편집 제한: CarUx 제한 또는 자체 판정이 정차 아님)에는 알림에 액션 버튼을 붙이지 않고 문장만 보낸다.
 * 제한이 풀리면 아직 유효한(좌석 에피소드가 같은) 알림만 버튼을 붙여 다시 낸다. 본문은 그대로다.
 */
class NoticeButtonsTest {
    private val right = SeatId.ROW_2_RIGHT
    private val left = SeatId.ROW_2_LEFT

    private val all = listOf(NoticeAction.RETRY, NoticeAction.CLIMATE_OFF, NoticeAction.WINDOW_CLOSE, NoticeAction.ADULT_ABOARD)

    @Test
    fun `제한 중이면 알림의 액션이 0개다 - 제한이 없으면 그대로`() {
        assertTrue(NoticeButtons.visible(restricted = true, wanted = all).isEmpty())
        assertEquals(all, NoticeButtons.visible(restricted = false, wanted = all))
        assertTrue(NoticeButtons.visible(restricted = true, wanted = listOf(NoticeAction.RETRY)).isEmpty())   // [다시 시도]도 같은 규칙
    }

    @Test
    fun `제한이 풀리면 유효한 알림만 버튼을 붙여 다시 낸다`() {
        val d = DeferredNotices<String>()
        d.defer(1, "본문 A", mapOf(right to 3))
        val released = d.release { 3 }
        assertEquals(listOf(1 to "본문 A"), released)
        assertTrue("한 번 내보낸 뒤에는 비운다", d.release { 3 }.isEmpty())
    }

    @Test
    fun `풀리기 전에 에피소드가 바뀌었으면 버튼을 붙이지 않는다`() {
        val d = DeferredNotices<String>()
        d.defer(1, "본문 A", mapOf(right to 3))
        d.defer(2, "본문 B", mapOf(left to 7))
        val released = d.release { seat -> if (seat == right) 4 else 7 }       // 오른쪽 문이 다시 열려 다음 손님
        assertEquals(listOf(2 to "본문 B"), released)
    }

    @Test
    fun `합쳐진 알림의 본문은 그대로 다시 나간다 - 첫 줄만 보낸 제한 중 문장이 아니라 전체 본문`() {
        val full = "뒷좌석 오른쪽 창문을 닫았어요.\n뒷좌석 오른쪽 열선을 껐어요.\n이번 달 창문 닫음 7"
        val d = DeferredNotices<String>()
        d.defer(5, full, mapOf(right to 1))
        assertEquals(full, d.release { 1 }.single().second)
    }

    @Test
    fun `같은 알림을 다시 내면 대기 항목을 갱신한다 - 알림이 지워지면 잊는다`() {
        val d = DeferredNotices<String>()
        d.defer(1, "예전", mapOf(right to 1))
        d.defer(1, "새 본문", mapOf(right to 1))
        d.forget(9)                                       // 없는 키는 무시
        assertEquals(listOf(1 to "새 본문"), d.release { 1 })
        d.defer(2, "지울 것", mapOf(right to 1))
        d.forget(2)
        assertTrue(d.release { 1 }.isEmpty())
    }
}
