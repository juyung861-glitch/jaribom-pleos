package com.jaribom.app.core

/**
 * 차속(PERF_VEHICLE_SPEED) 신호의 상태(7차 F10-2). 속도 권한(android.car.permission.CAR_SPEED)은 런타임 요청으로 받는다.
 *  - UNKNOWN: 아직 모름(연결 전)
 *  - OK: 구독 중. 값이 들어오면 정차·주행을 속도로 판정한다([VehicleMotion])
 *  - PERMISSION_DENIED: 권한 거부 → 속도 null, 기어로만 판정
 *  - UNSUPPORTED: 권한은 있으나 이 차가 속도 속성을 주지 않음 → 속도 null, 기어로만 판정
 */
enum class SpeedSignal { UNKNOWN, OK, PERMISSION_DENIED, UNSUPPORTED }

object SpeedSignalText {
    /** 설정 화면에 보여 줄 한 줄. 속도를 정상으로 받고 있거나 아직 모르면 null. */
    fun line(s: SpeedSignal): String? = when (s) {
        SpeedSignal.PERMISSION_DENIED -> "속도 권한 없음: 기어로만 판정해요"
        SpeedSignal.UNSUPPORTED -> "이 차는 속도 신호가 없어요"
        SpeedSignal.OK, SpeedSignal.UNKNOWN -> null
    }

    /** [권한 허용] 버튼을 보여 줄 상태: 거부일 때만(미지원은 권한 문제가 아니다) */
    fun canRequest(s: SpeedSignal): Boolean = s == SpeedSignal.PERMISSION_DENIED
}
