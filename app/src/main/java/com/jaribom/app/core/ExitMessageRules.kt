package com.jaribom.app.core

/**
 * 하차 멘트 규칙. 말하는 시점을 둘로 나눈다(심사위원 2차 평가 반영).
 *  ① 문이 열릴 때 — 동승자가 아직 차 안에서 듣는 시점: 물건·날씨 안내 [passengerMessage]
 *  ② 문이 닫힌 뒤 — 운전자에게 정리 보고: 실제로 실행된 결과만 [driverReport]
 *
 * 사용자 결정 반영
 *  - 기본 멘트는 입력 없이도 "두고 가시는 물건은 없나요?"
 *  - 날씨는 실제 기상 정보(기상청) + 실내 온도
 *  - 운전석 창문은 손대지 않고 알리기만. 그것도 비가 오거나 1시간 안에 비 소식이 있을 때만(4차 심사 A12)
 *  - 아무 조건도 없을 때의 정책(무음/한 마디)은 설정 토글. 한 마디는 운행당 1번만(A9)
 */
object ExitMessageRules {

    data class Thresholds(
        val hotCabinC: Float = 30f,
        val coldOutdoorC: Float = 3f,
        val windowOpenMinPercent: Int = 5,
    )

    enum class QuietPolicy { SILENT, SHORT_PHRASE }

    data class Message(val sentences: List<String>, val firedRules: List<String>) {
        /** 기본 멘트 외에 날씨·물건 안내가 붙었는지(기록 화면의 '챙김 알림' 수) */
        val hasReminder: Boolean get() = firedRules.size > 1 && "QUIET_SILENT" !in firedRules
    }

    const val BASE_PHRASE = "두고 가시는 물건은 없나요?"
    /** 아이 좌석 기본 멘트. 붙일 물건이 있으면 "{물건} 챙겼지? 조심히 내려." */
    const val CHILD_BASE_PHRASE = "두고 가는 거 없지? 조심히 내려."

    /**
     * 도로변 하차 뒤 차 확인(F12-6 대체안, 설정 기본 꺼짐). 순정 SEA 상태를 읽을 API 가 없어 경고가 아니라 안내 한 문장이다.
     * D·N 에서 문이 열린 하차 안내 맨 앞에 붙는다. 도어는 건드리지 않는다.
     * 키즈락 모드로 탄 좌석은 설정과 상관없이 항상 아이 말투 문장을 붙인다(F13-1).
     */
    const val REAR_CHECK_POLITE = "뒤에 오는 차를 확인하고 내리세요."
    const val REAR_CHECK_CHILD = "뒤에 차 오는지 꼭 보고 내려."

    /**
     * @param seat 문이 열린(내리는) 좌석. [ItemCondition.SEAT] 물건은 이 좌석일 때만 붙는다.
     * @param baseAlreadySaid 이번 운행에서 기본 멘트를 이미 말했으면 true. 붙일 물건·날씨 문장이 없을 때 다시 말하지 않는다.
     */
    fun passengerMessage(
        weather: WeatherInfo,
        snapshot: VehicleSnapshot,
        items: List<CarryItem>,
        thresholds: Thresholds = Thresholds(),
        quietPolicy: QuietPolicy = QuietPolicy.SHORT_PHRASE,
        seat: SeatId? = null,
        baseAlreadySaid: Boolean = false,
        picker: PhrasePicker = PhrasePicker.LEGACY,
        /** 도로변(D·N) 하차 뒤 차 확인 문장을 맨 앞에 붙인다(F12-6). */
        rearCheck: Boolean = false,
    ): Message {
        val m = passengerMessageBody(weather, snapshot, items, thresholds, quietPolicy, seat, baseAlreadySaid, picker)
        if (!rearCheck) return m
        // 뒤 차 확인은 안전 안내라 무음·운행당 1번(기본 멘트) 규칙과 상관없이 매번 말한다. 기본 멘트 규칙은 그대로다.
        return Message(listOf(REAR_CHECK_POLITE) + m.sentences, listOf("REAR_CHECK") + m.firedRules)
    }

