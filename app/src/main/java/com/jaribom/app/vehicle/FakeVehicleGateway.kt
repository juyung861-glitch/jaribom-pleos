package com.jaribom.app.vehicle

import com.jaribom.app.core.ConnectionSupervisor
import com.jaribom.app.core.Gear
import com.jaribom.app.core.LinkWatchdog
import com.jaribom.app.core.HvacState
import com.jaribom.app.core.MotionTracker
import com.jaribom.app.core.SeatId
import com.jaribom.app.core.SeatState
import com.jaribom.app.core.VehicleCommand
import com.jaribom.app.core.VehicleSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * SDK 없이 동작하는 가짜 차량. 디버그 패널(DebugPanel)이 아래 set* 를 호출해 Virtual Console 역할을 한다.
 * 쓰기 명령은 약간의 지연 뒤 상태에 반영되고, Pleos 게이트웨이와 같은 [WriteVerifier] 로 확인한다.
 * [writeMode] 로 지연·무응답·늦은 성공을 흉내 낼 수 있다(5차 심사 F2 테스트).
 */
class FakeVehicleGateway : VehicleGateway {

    private val _snapshot = MutableStateFlow(
        VehicleSnapshot(
            seats = SeatId.entries.associateWith {
                // 창문 잠금은 뒷좌석만 있다(Virtual Console 과 같음). 앞좌석은 null = 미지원.
                SeatState(
                    occupied = it.isDriver, beltBuckled = it.isDriver, doorOpen = false, windowOpenPercent = 0, heaterLevel = 0, ventilationLevel = 0,
                    windowLocked = if (it == SeatId.ROW_2_LEFT || it == SeatId.ROW_2_RIGHT) false else null,
                )
            },
            hvac = HvacState(powerOn = true, targetTempC = 22f, cabinTempC = 24f),
            gear = Gear.PARK,
            timestampMs = System.currentTimeMillis(),
        )
    )
    override val snapshot: StateFlow<VehicleSnapshot> = _snapshot
    override val status = MutableStateFlow("가짜 차량(mock) 연결됨")

    private val _modes = MutableStateFlow<Map<SeatId, com.jaribom.app.core.SeatSignalMode>>(emptyMap())
    override val seatModes: StateFlow<Map<SeatId, com.jaribom.app.core.SeatSignalMode>> = _modes
    /** 좌석별 신호 모드를 흉내 낸다(F9-3 시험·시연용) */
    fun setSeatModes(m: Map<SeatId, com.jaribom.app.core.SeatSignalMode>) { _modes.value = m }

    private val _ux = MutableStateFlow<Boolean?>(null)
    override val uxRestricted: StateFlow<Boolean?> = _ux
    /** CarUx 제한 신호를 흉내 낸다. null = 매니저 없음 */
    fun setUxRestricted(v: Boolean?) { _ux.value = v }

    /**
     * 천천히 움직이는 창문(F9-1 시험용). 지정하면 창문 쓰기는 [stepMs] 마다 [stepPct]% 씩 움직이는 독립 작업이 되어,
     * 쓰기 요청을 취소해도 창문은 계속 움직인다(실차처럼). [stopWindow] 만 멈춘다.
     */
    data class WindowMove(val stepPct: Int, val stepMs: Long)
    var windowMove: WindowMove? = null
    /** [stopWindow] 를 부른 횟수 */
    var stopWindowCalls: Int = 0
        private set
    private val moveScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val moving = mutableMapOf<SeatId, Job>()

    /** 정지 요청 뒤에도 더 움직이는 칸 수(읽기와 쓰기 사이 지연·늦은 정지를 흉내) */
    var stopOvershootSteps: Int = 0
    /** 정지 쓰기가 실패하는 모드 */
    var stopFails: Boolean = false
    /** 처음 N번의 정지는 응답만 성공하고 창문은 계속 움직인다(F10-5 정지 확인 시험용) */
    var stopIgnoredCalls: Int = 0

    override suspend fun stopWindow(seat: SeatId): Result<Unit> {
        stopWindowCalls++
        if (stopFails) return Result.failure(IllegalStateException("(모의) 정지 쓰기 실패"))
        if (stopIgnoredCalls > 0) { stopIgnoredCalls--; return Result.success(Unit) }
        val job = moving.remove(seat)
        val mv = windowMove
        if (stopOvershootSteps > 0 && mv != null) {
            val cur = _snapshot.value.seat(seat).windowOpenPercent
            job?.cancel()
            if (cur != null) {
                repeat(stopOvershootSteps) {
                    delay(mv.stepMs)
                    val next = maxOf(0, (_snapshot.value.seat(seat).windowOpenPercent ?: cur) - mv.stepPct)
                    updateSeat(seat) { it.copy(windowOpenPercent = next) }
                }
            }
        } else job?.cancel()
        return Result.success(Unit)
    }

