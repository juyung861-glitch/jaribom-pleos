package com.jaribom.app.vehicle

import com.jaribom.app.core.VehicleCommand
import com.jaribom.app.core.VehicleSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 차량과 앱 사이의 유일한 통로.
 *  - mock 플레이버: FakeVehicleGateway (디버그 패널이 값을 바꾼다)
 *  - pleos 플레이버: PleosVehicleGateway (Vehicle SDK 2.3.2)
 * 엔진·UI 는 이 인터페이스만 알기 때문에 SDK 를 바꿔 끼워도 로직이 바뀌지 않는다.
 */
interface VehicleGateway {
    /** 최신 차량 상태. 구독 콜백이 올 때마다 갱신된다. */
    val snapshot: StateFlow<VehicleSnapshot>

    /** 연결 상태 텍스트(UI 표시용): "연결됨", "Vehicle Service 없음" 등 */
    val status: StateFlow<String>

    /**
     * CarUxRestrictionsManager 의 isRequiresDistractionOptimization(F6-3, 화면 편집 제한용).
     * null = 매니저 없음·읽을 수 없음. 안전 판정에는 쓰지 않는다([com.jaribom.app.core.UxPolicy]).
     */
    val uxRestricted: StateFlow<Boolean?> get() = NO_UX

    /**
     * 차량 서비스와 연결돼 값을 믿을 수 있는지(F7-2). false 인 동안의 스냅샷은 마지막 값이라 엔진에 넣지 않는다.
     * 기본은 항상 true(연결 감독이 없는 구현).
     */
    val linked: StateFlow<Boolean> get() = ALWAYS_LINKED

    /**
     * 좌석별 착좌·벨트 신호 지원 모드(F9-3). 연결 때·재연결 때 다시 판별한다. 비어 있으면 모두 [com.jaribom.app.core.SeatSignalMode.FULL].
     */
    val seatModes: StateFlow<Map<com.jaribom.app.core.SeatId, com.jaribom.app.core.SeatSignalMode>> get() = NO_MODES

    /** 좌석별 열선·통풍 지원 여부(F12-5). 비어 있으면 모두 모름(있는 것으로 봄, 뒷좌석 통풍 자동 켜기는 하지 않음). */
    val climateSupport: StateFlow<Map<com.jaribom.app.core.SeatId, com.jaribom.app.core.ClimateSupport>> get() = NO_CLIMATE

    /**
     * 움직이는 창문을 지금 위치에서 멈춘다(F9-1). 닫기를 도중에 그만둘 때만 쓴다. 기본 구현은 지원 안 함.
     * 정지 쓰기는 새 창문 명령을 만들지 않는다(권한은 기존 CONTROL_CAR_WINDOWS).
     */
    suspend fun stopWindow(seat: com.jaribom.app.core.SeatId): Result<Unit> = Result.failure(UnsupportedOperationException("창문 정지 미지원"))

    /** 차속 신호 상태(F10-2): 구독 중·권한 거부·속성 미지원. 설정 화면이 문구로 보여 준다. 기본은 모름. */
    val speedSignal: StateFlow<com.jaribom.app.core.SpeedSignal> get() = NO_SPEED

    /**
     * 착좌 반전 판정을 새로 저장한 뒤 맞이한 첫 운행인가(F10-5). 참이면 그 운행은 창문을 자동으로 닫지 않고 운전자 [창문 닫기] 버튼으로 닫는다.
     * 기본은 거짓.
     */
    val occupancyFreshlyLearned: Boolean get() = false

    /** 운행이 끝났다(F10-5): 반전 저장 직후 첫 운행 표시를 지운다. 기본 구현은 아무것도 하지 않는다. */
    fun onTripEnded() {}

    /** 속도 권한을 새로 허용받았을 때 속도 구독을 다시 시도한다(F10-2). 기본 구현은 아무것도 하지 않는다. */
    fun retrySpeed() {}

    suspend fun connect()
    suspend fun disconnect()

    /**
     * 쓰기 명령. 성공/실패를 Result 로 돌려준다.
     * 성공은 "쓰기 응답 + 다시 읽은 값이 목표와 같음"일 때만이다(A3). 응답 시간 초과·값 불일치는 실패다.
     */
    suspend fun execute(cmd: VehicleCommand): Result<Unit>
}

private val NO_UX: StateFlow<Boolean?> = MutableStateFlow<Boolean?>(null)
private val ALWAYS_LINKED: StateFlow<Boolean> = MutableStateFlow(true)
private val NO_SPEED: StateFlow<com.jaribom.app.core.SpeedSignal> = MutableStateFlow(com.jaribom.app.core.SpeedSignal.UNKNOWN)
private val NO_CLIMATE: StateFlow<Map<com.jaribom.app.core.SeatId, com.jaribom.app.core.ClimateSupport>> = MutableStateFlow(emptyMap())
private val NO_MODES: StateFlow<Map<com.jaribom.app.core.SeatId, com.jaribom.app.core.SeatSignalMode>> = MutableStateFlow(emptyMap())