    private fun passengerMessageBody(
        weather: WeatherInfo, snapshot: VehicleSnapshot, items: List<CarryItem>, thresholds: Thresholds, quietPolicy: QuietPolicy,
        seat: SeatId?, baseAlreadySaid: Boolean, picker: PhrasePicker,
    ): Message {
        // 존댓말 풀만 쓴다(F11-1: 택시 모드는 이 함수만 타서 반말 문장이 절대 선택되지 않는다).
        val lines = mutableListOf(picker.pick(seat, ExitPhrasePool.Polite.base))
        val fired = mutableListOf("BASE_DOOR_OPEN")
        val rain = weather.rainingNow == true || weather.rainWithinHour == true
        if (weather.rainingNow == true) {
            lines += picker.pick(seat, ExitPhrasePool.Polite.rainNow); fired += "WEATHER_RAIN_NOW"
        } else if (weather.rainWithinHour == true) {
            lines += picker.pick(seat, ExitPhrasePool.Polite.rainSoon); fired += "WEATHER_RAIN_SOON"
        }
        val cabin = snapshot.hvac.cabinTempC
        val outdoor = weather.outdoorTempC
        val hot = cabin != null && cabin >= thresholds.hotCabinC
        val cold = outdoor != null && outdoor <= thresholds.coldOutdoorC
        if (cold) { lines += picker.pick(seat, ExitPhrasePool.Polite.cold); fired += "OUTDOOR_COLD" }

        // 말할 때 물건은 최대 3개(F12-3, 아이 말투와 같은 상한).
        val matched = matchedItems(items, rain, hot, cold, seat).take(ChildProfiles.MAX_SPOKEN_ITEMS)
        if (matched.isNotEmpty()) {
            lines += itemsSentence(matched, seat, picker); fired += "ITEMS"
        }
        if (fired.size == 1 && quietPolicy == QuietPolicy.SILENT) return Message(emptyList(), fired + "QUIET_SILENT")
        if (fired.size == 1 && baseAlreadySaid) return Message(emptyList(), fired + "BASE_ONCE_PER_TRIP")
        return Message(lines, fired)
    }

    /**
     * 아이 좌석(설정 "아이 좌석") 하차 안내. 아이에게 하는 말투다.
     * 날씨 문장 뒤에 물건이 있으면 "{물건} 챙겼지? 조심히 내려.", 없으면 [CHILD_BASE_PHRASE].
     * "조심히 내려"는 안전 안내라 운행당 1번 규칙(A9)을 적용하지 않는다. 무음 설정([QuietPolicy.SILENT])은 따른다.
     */
    fun childMessage(
        weather: WeatherInfo,
        snapshot: VehicleSnapshot,
        items: List<CarryItem>,
        seat: SeatId,
        thresholds: Thresholds = Thresholds(),
        quietPolicy: QuietPolicy = QuietPolicy.SHORT_PHRASE,
        picker: PhrasePicker = PhrasePicker.LEGACY,
        /** 아이 이름(F11-3). 비어 있으면 부르지 않는다. 첫 문장 앞에 호격으로 붙는다. */
        name: String? = null,
        /** 이 아이의 오늘 챙길 물건(요일별 → 기본, F11-2). 등록 물건보다 앞에 말한다. 이름만 받고 조건은 없다. */
        extraItems: List<String> = emptyList(),
        /** 도로변(D·N) 하차 뒤 차 확인 문장을 맨 앞에 붙인다(F12-6). 이름 호격은 이 문장 앞에 붙는다. */
        rearCheck: Boolean = false,
    ): Message {
        val lines = mutableListOf<String>()
        val fired = mutableListOf("CHILD_DOOR_OPEN")
        if (rearCheck) { lines += REAR_CHECK_CHILD; fired += "REAR_CHECK" }
        val rain = weather.rainingNow == true || weather.rainWithinHour == true
        if (weather.rainingNow == true) {
            lines += picker.pick(seat, ExitPhrasePool.Child.rainNow); fired += "WEATHER_RAIN_NOW"
        } else if (weather.rainWithinHour == true) {
            lines += picker.pick(seat, ExitPhrasePool.Child.rainSoon); fired += "WEATHER_RAIN_SOON"
        }
        val cabin = snapshot.hvac.cabinTempC
        val outdoor = weather.outdoorTempC
        val hot = cabin != null && cabin >= thresholds.hotCabinC
        val cold = outdoor != null && outdoor <= thresholds.coldOutdoorC
        if (cold) { lines += picker.pick(seat, ExitPhrasePool.Child.cold); fired += "OUTDOOR_COLD" }
        val spoken = ChildProfiles.speakable(extraItems, matchedItems(items, rain, hot, cold, seat).map { it.name }, dropUmbrella = rain)
        if (spoken.isNotEmpty()) {
            lines += childItemsSentence(spoken, seat, picker); fired += "ITEMS"
        } else lines += picker.pick(seat, ExitPhrasePool.Child.base)
        if (fired.size == 1 && quietPolicy == QuietPolicy.SILENT) return Message(emptyList(), fired + "QUIET_SILENT")
        return Message(withName(lines, name), fired)
    }

    /** 아이 좌석이 같은 하차 묶음에 늦게 합류했을 때의 한 마디. 이 좌석 물건이 있으면 붙인다. */
    fun childJoinedMessage(
        items: List<CarryItem>, seat: SeatId,
        picker: PhrasePicker = PhrasePicker.LEGACY, name: String? = null,
        extraItems: List<String> = emptyList(),
    ): String {
        val own = items.filter { it.condition == ItemCondition.SEAT && it.seat == seat }.map { it.name }
        val spoken = ChildProfiles.speakable(extraItems, own, dropUmbrella = false)
        val text = if (spoken.isNotEmpty()) childItemsSentence(spoken, seat, picker) else picker.pick(seat, ExitPhrasePool.Child.base)
        return withName(listOf(text), name).single()
    }

