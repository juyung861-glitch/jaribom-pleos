package com.jaribom.app.data

import android.content.Context
import androidx.core.content.edit
import com.jaribom.app.core.CabinRoutineEngine
import com.jaribom.app.core.CarryItem
import com.jaribom.app.core.ChildProfile
import com.jaribom.app.core.ChildProfiles
import com.jaribom.app.core.DailyCountStore
import com.jaribom.app.core.DriverReportMode
import com.jaribom.app.core.ExitMessageRules
import com.jaribom.app.core.ItemCondition
import com.jaribom.app.core.SeatId
import com.jaribom.app.core.SeatSignalQuality
import com.jaribom.app.core.StatsCodec
import com.jaribom.app.core.TelemetryCopies
import com.jaribom.app.core.TelemetryManager
import com.jaribom.app.weather.Region
import com.jaribom.app.weather.Regions
import org.json.JSONArray
import org.json.JSONObject

/**
 * 챙길 물건 저장소. SharedPreferences + JSON 으로 충분히 작다(DB 불필요).
 * 개인정보 최소 수집: 물건 이름과 조건만 저장하고, 동승자 이름·위치 이력은 저장하지 않는다.
 */
class ItemStore(context: Context) {
    private val prefs = context.getSharedPreferences("jaribom", Context.MODE_PRIVATE)

