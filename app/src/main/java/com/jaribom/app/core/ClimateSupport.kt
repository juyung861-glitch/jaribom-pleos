package com.jaribom.app.core

/**
 * 좌석 하나의 열선·통풍 지원 여부(F12-5). 연결 때 capability·읽기 오류("not supported")로 판별한다.
 * 판별하지 못한 것([SeatSignals.Support.UNKNOWN])은 예전처럼 있는 것으로 본다 — 단 뒷좌석 통풍 자동 켜기는 SUPPORTED 일 때만 한다.
 */
data class ClimateSupport(
    val heater: SeatSignals.Support = SeatSignals.Support.UNKNOWN,
    val vent: SeatSignals.Support = SeatSignals.Support.UNKNOWN,
) {
    /** 설정 화면 좌석 항목 아래 한 줄. 없는 것이 없으면 null. */
    val line: String?
        get() {
            val missing = listOfNotNull(
                "열선".takeIf { heater == SeatSignals.Support.UNSUPPORTED },
                "통풍".takeIf { vent == SeatSignals.Support.UNSUPPORTED },
            )
            return if (missing.isEmpty()) null else "이 좌석은 ${missing.joinToString("·")}이 없어요"
        }
}
