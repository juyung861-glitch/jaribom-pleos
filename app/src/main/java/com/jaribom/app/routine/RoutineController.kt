package com.jaribom.app.routine

import android.content.Context
import com.jaribom.app.BuildConfig
import com.jaribom.app.JbLog
import com.jaribom.app.core.AlertLimiter
import com.jaribom.app.core.ButtonGate
import com.jaribom.app.core.ButtonGuard
import com.jaribom.app.core.ButtonNoticeMerger
import com.jaribom.app.core.CabinRoutineEngine
import com.jaribom.app.core.CarryItem
import com.jaribom.app.core.ChildProfile
import com.jaribom.app.core.ChildProfiles
import com.jaribom.app.core.ClimateSupport
import com.jaribom.app.core.LogRedactor
import com.jaribom.app.core.SeatSignalQuality
import com.jaribom.app.core.SeatSignalStatus
import com.jaribom.app.core.CommandResult
import com.jaribom.app.core.ExitMessageRules
import com.jaribom.app.core.DeferredNotices
import com.jaribom.app.core.DriverReportMode
import com.jaribom.app.core.ItemCondition
import com.jaribom.app.core.NoticeAction
import com.jaribom.app.core.NoticeButtons
import com.jaribom.app.core.PendingWindowWrite
import com.jaribom.app.core.QuickItems
import com.jaribom.app.core.LinkedSnapshotFeed
import com.jaribom.app.core.RetryBook
import com.jaribom.app.core.RoutineEvent
import com.jaribom.app.core.SeatSignalMode
import com.jaribom.app.core.SpeedSignal
import com.jaribom.app.core.SafetyGate
import com.jaribom.app.core.SeatId
import com.jaribom.app.core.TelemetryManager
import com.jaribom.app.core.TelemetrySink
import com.jaribom.app.core.UnconfirmedWindowWrites
import com.jaribom.app.core.UxPolicy
import com.jaribom.app.core.VehicleCommand
import com.jaribom.app.core.VehicleSnapshot
import com.jaribom.app.core.WeatherInfo
import com.jaribom.app.core.WindowAbortReason
import com.jaribom.app.data.ItemStore
import com.jaribom.app.platform.platformBindings
import com.jaribom.app.vehicle.ShutdownOrder
import com.jaribom.app.vehicle.WindowCloser
import com.jaribom.app.vehicle.WindowRecovery
import com.jaribom.app.vehicle.WriteVerifier
import com.jaribom.app.weather.Region
import com.jaribom.app.weather.Regions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/** 설정 화면에서 보내는 사용자 동작. 미리보기에서는 빈 구현을 넘긴다. */
interface JariBomActions {
    /** [seat] 는 [ItemCondition.SEAT](이 좌석에서만)일 때 고른 좌석 */
    fun addItem(name: String, condition: ItemCondition, seat: SeatId?)
    fun removeItem(id: Long)
    /** "자주 쓰는 물건" 칩 한 번 탭(F12-3): 기본 조건으로 넣거나 뺀다. */
    fun toggleQuickItem(preset: QuickItems.Preset)
    fun setQuietPolicy(p: ExitMessageRules.QuietPolicy)
    fun toggleManaged(seat: SeatId)
    /** 설정 "아이 좌석". 뒷좌석 왼쪽·오른쪽만 받는다. */
    fun toggleChildSeat(seat: SeatId)
    /** 설정 "탑승 멘트" on/off */
    fun setBoardingVoice(on: Boolean)
    /** 설정 "하차 멘트" on/off */
    fun setExitVoice(on: Boolean)
    fun speakTest()
    /** 디버그 패널에서 날씨를 바꾸면 즉시 반영(mock) */
    fun refreshWeatherNow()
    /** 설정 "주로 다니는 지역"(A6) */
    fun setRegion(region: Region)
    /** debug 빌드 전용 "바깥 기온 강제값"(A7). null 이면 기상청 값을 쓴다. release 에서는 무시한다. */
    fun setOutdoorTempOverride(tempC: Float?)
    /** debug 빌드 전용 "기록 초기화". 월별 기록을 모두 지운다. release 에서는 무시한다. */
    fun clearStats()
    /** 설정 "익명 사용 통계 보내기"(F6-7, 기본 꺼짐). 끄면 만들어 둔 집계 사본을 지운다. */
    fun setTelemetry(on: Boolean)
    /** 설정 "택시 모드"(F6-8, 기본 꺼짐) */
    fun setTaxiMode(on: Boolean)
    /** 아이 좌석의 물건 추가(F11-2). [day] 가 null 이면 기본 물건, 아니면 그 요일 물건. */
    fun addChildItem(seat: SeatId, day: java.time.DayOfWeek?, name: String)
    fun removeChildItem(seat: SeatId, day: java.time.DayOfWeek?, name: String)
    /** 아이 이름(선택, F11-3). 비우면 이름 없이 말한다. 기기 안에만 저장하고 로그에는 set/unset 만 남긴다. */
    fun setChildName(seat: SeatId, name: String)
    /** 설정 "운전자 보고"(F12-1): 모두 / 경고만 / 끔. 경고는 설정과 상관없이 알린다. */
    fun setReportMode(mode: DriverReportMode)
    /** 메인 "키즈락 모드" 켜기/끄기(F12-4). 정차 중에만 바꾼다. */
    fun setKidsLockOn(on: Boolean)
    /** 설정 "뒷좌석 통풍도 켜기"(F12-5, 기본 꺼짐). capability 가 지원을 보고한 뒷좌석만. */
    fun setRearVentOnBoard(on: Boolean)
    /** 설정 "도로변 하차 때 뒤 차 확인"(F12-6 대체안, 기본 꺼짐) */
    fun setRoadsideRearCheck(on: Boolean)
    /** 설정 "운전석 창문 안내"(비·1시간 안 비) */
    fun setDriverWindowNotice(on: Boolean)
    /** 설정 "실내 온도 안내"(30℃ 이상) */
    fun setCabinHeatNotice(on: Boolean)
}

/** 이번 단계의 전송부: 로그만 남긴다(네트워크 없음). */
private object LogTelemetrySink : TelemetrySink {
    override fun send(json: String) = JbLog.i("JariBomTelemetry", "(전송 안 함, 로그만) " + json.replace("\n", " "))
}