    /** 첫 문장 앞에 이름 호격("지민아, ")을 붙인다. 이름이 비어 있으면 그대로(F11-3). */
    private fun withName(lines: List<String>, name: String?): List<String> {
        val call = KoreanPostposition.vocative(name.orEmpty())
        if (call.isEmpty() || lines.isEmpty()) return lines
        return listOf("$call, ${lines.first()}") + lines.drop(1)
    }

    /** 등록 물건 중 조건이 맞는 것. 날씨 문장에서 이미 말한 우산은 빼서 중복을 없앤다. */
    private fun matchedItems(items: List<CarryItem>, rain: Boolean, hot: Boolean, cold: Boolean, seat: SeatId?): List<CarryItem> =
        items.filter {
            when (it.condition) {
                ItemCondition.ALWAYS -> true
                ItemCondition.RAIN -> rain
                ItemCondition.HOT -> hot
                ItemCondition.COLD -> cold
                ItemCondition.SEAT -> seat != null && it.seat == seat
            }
        }.filterNot { rain && it.name.contains("우산") }

    /** 아이 말투: "지갑이랑 우산 챙겼지? 조심히 내려." 받침에 맞는 조사로 잇는다(F10-6). 틀은 풀에서 고른다(F11-1). */
    private fun childItemsSentence(names: List<String>, seat: SeatId?, picker: PhrasePicker) =
        ExitPhrasePool.fill(picker.pick(seat, ExitPhrasePool.Child.items), KoreanPostposition.joinChild(names))

    /** 같은 하차 묶음에 늦게 합류한 좌석의 "이 좌석에서만" 물건 문장. 없으면 null. */
    fun seatOnlyItems(items: List<CarryItem>, seat: SeatId, picker: PhrasePicker = PhrasePicker.LEGACY): String? =
        items.filter { it.condition == ItemCondition.SEAT && it.seat == seat }.take(ChildProfiles.MAX_SPOKEN_ITEMS).takeIf { it.isNotEmpty() }?.let { itemsSentence(it, seat, picker) }

    /** 존댓말: "지갑과 우산 챙기셨어요?" 받침에 맞는 조사로 잇는다(F10-6). 틀은 풀에서 고른다(F11-1). */
    private fun itemsSentence(items: List<CarryItem>, seat: SeatId?, picker: PhrasePicker) =
        ExitPhrasePool.fill(picker.pick(seat, ExitPhrasePool.Polite.items), KoreanPostposition.joinPolite(items.map { it.name }))

    /** 좌석 하나의 운전자 보고. [driverReportBatch] 의 1인 묶음과 같다. */
    fun driverReport(
        seat: SeatId,
        results: List<CommandResult>,
        snapshot: VehicleSnapshot,
        managed: Boolean,
        thresholds: Thresholds = Thresholds(),
        weather: WeatherInfo = WeatherInfo(),
    ): List<String> = driverReportBatch(mapOf(seat to results), if (managed) emptySet() else setOf(seat), snapshot, thresholds, weather)

    /**
     * 동시 하차 묶음의 운전자 보고. 실제로 한 일만 말한다.
     *  - 같은 일을 한 좌석은 한 문장으로 묶는다. 예: "조수석·뒷좌석 왼쪽 열선을 껐어요."
     *  - "운전석 창문이 열려 있어요."·실내 온도 안내는 묶음당 1번이다. 운전석 창문은 비가 오거나 1시간 안에 비 소식이 있을 때만 말한다.
     */
    fun driverReportBatch(
        perSeat: Map<SeatId, List<CommandResult>>,
        unmanaged: Set<SeatId>,
        snapshot: VehicleSnapshot,
        thresholds: Thresholds = Thresholds(),
        weather: WeatherInfo = WeatherInfo(),
    ): List<String> = driverReportLines(perSeat, unmanaged, snapshot, thresholds, weather).map { it.text }

