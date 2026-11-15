package com.jaribom.app.core

/**
 * core 패키지는 Android/Pleos SDK 에 의존하지 않는 순수 Kotlin 이다.
 * → JVM 단위 테스트로 상태머신·규칙을 검증하고, PT 에서 "로직이 어디 있는지"를 한 곳으로 설명할 수 있다.
 */

/** 좌석 식별자. Pleos SeatValue(ROW_1_LEFT …)와 1:1 로 대응한다. */
enum class SeatId(val label: String, val isDriver: Boolean) {
    ROW_1_LEFT("운전석", true),
    ROW_1_RIGHT("조수석", false),
    ROW_2_LEFT("뒷좌석 왼쪽", false),
    ROW_2_RIGHT("뒷좌석 오른쪽", false);

    /** 열 번호(1열·2열) */
    val row: Int get() = if (name.startsWith("ROW_1")) 1 else 2

    companion object {
        val passengers: List<SeatId> = entries.filter { !it.isDriver }

        /** 같은 열의 다른 좌석(운전석 제외, F13-2). 조수석은 같은 열에 운전석뿐이라 비어 있다. */
        fun rowMates(seat: SeatId): List<SeatId> = entries.filter { it != seat && !it.isDriver && it.row == seat.row }
    }
}

enum class Gear { PARK, REVERSE, NEUTRAL, DRIVE, UNKNOWN }

/** 좌석 하나의 "읽기" 상태. null 은 차량이 값을 주지 않음(미지원/미확인). */
data class SeatState(
    val occupied: Boolean? = null,
    val beltBuckled: Boolean? = null,
    val doorOpen: Boolean? = null,
    /** 창문 열림 정도 0(닫힘)~100(완전 열림). Pleos 는 min~max 범위를 주므로 어댑터에서 %로 정규화한다. */
    val windowOpenPercent: Int? = null,
    /** 열선 0=꺼짐, 1~3 단계 */
    val heaterLevel: Int? = null,
    /** 통풍 0=꺼짐, 1~3 단계 */
    val ventilationLevel: Int? = null,
    /** 창문 잠금(뒷좌석 창문 스위치 잠금). null = 읽지 못함(미지원) → 아이 좌석 모드는 안내만 한다. */
    val windowLocked: Boolean? = null,
)

/** 공조(존 단위가 아니라 전체 공조로 단순화. 에뮬레이터에 2열 독립 공조가 없기 때문) */
data class HvacState(
    val powerOn: Boolean? = null,
    val targetTempC: Float? = null,
    /** 실내 온도. Pleos HVAC.getHvacTemperatureCurrent 로 읽는다. */
    val cabinTempC: Float? = null,
)

/** 어댑터가 모아서 넘겨주는 차량 전체 스냅샷 */
data class VehicleSnapshot(
    val seats: Map<SeatId, SeatState> = SeatId.entries.associateWith { SeatState() },
    val hvac: HvacState = HvacState(),
    val gear: Gear = Gear.UNKNOWN,
    /** 차속 m/s(PERF_VEHICLE_SPEED). null = 신호 없음(권한 거부·에뮬레이터) → 정차 판정은 기어로 떨어진다([VehicleMotion]). */
    val speedMps: Float? = null,
    /** 속도가 [VehicleMotion.STOP_MPS] 이하로 내려온 시각(유지 시간 계산용). 빠르거나 속도 없음이면 null. */
    val slowSinceMs: Long? = null,
    val timestampMs: Long = 0L,
) {
    fun seat(id: SeatId): SeatState = seats[id] ?: SeatState()
    /** 기어가 P 인지만. 음성·정차 판정은 [VehicleMotion] 을 쓴다. */
    val isParked: Boolean get() = gear == Gear.PARK
}

/** 날씨 입력(기상청 API 또는 Fake). null = 알 수 없음 */
data class WeatherInfo(
    val rainingNow: Boolean? = null,
    val rainWithinHour: Boolean? = null,
    val outdoorTempC: Float? = null,
    val fetchedAtMs: Long = 0L,
)

/**
 * 사용자가 미리 등록한 "챙길 물건". 조건이 맞을 때만 멘트에 붙는다.
 * [seat] 는 [ItemCondition.SEAT](이 좌석에서만)일 때 그 좌석. 다른 조건이면 null.
 */
data class CarryItem(
    val id: Long,
    val name: String,
    val condition: ItemCondition,
    val seat: SeatId? = null,
)

/** 저장된 옛 값 WITH_PASSENGER(동승자 있을 때)는 ALWAYS 로 읽는다(ItemStore). */
enum class ItemCondition(val label: String) {
    ALWAYS("항상"),
    RAIN("비 올 때"),
    HOT("더울 때"),
    COLD("추울 때"),
    SEAT("이 좌석에서만"),
}

/**
 * 앱이 차에 내리는 "쓰기" 명령의 전부다. 동승석 창문 닫기·창문 잠금(아이 좌석)과 시트 열선·통풍뿐이다.
 * 창문 잠금은 창문 도메인이다(Window.setWindowLock, 권한 CONTROL_CAR_WINDOWS). 도어 잠금·차일드락과는 무관하다.
 *
 * 원칙(팀 결정 11-02): 운전 기능·도어 열림/닫힘(잠금·차일드락 포함)은 앱에 제어 능력이 없다.
 * 권한·명령·호출을 추가하지 않는다. "막는" 것이 아니라 애초에 만들 수 없게 둔다.
 * 이 목록은 VehicleCapabilityTest 의 허용 목록으로 고정돼 있고, 권한은 매니페스트 테스트, SDK 호출은 코드 검색 테스트가 지킨다.
 * 운전석 대상은 SafetyGate 에서 거부된다.
 */
