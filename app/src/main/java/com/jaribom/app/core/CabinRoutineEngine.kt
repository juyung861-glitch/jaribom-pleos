package com.jaribom.app.core

/**
 * 캐빈 루틴 엔진: 트리거(차량 신호) → 탑승 기록 → 하차 정리.
 *
 * 흐름
 *  Boarded(seat)    : 관리 좌석이면 "탔었다"를 기록 → 환영 멘트(설정 [boardingVoice]) → 탑승 프리셋(바깥 ≤3℃ 열선 2단, ≥28℃ 통풍 2단)
 *  DoorOpened(seat) : 동승자에게 물건·날씨 안내(ExitMessageRules.passengerMessage, 설정 [exitVoice]). 기어와 상관없이 바로 말한다.
 *  Alighted(seat)   : 그 좌석을 정리한다(팀 결정 11-02). 열린 창문은 닫고, 켜진 열선·통풍은 끈다.
 *                     이미 닫혀 있거나 꺼져 있으면 명령을 만들지 않는다. 탈 때 값으로 되돌리지 않는다.
 *                     실행 쪽은 잠깐 기다린 뒤 [cleanupStillValid] 로 다시 확인하고, 그사이 다시 앉았으면 [cancelCleanup] 으로 취소한다.
 *  onJobFinished    : 실제로 실행한 결과를 돌려받아 운전자 보고(driverReport)와 알림([RoutineEvent.CleanupNotice])을 낸다.
 *
 * 동시 하차 묶음(심사 3차: 반복 음성·알림 줄이기)
 *  - 처음 문이 열린 뒤 [batchWindowMs] 안에 다른 좌석 문이 열리면 같은 묶음이다. 동승자 안내는 묶음당 1번.
 *  - 묶음 안 좌석의 정리가 모두 끝나면 운전자 보고·알림을 1번으로 합친다(알림 키 = 묶음 ID).
 *    "운전석 창문이 열려 있어요"·실내 온도 안내도 묶음당 1번이다.
 *
 * 기어(4차 심사 A1)
 *  - 열선·통풍·창문(끼임 방지만) 제어는 기어와 상관없이 바로 실행한다. 기어 D 에 둔 채 내려 줘도 그 자리에서 정리한다.
 *  - 기어가 보는 것은 운전자 보고 방식뿐이다: P 면 음성 + 알림, P 가 아니면 알림만(운전 중 말 걸지 않기).
 *
 * 아이 좌석 모드([childSeats], 뒷좌석만)
 *  - 탑승 확정: 그 좌석의 원래 창문 잠금을 기억하고 풀려 있으면 잠근다(잠금을 읽을 수 있을 때만. 못 읽으면 안내만).
 *  - 문 열림: 아이 말투 안내([ExitMessageRules.childMessage]).
 *  - 하차 확정: 기억해 둔 원래 잠금으로 돌린 뒤 빈 좌석 정리(창문 닫기, 열선·통풍 끄기). 보고는 실제로 한 것만.
 *  - 아이 이름(선택, F11-3)은 기기 안 설정에만 있고 호격으로 첫 문장에 붙인다. 통계·로그·알림 기록에는 남기지 않는다([LogRedactor]). 나이는 받지 않는다.
 *
 * 남은 물건 감지(무거운 가방이 남아 착좌가 안 꺼짐)
 *  - 이 좌석은 벨트가 풀린 채 착좌가 켜져 있고 그 문이 열렸다 닫혔는데, 이 좌석 문이 열린 시각에서
 *    [LEFTOVER_PAIR_WINDOW_MS] 안에 다른 좌석이 하차 확정됐으면 운전자에게만 알린다(5차 심사 F3).
 *    그 하차의 정리 보고 묶음이 아직 열려 있으면 한 문장으로 합친다(음성 1번, 알림 1건). 닫혔으면 따로 알린다.
 *    그 좌석에 "이 좌석에서만" 물건이 있으면 이름을 넣는다. 음성은 P 일 때만, 알림은 항상.
 *  - 아이 좌석 보조 판정: 아이 좌석 문이 열렸다 닫힌 뒤(착좌 유지·벨트 풀림) P→D 로 출발하면 알림만 한다.
 *  - 남은 물건으로 표시된 좌석은 운행 종료 판정([isTripIdle])에서 빈 좌석으로 본다.
 *  - 벨트를 맨 좌석은 사람으로 본다. 벨트 값이 없으면(null) 사람과 짐을 가릴 수 없어 알리지 않고 기록만 한다.
 *  - 그 좌석은 하차가 아니므로 빈 좌석 정리를 하지 않는다(사람일 수 있음). 착좌가 꺼질 때까지 좌석당 1번만 알린다.
 *
 * 탑승 프리셋 안전장치(A2)
 *  - 프리셋 명령은 좌석을 달고 나간다. 실행 직전 그 좌석이 아직 탑승 중인지 [presetStillValid] 로 다시 본다.
 *  - 하차가 확정되면 그 좌석의 대기 프리셋을 지운다. 쓰는 사이에 내렸으면 켠 것을 되돌린다([onPresetFinished]).
 */
