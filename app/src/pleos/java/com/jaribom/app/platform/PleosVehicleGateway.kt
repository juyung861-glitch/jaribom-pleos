package com.jaribom.app.platform

import android.content.Context
import com.jaribom.app.JbLog
import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.DoorArea
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.area.WindowArea
import ai.pleos.playground.vehicle.constant.area.DoorValue
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.constant.area.WindowValue
import ai.pleos.playground.vehicle.constant.unit.TemperatureUnit
import ai.pleos.playground.vehicle.listener.DoorPositionListener
import ai.pleos.playground.vehicle.listener.HvacSeatTemperatureListener
import ai.pleos.playground.vehicle.listener.HvacSeatVentilationListener
import ai.pleos.playground.vehicle.listener.SeatBeltBuckledStateListener
import ai.pleos.playground.vehicle.listener.SeatOccupancyListener
import ai.pleos.playground.vehicle.listener.WindowLockStateListener
import ai.pleos.playground.vehicle.listener.WindowPositionListener
import com.jaribom.app.BuildConfig
import com.jaribom.app.core.ClimateSupport
import com.jaribom.app.core.ConnectionSupervisor
import com.jaribom.app.core.OccupancyInversionDetector
import com.jaribom.app.core.SeatSignalMode
import com.jaribom.app.core.SeatSignals
import com.jaribom.app.core.VehicleMotion
import com.jaribom.app.data.ItemStore
import com.jaribom.app.core.Gear
import com.jaribom.app.core.GearMapping
import com.jaribom.app.core.LinkWatchdog
import com.jaribom.app.core.MotionTracker
import com.jaribom.app.core.SeatId
import com.jaribom.app.core.SeatState
import com.jaribom.app.core.VehicleCommand
import com.jaribom.app.core.VehicleSnapshot
import com.jaribom.app.vehicle.VehicleGateway
import com.jaribom.app.vehicle.WriteVerifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

/**
 * Pleos Vehicle SDK 2.3.2 어댑터.
 *
 * 메서드·리스너 이름은 Vehicle-2.3.2.aar 의 실제 클래스(javap)로 확인한 것이다.
 *  - 읽기: Seat.getCurrentSeatOccupancy / getCurrentSeatBeltBuckledState / getHvacSeatTemperature / getHvacSeatVentilationState
 *          Door.getCurrentDoorPosition, Window.getCurrentWindowPosition,
 *          HVAC.getHvacTemperatureCurrent(실내 온도)
 *  - 구독: register{SeatOccupancy, SeatBeltBuckledState, HvacSeatTemperature, HvacSeatVentilation, DoorPosition, WindowPosition, GearSelection}
 *  - 창문 잠금(아이 좌석, 뒷좌석만): Window.getCurrentWindowLockState / registerWindowLockState(CAR_WINDOWS),
 *          Window.setWindowLock(CONTROL_CAR_WINDOWS). SDK 2.3.2 소스의 @RequiresPermission 으로 확인. 도어 권한과 무관하다.
 *  - 쓰기: Window.setWindowPosition / setWindowLock, Seat.setHvacSeatTemperature / setHvacSeatVentilation 뿐이다(권한 CONTROL_CAR_WINDOWS·CONTROL_CAR_SEATS).
 *          공조 쓰기(HVAC.setHvacPower/setHvacTemperature, CONTROL_CAR_CLIMATE)는 앱이 쓰지 않아 호출·권한 모두 없다.
 *  - 차속 읽기 API 는 Pleos SDK 에 없다 → AAOS android.car 의 PERF_VEHICLE_SPEED 를 [CarSignals] 로 읽는다(F6-1).
 *    속도가 null 이면 정차 판정은 기어로 떨어진다([com.jaribom.app.core.VehicleMotion]).
 *
 * 설계 메모
 *  - 모든 SDK 결과는 onComplete/onFailed 비동기 → 콜백이 올 때마다 VehicleSnapshot 을 갱신한다.
 *  - 에뮬레이터 착좌값 반전: VHAL 표준(1=vacant, 2=occupied)을 따라 SDK 가 '비어 있음'을 true 로 준다는
 *    보고가 있어 [occupancyInverted] 로 보정한다. Virtual Console 에서 확인 후 이 플래그 하나만 바꾼다.
 *  - 창문 위치는 capability 가 주는 창문별 min~max 를 0~100% 로 정규화한다.
 *  - 상태 변화는 구독 콜백으로 받는다. Cold boot 가 필수(Quick boot 면 VHAL 이 꺼짐)라 콜백이 기본 경로이고,
 *    [POLL_MS] 마다 한 번 전체 값을 다시 읽는 것은 콜백을 놓쳤을 때를 위한 안전망일 뿐이다.
 *    Pleos 성능 기준(주행 중 CPU 5% 미만, 백그라운드 폴링 최소화)에 맞춰 2초 → 30초로 늘렸다.
 *  - 시트 통풍은 차종마다 지원 영역이 다르다(에뮬레이터는 2열 미지원). 연결 직후 capability 로 확인하고,
 *    그래도 "not supported" 오류가 오면 그 영역을 [ventUnsupported] 에 넣어 이후 읽기·구독에서 뺀다(하드코딩하지 않음).
 */
class PleosVehicleGateway(context: Context) : VehicleGateway {

    companion object {
        private const val TAG = "PleosVehicleGateway"
        private const val POLL_MS = 30_000L
        private const val CAPABILITY_TIMEOUT_MS = 2_000L
    }

    private val appContext = context.applicationContext
    /** 사망 뒤에는 새 인스턴스로 바꾼다(F7-2, 예전 인스턴스의 큐·바인딩과 섞이지 않게). 메인 스레드에서만 바꾼다. */
    @Volatile private var vehicle = Vehicle(appContext)
    private val watchdog = LinkWatchdog()
    private var probeJob: Job? = null