/** 설정 화면이 보는 상태. 차량 상세(좌석·창문 등)는 Pleos 기본 화면에 있으므로 여기서는 다루지 않는다. */
data class RoutineState(
    val running: Boolean = false,
    val vehicleStatus: String = "",
    val uxRestricted: Boolean = false,
    /** 알림 버튼을 숨길 때(F10-4 화면 제한 또는 기어가 P 가 아님, F12-fix). [NoticeButtons.hidden] */
    val buttonsHidden: Boolean = false,
    val riding: List<SeatId> = emptyList(),
    val items: List<CarryItem> = emptyList(),
    val managedSeats: Set<SeatId> = SeatId.passengers.toSet(),
    /** 설정 "아이 좌석"(기본 없음) */
    val childSeats: Set<SeatId> = emptySet(),
    val quietPolicy: ExitMessageRules.QuietPolicy = ExitMessageRules.QuietPolicy.SHORT_PHRASE,
    val boardingVoice: Boolean = true,
    val exitVoice: Boolean = true,
    /** 메인 "키즈락 모드"(F12-4, 기본 켜짐). 끄면 키즈락 좌석도 일반 좌석으로 동작한다. */
    val kidsLockOn: Boolean = true,
    /** 설정 "뒷좌석 통풍도 켜기"(F12-5, 기본 꺼짐) */
    val rearVentOnBoard: Boolean = false,
    /** 설정 "도로변 하차 때 뒤 차 확인"(F12-6, 기본 꺼짐) */
    val roadsideRearCheck: Boolean = false,
    /** 좌석별 열선·통풍 지원 여부(F12-5). 설정 화면이 "이 좌석은 통풍이 없어요"를 보여 준다. */
    val climateSupport: Map<SeatId, ClimateSupport> = emptyMap(),
    /** 설정 "운전자 보고"(F12-1, 기본 경고만) */
    val reportMode: DriverReportMode = DriverReportMode.DEFAULT,
    val driverWindowNotice: Boolean = true,
    val cabinHeatNotice: Boolean = true,
    val lastResult: CabinRoutineEngine.AlightResult? = null,
    /** 이번 달 누적 기록(ItemStore 월별 저장값) */
    val stats: CabinRoutineEngine.Stats = CabinRoutineEngine.Stats(),
    /** 지난달 누적 기록. "이번 달 대신한 일" 카드의 비교용 */
    val lastMonthStats: CabinRoutineEngine.Stats = CabinRoutineEngine.Stats(),
    val weather: WeatherInfo = WeatherInfo(),
    val region: Region = Regions.default,
    /** debug 빌드 "바깥 기온 강제값". null = 기상청 값 */
    val outdoorTempOverride: Float? = null,
    /** debug 패널용 뒷좌석 창문 잠금(차량 구독값). null = 읽을 수 없음 */
    val rearWindowLocks: Map<SeatId, Boolean?> = emptyMap(),
    /** 좌석별 신호 모드(F9-3). 비어 있으면 모두 전체(착좌·문). 설정 화면이 좌석 옆에 한 줄로 보여 준다. */
    val seatModes: Map<SeatId, SeatSignalMode> = emptyMap(),
    /** 설정 "택시 모드"(기본 꺼짐): 모든 동승 좌석에 두고 내림 보조 판정 */
    val taxiMode: Boolean = false,
    /** 아이 좌석별 기본·요일별 물건(F11-2). 기기 안에만 저장한다. */
    val childProfiles: Map<SeatId, ChildProfile> = emptyMap(),
    /** 동승 좌석별 신호 상태(F11-4). 설정 화면의 좌석 항목에만 보여 준다. */
    val seatSignals: Map<SeatId, SeatSignalStatus> = emptyMap(),
    /** 차속 신호 상태(F10-2). 설정 화면이 거부·미지원 문구를 보여 준다. */
    val speedSignal: SpeedSignal = SpeedSignal.UNKNOWN,
    /** 설정 "익명 사용 통계 보내기"(기본 꺼짐) */
    val telemetryOn: Boolean = TelemetryManager.DEFAULT_ENABLED,
    /** "보낼 데이터 미리 보기". 꺼져 있으면 "보내지 않음" */
    val telemetryPreview: String = TelemetryManager.OFF_PREVIEW,
)

/**
 * 차량 스냅샷 → 엔진 → (Pleos TTS, 차량 쓰기, 시스템 알림) 을 잇는 곳. 화면과 무관하게 [JariBomService] 안에서 돈다.
 * 앱 프로세스에 하나만 있도록 [get] 으로 꺼내 쓴다(서비스·설정 화면·알림 버튼이 같은 인스턴스를 본다).
 */
class RoutineController private constructor(context: Context) : JariBomActions {

    companion object {
        private const val TAG = "JariBomRoutine"
        /** 판정을 기다리는 좌석이 있을 때 마지막 스냅샷을 다시 넣는 간격(탑승 확정 3초 대기를 ~0.5초 오차로 맞춤) */
        private const val TICK_MS = 500L
        /**
         * 하차 확정 뒤 원복 쓰기까지 기다리는 시간. 문을 닫자마자 다시 앉는 경우 착좌 신호가 도착할 틈을 준다.
         * 그사이 다시 앉으면 작업을 취소하고, 그래도 겹치면 쓰기 직전 SafetyGate 가 "사람이 다시 앉아 있음"으로 거부한다.
         */
        private const val CLEANUP_DELAY_MS = 1_500L
        /** 재연결 직후 게이트웨이가 현재값을 다시 읽어 스냅샷에 넣을 틈(F8-2) */
        private const val RECONNECT_SETTLE_MS = 1_000L
        /** 기어 P 로 이만큼 서 있고 동승석이 모두 비면 운행이 끝난 것으로 보고 [CabinRoutineEngine.endTrip] 을 부른다. */
        private const val END_TRIP_IDLE_MS = 5 * 60 * 1000L
        @Volatile private var instance: RoutineController? = null
        fun get(context: Context): RoutineController =
            instance ?: synchronized(this) { instance ?: RoutineController(context.applicationContext).also { instance = it } }
    }

    private val app = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val bindings = platformBindings()
    private val gateway = bindings.vehicleGateway(app)
    /** 안내 음성. stop() 때 해제하고(5차 심사 F5), 다시 start() 하면 새로 만든다. */
    private var announcer = bindings.announcer(app)
    private var announcerReleased = false

    /** 음성 실패(F9-4)를 엔진에 알린다. 동승자 안내가 실패했으면 하차 보고 알림 끝에 한 줄이 붙는다. */
    private fun bindSpeechFailure() {
        announcer.onFailed = { text, reason ->
            for (ev in engine.onSpeechFailed(text, reason.name)) handle(ev)
            syncEngineState()
        }
        announcer.onDropped = { text, reason ->
            for (ev in engine.onPassengerSpeechDropped(text, reason)) handle(ev)
            syncEngineState()
        }
        // F12-2: 음성으로만 낸 보고는 끝까지 나가면 알림을 남기지 않고, 포커스를 잃어 못 나가면 알림으로 남긴다.
        announcer.onSpoken = { text -> for (ev in engine.onSpeechDone(text)) handle(ev) }
        announcer.onAbandoned = { text -> for (ev in engine.onSpeechAbandoned(text)) handle(ev) }
    }
    private val location = bindings.locationProvider(app)
    private val weatherProvider = bindings.weatherProvider(app)
    private val store = ItemStore(app)
    // 예전 "이 좌석에서만" 물건 중 키즈락 좌석 것을 그 좌석 기본 물건으로 복사한다(F11-2, F11-V: 예전 목록은 그대로, 좌석별 한 번).
    // 아래 상태를 읽기 전에 해야 한다.
    init {
        val copied = store.copyLegacyItemsOnce(store.loadChildSeats())
        if (copied > 0) JbLog.i(TAG, "예전 좌석 전용 물건 ${copied}개를 키즈락 좌석 기본 물건으로 복사(예전 목록은 그대로)")
    }
    // 하차 한마디는 풀에서 무작위로 고른다(F11-1, 같은 좌석에서 직전 문장은 피함). 테스트용 엔진은 기본(0번 고정)을 쓴다.
    private val engine = CabinRoutineEngine().apply { phrasePicker = com.jaribom.app.core.PhrasePicker(kotlin.random.Random.Default) }
    private val notifier = Notifier(app)

    private val _state = MutableStateFlow(
        RoutineState(
            items = store.load(), managedSeats = store.loadManagedSeats(), childSeats = store.loadChildSeats(), quietPolicy = store.loadQuietPolicy(),
            boardingVoice = store.loadBoardingVoice(), exitVoice = store.loadExitVoice(), region = store.loadRegion(),
            telemetryOn = store.loadTelemetry(), taxiMode = store.loadTaxiMode(), childProfiles = store.loadChildProfiles(),
            kidsLockOn = store.loadKidsLockOn(), rearVentOnBoard = store.loadRearVentOnBoard(), roadsideRearCheck = store.loadRoadsideRearCheck(), reportMode = store.loadReportMode(), driverWindowNotice = store.loadDriverWindowNotice(), cabinHeatNotice = store.loadCabinHeatNotice(),
        )
    )
    private val telemetry = TelemetryManager(store.telemetryCopies, LogTelemetrySink, store.loadTelemetry())
    val state: StateFlow<RoutineState> = _state.asStateFlow()

    /** 엔진이 센 기록 중 이미 월별 저장소에 더한 만큼. 엔진은 시작 뒤 늘어난 만큼만 센다. */
    private var counted = CabinRoutineEngine.Stats()
    /** 화면에 올린 기록의 달. 달이 바뀌면 다시 읽는다. */
    private var shownMonth = ""