class CabinRoutineEngine(
    private val machine: BoardingStateMachine = BoardingStateMachine(),
    private val thresholds: ExitMessageRules.Thresholds = ExitMessageRules.Thresholds(),
    var quietPolicy: ExitMessageRules.QuietPolicy = ExitMessageRules.QuietPolicy.SHORT_PHRASE,
    var autoPresetOnBoard: Boolean = true,
    /** 이 시간 안에 문이 열린 좌석들은 한 번에 내린 것으로 묶는다 */
    private val batchWindowMs: Long = 3_000L,
) {
    companion object {
        /** 하차 뒤 이만큼 지나 나오는 보고에는 "아까 내리신 …" 맥락을 붙인다(A10) */
        const val LATE_REPORT_MS = 30_000L
        /** 아이 좌석으로 고를 수 있는 좌석. 운전석·조수석은 고를 수 없다. */
        val CHILD_SEAT_CANDIDATES: Set<SeatId> = setOf(SeatId.ROW_2_LEFT, SeatId.ROW_2_RIGHT)
        /** 동승자 안내 음성이 재생되지 못했을 때 하차 보고 알림 끝에 붙는 한 줄(F9-4) */
        const val SPEECH_FAILED_LINE = "(동승자 안내 음성은 재생되지 못했어요)"
        /** 남은 물건 알림의 키(좌석별). 하차 묶음 알림 키(1부터 증가)와 겹치지 않게 떨어뜨린다. */
        const val LEFTOVER_NOTICE_KEY_BASE = 900_000
        /** 아이 좌석 창문 잠금 실패 알림의 키(좌석별) */
        const val LOCK_FAIL_NOTICE_KEY_BASE = 910_000
        /** 문을 열었다 닫은 기록을 이만큼만 기다린다(다른 좌석 하차 확정을 기다리는 시간). */
        const val LEFTOVER_PAIR_MS = 60_000L
        /** 이 좌석 문이 열린 시각과 다른 좌석의 하차 확정 시각이 이 안이면 같은 하차로 본다(차례로 내림). */
        const val LEFTOVER_PAIR_WINDOW_MS = 10_000L
        /** 아이 좌석 보조 판정 알림의 키(좌석별) */
        const val CHILD_CHECK_NOTICE_KEY_BASE = 920_000
        /** 재연결 뒤 창문 확인 알림의 키(좌석별, F8-2) */
        const val RECOVERY_NOTICE_KEY_BASE = 930_000
        /** 아이 좌석 모드 탑승 확인 알림의 키(좌석별, F8-3) */
        const val CHILD_MODE_NOTICE_KEY_BASE = 940_000
        /** SafetyGate 가 문이 열려 있어 창문 닫기를 거부할 때의 이유(보고에서 같은 말을 두 번 하지 않으려고 본다) */
        const val DOOR_OPEN_REASON = "문이 열려 있음"
    }

    /** 화면의 '최근 하차 정리' 카드에 쓰는 마지막 하차 정리 결과(묶음 단위) */
    data class AlightResult(
        val seat: SeatId,
        val atMs: Long,
        val planned: List<VehicleCommand>,
        val results: List<CommandResult> = emptyList(),
        val report: String = "",
        val finished: Boolean = false,
        val seats: List<SeatId> = listOf(seat),
    )

    /**
     * 누적 기록(4차 심사 A4). 엔진은 시작 뒤 늘어난 만큼만 센다. 월별 합계·저장은 RoutineController·ItemStore 가 한다.
     *  - [rides]: 관리 좌석에서 탔다 내린 횟수
     *  - [restores]: 정리한 하차(명령이 1개 이상 실제로 된 하차 묶음) 수
     *  - [reminders]: 실제로 음성으로 나간 챙김 안내(물건·날씨) 수. 하차 멘트를 끄면 세지 않는다.
     *  - 세부: 창문 닫음, 열선 끔, 통풍 끔, 비 오는 날 안내
     *  - 5차 심사 F4: [childLocks] 아이 좌석 창문 잠금(실제로 잠길 때), [leftoverAlerts] 남은 물건 알림,
     *    [childReminders] 아이 말투 안내(실제로 나간 것, 챙김 안내에도 포함)
     */
    data class Stats(
        val rides: Int = 0,
        val restores: Int = 0,
        val reminders: Int = 0,
        val windowsClosed: Int = 0,
        val heatersOff: Int = 0,
        val ventsOff: Int = 0,
        val rainReminders: Int = 0,
        val childLocks: Int = 0,
        val leftoverAlerts: Int = 0,
        val childReminders: Int = 0,
        /** 6차 F6-7: 정리가 일부라도 실패한 하차 묶음 수 */
        val restoreFailures: Int = 0,
        /** 6차 F6-8: 택시 모드로 알린 "두고 내림 확인" 수(아이 좌석 알림은 [leftoverAlerts]) */
        val taxiLeftoverAlerts: Int = 0,
        /** 6차 F6-9: 반복 알림 상한([AlertPolicy])에 걸려 보내지 않은 알림 수 */
        val suppressedAlerts: Int = 0,
        /** 6차 F9-1: 창문을 닫다가 문 열림·착좌·벨트로 멈춘 횟수. 대신한 일에도 실패에도 넣지 않는다(통계 JSON 13키에도 없음). */
        val windowAborts: Int = 0,
        /** 6차 F9-2: 알림의 [열선 끄기] 버튼으로 끈 횟수. 통계 JSON 13키에는 없다. */
        val leftoverHeatOffs: Int = 0,
        /** 6차 F9-4: 음성이 말해지지 못한 횟수(엔진 오류·초기화 실패·타임아웃·포커스 거부). 통계 JSON 13키에는 없다. */
        val speechFailures: Int = 0,
        /** 7차 F10-3: 알림의 [어른이 탔어요] 를 누른 횟수(실행한 것). 저장에만 있고 통계 v2 JSON 에 나간다. */
        val adultTaps: Int = 0,
        /** 7차 F10-3: 벨트+문 모드 알림의 [창문 닫기] 를 눌러 실행한 횟수(성공·중단·실패 모두). */
        val windowCloseTaps: Int = 0,
    ) {
        operator fun plus(o: Stats) = Stats(
            rides + o.rides, restores + o.restores, reminders + o.reminders,
            windowsClosed + o.windowsClosed, heatersOff + o.heatersOff, ventsOff + o.ventsOff, rainReminders + o.rainReminders,
            childLocks + o.childLocks, leftoverAlerts + o.leftoverAlerts, childReminders + o.childReminders,
            restoreFailures + o.restoreFailures, taxiLeftoverAlerts + o.taxiLeftoverAlerts, suppressedAlerts + o.suppressedAlerts,
            windowAborts + o.windowAborts, leftoverHeatOffs + o.leftoverHeatOffs, speechFailures + o.speechFailures,
            adultTaps + o.adultTaps, windowCloseTaps + o.windowCloseTaps,
        )
        operator fun minus(o: Stats) = Stats(
            rides - o.rides, restores - o.restores, reminders - o.reminders,
            windowsClosed - o.windowsClosed, heatersOff - o.heatersOff, ventsOff - o.ventsOff, rainReminders - o.rainReminders,
            childLocks - o.childLocks, leftoverAlerts - o.leftoverAlerts, childReminders - o.childReminders,
            restoreFailures - o.restoreFailures, taxiLeftoverAlerts - o.taxiLeftoverAlerts, suppressedAlerts - o.suppressedAlerts,
            windowAborts - o.windowAborts, leftoverHeatOffs - o.leftoverHeatOffs, speechFailures - o.speechFailures,
            adultTaps - o.adultTaps, windowCloseTaps - o.windowCloseTaps,
        )

        /** "이번 달 대신한 일" 카드의 합계(창문 닫음 + 열선 끔 + 통풍 끔 + 비 오는 날 안내 + 키즈락 잠금 + 두고 내림 확인). 지난달과 비교할 때 쓴다. */
        val doneTotal: Int get() = windowsClosed + heatersOff + ventsOff + rainReminders + childLocks + taxiLeftoverAlerts

        /** 예: "창문 닫음 7 · 열선 끔 5 · 키즈락 잠금 2 · 비 오는 날 안내 3". 통풍·키즈락 잠금·두고 내림 확인은 0이면 넣지 않는다. */
        fun doneSummary(): String = buildList {
            add("창문 닫음 $windowsClosed")
            add("열선 끔 $heatersOff")
            if (ventsOff > 0) add("통풍 끔 $ventsOff")
            if (childLocks > 0) add("키즈락 잠금 $childLocks")
            if (taxiLeftoverAlerts > 0) add("두고 내림 확인 $taxiLeftoverAlerts")
            add("비 오는 날 안내 $rainReminders")
        }.joinToString(" · ")
    }

    /** 동시 하차 묶음. 동승자 안내·운전자 보고·알림을 묶음 단위로 1번씩 낸다. */
    private class Batch(val id: Int, val startMs: Long) {
        /** 문이 열린 좌석(묶음 구성원) */
        val seats = linkedSetOf<SeatId>()
        /** 정리가 끝난(또는 할 일이 없던) 좌석의 결과 */
        val done = linkedMapOf<SeatId, List<CommandResult>>()
        val planned = mutableListOf<VehicleCommand>()
        val unmanaged = linkedSetOf<SeatId>()
        /** 이 하차와 같은 때 남은 물건으로 본 좌석과 그 문장. 보고에 한 문장으로 합친다. */
        val leftovers = linkedMapOf<SeatId, String>()
        /** 이 하차의 동승자 안내 음성이 재생되지 못했는가(F9-4). 보고 알림 끝에 한 줄을 붙인다. */
        var speechFailed = false
        /** 같은 열 옆자리에 사람이 있어 창문을 자동으로 닫지 않은 좌석(F13-2). 보고에 [창문 닫기] 버튼과 안내를 남긴다. */
        val neighborHeld = linkedSetOf<SeatId>()
        /** 키즈락 열에서 옆자리 신호를 확인할 수 없어 창문을 자동으로 닫지 않은 좌석(F14-1). 같은 [창문 닫기] 버튼, 다른 문장. */
        val neighborUnknown = linkedSetOf<SeatId>()
    }

    /** 동승자가 탔던 관리 좌석. 내리면 정리 대상이 된다. */
    private val riders = mutableSetOf<SeatId>()
    /** 하차 정리 중인 좌석 */
    private val cleaning = mutableSetOf<SeatId>()
    /** 하차 정리가 취소된(= 같은 사람이 다시 앉은) 좌석. 다시 탑승이 확정돼도 인사하지 않는다. */
    private val resumed = mutableSetOf<SeatId>()
    /** 내보냈지만 아직 결과가 오지 않은 탑승 프리셋(작업 ID → 좌석). 하차가 확정되면 지운다. */
    private val presets = mutableMapOf<Long, SeatId>()
    private val batches = mutableListOf<Batch>()
    /** 동승자 안내로 낸 문장 → 그 묶음 id(F9-4). 음성이 실패했을 때 어느 하차의 보고에 한 줄을 붙일지 찾는다. */
    private val passengerSpeech = mutableMapOf<String, Int>()
    /** 동승자 안내로 낸 문장 → 그 문장을 듣는 좌석(F8-1). 재생 차례에 그 좌석 문이 닫힌 지 오래됐는지 본다. */
    private val passengerSpeechSeat = mutableMapOf<String, SeatId>()
    /** 좌석별 문이 닫힌 시각(열림→닫힘 순간, F8-1) */
    private val doorClosedAt = mutableMapOf<SeatId, Long>()
    /** 이미 낸 하차 보고 알림(묶음 id → 알림). 동승자 안내 실패가 보고보다 늦게 오면 같은 알림에 한 줄을 붙여 다시 낸다. */
    private val reportedNotices = mutableMapOf<Int, RoutineEvent.CleanupNotice>()
    /** 이번 운행에서 기본 멘트("두고 가시는 물건은 없나요?")를 말했는지. 붙일 문장이 없으면 운행당 1번만 말한다(A9). */
    private var baseSaidThisTrip = false
    /** 이번 운행에서 [어른이 탔어요]를 누른 아이 좌석(F8-3). 운행이 끝나면 아이 좌석 모드로 돌아온다. */
    private val adultThisTrip = mutableSetOf<SeatId>()

    /**
     * 화면 "키즈락 모드"(F12-4, 기본 켜짐). 끄면 키즈락 좌석으로 지정한 좌석도 일반 좌석으로 동작한다(잠금·아이 말투·탑승 알림 없음).
     * 이미 잠가 둔 좌석은 내릴 때 원래 잠금으로 돌린다([originalLock] 은 그대로 남는다).
     */
    var kidsLockOn: Boolean = true

    /** 지금 이 좌석을 키즈락(아이) 좌석으로 다루는가: 키즈락 모드가 켜져 있고, 지정 좌석이고, 이번 운행에 어른 표시가 없을 때 */
    fun isChildNow(seat: SeatId) = kidsLockOn && seat in childSeats && seat !in adultThisTrip

    /** 좌석별 마지막 하차 확정 시각. 늦게 나오는 보고(다시 시도 등)에 "아까 내리신 …" 맥락을 붙일 때 쓴다(A10). */
    private val alightedAtMs = mutableMapOf<SeatId, Long>()
    /** 아이 좌석의 탑승 때 원래 창문 잠금. 하차 정리에서 되돌리고 나면 지운다(실패하면 다시 시도를 위해 남긴다). */
    private val originalLock = mutableMapOf<SeatId, Boolean>()
    /** 남은 물건 판정용: 좌석별 지난 문 상태, 착좌 중 문이 열린 시각, 열렸다 닫힌 기록(열린 시각), 하차 문이 열린 시각, 이미 알린 좌석 */
    private val prevDoor = mutableMapOf<SeatId, Boolean?>()
    private val doorOpenWhileSeatedAt = mutableMapOf<SeatId, Long>()
    private val doorCycledAt = mutableMapOf<SeatId, Long>()
    private val leftoverAlerted = mutableSetOf<SeatId>()
    /** 아이 좌석 보조 판정: 착좌 중 문이 열렸다 닫힌 아이 좌석(다음 P→D 출발 때 한 번 본다) */
    private val childDoorCycled = mutableSetOf<SeatId>()
    /** 벨트 풀림 순간 뒤 차 경고(F14-4): 좌석별 지난 벨트 값, 경고를 한 좌석 에피소드, 경고 시각 */
    private val prevBelt = mutableMapOf<SeatId, Boolean?>()
    private val beltWarnEpisode = mutableMapOf<SeatId, Int>()
    private val beltWarnAtMs = mutableMapOf<SeatId, Long>()
    private var prevGear: Gear? = null
    private var nextJobId = 1L
    private var nextBatchId = 1
    private var lastTimestampMs = 0L

    var items: List<CarryItem> = emptyList()
    var weather: WeatherInfo = WeatherInfo()
    /** 사용자가 관리하도록 켜 둔 좌석. 운전석은 넣어도 무시된다. */
    var managedSeats: Set<SeatId> = SeatId.passengers.toSet()
    /** 설정 "탑승 멘트". 끄면 탑승 인사를 말하지 않는다(운전자 보고·알림과는 별개). */
    var boardingVoice: Boolean = true
    /** 설정 "하차 멘트". 끄면 문이 열릴 때의 동승자 안내를 말하지 않는다(운전자 보고·알림과는 별개). */
    var exitVoice: Boolean = true

    /**
     * 설정 "운전자 보고"(F12-1). 엔진 기본값은 예전 동작(모두)이고, 앱은 컨트롤러가 저장값(없으면 [DriverReportMode.DEFAULT] = 경고만)을 넣는다.
     * 경고는 이 설정과 상관없이 항상 알린다.
     */
    var reportMode: DriverReportMode = DriverReportMode.ALL
    /**
     * 설정 "도로변 하차 때 뒤 차 확인"(F12-6 대체안, 기본 꺼짐). D·N 에서 문이 열린 하차 안내 맨 앞에 한 문장을 더한다.
     * 동승자 안내와 같은 규칙(하차 멘트 설정, 달리는 중 생략, 묶음당 1번, 하루 상한 없음)을 따른다.
     */
    var roadsideRearCheck: Boolean = false
    /** 설정 "운전석 창문 안내"(비·1시간 안 비, 기본 켜짐, F12-1) */
    var driverWindowNotice: Boolean = true
    /** 설정 "실내 온도 안내"(30℃ 이상 환기, 기본 켜짐, F12-1) */
    var cabinHeatNotice: Boolean = true

    /**
     * 경고 없이 음성으로만 낸 보고(문장 → 묶음 id, F12-2). 음성이 끝까지 나가면 알림을 남기지 않고,
     * 실패하거나 포커스를 잃어 말하지 못하면 그때 [reportedNotices] 의 알림을 낸다.
     */
    private val pendingSpokenReports = mutableMapOf<String, Int>()
    /** 설정 "아이 좌석". [CHILD_SEAT_CANDIDATES] 밖의 좌석은 넣어도 빠진다. */
    var childSeats: Set<SeatId> = emptySet()
        set(v) { field = v.filter { it in CHILD_SEAT_CANDIDATES }.toSet() }

    /**
     * 설정 "택시 모드"(6차 F6-8, 기본 꺼짐). 켜면 아이 좌석 보조 판정(문 열림→닫힘 뒤 착좌 유지·벨트 풀림, P→D 출발 때 알림만)을
     * 운전석을 뺀 모든 동승 좌석에 적용한다. 아이 좌석으로 지정된 좌석은 기존 아이 좌석 문구를 쓴다.
     */
    var taxiMode: Boolean = false

    /**
     * 하차 한마디 문장 선택기(F11-1). 기본은 항상 0번 문장([PhrasePicker.LEGACY])이라 예전 동작과 같다.
     * 앱은 컨트롤러가 무작위 선택기를 넣고, 테스트는 시드가 고정된 [kotlin.random.Random] 을 넣는다.
     */
    var phrasePicker: PhrasePicker = PhrasePicker.LEGACY

    /** 아이 좌석별 설정(F11-2·F11-3): 기본·요일별 물건, 이름. 설정에서 바꾸면 컨트롤러가 넣는다. */
    var childProfiles: Map<SeatId, ChildProfile> = emptyMap()

    /** 요일 판정용 시계(F11-2). 기기 로컬 시간. 테스트에서 고정 시계를 넣는다. */
    var clock: java.time.Clock = java.time.Clock.systemDefaultZone()

    /** 로그에 남길 문장에서 설정된 아이 이름을 가린다(F11-3). 전역 로그 필터([LogRedactor])와 별개로 엔진이 만드는 로그 이벤트도 이름이 없게 한다. */
    private fun hideNames(text: String): String =
        childProfiles.values.map { it.name.trim() }.filter { it.isNotEmpty() }.sortedByDescending { it.length }.fold(text) { acc, n -> acc.replace(n, LogRedactor.MASK) }

    /**
     * 좌석 신호 기록(F11-4). 좌석별 최근 문 열림 10번에 착좌가 함께 잡혔는지 본다. 설정 화면에만 보여 주고 음성·알림에는 쓰지 않는다.
     * 저장·복원은 컨트롤러가 한다(앱을 다시 켜도 이어서 센다).
     */
    var signalQuality: SeatSignalQuality = SeatSignalQuality()

    /** 동승 좌석별 신호 상태(설정 화면 표시용) */
    fun signalStatuses(): Map<SeatId, SeatSignalStatus> = SeatId.passengers.associateWith { signalQuality.status(it, modeOf(it)) }

    /** 이 아이 좌석이 오늘 챙길 물건(오늘 요일 → 기본, 최대 3개) */
    private fun childTodayItems(seat: SeatId) = ChildProfiles.spokenItems(childProfiles[seat], ChildProfiles.today(clock))

    /** 이 좌석 하차 음성을 아이 말투(반말)로 하는가. 택시 모드는 아이 좌석으로 지정돼 있어도 존댓말 고정이다(F11-1). */
    private fun childVoice(seat: SeatId) = isChildNow(seat) && !taxiMode

    /** 반복 알림 상한(6차 F6-9). 숫자는 [AlertPolicy] 에 있다. 테스트에서 하루 기준 날짜를 바꿔 끼운다. */
    var alertLimiter: AlertLimiter = AlertLimiter()

    /** [다시 시도] 를 누른 좌석은 이전 알림을 지웠으므로, 다시 실패하면 10분 안이어도 새로 알린다. */
    fun allowFailureReportAgain(seats: Collection<SeatId>) = seats.forEach { alertLimiter.forgetFailures(it) }

    var lastResult: AlightResult? = null; private set
    var stats: Stats = Stats(); private set

    /** 지금 동승자가 탄 것으로 기록된 관리 좌석 */
    val ridingSeats: Set<SeatId> get() = riders.toSet()
    fun phaseOf(seat: SeatId) = machine.phaseOf(seat)

    /**
     * 시간 경과만으로 판정이 바뀔 수 있는 상태면 true. RoutineController 가 이 동안 마지막 스냅샷을 주기적으로 다시 넣는다.
     * (탑승 확정 대기, 문 열림 대기, 아직 다른 좌석이 합류할 수 있는 하차 묶음)
     */
    val needsTick: Boolean
        get() = machine.needsTick || batches.any { lastTimestampMs - it.startMs < batchWindowMs }
    fun isManaged(seat: SeatId) = !seat.isDriver && seat in managedSeats && modeOf(seat) != SeatSignalMode.NONE

    /**
     * 좌석별 신호 모드(F9-3). 연결 때 게이트웨이가 판별해 넣는다. 비어 있으면 모두 [SeatSignalMode.FULL].
     *  - BELT_DOOR: 착좌 신호가 없는 좌석. 벨트 체결을 착좌로 보고, 창문은 자동으로 닫지 않는다([windowClose] 버튼).
     *  - NONE: 신호가 없어 관리하지 않는다.
     */
    var seatModes: Map<SeatId, SeatSignalMode> = emptyMap()
    private fun modeOf(seat: SeatId) = seatModes[seat] ?: SeatSignalMode.FULL

    /**
     * 창문을 자동으로 닫지 않고 운전자 [창문 닫기] 버튼으로만 닫는 상태(F10-5). 착좌 반전 판정을 새로 저장한 직후의 첫 운행에 켠다
     * (새 해석이 아직 검증되지 않았으므로). 컨트롤러가 켜고 운행이 끝나면 끈다.
     */
    var windowButtonOnly: Boolean = false

    /** 이 좌석 창문은 자동으로 닫지 않는다: 착좌 신호가 없는 좌석(F9-3)이거나 [windowButtonOnly] 일 때 */
    private fun isWindowButtonOnly(seat: SeatId) = windowButtonOnly || modeOf(seat) == SeatSignalMode.BELT_DOOR

    /** 실제로 제어하는 좌석: 사용자가 켜 둔 좌석에서 신호 부족(NONE) 좌석을 뺀다. 쓰기 직전 안전 게이트가 쓴다. */
    val controlledSeats: Set<SeatId> get() = managedSeats.filter { !it.isDriver && modeOf(it) != SeatSignalMode.NONE }.toSet()

    /** 엔진이 보는 스냅샷. 착좌 미지원 좌석은 벨트를 착좌로 보고, 신호 부족 좌석은 아무 신호도 없는 것으로 본다. */
    private fun view(s: VehicleSnapshot): VehicleSnapshot {
        if (seatModes.isEmpty()) return s
        return s.copy(seats = s.seats.mapValues { (id, st) ->
            when (modeOf(id)) {
                SeatSignalMode.FULL -> st
                SeatSignalMode.BELT_DOOR -> st.copy(occupied = st.beltBuckled == true)
                SeatSignalMode.NONE -> st.copy(occupied = null, beltBuckled = null, doorOpen = null)
            }
        })
    }

    /** 탑승 기록·진행 중인 정리 중 하나라도 남아 있으면 true. [endTrip] 이 정리할 것이 있는지 본다. */
    val tripActive: Boolean
        get() = riders.isNotEmpty() || cleaning.isNotEmpty() || batches.isNotEmpty() ||
            SeatId.passengers.any { machine.phaseOf(it) != BoardingStateMachine.Phase.EMPTY }

    /** 남은 물건으로 알린 좌석(착좌가 꺼질 때까지). */
    val leftoverSeats: Set<SeatId> get() = leftoverAlerted.toSet()

    /**
     * 운행 종료 판정용: P 이고 동승석이 모두 비었는지. 남은 물건으로 표시된 좌석은 빈 좌석으로 본다
     * (가방 때문에 착좌가 안 꺼져도 운행이 끝날 수 있게, 5차 심사 F3).
     */
    fun isTripIdle(snapshot: VehicleSnapshot): Boolean {
        val v = view(snapshot)
        return VehicleMotion.voiceAllowed(v) && SeatId.passengers.none { v.seat(it).occupied == true && it !in leftoverAlerted }
    }

    /** 하차 정리를 실행해도 되는지. 하차 확정 뒤 다시 앉았으면(BOARDING/RIDING) false. */
    fun cleanupStillValid(seat: SeatId): Boolean = machine.phaseOf(seat) == BoardingStateMachine.Phase.EMPTY

    /** 탑승 프리셋을 실행해도 되는지. 하차가 확정돼 지워졌거나 그 좌석이 탑승 중(RIDING)이 아니면 false. */
    fun presetStillValid(jobId: Long, seat: SeatId): Boolean =
        presets[jobId] == seat && machine.phaseOf(seat) == BoardingStateMachine.Phase.RIDING

    /**
     * 하차 정리를 실행하지 않고 버린다(같은 사람이 다시 앉음). 다시 탑승이 확정되면 인사 없이 다음 하차 때 정리한다.
     * [snapshot] 을 주면 남은 묶음 좌석의 보고를 마저 낸다.
     */
    fun cancelCleanup(seat: SeatId, snapshot: VehicleSnapshot? = null): List<RoutineEvent> {
        cleaning.remove(seat)
        batches.forEach { it.seats.remove(seat); it.done.remove(seat) }
        resumed += seat
        val out = mutableListOf<RoutineEvent>(RoutineEvent.Log("하차 정리 취소: ${seat.label}에 다시 앉음 — 정리하지 않음"))
        if (snapshot != null) out += checkBatches(snapshot)
        return out
    }

    fun onSnapshot(raw: VehicleSnapshot): List<RoutineEvent> {
        val snapshot = view(raw)
        lastTimestampMs = snapshot.timestampMs
        // 신호 품질은 차량이 준 원래 값으로 본다(착좌 미지원 좌석의 합성 착좌가 아니라).
        for (id in SeatId.passengers) raw.seat(id).let { signalQuality.observe(id, it.doorOpen, it.occupied, raw.timestampMs) }
        val out = mutableListOf<RoutineEvent>()
        val changes = machine.update(snapshot)
        machine.drainNotes().forEach { out += RoutineEvent.Log(it) }
        for (ev in changes) {
            when (ev) {
                is RoutineEvent.Boarded -> handleBoarded(ev.seat, snapshot, out)
                is RoutineEvent.DoorOpened -> handleDoorOpened(ev.seat, snapshot, out)
                is RoutineEvent.Alighted -> handleAlighted(ev.seat, snapshot, out)
                else -> {}
            }
        }
        // 취소 뒤 다시 앉았다가 탑승 확정 전에 떠났으면 '같은 사람' 표시를 지운다(다음 사람에게는 인사한다).
        resumed.removeAll { machine.phaseOf(it) == BoardingStateMachine.Phase.EMPTY && it !in cleaning }
        out += checkLeftovers(snapshot)
        out += checkBeltRelease(snapshot)
        out += checkBatches(snapshot)
        return out
    }

    /**
     * F14-4: 키즈락 모드로 탄 좌석의 벨트가 체결→풀림으로 바뀌는 순간, 기어 D·N 이고 정차(속도 값이 있고 정차 기준 이하)이면
     * 문이 열리기 전에 "뒤에 차 오는지 꼭 보고 내려."만 먼저 말한다. 좌석 에피소드당 1번(벨트를 다시 채웠다 풀어도 반복하지 않는다).
     * 말하지 않는 경우: 속도 null·벨트 null(미지원)·주행 중·P·택시 모드·어른 전환 좌석·키즈락 모드 끔·하차 멘트 끔 — 그때는 F13-1(문 열릴 때)로 넘어간다.
     * 문장은 [passengerSpeechSeat] 에 등록하지 않는다(문이 닫혀 있어 F8-1 이 오래된 안내로 버리지 않도록). 음성 큐·포커스·묵음은 기존 동승자 안내와 같다.
     */
    private fun checkBeltRelease(snapshot: VehicleSnapshot): List<RoutineEvent> {
        val out = mutableListOf<RoutineEvent>()
        for (seat in SeatId.passengers) {
            val belt = snapshot.seat(seat).beltBuckled
            val was = prevBelt[seat]
            prevBelt[seat] = belt
            if (was != true || belt != false) continue
            if (!childVoice(seat) || seat !in riders) continue
            if (snapshot.gear != Gear.DRIVE && snapshot.gear != Gear.NEUTRAL) continue
            if (snapshot.speedMps == null || !VehicleMotion.isStopped(snapshot)) continue
            val episode = alertLimiter.episodeOf(seat)
            if (beltWarnEpisode[seat] == episode) continue
            if (!exitVoice || !VehicleMotion.passengerVoiceAllowed(snapshot)) {
                out += RoutineEvent.Log("${seat.label} 벨트 풀림 — 하차 멘트가 꺼져 있거나 음성 금지라 뒤 차 경고 생략")
                continue
            }
            beltWarnEpisode[seat] = episode
            beltWarnAtMs[seat] = snapshot.timestampMs
            out += RoutineEvent.Log("벨트 풀림: ${seat.label} · ${snapshot.gear} 정차 — 문 열리기 전 뒤 차 경고 먼저")
            out += RoutineEvent.Speak(ExitMessageRules.REAR_CHECK_CHILD)
        }
        return out
    }

    /**
     * 남은 물건 판정. 착좌가 켜진 채(RIDING) 그 좌석 문이 열렸다 닫혔고, 이 좌석 문이 열린 시각에서
     * [LEFTOVER_PAIR_WINDOW_MS] 안에 다른 좌석이 하차 확정됐으면 — 벨트가 풀려 있을 때만 — 운전자에게 알린다.
     * 아이 좌석은 상대 하차가 없어도 다음 P→D 출발 때 한 번 본다(보조 판정, 알림만).
     */
    private fun checkLeftovers(snapshot: VehicleSnapshot): List<RoutineEvent> {
        val out = mutableListOf<RoutineEvent>()
        val now = snapshot.timestampMs
        for (seat in SeatId.passengers) {
            val s = snapshot.seat(seat)
            val door = s.doorOpen
            val was = prevDoor[seat]
            prevDoor[seat] = door
            if (door == false && was == true) doorClosedAt[seat] = now
            // 그 좌석 문이 열리면 새 좌석 에피소드 — 알림 상한이 풀린다(F7-3, 택시 다음 손님). 착좌 여부와 상관없이 본다.
            if (door == true && was != true) alertLimiter.onSeatDoorOpened(seat)
            if (s.occupied != true) {
                leftoverAlerted.remove(seat); doorOpenWhileSeatedAt.remove(seat); doorCycledAt.remove(seat); childDoorCycled.remove(seat)
                continue
            }
            if (machine.phaseOf(seat) != BoardingStateMachine.Phase.RIDING) continue
            if (door == true && was != true) doorOpenWhileSeatedAt[seat] = now
            if (door == false && was == true) doorOpenWhileSeatedAt.remove(seat)?.let {
                doorCycledAt[seat] = it
                if (isChildNow(seat) || taxiMode) childDoorCycled += seat
            }
        }
        doorCycledAt.entries.removeAll { now - it.value > LEFTOVER_PAIR_MS }
        for ((seat, openedAt) in doorCycledAt.toList()) {
            if (seat in leftoverAlerted) { doorCycledAt.remove(seat); continue }
            val partner = SeatId.passengers.firstOrNull { p ->
                p != seat && alightedAtMs[p]?.let { kotlin.math.abs(it - openedAt) <= LEFTOVER_PAIR_WINDOW_MS } == true
            } ?: continue
            doorCycledAt.remove(seat)
            when (snapshot.seat(seat).beltBuckled) {
                true -> out += RoutineEvent.Log("${seat.label}: 문을 열었다 닫았지만 벨트를 매고 있음 — 사람으로 봄")
                null -> out += RoutineEvent.Log("${seat.label}: 벨트 신호 없음 — 남은 물건 판정 안 함(${partner.label} 하차와 같은 때 문 열림)")
                false -> {
                    leftoverAlerted += seat
                    childDoorCycled.remove(seat)
                    if (!alertLimiter.tryLeftover(seat)) {
                        stats = stats.copy(suppressedAlerts = stats.suppressedAlerts + 1)
                        out += RoutineEvent.Log("남은 물건 알림 억제: ${seat.label} 이번 운행에서 이미 알림")
                        continue
                    }
                    stats = stats.copy(leftoverAlerts = stats.leftoverAlerts + 1)
                    out += RoutineEvent.Log("남은 물건: ${seat.label} 착좌 유지·벨트 풀림·문 열림→닫힘, ${partner.label} 하차 확정")
                    out += leftoverAlert(seat, partner, snapshot)
                }
            }
        }
        out += checkChildDeparture(snapshot)
        return out
    }

    /** 남은 물건 문장. 그 좌석의 "이 좌석에서만" 물건이 있으면 이름을 넣는다. */
    private fun leftoverSentence(seat: SeatId): String {
        val names = items.filter { it.condition == ItemCondition.SEAT && it.seat == seat }.joinToString(", ") { it.name }
        return if (names.isEmpty()) "${seat.label}에 무언가 남아 있는 것 같아요. 확인해 주세요."
        else "${seat.label}에 $names${subjectParticle(names)} 남아 있는 것 같아요."
    }

    /** 받침이 있으면 "이", 없으면 "가". 한글이 아니면 "이(가)". */
    private fun subjectParticle(word: String): String {
        val c = word.lastOrNull() ?: return "이"
        if (c !in '가'..'힣') return "이(가)"
        return if ((c - '가') % 28 != 0) "이" else "가"
    }

    /** 남은 물건 알림. 상대 좌석의 보고 묶음이 아직 열려 있으면 거기에 합치고, 없으면 따로 낸다. */
    private fun leftoverAlert(seat: SeatId, partner: SeatId, snapshot: VehicleSnapshot): List<RoutineEvent> {
        val text = leftoverSentence(seat)
        val b = batchOf(partner)
        if (b != null) {
            b.leftovers[seat] = text
            return listOf(RoutineEvent.Log("남은 물건을 하차 묶음 #${b.id} 보고에 합침: ${seat.label}"))
        }
        val out = mutableListOf<RoutineEvent>()
        if (VehicleMotion.voiceAllowed(snapshot)) out += RoutineEvent.Speak(text)
        else out += RoutineEvent.Log("정차(P)가 아니어서 남은 물건은 알림만: $text")
        out += RoutineEvent.CleanupNotice(
            LEFTOVER_NOTICE_KEY_BASE + seat.ordinal, listOf(seat), text, final = true, title = "${seat.label} 남은 물건 확인", plain = true,
            climateOff = climateOffCommands(seat, snapshot), buttonEpisodes = episodesOf(climateOffCommands(seat, snapshot)),
        )
        return out
    }

    /** 좌석별 현재 좌석 에피소드(버튼이 오래된 알림인지 보는 데 쓴다). */
    fun episodeOf(seat: SeatId): Int = alertLimiter.episodeOf(seat)

    private fun episodesOf(cmds: List<VehicleCommand>): Map<SeatId, Int> = cmds.map { it.seat }.distinct().associateWith { alertLimiter.episodeOf(it) }

    /**
     * [어른이 탔어요] 버튼(F8-3, F12-4). 이번 운행 동안 그 아이 좌석을 일반(존댓말) 안내와 일반 남은 물건 규칙으로 다룬다.
     * 창문 잠금은 운전자가 P·정차에서 직접 누른 경우에만, 탑승 전 원래 풀려 있었을 때 컨트롤러가 [adultUnlockCommand] 로 푼다.
     * 문구는 그 결과에 맞춘다(F12-fix2): 풀 예정이면 안내만(결과는 [onAdultUnlockResult] 가 덮음), 탑승 전부터 잠겨 있었으면
     * 그대로 둔다는 안내, P·정차가 아니면 P에서만 푼다는 안내. [snapshot] 이 없으면(모름) 안내만 낸다.
     * 알림을 만든 뒤 그 좌석 문이 다시 열렸거나 운행이 끝났으면(오래된 알림) 무시한다.
     */
    fun onAdultAboard(key: Int, seat: SeatId, episode: Int, snapshot: VehicleSnapshot? = null): RoutineEvent.CleanupNotice {
        if (!kidsLockOn || seat !in childSeats || ButtonGuard.isStale(mapOf(seat to episode), ::episodeOf)) return onButtonStale(key, listOf(seat))
        adultThisTrip += seat
        stats = stats.copy(adultTaps = stats.adultTaps + 1)
        val line = adultLine(seat)
        val lockedNow = snapshot?.seat(seat)?.windowLocked == true
        val text = when {
            snapshot == null || !lockedNow -> line
            originalLock[seat] == true -> "$line 탑승 전부터 잠겨 있던 창문이라 그대로 둘게요. 풀려면 운전석 창문 잠금 버튼을 써 주세요."
            originalLock[seat] == false && !VehicleMotion.voiceAllowed(snapshot) ->
                "$line 창문 잠금은 P에서만 풀 수 있어요. 지금 풀려면 운전석 창문 잠금 버튼을 써 주세요."
            else -> line                                    // 곷 푼다. 결과 문구는 [onAdultUnlockResult]
        }
        return RoutineEvent.CleanupNotice(key, listOf(seat), text, final = true, title = "${seat.label} 키즈락 좌석", plain = true)
    }

    private fun adultLine(seat: SeatId) = "${seat.label}은 이번 운행만 어른 좌석으로 안내할게요."

    /**
     * [어른이 탔어요] 뒤 잠금 해제(F12-4): 이번 운행 어른 표시가 있고, 기어 P·정차이고, 탑승 전 원래 잠금이 풀려 있었는데
     * 지금 잠겨 있으면 원래 상태로 돌리는 명령. 아니면 null. 실행 쪽은 SafetyGate 에 driverAction = true 를 넘긴다(이 경로만).
     */
    fun adultUnlockCommand(seat: SeatId, snapshot: VehicleSnapshot): VehicleCommand.SetWindowLock? {
        if (seat !in adultThisTrip || !VehicleMotion.voiceAllowed(snapshot)) return null
        val orig = originalLock[seat] ?: return null
        if (orig || snapshot.seat(seat).windowLocked != true) return null
        return VehicleCommand.SetWindowLock(seat, false)
    }

    /** 잠금 해제 결과로 같은 알림 [key] 를 갱신한다. 실패해도 [다시 시도]는 없다(자동 경로가 잠금을 풀지 않도록). */
    fun onAdultUnlockResult(key: Int, r: CommandResult): RoutineEvent.CleanupNotice {
        val seat = r.cmd.seat
        val ok = r.status == CommandResult.Status.OK
        if (ok) originalLock.remove(seat)                 // 원래대로 돌아갔으므로 내릴 때 되돌릴 것이 없다
        val tail = if (ok) "창문 잠금을 탑승 전처럼 풀었어요." else "창문 잠금을 풀지 못했어요. 운전석 창문 잠금 버튼으로 풀어 주세요."
        // 실패는 경고다: 제목에 실패를 드러낸다(다시 시도는 없다. 자동 경로가 잠금을 풀지 않도록).
        return RoutineEvent.CleanupNotice(
            key, listOf(seat), "${adultLine(seat)} $tail", final = true,
            title = if (ok) "${seat.label} 키즈락 좌석" else "${seat.label} 창문 잠금 해제 실패", plain = true,
        )
    }

    /** 오래된 알림의 버튼을 눌렀다: 쓰기 없이 알림을 갱신한다(버튼은 없앤다). */
    fun onButtonStale(key: Int, seats: List<SeatId>): RoutineEvent.CleanupNotice =
        RoutineEvent.CleanupNotice(key, seats, ButtonGuard.STALE_TEXT, final = true, title = "지난 하차 알림", plain = true)

    /**
     * [열선 끄기] 버튼 대상(F9-2): 그 좌석 열선·통풍이 켜져 있을 때만 끄기 명령을 만든다. 창문은 만들지 않는다.
     * 가방이 남은 좌석은 정리하지 않아 열선이 켜진 채 달리므로, 운전자가 알림에서 직접 끈다.
     */
    private fun climateOffCommands(seat: SeatId, snapshot: VehicleSnapshot): List<VehicleCommand> = buildList {
        val s = snapshot.seat(seat)
        if ((s.heaterLevel ?: 0) > 0) add(VehicleCommand.SetSeatHeater(seat, 0))
        if ((s.ventilationLevel ?: 0) > 0) add(VehicleCommand.SetSeatVentilation(seat, 0))
    }

    /**
     * [창문 닫기] 버튼을 눌렀을 때의 안전 확인(F9 리뷰). 착좌 신호가 없는 좌석은 벨트가 곧 착좌 신호라, 눌렀을 때 벨트를 맨 사람이 있거나
     * 문이 열려 있으면 닫지 않는다. 막을 이유가 없으면 null. 닫는 도중의 체결·문 열림은 [WindowAbortReason] 중단 처리가 막는다.
     * [raw] 는 게이트웨이의 원본 스냅샷이다.
     */
    fun windowCloseBlock(seat: SeatId, raw: VehicleSnapshot): String? {
        val s = raw.seat(seat)
        return when {
            s.beltBuckled == true -> "벨트를 맨 사람이 있어 창문을 닫지 않았어요."
            s.doorOpen == true -> "문이 열려 있어 창문을 닫지 않았어요."
            else -> null
        }
    }

    /**
     * 같은 열의 다른 좌석(운전석 제외)에 착좌 또는 벨트 체결이 읽히면 그 좌석(F13-2). 없거나 둘 다 null(미지원·모름)이면 null.
     * 좌석 신호 모드(F9-3)를 적용한 값으로 본다(신호 부족 좌석은 값이 없는 것으로 봄).
     */
    fun neighborPresent(seat: SeatId, snapshot: VehicleSnapshot): SeatId? {
        val v = view(snapshot)
        return SeatId.rowMates(seat).firstOrNull { val s = v.seat(it); s.occupied == true || s.beltBuckled == true }
    }

    /**
     * F14-1(fail-closed): 하차 좌석과 같은 열(운전석 제외)에 키즈락 좌석이 하나라도 있고 키즈락 모드가 켜져 있을 때(하차 좌석 자신 포함),
     * 옆자리 중 착좌·벨트가 둘 다 미지원이거나 null 인 좌석이 있으면 true. 사람이 있는지 알 수 없어 창문을 자동으로 닫지 않는다.
     * 키즈락 좌석이 없는 열·키즈락 모드 꺼짐은 F13-2 규칙 그대로(신호가 없으면 기존 동작). 원본 스냅샷과 좌석 신호 모드를 함께 본다.
     */
    fun neighborSignalUnknown(seat: SeatId, snapshot: VehicleSnapshot): Boolean {
        val mates = SeatId.rowMates(seat)
        if (!kidsLockOn || (mates + seat).none { it in childSeats }) return false
        return mates.any { m ->
            val s = snapshot.seat(m)
            modeOf(m) == SeatSignalMode.NONE || (s.occupied == null && s.beltBuckled == null)
        }
    }

    /** 창문 닫기를 막았다는 안내로 같은 알림 [key] 를 갱신한다(버튼은 없앤다). */
    fun onWindowCloseBlocked(key: Int, seat: SeatId, reason: String): RoutineEvent.CleanupNotice =
        RoutineEvent.CleanupNotice(key, listOf(seat), reason, final = true, title = "${seat.label} 창문 닫기 안 함", plain = true)

    /**
     * [창문 닫기] 버튼 결과(F9-3). 같은 알림 [key] 를 갱신한다. 닫았으면 센다. 중간에 멈췄으면 원인을, 실패하면 직접 확인을 알리고
     * 실패 때는 같은 버튼을 남긴다. 멈춘 것(ABORTED)은 자동으로 다시 닫지 않으므로 버튼도 없다.
     */
    fun onWindowCloseResult(key: Int, results: List<CommandResult>): List<RoutineEvent.CleanupNotice> {
        val r = results.firstOrNull() ?: return emptyList()
        val seat = r.cmd.seat
        stats = stats.copy(windowCloseTaps = stats.windowCloseTaps + 1)          // 눌러서 실행한 횟수(F10-3)
        return when (r.status) {
            CommandResult.Status.OK -> {
                stats = stats.copy(windowsClosed = stats.windowsClosed + 1)
                listOf(RoutineEvent.CleanupNotice(key, listOf(seat), "${seat.label} 창문을 닫았어요.", final = true, title = "${seat.label} 창문 닫기", plain = true))
            }
            CommandResult.Status.ABORTED -> {
                stats = stats.copy(windowAborts = stats.windowAborts + 1)
                val stopFail = WindowAbortReason.tail(r.why, seat.label)
                listOf(
                    RoutineEvent.CleanupNotice(
                        key, listOf(seat), "${seat.label} 창문을 닫다가 ${WindowAbortReason.phrase(r.why)} 멈췄어요.$stopFail", final = true,
                        title = "${seat.label} 창문 닫기 중단", plain = true,
                    )
                )
            }
            else -> listOf(
                RoutineEvent.CleanupNotice(
                    key, listOf(seat), "${seat.label} 창문 닫기에 실패했어요. 직접 확인해 주세요.", final = true,
                    title = "${seat.label} 창문 닫기 실패", plain = true, windowClose = listOf(r.cmd as VehicleCommand.SetWindow),
                )
            )
        }
    }

    /**
     * 재연결 뒤 창문 확인 결과(F8-2). 정지는 F9-1 문구로, 움직였을 수 있으면 확인 요청으로 알리고, 성공·변화 없음은 로그만 남긴다.
     * 이미 나간 하차 보고는 건드리지 않고 좌석별 별도 알림(키 [RECOVERY_NOTICE_KEY_BASE] + 좌석)을 낸다.
     */
    fun onWindowRecovery(verdicts: List<RecoveryVerdict>): List<RoutineEvent> {
        val out = mutableListOf<RoutineEvent>()
        for (v in verdicts) {
            val key = RECOVERY_NOTICE_KEY_BASE + v.seat.ordinal
            when (v) {
                is RecoveryVerdict.Done -> out += RoutineEvent.Log("재연결 뒤 확인: ${v.seat.label} 창문이 목표에 닿아 있음 — 정리 성공")
                is RecoveryVerdict.NoChange -> out += RoutineEvent.Log("재연결 뒤 확인: ${v.seat.label} 창문 위치 그대로 — 할 일 없음")
                is RecoveryVerdict.Stop -> {
                    val why = v.reason.name + if (v.stopFailed) WindowAbortReason.STOP_FAILED_SUFFIX else ""
                    out += RoutineEvent.Log("재연결 뒤 확인: ${v.seat.label} 창문이 움직이는 중에 ${v.reason.phrase} 정지")
                    out += onWindowCloseResult(key, listOf(CommandResult(VehicleCommand.SetWindow(v.seat, v.target), CommandResult.Status.ABORTED, why)))
                }
                is RecoveryVerdict.Notify -> {
                    out += RoutineEvent.Log("재연결 뒤 확인: ${v.seat.label} 창문이 끊긴 동안 움직였을 수 있음")
                    out += RoutineEvent.CleanupNotice(
                        key, listOf(v.seat), "${v.seat.label} 창문이 연결이 끊긴 동안 움직였을 수 있어요. 확인해 주세요.", final = true,
                        title = "${v.seat.label} 창문 확인", plain = true,
                    )
                }
            }
        }
        return out
    }

    /**
     * [열선 끄기] 버튼 결과(F9-2). 같은 알림 [key] 를 갱신한다. 성공하면 "껐어요"와 함께 버튼을 없애고 센다.
     * 하나라도 실패하면 실패 문구와 함께 같은 버튼을 남겨 다시 누를 수 있게 한다(F1 실패 보고 경로와 같은 모양).
     */
    fun onClimateOffResult(key: Int, results: List<CommandResult>): List<RoutineEvent.CleanupNotice> {
        if (results.isEmpty()) return emptyList()
        val seat = results.first().cmd.seat
        fun kinds(rs: List<CommandResult>) = rs.map { if (it.cmd is VehicleCommand.SetSeatHeater) "열선" else "통풍" }.distinct().joinToString("·")
        val failed = results.filter { it.status != CommandResult.Status.OK }
        stats = stats.copy(leftoverHeatOffs = stats.leftoverHeatOffs + 1)        // 눌러서 실행한 횟수(F10-3: 성공·실패 모두)
        if (failed.isEmpty()) {
            return listOf(RoutineEvent.CleanupNotice(key, listOf(seat), "${seat.label} ${kinds(results)}을 껐어요.", final = true, title = "${seat.label} 열선 끄기", plain = true))
        }
        return listOf(
            RoutineEvent.CleanupNotice(
                key, listOf(seat), "${seat.label} ${kinds(failed)}을 끄지 못했어요. 직접 확인해 주세요.", final = true,
                title = "${seat.label} 열선 끄기 실패", plain = true, climateOff = failed.map { it.cmd },
            )
        )
    }

    /** 아이 좌석 보조 판정: P→D 로 출발할 때, 문을 열었다 닫은 뒤 착좌 유지·벨트 풀림인 아이 좌석을 알린다(알림만, 정리 없음). */
    private fun checkChildDeparture(snapshot: VehicleSnapshot): List<RoutineEvent> {
        val departing = prevGear == Gear.PARK && snapshot.gear == Gear.DRIVE
        prevGear = snapshot.gear
        if (!departing || childDoorCycled.isEmpty()) return emptyList()
        val out = mutableListOf<RoutineEvent>()
        for (seat in childDoorCycled.toList()) {
            childDoorCycled.remove(seat)
            val s = snapshot.seat(seat)
            if (seat in leftoverAlerted || s.occupied != true) continue
            when (s.beltBuckled) {
                true -> out += RoutineEvent.Log("${seat.label} 아이 좌석: 벨트를 매고 있음 — 출발 확인 알림 없음")
                null -> out += RoutineEvent.Log("${seat.label} 아이 좌석: 벨트 신호 없음 — 출발 확인 판정 안 함")
                false -> {
                    leftoverAlerted += seat
                    val child = isChildNow(seat)
                    // 택시 모드는 손님당 1번(하루 상한 없음), 아이 좌석 보조는 하루 5번도 적용(F10-1)
                    val allowed = if (child) alertLimiter.tryAssist(seat, snapshot.timestampMs) else alertLimiter.tryTaxi(seat)
                    if (!allowed) {
                        stats = stats.copy(suppressedAlerts = stats.suppressedAlerts + 1)
                        out += RoutineEvent.Log("출발 확인 알림 억제: ${seat.label} " + if (child) "좌석 에피소드당 1번·하루 ${AlertPolicy.ASSIST_PER_DAY}번 상한" else "같은 손님에게 1번")
                        continue
                    }
                    stats = if (child) stats.copy(leftoverAlerts = stats.leftoverAlerts + 1) else stats.copy(taxiLeftoverAlerts = stats.taxiLeftoverAlerts + 1)
                    val text = if (child) "${seat.label}을 확인해 주세요. 아이가 내렸다면 가방이 남아 있을 수 있어요."
                    else "${seat.label}에 두고 내린 물건이 있는지 확인해 주세요."
                    out += RoutineEvent.Log((if (child) "아이 좌석" else "택시 모드") + " 출발 확인: ${seat.label} 문 열림→닫힘 뒤 착좌 유지·벨트 풀림으로 P→D")
                    out += RoutineEvent.CleanupNotice(
                        CHILD_CHECK_NOTICE_KEY_BASE + seat.ordinal, listOf(seat), text, final = true, title = "${seat.label} 확인 필요", plain = true,
                        climateOff = climateOffCommands(seat, snapshot), buttonEpisodes = episodesOf(climateOffCommands(seat, snapshot)),
                    )
                }
            }
        }
        return out
    }

    private fun handleBoarded(seat: SeatId, snapshot: VehicleSnapshot, out: MutableList<RoutineEvent>) {
        out += RoutineEvent.Boarded(seat)
        if (!isManaged(seat)) {
            out += RoutineEvent.Log("${seat.label}은 관리 꺼짐 — 탑승 기록·인사·프리셋 건너뜀")
            return
        }
        riders += seat
        val now = snapshot.seat(seat)
        out += RoutineEvent.Log("탑승 기록 ${seat.label}: 창문 ${now.windowOpenPercent ?: "?"}%, 열선 ${now.heaterLevel ?: "?"}, 통풍 ${now.ventilationLevel ?: "?"}")
        val sameRider = seat in resumed
        when {
            resumed.remove(seat) -> out += RoutineEvent.Log("${seat.label}: 정리 취소 뒤 같은 분이 다시 앉음 — 다시 인사하지 않음")
            !boardingVoice -> out += RoutineEvent.Log("탑승 멘트 꺼짐 — ${seat.label} 인사 생략")
            // 7차 F10-2: 달리는 중에 말하지 않도록 기어 P 이거나 그 좌석 문이 열려 있을 때만 말한다.
            snapshot.gear != Gear.PARK && snapshot.seat(seat).doorOpen != true ->
                out += RoutineEvent.Log("${seat.label} 인사 생략 — 기어 P 가 아니고 문도 닫혀 있음(기어 ${snapshot.gear})")
            else -> out += RoutineEvent.Speak("${seat.label}에 탑승을 확인했어요. 편하게 가세요.")
        }
        if (isChildNow(seat) && !sameRider) {
            out += RoutineEvent.CleanupNotice(
                CHILD_MODE_NOTICE_KEY_BASE + seat.ordinal, listOf(seat), "${seat.label} 키즈락 모드로 탑승을 확인했어요.", final = true,
                title = "${seat.label} 키즈락 좌석", plain = true, adultSeat = seat, buttonEpisodes = mapOf(seat to episodeOf(seat)),
            )
        }
        val cmds = childLockCommands(seat, now, out) + climatePreset(seat, now)
        if (cmds.isEmpty()) return
        val id = nextJobId++
        presets[id] = seat
        out += RoutineEvent.Execute(id, cmds, "탑승 프리셋 ${seat.label}" + (weather.outdoorTempC?.let { "(바깥 ${it}℃)" } ?: ""), seat, preset = true)
    }

    /** 아이 좌석 탑승: 원래 창문 잠금을 기억하고 풀려 있으면 잠그는 명령. 잠금을 읽을 수 없으면 없다(안내만). */
    private fun childLockCommands(seat: SeatId, now: SeatState, out: MutableList<RoutineEvent>): List<VehicleCommand> {
        if (!isChildNow(seat)) return emptyList()
        val cur = now.windowLocked
        if (cur == null) {
            out += RoutineEvent.Log("${seat.label} 아이 좌석: 창문 잠금을 읽을 수 없음 — 잠금 없이 안내만")
            return emptyList()
        }
        // 정리 취소·복구 실패로 남아 있는 원래 값은 그대로 둔다(지금 값은 앱이 잠근 값일 수 있음).
        if (seat !in originalLock) originalLock[seat] = cur
        if (cur) {
            out += RoutineEvent.Log("${seat.label} 아이 좌석: 창문이 이미 잠겨 있음")
            return emptyList()
        }
        return listOf(VehicleCommand.SetWindowLock(seat, true))
    }

    /**
     * 탑승 프리셋(바깥 ≤3℃ 열선 2단, ≥28℃ 통풍 2단). 지금 값을 본다(사용자가 직접 켠 열선·통풍을 덮어쓰지 않도록).
     * 좌석 범위(F12-5): 열선은 관리 좌석 전체(미지원은 건너뜀). 통풍은 조수석만(미지원은 건너뜀)이고,
     * 뒷좌석은 [rearVentOnBoard] 가 켜져 있고 capability 가 지원(SUPPORTED)을 보고한 좌석만.
     */
    private fun climatePreset(seat: SeatId, now: SeatState): List<VehicleCommand> {
        if (!autoPresetOnBoard) return emptyList()
        val outdoor = weather.outdoorTempC ?: return emptyList()
        val sup = climateSupport[seat] ?: ClimateSupport()
        val ventAllowed = when (seat) {
            SeatId.ROW_1_RIGHT -> sup.vent != SeatSignals.Support.UNSUPPORTED
            else -> !seat.isDriver && rearVentOnBoard && sup.vent == SeatSignals.Support.SUPPORTED
        }
        return when {
            outdoor <= thresholds.coldOutdoorC -> if ((now.heaterLevel ?: 0) == 0 && sup.heater != SeatSignals.Support.UNSUPPORTED) listOf(VehicleCommand.SetSeatHeater(seat, 2)) else emptyList()
            outdoor >= 28f -> if ((now.ventilationLevel ?: 0) == 0 && ventAllowed) listOf(VehicleCommand.SetSeatVentilation(seat, 2)) else emptyList()
            else -> emptyList()
        }
    }

    /** 좌석별 열선·통풍 지원 여부(F12-5). 게이트웨이가 연결 때 판별해 넣는다. 비어 있으면 모두 모름(있는 것으로 봄). */
    var climateSupport: Map<SeatId, ClimateSupport> = emptyMap()
    /** 설정 "뒷좌석 통풍도 켜기"(F12-5, 기본 꺼짐). capability 가 지원을 보고한 뒷좌석에만 적용한다. */
    var rearVentOnBoard: Boolean = false

    private fun handleDoorOpened(seat: SeatId, snapshot: VehicleSnapshot, out: MutableList<RoutineEvent>) {
        out += RoutineEvent.DoorOpened(seat)
        // F14-4: 벨트 풀림에서 이미 뒤 차 경고를 했으면 그 뒤 30초 안의 문 열림 안내에서는 뒤 차 문장을 뺀다(한 번 쓰고 지운다).
        val warnedAt = beltWarnAtMs.remove(seat)
        val warnedJustBefore = warnedAt != null && snapshot.timestampMs - warnedAt <= AlertPolicy.BELT_REAR_WARNING_WINDOW_MS
        batches.forEach { it.seats.remove(seat) }
        val open = batches.lastOrNull { snapshot.timestampMs - it.startMs < batchWindowMs }
        if (open != null) {
            open.seats += seat
            out += RoutineEvent.Log("하차 준비: ${seat.label} 문 열림 · 묶음 #${open.id} 합류 — 동승자 안내는 이미 함")
            // 공통 안내는 이미 했지만, 이 좌석에서만 챙길 물건은 이 좌석 사람에게 따로 말한다.
            // 아이 좌석은 아이 말투로 한 마디 한다(물건이 없어도 "조심히 내려").
            val own = if (childVoice(seat)) ExitMessageRules.childJoinedMessage(items, seat, phrasePicker, name = childProfiles[seat]?.name, extraItems = childTodayItems(seat)) else ExitMessageRules.seatOnlyItems(items, seat, phrasePicker)
            if (own != null) {
                if (exitVoice && !VehicleMotion.passengerVoiceAllowed(snapshot)) {
                    out += RoutineEvent.Log("달리는 중(속도 ${snapshot.speedMps} m/s)이라 동승자 안내 음성 생략 — ${seat.label}: ${hideNames(own)}")
                } else if (exitVoice) {
                    out += RoutineEvent.Speak(own)
                    passengerSpeech[own] = open.id
                    passengerSpeechSeat[own] = seat
                    // 아이 말투 안내는 기본 멘트여도 실제로 나갔으면 챙김 안내로 센다(5차 심사 F4).
                    stats = stats.copy(reminders = stats.reminders + 1, childReminders = stats.childReminders + if (childVoice(seat)) 1 else 0)
                } else out += RoutineEvent.Log("하차 멘트 꺼짐 — ${seat.label} 물건 안내 생략")
            }
            return
        }
        val b = Batch(nextBatchId++, snapshot.timestampMs).also { it.seats += seat; batches += it }
        val child = childVoice(seat)
        // F12-6 대체안: 도로변(D·N) 하차면 뒤 차 확인 한 문장(설정, 기본 꺼짐). 순정 SEA 를 읽지 못해 경고가 아니라 안내다.
        // F13-1: 키즈락 모드로 탄 좌석(아이 말투)은 설정과 상관없이 항상 붙인다. [어른이 탔어요]·택시는 설정을 따른다.
        val rear = (roadsideRearCheck || child) && (snapshot.gear == Gear.DRIVE || snapshot.gear == Gear.NEUTRAL) && !(child && warnedJustBefore)
        val m = if (child) ExitMessageRules.childMessage(weather, snapshot, items, seat, thresholds, quietPolicy, phrasePicker, name = childProfiles[seat]?.name, extraItems = childTodayItems(seat), rearCheck = rear)
        else ExitMessageRules.passengerMessage(weather, snapshot, items, thresholds, quietPolicy, seat, baseSaidThisTrip, phrasePicker, rearCheck = rear)
        out += RoutineEvent.Log("하차 준비: ${seat.label} 문 열림 · 묶음 #${b.id} · 규칙 ${m.firedRules.joinToString(", ")}" + if (child) " · ${LogRedactor.marker(childProfiles[seat]?.name)}" else "")
        if (m.sentences.isNotEmpty()) {
            if (exitVoice && !VehicleMotion.passengerVoiceAllowed(snapshot)) {
                out += RoutineEvent.Log("달리는 중(속도 ${snapshot.speedMps} m/s)이라 동승자 안내 음성 생략 — ${hideNames(m.sentences.joinToString(" "))}")
            } else if (exitVoice) {
                out += RoutineEvent.Speak(m.sentences.joinToString(" ")).also { passengerSpeech[it.text] = b.id; passengerSpeechSeat[it.text] = seat }
                if (!child) baseSaidThisTrip = true
                // 기록은 실제로 말한 안내만 센다(A4). 아이 말투 안내는 기본 멘트도 센다(5차 심사 F4).
                if (child) stats = stats.copy(childReminders = stats.childReminders + 1)
                if (m.hasReminder || child) {
                    val rain = m.firedRules.any { it.startsWith("WEATHER_RAIN") }
                    stats = stats.copy(reminders = stats.reminders + 1, rainReminders = stats.rainReminders + if (rain) 1 else 0)
                }
            } else out += RoutineEvent.Log("하차 멘트 꺼짐 — 동승자 안내 생략")
        }
    }

    private fun handleAlighted(seat: SeatId, snapshot: VehicleSnapshot, out: MutableList<RoutineEvent>) {
        out += RoutineEvent.Alighted(seat)
        alightedAtMs[seat] = snapshot.timestampMs
        val dropped = presets.filterValues { it == seat }.keys
        if (dropped.isNotEmpty()) {
            presets.keys.removeAll(dropped)
            out += RoutineEvent.Log("${seat.label} 하차 확정 — 대기 중이던 탑승 프리셋 ${dropped.size}건 삭제")
        }
        val b = batchOf(seat) ?: closedBatch(seat, snapshot)
        val rode = riders.remove(seat)
        val managed = isManaged(seat)
        if (!managed) b.unmanaged += seat
        if (rode && managed) stats = stats.copy(rides = stats.rides + 1)          // 관리 좌석 하차만 센다(A4)
        // F13-2: 같은 열 옆자리에 사람이 있으면 창문은 자동으로 닫지 않는다(열선·통풍 끄기와 잠금 복원은 그대로). 보고에 [창문 닫기] 버튼.
        val mate = if (rode && managed && (snapshot.seat(seat).windowOpenPercent ?: 0) > 0 && !isWindowButtonOnly(seat)) neighborPresent(seat, snapshot) else null
        if (mate != null) {
            b.neighborHeld += seat
            out += RoutineEvent.Log("${seat.label}: 옆자리(${mate.label})에 사람이 있어 창문 자동 닫기 안 함 — [창문 닫기] 버튼으로")
        }
        // F14-1: 키즈락 열은 옆자리 신호를 확인할 수 없으면(사람이 없다는 증거가 없으면) 자동으로 닫지 않는다. 사람이 있으면 위 F13-2 가 우선이다.
        val unknown = mate == null && rode && managed && (snapshot.seat(seat).windowOpenPercent ?: 0) > 0 && !isWindowButtonOnly(seat) && neighborSignalUnknown(seat, snapshot)
        if (unknown) {
            b.neighborUnknown += seat
            out += RoutineEvent.Log("${seat.label}: 키즈락 열의 옆자리 좌석 신호를 확인할 수 없어 창문 자동 닫기 안 함 — [창문 닫기] 버튼으로")
        }
        val cmds = if (rode && managed) lockRestoreCommands(seat, snapshot.seat(seat)) + cleanupCommands(seat, snapshot.seat(seat), holdWindow = mate != null || unknown) else emptyList()
        if (rode) {
            val now = snapshot.seat(seat)
            val lock = originalLock[seat]?.let { ", 창문 잠금 ${now.windowLocked ?: "?"}→원래 $it" } ?: ""
            out += RoutineEvent.Log(
                "정리 목표 ${seat.label}: 창문 ${now.windowOpenPercent ?: "?"}%→닫힘, 열선 ${now.heaterLevel ?: "?"}→끔, 통풍 ${now.ventilationLevel ?: "?"}→끔$lock" +
                    if (cmds.isEmpty()) " (할 일 없음)" else " (${cmds.size}개 명령)"
            )
        }
        if (cmds.isEmpty()) {
            b.done[seat] = emptyList()
            return
        }
        b.planned += cmds
        cleaning += seat
        out += RoutineEvent.Execute(nextJobId++, cmds, "하차 정리 ${seat.label}", seat)
    }

    private fun batchOf(seat: SeatId): Batch? = batches.lastOrNull { seat in it.seats }

    /** 묶음 없이 정리 결과가 생긴 경우(다시 시도 등) — 합류 창이 이미 닫힌 1인 묶음 */
    private fun closedBatch(seat: SeatId, snapshot: VehicleSnapshot) =
        Batch(nextBatchId++, snapshot.timestampMs - batchWindowMs).also { it.seats += seat; batches += it }

    /**
     * 아이 좌석 하차: 탑승 때 기억한 원래 창문 잠금과 지금 값이 다르면 되돌리는 명령(정리 목록 맨 앞).
     * 같거나 지금 값을 읽을 수 없으면 명령 없이 기억만 지운다. 설정에서 아이 좌석을 더라도 잠가 둔 것은 되돌린다.
     */
    private fun lockRestoreCommands(seat: SeatId, now: SeatState): List<VehicleCommand> {
        val orig = originalLock[seat] ?: return emptyList()
        val cur = now.windowLocked
        if (cur == null || cur == orig) {
            originalLock.remove(seat)
            return emptyList()
        }
        return listOf(VehicleCommand.SetWindowLock(seat, orig))
    }

    /** 하차 정리 규칙: 열린 창문은 닫고, 켜진 열선·통풍은 끈다. 이미 닫혀 있거나 꺼져 있으면(또는 값을 모르면) 만들지 않는다. */
    private fun cleanupCommands(seat: SeatId, now: SeatState, holdWindow: Boolean = false): List<VehicleCommand> = buildList {
        // 착좌 신호가 없는 좌석은 빈 좌석을 확인할 수 없어 창문을 자동으로 닫지 않는다(F9-3). 알림 버튼으로만 닫는다.
        // 같은 열 옆자리에 사람이 있을 때도 마찬가지다([holdWindow], F13-2).
        if ((now.windowOpenPercent ?: 0) > 0 && !isWindowButtonOnly(seat) && !holdWindow) add(VehicleCommand.SetWindow(seat, 0))
        if ((now.heaterLevel ?: 0) > 0) add(VehicleCommand.SetSeatHeater(seat, 0))
        if ((now.ventilationLevel ?: 0) > 0) add(VehicleCommand.SetSeatVentilation(seat, 0))
    }

    /**
     * 탑승 프리셋을 실행한 뒤 호출한다. 쓰는 사이에 그 좌석이 하차로 확정됐으면(프리셋이 지워짐) 켠 것을 바로 끈다
     * (빈 좌석에 열선이 켜진 채 남지 않도록). 되돌림 작업은 보고 없이 로그만 남긴다.
     */
    fun onPresetFinished(jobId: Long, seat: SeatId, results: List<CommandResult>, snapshot: VehicleSnapshot? = null): List<RoutineEvent> {
        val stillRiding = presets.remove(jobId) != null && machine.phaseOf(seat) == BoardingStateMachine.Phase.RIDING
        if (stillRiding) {
            val locked = results.count { val c = it.cmd; c is VehicleCommand.SetWindowLock && c.locked && it.status == CommandResult.Status.OK }
            if (locked > 0) stats = stats.copy(childLocks = stats.childLocks + locked)
            return lockFailureReport(seat, results, snapshot)
        }
        val revert = results.filter { it.status == CommandResult.Status.OK }.mapNotNull {
            when (val c = it.cmd) {
                is VehicleCommand.SetSeatHeater -> if (c.level > 0) VehicleCommand.SetSeatHeater(c.seat, 0) else null
                is VehicleCommand.SetSeatVentilation -> if (c.level > 0) VehicleCommand.SetSeatVentilation(c.seat, 0) else null
                // 잠그는 사이 내렸으면 원래 잠금으로 돌린다(하차 정리는 잠기기 전 값을 봤을 수 있음)
                is VehicleCommand.SetWindowLock -> originalLock.remove(c.seat)?.takeIf { o -> o != c.locked }?.let { o -> VehicleCommand.SetWindowLock(c.seat, o) }
                else -> null
            }
        }
        if (revert.isEmpty()) return emptyList()
        return listOf(
            RoutineEvent.Log("${seat.label}: 탑승 프리셋을 쓰는 사이 하차 — 켠 것을 되돌림"),
            RoutineEvent.Execute(nextJobId++, revert, "프리셋 되돌림 ${seat.label}"),
        )
    }

    /**
     * 아이 좌석 탑승 때 창문 잠금이 실패하면(FAILED) 운전자에게 알린다(5차 심사 F1). 알림은 항상, 음성은 P 일 때만.
     * 잠기지 않았으므로 원래 잠금 기억은 그대로 둔다(하차 때 지금 값과 같으면 되돌릴 것이 없다).
     */
    private fun lockFailureReport(seat: SeatId, results: List<CommandResult>, snapshot: VehicleSnapshot?): List<RoutineEvent> {
        val failed = results.any { val c = it.cmd; c is VehicleCommand.SetWindowLock && c.locked && it.status == CommandResult.Status.FAILED }
        if (!failed) return emptyList()
        val text = "${seat.label} 창문 잠금에 실패했어요. 직접 잠가 주세요."
        val now = snapshot?.timestampMs ?: lastTimestampMs
        if (!alertLimiter.tryFailure(seat, "window-lock", now)) {
            stats = stats.copy(suppressedAlerts = stats.suppressedAlerts + 1)
            return listOf(RoutineEvent.Log("잠금 실패 알림 억제: ${seat.label} 10분 안에 이미 알림"))
        }
        val out = mutableListOf<RoutineEvent>(RoutineEvent.Log("아이 좌석 잠금 실패: ${seat.label}"))
        if (snapshot != null && VehicleMotion.voiceAllowed(snapshot)) out += RoutineEvent.Speak(text)
        else out += RoutineEvent.Log("정차(P)가 아니어서 잠금 실패는 알림만: $text")
        out += RoutineEvent.CleanupNotice(LOCK_FAIL_NOTICE_KEY_BASE + seat.ordinal, listOf(seat), text, final = true, title = "${seat.label} 창문 잠금 실패", plain = true)
        return out
    }

    /**
     * 하차 정리 명령을 실제로 실행한 뒤 호출한다. 결과를 묶음에 넘기고, 묶음이 다 끝나면 보고·알림을 1번 낸다.
     * [seat] 가 없으면(프리셋 되돌림 등) 보고하지 않는다.
     */
    fun onJobFinished(seat: SeatId?, results: List<CommandResult>, snapshot: VehicleSnapshot): List<RoutineEvent> {
        if (seat == null) return emptyList()
        cleaning.remove(seat)
        // 원래 잠금으로 돌렸으면 기억을 지운다. 실패하면 [다시 시도]를 위해 남긴다.
        if (results.any { it.cmd is VehicleCommand.SetWindowLock && it.status == CommandResult.Status.OK }) originalLock.remove(seat)
        val b = batchOf(seat) ?: closedBatch(seat, snapshot)
        b.done[seat] = results
        return checkBatches(snapshot)
    }

    /** 합류 창이 닫혔고 구성원 정리가 모두 끝난 묶음의 보고·알림을 낸다. */
    private fun checkBatches(snapshot: VehicleSnapshot): List<RoutineEvent> {
        val out = mutableListOf<RoutineEvent>()
        for (b in batches.toList()) {
            if (snapshot.timestampMs - b.startMs < batchWindowMs) continue
            // 문을 열었다가 다시 앉아 하차가 아니게 된 좌석은 묶음에서 뺀다.
            b.seats.removeAll { it !in b.done && it !in cleaning && machine.phaseOf(it) != BoardingStateMachine.Phase.ALIGHTING_WAIT_DOOR_CLOSE }
            if (b.seats.isEmpty()) { batches.remove(b); continue }
            if (b.seats.any { it !in b.done }) continue
            batches.remove(b)
            out += report(b, snapshot)
        }
        return out
    }

    /** 묶음 마무리: 운전자 보고(정차 중일 때만 음성)와 결과 알림 1건. 실제로 한 일만 말한다. */
    private fun report(b: Batch, snapshot: VehicleSnapshot): List<RoutineEvent> {
        val perSeat = b.seats.associateWith { b.done[it].orEmpty() }
        val results = perSeat.values.flatten()
        countDone(results)
        val aborted = results.count { it.status == CommandResult.Status.ABORTED }
        if (aborted > 0) stats = stats.copy(windowAborts = stats.windowAborts + aborted)
        // 남은 물건은 정리 보고 끝에 한 문장으로 합친다(음성 1번, 알림 1건, 5차 심사 F3).
        // 착좌 신호가 없는 좌석의 열린 창문: 자동으로 닫지 않고 운전자에게 확인을 부탁한다(F9-3, [창문 닫기] 버튼).
        val buttonOnly = b.seats.filter { isWindowButtonOnly(it) && (snapshot.seat(it).windowOpenPercent ?: 0) > 0 }
        val windowLines = buttonOnly.map { "${it.label} 창문이 열려 있어요. 비어 있는지 확인하고 닫아 주세요." }
        // 같은 열 옆자리에 사람이 있어 닫지 않은 창문(F13-2): 경고가 아니라 안내 문장 + [창문 닫기] 버튼.
        val held = b.seats.filter { it in b.neighborHeld && it !in buttonOnly && (snapshot.seat(it).windowOpenPercent ?: 0) > 0 }
        val unknownHeld = b.seats.filter { it in b.neighborUnknown && it !in buttonOnly && it !in held && (snapshot.seat(it).windowOpenPercent ?: 0) > 0 }
        val heldLines = held.map { "옆자리에 사람이 있어 ${it.label} 창문은 닫지 않았어요." } +
            unknownHeld.map { "옆자리 좌석 신호를 확인할 수 없어 ${it.label} 창문은 닫지 않았어요." }
        val windowClose = (buttonOnly + held + unknownHeld).map { VehicleCommand.SetWindow(it, 0) }
        // 정리 직후에도 그 좌석 문이 열려 있으면 경고다(F12-1). "문이 열려 있어서 … 그대로 뒀어요"가 이미 있으면 같은 말을 두 번 하지 않는다.
        val doorLines = b.seats.filter { s ->
            snapshot.seat(s).doorOpen == true && perSeat[s].orEmpty().none { it.status == CommandResult.Status.REJECTED && it.why == DOOR_OPEN_REASON }
        }.map { "${it.label} 문이 열려 있어요." }
        val all = ExitMessageRules.driverReportLines(perSeat, b.unmanaged, snapshot, thresholds, weather).filter {
            when (it.kind) {
                ReportKind.DRIVER_WINDOW -> driverWindowNotice
                ReportKind.CABIN_HEAT -> cabinHeatNotice
                else -> true
            }
        } + (b.leftovers.values + windowLines + doorLines).map { ReportLine(ReportKind.WARNING, it) } +
            heldLines.map { ReportLine(ReportKind.NOTICE, it) }
        val fullText = withLateContext(b, all.map { it.text }, snapshot).joinToString(" ")
        // '최근 하차 정리' 카드는 설정과 상관없이 성공까지 모두 보여 준다.
        lastResult = AlightResult(b.seats.first(), snapshot.timestampMs, b.planned.toList(), results, fullText, finished = true, seats = b.seats.toList())
        if (all.isEmpty()) return listOf(RoutineEvent.Log("하차 묶음 #${b.id} 마무리: 보고할 일 없음"))
        val warned = all.any { it.kind == ReportKind.WARNING }
        // 성공 보고는 "모두"일 때만 낸다(F12-1). 경고·안내는 모드와 상관없이 낸다.
        val reportable = all.filter { it.kind != ReportKind.SUCCESS || reportMode == DriverReportMode.ALL }
        val text = withLateContext(b, reportable.map { it.text }, snapshot).joinToString(" ")
        val out = mutableListOf<RoutineEvent>()
        // 합쳐진 남은 물건 좌석의 켜진 열선·통풍은 이 알림에서 끌 수 있다(F9-2, 정리 대상이 아니라 자동으로 끄지 않는다).
        val leftoverClimateOff = b.leftovers.keys.flatMap { climateOffCommands(it, snapshot) }
        // 기어 P 이고 정차가 아니면(출발 중·굴러감) 음성으로 말하지 않는다. 알림에는 남긴다(F6-1). "끔"은 음성을 내지 않는다.
        val spoken = text.isNotEmpty() && reportMode != DriverReportMode.OFF && VehicleMotion.voiceAllowed(snapshot)
        when {
            spoken -> out += RoutineEvent.Speak(text)
            text.isEmpty() -> out += RoutineEvent.Log("운전자 보고(${reportMode.label}): 성공 보고는 카드에만 — $fullText")
            reportMode == DriverReportMode.OFF -> out += RoutineEvent.Log("운전자 보고 음성 끔 — 알림만: $text")
            else -> out += RoutineEvent.Log("정차(P)가 아니어서 운전자 보고는 알림만: $text")
        }
        val failed = perSeat.mapValues { (_, rs) -> rs.filter { it.status == CommandResult.Status.FAILED }.map { it.cmd } }.filterValues { it.isNotEmpty() }
        if (failed.isNotEmpty()) stats = stats.copy(restoreFailures = stats.restoreFailures + 1)
        // 같은 좌석·같은 명령의 실패 보고는 10분 안에 1번만 낸다(F6-9). 새 실패가 하나도 없으면 이 알림은 낸 것과 같아 보내지 않는다.
        // 다시 시도 버튼은 먼저 낸 알림이 같은 좌석·같은 명령의 실패를 이미 가리킨다(RetryBook).
        if (failed.isNotEmpty()) {
            val fresh = failed.flatMap { (s, cmds) -> cmds.map { s to it.toString() } }.filter { (s, c) -> alertLimiter.tryFailure(s, c, snapshot.timestampMs) }
            // 같은 실패만 되풀이한 보고일 때만 억제한다. 실제로 된 일이나 합쳐진 남은 물건 문장이 있으면 보고는 낸다.
            // 창문 닫기 중단(ABORTED)이 들어 있으면 가장 중요한 안내라 억제하지 않는다(F9-1 리뷰).
            if (fresh.isEmpty() && results.none { it.status == CommandResult.Status.OK || it.status == CommandResult.Status.ABORTED } && b.leftovers.isEmpty()) {
                stats = stats.copy(suppressedAlerts = stats.suppressedAlerts + 1)
                return listOf(RoutineEvent.Log("실패 보고 억제: 같은 좌석·같은 명령, 10분 안에 이미 알림 — $text"))
            }
        }
        // 알림 본문: 내보낸 보고. 성공뿐이라 내보낸 것이 없으면(경고만·끔) 음성 실패 때 남길 수 있게 전체를 둔다.
        val body = text.ifEmpty { fullText }
        val noticeText = if (b.speechFailed) "$body\n$SPEECH_FAILED_LINE" else body
        val title = if (b.leftovers.isEmpty()) null
        else "${b.seats.joinToString("·") { it.label }} 하차 정리 · ${b.leftovers.keys.joinToString("·") { it.label }} 확인"
        val notice = RoutineEvent.CleanupNotice(b.id, b.seats.toList(), noticeText, final = true, failed = failed, title = title, climateOff = leftoverClimateOff, windowClose = windowClose,
            buttonEpisodes = episodesOf(leftoverClimateOff + windowClose))
        reportedNotices[b.id] = notice
        // F12-2: 알림을 남기는 경우 = 경고·실패([다시 시도])·버튼이 필요한 알림, 동승자 안내 음성 실패, P 가 아니라(또는 끔이라) 음성을 못 한 보고.
        // 음성으로 낸 보고는 음성이 끝까지 나가면 알림을 남기지 않는다(카드만). 못 나가면 [onSpeechFailed]·[onSpeechAbandoned] 가 이 알림을 낸다.
        val mustPost = warned || failed.isNotEmpty() || b.speechFailed || windowClose.isNotEmpty() || leftoverClimateOff.isNotEmpty()
        when {
            mustPost -> out += notice
            text.isEmpty() -> Unit
            spoken -> { pendingSpokenReports[text] = b.id; out += RoutineEvent.Log("하차 묶음 #${b.id}: 음성으로 보고 — 알림은 음성이 나가지 못할 때만") }
            else -> out += notice
        }
        return out
    }

    /** 음성이 끝까지 말해졌다(F12-2). 음성으로만 낸 보고면 알림을 남기지 않고 끝낸다. */
    fun onSpeechDone(text: String): List<RoutineEvent> {
        val id = pendingSpokenReports.remove(text) ?: return emptyList()
        return listOf(RoutineEvent.Log("하차 묶음 #$id: 보고 음성이 나감 — 알림 없이 카드만 갱신"))
    }

    /**
     * 포커스를 잃어 큐에서 빠진 문장(F12-2). 음성 실패([Stats.speechFailures])로 세지 않는다(F9-4 규칙 그대로).
     * 음성으로만 낸 보고였으면 그 알림을 남긴다.
     */
    fun onSpeechAbandoned(text: String): List<RoutineEvent> = unspokenReport(text, "포커스 잃음")

    private fun unspokenReport(text: String, why: String): List<RoutineEvent> {
        val id = pendingSpokenReports.remove(text) ?: return emptyList()
        val notice = reportedNotices[id] ?: return emptyList()
        return listOf(RoutineEvent.Log("하차 묶음 #$id: 보고 음성이 나가지 못함($why) — 알림으로 남김"), notice)
    }

    /**
     * 대기 중이던 동승자 하차 안내 음성의 재생 차례(F8-1): 버려야 하면 이유를, 아니면 null.
     *  - 음성 금지 상태([VehicleMotion.passengerVoiceAllowed] 가 false, 속도 > 0.5 m/s)
     *  - 그 좌석 문이 닫힌 채 [AlertPolicy.PASSENGER_SPEECH_STALE_MS] 를 넘김(이미 내린 사람에게 늦게 말하지 않는다)
     * 동승자 안내가 아닌 문장(운전자 보고·탑승 인사)은 판정하지 않는다. [nowMs] 는 스냅샷과 같은 시계(System.currentTimeMillis).
     */
    fun passengerSpeechDropReason(text: String, nowMs: Long, latest: VehicleSnapshot): String? {
        val seat = passengerSpeechSeat[text] ?: return null
        if (!VehicleMotion.passengerVoiceAllowed(latest)) return "달리는 중(속도 ${latest.speedMps} m/s)"
        if (prevDoor[seat] == false) {
            val closedFor = doorClosedAt[seat]?.let { nowMs - it }
            if (closedFor != null && closedFor > AlertPolicy.PASSENGER_SPEECH_STALE_MS) return "${seat.label} 문이 닫힌 지 ${closedFor}ms"
        }
        return null
    }

    /** 동승자 안내 음성을 일부러 버렸다(F8-1). 음성 실패가 아니므로 [Stats.speechFailures] 도, 보고 알림의 한 줄도 없다. */
    fun onPassengerSpeechDropped(text: String, reason: String): List<RoutineEvent> {
        passengerSpeech.remove(text)
        passengerSpeechSeat.remove(text)
        return listOf(RoutineEvent.Log("동승자 안내 음성 버림($reason): $text"))
    }

    /**
     * 음성이 말해지지 못했다(F9-4). 모든 실패를 [Stats.speechFailures] 로 센다.
     *  - 운전자 보고·탑승 인사 등: 알림은 원래 항상 나가므로 새 알림을 만들지 않는다(중복 방지).
     *  - 동승자 하차 안내: 그 하차의 운전자 보고 알림 끝에 한 줄을 붙인다. 보고 전이면 표시만 해 두었다가 보고에 붙이고,
     *    이미 나간 뒤면 같은 알림 키로 한 줄을 붙여 다시 낸다. 달리는 중이라 일부러 안 한 안내는 실패가 아니므로 여기 오지 않는다.
     * [reason] 은 로그용이다.
     */
    fun onSpeechFailed(text: String, reason: String = ""): List<RoutineEvent> {
        stats = stats.copy(speechFailures = stats.speechFailures + 1)
        // 음성으로만 낸 보고가 실패했으면 이제 알림을 남긴다(F12-2).
        unspokenReport(text, "음성 실패 $reason").takeIf { it.isNotEmpty() }?.let { return it }
        val id = passengerSpeech.remove(text) ?: return listOf(RoutineEvent.Log("음성 실패($reason): 알림은 이미 나감 — $text"))
        val open = batches.firstOrNull { it.id == id }
        if (open != null) {
            open.speechFailed = true
            return listOf(RoutineEvent.Log("동승자 안내 음성 실패($reason): 보고 알림 끝에 한 줄 붙임 — 묶음 #$id"))
        }
        val reported = reportedNotices[id] ?: return listOf(RoutineEvent.Log("동승자 안내 음성 실패($reason): 붙일 보고 없음 — 묶음 #$id"))
        if (reported.text.endsWith(SPEECH_FAILED_LINE)) return emptyList()
        val updated = reported.copy(text = "${reported.text}\n$SPEECH_FAILED_LINE")
        reportedNotices[id] = updated
        return listOf(updated)
    }

    /** 정리한 하차 1건(실제로 된 명령이 하나라도 있을 때)과 세부(창문 닫음·열선 끔·통풍 끔)를 센다. */
    private fun countDone(results: List<CommandResult>) {
        val ok = results.filter { it.status == CommandResult.Status.OK }.map { it.cmd }
        if (ok.isEmpty()) return
        stats = stats.copy(
            restores = stats.restores + 1,
            windowsClosed = stats.windowsClosed + ok.count { it is VehicleCommand.SetWindow && it.openPercent == 0 },
            heatersOff = stats.heatersOff + ok.count { it is VehicleCommand.SetSeatHeater && it.level == 0 },
            ventsOff = stats.ventsOff + ok.count { it is VehicleCommand.SetSeatVentilation && it.level == 0 },
        )
    }

    /**
     * 하차 뒤 [LATE_REPORT_MS] 이상 지나 나오는 보고(다시 시도 등)에는 어느 하차인지 맥락을 붙인다(A10).
     * 예: "아까 내리신 조수석 창문을 닫았어요." 첫 문장이 좌석 이름으로 시작하지 않으면 앞에 한 문장을 넣는다.
     */
    private fun withLateContext(b: Batch, lines: List<String>, snapshot: VehicleSnapshot): List<String> {
        if (lines.isEmpty()) return lines
        val at = b.seats.mapNotNull { alightedAtMs[it] }.minOrNull() ?: return lines
        if (snapshot.timestampMs - at < LATE_REPORT_MS) return lines
        val labels = b.seats.joinToString("·") { it.label }
        val first = lines.first()
        return if (b.seats.any { first.startsWith(it.label) }) listOf("아까 내리신 $first") + lines.drop(1)
        else listOf("아까 내리신 $labels 정리 결과예요.") + lines
    }

    /**
     * 운행이 끝났을 때(P 로 오래 서 있고 동승석이 모두 빔, 또는 서비스 종료) 탑승 기록을 비운다.
     * 이미 내보낸 하차 정리는 결과를 돌려받아 보고해야 하므로 그 좌석의 진행 기록과 묶음은 남겨 둔다.
     */
    fun endTrip(): List<RoutineEvent> {
        adultThisTrip.clear()
        beltWarnEpisode.clear(); beltWarnAtMs.clear()
        riders.clear(); resumed.clear(); presets.clear(); machine.reset()
        doorOpenWhileSeatedAt.clear(); doorCycledAt.clear(); leftoverAlerted.clear(); childDoorCycled.clear()
        alertLimiter.startTrip()
        baseSaidThisTrip = false
        batches.removeAll { b -> b.seats.none { it in cleaning } }
        return listOf(RoutineEvent.Log("운행 종료: 탑승 기록 정리"))
    }
}