    // ── 좌석별 신호 지원 모드(F9-3) ──
    private val _modes = MutableStateFlow<Map<SeatId, SeatSignalMode>>(emptyMap())
    override val seatModes: StateFlow<Map<SeatId, SeatSignalMode>> = _modes
    /** capability 로 확인한 지원 좌석 영역. null = 조회 실패·시간 초과(판별 못 함 → 지원하는 것으로 본다) */
    @Volatile private var capOccupancy: Set<SeatArea>? = null
    @Volatile private var capBelt: Set<SeatArea>? = null
    /** 읽기에서 "not supported" 오류가 온 좌석(통풍과 같은 방식) */
    private val occReadUnsupported: MutableSet<SeatId> = ConcurrentHashMap.newKeySet()
    private val beltReadUnsupported: MutableSet<SeatId> = ConcurrentHashMap.newKeySet()
    /** 첫 탐침이 연속으로 앱 쪽 예외(ERROR)였던 횟수 */
    private var firstErrors = 0

    /** 반전 판정을 새로 저장한 직후의 첫 운행인가(F10-5). 이 프로세스가 시작할 때의 저장 표시를 쓴다. */
    private val freshlyLearnedAtStart: Boolean = ItemStore(context.applicationContext).loadOccupancyFresh()
    @Volatile private var freshTrip = freshlyLearnedAtStart
    override val occupancyFreshlyLearned: Boolean get() = freshTrip
    override fun onTripEnded() { if (freshTrip) { freshTrip = false; store.clearOccupancyFresh() } }

    // ── 착좌 반전(F6-5) ──
    // 유효값 = (debug 수동 지정) → 주행 중 운전석 신호로 한 번 판정해 저장해 둔 값 → 기본값(debug 에뮬레이터 true, release false).
    // 판정은 저장만 하고 다음 실행부터 쓴다(실행 중 해석이 바뀌지 않게).
    private val store = ItemStore(context.applicationContext)
    private val occupancyInverted: Boolean = OccupancyInversionDetector.effective(
        override = if (BuildConfig.DEBUG) store.loadOccupancyOverride() else null,
        decided = store.loadOccupancyInverted(),
        default = BuildConfig.DEBUG,
    )
    private val inversionDetector = OccupancyInversionDetector(alreadyDecided = store.loadOccupancyInverted() != null)
    /** 운전석 SDK 원시 착좌값(보정 전) */
    @Volatile private var driverRaw: Boolean? = null
    @Volatile private var initialized = false
    /** 쓰기를 받아도 되는 상태. 사망 신호가 오면 바로 false(F6-4). */
    @Volatile private var linkUp = false

    private val _snapshot = MutableStateFlow(VehicleSnapshot(timestampMs = System.currentTimeMillis()))
    override val snapshot: StateFlow<VehicleSnapshot> = _snapshot
    override val status = MutableStateFlow("연결 전")

    private val _ux = MutableStateFlow<Boolean?>(null)
    private val _speedSignal = MutableStateFlow(com.jaribom.app.core.SpeedSignal.UNKNOWN)
    override val speedSignal: StateFlow<com.jaribom.app.core.SpeedSignal> = _speedSignal
    override fun retrySpeed() = carSignals.retrySpeed()
    private val _linked = MutableStateFlow(false)
    override val linked: StateFlow<Boolean> = _linked
    override val uxRestricted: StateFlow<Boolean?> = _ux

    /** 창문별 SDK 원시값 범위(min, max). capability 를 못 읽으면 0~100 으로 본다. */
    private val windowRanges = mutableMapOf<SeatId, Pair<Int, Int>>()

    /** 시트 통풍을 지원하지 않는 좌석. SDK 콜백 스레드에서도 넣으므로 동시성 집합을 쓴다. */
    private val ventUnsupported: MutableSet<SeatId> = ConcurrentHashMap.newKeySet()

    // ── 좌석별 열선·통풍 지원(F12-5) ──
    /** 통풍 capability 가 보고한 지원 영역. null = 응답 없음(모름) */
    @Volatile private var capVent: Set<SeatArea>? = null
    /** 열선 읽기에서 "not supported" 오류가 온 좌석 */
    private val heaterUnsupported: MutableSet<SeatId> = ConcurrentHashMap.newKeySet()
    private val _climate = MutableStateFlow<Map<SeatId, ClimateSupport>>(emptyMap())
    override val climateSupport: StateFlow<Map<SeatId, ClimateSupport>> = _climate

    private fun publishClimate() {
        val m = SeatId.passengers.associateWith { id ->
            ClimateSupport(
                heater = if (id in heaterUnsupported) SeatSignals.Support.UNSUPPORTED else SeatSignals.Support.UNKNOWN,
                vent = when {
                    id in ventUnsupported -> SeatSignals.Support.UNSUPPORTED
                    capVent?.contains(seatAreas.getValue(id)) == true -> SeatSignals.Support.SUPPORTED
                    else -> SeatSignals.Support.UNKNOWN
                },
            )
        }
        if (m != _climate.value) JbLog.i(TAG, "열선·통풍 지원: " + m.entries.joinToString { "${it.key.label}=열선 ${it.value.heater}/통풍 ${it.value.vent}" })
        _climate.value = m
    }

    /** 열선 읽기 실패: "not supported" 면 그 좌석은 열선이 없는 것으로 본다(F12-5, 통풍과 같은 판정). */
    private fun onHeaterFailed(id: SeatId, what: String, e: Exception) {
        val unsupported = generateSequence<Throwable>(e) { it.cause }.any { it.message?.contains("not supported") == true }
        if (unsupported && heaterUnsupported.add(id)) { JbLog.i(TAG, "열선 미지원 좌석: ${id.name} (${e.message})"); publishClimate() }
        else if (!unsupported) warn(what, e)
    }

