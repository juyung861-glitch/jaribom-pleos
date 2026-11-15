package com.jaribom.app.platform

import android.car.Car
import android.car.VehiclePropertyIds
import android.car.hardware.CarPropertyValue
import android.car.drivingstate.CarUxRestrictionsManager
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.jaribom.app.JbLog

/**
 * AAOS android.car(CarPropertyManager)로 읽는 신호들(6차 F6). 쓰기는 없다 — 전부 읽기 권한이다.
 *  - PERF_VEHICLE_SPEED(m/s): 권한 android.car.permission.CAR_SPEED
 *  - GEAR_SELECTION(VehicleGear 상수): 권한 android.car.permission.CAR_POWERTRAIN (F6-2, Pleos 기어 API 대체)
 * 못 얻으면(권한 거부·미지원·서비스 없음) 해당 값을 null 로 알린다. 속도가 null 이면 정차 판정은 기어로, 기어가 null 이면 "모름"으로
 * 떨어진다([com.jaribom.app.core.VehicleMotion], [com.jaribom.app.core.GearMapping]).
 */
internal class CarSignals(private val context: Context, private val sink: Sink) {

    interface Sink {
        /** 차속 m/s. null = 읽을 수 없음 */
        fun onSpeedMps(mps: Float?)
        /** 속도 신호 상태(F10-2): 구독 성공·권한 거부·속성 미지원 */
        fun onSpeedStatus(status: com.jaribom.app.core.SpeedSignal)
        /** GEAR_SELECTION 값(VehicleGear). null = 읽을 수 없음 */
        fun onGear(carGear: Int?)
        /** CarUxRestrictionsManager 의 isRequiresDistractionOptimization(F6-3). null = 매니저 없음·읽을 수 없음 */
        fun onUx(restricted: Boolean?)
        /** 준비됐던 Car 서비스가 죽었다(F6-4). 우리가 stop() 해서 끊긴 경우에는 부르지 않는다. */
        fun onCarServiceDied()
    }

    private var car: Car? = null
    private var props: CarPropertyManager? = null
    private var uxManager: CarUxRestrictionsManager? = null
    private var everReady = false

    private val speedCallback = object : CarPropertyManager.CarPropertyEventCallback {
        override fun onChangeEvent(value: CarPropertyValue<*>) { sink.onSpeedMps((value.value as? Number)?.toFloat()) }
        override fun onErrorEvent(propId: Int, zone: Int) { JbLog.w(TAG, "속도 오류 propId=$propId zone=$zone"); sink.onSpeedMps(null) }
    }

    private val gearCallback = object : CarPropertyManager.CarPropertyEventCallback {
        override fun onChangeEvent(value: CarPropertyValue<*>) {
            val raw = (value.value as? Number)?.toInt()
            JbLog.i(TAG, "기어 이벤트 raw=$raw")
            sink.onGear(raw)
        }
        override fun onErrorEvent(propId: Int, zone: Int) { JbLog.w(TAG, "기어 오류 propId=$propId zone=$zone"); sink.onGear(null) }
    }

    /** Car 서비스에 비동기로 붙는다. 준비되면(ready) 구독을 건다. 여러 번 불러도 한 번만 붙는다. */
    fun start() {
        if (car != null) return
        runCatching {
            car = Car.createCar(context, Handler(Looper.getMainLooper()), Car.CAR_WAIT_TIMEOUT_DO_NOT_WAIT) { c, ready -> onLifecycle(c, ready) }
        }.onFailure { JbLog.w(TAG, "Car 생성 실패: ${it.message}") }
    }

    /**
     * 속성 구독. API 35+ 는 subscribePropertyEvents, 그 밖(Pleos 에뮬레이터 API 34)은 옛 registerCallback 을 쓴다.
     * compileSdk 37 에서 registerCallback 이 deprecated 로 표시되지만 API 34 에는 새 API 가 없어 옛 경로에만 @Suppress 를 둔다.
     */
    private fun subscribe(pm: CarPropertyManager, id: Int, cb: CarPropertyManager.CarPropertyEventCallback) {
        if (Build.VERSION.SDK_INT >= 35) pm.subscribePropertyEvents(id, CarPropertyManager.SENSOR_RATE_NORMAL, cb)
        else @Suppress("DEPRECATION") pm.registerCallback(cb, id, CarPropertyManager.SENSOR_RATE_NORMAL)
    }

    private fun unsubscribe(pm: CarPropertyManager, cb: CarPropertyManager.CarPropertyEventCallback) {
        if (Build.VERSION.SDK_INT >= 35) pm.unsubscribePropertyEvents(cb)
        else @Suppress("DEPRECATION") pm.unregisterCallback(cb)
    }

    fun stop() {
        runCatching { props?.let { unsubscribe(it, speedCallback); unsubscribe(it, gearCallback) } }
        props = null
        runCatching { uxManager?.unregisterListener() }
        uxManager = null
        everReady = false
        val c = car
        car = null          // 먼저 비워 두면 disconnect 로 오는 not-ready 콜백을 사망으로 오해하지 않는다
        runCatching { c?.disconnect() }
    }

