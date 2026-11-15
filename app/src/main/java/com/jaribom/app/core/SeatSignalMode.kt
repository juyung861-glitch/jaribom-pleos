package com.jaribom.app.core

/**
 * 좌석별 신호 지원 여부(6차 F9-3). 실차에는 2열 착좌 센서가 없는 경우가 많다.
 * 연결 때(재연결 때도) 좌석마다 착좌·벨트 신호가 "지원 안 함"인지 판별한다. "지원하지만 값이 null"과 구분한다.
 * 판별하지 못한 것([Support.UNKNOWN])은 지원하는 것으로 본다 — 에뮬레이터 동작(벨트 값이 null 이면 풀림으로 읽기)을 보존한다.
 */
data class SeatSignals(val occupancy: Support, val belt: Support) {
    enum class Support { SUPPORTED, UNSUPPORTED, UNKNOWN }
}

enum class SeatSignalMode(val label: String) {
    /** 착좌 지원(또는 판별 못 함): 착좌+문(+벨트). 지금 동작 그대로. */
    FULL("착좌·문으로 판단해요"),

    /**
     * 착좌 미지원·벨트 지원: 탑승 = 벨트 체결, 하차 = 벨트 풀림 + 그 좌석 문 열렸다 닫힘. 하차 안내 음성·열선/통풍 끄기는 한다.
     * 창문은 자동으로 닫지 않는다(빈 좌석을 확인할 수 없어 끼임 조건을 못 지킴). 운전자 알림의 [창문 닫기] 버튼으로만 닫는다.
     */
    BELT_DOOR("벨트·문으로 판단해요(창문은 확인 후 닫아요)"),

    /** 착좌·벨트 둘 다 미지원: 그 좌석은 관리하지 않는다. */
    NONE("신호가 없어 관리하지 않아요");

    companion object {
        fun of(s: SeatSignals): SeatSignalMode = when {
            s.occupancy != SeatSignals.Support.UNSUPPORTED -> FULL
            s.belt != SeatSignals.Support.UNSUPPORTED -> BELT_DOOR
            else -> NONE
        }
    }
}