    private val seatAreas: Map<SeatId, SeatArea> = mapOf(
        SeatId.ROW_1_LEFT to SeatArea(SeatValue.ROW_1_LEFT),
        SeatId.ROW_1_RIGHT to SeatArea(SeatValue.ROW_1_RIGHT),
        SeatId.ROW_2_LEFT to SeatArea(SeatValue.ROW_2_LEFT),
        SeatId.ROW_2_RIGHT to SeatArea(SeatValue.ROW_2_RIGHT),
    )
    private val doorAreas: Map<SeatId, DoorArea> = mapOf(
        SeatId.ROW_1_LEFT to DoorArea(DoorValue.ROW_1_LEFT),
        SeatId.ROW_1_RIGHT to DoorArea(DoorValue.ROW_1_RIGHT),
        SeatId.ROW_2_LEFT to DoorArea(DoorValue.ROW_2_LEFT),
        SeatId.ROW_2_RIGHT to DoorArea(DoorValue.ROW_2_RIGHT),
    )
    private val windowAreas: Map<SeatId, WindowArea> = mapOf(
        SeatId.ROW_1_LEFT to WindowArea(WindowValue.ROW_1_LEFT),
        SeatId.ROW_1_RIGHT to WindowArea(WindowValue.ROW_1_RIGHT),
        SeatId.ROW_2_LEFT to WindowArea(WindowValue.ROW_2_LEFT),
        SeatId.ROW_2_RIGHT to WindowArea(WindowValue.ROW_2_RIGHT),
    )
    /** 전체 공조·실내 온도는 운전석 존을 대표값으로 쓴다(에뮬레이터에 2열 독립 공조 없음). */
    private val hvacArea = seatAreas.getValue(SeatId.ROW_1_LEFT)

    // 등록한 리스너를 같은 객체로 해제해야 하므로 보관
    private val occupancyListeners = mutableMapOf<SeatId, SeatOccupancyListener>()
    private val beltListeners = mutableMapOf<SeatId, SeatBeltBuckledStateListener>()
    private val heaterListeners = mutableMapOf<SeatId, HvacSeatTemperatureListener>()
    private val ventListeners = mutableMapOf<SeatId, HvacSeatVentilationListener>()
    private val doorListeners = mutableMapOf<SeatId, DoorPositionListener>()
    private val windowListeners = mutableMapOf<SeatId, WindowPositionListener>()
    private val lockListeners = mutableMapOf<SeatId, WindowLockStateListener>()
    /** 창문 잠금을 읽는 좌석(뒷좌석 창문 스위치 잠금). 앞좌석은 잠금이 없다. */
    private val lockSeats = setOf(SeatId.ROW_2_LEFT, SeatId.ROW_2_RIGHT)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var pollJob: Job? = null

    /** 차속 추적기(느려진 시각 기억). 신호가 올 때마다 스냅샷에 반영한다. */
    private val motion = MotionTracker()
    private val carSignals = CarSignals(context.applicationContext, object : CarSignals.Sink {
        override fun onSpeedMps(mps: Float?) {
            val t = now()
            val slow = motion.update(mps, t)
            _snapshot.update { it.copy(speedMps = mps, slowSinceMs = slow, timestampMs = t) }
            checkInversion()
        }
        override fun onSpeedStatus(status: com.jaribom.app.core.SpeedSignal) { _speedSignal.value = status }
        override fun onUx(restricted: Boolean?) { _ux.value = restricted }
        override fun onCarServiceDied() = onServiceDied()
        override fun onGear(carGear: Int?) {
            // P·R·N·D 외의 값·null 은 "모름"(정차로 보지 않는다). Pleos SDK 의 deprecated 기어 API 는 더 쓰지 않는다(F6-2).
            _snapshot.update { it.copy(gear = GearMapping.fromCar(carGear), timestampMs = now()) }
            checkInversion()
        }
    })

    /** 연결 감독(F6-4): 실패하면 1·2·4·8·16·30초 간격으로 다시, 서비스가 죽으면 구독을 해제하고 다시 붙는다. */
    private val supervisor = ConnectionSupervisor(
        connectOnce = { connectOnce() },
        teardown = { teardown() },
        onRetry = { n, waitMs ->
            status.value = "차량 연결 재시도 ${n}회째 — ${waitMs / 1000}초 뒤"
            JbLog.w(TAG, "차량 연결 재시도 ${n}회째: ${waitMs / 1000}초 뒤")
        },
    )
    private var reconnectJob: Job? = null

    override suspend fun connect() = withContext(Dispatchers.Main) { supervisor.connectWithRetry() }

    /**
     * 한 번 붙어 본다. initialize() 는 bind 실패에도 예외를 던지지 않으므로(SDK 2.3.2) 탐침 읽기 1번이 성공해야 연결된 것으로 본다.
     * 탐침이 실패하면 같은 인스턴스를 그대로 두고 다음 백오프 때 다시 탐침만 한다(새 인스턴스를 만들면 예전 bind 재시도가 새어 남는다).
     * 성공하면 현재값을 모두 다시 읽고(스냅샷 새로 채움) 구독을 건다.
     */
    private suspend fun connectOnce(): Boolean {
        if (!initialized) {
            try {
                vehicle.initialize()
                initialized = true
            } catch (e: Exception) {
                status.value = "Vehicle Service 없음: ${e.message}"
                JbLog.e(TAG, "initialize failed", e)
                return false
            }
        }
        val first = probe(LinkWatchdog.FIRST_PROBE_TIMEOUT_MS)
        firstErrors = if (first == LinkWatchdog.ProbeResult.ERROR) firstErrors + 1 else 0
        when (LinkWatchdog.nextStep(first, firstErrors)) {
            LinkWatchdog.ConnectStep.CONNECTED -> {
                if (first == LinkWatchdog.ProbeResult.ERROR) JbLog.w(TAG, "첫 탐침이 ${firstErrors}번 연속 앱 쪽 예외 — 판정 불가로 보고 연결로 진행")
                firstErrors = 0
            }
            LinkWatchdog.ConnectStep.REPROBE -> {
                status.value = "Vehicle Service 응답 없음"
                JbLog.w(TAG, "연결 확인 탐침 실패($first) — 같은 인스턴스로 다시 탐침")
                return false
            }
            LinkWatchdog.ConnectStep.REINITIALIZE -> {
                // SDK 가 DISCONNECT 에 멈춤(bind 재시도 소진). 같은 인스턴스로 initialize() 를 다시 부르면 bind 만 다시 한다.
                initialized = false
                status.value = "Vehicle Service 없음"
                JbLog.w(TAG, "연결 확인 탐침 실패($first) — 다음 시도에 initialize 다시")
                return false
            }
        }
        status.value = "Vehicle SDK 연결됨"
        detectSeatModes()           // F9-3: 연결(재연결)마다 좌석별 착좌·벨트 지원 여부를 다시 판별한다
        readWindowRanges()
        readVentCapability()
        readAll()          // 퀵부트 때 구독 콜백이 안 오는 문제 대비: 현재값을 명시적으로 읽는다
        subscribeAll()
        carSignals.start()
        pollJob = scope.launch {
            while (isActive) { delay(POLL_MS); readAll(); checkInversion() }
        }
        watchdog.reset()
        probeJob = scope.launch {
            while (isActive) {
                delay(LinkWatchdog.PROBE_INTERVAL_MS)
                val r = probe(LinkWatchdog.PROBE_TIMEOUT_MS)
                if (r != LinkWatchdog.ProbeResult.OK && r != LinkWatchdog.ProbeResult.ANSWERED) JbLog.w(TAG, "탐침 실패: $r")
                if (watchdog.record(r)) {
                    JbLog.w(TAG, "Pleos 차량 서비스 사망 감지: 탐침 연속 ${LinkWatchdog.FAILS_TO_DEAD}번 실패")
                    onServiceDied()
                }
            }
        }
        linkUp = true
        _linked.value = true
        JbLog.i(TAG, "차량 연결 완료(구독 등록·현재값 다시 읽음)")
        return true
    }