    /** 알림별 정리 실패(알림 키 → 좌석별 실패 명령). 각 알림의 [다시 시도]는 자기 실패만 실행한다(5차 심사 F1). */
    private val retryBook = RetryBook()
    /** [열선 끄기] 버튼이 붙은 알림의 대상(알림 키 → 끄기 명령, F9-2). 알림마다 키가 달라 버튼은 자기 좌석만 끈다. */
    private val climateOffBook = mutableMapOf<Int, List<VehicleCommand>>()
    /** [창문 닫기] 버튼이 붙은 알림의 대상(F9-3, 착좌 신호가 없는 좌석). 운전자가 눌러야 닫는다. */
    private val windowCloseBook = mutableMapOf<Int, List<VehicleCommand.SetWindow>>()
    /** [어른이 탔어요] 버튼이 붙은 알림 키 → 아이 좌석(F8-3). 프로세스가 다시 뜨면 비어 있어 버튼을 눌러도 실행하지 않는다. */
    private val adultBook = mutableMapOf<Int, SeatId>()
    /** 버튼이 붙은 알림을 만들 때의 좌석 에피소드(알림 키 → 좌석별). 누를 때 달라졌으면 오래된 알림이다. */
    private val buttonEpisodes = mutableMapOf<Int, Map<SeatId, Int>>()
    /** 좌석별 버튼 명령 진행 중 표시(연타 무시) */
    private val buttonGate = ButtonGate()
    /** 창문을 닫는 중인 작업들(F9 리뷰). stop() 이 정지가 나가기를 기다리는 데 쓴다. */
    private val closingJobs = mutableSetOf<Job>()
    /** 하차 보고에 합쳐진 알림을 버튼 결과가 덮어쓰지 않게 원래 알림을 기억한다. */
    private val merger = ButtonNoticeMerger()
    /** 기어 P 이고 동승석이 모두 빈 상태가 시작된 시각. 아니면 null. */
    private var idleParkedSinceMs: Long? = null
    /** CarUxRestrictionsManager 신호(F6-3). null = 매니저 없음. 화면 편집 제한에만 쓴다. */
    private var carUx: Boolean? = null
    /** 결과를 못 받은 창문 쓰기(F8-2). 서비스 사망 뒤 재연결되면 그 좌석 창문을 다시 읽어 확인한다. */
    private val unconfirmedWindows = UnconfirmedWindowWrites()
    private var started = false
    /** 마지막으로 저장한 좌석 신호 기록(F11-4). 바뀐 때만 저장한다. */
    private var savedSignalHistory: Map<SeatId, List<Boolean>> = emptyMap()
    private var weatherJob: Job? = null
    private var tickJob: Job? = null

    init {
        engine.items = _state.value.items
        engine.managedSeats = _state.value.managedSeats
        engine.childSeats = _state.value.childSeats
        engine.quietPolicy = _state.value.quietPolicy
        engine.boardingVoice = _state.value.boardingVoice
        engine.exitVoice = _state.value.exitVoice
        engine.reportMode = _state.value.reportMode
        engine.driverWindowNotice = _state.value.driverWindowNotice
        engine.cabinHeatNotice = _state.value.cabinHeatNotice
        engine.taxiMode = _state.value.taxiMode
        engine.kidsLockOn = _state.value.kidsLockOn
        engine.rearVentOnBoard = _state.value.rearVentOnBoard
        engine.roadsideRearCheck = _state.value.roadsideRearCheck
        engine.childProfiles = _state.value.childProfiles
        engine.signalQuality = SeatSignalQuality(store.loadSignalHistory())
        savedSignalHistory = engine.signalQuality.allHistory()
        LogRedactor.setNames(_state.value.childProfiles.values.map { it.name })
        // 하루 보조 알림 횟수는 저장소에 이어서 센다(F7-4, 앱을 다시 켜도 유지).
        engine.alertLimiter = AlertLimiter(daily = store.dailyAssistCount)
        bindSpeechFailure()
        syncEngineState()
    }

    /** 서비스가 시작될 때 한 번 부른다. 여러 번 불러도 안전하다. */
    fun start() {
        if (started) return
        started = true
        if (announcerReleased) { announcer = bindings.announcer(app); announcerReleased = false }
        bindSpeechFailure()
        _state.update { it.copy(running = true) }
        // 착좌 반전 판정을 새로 저장한 직후의 첫 운행은 창문을 자동으로 닫지 않고 [창문 닫기] 버튼으로만 닫는다(F10-5).
        engine.windowButtonOnly = gateway.occupancyFreshlyLearned
        if (engine.windowButtonOnly) JbLog.i(TAG, "착좌 반전 판정을 새로 저장한 뒤 첫 운행 — 창문 자동 닫기 대신 버튼")
        scope.launch {
            gateway.connect()
            JbLog.i(TAG, "차량 연결: ${gateway.status.value}")
        }
        scope.launch { gateway.status.collect { s -> _state.update { it.copy(vehicleStatus = s) } } }
        // 연결부(끊긴 동안 스냅샷 버림, 다시 붙은 순간 한 번 넣기)는 [LinkedSnapshotFeed] 가 담당한다(F14-2, 동작은 그대로).
        val feed = LinkedSnapshotFeed(gateway.linked.value, ::onSnapshot)
        scope.launch { gateway.snapshot.collect { snap -> feed.onSnapshot(snap, gateway.linked.value) } }
        scope.launch { gateway.speedSignal.collect { s -> _state.update { it.copy(speedSignal = s) } } }
        scope.launch {
            var wasHidden = _state.value.buttonsHidden
            _state.collect { s ->
                if (!wasHidden && s.buttonsHidden) hideShownNoticeButtons()
                if (wasHidden && !s.buttonsHidden) releaseDeferredNotices()
                wasHidden = s.buttonsHidden
            }
        }
        scope.launch {
            var wasLinked = gateway.linked.value
            gateway.linked.collect { up ->
                if (wasLinked && !up) unconfirmedWindows.onDied(System.currentTimeMillis())
                // F13-3: 다시 붙은 순간 지금 전체 상태를 엔진에 한 번 넣는다(끊긴 사이 문이 닫힌 하차를 이어서 확정).
                val reconnected = feed.onLinkChanged(up, gateway.snapshot.value, System.currentTimeMillis())
                if (reconnected) JbLog.i(TAG, "재연결 — 지금 상태를 엔진에 한 번 넣어 진행 중인 탑승·하차와 맞춤")
                if (reconnected) reviewWindowsAfterReconnect()
                wasLinked = up
            }
        }
        scope.launch {
            gateway.seatModes.collect { modes ->
                engine.seatModes = modes
                _state.update { it.copy(seatModes = modes, seatSignals = engine.signalStatuses()) }
                JbLog.i(TAG, "좌석 신호 모드: " + modes.entries.joinToString { "${it.key.label}=${it.value.name}" })
            }
        }
        scope.launch {
            gateway.climateSupport.collect { sup ->
                engine.climateSupport = sup
                _state.update { it.copy(climateSupport = sup) }
            }
        }
        scope.launch {
            gateway.uxRestricted.collect { ux ->
                carUx = ux
                val snap = gateway.snapshot.value
                _state.update { it.copy(uxRestricted = UxPolicy.screenLocked(ux, snap), buttonsHidden = NoticeButtons.hidden(ux, snap)) }
            }
        }
        weatherJob = scope.launch { refreshWeatherLoop() }
    }

    fun stop() {
        if (!started) return
        started = false
        engine.endTrip().forEach { handle(it) }
        finishTripFlags()
        telemetry.flush()          // 켜 둔 경우에만, 로그로만 남는다
        // 닫는 중이던 창문은 취소되면서 정지를 보낸다. disconnect 가 먼저 돌면 정지가 실패하므로 그 작업이 끝나기를 잠깐 기다린다(F9 리뷰).
        val closingNow = closingJobs.toList()
        scope.coroutineContext.cancelChildren()
        announcer.release()
        announcerReleased = true
        _state.update { it.copy(running = false) }
        CoroutineScope(Dispatchers.Main).launch {
            ShutdownOrder.run(closingNow, log = { JbLog.w(TAG, it) }) { gateway.disconnect() }
        }
    }

