package com.jaribom.app.core

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 반복 알림 상한(6차 F6-9). 알림 피로를 줄이려고 숫자를 이 한 곳에 모았다. 설문 결과로 숫자만 바꾼다.
 *  - 남은 물건 알림: 같은 좌석, 한 에피소드에 [LEFTOVER_PER_SEAT_EPISODE]번
 *  - 아이 좌석 보조 알림: 같은 좌석 한 에피소드에 [ASSIST_PER_SEAT_EPISODE]번 + 하루 전체 [ASSIST_PER_DAY]번
 *  - 택시 모드 알림(F10-1): 손님(좌석 에피소드)당 [TAXI_PER_SEAT_EPISODE]번, 하루 상한 없음(택시는 하루 손님이 수십 명)
 *  - 좌석 에피소드(F7-3): 알림이 나간 뒤 그 좌석 문이 다시 열리면 새 에피소드(택시 다음 손님). 운행이 끝나도 새 에피소드.
 *  - 실패 보고: 같은 좌석·같은 명령은 [FAILURE_DEDUPE_MS] 안에 1번
 * 하루 기준은 기기 로컬 날짜이고, 날짜가 바뀌면 하루 횟수를 처음부터 센다.
 * 상한에 걸려 보내지 않은 알림은 [CabinRoutineEngine.Stats.suppressedAlerts] 로 센다.
 */
object AlertPolicy {
    /** 남은 물건 알림: 같은 좌석, 한 에피소드에 이만큼(F7-3) */
    const val LEFTOVER_PER_SEAT_EPISODE = 1
    /** 보조 판정 알림(아이 좌석·택시 모드): 같은 좌석, 한 에피소드에 이만큼(F7-3) */
    const val ASSIST_PER_SEAT_EPISODE = 1
    const val ASSIST_PER_DAY = 5
    /** 택시 모드 알림: 같은 좌석, 한 에피소드(손님)에 이만큼. 하루 상한은 없다(F10-1) */
    const val TAXI_PER_SEAT_EPISODE = 1
    const val FAILURE_DEDUPE_MS = 10 * 60 * 1000L
    /** 대기 중이던 동승자 하차 안내 음성은 재생 차례에 그 좌석 문이 닫힌 지 이만큼 넘었으면 버린다(F8-1) */
    const val PASSENGER_SPEECH_STALE_MS = 5_000L
    /** 벨트 풀림 순간 "뒤에 차 오는지 꼭 보고 내려"를 말한 뒤 이 시간 안의 문 열림 안내에서는 뒤 차 문장을 뺀다(F14-4) */
    const val BELT_REAR_WARNING_WINDOW_MS = 30_000L

    /** 기기 로컬 날짜 키(yyyy-MM-dd) */
    fun localDayKey(ms: Long): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(ms))
}

/** 하루 보조 알림 횟수 저장소(F7-4). 앱에서는 ItemStore(SharedPreferences)가 구현한다. */
interface DailyCountStore {
    /** (날짜 키, 그날 횟수). 없으면 null */
    fun load(): Pair<String, Int>?
    fun save(day: String, count: Int)
    fun clear()
}

