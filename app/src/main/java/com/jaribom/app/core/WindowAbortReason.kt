package com.jaribom.app.core

/**
 * 창문을 닫는 도중 멈추는 원인(6차 F9-1, 끼임 안전). 닫기를 시작한 뒤 그 좌석(또는 같은 열 옆자리, F13-2)에서 아래 중 하나가 생기면 즉시 멈춘다.
 * 자동으로 다시 닫지 않는다. 사망 감지(연결 끊김)는 쓰기가 불가능하므로 정지 시도 없이 "연결 없음"으로 처리한다(여기 원인이 아님).
 */
enum class WindowAbortReason(val phrase: String) {
    DOOR("문이 열려"),
    SEAT("사람이 앉아"),
    BELT("벨트가 채워져"),
    /** 같은 열 옆자리(운전석 제외)에 착좌·벨트 체결이 새로 생김(F13-2) */
    NEIGHBOR("옆자리에 사람이 앉아");

    companion object {
        /** 닫기를 시작할 때의 좌석 상태 [start] 와 지금 [now] 를 비교한다. 시작할 때 이미 그랬던 것은 원인으로 보지 않는다. */
        fun detect(start: SeatState, now: SeatState): WindowAbortReason? = when {
            now.doorOpen == true && start.doorOpen != true -> DOOR
            now.occupied == true && start.occupied != true -> SEAT
            now.beltBuckled == true && start.beltBuckled != true -> BELT
            else -> null
        }

        /**
         * 같은 열 옆자리(F13-2): 닫기를 시작할 때 [start] 에 없던 착좌·벨트 체결이 [now] 에 새로 생겨야 원인이다.
         * 시작 때 이미 있던 사람(운전자가 버튼으로 직접 닫는 경우)은 원인으로 보지 않는다.
         */
        fun detectNeighbor(start: SeatState, now: SeatState): WindowAbortReason? =
            if ((now.occupied == true && start.occupied != true) || (now.beltBuckled == true && start.beltBuckled != true)) NEIGHBOR else null

        /** 정지 쓰기까지 실패했을 때 [CommandResult.why] 에 붙이는 꼬리표 */
        const val STOP_FAILED_SUFFIX = ":STOPFAIL"

        /** 정지 뒤에도 창문이 계속 움직여 한 번 더 정지했을 때(F10-5) 붙이는 꼬리표 */
        const val NOT_STOPPED_SUFFIX = ":NOSTOP"

        fun notStopped(why: String): Boolean = why.contains(NOT_STOPPED_SUFFIX)

        /** 재정지 뒤에도 창문이 계속 움직였을 때(F14-3) 붙이는 꼬리표. [NOT_STOPPED_SUFFIX] 와 함께 붙는다. */
        const val STILL_MOVING_SUFFIX = ":STILLMOVING"

        fun stillMoving(why: String): Boolean = why.contains(STILL_MOVING_SUFFIX)

        /** [CommandResult.why] 에 담긴 원인 이름을 문구로. 모르는 값이면 일반 문구. */
        fun phrase(why: String): String = entries.firstOrNull { it.name == why.substringBefore(':') }?.phrase ?: "문제가 생겨"

        fun stopFailed(why: String): Boolean = why.endsWith(STOP_FAILED_SUFFIX)

        /** 멈춤 안내 뒤에 붙는 문장들: 정지 명령 실패, 정지 뒤에도 움직임. 좌석 이름은 [seatLabel]. */
        fun tail(why: String, seatLabel: String): String =
            (if (stopFailed(why)) " 정지 명령이 실패했어요. 직접 확인해 주세요." else "") +
                (when {
                    // F14-3: 재정지 뒤에도 움직였다. 더 쓰지 않고 직접 확인을 부탁한다(끌 수 없는 경고).
                    stillMoving(why) -> " $seatLabel 창문이 멈추지 않았어요. 직접 확인해 주세요."
                    notStopped(why) -> " $seatLabel 창문이 멈추지 않았을 수 있어요. 확인해 주세요."
                    else -> ""
                })
    }
}