    /** 재연결 직후(F8-2): 사망 때 결과를 못 받은 창문 쓰기가 있으면 잠시 기다려 새 값을 받은 뒤 그 좌석 창문을 확인한다. */
    private suspend fun reviewWindowsAfterReconnect() {
        val pending = unconfirmedWindows.takeAfterReconnect()
        if (pending.isEmpty()) return
        JbLog.i(TAG, "재연결 뒤 창문 확인 대상: " + pending.joinToString { "${it.seat.label}(목표 ${it.target}%, 직전 ${it.before ?: "?"}%)" })
        delay(RECONNECT_SETTLE_MS)
        for (ev in engine.onWindowRecovery(WindowRecovery.review(gateway, pending))) handle(ev)
        syncEngineState()
    }

    private suspend fun refreshWeatherLoop() {
        while (true) {
            fetchWeather()
            delay(10 * 60 * 1000L) // 10분마다
        }
    }

    private suspend fun fetchWeather() {
        val loc = location.current()
        val fetched = if (loc != null) weatherProvider.fetch(loc.first, loc.second) else WeatherInfo()
        _state.update { it.copy(weather = fetched) }
        applyWeather()
    }

    /** 받아 둔 날씨에 debug 강제 기온을 덮어 엔진에 넣는다(release 에서는 강제값을 쓰지 않는다). */
    private fun applyWeather() {
        val override = _state.value.outdoorTempOverride.takeIf { BuildConfig.DEBUG }
        val w = _state.value.weather.let { if (override != null) it.copy(outdoorTempC = override) else it }
        engine.weather = w
        JbLog.i(TAG, "날씨: ${_state.value.region.label} · 바깥 ${w.outdoorTempC ?: "?"}℃" + (if (override != null) "(강제값)" else "") +
            " · 비 ${w.rainingNow ?: "?"} · 1시간 내 비 ${w.rainWithinHour ?: "?"}")
    }

    override fun setRegion(region: Region) {
        if (editBlocked("지역")) return
        store.saveRegion(region)
        _state.update { it.copy(region = region) }
        JbLog.i(TAG, "날씨 지역 변경: ${region.label} (격자 ${region.grid.first},${region.grid.second})")
        refreshWeatherNow()
    }

    override fun setOutdoorTempOverride(tempC: Float?) {
        if (!BuildConfig.DEBUG) return
        _state.update { it.copy(outdoorTempOverride = tempC) }
        applyWeather()
    }

    override fun clearStats() {
        if (!BuildConfig.DEBUG) return
        store.clearStats()
        store.saveSignalHistory(emptyMap()); engine.signalQuality = SeatSignalQuality(); savedSignalHistory = emptyMap()
        _state.update { it.copy(seatSignals = engine.signalStatuses()) }
        engine.alertLimiter.resetDaily()          // 하루 보조 알림 횟수도 0(F7-4)
        // 엔진이 이미 센 만큼은 다시 더하지 않는다(counted 유지). 이후 늘어난 만큼만 새로 쌓인다.
        shownMonth = ""
        syncEngineState()
        JbLog.i(TAG, "기록 초기화(debug)")
    }

    override fun refreshWeatherNow() {
        scope.launch { fetchWeather() }
    }

    private fun onSnapshot(snap: VehicleSnapshot) {
        // 차량 서비스와 끊긴 동안의 스냅샷은 마지막 값이다. 엔진에 넣지 않는다(가짜 하차·장남 판정 방지, F7-2).
        // 다시 붙으면 게이트웨이가 현재값을 모두 다시 읽어 새 스냅샷이 들어온다.
        if (!gateway.linked.value) return
        val events = engine.onSnapshot(snap)
        _state.update {
            it.copy(
                // F6-3(B안): 자체 정차 판정이 1차, CarUx 는 화면 편집 제한을 더할 뿐이다.
                uxRestricted = UxPolicy.screenLocked(carUx, snap),
                buttonsHidden = NoticeButtons.hidden(carUx, snap),
                riding = SeatId.passengers.filter { s -> snap.seat(s).occupied == true },
                rearWindowLocks = CabinRoutineEngine.CHILD_SEAT_CANDIDATES.associateWith { s -> snap.seat(s).windowLocked },
                seatSignals = engine.signalStatuses(),
            )
        }
        persistSignalHistory()
        for (ev in events) handle(ev)
        checkEndTrip(snap)
        syncEngineState()
        ensureTick()
    }

    /**
     * P 로 [END_TRIP_IDLE_MS] 동안 서 있고 동승석이 모두 비었으면 운행을 마무리한다(30초 폴링 스냅샷으로도 판정된다).
     * 남은 물건으로 알린 좌석은 빈 좌석으로 본다([CabinRoutineEngine.isTripIdle]).
     */
    /** 운행이 끝났다: 반전 저장 직후 첫 운행 표시(창문 버튼 모드)를 푼다(F10-5). */
    private fun finishTripFlags() {
        engine.windowButtonOnly = false
        gateway.onTripEnded()
    }

    private fun checkEndTrip(snap: VehicleSnapshot) {
        val idle = engine.isTripIdle(snap)
        if (!idle) { idleParkedSinceMs = null; return }
        val since = idleParkedSinceMs ?: snap.timestampMs.also { idleParkedSinceMs = it }
        if (snap.timestampMs - since >= END_TRIP_IDLE_MS && engine.tripActive) {
            idleParkedSinceMs = null
            for (ev in engine.endTrip()) handle(ev)
            finishTripFlags()
        }
    }

    /**
     * 상태머신은 새 스냅샷이 들어올 때만 판정한다. 차가 아무 신호도 보내지 않으면 "착좌 3초 유지" 같은 시간 조건이
     * 다음 콜백·30초 폴링까지 미뤄진다. 판정을 기다리는 좌석이 있는 동안만 마지막 스냅샷의 시각을 현재로 바꿔 다시 넣는다.
     * 기다리는 좌석이 없으면 루프가 끝난다(30초 안전망 폴링은 게이트웨이에 그대로 있다).
     */
    private fun ensureTick() {
        if (!engine.needsTick || tickJob?.isActive == true) return
        tickJob = scope.launch {
            while (engine.needsTick) {
                delay(TICK_MS)
                onSnapshot(gateway.snapshot.value.copy(timestampMs = System.currentTimeMillis()))
            }
        }
    }

    /** 저장할 달은 저장할 때마다 다시 계산한다(앱을 켠 채 달이 바뀌어도 새 달에 쌓이도록). */
    private fun monthOf(offset: Int = 0): String {
        val c = Calendar.getInstance().apply { add(Calendar.MONTH, offset) }
        return SimpleDateFormat("yyyy-MM", Locale.KOREA).format(c.time)
    }

    private fun persistSignalHistory() {
        val now = engine.signalQuality.allHistory()
        if (now == savedSignalHistory) return
        store.saveSignalHistory(now); savedSignalHistory = now
    }

    private fun syncEngineState() {
        val now = engine.stats
        val month = monthOf()
        if (now != counted || month != shownMonth) {
            if (now != counted) {
                // 동승 하차가 있던 날 수(날짜는 저장하지 않고 일자 번호 집합의 크기만 통계에 쓴다)
                if (now.rides > counted.rides) store.markActiveDay(month, Calendar.getInstance().get(Calendar.DAY_OF_MONTH))
                store.saveStats(month, store.loadStats(month) + (now - counted))
                counted = now
            }
            shownMonth = month
            val st = store.loadStats(month)
            val install = store.installMonth(month)
            telemetry.update(month, st, store.activeDays(month), install)
            _state.update {
                it.copy(
                    stats = st, lastMonthStats = store.loadStats(monthOf(-1)),
                    telemetryPreview = telemetry.preview(month, st, store.activeDays(month), install),
                )
            }
        }
        _state.update { it.copy(lastResult = engine.lastResult) }
    }

