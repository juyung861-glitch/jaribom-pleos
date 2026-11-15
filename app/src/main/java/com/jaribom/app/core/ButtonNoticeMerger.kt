package com.jaribom.app.core

/**
 * 버튼 결과가 하차 보고에 합쳐진 알림을 덮어쓰지 않게 한다(6차 F9 리뷰 반영).
 * 같은 알림 키로 갱신해야 하므로, 처음 알림([remember])을 기억해 두었다가 버튼 결과를 그 본문 끝에 붙여 다시 낸다.
 *  - 원래 본문·제목·실패 맵([다시 시도] 대상)은 그대로다.
 *  - 누른 버튼의 결과 문구만 붙는다. 같은 버튼의 이전 결과는 대체하고, 다른 버튼의 결과는 유지한다.
 *  - 원래 알림이 단독(plain) 알림이면 합치지 않는다([merge] 가 null).
 */
class ButtonNoticeMerger {
    /** STALE: 오래된 알림이라 실행하지 않음(두 버튼 모두 없앤다) */
    enum class Kind { CLIMATE, WINDOW, STALE }

    private class Entry(val base: RoutineEvent.CleanupNotice) {
        val lines = linkedMapOf<Kind, String>()
        var climateOff: List<VehicleCommand> = base.climateOff
        var windowClose: List<VehicleCommand.SetWindow> = base.windowClose
    }

    private val entries = mutableMapOf<Int, Entry>()

    /** 엔진이 낸 알림을 기억한다(버튼이 붙었거나 실패가 있는 하차 보고). 같은 키의 이전 기록은 새 알림으로 바뀐다. */
    fun remember(n: RoutineEvent.CleanupNotice) {
        if (n.plain) entries.remove(n.key) else entries[n.key] = Entry(n)
    }

    fun forget(key: Int) { entries.remove(key) }

    /** 버튼 결과 [result](엔진이 만든 단독 알림)를 원래 알림에 합친 알림. 합칠 원래 알림이 없으면 null. */
    fun merge(kind: Kind, result: RoutineEvent.CleanupNotice): RoutineEvent.CleanupNotice? {
        val e = entries[result.key] ?: return null
        e.lines[kind] = result.text
        when (kind) {
            Kind.CLIMATE -> e.climateOff = result.climateOff
            Kind.WINDOW -> e.windowClose = result.windowClose
            Kind.STALE -> { e.climateOff = emptyList(); e.windowClose = emptyList() }
        }
        return e.base.copy(
            text = e.base.text + "\n" + e.lines.values.joinToString("\n"),
            climateOff = e.climateOff, windowClose = e.windowClose,
        )
    }
}