    private fun onLifecycle(c: Car, ready: Boolean) {
        // stop() 뒤 새로 만든 Car 가 있으면 예전 Car 의 늦은 콜백은 무시한다(가짜 사망·중복 구독 방지).
        if (car != null && c !== car) return
        if (!ready) {
            props = null; uxManager = null
            JbLog.i(TAG, "Car 서비스 준비 안 됨")
            if (everReady && car != null) { everReady = false; JbLog.w(TAG, "Car 서비스 사망 감지"); sink.onCarServiceDied() }
            return
        }
        everReady = true
        props = c.getCarManager(Car.PROPERTY_SERVICE) as? CarPropertyManager
        val pm = props ?: run { JbLog.w(TAG, "CarPropertyManager 없음 — 속도·기어 신호 없음"); sink.onSpeedMps(null); sink.onGear(null); return }
        registerSpeed(pm)
        registerGear(pm)
        registerUx(c)
    }

    /**
     * 화면 편집 제한용 신호(F6-3, B안). 안전 판정에는 쓰지 않는다. 매니저가 없거나 읽지 못하면 null 을 알린다(제한을 더하지 않음).
     */
    private fun registerUx(c: Car) {
        try {
            val m = c.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as? CarUxRestrictionsManager
            if (m == null) { JbLog.i(TAG, "CarUxRestrictionsManager 없음 — 화면 제한 신호 없음"); sink.onUx(null); return }
            uxManager = m
            m.registerListener { r -> JbLog.i(TAG, "UX 제한 변경: requiresDistractionOptimization=${r.isRequiresDistractionOptimization}"); sink.onUx(r.isRequiresDistractionOptimization) }
            sink.onUx(m.currentCarUxRestrictions?.isRequiresDistractionOptimization)
            JbLog.i(TAG, "CarUxRestrictionsManager 구독 완료")
        } catch (e: Exception) {
            JbLog.w(TAG, "UX 제한 구독 실패: ${e.message}")
            sink.onUx(null)
        }
    }

    private fun registerGear(pm: CarPropertyManager) {
        try {
            val id = VehiclePropertyIds.GEAR_SELECTION
            if (pm.getCarPropertyConfig(id) == null) {
                JbLog.i(TAG, "GEAR_SELECTION 미지원 — 기어 모름")
                sink.onGear(null); return
            }
            subscribe(pm, id, gearCallback)
            pm.getProperty(Int::class.javaObjectType, id, 0)?.let { sink.onGear(it.value) }
            JbLog.i(TAG, "GEAR_SELECTION 구독 완료")
        } catch (e: SecurityException) {
            JbLog.w(TAG, "GEAR_SELECTION 권한 거부됨(CAR_POWERTRAIN): ${e.message}")
            sink.onGear(null)
        } catch (e: Exception) {
            JbLog.w(TAG, "GEAR_SELECTION 읽기 실패: ${e.message}")
            sink.onGear(null)
        }
    }

    /** 놓친 콜백에 대비해 현재값을 한 번 다시 읽는다(게이트웨이의 30초 안전망 폴링에서 부른다). */
    fun refresh() {
        val pm = props ?: return
        runCatching { pm.getProperty(Float::class.javaObjectType, VehiclePropertyIds.PERF_VEHICLE_SPEED, 0)?.let { sink.onSpeedMps(it.value) } }
        runCatching { pm.getProperty(Int::class.javaObjectType, VehiclePropertyIds.GEAR_SELECTION, 0)?.let { sink.onGear(it.value) } }
    }

    /** 속도 권한을 새로 허용받았을 때 구독을 다시 시도한다(F10-2). 이미 구독 중이면 먼저 해제한다. */
    fun retrySpeed() {
        val pm = props ?: return
        runCatching { unsubscribe(pm, speedCallback) }
        registerSpeed(pm)
    }

    private fun registerSpeed(pm: CarPropertyManager) {
        try {
            val id = VehiclePropertyIds.PERF_VEHICLE_SPEED
            if (context.checkSelfPermission("android.car.permission.CAR_SPEED") != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                // 런타임 권한이 아직 없다(거부 또는 요청 전). 구독은 실패하므로 시도하지 않는다.
                JbLog.w(TAG, "PERF_VEHICLE_SPEED 권한 없음(CAR_SPEED 미허용) — 속도 없음(기어로 판정)")
                sink.onSpeedStatus(com.jaribom.app.core.SpeedSignal.PERMISSION_DENIED)
                sink.onSpeedMps(null); return
            }
            if (pm.getCarPropertyConfig(id) == null) {
                JbLog.i(TAG, "PERF_VEHICLE_SPEED 미지원 — 속도 없음(기어로 판정)")
                sink.onSpeedStatus(com.jaribom.app.core.SpeedSignal.UNSUPPORTED)
                sink.onSpeedMps(null); return
            }
            subscribe(pm, id, speedCallback)
            pm.getProperty(Float::class.javaObjectType, id, 0)?.let { sink.onSpeedMps(it.value) }
            sink.onSpeedStatus(com.jaribom.app.core.SpeedSignal.OK)
            JbLog.i(TAG, "PERF_VEHICLE_SPEED 구독 완료")
        } catch (e: SecurityException) {
            JbLog.w(TAG, "PERF_VEHICLE_SPEED 권한 거부됨(CAR_SPEED): ${e.message}")
            sink.onSpeedStatus(com.jaribom.app.core.SpeedSignal.PERMISSION_DENIED)
            sink.onSpeedMps(null)
        } catch (e: Exception) {
            JbLog.w(TAG, "PERF_VEHICLE_SPEED 읽기 실패: ${e.message}")
            sink.onSpeedMps(null)
        }
    }

    private companion object { const val TAG = "CarSignals" }
}