    /** 알림 한 건의 내용(F10-4). [fullBody] 는 제한이 없을 때의 본문, [shownBody] 는 지금 내보내는 본문(제한 중이면 첫 줄만). */
    private class NoticeArgs(
        val key: Int, val seats: List<SeatId>, val fullBody: String, val shownBody: String, val final: Boolean, val canRetry: Boolean,
        val title: String?, val climateOffLabel: String?, val windowCloseLabel: String?, val adultLabel: String?,
    ) {
        val actions: List<NoticeAction>
            get() = listOfNotNull(
                NoticeAction.RETRY.takeIf { canRetry }, NoticeAction.CLIMATE_OFF.takeIf { climateOffLabel != null },
                NoticeAction.WINDOW_CLOSE.takeIf { windowCloseLabel != null }, NoticeAction.ADULT_ABOARD.takeIf { adultLabel != null },
            )
    }

    /** 제한 중에 버튼 없이 낸 알림(F10-4). 제한이 풀리면 아직 유효한 것만 버튼을 붙여 다시 낸다. */
    private val deferredNotices = DeferredNotices<NoticeArgs>()

    /** 알림을 낸다. 화면 편집 제한 중이면 액션 버튼 없이 문장만 내고, 풀릴 때 붙이도록 기억해 둔다. */
    private fun postNotice(a: NoticeArgs) {
        val wanted = a.actions
        val shown = NoticeButtons.visible(_state.value.buttonsHidden, wanted)
        val episodes = buttonEpisodes[a.key] ?: a.seats.associateWith { engine.episodeOf(it) }
        when {
            wanted.isEmpty() -> deferredNotices.forget(a.key)
            shown.isEmpty() -> {
                deferredNotices.defer(a.key, a, episodes)
                JbLog.i(TAG, "주행 중이라 알림 버튼 숨김: 알림 ${a.key} ${wanted.joinToString()}")
            }
            else -> deferredNotices.markShown(a.key, a, episodes)
        }
        notifier.showCleanup(
            a.key, a.seats, a.shownBody, a.final, canRetry = NoticeAction.RETRY in shown, title = a.title,
            climateOffLabel = a.climateOffLabel.takeIf { NoticeAction.CLIMATE_OFF in shown },
            windowCloseLabel = a.windowCloseLabel.takeIf { NoticeAction.WINDOW_CLOSE in shown },
            adultLabel = a.adultLabel.takeIf { NoticeAction.ADULT_ABOARD in shown },
        )
    }

    /**
     * 제한이 시작됐다(화면 제한 또는 기어가 P 를 벗어남, F12-fix): 버튼을 달고 나간 알림을 같은 ID 로 다시 내 버튼만 뗄다.
     * 본문은 그대로이고 소리·헤드업 없이 갱신한다. 다시 정차하면 [releaseDeferredNotices] 가 유효한 것만 붙인다.
     */
    private fun hideShownNoticeButtons() {
        for ((key, a) in deferredNotices.hideShown()) {
            JbLog.i(TAG, "주행 시작: 알림 $key 버튼만 떼고 다시 냄 ${a.actions.joinToString()}")
            notifier.showCleanup(a.key, a.seats, a.shownBody, a.final, canRetry = false, title = a.title, quiet = true)
        }
    }

    /** 제한이 풀렸다(정차): 아직 유효한 알림(좌석 에피소드가 같은 것)만 전체 본문과 버튼을 붙여 갱신한다. 바뀐 알림은 버튼 없이 둔다. */
    private fun releaseDeferredNotices() {
        for ((key, a) in deferredNotices.release(engine::episodeOf)) {
            JbLog.i(TAG, "정차: 알림 $key 버튼 붙여 갱신 ${a.actions.joinToString()}")
            notifier.showCleanup(
                a.key, a.seats, a.fullBody, a.final, a.canRetry, a.title, a.climateOffLabel, a.windowCloseLabel, a.adultLabel, quiet = true,
            )
        }
    }

    /** [fromButton] 이면 버튼 결과를 원래 알림에 합친 것이라 [merger] 가 원래 알림을 새로 기억하지 않는다. */
    private fun handle(ev: RoutineEvent, fromButton: Boolean = false) {
        when (ev) {
            is RoutineEvent.Boarded -> JbLog.i(TAG, "탑승 확정: ${ev.seat.label}")
            is RoutineEvent.DoorOpened -> JbLog.i(TAG, "하차 준비(문 열림): ${ev.seat.label}")
            is RoutineEvent.Alighted -> JbLog.i(TAG, "하차 확정: ${ev.seat.label}")
            is RoutineEvent.Log -> JbLog.i(TAG, ev.text)
            is RoutineEvent.Speak -> {
                JbLog.i(TAG, "🔊 ${ev.text}")
                // 동승자 하차 안내는 큐에서 차례가 됐을 때 다시 판정한다(F8-1). 그 밖의 문장은 가드가 null 을 돌려준다.
                announcer.speak(ev.text) {
                    engine.passengerSpeechDropReason(ev.text, System.currentTimeMillis(), gateway.snapshot.value)
                }
            }
            is RoutineEvent.CleanupNotice -> {
                if (!fromButton) merger.remember(ev)
                if (ev.plain) {
                    // 단독 알림(잠금 실패·남은 물건 등): 정리 결과가 아니므로 월 누적·[다시 시도]와 섞지 않는다.
                    val text = UxPolicy.notificationText(ev.text, _state.value.uxRestricted)
                    JbLog.i(TAG, "🔔 [${ev.title ?: "알림"}] $text" + if (ev.climateOff.isNotEmpty()) " (버튼: ${climateOffLabel(ev.climateOff)})" else "")
                    if (ev.climateOff.isNotEmpty()) climateOffBook[ev.key] = ev.climateOff else climateOffBook.remove(ev.key)
                    if (ev.windowClose.isNotEmpty()) windowCloseBook[ev.key] = ev.windowClose else windowCloseBook.remove(ev.key)
                    if (ev.adultSeat != null) adultBook[ev.key] = ev.adultSeat else adultBook.remove(ev.key)
                    trackEpisodes(ev)
                    postNotice(
                        NoticeArgs(
                            ev.key, ev.seats, fullBody = ev.text, shownBody = text, final = true, canRetry = false, title = ev.title,
                            climateOffLabel = ev.climateOff.takeIf { it.isNotEmpty() }?.let(::climateOffLabel),
                            windowCloseLabel = "창문 닫기".takeIf { ev.windowClose.isNotEmpty() },
                            adultLabel = "어른이 탔어요".takeIf { ev.adultSeat != null },
                        )
                    )
                    return
                }
                // 알림 수는 늘리지 않고 본문 끝에 이번 달 누적 한 줄을 붙인다(A5)
                syncEngineState()
                val body = UxPolicy.notificationText(ev.text + "\n이번 달 " + _state.value.stats.doneSummary(), _state.value.uxRestricted)
                JbLog.i(TAG, "🔔 [${if (ev.final) "결과" else "대기"}] ${body.replace("\n", " / ")}")
                if (ev.final) retryBook.record(ev.key, ev.failed)
                if (ev.climateOff.isNotEmpty()) climateOffBook[ev.key] = ev.climateOff else climateOffBook.remove(ev.key)
                if (ev.windowClose.isNotEmpty()) windowCloseBook[ev.key] = ev.windowClose else windowCloseBook.remove(ev.key)
                trackEpisodes(ev)
                postNotice(
                    NoticeArgs(
                        ev.key, ev.seats, fullBody = ev.text + "\n이번 달 " + _state.value.stats.doneSummary(), shownBody = body, final = ev.final,
                        canRetry = ev.final && ev.failed.isNotEmpty(), title = ev.title,
                        climateOffLabel = ev.climateOff.takeIf { it.isNotEmpty() }?.let(::climateOffLabel),
                        windowCloseLabel = "창문 닫기".takeIf { ev.windowClose.isNotEmpty() },
                        adultLabel = null,
                    )
                )
            }
            is RoutineEvent.Execute -> scope.launch {
                val seat = ev.seat
                when {
                    ev.preset && seat != null -> runPreset(ev.jobId, seat, ev.commands, ev.reason)
                    seat != null -> {
                        delay(CLEANUP_DELAY_MS)
                        if (!engine.cleanupStillValid(seat)) {
                            for (e in engine.cancelCleanup(seat, gateway.snapshot.value)) handle(e)
                            syncEngineState()
                            return@launch
                        }
                        execute(ev.commands, ev.reason, seat)
                    }
                    else -> execute(ev.commands, ev.reason, null)
                }
            }
        }
    }