    fun load(): List<CarryItem> = runCatching {
        val arr = JSONArray(prefs.getString("items", "[]") ?: "[]")
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            // 옛 조건 WITH_PASSENGER(동승자 있을 때)·모르는 값은 ALWAYS 로 읽는다. 좌석이 없는 SEAT 도 ALWAYS.
            val seat = SeatId.entries.find { it.name == o.optString("seat") }?.takeIf { !it.isDriver }
            val cond = runCatching { ItemCondition.valueOf(o.getString("condition")) }.getOrDefault(ItemCondition.ALWAYS)
                .let { if (it == ItemCondition.SEAT && seat == null) ItemCondition.ALWAYS else it }
            CarryItem(
                id = o.getLong("id"),
                name = o.getString("name"),
                condition = cond,
                seat = if (cond == ItemCondition.SEAT) seat else null,
            )
        }
    }.getOrDefault(emptyList())

    fun save(items: List<CarryItem>) {
        val arr = JSONArray()
        items.forEach { item ->
            val o = JSONObject().put("id", item.id).put("name", item.name).put("condition", item.condition.name)
            item.seat?.let { o.put("seat", it.name) }
            arr.put(o)
        }
        prefs.edit { putString("items", arr.toString()) }
    }

    /** 관리할 좌석(설정 화면 스위치). 저장값이 없으면 동승자 좌석 전부. */
    fun loadManagedSeats(): Set<SeatId> {
        val raw = prefs.getString("managed", null) ?: return SeatId.passengers.toSet()
        return raw.split(",").mapNotNull { n -> SeatId.entries.find { it.name == n } }.filter { !it.isDriver }.toSet()
    }

    fun saveManagedSeats(seats: Set<SeatId>) {
        prefs.edit { putString("managed", seats.joinToString(",") { it.name }) }
    }

    /**
     * 키즈락 좌석(설정, 내부 키 child_seats). 저장값이 없으면 없음. 뒷좌석 왼쪽·오른쪽만 읽는다. 여기에는 좌석만 저장한다.
     * 아이 나이는 받지 않는다. 이름은 선택으로 받아 [loadChildProfiles] 에 이 기기 안에만 저장하고 통계·로그에는 남기지 않는다(F11-3).
     */
    fun loadChildSeats(): Set<SeatId> {
        val raw = prefs.getString("child_seats", null) ?: return emptySet()
        return raw.split(",").mapNotNull { n -> SeatId.entries.find { it.name == n } }.filter { it in CabinRoutineEngine.CHILD_SEAT_CANDIDATES }.toSet()
    }

    fun saveChildSeats(seats: Set<SeatId>) {
        prefs.edit { putString("child_seats", seats.filter { it in CabinRoutineEngine.CHILD_SEAT_CANDIDATES }.joinToString(",") { it.name }) }
    }

    /** 아이 좌석별 기본·요일별 물건(F11-2)과 이름(F11-3). 이 기기 안에만 있고 백업·통계·로그에 넣지 않는다. */
    fun loadChildProfiles(): Map<SeatId, ChildProfile> = ChildProfiles.decode(prefs.getString(CHILD_PROFILES, null))
    fun saveChildProfiles(p: Map<SeatId, ChildProfile>) = prefs.edit { putString(CHILD_PROFILES, ChildProfiles.encode(p)) }

    /**
     * 예전 물건 목록 이전(F11-2, F11-V 비파괴). [seats] 중 아직 하지 않은 좌석에 한 번만: 그 좌석 전용 물건을 기본 물건으로
     * 복사한다(예전 목록은 그대로, 기본 물건이 이미 있으면 건너뜀). 완료 좌석은 [CHILD_MIGRATED_SEATS] 에 남겨 다시 하지 않는다.
     * 옛 플래그 child_profiles_migrated_v1 은 읽지도 지우지도 않는다. @return 복사한 물건 수
     */
    fun copyLegacyItemsOnce(seats: Set<SeatId>): Int {
        val done = loadChildMigratedSeats()
        if ((seats - done).isEmpty()) return 0
        val before = loadChildProfiles()
        val m = ChildProfiles.copyLegacy(load(), seats, before, done)
        if (m.profiles != before) saveChildProfiles(m.profiles)
        prefs.edit { putString(CHILD_MIGRATED_SEATS, m.doneSeats.joinToString(",") { it.name }) }
        return m.copied
    }

    /** 이전을 마친 키즈락 좌석(F11-V). 지정을 해제해도 지우지 않는다. */
    fun loadChildMigratedSeats(): Set<SeatId> =
        (prefs.getString(CHILD_MIGRATED_SEATS, null) ?: "").split(",").mapNotNull { n -> SeatId.entries.find { it.name == n } }.toSet()

    /** 좌석별 최근 문 열림 기록(F11-4). `ROW_2_LEFT:1101;…` — 1 은 착좌가 함께 잡힘, 0 은 문만 열림. 좌석·0/1 뿐이라 개인 정보가 없다. */
    fun loadSignalHistory(): Map<SeatId, List<Boolean>> =
        (prefs.getString(SIGNAL_HISTORY, null) ?: "").split(";").mapNotNull { rec ->
            val seat = SeatId.entries.find { it.name == rec.substringBefore(":") }?.takeIf { !it.isDriver } ?: return@mapNotNull null
            seat to rec.substringAfter(":", "").filter { it == '0' || it == '1' }.map { it == '1' }.takeLast(SeatSignalQuality.WINDOW)
        }.toMap()

    fun saveSignalHistory(h: Map<SeatId, List<Boolean>>) = prefs.edit {
        putString(SIGNAL_HISTORY, h.entries.joinToString(";") { (s, l) -> s.name + ":" + l.joinToString("") { if (it) "1" else "0" } })
    }

    /** 조건이 없을 때 기본 한 마디 / 무음 */
    fun loadQuietPolicy(): ExitMessageRules.QuietPolicy =
        runCatching { ExitMessageRules.QuietPolicy.valueOf(prefs.getString("quiet", null)!!) }
            .getOrDefault(ExitMessageRules.QuietPolicy.SHORT_PHRASE)

    fun saveQuietPolicy(p: ExitMessageRules.QuietPolicy) {
        prefs.edit { putString("quiet", p.name) }
    }

    /** 날씨 지역(A6). 저장값이 없거나 표에 없으면 기본(서울 중구). */
    fun loadRegion(): Region = Regions.find(prefs.getString("region_sido", null), prefs.getString("region_sigungu", null)) ?: Regions.default
    fun saveRegion(r: Region) = prefs.edit { putString("region_sido", r.sido); putString("region_sigungu", r.sigungu) }

    /** 착좌 반전 판정(F6-5). 아직 판정 전이면 null. 다음 실행부터 쓴다. */
    fun loadOccupancyInverted(): Boolean? = if (prefs.contains(OCC_INVERTED)) prefs.getBoolean(OCC_INVERTED, false) else null
    fun saveOccupancyInverted(v: Boolean) = prefs.edit { putBoolean(OCC_INVERTED, v); putBoolean("occ_inverted_fresh", true) }

    /** 반전 판정을 새로 저장한 뒤 아직 운행이 끝나지 않았다(F10-5). 이 값이 참인 채 시작한 첫 운행은 창문을 버튼으로만 닫는다. */
    fun loadOccupancyFresh(): Boolean = prefs.getBoolean("occ_inverted_fresh", false)
    fun clearOccupancyFresh() = prefs.edit { remove("occ_inverted_fresh") }

    /** debug 수동 지정(착좌 반전). null = 자동. release 에서는 읽지 않는다. */
    fun loadOccupancyOverride(): Boolean? = if (prefs.contains(OCC_OVERRIDE)) prefs.getBoolean(OCC_OVERRIDE, false) else null
    fun saveOccupancyOverride(v: Boolean?) = prefs.edit { if (v == null) remove(OCC_OVERRIDE) else putBoolean(OCC_OVERRIDE, v) }

    /** 설정 "탑승 멘트"(기본 켜짐) */
    fun loadBoardingVoice(): Boolean = prefs.getBoolean("voice_boarding", true)
    fun saveBoardingVoice(on: Boolean) = prefs.edit { putBoolean("voice_boarding", on) }

    /** 설정 "하차 멘트"(기본 켜짐) */
    fun loadExitVoice(): Boolean = prefs.getBoolean("voice_exit", true)
    fun saveExitVoice(on: Boolean) = prefs.edit { putBoolean("voice_exit", on) }

    /** 메인 "키즈락 모드"(F12-4, 기본 켜짐 — 예전처럼 지정 좌석은 키즈락으로 동작). 좌석 목록은 예전 키 child_seats 를 그대로 쓴다. */
    fun loadKidsLockOn(): Boolean = prefs.getBoolean("kids_lock_on", true)
    fun saveKidsLockOn(on: Boolean) = prefs.edit { putBoolean("kids_lock_on", on) }

    /** 설정 "뒷좌석 통풍도 켜기"(F12-5, 기본 꺼짐) */
    fun loadRearVentOnBoard(): Boolean = prefs.getBoolean("rear_vent_on_board", false)
    fun saveRearVentOnBoard(on: Boolean) = prefs.edit { putBoolean("rear_vent_on_board", on) }

    /** 설정 "도로변 하차 때 뒤 차 확인"(F12-6 대체안, 기본 꺼짐) */
    fun loadRoadsideRearCheck(): Boolean = prefs.getBoolean("roadside_rear_check", false)
    fun saveRoadsideRearCheck(on: Boolean) = prefs.edit { putBoolean("roadside_rear_check", on) }

    /** 설정 "운전자 보고"(F12-1). 저장값이 없거나 모르는 값이면 기본(경고만). */
    fun loadReportMode(): DriverReportMode = DriverReportMode.fromName(prefs.getString(REPORT_MODE, null))
    fun saveReportMode(m: DriverReportMode) = prefs.edit { putString(REPORT_MODE, m.name) }

    /** 설정 "운전석 창문 안내"·"실내 온도 안내"(F12-1, 기본 켜짐) */
    fun loadDriverWindowNotice(): Boolean = prefs.getBoolean(DRIVER_WINDOW_NOTICE, true)
    fun saveDriverWindowNotice(on: Boolean) = prefs.edit { putBoolean(DRIVER_WINDOW_NOTICE, on) }
    fun loadCabinHeatNotice(): Boolean = prefs.getBoolean(CABIN_HEAT_NOTICE, true)
    fun saveCabinHeatNotice(on: Boolean) = prefs.edit { putBoolean(CABIN_HEAT_NOTICE, on) }

    /**
     * 월별 누적 기록(A4). 키 "stats_yyyy-MM", 값 "rides,restores,reminders,windows,heaters,vents,rain,childLocks,leftovers,childReminders"
     * (뒤 3개는 5차 심사 F4. 예전 7칸 값은 나머지를 0으로 읽는다).
     * 최근 [KEEP_MONTHS]개월만 남긴다. 옛 키 "stats"(예전 방식으로 셈 값)는 읽지 않고 지운다.
     */
    fun loadStats(month: String): CabinRoutineEngine.Stats {
        dropOldStats()
        return StatsCodec.decode(prefs.getString(STATS_PREFIX + month, null))
    }

    fun saveStats(month: String, s: CabinRoutineEngine.Stats) {
        val v = StatsCodec.encode(s)
        val old = prefs.all.keys.filter { it.startsWith(STATS_PREFIX) && it != STATS_PREFIX + month }.sortedDescending().drop(KEEP_MONTHS - 1)
        prefs.edit {
            putString(STATS_PREFIX + month, v)
            old.forEach { remove(it) }
        }
    }

    /** 월별 기록을 모두 지운다(debug DebugPanel "기록 초기화"). 동승 하차가 있던 날 기록도 같이 지운다. */
    fun clearStats() {
        val keys = prefs.all.keys.filter { it.startsWith(STATS_PREFIX) || it.startsWith(ACTIVE_DAYS_PREFIX) || it.startsWith(TELE_COPY_PREFIX) }
        prefs.edit { keys.forEach { remove(it) }; remove(ASSIST_DAY); remove(ASSIST_COUNT) }
    }

    /** 동승 하차가 있던 날(그 달의 일자 번호만 저장, 6차 F6-7 activeTripDays 용). */
    fun markActiveDay(month: String, day: Int) {
        val cur = loadActiveDaySet(month)
        if (day in cur) return
        prefs.edit { putString(ACTIVE_DAYS_PREFIX + month, (cur + day).sorted().joinToString(",")) }
    }
    fun activeDays(month: String): Set<Int> = loadActiveDaySet(month)

    /**
     * 앱을 처음 실행한 달(YYYY-MM, 월 단위만, F10-3). 처음 부르면 [currentMonth] 를 저장한다. 통계 코호트(Retention)용이며 기록 초기화로도 지우지 않는다.
     */
    fun installMonth(currentMonth: String): String =
        prefs.getString("install_month", null) ?: currentMonth.also { prefs.edit { putString("install_month", it) } }
    private fun loadActiveDaySet(month: String): Set<Int> =
        (prefs.getString(ACTIVE_DAYS_PREFIX + month, null) ?: "").split(",").mapNotNull { it.toIntOrNull() }.toSet()

    /** 하루 보조 알림 횟수(F7-4). 날짜 키와 함께 저장해 앱을 다시 켜도 이어서 센다. */
    val dailyAssistCount: DailyCountStore = object : DailyCountStore {
        override fun load(): Pair<String, Int>? {
            val day = prefs.getString(ASSIST_DAY, null) ?: return null
            return day to prefs.getInt(ASSIST_COUNT, 0)
        }
        override fun save(day: String, count: Int) = prefs.edit { putString(ASSIST_DAY, day); putInt(ASSIST_COUNT, count) }
        override fun clear() = prefs.edit { remove(ASSIST_DAY); remove(ASSIST_COUNT) }
    }

    /** debug 전용(F9-3): 좌석별로 "착좌 미지원"·"벨트 미지원"을 강제한다(에뮤레이터 확인용). release 에서는 읽지 않는다. 다음 실행부터 적용. */
    data class SignalOverrides(val occupancyOff: Set<SeatId> = emptySet(), val beltOff: Set<SeatId> = emptySet())

    fun loadSignalOverrides(): SignalOverrides {
        fun seats(key: String) = (prefs.getString(key, null) ?: "").split(",").mapNotNull { n -> SeatId.entries.find { it.name == n } }.filter { !it.isDriver }.toSet()
        return SignalOverrides(seats(SIG_OCC_OFF), seats(SIG_BELT_OFF))
    }

    fun saveSignalOverrides(o: SignalOverrides) = prefs.edit {
        putString(SIG_OCC_OFF, o.occupancyOff.joinToString(",") { it.name })
        putString(SIG_BELT_OFF, o.beltOff.joinToString(",") { it.name })
    }

    /** 택시 모드(F6-8). 기본 꺼짐. */
    /** 속도 권한을 이미 한 번 물었는지(F10-2). 거부돼도 다시 묻지 않고 설정에서 [권한 허용]으로 다시 요청한다. */
    fun loadSpeedPermissionAsked(): Boolean = prefs.getBoolean("speed_permission_asked", false)
    fun saveSpeedPermissionAsked() = prefs.edit { putBoolean("speed_permission_asked", true) }

    fun loadTaxiMode(): Boolean = prefs.getBoolean(TAXI_MODE, false)
    fun saveTaxiMode(on: Boolean) = prefs.edit { putBoolean(TAXI_MODE, on) }

    /** 옵트인 익명 사용 통계(F6-7). 기본 꺼짐. */
    fun loadTelemetry(): Boolean = prefs.getBoolean(TELEMETRY_ON, TelemetryManager.DEFAULT_ENABLED)
    fun saveTelemetry(on: Boolean) = prefs.edit { putBoolean(TELEMETRY_ON, on) }

    /** 보낼 집계의 로컬 사본(토글을 켜 둔 동안만 있다). */
    val telemetryCopies: TelemetryCopies = object : TelemetryCopies {
        override fun put(month: String, json: String) { prefs.edit { putString(TELE_COPY_PREFIX + month, json) } }
        override fun all(): Map<String, String> =
            prefs.all.filterKeys { it.startsWith(TELE_COPY_PREFIX) }.mapKeys { it.key.removePrefix(TELE_COPY_PREFIX) }.mapValues { it.value as? String ?: "" }
        override fun clear() {
            val keys = prefs.all.keys.filter { it.startsWith(TELE_COPY_PREFIX) }
            prefs.edit { keys.forEach { remove(it) } }
        }
    }

    /**
     * 개발 중에 쌓인 기록 정리(한 번만). 옛 키 "stats"는 읽지 않고 지우고, 이미 옮겨 둔 월별 기록도
     * 예전 방식으로 센 값이 섞여 있어 전부 지운다. 끝나면 [STATS_RESET_FLAG]를 세운다.
     */
    private fun dropOldStats() {
        if (prefs.getBoolean(STATS_RESET_FLAG, false)) return
        val keys = prefs.all.keys.filter { it == "stats" || it.startsWith(STATS_PREFIX) }
        prefs.edit {
            keys.forEach { remove(it) }
            putBoolean(STATS_RESET_FLAG, true)
        }
    }

    private companion object {
        const val REPORT_MODE = "report_mode"
        const val DRIVER_WINDOW_NOTICE = "notice_driver_window"
        const val CABIN_HEAT_NOTICE = "notice_cabin_heat"
        const val TAXI_MODE = "taxi_mode"
        const val SIGNAL_HISTORY = "signal_history"
        const val CHILD_PROFILES = "child_profiles"
        /** F11-V: 좌석별 이전 완료 기록. 옛 플래그 child_profiles_migrated_v1 은 더 읽지 않는다(지우지도 않는다). */
        const val CHILD_MIGRATED_SEATS = "child_migrated_seats"
        const val SIG_OCC_OFF = "sig_occ_off"
        const val SIG_BELT_OFF = "sig_belt_off"
        const val ASSIST_DAY = "assist_day"
        const val ASSIST_COUNT = "assist_count"
        const val TELEMETRY_ON = "telemetry_on"
        const val ACTIVE_DAYS_PREFIX = "active_days_"
        const val TELE_COPY_PREFIX = "tele_copy_"
        const val OCC_INVERTED = "occ_inverted"
        const val OCC_OVERRIDE = "occ_inverted_override"
        const val STATS_PREFIX = "stats_"
        const val KEEP_MONTHS = 12
        /** stats_ 로 시작하면 clearStats·saveStats 정리에 같이 지워지므로 다른 이름을 쓴다. */
        const val STATS_RESET_FLAG = "dev_stats_reset_v1"
    }
}