    /**
     * 탐침(F7-2): 운전석 착좌를 한 번 읽는다. 시간 안에 콜백이 없으면 TIMEOUT, SDK 의 연결 실패 오류면 DISCONNECTED.
     * 값이 null 이거나 다른 오류로 답하면 서비스는 살아 있다(ANSWERED). 늦게 온 콜백은 취소된 continuation 이라 버려진다.
     */
    private suspend fun probe(timeoutMs: Long): LinkWatchdog.ProbeResult {
        val v = vehicle
        return withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                fun done(r: LinkWatchdog.ProbeResult) { if (cont.isActive) cont.resume(r) }
                try {
                    v.getSeat().getCurrentSeatOccupancy(
                        seatAreas.getValue(SeatId.ROW_1_LEFT),
                        { raw -> done(if (raw != null) LinkWatchdog.ProbeResult.OK else LinkWatchdog.ProbeResult.ANSWERED) },
                        { e ->
                            // 권한 오류는 앱 쪽 검사라 서비스 생사를 알 수 없다(탐침 불가). 생존으로 세되 로그로 남긴다.
                            if (e.message?.contains("ermission") == true) JbLog.w(TAG, "탐침 불가(권한): ${e.message}")
                            done(LinkWatchdog.classifyError(e.message))
                        },
                    )
                } catch (e: Exception) {
                    // SDK 는 바인더 예외를 안에서 잡아 큐에 넣으므로 여기 오는 동기 예외는 앱 쪽 문제(영역 조회·권한 등)일 가능성이 크다.
                    // 바인더 사망으로 보지 않고(initialize 를 다시 부르지 않음) 사망 카운트에도 넣지 않는다(LinkWatchdog.record).
                    JbLog.w(TAG, "탐침 동기 예외: ${e.message}")
                    done(LinkWatchdog.ProbeResult.ERROR)
                }
            }
        } ?: LinkWatchdog.ProbeResult.TIMEOUT
    }

    override suspend fun disconnect() = withContext(Dispatchers.Main) {
        reconnectJob?.cancel(); reconnectJob = null
        supervisor.stop()
    }

    /** Car 서비스 사망(F6-4). 쓰기를 막고, 구독을 해제한 뒤 다시 붙는다. 신호가 겹쳐 와도 재연결은 하나다. */
    private fun onServiceDied() {
        if (!linkUp) return
        linkUp = false
        _linked.value = false
        JbLog.w(TAG, "차량 서비스 사망 — 쓰기 중단, 구독 해제 후 재연결")
        status.value = "차량 서비스 끊김 — 다시 연결 중"
        reconnectJob = scope.launch { supervisor.onServiceDied() }
    }

    private suspend fun teardown() = withContext(Dispatchers.Main) {
        linkUp = false
        _ux.value = null
        // 끊긴 동안 마지막 기어·속도로 음성 허용·운행 종료를 판정하지 않게 '모름'으로 비운다(다시 붙으면 새로 읽는다).
        _snapshot.update { it.copy(gear = Gear.UNKNOWN, speedMps = null, slowSinceMs = null, timestampMs = now()) }
        // 끊긴 동안의 착좌·문 값은 [linked] = false 로 알려 RoutineController 가 엔진에 넣지 않는다(F7-2).
        // 값을 null 로 비우면 상태머신이 '빈 좌석·문 닫힘'으로 읽어 가짜 하차가 확정되므로 비우지 않는다.
        _linked.value = false
        probeJob?.cancel(); probeJob = null
        pollJob?.cancel(); pollJob = null
        carSignals.stop()
        if (!initialized) return@withContext
        // 예전 인스턴스는 지역 변수로 잡아 해제한다. 서비스가 멈춰(hang) 있으면 바인더 호출이 막히므로 메인이 아닌 IO 에서,
        // 시간 제한을 두고, 끝나기를 기다리지 않는다. 다음 연결은 새 인스턴스로 한다(큐에 남은 예전 호출과 섞이지 않게).
        val old = vehicle
        val reg = Registrations(occupancyListeners.toMap(), beltListeners.toMap(), heaterListeners.toMap(),
            ventListeners.filterKeys { it !in ventUnsupported }, doorListeners.toMap(), windowListeners.toMap(), lockListeners.toMap())
        vehicle = Vehicle(appContext)
        // 동기 바인더 호출이라 시간 제한으로 끊을 수 없다. 메인을 막지 않게 IO 에서 하고 기다리지 않는다(서비스가 멈춰 있으면 그 스레드는 풀릴 때까지 막힌다).
        CoroutineScope(Dispatchers.IO).launch { releaseOld(old, reg) }
        occupancyListeners.clear(); beltListeners.clear(); heaterListeners.clear(); ventListeners.clear(); ventUnsupported.clear()
        occReadUnsupported.clear(); beltReadUnsupported.clear()      // 재연결 때 다시 판별한다(일시 오류가 굳지 않게)
        doorListeners.clear(); windowListeners.clear(); lockListeners.clear()
        initialized = false
        status.value = "연결 해제"
    }

    private class Registrations(
        val occ: Map<SeatId, SeatOccupancyListener>, val belt: Map<SeatId, SeatBeltBuckledStateListener>,
        val heater: Map<SeatId, HvacSeatTemperatureListener>, val vent: Map<SeatId, HvacSeatVentilationListener>,
        val door: Map<SeatId, DoorPositionListener>, val window: Map<SeatId, WindowPositionListener>, val lock: Map<SeatId, WindowLockStateListener>,
    )

    private fun releaseOld(old: Vehicle, r: Registrations) {
        runCatching {
            val seat = old.getSeat()
            r.occ.forEach { (id, l) -> seat.unregisterSeatOccupancy(seatAreas.getValue(id), l) }
            r.belt.forEach { (id, l) -> seat.unregisterSeatBeltBuckledState(seatAreas.getValue(id), l) }
            r.heater.forEach { (id, l) -> seat.unregisterHvacSeatTemperature(seatAreas.getValue(id), l) }
            r.vent.forEach { (id, l) -> seat.unregisterHvacSeatVentilation(seatAreas.getValue(id), l) }
            val door = old.getDoor()
            r.door.forEach { (id, l) -> door.unregisterDoorPosition(doorAreas.getValue(id), l) }
            val window = old.getWindow()
            r.window.forEach { (id, l) -> window.unregisterWindowPosition(windowAreas.getValue(id), l) }
            r.lock.forEach { (id, l) -> window.unregisterWindowLockState(windowAreas.getValue(id), l) }
        }.onFailure { JbLog.w(TAG, "unregister", it) }
        runCatching { old.release() }.onFailure { JbLog.w(TAG, "release", it) }
    }

    // ── 읽기 ─────────────────────────────────────────────────
    private fun readWindowRanges() {
        runCatching {
            // 2.3.2: 옛 콜백(bounds 만 받는 형태)은 deprecated → CapabilityConfig 를 함께 받는 오버로드를 쓴다.
            vehicle.getWindow().checkWindowPositionCapabilities(
                { _, bounds ->
                    for ((id, area) in windowAreas) {
                        bounds[area]?.let { b -> windowRanges[id] = b.minValue to b.maxValue }
                    }
                },
                { e -> JbLog.w(TAG, "window capability ${e.message}") },
            )
        }.onFailure { JbLog.w(TAG, "window capability", it) }
    }

    /**
     * 통풍 지원 영역을 capability 로 확인한다. 응답이 없거나(타임아웃·오류) 빈 목록이면 '모름'으로 두고,
     * 이후 첫 "not supported" 오류로 판단한다([onVentFailed]).
     */
    private suspend fun readVentCapability() {
        val supported: Set<SeatArea>? = withTimeoutOrNull(CAPABILITY_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                runCatching {
                    vehicle.getSeat().checkSeatVentilationCapabilities(
                        { _, bounds -> if (cont.isActive) cont.resume(bounds.keys) },
                        { e -> JbLog.w(TAG, "vent capability ${e.message}"); if (cont.isActive) cont.resume(null) },
                    )
                }.onFailure { JbLog.w(TAG, "vent capability", it); if (cont.isActive) cont.resume(null) }
            }
        }
        if (supported.isNullOrEmpty()) {
            JbLog.i(TAG, "통풍 capability 응답 없음(${CAPABILITY_TIMEOUT_MS}ms) → 첫 'not supported' 오류로 판단")
            publishClimate()
            return
        }
        capVent = supported
        val missing = seatAreas.filterValues { it !in supported }.keys
        if (missing.isNotEmpty()) {
            ventUnsupported += missing
            JbLog.i(TAG, "통풍 미지원 좌석(capability): ${missing.joinToString { it.name }} → 읽기·구독에서 제외")
        }
        publishClimate()
    }

    /**
     * 좌석별 착좌·벨트 지원 여부를 capability 로 판별한다(F9-3). 영역 목록에 있으면 지원, 목록은 왔는데 없으면 미지원,
     * 조회 실패·시간 초과는 판별 못 함(UNKNOWN → 지원으로 봄). "지원하지만 값이 null"은 미지원이 아니다.
     * debug 빌드에서는 좌석별 "착좌 미지원"·"벨트 미지원" 강제값을 합친다(에뮬레이터 확인용).
     */
    private suspend fun detectSeatModes() {
        // 두 조회를 동시에 보내 시간 초과여도 연결이 최대 2초만 늘어나게 한다.
        coroutineScope {
            val occ = async { supportedAreas("착좌") { ok, fail -> vehicle.getSeat().checkSeatOccupancyCapabilities({ _, a -> ok(a) }, { e -> fail(e) }) } }
            val belt = async { supportedAreas("벨트") { ok, fail -> vehicle.getSeat().checkSeatBeltBuckledCapabilities({ _, a -> ok(a) }, { e -> fail(e) }) } }
            capOccupancy = occ.await()
            capBelt = belt.await()
        }
        publishSeatModes()
    }

    private suspend fun supportedAreas(what: String, check: (ok: (List<SeatArea>) -> Unit, fail: (Exception) -> Unit) -> Unit): Set<SeatArea>? =
        withTimeoutOrNull(CAPABILITY_TIMEOUT_MS) {
            suspendCancellableCoroutine<Set<SeatArea>?> { cont ->
                try {
                    check({ list -> if (cont.isActive) cont.resume(list.toSet()) }, { e ->
                        JbLog.i(TAG, "$what capability 조회 실패 — 판별 못 함(지원으로 봄): ${e.message}")
                        if (cont.isActive) cont.resume(null)
                    })
                } catch (e: Exception) {
                    JbLog.w(TAG, "$what capability 예외", e)
                    if (cont.isActive) cont.resume(null)
                }
            }
        }

    private fun publishSeatModes() {
        val forced = if (BuildConfig.DEBUG) store.loadSignalOverrides() else ItemStore.SignalOverrides()
        fun support(id: SeatId, cap: Set<SeatArea>?, forcedOff: Set<SeatId>, readOff: Set<SeatId> = emptySet()): SeatSignals.Support = when {
            id in forcedOff || id in readOff -> SeatSignals.Support.UNSUPPORTED
            cap == null -> SeatSignals.Support.UNKNOWN
            seatAreas.getValue(id) in cap -> SeatSignals.Support.SUPPORTED
            else -> SeatSignals.Support.UNSUPPORTED
        }
        val modes = SeatId.passengers.associateWith { id ->
            SeatSignalMode.of(SeatSignals(support(id, capOccupancy, forced.occupancyOff, occReadUnsupported), support(id, capBelt, forced.beltOff, beltReadUnsupported)))
        }
        if (modes != _modes.value) JbLog.i(TAG, "좌석 신호 모드: " + modes.entries.joinToString { "${it.key.label}=${it.value.name}" })
        _modes.value = modes
    }

    /** 통풍 읽기·구독 실패 처리. 미지원 영역이면 한 번만 기록하고 이후 대상에서 뺀다. */
    private fun onVentFailed(id: SeatId, what: String, e: Exception) {
        // SDK 는 IllegalArgumentException("area ID: 0x10 not supported for property ID: …")을 다른 예외로 감싸 넘긴다.
        // → 타입이 아니라 원인 체인의 메시지로 판정한다.
        val unsupported = generateSequence<Throwable>(e) { it.cause }.any { it.message?.contains("not supported") == true }
        if (unsupported) {
            if (ventUnsupported.add(id)) { JbLog.i(TAG, "통풍 미지원 좌석: ${id.name} → 읽기·구독에서 제외 (${e.message})"); publishClimate() }
        } else {
            warn(what, e)
        }
    }

    /** 착좌·벨트·문·창문·열선·통풍·기어·실내 온도를 한 번 모두 읽는다. */
    private fun readAll() {
        if (!initialized) return
        val seat = vehicle.getSeat()
        val door = vehicle.getDoor()
        val window = vehicle.getWindow()
        for (id in SeatId.entries) {
            val sa = seatAreas.getValue(id)
            safe("occupancy $id") {
                seat.getCurrentSeatOccupancy(sa, { v -> onOccupancyRaw(id, v) }, { e -> onOccupancyReadFailed(id, e) })
            }
            safe("belt $id") {
                seat.getCurrentSeatBeltBuckledState(sa, { v -> updateSeat(id) { it.copy(beltBuckled = v) } }, { e -> onBeltReadFailed(id, e) })
            }
            safe("heater $id") {
                seat.getHvacSeatTemperature(sa, { v -> updateSeat(id) { it.copy(heaterLevel = v) } }, { e -> onHeaterFailed(id, "heater $id", e) })
            }
            if (id !in ventUnsupported) safe("vent $id") {
                seat.getHvacSeatVentilationState(sa, { v -> updateSeat(id) { it.copy(ventilationLevel = v) } }, { e -> onVentFailed(id, "vent $id", e) })
            }
            safe("door $id") {
                door.getCurrentDoorPosition(doorAreas.getValue(id), { pos -> updateSeat(id) { it.copy(doorOpen = isDoorOpen(pos)) } }, { e -> warn("door $id", e) })
            }
            safe("window $id") {
                window.getCurrentWindowPosition(windowAreas.getValue(id), { raw -> updateSeat(id) { it.copy(windowOpenPercent = raw?.let { r -> normalizeWindow(id, r) }) } }, { e -> warn("window $id", e) })
            }
            if (id in lockSeats) safe("window lock $id") {
                window.getCurrentWindowLockState(windowAreas.getValue(id), { v -> updateSeat(id) { it.copy(windowLocked = v) } }, { e -> warn("window lock $id", e) })
            }
        }
        carSignals.refresh()    // 속도·기어는 android.car 에서 읽는다(F6)
        safe("cabin temp") {
            vehicle.getHVAC().getHvacTemperatureCurrent(
                hvacArea, TemperatureUnit.CELSIUS,
                { v -> _snapshot.update { it.copy(hvac = it.hvac.copy(cabinTempC = v), timestampMs = now()) } },
                { e -> warn("cabin temp", e) },
            )
        }
    }

    // ── 구독 ─────────────────────────────────────────────────
    private fun subscribeAll() {
        val seat = vehicle.getSeat()
        val door = vehicle.getDoor()
        val window = vehicle.getWindow()
        for (id in SeatId.entries) {
            val sa = seatAreas.getValue(id)

            val occ = object : SeatOccupancyListener {
                override fun onSeatOccupancyUpdated(occupancy: Boolean?) { onOccupancyRaw(id, occupancy) }
                override fun onFailed(e: Exception) { warn("occupancy listener $id", e) }
            }
            safe("register occupancy $id") { seat.registerSeatOccupancy(sa, occ); occupancyListeners[id] = occ }

            val belt = object : SeatBeltBuckledStateListener {
                override fun onSeatBeltBuckledStateUpdated(state: Boolean?) { updateSeat(id) { it.copy(beltBuckled = state) } }
                override fun onFailed(e: Exception) { warn("belt listener $id", e) }
            }
            safe("register belt $id") { seat.registerSeatBeltBuckledState(sa, belt); beltListeners[id] = belt }

            val heater = object : HvacSeatTemperatureListener {
                override fun onHvacSeatTemperatureUpdated(temperature: Int?) { updateSeat(id) { it.copy(heaterLevel = temperature) } }
                override fun onFailed(e: Exception) { warn("heater listener $id", e) }
            }
            safe("register heater $id") { seat.registerHvacSeatTemperature(sa, heater); heaterListeners[id] = heater }

            if (id !in ventUnsupported) {
                val vent = object : HvacSeatVentilationListener {
                    override fun onHvacSeatVentilationUpdated(ventilation: Int?) { updateSeat(id) { it.copy(ventilationLevel = ventilation) } }
                    override fun onFailed(e: Exception) { onVentFailed(id, "vent listener $id", e) }
                }
                safe("register vent $id") { seat.registerHvacSeatVentilation(sa, vent); ventListeners[id] = vent }
            }

            val dl = object : DoorPositionListener {
                override fun onPositionUpdated(pos: Int?) { updateSeat(id) { it.copy(doorOpen = isDoorOpen(pos)) } }
                override fun onFailed(e: Exception) { warn("door listener $id", e) }
            }
            safe("register door $id") { door.registerDoorPosition(doorAreas.getValue(id), dl); doorListeners[id] = dl }

            val wl = object : WindowPositionListener {
                override fun onWindowPositionUpdated(position: Int?) { updateSeat(id) { it.copy(windowOpenPercent = position?.let { r -> normalizeWindow(id, r) }) } }
                override fun onFailed(e: Exception) { warn("window listener $id", e) }
            }
            safe("register window $id") { window.registerWindowPosition(windowAreas.getValue(id), wl); windowListeners[id] = wl }

            if (id in lockSeats) {
                val ll = object : WindowLockStateListener {
                    override fun onWindowLockStateUpdated(state: Boolean?) { updateSeat(id) { it.copy(windowLocked = state) } }
                    override fun onFailed(e: Exception) { warn("window lock listener $id", e) }
                }
                safe("register window lock $id") { window.registerWindowLockState(windowAreas.getValue(id), ll); lockListeners[id] = ll }
            }
        }
    }

    // ── 쓰기 ─────────────────────────────────────────────────
    /**
     * 쓰기 → 확인(4차 심사 A3, 5차 심사 F2). onComplete 만 믿지 않는다. 규칙은 [WriteVerifier] 한 곳에 있다.
     *  - 응답 시간 초과여도 그 값을 다시 읽어 실제 값으로 판정한다(늦은 성공은 성공).
     *  - 창문은 5초, 열선·통풍·잠금은 1.5초 안에 구독값이 목표에 닿아야 성공이다.
     *  운전자 보고는 이 확인 결과로만 만든다(RoutineController → CabinRoutineEngine).
     */
    override suspend fun execute(cmd: VehicleCommand): Result<Unit> = withContext(Dispatchers.Main) {
        if (!linkUp) return@withContext Result.failure(IllegalStateException("연결 없음(재연결 중)"))
        WriteVerifier.run(cmd, _snapshot, write = { write(cmd) }, readBack = { readBack(cmd) }).also { r ->
            if (r.isSuccess) JbLog.i(TAG, "쓰기 확인 OK: $cmd") else JbLog.w(TAG, "쓰기 확인 실패: $cmd — ${r.exceptionOrNull()?.message}")
        }
    }

    /**
     * 움직이는 창문을 지금 위치에서 멈춘다(F9-1). 지금 위치를 새로 읽어 그 위치를 목표로 다시 쓴다(SDK 에 별도 정지 API 가 있으면 그것으로 바꾼다).
     * 정지는 확인 대기 없이 쓰기 응답까지만 본다 — 멈추는 것이 급하다.
     */
    override suspend fun stopWindow(seat: SeatId): Result<Unit> = withContext(Dispatchers.Main) {
        if (!linkUp) return@withContext Result.failure(IllegalStateException("연결 없음(재연결 중)"))
        val fresh = withTimeoutOrNull(1_500L) {
            suspendCancellableCoroutine<Int?> { cont ->
                try {
                    vehicle.getWindow().getCurrentWindowPosition(
                        windowAreas.getValue(seat),
                        { raw -> if (cont.isActive) cont.resume(raw?.let { normalizeWindow(seat, it) }) },
                        { _ -> if (cont.isActive) cont.resume(null) },
                    )
                } catch (e: Exception) { if (cont.isActive) cont.resume(null) }
            }
        }
        // 위치를 새로 읽지 못하면(시간 초과는 SDK 가 CONNECTING 이라 호출이 큐에 쌓이는 상황일 수 있다) 쓰지 않는다:
        // 오래된 위치를 큐에 남겨 두면 재연결 때 뒤늄게 창문이 그리로 움직인다.
        val pct = fresh ?: return@withContext Result.failure(IllegalStateException("창문 위치를 읽지 못해 정지를 쓰지 않음"))
        JbLog.i(TAG, "창문 정지 쓰기: ${seat.label} → ${pct}%")
        val first = withTimeoutOrNull(WriteVerifier.WRITE_TIMEOUT_MS) { write(VehicleCommand.SetWindow(seat, pct)) }
            ?: return@withContext Result.failure(IllegalStateException("정지 쓰기 응답 없음"))
        if (first.isFailure) return@withContext first
        // 정지가 실제로 먹었는지 0.5초 뒤 한 번 확인한다. 계속 변하면 한 번 더 정지하고 경고를 남긴다(마지막 목표값만 돌려주는 차량 대비).
        delay(500)
        val after = withTimeoutOrNull(1_500L) {
            suspendCancellableCoroutine<Int?> { cont ->
                try {
                    vehicle.getWindow().getCurrentWindowPosition(
                        windowAreas.getValue(seat),
                        { raw -> if (cont.isActive) cont.resume(raw?.let { normalizeWindow(seat, it) }) },
                        { _ -> if (cont.isActive) cont.resume(null) },
                    )
                } catch (e: Exception) { if (cont.isActive) cont.resume(null) }
            }
        }
        if (after != null && kotlin.math.abs(after - pct) > 2) {
            JbLog.w(TAG, "창문 정지 후에도 위치가 변함(${pct}%→${after}%) — 한 번 더 정지")
            return@withContext withTimeoutOrNull(WriteVerifier.WRITE_TIMEOUT_MS) { write(VehicleCommand.SetWindow(seat, after)) }
                ?: Result.failure(IllegalStateException("정지 재시도 응답 없음"))
        }
        first
    }

    /** 확인용으로 그 속성 하나만 한 번 더 읽는다(구독을 놓친 경우 대비). 값은 구독과 같은 경로로 스냅샷에 들어간다. */
    private fun readBack(cmd: VehicleCommand) {
        val id = cmd.seat
        when (cmd) {
            is VehicleCommand.SetWindow -> safe("window $id") {
                vehicle.getWindow().getCurrentWindowPosition(windowAreas.getValue(id), { raw -> updateSeat(id) { it.copy(windowOpenPercent = raw?.let { r -> normalizeWindow(id, r) }) } }, { e -> warn("window $id", e) })
            }
            is VehicleCommand.SetSeatHeater -> safe("heater $id") {
                vehicle.getSeat().getHvacSeatTemperature(seatAreas.getValue(id), { v -> updateSeat(id) { it.copy(heaterLevel = v) } }, { e -> warn("heater $id", e) })
            }
            is VehicleCommand.SetSeatVentilation -> safe("vent $id") {
                vehicle.getSeat().getHvacSeatVentilationState(seatAreas.getValue(id), { v -> updateSeat(id) { it.copy(ventilationLevel = v) } }, { e -> warn("vent $id", e) })
            }
            is VehicleCommand.SetWindowLock -> safe("window lock $id") {
                vehicle.getWindow().getCurrentWindowLockState(windowAreas.getValue(id), { v -> updateSeat(id) { it.copy(windowLocked = v) } }, { e -> warn("window lock $id", e) })
            }
        }
    }

    private suspend fun write(cmd: VehicleCommand): Result<Unit> =
        suspendCancellableCoroutine { cont ->
            fun ok() { if (cont.isActive) cont.resume(Result.success(Unit)) }
            fun fail(e: Exception) { if (cont.isActive) cont.resume(Result.failure(e)) }
            try {
                when (cmd) {
                    is VehicleCommand.SetWindow -> vehicle.getWindow().setWindowPosition(
                        windowAreas.getValue(cmd.seat), denormalizeWindow(cmd.seat, cmd.openPercent), { _, _ -> ok() }, { e -> fail(e) },
                    )
                    is VehicleCommand.SetSeatHeater -> vehicle.getSeat().setHvacSeatTemperature(
                        seatAreas.getValue(cmd.seat), cmd.level, { _, _ -> ok() }, { e -> fail(e) },
                    )
                    is VehicleCommand.SetSeatVentilation -> vehicle.getSeat().setHvacSeatVentilation(
                        seatAreas.getValue(cmd.seat), cmd.level, { _, _ -> ok() }, { e -> fail(e) },
                    )
                    is VehicleCommand.SetWindowLock -> vehicle.getWindow().setWindowLock(
                        windowAreas.getValue(cmd.seat), cmd.locked, { _, _ -> ok() }, { e -> fail(e) },
                    )
                }
            } catch (e: Exception) {
                fail(e)
            }
        }

    // ── 변환 유틸 ─────────────────────────────────────────────
    /** 착좌 읽기 실패. "not supported" 면 그 좌석 착좌는 미지원으로 기록하고 모드를 다시 낸다(F9-3, 통풍과 같은 방식). */
    private fun onOccupancyReadFailed(id: SeatId, e: Exception) {
        val unsupported = generateSequence<Throwable>(e) { it.cause }.any { it.message?.contains("not supported") == true }
        if (unsupported && !id.isDriver) {
            if (occReadUnsupported.add(id)) { JbLog.i(TAG, "착좌 미지원 좌석(읽기 오류): ${id.name} (${e.message})"); publishSeatModes() }
        } else warn("occupancy $id", e)
    }

    /** 벨트 읽기 실패. "not supported" 면 그 좌석 벨트는 미지원으로 기록하고 모드를 다시 낸다(F9-3 리뷰: 벨트도 없으면 NONE 으로). */
    private fun onBeltReadFailed(id: SeatId, e: Exception) {
        val unsupported = generateSequence<Throwable>(e) { it.cause }.any { it.message?.contains("not supported") == true }
        if (unsupported && !id.isDriver) {
            if (beltReadUnsupported.add(id)) { JbLog.i(TAG, "벨트 미지원 좌석(읽기 오류): ${id.name} (${e.message})"); publishSeatModes() }
        } else warn("belt $id", e)
    }

    private fun onOccupancyRaw(id: SeatId, raw: Boolean?) {
        updateSeat(id) { it.copy(occupied = normalizeOccupancy(raw)) }
        if (id == SeatId.ROW_1_LEFT) { driverRaw = raw; checkInversion() }
    }

    /**
     * 주행 중 운전석 신호로 반전 여부를 한 번 판정해 저장한다(다음 실행부터 적용).
     * debug(에뮬레이터)에서는 속도 신호 없이 기어 D 만으로 '반전 아님'을 저장하지 않는다 — 콘솔 운전석은 기본이 빈 좌석이라 오판하기 쉽다.
     * 시연 때는 콘솔에서 운전석 착좌를 켠 뒤 D 로 바꾼다.
     */
    private fun checkInversion() {
        val s = _snapshot.value
        // F10-5: 속도 값이 있고 2 m/s 이상이 3초 이어질 때만 학습한다. 속도가 null 이면(기어만 D) debug·release 모두 학습하지 않는다.
        val v = inversionDetector.observe(now(), OccupancyInversionDetector.isLearningSpeed(s.speedMps), driverRaw) ?: return
        store.saveOccupancyInverted(v)
        JbLog.i(TAG, "착좌 반전 판정: $v — 저장함(다음 실행부터 사용, 지금은 $occupancyInverted)")
    }

    private fun normalizeOccupancy(raw: Boolean?): Boolean? = raw?.let { if (occupancyInverted) !it else it }

    private fun isDoorOpen(pos: Int?): Boolean? = pos?.let { it > 0 }

    private fun normalizeWindow(id: SeatId, raw: Int): Int {
        val (min, max) = windowRanges[id] ?: (0 to 100)
        if (max <= min) return raw.coerceIn(0, 100)
        return (((raw - min) * 100) / (max - min)).coerceIn(0, 100)
    }

    private fun denormalizeWindow(id: SeatId, percent: Int): Int {
        val (min, max) = windowRanges[id] ?: (0 to 100)
        return min + ((max - min) * percent.coerceIn(0, 100)) / 100
    }

    private fun updateSeat(id: SeatId, f: (SeatState) -> SeatState) =
        _snapshot.update { s -> s.copy(seats = s.seats + (id to f(s.seat(id))), timestampMs = now()) }

    private inline fun safe(what: String, block: () -> Unit) {
        try { block() } catch (e: Exception) { warn(what, e) }
    }

    private fun warn(what: String, e: Exception) = JbLog.w(TAG, "$what: ${e.message}")

    private fun now() = System.currentTimeMillis()
}