    /**
     * 탑승 프리셋. 하차 정리와 같게 [CLEANUP_DELAY_MS] 기다린 뒤, 명령마다 쓰기 직전에 그 좌석이 아직 탑승 중인지 다시 본다.
     * 아니면(그사이 내림) 남은 명령은 버린다. 쓰는 사이에 내렸으면 엔진이 켠 것을 되돌리는 작업을 낸다.
     */
    private suspend fun runPreset(jobId: Long, seat: SeatId, commands: List<VehicleCommand>, reason: String) {
        delay(CLEANUP_DELAY_MS)
        val results = mutableListOf<CommandResult>()
        for (c in commands) {
            if (!engine.presetStillValid(jobId, seat)) {
                JbLog.i(TAG, "탑승 프리셋 버림: ${seat.label}이 탑승 중이 아님 — $c")
                break
            }
            results += runOne(c, reason)
        }
        for (ev in engine.onPresetFinished(jobId, seat, results, gateway.snapshot.value)) handle(ev)
        syncEngineState()
    }

    /**
     * 명령을 하나씩 순서대로 실행한다. 기어와 상관없이 실행한다(A1: 운전 기능·도어는 애초에 명령이 없고 SafetyGate 가 막는다).
     *  1) 쓰기 직전에 SafetyGate 를 최신 스냅샷으로 다시 검사(그사이 다시 앉거나 문을 열 수 있음)
     *  2) 쓰기 실패 시 1회 재시도, 그래도 실패면 FAILED → 운전자 보고 + 알림의 [다시 시도]
     *  3) 결과를 엔진에 돌려준다. 엔진이 실제로 된 일만 말하고(정차 중일 때만) 알림 이벤트를 낸다.
     */
    private suspend fun execute(commands: List<VehicleCommand>, reason: String, seat: SeatId?) {
        // 게이트웨이가 명령마다 다시 읽어 목표값을 확인한 뒤에야 성공을 돌려준다(A3). 고정 대기 뒤 스냅샷을 보지 않는다.
        val results = commands.map { runOne(it, reason) }
        // 스냅샷 시각은 마지막 신호 시각이라 오래됐을 수 있다. 보고 시점(늦은 보고 맥락 A10)은 지금 시각으로 넘긴다.
        val after = gateway.snapshot.value.copy(timestampMs = System.currentTimeMillis())
        for (ev in engine.onJobFinished(seat, results, after)) handle(ev)
        syncEngineState()
    }

    /** 명령 하나. 재시도는 검증 실패(값이 목표에 안 닿음)일 때만 1회(5차 심사 F2). 차량이 거절한 쓰기는 바로 실패다. */
    /** [driverAction] 은 운전자가 P 에서 [어른이 탔어요]를 눌러 생긴 잠금 해제만 true 다(F12-4). 다시 시도·자동 경로는 넘기지 않는다. */
    private suspend fun runOne(c: VehicleCommand, reason: String, driverAction: Boolean = false): CommandResult {
        val windowBefore = (c as? VehicleCommand.SetWindow)?.let { gateway.snapshot.value.seat(it.seat).windowOpenPercent }
        for (attempt in 0..1) {
            val v = SafetyGate.check(c, gateway.snapshot.value, engine.controlledSeats, driverAction)
            if (!v.allowed) {
                JbLog.w(TAG, "거부(쓰기 직전): $c — ${v.reason}")
                return CommandResult(c, CommandResult.Status.REJECTED, v.reason)
            }
            // 창문을 닫는 쓰기는 닫히는 동안 그 좌석을 지켜본다(F9-1). 문 열림·착좌·벨트가 생기면 멈추고, 다시 닫지 않는다.
            val closing = c is VehicleCommand.SetWindow && c.isClosing(gateway.snapshot.value.seat(c.seat).windowOpenPercent)
            val r = if (closing) {
                // stop() 가 닫는 중이던 작업을 기다릴 수 있게 추적한다(F9 리뷰: 정지가 disconnect 보다 먼저).
                val job = currentCoroutineContext()[Job]
                if (job != null) closingJobs += job
                val o = try { WindowCloser.close(gateway, c) } finally { if (job != null) closingJobs -= job }
                when (o) {
                    is WindowCloser.Outcome.Done -> o.result
                    is WindowCloser.Outcome.Aborted -> {
                        JbLog.w(TAG, "창문 닫기 중단 [$reason] ${c.seat.label}: ${o.reason} — 정지 ${if (o.stop.isSuccess) "OK" else "실패: ${o.stop.exceptionOrNull()?.message}"}")
                        // 정지 쓰기까지 실패하면 보고에 직접 확인을 붙이도록 꼬리표를 단다.
                        return CommandResult(
                            c, CommandResult.Status.ABORTED,
                            o.reason.name + (if (o.notStopped) WindowAbortReason.NOT_STOPPED_SUFFIX else "") + (if (o.stillMoving) WindowAbortReason.STILL_MOVING_SUFFIX else "") + (if (o.stop.isFailure) WindowAbortReason.STOP_FAILED_SUFFIX else ""),
                        )
                    }
                }
            } else gateway.execute(c)
            if (r.isSuccess) {
                JbLog.i(TAG, "쓰기 OK [$reason] $c" + if (attempt == 1) " (재시도 성공)" else "")
                if (c is VehicleCommand.SetWindow) unconfirmedWindows.confirmed(c.seat)
                return CommandResult(c, CommandResult.Status.OK)
            }
            val e = r.exceptionOrNull()
            JbLog.w(TAG, "쓰기 실패 [$reason] $c → ${e?.message}")
            // 결과를 못 받은 창문 쓰기는 차량이 받았을 수 있다. 서비스 사망 뒤 재연결되면 확인한다(F8-2).
            if (c is VehicleCommand.SetWindow) unconfirmedWindows.record(PendingWindowWrite(c.seat, c.openPercent, windowBefore, System.currentTimeMillis()))
            if (attempt == 1 || e == null || !WriteVerifier.shouldRetry(e)) break
            delay(WriteVerifier.RETRY_GAP_MS)
        }
        return CommandResult(c, CommandResult.Status.FAILED)
    }

    /**
     * 알림 [key] 의 [다시 시도] 버튼. 그 알림의 실패만 실행하고 그 알림만 지운다.
     * 좌석마다 실행 전에 [CabinRoutineEngine.cleanupStillValid] 로 확인해 그사이 다시 탄 좌석은 버리고,
     * 쓰기 직전에는 안전 게이트를 다시 검사한다.
     */
    fun retryFailed(key: Int) {
        if (!retryBook.has(key)) return
        val failed = retryBook.take(key) { seat ->
            engine.cleanupStillValid(seat).also { ok -> if (!ok) JbLog.i(TAG, "다시 시도 버림: ${seat.label}에 다시 탐") }
        }
        notifier.cancelCleanup(key)
        // 이전 알림을 지웠으므로 다시 실패하면 10분 안이어도 새로 알린다(F6-9 중복 억제의 예외).
        engine.allowFailureReportAgain(failed.keys)
        for ((seat, cmds) in failed) scope.launch { execute(cmds, "다시 시도 ${seat.label}", seat) }
    }

    /** 버튼이 있는 알림의 좌석 에피소드를 기록한다. 버튼이 남는 결과 알림(실패 재표시)은 에피소드를 새로 주지 않으므로 기존 값을 유지한다. */
    private fun trackEpisodes(ev: RoutineEvent.CleanupNotice) {
        if (ev.climateOff.isEmpty() && ev.windowClose.isEmpty() && ev.adultSeat == null) { buttonEpisodes.remove(ev.key); return }
        if (ev.buttonEpisodes.isNotEmpty()) buttonEpisodes[ev.key] = ev.buttonEpisodes
    }

    private fun climateOffLabel(cmds: List<VehicleCommand>): String {
        val heater = cmds.any { it is VehicleCommand.SetSeatHeater }
        val vent = cmds.any { it is VehicleCommand.SetSeatVentilation }
        return when { heater && vent -> "열선·통풍 끄기"; vent -> "통풍 끄기"; else -> "열선 끄기" }
    }

