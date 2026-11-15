package com.jaribom.app.core

import java.net.URLDecoder
import java.net.URLEncoder
import java.time.Clock
import java.time.DayOfWeek
import java.time.LocalDate

/**
 * 아이 좌석 하나의 설정(7차 F11-2·F11-3). 기기 안(SharedPreferences)에만 저장한다. 통계·로그·알림 기록에는 넣지 않는다.
 *  - [baseItems]: 매일 챙길 기본 물건
 *  - [dayItems]: 요일별 물건(월~일). 예: 화요일 "수영 가방"
 *  - [name]: 아이 이름(선택, F11-3). 비어 있으면 이름 없이 말한다.
 */
data class ChildProfile(
    val name: String = "",
    val baseItems: List<String> = emptyList(),
    val dayItems: Map<DayOfWeek, List<String>> = emptyMap(),
) {
    val isEmpty: Boolean get() = name.isBlank() && baseItems.isEmpty() && dayItems.values.all { it.isEmpty() }
}

object ChildProfiles {
    /** 한 번에 말하는 물건 수 상한(F11-2). 길면 아이가 듣다 놓친다. */
    const val MAX_SPOKEN_ITEMS = 3
    /** 입력 제한: 물건 이름 길이, 목록당 물건 수, 아이 이름 길이 */
    const val MAX_ITEM_CHARS = 20
    const val MAX_ITEMS_PER_LIST = 8
    const val MAX_NAME_CHARS = 10

    /** 기기 로컬 날짜의 요일. [clock] 을 주입하면 테스트에서 요일을 고정할 수 있다. */
    fun today(clock: Clock): DayOfWeek = LocalDate.now(clock).dayOfWeek

    /**
     * 오늘 말할 아이 물건: 오늘 요일 물건 먼저, 그다음 기본 물건. 중복(공백·대소문자 무시)은 빼고 [MAX_SPOKEN_ITEMS]개까지.
     */
    fun spokenItems(profile: ChildProfile?, day: DayOfWeek): List<String> {
        if (profile == null) return emptyList()
        return speakable(profile.dayItems[day].orEmpty(), profile.baseItems, dropUmbrella = false)
    }

    /**
     * 말할 이름 목록을 정리한다: [first] 다음 [then] 순서로 이어 비거나 겹치는 이름을 빼고 [MAX_SPOKEN_ITEMS]개까지 남긴다.
     * [dropUmbrella] 이면 우산은 뺀다(비 오는 날 날씨 문장에서 이미 말했을 때).
     */
    fun speakable(first: List<String>, then: List<String>, dropUmbrella: Boolean): List<String> =
        (first + then).map { it.trim() }
            .filter { it.isNotEmpty() && !(dropUmbrella && it.contains("우산")) }
            .distinctBy { it.replace(" ", "").lowercase() }
            .take(MAX_SPOKEN_ITEMS)

    /** 입력 이름을 정리한다: 앞뒤 공백 제거, 길이 제한. 비면 null. */
    fun cleanItem(raw: String): String? = raw.trim().take(MAX_ITEM_CHARS).takeIf { it.isNotEmpty() }
    fun cleanName(raw: String): String = raw.trim().take(MAX_NAME_CHARS)

    /** [copyLegacy] 결과. [doneSeats] 는 이번까지 완료된 좌석 전체(저장할 값), [copied] 는 이번에 복사한 물건 수. */
    data class Migration(val profiles: Map<SeatId, ChildProfile>, val doneSeats: Set<SeatId>, val copied: Int)

    /**
     * 예전 저장값 이전(F11-2, F11-V 비파괴): 예전 "챙길 물건" 목록 중 키즈락 좌석 전용 물건("이 좌석에서만")을
     * 그 좌석의 "기본 물건"으로 **복사만** 한다. 예전 목록은 건드리지 않는다(같은 물건은 말할 때 [speakable] 이 한 번만 낸다).
     *  - 좌석별로 한 번만: [done] 에 있는 좌석은 다시 복사하지 않는다(지정을 해제했다 다시 지정해도).
     *  - 그 좌석에 기본 물건이 이미 있으면 건너뛴다(사용자가 정한 값을 덮어쓰지 않음). 건너뛴 좌석도 완료다.
     *  - [seats] 는 지금 키즈락 좌석(앱 시작) 또는 방금 지정한 좌석(뒤늦게 지정). 여기 없는 좌석은 완료로 기록하지 않는다.
     */
    fun copyLegacy(items: List<CarryItem>, seats: Set<SeatId>, existing: Map<SeatId, ChildProfile>, done: Set<SeatId>): Migration {
        val out = existing.toMutableMap()
        val doneOut = done.toMutableSet()
        var copied = 0
        for (seat in seats.filter { it in CabinRoutineEngine.CHILD_SEAT_CANDIDATES && it !in done }) {
            doneOut += seat
            val cur = out[seat] ?: ChildProfile()
            if (cur.baseItems.isNotEmpty()) continue
            val names = items.filter { it.condition == ItemCondition.SEAT && it.seat == seat }.mapNotNull { cleanItem(it.name) }
                .distinctBy { it.replace(" ", "").lowercase() }.take(MAX_ITEMS_PER_LIST)
            if (names.isEmpty()) continue
            out[seat] = cur.copy(baseItems = names)
            copied += names.size
        }
        return Migration(out, doneOut, copied)
    }

    // ── 저장 형식(JSON 없이 JVM 테스트로 검증): 레코드 `SEAT|이름|기본,…|MONDAY:물건,…~TUESDAY:…` 를 `;` 로 잇고 각 값은 URL 인코딩 ──
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    private fun dec(s: String) = URLDecoder.decode(s, "UTF-8")

    fun encode(profiles: Map<SeatId, ChildProfile>): String =
        profiles.filterValues { !it.isEmpty }.entries.sortedBy { it.key.ordinal }.joinToString(";") { (seat, p) ->
            val days = DayOfWeek.entries.mapNotNull { d -> p.dayItems[d]?.takeIf { it.isNotEmpty() }?.let { "${d.name}:" + it.joinToString(",") { n -> enc(n) } } }
            listOf(seat.name, enc(p.name), p.baseItems.joinToString(",") { enc(it) }, days.joinToString("~")).joinToString("|")
        }

    fun decode(raw: String?): Map<SeatId, ChildProfile> {
        if (raw.isNullOrBlank()) return emptyMap()
        return runCatching {
            raw.split(";").filter { it.isNotBlank() }.mapNotNull { rec ->
                val f = rec.split("|")
                val seat = SeatId.entries.find { it.name == f.getOrNull(0) }?.takeIf { it in CabinRoutineEngine.CHILD_SEAT_CANDIDATES } ?: return@mapNotNull null
                fun list(s: String?) = s.orEmpty().split(",").filter { it.isNotEmpty() }.map { dec(it) }.mapNotNull { cleanItem(it) }.take(MAX_ITEMS_PER_LIST)
                val days = f.getOrNull(3).orEmpty().split("~").filter { it.isNotEmpty() }.mapNotNull { e ->
                    val day = DayOfWeek.entries.find { it.name == e.substringBefore(":") } ?: return@mapNotNull null
                    day to list(e.substringAfter(":", ""))
                }.toMap()
                seat to ChildProfile(cleanName(dec(f.getOrNull(1).orEmpty())), list(f.getOrNull(2)), days)
            }.toMap()
        }.getOrDefault(emptyMap())
    }
}
