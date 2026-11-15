package com.jaribom.app.core

/**
 * 착좌값 반전(occupancyInverted) 런타임 판정(6차 F6-5). 순수 Kotlin.
 *
 * 일부 차(에뮬레이터 포함)는 SDK 착좌값이 거꾸로 온다(VHAL 1=vacant 를 true 로 줌). 달리는 중에는 운전석에 사람이 있으므로,
 * 주행이 [CONFIRM_MS] 이상 이어지는 동안 운전석 원시값이 '비어 있음(false)'으로 읽히면 이 차는 반전이다.
 *  - 한 번만 판정한다. 판정은 저장해 다음 실행부터 쓴다(실행 중에 해석이 바뀌지 않게).
 *  - 속도 신호가 없으면 판정하지 않고 저장값(없으면 기본값)을 쓴다(F10-5: 기어 D 만으로 학습하지 않는다).
 *  - 주행 여부는 호출하는 쪽이 [isLearningSpeed] 로 넘긴다(속도 값이 있고 2 m/s 이상).
 */
class OccupancyInversionDetector(alreadyDecided: Boolean = false) {

    companion object {
        /** 주행이 이만큼 이어진 뒤에 판정한다(순간 신호로 정하지 않음). */
        const val CONFIRM_MS = 3_000L

        /** 학습에 쓰는 속도 하한 2 m/s(F10-5). 속도 값이 있어야 하고, 이보다 느리거나 속도가 null 이면(기어만 D) 학습하지 않는다. */
        const val LEARN_MIN_MPS = 2f

        /** 반전 학습을 해도 되는 '달리는 중': 속도 값이 있고 |속도| ≥ [LEARN_MIN_MPS]. 기어만으로는 학습하지 않는다(release 에서도). */
        fun isLearningSpeed(speedMps: Float?): Boolean = speedMps != null && kotlin.math.abs(speedMps) >= LEARN_MIN_MPS

        /** 유효값: 디버그 수동 지정 → 저장된 판정 → 기본값 순서. */
        fun effective(override: Boolean?, decided: Boolean?, default: Boolean): Boolean = override ?: decided ?: default
    }

    private var decided = alreadyDecided
    private var drivingSinceMs: Long? = null

    /**
     * [driverRaw] 는 운전석 SDK 원시 착좌값(보정 전). 새로 정한 값이 있으면 돌려주고(= 저장 대상), 없으면 null.
     */
    fun observe(nowMs: Long, driving: Boolean, driverRaw: Boolean?): Boolean? {
        if (decided) return null
        if (!driving) { drivingSinceMs = null; return null }
        val since = drivingSinceMs ?: nowMs.also { drivingSinceMs = it }
        if (driverRaw == null || nowMs - since < CONFIRM_MS) return null
        decided = true
        return !driverRaw
    }
}