    /**
     * 알림 [key] 의 [열선 끄기] 버튼(F9-2). 그 알림 좌석의 열선·통풍 끄기만 한다(창문은 건드리지 않는다).
     * 열선·통풍은 주행 중에도 허용이다. 쓰기 직전 안전 게이트와 F2 검증을 그대로 거치고, 결과로 같은 알림을 갱신한다.
     * 실패하면 같은 버튼을 남겨 다시 누를 수 있다.
     */
    fun turnOffClimate(key: Int) {
        // 프로세스가 다시 떠서 대상을 모르면 실행하지 않고 오래된 알림을 지운다.
        val cmds = climateOffBook[key] ?: run { notifier.cancelCleanup(key); return }
        val seats = cmds.map { it.seat }.distinct()
        if (rejectStale(key, seats)) return
        if (!buttonGate.tryBegin(seats)) { JbLog.i(TAG, "열선 끄기 연타 무시: ${seats.joinToString { it.label }}"); return }
        scope.launch {
            try {
                val results = cmds.map { runOne(it, "열선 끄기") }
                for (n in engine.onClimateOffResult(key, results)) handle(merger.merge(ButtonNoticeMerger.Kind.CLIMATE, n) ?: n, fromButton = true)
                syncEngineState()
            } finally { buttonGate.end(seats) }
        }
    }

    /** 버튼이 오래된 알림의 것이면(그 좌석 문이 다시 열려 다음 손님이 탐) 쓰지 않고 알림을 갱신한다. true 면 막았다. */
    private fun rejectStale(key: Int, seats: List<SeatId>): Boolean {
        if (!ButtonGuard.isStale(buttonEpisodes[key].orEmpty(), engine::episodeOf)) return false
        JbLog.w(TAG, "오래된 알림의 버튼 거부: 알림 $key ${seats.joinToString { it.label }}")
        climateOffBook.remove(key); windowCloseBook.remove(key); adultBook.remove(key); buttonEpisodes.remove(key)
        handle(engine.onButtonStale(key, seats).let { merger.merge(ButtonNoticeMerger.Kind.STALE, it) ?: it }, fromButton = true)
        return true
    }

    /**
     * 알림 [key] 의 [창문 닫기] 버튼(F9-3). 착좌 신호가 없는 좌석의 열린 창문을 운전자가 확인한 뒤 닫는다.
     * 쓰기 직전 안전 게이트(문 열림 거부)와 닫는 도중 감시(F9-1: 문 열림·착좌·벨트 체결이면 멈춤)를 그대로 거친다.
     */
    fun closeWindows(key: Int) {
        val cmds = windowCloseBook[key] ?: run { notifier.cancelCleanup(key); return }
        val seats = cmds.map { it.seat }.distinct()
        if (rejectStale(key, seats)) return
        // 눌렀을 때 벨트를 맨 사람이 있거나 문이 열려 있으면 닫지 않는다(착좌 신호가 없는 좌석은 벨트가 착좌 신호다).
        val blocked = cmds.firstNotNullOfOrNull { c -> engine.windowCloseBlock(c.seat, gateway.snapshot.value)?.let { c.seat to it } }
        if (blocked != null) {
            JbLog.w(TAG, "창문 닫기 버튼 거부 ${blocked.first.label}: ${blocked.second}")
            windowCloseBook.remove(key)
            handle(engine.onWindowCloseBlocked(key, blocked.first, blocked.second).let { merger.merge(ButtonNoticeMerger.Kind.WINDOW, it) ?: it }, fromButton = true)
            return
        }
        if (!buttonGate.tryBegin(seats)) { JbLog.i(TAG, "창문 닫기 연타 무시: ${seats.joinToString { it.label }}"); return }
        // F13-2: 옆자리에 여전히 사람이 있어도 운전자가 직접 눌렀으면 닫는다(그 좌석 자체 조건은 위·안전 게이트가 본다). 기록만 남긴다.
        cmds.forEach { c -> engine.neighborPresent(c.seat, gateway.snapshot.value)?.let { m -> JbLog.i(TAG, "창문 닫기 버튼 ${c.seat.label}: 옆자리(${m.label})에 사람 있음 — 운전자가 직접 눌러 닫음") } }
        scope.launch {
            try {
                val results = cmds.map { runOne(it, "창문 닫기") }
                for (n in engine.onWindowCloseResult(key, results)) handle(merger.merge(ButtonNoticeMerger.Kind.WINDOW, n) ?: n, fromButton = true)
                syncEngineState()
            } finally { buttonGate.end(seats) }
        }
    }

    /**
     * 알림 [key] 의 [어른이 탔어요] 버튼(F8-3, F12-4). 이번 운행만 그 아이 좌석을 일반 안내로 다룬다. 창문 잠금은 운전자가
     * P·정차에서 직접 누른 경우에만, 탑승 전 원래 풀려 있었으면 푼다(이 쓰기만 SafetyGate 에 driverAction). 문구는 그 결과에 맞춘다.
     * 버튼은 화면 제한·P 가 아닐 때 숨는다(F10-4·F12-fix). 오래된 알림(그 좌석 문이 다시 열림·운행 종료)이나 프로세스가 다시 뜬 뒤에는 실행하지 않는다.
     */
    fun adultAboard(key: Int) {
        val seat = adultBook[key] ?: run { notifier.cancelCleanup(key); return }
        val episode = buttonEpisodes[key]?.get(seat)
        adultBook.remove(key); buttonEpisodes.remove(key)           // 연타는 두 번째부터 위에서 걸러진다
        val n = if (episode == null) engine.onButtonStale(key, listOf(seat)) else engine.onAdultAboard(key, seat, episode, gateway.snapshot.value)
        JbLog.i(TAG, "어른이 탔어요: ${seat.label} → ${n.text}")
        handle(n, fromButton = true)
        // F12-4: P·정차에서 눌렀고 탑승 전에 풀려 있었으면 잠금을 원래대로 돌린다. 이 쓰기만 SafetyGate 에 driverAction 을 넘긴다.
        if (episode == null) return
        val unlock = engine.adultUnlockCommand(seat, gateway.snapshot.value) ?: return
        if (!buttonGate.tryBegin(listOf(seat))) return
        scope.launch {
            try {
                val r = runOne(unlock, "어른이 탔어요 잠금 해제", driverAction = true)
                handle(engine.onAdultUnlockResult(key, r), fromButton = true)
                syncEngineState()
            } finally { buttonGate.end(listOf(seat)) }
        }
    }

    /**
     * 속도 권한(CAR_SPEED)을 지금 물어도 되는가(F10-2): 아직 안 물었고, 정차 상태(화면 편집 제한이 없음)일 때만.
     * 거부돼도 다시 묻지 않는다. 설정의 [권한 허용]은 사용자가 직접 누르는 요청이라 이 제한을 받지 않는다.
     */
    fun shouldAskSpeedPermission(): Boolean = !store.loadSpeedPermissionAsked() && !_state.value.uxRestricted

    /** 권한 요청을 띄웠다(결과와 무관하게 자동으로 다시 묻지 않는다). */
    fun onSpeedPermissionAsked() = store.saveSpeedPermissionAsked()

    /** 권한 요청 결과. 허용됐으면 속도 구독을 다시 시도한다. */
    fun onSpeedPermissionResult(granted: Boolean) {
        JbLog.i(TAG, "속도 권한 결과: ${if (granted) "허용" else "거부"}")
        if (granted) gateway.retrySpeed()
    }

    // ── 설정 ─────────────────────────────────────────────────
    /** 화면 편집 제한 중이면 설정을 바꾸지 않는다(F6-3). 화면도 잠그지만 알림·다른 경로로 들어와도 막는다. */
    private fun editBlocked(what: String): Boolean {
        if (UxPolicy.canEdit(_state.value.uxRestricted)) return false
        JbLog.i(TAG, "설정 편집 거부($what): ${UxPolicy.LOCK_MESSAGE}")
        return true
    }

