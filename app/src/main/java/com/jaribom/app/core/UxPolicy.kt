package com.jaribom.app.core

/**
 * 화면 편집 제한 정책 (6차 F6-3, B안).
 *  - 안전(음성 허용·정리 쓰기)은 [VehicleMotion] 자체 판정이 1차다. CarUxRestrictionsManager 는 거기에 관여하지 않는다.
 *  - 설정 화면 편집(아이 좌석·물건 등록·통계·택시 모드 토글 등)은 자체 판정이 정차가 아니거나 CarUx 가
 *    isRequiresDistractionOptimization 을 요구하면 막는다. CarUx 가 없으면(null) 제한을 더하지 않는다.
 *  - 제한 중에는 알림 문구를 짧게 낸다.
 */
object UxPolicy {
    const val LOCK_MESSAGE = "정차 후 바꿀 수 있어요"

    /** [carUx] true = 플랫폼이 주의 분산 최적화를 요구함, false = 요구하지 않음, null = 매니저 없음·읽을 수 없음 */
    fun screenLocked(carUx: Boolean?, snap: VehicleSnapshot): Boolean = carUx == true || !VehicleMotion.isStopped(snap)

    fun canEdit(locked: Boolean): Boolean = !locked

    /**
     * 주행 중 설정 화면 부제(F12-fix2). 실제 동작: 설정 편집은 막히고(익명 통계 끄기만 됨), 알림은 항상 나가며,
     * 속도 값이 있고 0.5 m/s 를 넘으면 동승자 하차 안내 음성을 하지 않는다(운전자 보고 음성은 P·정차에서만).
     */
    const val DRIVING_NOTE = "주행 중에는 설정을 바꿀 수 없어요. 안내는 알림으로 남고, 달리는 중에는 동승자 음성을 쉬어요."

    /** 익명 통계를 [on] 으로 바꿀 수 있는가. 끄기(옵트아웃)는 주행 중에도 되고, 켜기는 정차 후에만(F6-7·F12-fix2). */
    fun canSetTelemetry(on: Boolean, locked: Boolean): Boolean = !on || !locked

    /** 화면의 익명 통계 스위치를 누를 수 있는가: 누르면 [currentlyOn] 의 반대가 된다. */
    fun telemetrySwitchEnabled(currentlyOn: Boolean, locked: Boolean): Boolean = canSetTelemetry(on = !currentlyOn, locked = locked)

    /** 제한 중이면 첫 줄만(이번 달 누적 같은 덧붙임 줄은 뺀다). */
    fun notificationText(text: String, locked: Boolean): String =
        if (locked) text.lineSequence().first().trim() else text
}