    private fun startMove(seat: SeatId, target: Int, mv: WindowMove) {
        moving.remove(seat)?.cancel()
        moving[seat] = moveScope.launch {
            while (true) {
                val cur = _snapshot.value.seat(seat).windowOpenPercent ?: break
                if (cur == target) break
                delay(mv.stepMs)
                val next = if (cur > target) maxOf(target, cur - mv.stepPct) else minOf(target, cur + mv.stepPct)
                updateSeat(seat) { it.copy(windowOpenPercent = next) }
            }
        }
    }

    /** 남은 거절 횟수. 차량이 쓰기를 거절한 것처럼 실패한다(재시도 없음 → 알림의 [다시 시도] 시연, J3). */
    var failRemaining: Int = 0

    /** 쓰기 응답 흉내. NORMAL 300ms, DELAY [modeDelayMs] 뒤 응답, NO_RESPONSE 응답·반영 없음, LATE_SUCCESS 응답 시간 초과 뒤 [modeDelayMs] 에 반영 */
    enum class WriteMode { NORMAL, DELAY, NO_RESPONSE, LATE_SUCCESS }
    var writeMode: WriteMode = WriteMode.NORMAL
    var modeDelayMs: Long = 1_000L

    // ── 연결 흉내(F6-4): 앞의 N번은 연결 실패, 서비스 사망 흉내. 실제 게이트웨이와 같은 [ConnectionSupervisor] 를 쓴다 ──
    /** connect 가 실패하는 남은 횟수. 그 뒤 성공한다. */
    var connectFailures: Int = 0
    /** 재시도 대기 흉내(테스트에서 시간을 건너뛰거나 대기 중 동작을 끼워 넣는다) */
    var sleepFn: suspend (Long) -> Unit = { delay(it) }
    /** 지금 등록돼 있는 리스너(구독) 수. 재연결 뒤에도 1이어야 한다(겹쳐 등록하지 않음). */
    var listenerCount: Int = 0
        private set
    @Volatile private var linkDown = false

    private val supervisor = ConnectionSupervisor(
        connectOnce = {
            if (connectFailures > 0) { connectFailures--; status.value = "연결 재시도 중"; false }
            else { listenerCount += 1; linkDown = false; _linked.value = true; watchdog.reset(); status.value = "가짜 차량(mock) 연결됨"; true }
        },
        teardown = { listenerCount = 0 },
        sleep = { sleepFn(it) },
    )

    override suspend fun connect() = supervisor.connectWithRetry()
    override suspend fun disconnect() { supervisor.stop() }
    /** 차량 서비스가 죽은 것을 흉내 낸다. 쓰기는 "연결 없음"으로 실패하고, 구독을 해제한 뒤 다시 붙는다. */
    suspend fun simulateServiceDeath() { linkDown = true; _linked.value = false; supervisor.onServiceDied() }

    private val _linked = MutableStateFlow(true)
    override val linked: StateFlow<Boolean> = _linked

    // ── 탐침 흉내(F7-2): Pleos 읽기가 연속 N번 실패하는 모드. 실제 게이트웨이와 같은 [LinkWatchdog] 로 사망을 판정한다 ──
    /** 앞으로 실패할 탐침 횟수(응답 없음·실패·null 을 흉내) */
    var probeFailures: Int = 0
    /** 탐침으로 사망을 판정한 횟수 */
    var deathsDetected: Int = 0
        private set
    private val watchdog = LinkWatchdog()

    /** 탐침 1번. 연속 실패가 기준에 닿으면 사망으로 보고 재연결 경로를 탄다. */
    suspend fun pollOnce() {
        val r = if (probeFailures > 0) { probeFailures--; LinkWatchdog.ProbeResult.TIMEOUT } else LinkWatchdog.ProbeResult.OK
        if (watchdog.record(r)) { deathsDetected++; simulateServiceDeath() }
    }

    override suspend fun execute(cmd: VehicleCommand): Result<Unit> =
        if (linkDown) Result.failure(IllegalStateException("연결 없음(재연결 중)")) else WriteVerifier.run(cmd, _snapshot, write = { simulateWrite(cmd) }, readBack = {})