    override fun addItem(name: String, condition: ItemCondition, seat: SeatId?) {
        if (editBlocked("물건 추가")) return
        if (name.isBlank()) return
        if (condition == ItemCondition.SEAT && (seat == null || seat.isDriver)) return
        val item = CarryItem(System.currentTimeMillis(), name.trim(), condition, if (condition == ItemCondition.SEAT) seat else null)
        val items = _state.value.items + item
        store.save(items); engine.items = items
        _state.update { it.copy(items = items) }
    }

    override fun toggleQuickItem(preset: QuickItems.Preset) {
        if (editBlocked("자주 쓰는 물건")) return
        val items = QuickItems.toggle(_state.value.items, preset, System.currentTimeMillis())
        store.save(items); engine.items = items
        _state.update { it.copy(items = items) }
    }

    override fun removeItem(id: Long) {
        if (editBlocked("물건 삭제")) return
        val items = _state.value.items.filter { it.id != id }
        store.save(items); engine.items = items
        _state.update { it.copy(items = items) }
    }

    override fun setQuietPolicy(p: ExitMessageRules.QuietPolicy) {
        if (editBlocked("빈 안내")) return
        store.saveQuietPolicy(p); engine.quietPolicy = p
        _state.update { it.copy(quietPolicy = p) }
    }

    override fun toggleManaged(seat: SeatId) {
        if (editBlocked("관리 좌석")) return
        if (seat.isDriver) return
        val cur = _state.value.managedSeats
        val next = if (seat in cur) cur - seat else cur + seat
        store.saveManagedSeats(next); engine.managedSeats = next
        _state.update { it.copy(managedSeats = next) }
    }

    override fun toggleChildSeat(seat: SeatId) {
        if (editBlocked("키즈락 좌석")) return
        if (seat !in CabinRoutineEngine.CHILD_SEAT_CANDIDATES) return
        // 신호가 없어 관리하지 않는 좌석은 아이 좌석으로 고를 수 없다(F9-3). 이미 아이 좌석이면 끄는 것만 허용한다.
        if (_state.value.seatModes[seat] == SeatSignalMode.NONE && seat !in _state.value.childSeats) return
        val cur = _state.value.childSeats
        val next = if (seat in cur) cur - seat else cur + seat
        store.saveChildSeats(next); engine.childSeats = next
        // 뒤늦게 지정한 좌석도 예전 좌석 전용 물건을 한 번 복사한다(F11-V). 해제했다 다시 지정하면 완료 기록이 있어 하지 않는다.
        if (seat in next && store.copyLegacyItemsOnce(setOf(seat)) > 0) {
            val profiles = store.loadChildProfiles()
            engine.childProfiles = profiles
            _state.update { it.copy(childSeats = next, childProfiles = profiles) }
            JbLog.i(TAG, "키즈락 좌석 지정: ${seat.label} 예전 좌석 전용 물건을 기본 물건으로 복사")
        } else _state.update { it.copy(childSeats = next) }
    }

    override fun setTaxiMode(on: Boolean) {
        if (editBlocked("택시 모드")) return
        store.saveTaxiMode(on); engine.taxiMode = on
        _state.update { it.copy(taxiMode = on) }
    }

    /** 아이 좌석 설정 하나를 바꿔 저장한다. 정차 중에만(편집 제한). 물건 이름은 로그에 남기지 않는다. */
    private fun editProfile(seat: SeatId, what: String, change: (ChildProfile) -> ChildProfile) {
        if (editBlocked(what)) return
        if (seat !in CabinRoutineEngine.CHILD_SEAT_CANDIDATES) return
        val cur = _state.value.childProfiles
        val next = cur + (seat to change(cur[seat] ?: ChildProfile()))
        if (next == cur) return
        store.saveChildProfiles(next); engine.childProfiles = next
        LogRedactor.setNames(next.values.map { it.name })
        _state.update { it.copy(childProfiles = next) }
        JbLog.i(TAG, "$what: ${seat.label} 저장")
    }

    override fun setChildName(seat: SeatId, name: String) {
        val clean = ChildProfiles.cleanName(name)
        editProfile(seat, "아이 이름 ${LogRedactor.marker(clean)}") { it.copy(name = clean) }
    }

    override fun addChildItem(seat: SeatId, day: java.time.DayOfWeek?, name: String) {
        val item = ChildProfiles.cleanItem(name) ?: return
        editProfile(seat, "아이 물건 추가") { p ->
            val list = if (day == null) p.baseItems else p.dayItems[day].orEmpty()
            if (list.size >= ChildProfiles.MAX_ITEMS_PER_LIST || list.any { it == item }) p
            else if (day == null) p.copy(baseItems = list + item) else p.copy(dayItems = p.dayItems + (day to (list + item)))
        }
    }

    override fun removeChildItem(seat: SeatId, day: java.time.DayOfWeek?, name: String) {
        editProfile(seat, "아이 물건 삭제") { p ->
            if (day == null) p.copy(baseItems = p.baseItems - name)
            else p.copy(dayItems = p.dayItems + (day to (p.dayItems[day].orEmpty() - name)))
        }
    }

    override fun setTelemetry(on: Boolean) {
        // 켜기만 정차 중에 한다. 끄기(옵트아웃)는 주행 중에도 막지 않는다(UxPolicy.canSetTelemetry).
        if (!UxPolicy.canSetTelemetry(on, _state.value.uxRestricted) && editBlocked("익명 통계")) return
        store.saveTelemetry(on)
        telemetry.setEnabled(on)
        _state.update { it.copy(telemetryOn = on) }
        shownMonth = ""          // 미리 보기·사본을 지금 값으로 다시 만든다
        syncEngineState()
        JbLog.i(TAG, "익명 사용 통계: ${if (on) "켬(로그 출력만, 전송 없음)" else "끔 — 집계 사본 삭제"}")
    }

    override fun setBoardingVoice(on: Boolean) {
        if (editBlocked("탑승 멘트")) return
        store.saveBoardingVoice(on); engine.boardingVoice = on
        _state.update { it.copy(boardingVoice = on) }
    }

    override fun setExitVoice(on: Boolean) {
        if (editBlocked("하차 멘트")) return
        store.saveExitVoice(on); engine.exitVoice = on
        _state.update { it.copy(exitVoice = on) }
    }

    override fun setRoadsideRearCheck(on: Boolean) {
        if (editBlocked("뒤 차 확인")) return
        store.saveRoadsideRearCheck(on); engine.roadsideRearCheck = on
        _state.update { it.copy(roadsideRearCheck = on) }
    }

    override fun setRearVentOnBoard(on: Boolean) {
        if (editBlocked("뒷좌석 통풍")) return
        store.saveRearVentOnBoard(on); engine.rearVentOnBoard = on
        _state.update { it.copy(rearVentOnBoard = on) }
    }

    override fun setKidsLockOn(on: Boolean) {
        if (editBlocked("키즈락 모드")) return
        store.saveKidsLockOn(on); engine.kidsLockOn = on
        _state.update { it.copy(kidsLockOn = on) }
        JbLog.i(TAG, "키즈락 모드: ${if (on) "켬" else "끔"}")
    }

    override fun setReportMode(mode: DriverReportMode) {
        if (editBlocked("운전자 보고")) return
        store.saveReportMode(mode); engine.reportMode = mode
        _state.update { it.copy(reportMode = mode) }
        JbLog.i(TAG, "운전자 보고: ${mode.label}")
    }

    override fun setDriverWindowNotice(on: Boolean) {
        if (editBlocked("운전석 창문 안내")) return
        store.saveDriverWindowNotice(on); engine.driverWindowNotice = on
        _state.update { it.copy(driverWindowNotice = on) }
    }

    override fun setCabinHeatNotice(on: Boolean) {
        if (editBlocked("실내 온도 안내")) return
        store.saveCabinHeatNotice(on); engine.cabinHeatNotice = on
        _state.update { it.copy(cabinHeatNotice = on) }
    }

    override fun speakTest() = announcer.speak("자리봄 음성 안내입니다. 동승자가 내리면 이렇게 알려 드릴게요.")
}