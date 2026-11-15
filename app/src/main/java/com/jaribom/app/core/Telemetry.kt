package com.jaribom.app.core

/**
 * 월별 기록 문자열 변환(ItemStore 가 저장에 쓴다). 칸 순서는 한 번 정하면 바꾸지 않고 뒤에만 붙인다.
 * 예전 7칸·10칸·13칸·15칸·16칸 값도 읽는다(없는 칸은 0). 지금은 18칸(F10-3).
 */
object StatsCodec {
    fun encode(s: CabinRoutineEngine.Stats): String = listOf(
        s.rides, s.restores, s.reminders, s.windowsClosed, s.heatersOff, s.ventsOff, s.rainReminders,
        s.childLocks, s.leftoverAlerts, s.childReminders,
        s.restoreFailures, s.taxiLeftoverAlerts, s.suppressedAlerts,
        s.windowAborts, s.leftoverHeatOffs, s.speechFailures,
        s.adultTaps, s.windowCloseTaps,
    ).joinToString(",")

    fun decode(raw: String?): CabinRoutineEngine.Stats {
        if (raw == null) return CabinRoutineEngine.Stats()
        val p = raw.split(",").map { it.trim().toIntOrNull() ?: 0 }
        fun at(i: Int) = p.getOrElse(i) { 0 }
        return CabinRoutineEngine.Stats(at(0), at(1), at(2), at(3), at(4), at(5), at(6), at(7), at(8), at(9), at(10), at(11), at(12), at(13), at(14), at(15), at(16), at(17))
    }
}

/** 보낼 통계 한 달치. 월별 횟수와 파생 값뿐이다. 기기 ID·계정·위치·월보다 세밀한 시각·물건 이름·좌석별 내역은 없다. */
data class UsageAggregate(
    val schemaVersion: Int,
    val month: String,
    /** 앱을 처음 실행한 달(YYYY-MM, 월 단위만). 코호트 계산용(F10-3) */
    val installMonth: String,
    val rides: Int,
    val restores: Int,
    val restoreFailures: Int,
    val doneTotal: Int,
    val childLocks: Int,
    val leftoverAlerts: Int,
    val childReminders: Int,
    val taxiLeftoverAlerts: Int,
    val suppressedAlerts: Int,
    /** 동승 하차가 1번 이상 있던 날 수(날짜 자체는 담지 않는다) */
    val activeTripDays: Int,
    /** 그 달 하차가 있던 주 수 0~5(7일 단위, 날짜 자체는 담지 않는다) */
    val activeWeeks: Int,
    /** 그 달 동승 하차가 [UsageTelemetry.RETAINED_RIDES] 회 이상이면 true — 이름은 유지하지만 뜻은 "사용 강도"다(Retention 은 installMonth 코호트로 계산) */
    val retained: Boolean,
    /** 알림 버튼을 눌러 실행한 횟수 */
    val heatOffTaps: Int,
    val adultTaps: Int,
    val windowCloseTaps: Int,
    /** 창문을 닫다가 멈춘 횟수 */
    val windowStops: Int,
    val speechFailures: Int,
)

object UsageTelemetry {
    const val SCHEMA_VERSION = 2
    const val RETAINED_RIDES = 4

    /** JSON 키 목록(순서 포함). 테스트가 고정해 둔다 — 키를 늘리려면 테스트부터 고쳐야 한다. */
    val KEYS: List<String> = listOf(
        "schemaVersion", "month", "installMonth", "rides", "restores", "restoreFailures", "doneTotal", "childLocks", "leftoverAlerts",
        "childReminders", "taxiLeftoverAlerts", "suppressedAlerts", "activeTripDays", "activeWeeks", "retained",
        "heatOffTaps", "adultTaps", "windowCloseTaps", "windowStops", "speechFailures",
    )

    /**
     * 정리 성공률 = restores / (restores + restoreFailures). 분모가 0 이면 null. 키가 아니라 두 키로 계산하는 파생 값이다(F10-3).
     * 정리가 일부라도 실패한 하차는 restoreFailures 에도 세므로, 일부 성공·일부 실패인 하차는 양쪽에 들어간다.
     */
    fun successRate(s: CabinRoutineEngine.Stats): Double? {
        val denom = s.restores + s.restoreFailures
        return if (denom == 0) null else s.restores.toDouble() / denom
    }

    /** 하차가 있던 날 번호(1~31)를 7일 단위 주(1~7일=1주 … 29~31일=5주)로 묶어 서로 다른 주의 수(0~5). */
    fun activeWeeks(days: Set<Int>): Int = days.filter { it in 1..31 }.map { (it - 1) / 7 }.toSet().size