    /**
     * [driverReportBatch] 와 같은 줄을 같은 순서로 내되 종류([ReportKind])를 붙인다(F12-1).
     * 한 일·관리 꺼짐 = 성공, 그대로 둠·실패·중단 = 경고, 운전석 창문·실내 온도 = 안내.
     */
    fun driverReportLines(
        perSeat: Map<SeatId, List<CommandResult>>,
        unmanaged: Set<SeatId>,
        snapshot: VehicleSnapshot,
        thresholds: Thresholds = Thresholds(),
        weather: WeatherInfo = WeatherInfo(),
    ): List<ReportLine> {
        val doneBySentence = linkedMapOf<String, MutableList<SeatId>>()
        val keptLines = mutableListOf<String>()
        val failedLines = mutableListOf<String>()
        val abortedLines = mutableListOf<String>()
        for ((seat, results) in perSeat) {
            // 창문 닫기를 도중에 멈춘 것(F9-1). 성공도 실패도 아니라 따로 알린다.
            results.filter { it.status == CommandResult.Status.ABORTED }.forEach { r ->
                abortedLines += "${seat.label} 창문을 닫다가 ${WindowAbortReason.phrase(r.why)} 멈췄어요." + WindowAbortReason.tail(r.why, seat.label)
            }
            val (done, kept, failed) = summarize(results)
            if (done.isNotEmpty()) {
                val sentence = done.mapIndexed { i, p -> if (i == done.lastIndex) p.second else p.first }.joinToString(" ")
                doneBySentence.getOrPut(sentence) { mutableListOf() } += seat
            }
            if (kept.isNotEmpty()) {
                val why = kept.map { it.second }.distinct().joinToString(", ").replace(Regex("있음$"), "있어서")
                keptLines += "$why ${seat.label} ${kept.joinToString("·") { it.first }}은 그대로 뒀어요."
            }
            if (failed.isNotEmpty()) failedLines += "${seat.label} ${failed.joinToString("·")} 정리에 실패했어요. 직접 확인해 주세요."
        }
        val out = mutableListOf<ReportLine>()
        doneBySentence.forEach { (sentence, seats) -> out += ReportLine(ReportKind.SUCCESS, seats.joinToString("·") { it.label } + " " + sentence + ".") }
        (keptLines + failedLines + abortedLines).forEach { out += ReportLine(ReportKind.WARNING, it) }
        if (unmanaged.isNotEmpty()) out += ReportLine(ReportKind.SUCCESS, "${unmanaged.joinToString("·") { it.label }}은 관리를 꺼 둬서 그대로 뒀어요.")
        val rain = weather.rainingNow == true || weather.rainWithinHour == true
        if (rain && (snapshot.seat(SeatId.ROW_1_LEFT).windowOpenPercent ?: 0) >= thresholds.windowOpenMinPercent) out += ReportLine(ReportKind.DRIVER_WINDOW, "운전석 창문이 열려 있어요.")
        val cabin = snapshot.hvac.cabinTempC
        if (cabin != null && cabin >= thresholds.hotCabinC) out += ReportLine(ReportKind.CABIN_HEAT, "실내가 ${cabin.toInt()}℃예요. 다음 탑승 전에 환기해 주세요.")
        return out
    }

    /** 명령 결과를 (한 일 문구, 그대로 둔 항목·이유, 실패 항목) 으로 나눈다. */
    private fun summarize(results: List<CommandResult>): Triple<List<Pair<String, String>>, List<Pair<String, String>>, List<String>> {
        val done = mutableListOf<Pair<String, String>>()   // (이어지는 말, 끝나는 말)
        val kept = mutableListOf<Pair<String, String>>()   // (항목, 이유)
        val failed = mutableListOf<String>()
        for (r in results) {
            val (kind, v) = kindAndValue(r.cmd)
            val phrase = when {
                r.cmd is VehicleCommand.SetWindowLock && r.cmd.locked -> "창문을 잠그고" to "창문을 잠갔어요"
                r.cmd is VehicleCommand.SetWindowLock -> "창문 잠금을 풀고" to "창문 잠금을 풀었어요"
                r.cmd is VehicleCommand.SetWindow && v == 0 -> "창문을 닫고" to "창문을 닫았어요"
                r.cmd is VehicleCommand.SetWindow -> "창문을 ${v}%로 맞추고" to "창문을 ${v}%로 맞췄어요"
                v == 0 -> "${kind}을 끄고" to "${kind}을 껐어요"
                else -> "${kind}을 ${v}단으로 돌리고" to "${kind}을 ${v}단으로 돌렸어요"
            }
            when (r.status) {
                CommandResult.Status.OK -> done += phrase
                CommandResult.Status.REJECTED -> kept += kind to r.why
                CommandResult.Status.FAILED -> failed += kind
                CommandResult.Status.ABORTED -> Unit      // [driverReportBatch] 가 따로 문구를 만든다
            }
        }
        return Triple(done, kept, failed)
    }

    private fun kindAndValue(c: VehicleCommand): Pair<String, Int> = when (c) {
        is VehicleCommand.SetWindow -> "창문" to c.openPercent
        is VehicleCommand.SetSeatHeater -> "열선" to c.level
        is VehicleCommand.SetSeatVentilation -> "통풍" to c.level
        is VehicleCommand.SetWindowLock -> "창문 잠금" to if (c.locked) 1 else 0
    }
}