    /** SDK 쓰기 흉내. 응답이 오면 끝난다(NO_RESPONSE 는 끝나지 않음). */
    private suspend fun simulateWrite(cmd: VehicleCommand): Result<Unit> {
        if (failRemaining > 0) {
            failRemaining--
            delay(300)
            return Result.failure(IllegalStateException("(모의) Vehicle Service onFailed"))
        }
        when (writeMode) {
            WriteMode.NORMAL -> delay(if (windowMove != null && cmd is VehicleCommand.SetWindow) 20 else 300)     // 움직이는 창문은 요청만 받고 바로 응답
            WriteMode.DELAY, WriteMode.LATE_SUCCESS -> delay(modeDelayMs)
            WriteMode.NO_RESPONSE -> awaitCancellation()
        }
        return apply(cmd)
    }

    private fun apply(cmd: VehicleCommand): Result<Unit> {
        when (cmd) {
            is VehicleCommand.SetWindow -> {
                val mv = windowMove
                if (mv != null) startMove(cmd.seat, cmd.openPercent.coerceIn(0, 100), mv)
                else updateSeat(cmd.seat) { it.copy(windowOpenPercent = cmd.openPercent.coerceIn(0, 100)) }
            }
            is VehicleCommand.SetSeatHeater -> updateSeat(cmd.seat) { it.copy(heaterLevel = cmd.level.coerceIn(0, 3)) }
            is VehicleCommand.SetSeatVentilation -> updateSeat(cmd.seat) { it.copy(ventilationLevel = cmd.level.coerceIn(0, 3)) }
            is VehicleCommand.SetWindowLock -> {
                if (_snapshot.value.seat(cmd.seat).windowLocked == null) return Result.failure(IllegalStateException("(모의) 창문 잠금 미지원 좌석"))
                updateSeat(cmd.seat) { it.copy(windowLocked = cmd.locked) }
            }
        }
        return Result.success(Unit)
    }

    // ── 디버그 패널용 조작 ─────────────────────────────────────
    // 가짜 콘솔이 "차에서 일어난 일"을 흉내 내는 것이다(사람이 문을 열고, 운전자가 기어를 바꿈). 앱의 차량 제어가 아니다.
    // 문·기어는 이름을 simulate* 로 둬서 VehicleCapabilityTest 의 코드 검색(문·기어 set 호출 금지)과 구분한다.
    fun setOccupied(seat: SeatId, v: Boolean) = updateSeat(seat) { it.copy(occupied = v) }
    fun setBelt(seat: SeatId, v: Boolean) = updateSeat(seat) { it.copy(beltBuckled = v) }
    fun simulateDoorOpened(seat: SeatId, open: Boolean) = updateSeat(seat) { it.copy(doorOpen = open) }
    fun setWindow(seat: SeatId, pct: Int) = updateSeat(seat) { it.copy(windowOpenPercent = pct) }
    fun setHeater(seat: SeatId, lv: Int) = updateSeat(seat) { it.copy(heaterLevel = lv) }
    fun setVentilation(seat: SeatId, lv: Int) = updateSeat(seat) { it.copy(ventilationLevel = lv) }
    /** 운전자가 창문 잠금 스위치를 누른 것을 흉내 낸다(지원 좌석만). */
    fun setWindowLockSwitch(seat: SeatId, locked: Boolean) = updateSeat(seat) { if (it.windowLocked == null) it else it.copy(windowLocked = locked) }
    fun simulateGearSelected(g: Gear) = _snapshot.update { it.copy(gear = g, timestampMs = now()) }

    private val motion = MotionTracker()
    /** 차속 신호(PERF_VEHICLE_SPEED, m/s)를 넣는다. null = 신호 없음 → 기어로 판정(에뮬레이터와 같은 경로). */
    fun setSpeedMps(mps: Float?, nowMs: Long = now()) {
        val slow = motion.update(mps, nowMs)
        _snapshot.update { it.copy(speedMps = mps, slowSinceMs = slow, timestampMs = nowMs) }
    }
    fun setCabinTemp(c: Float) = _snapshot.update { it.copy(hvac = it.hvac.copy(cabinTempC = c), timestampMs = now()) }

    private fun updateSeat(seat: SeatId, f: (SeatState) -> SeatState) = _snapshot.update { s ->
        s.copy(seats = s.seats + (seat to f(s.seat(seat))), timestampMs = now())
    }
    private fun now() = System.currentTimeMillis()
}