    fun build(month: String, s: CabinRoutineEngine.Stats, activeDays: Set<Int>, installMonth: String) = UsageAggregate(
        schemaVersion = SCHEMA_VERSION, month = month, installMonth = installMonth, rides = s.rides, restores = s.restores,
        restoreFailures = s.restoreFailures,
        doneTotal = s.doneTotal, childLocks = s.childLocks, leftoverAlerts = s.leftoverAlerts, childReminders = s.childReminders,
        taxiLeftoverAlerts = s.taxiLeftoverAlerts, suppressedAlerts = s.suppressedAlerts,
        activeTripDays = activeDays.size, activeWeeks = activeWeeks(activeDays), retained = s.rides >= RETAINED_RIDES,
        heatOffTaps = s.leftoverHeatOffs, adultTaps = s.adultTaps, windowCloseTaps = s.windowCloseTaps,
        windowStops = s.windowAborts, speechFailures = s.speechFailures,
    )

    /** 사람이 읽기 좋은 JSON(미리 보기 그대로 보낼 내용). org.json 없이 만든다(JVM 단위 테스트에서도 돈다). */
    fun toJson(a: UsageAggregate): String = buildString {
        append("{\n")
        append("  \"schemaVersion\": ${a.schemaVersion},\n")
        append("  \"month\": \"${a.month}\",\n")
        append("  \"installMonth\": \"${a.installMonth}\",\n")
        append("  \"rides\": ${a.rides},\n")
        append("  \"restores\": ${a.restores},\n")
        append("  \"restoreFailures\": ${a.restoreFailures},\n")
        append("  \"doneTotal\": ${a.doneTotal},\n")
        append("  \"childLocks\": ${a.childLocks},\n")
        append("  \"leftoverAlerts\": ${a.leftoverAlerts},\n")
        append("  \"childReminders\": ${a.childReminders},\n")
        append("  \"taxiLeftoverAlerts\": ${a.taxiLeftoverAlerts},\n")
        append("  \"suppressedAlerts\": ${a.suppressedAlerts},\n")
        append("  \"activeTripDays\": ${a.activeTripDays},\n")
        append("  \"activeWeeks\": ${a.activeWeeks},\n")
        append("  \"retained\": ${a.retained},\n")
        append("  \"heatOffTaps\": ${a.heatOffTaps},\n")
        append("  \"adultTaps\": ${a.adultTaps},\n")
        append("  \"windowCloseTaps\": ${a.windowCloseTaps},\n")
        append("  \"windowStops\": ${a.windowStops},\n")
        append("  \"speechFailures\": ${a.speechFailures}\n")
        append("}")
    }
}

/** 전송부. 이번 단계는 구현이 로그 출력(no-op)뿐이다 — 네트워크 코드와 새 권한은 넣지 않는다. */
interface TelemetrySink {
    fun send(json: String)
}

/** 보낼 집계의 로컬 사본 저장소. 토글을 끄면 [clear] 로 모두 지운다. */
interface TelemetryCopies {
    fun put(month: String, json: String)
    fun all(): Map<String, String>
    fun clear()
}

/**
 * 옵트인 익명 통계의 켜기/끄기와 사본 관리. 기본값은 꺼짐이다.
 *  - 꺼져 있으면 사본을 만들지 않고 미리 보기는 "보내지 않음"이다.
 *  - 끄면 지금까지 만든 사본을 지운다(통계 원본 Stats 는 '이번 달 대신한 일'에 쓰므로 그대로 둔다).
 */
class TelemetryManager(private val copies: TelemetryCopies, private val sink: TelemetrySink, enabled: Boolean = DEFAULT_ENABLED) {
    companion object {
        const val DEFAULT_ENABLED = false
        const val OFF_PREVIEW = "보내지 않음"
    }

    var enabled: Boolean = enabled
        private set

    fun setEnabled(on: Boolean) {
        enabled = on
        if (!on) copies.clear()
    }

    fun update(month: String, s: CabinRoutineEngine.Stats, activeDays: Set<Int>, installMonth: String) {
        if (!enabled) return
        copies.put(month, UsageTelemetry.toJson(UsageTelemetry.build(month, s, activeDays, installMonth)))
    }

    fun preview(month: String, s: CabinRoutineEngine.Stats, activeDays: Set<Int>, installMonth: String): String =
        if (!enabled) OFF_PREVIEW else UsageTelemetry.toJson(UsageTelemetry.build(month, s, activeDays, installMonth))

    /** 보낼 사본을 전송부로 넘긴다. 꺼져 있으면 아무것도 하지 않는다. */
    fun flush() {
        if (!enabled) return
        copies.all().values.forEach(sink::send)
    }
}
