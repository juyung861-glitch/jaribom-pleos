package com.jaribom.app.core

/**
 * 알림 버튼([열선 끄기]·[창문 닫기]) 안전장치(6차 F9 리뷰 반영).
 *  - 오래된 알림: 알림을 만들 때 버튼 대상 좌석의 "좌석 에피소드"([AlertLimiter])를 기록해 두고, 누를 때 지금 에피소드와 다르면
 *    (그 좌석 문이 다시 열렸다 = 다음 손님) 아무 쓰기도 하지 않는다.
 *  - 연타: 같은 좌석에 명령이 진행 중이면 다시 누른 것은 무시한다.
 */
object ButtonGuard {
    const val STALE_TEXT = "지난 하차 알림이라 실행하지 않았어요."

    fun isStale(recorded: Map<SeatId, Int>, current: (SeatId) -> Int): Boolean = recorded.any { (seat, ep) -> current(seat) != ep }
}

/** 좌석별 진행 중 표시. 메인 스레드에서만 쓴다. */
class ButtonGate {
    private val busy = mutableSetOf<SeatId>()

    /** 이 좌석들에 진행 중인 명령이 없으면 표시하고 true. 하나라도 진행 중이면 false(무시). */
    fun tryBegin(seats: Collection<SeatId>): Boolean {
        if (seats.any { it in busy }) return false
        busy += seats
        return true
    }

    fun end(seats: Collection<SeatId>) { busy -= seats.toSet() }
}
