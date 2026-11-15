package com.jaribom.app.core

import kotlin.math.abs

/**
 * 주행·정차 판정이 사는 유일한 곳(6차 F6-1). 다른 코드는 이 값만 본다.
 *  - 속도(PERF_VEHICLE_SPEED, m/s)가 있으면: 정차 = |속도| ≤ [STOP_MPS] 가 [STOP_HOLD_MS] 이상 유지. 기어와 무관하게 이보다 빠르면 주행.
 *  - 속도가 null 이면(신호 없음·권한 거부·에뮬레이터): 기어로 떨어진다. P·N = 정차, D·R = 주행, 모름 = 정차 아님.
 *  - 음성 허용 = 기어 P 이고 정차. P 인데 움직이면(견인·경사) 음성을 하지 않는다. 알림은 항상 간다.
 *  - 창문 닫기 끼임 조건(빈 좌석+문 닫힘)과 열선·통풍 허용은 기어·속도와 무관하다([SafetyGate]).
 */
object VehicleMotion {
    const val STOP_MPS = 0.5f
    const val STOP_HOLD_MS = 1_000L

    fun isStopped(s: VehicleSnapshot): Boolean {
        val v = s.speedMps ?: return s.gear == Gear.PARK || s.gear == Gear.NEUTRAL
        val since = s.slowSinceMs ?: return false
        return abs(v) <= STOP_MPS && s.timestampMs - since >= STOP_HOLD_MS
    }

    /** 확실히 달리는 중. 속도가 있으면 |속도| > [STOP_MPS], 없으면 기어 D·R. 착좌 반전 판정처럼 "주행을 확인한 뒤"에만 쓰는 곳이 본다. */
    fun isDriving(s: VehicleSnapshot): Boolean {
        val v = s.speedMps ?: return s.gear == Gear.DRIVE || s.gear == Gear.REVERSE
        return abs(v) > STOP_MPS
    }

    /** 운전자 보고 음성: 기어 P 이고 정차일 때만. */
    fun voiceAllowed(s: VehicleSnapshot): Boolean = s.gear == Gear.PARK && isStopped(s)

    /**
     * 동승자 하차 안내 음성(F7-1): 문이 열린 상황에서 부른다. 문이 열린 것 자체를 정차 증거로 보고 기어 D 에서도 허용한다
     * ("학원 앞, D, 아이 하차"). 단 속도 값이 있고 |속도| > [STOP_MPS] 면 하지 않는다. 속도가 null 이면 허용한다.
     */
    fun passengerVoiceAllowed(s: VehicleSnapshot): Boolean = s.speedMps?.let { abs(it) <= STOP_MPS } ?: true
}

/**
 * AAOS GEAR_SELECTION 값(android.car.VehicleGear 상수) → [Gear] (F6-2).
 * P·R·N·D 외의 값(숫자 기어·알 수 없는 값·null)은 "모름"이고, 모르면 정차로 보지 않는다([VehicleMotion.isStopped]).
 * 상수 값은 android.car.jar(API 37)의 VehicleGear 와 같다. core 는 android.car 에 의존하지 않아 숫자로 둔다.
 */
object GearMapping {
    const val CAR_GEAR_NEUTRAL = 1
    const val CAR_GEAR_REVERSE = 2
    const val CAR_GEAR_PARK = 4
    const val CAR_GEAR_DRIVE = 8

    fun fromCar(v: Int?): Gear = when (v) {
        CAR_GEAR_PARK -> Gear.PARK
        CAR_GEAR_REVERSE -> Gear.REVERSE
        CAR_GEAR_NEUTRAL -> Gear.NEUTRAL
        CAR_GEAR_DRIVE -> Gear.DRIVE
        else -> Gear.UNKNOWN
    }
}

/**
 * 속도 신호를 받을 때마다 "느려진 시각"을 이어서 기억한다. [VehicleSnapshot.slowSinceMs] 를 만든다.
 * 느려진 뒤 [VehicleMotion.STOP_HOLD_MS] 가 지났는지는 스냅샷 시각으로 판정하므로, 새 신호가 없어도 시간이 흐르면 정차가 된다.
 */
class MotionTracker {
    private var slowSinceMs: Long? = null

    /** @return 지금의 느려진 시각. 속도가 없거나 [VehicleMotion.STOP_MPS] 보다 빠르면 null. */
    fun update(speedMps: Float?, nowMs: Long): Long? {
        slowSinceMs = when {
            speedMps == null -> null
            abs(speedMps) > VehicleMotion.STOP_MPS -> null
            else -> slowSinceMs ?: nowMs
        }
        return slowSinceMs
    }
}
