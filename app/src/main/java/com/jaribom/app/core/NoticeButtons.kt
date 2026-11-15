package com.jaribom.app.core

/** 알림에 붙는 액션 버튼의 종류 */
enum class NoticeAction { RETRY, CLIMATE_OFF, WINDOW_CLOSE, ADULT_ABOARD }

/**
 * 주행 중 알림 버튼 숨김(7차 F10-4, 운전자 주의 분산). 화면 편집 제한 중(CarUx 가 제한을 요구하거나 자체 판정이 정차 아님,
 * [UxPolicy.screenLocked])에는 알림이 액션 버튼 없이 문장만 간다. 운전자 보고 음성 규칙은 그대로다.
 *
 * F12-fix: 기어가 P 가 아니면(모름 포함) 속도와 상관없이 버튼을 숨긴다. 속도 0 이 들어오는 차에서 P→D 순간에 나가는
 * 알림(택시 출발 알림 등)은 자체 판정이 아직 정차이고 CarUx 제한 신호도 늦게 와서 버튼이 붙은 채 나갔다.
 */
object NoticeButtons {
    fun visible(restricted: Boolean, wanted: List<NoticeAction>): List<NoticeAction> = if (restricted) emptyList() else wanted

    /** 버튼을 숨길 때인가. 화면 편집 제한이거나 기어가 P 가 아니면 숨긴다. */
    fun hidden(carUx: Boolean?, snap: VehicleSnapshot): Boolean = UxPolicy.screenLocked(carUx, snap) || snap.gear != Gear.PARK
}

/**
 * 버튼 알림의 상태를 따라간다.
 *  - 보이는 중(shown): 버튼을 달고 나간 알림. 제한이 시작되면 [hideShown] 이 돌려주어 버튼만 떼고 다시 낸다(F12-fix).
 *  - 대기(deferred): 제한 중에 버튼 없이 낸(또는 뗀) 알림. 제한이 풀리면 [release] 가 아직 유효한 것만 돌려주어 버튼을 붙인다.
 * 알림을 만든 때의 좌석 에피소드([AlertLimiter.episodeOf])를 같이 기억해, 풀릴 때 에피소드가 달라진 알림(그 좌석 문이 다시 열려
 * 다음 손님이 탔거나 운행이 끝남)은 버튼 없이 두고 더 따라가지 않는다. [T] 는 다시 낼 알림의 내용(전체 본문 포함).
 */
class DeferredNotices<T> {
    private class Entry<T>(val payload: T, val episodes: Map<SeatId, Int>)

    private val entries = linkedMapOf<Int, Entry<T>>()
    private val shown = linkedMapOf<Int, Entry<T>>()

    fun defer(key: Int, payload: T, episodes: Map<SeatId, Int>) { shown.remove(key); entries[key] = Entry(payload, episodes) }

    /** 버튼을 달고 내보냈다. 제한이 시작되면 [hideShown] 으로 버튼을 뗀다. */
    fun markShown(key: Int, payload: T, episodes: Map<SeatId, Int>) { entries.remove(key); shown[key] = Entry(payload, episodes) }

    fun forget(key: Int) { entries.remove(key); shown.remove(key) }

    /** 제한이 시작됐다. 버튼을 달고 나간 알림의 (키, 내용)을 돌려주고 대기 목록으로 옮긴다(버튼만 떼서 다시 낼 대상). */
    fun hideShown(): List<Pair<Int, T>> {
        val out = shown.map { (k, e) -> k to e.payload }
        entries.putAll(shown)
        shown.clear()
        return out
    }

    /**
     * 제한이 풀렸다. 아직 유효한 알림의 (키, 내용)을 돌려주고 대기 목록을 비운다. 돌려준 것은 다시 보이는 중으로 따라간다.
     * 유효하지 않은 것은 버튼 없이 그대로 두고 잊는다.
     */
    fun release(currentEpisode: (SeatId) -> Int): List<Pair<Int, T>> {
        val valid = entries.filter { (_, e) -> !ButtonGuard.isStale(e.episodes, currentEpisode) }
        entries.clear()
        shown.putAll(valid)
        return valid.map { (k, e) -> k to e.payload }
    }
}