sealed class VehicleCommand {
    abstract val seat: SeatId
    data class SetWindow(override val seat: SeatId, val openPercent: Int) : VehicleCommand() {
        /** 지금 위치([current], 모르면 100%로 봄)보다 더 닫는 명령인지. SafetyGate 와 닫는 도중 감시([WindowAbortReason])가 같은 식을 쓴다. */
        fun isClosing(current: Int?): Boolean = openPercent < (current ?: 100)
    }
    data class SetSeatHeater(override val seat: SeatId, val level: Int) : VehicleCommand()
    data class SetSeatVentilation(override val seat: SeatId, val level: Int) : VehicleCommand()
    /** 아이 좌석 창문 잠금. 탑승 때 잠그고, 하차 때 탈 때의 원래 상태로 돌린다. */
    data class SetWindowLock(override val seat: SeatId, val locked: Boolean) : VehicleCommand()
}

/** 명령 하나를 실제로 실행해 본 결과. 운전자 보고 멘트는 이 결과만 보고 만든다(실행 안 된 일을 했다고 말하지 않음). */
data class CommandResult(val cmd: VehicleCommand, val status: Status, val why: String = "") {
    /** [ABORTED]: 창문을 닫는 도중 문 열림·착좌·벨트로 멈춘 것(F9-1). 성공도 실패도 아니고 자동 재개하지 않는다. why 에 원인 이름(DOOR·SEAT·BELT). */
    enum class Status { OK, REJECTED, FAILED, ABORTED }
}

/** 엔진이 바깥(ViewModel)으로 내보내는 이벤트 */
sealed class RoutineEvent {
    data class Boarded(val seat: SeatId) : RoutineEvent()
    /** 하차하려고 문이 열림 — 동승자가 아직 듣고 있는 시점 */
    data class DoorOpened(val seat: SeatId) : RoutineEvent()
    /** 문이 닫혀 하차 확정 */
    data class Alighted(val seat: SeatId) : RoutineEvent()
    data class Speak(val text: String) : RoutineEvent()
    /**
     * 명령 실행 요청. ViewModel 은 명령마다 [SafetyGate] 를 쓰기 직전에 다시 검사하고, 실패하면 1회 재시도한 뒤
     * 결과를 [CabinRoutineEngine.onJobFinished] 로 돌려준다. [seat] 가 있으면 하차 정리 작업이다.
     * [preset] 이면 [seat] 의 탑승 프리셋이다. 실행 직전 [CabinRoutineEngine.presetStillValid] 로 다시 확인하고,
     * 결과는 [CabinRoutineEngine.onPresetFinished] 로 돌려준다.
     */
    data class Execute(
        val jobId: Long,
        val commands: List<VehicleCommand>,
        val reason: String,
        val seat: SeatId? = null,
        val preset: Boolean = false,
    ) : RoutineEvent()
    data class Log(val text: String) : RoutineEvent()
    /**
     * 하차 정리 시스템 알림. 같은 [key] 는 같은 알림을 덮어쓴다.
     * [final] = false 면 진행 안내, true 면 실제로 한 일의 결과다.
     * [failed] 가 있으면 알림에 [다시 시도] 버튼을 붙인다. [title] 이 없으면 "{좌석} 하차 정리".
     * [plain] 이면 하차 정리 결과가 아닌 단독 알림(잠금 실패·아이 좌석 확인 등)이라 월 누적 한 줄을 붙이지 않는다.
     */
    data class CleanupNotice(
        val key: Int,
        val seats: List<SeatId>,
        val text: String,
        val final: Boolean,
        val failed: Map<SeatId, List<VehicleCommand>> = emptyMap(),
        val title: String? = null,
        val plain: Boolean = false,
        /**
         * [열선 끄기] 버튼 대상(F9-2). 남은 물건·아이 좌석·택시 모드 알림에서 그 좌석 열선·통풍이 켜져 있을 때만 있다.
         * 열선·통풍 끄기 명령뿐이다(창문은 없다). 비어 있으면 버튼이 없다. 자동으로 끄지 않는다.
         */
        val climateOff: List<VehicleCommand> = emptyList(),
        /** [창문 닫기] 버튼 대상(F9-3). 착좌 신호가 없는 좌석의 열린 창문. 운전자가 눌러야 닫는다. */
        val windowClose: List<VehicleCommand.SetWindow> = emptyList(),
        /** 버튼 대상 좌석의 좌석 에피소드(알림을 만든 때). 누를 때 달라졌으면 오래된 알림이라 실행하지 않는다([ButtonGuard]). */
        val buttonEpisodes: Map<SeatId, Int> = emptyMap(),
        /** [어른이 탔어요] 버튼 대상 아이 좌석(F8-3). 아이 좌석 모드 탑승 확인 알림에만 있다. */
        val adultSeat: SeatId? = null,
    ) : RoutineEvent()
}