/** [AlertPolicy] 의 상한을 실제로 세는 상태. 허용되면 true 를 돌려주고 그 횟수를 기록한다. */
class AlertLimiter(
    /** 하루 횟수를 저장할 곳(F7-4). 있으면 앱을 다시 시작해도 이어서 센다. null 이면 메모리에서만 센다. */
    private val daily: DailyCountStore? = null,
    private val dayKeyOf: (Long) -> String = AlertPolicy::localDayKey,
) {
    /**
     * 좌석 에피소드(F7-3): 그 좌석의 알림이 나간 뒤 그 좌석 문이 다시 열리면 새 에피소드가 시작돼 좌석별 횟수가 풀린다.
     * 문 활동 없이 P→D 를 되풀이하면(같은 가방) 같은 에피소드라 계속 억제한다. 다른 좌석의 문은 이 좌석에 영향이 없다.
     */
    private val leftoverThisEpisode = mutableMapOf<SeatId, Int>()
    private val assistThisEpisode = mutableMapOf<SeatId, Int>()
    private val taxiThisEpisode = mutableMapOf<SeatId, Int>()
    private var assistDay: String? = daily?.load()?.first
    private var assistToday = daily?.load()?.second ?: 0
    private val failureAt = mutableMapOf<Pair<SeatId, String>, Long>()

    /** 그 좌석 문이 열렸다 = 새 에피소드. 그 좌석의 횟수만 비운다. */
    fun onSeatDoorOpened(seat: SeatId) {
        leftoverThisEpisode.remove(seat)
        assistThisEpisode.remove(seat)
        taxiThisEpisode.remove(seat)
        seatNo[seat] = (seatNo[seat] ?: 0) + 1
    }

    /** 좌석 에피소드 번호(알림 버튼이 오래된 알림인지 본다). 그 좌석 문이 열리거나 운행이 끝나면 달라진다. */
    private var tripNo = 0
    private val seatNo = mutableMapOf<SeatId, Int>()
    fun episodeOf(seat: SeatId): Int = tripNo * 100_000 + (seatNo[seat] ?: 0)

    /** 운행이 끝나면 모든 좌석이 새 에피소드다. 하루 횟수·실패 보고 기록은 그대로다. */
    fun startTrip() {
        leftoverThisEpisode.clear()
        assistThisEpisode.clear()
        taxiThisEpisode.clear()
        tripNo++
    }

    fun tryLeftover(seat: SeatId): Boolean {
        val n = leftoverThisEpisode[seat] ?: 0
        if (n >= AlertPolicy.LEFTOVER_PER_SEAT_EPISODE) return false
        leftoverThisEpisode[seat] = n + 1
        return true
    }

    /** 택시 모드 알림(F10-1): 손님(좌석 에피소드)당 [AlertPolicy.TAXI_PER_SEAT_EPISODE]번. 하루 횟수를 세지도 쓰지도 않는다. */
    fun tryTaxi(seat: SeatId): Boolean {
        val n = taxiThisEpisode[seat] ?: 0
        if (n >= AlertPolicy.TAXI_PER_SEAT_EPISODE) return false
        taxiThisEpisode[seat] = n + 1
        return true
    }

    fun tryAssist(seat: SeatId, nowMs: Long): Boolean {
        val day = dayKeyOf(nowMs)
        if (assistDay != day) { assistDay = day; assistToday = 0 }
        if ((assistThisEpisode[seat] ?: 0) >= AlertPolicy.ASSIST_PER_SEAT_EPISODE) return false
        if (assistToday >= AlertPolicy.ASSIST_PER_DAY) return false
        assistThisEpisode[seat] = (assistThisEpisode[seat] ?: 0) + 1
        assistToday++
        daily?.save(day, assistToday)
        return true
    }

    /** 오늘(기기 로컬 날짜) 보낸 보조 알림 수. 저장된 날짜가 오늘이 아니면 0. */
    fun assistCountToday(nowMs: Long): Int = if (assistDay == dayKeyOf(nowMs)) assistToday else 0

    /** debug "기록 초기화": 하루 횟수를 저장값까지 지운다. */
    fun resetDaily() {
        assistDay = null
        assistToday = 0
        daily?.clear()
    }

    /** [다시 시도] 로 이전 알림을 지운 좌석의 실패 기록을 지운다(다시 실패하면 새로 알린다). */
    fun forgetFailures(seat: SeatId) {
        failureAt.keys.removeAll { it.first == seat }
    }

    /** [command] 는 명령을 구분하는 문자열(예: 명령의 toString). */
    fun tryFailure(seat: SeatId, command: String, nowMs: Long): Boolean {
        val key = seat to command
        val last = failureAt[key]
        if (last != null && nowMs - last < AlertPolicy.FAILURE_DEDUPE_MS) return false
        failureAt[key] = nowMs
        return true
    }
}
