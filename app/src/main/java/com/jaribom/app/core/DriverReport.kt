package com.jaribom.app.core

/**
 * 설정 "운전자 보고"(F12-1). 하차 정리의 운전자 보고를 어디까지 말할지 고른다.
 *  - [ALL]: 성공 보고("조수석 창문을 닫고 열선을 껐어요")까지 말한다.
 *  - [WARNINGS_ONLY](기본): 경고와 안내만 말한다. 성공은 '최근 하차 정리' 카드에만 남는다.
 *  - [OFF]: 음성으로 보고하지 않는다. 경고는 끌 수 없어 알림으로 남는다.
 * 경고([ReportKind.WARNING])는 어느 모드에서도 알린다. P 면 음성+알림, P 가 아니면 알림만이라는 기존 방식은 그대로다.
 */
enum class DriverReportMode(val label: String, val desc: String) {
    ALL("모두", "정리한 일까지 모두 말해요."),
    WARNINGS_ONLY("경고만", "못 한 일·실패·남은 물건처럼 확인할 일만 말해요."),
    OFF("끔", "음성으로 보고하지 않아요. 경고는 알림으로 남아요.");

    companion object {
        /** 앱 기본값(저장값이 없을 때) */
        val DEFAULT = WARNINGS_ONLY
        fun fromName(name: String?): DriverReportMode = entries.find { it.name == name } ?: DEFAULT
    }
}

/**
 * 운전자 보고 한 줄의 종류(F12-1).
 *  - [SUCCESS]: 실제로 한 일, 관리 꺼짐 안내. "모두"일 때만 말한다.
 *  - [WARNING]: 문이 열려 있어 못 한 일(그대로 둠), 정리 실패, 닫다가 멈춤, 남은 물건 의심, 정리 뒤 문 열림, 창문 확인 부탁. 항상 알린다.
 *  - [DRIVER_WINDOW]: "운전석 창문이 열려 있어요"(비·1시간 안 비). 안내이며 따로 켜고 끈다.
 *  - [CABIN_HEAT]: "실내가 N℃예요. 환기해 주세요"(30℃ 이상). 안내이며 따로 켜고 끈다.
 *  - [NOTICE]: 문장이 남는 안내(경고 아님). "옆자리에 사람이 있어 {좌석} 창문은 닫지 않았어요."(F13-2). 모드와 상관없이 내고,
 *    [창문 닫기] 버튼이 붙어 "끔"이어도 알림은 남는다(버튼 필요 보고).
 */
enum class ReportKind { SUCCESS, WARNING, DRIVER_WINDOW, CABIN_HEAT, NOTICE }

data class ReportLine(val kind: ReportKind, val text: String)
