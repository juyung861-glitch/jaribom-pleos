package com.jaribom.app.core

/**
 * 좌석 신호 상태(7차 F11-4). 설정 화면의 좌석 항목에만 보여 준다. 주행 중 음성·알림으로는 알리지 않는다.
 *  - [NO_SIGNAL]: 착좌 신호가 미지원이거나(F9-3 capability 판별: [SeatSignalMode] 이 FULL 이 아님) 값이 null
 *  - [WEAK]: 최근 문 열림 중 착좌 없이 문만 열리고 닫힌 비율이 높음(가벼운 아이·부스터 좌석 등)
 */
enum class SeatSignalStatus(val line: String?) {
    NORMAL(null),
    NO_SIGNAL("신호 없음: 이 좌석은 착좌 신호를 받지 못해요"),
    WEAK("신호 약함: 이 좌석은 가벼운 승객이 잘 감지되지 않을 수 있어요"),
}

/**
 * 좌석별 문 열림 기록으로 착좌 신호가 약한지 본다. 순수 Kotlin(시각은 스냅샷 시각을 받는다).
 *
 * 문 열림 한 번(열림→닫힘)을 "착좌가 함께 잡혔나"로 기록한다.
 *  - 내리는 쪽: 앉아 있다가(마지막 문 닫힘 이후 착좌가 한 번이라도 잡힘) 문이 열렸다 닫히면 잡힌 것
 *  - 타는 쪽: 문이 닫힌 뒤 [BOARD_WINDOW_MS] 안에 착좌가 잡히면 잡힌 것
 *  - 둘 다 아니면 "착좌 없이 문만 열렸다 닫힘"(잡히지 않음). 짐만 싣고 내리는 정상 경우도 여기에 섞인다.
 * 기록은 좌석별 최근 [WINDOW]번만 남기고(저장은 [allHistory], 복원은 생성자), 문 열림이 [MIN_SAMPLES]번 이상 쌓인 뒤에만 판정한다.
 */
class SeatSignalQuality(restored: Map<SeatId, List<Boolean>> = emptyMap()) {
    companion object {
        /** 판정에 쓰는 최근 문 열림 수(요구 사항: 최근 10번) */
        const val WINDOW = 10
        /**
         * 이 수보다 적게 쌓이면 판정하지 않는다. 한두 번의 짐 싣기 때문에 바로 "약함"이 뜨지 않게 하는 값이다.
         * 근거: 실측 데이터가 없어 고른 값이다 — 창(10) 의 절반이면 우연한 두세 번은 넘기고, 같은 좌석을 쓰는 가족이라면 며칠 안에 쌓인다.
         */
        const val MIN_SAMPLES = 5
        /**
         * 기록 중 "착좌 없이 문만 열림"이 이 비율 이상이면 약함. 실측 데이터가 없어 고른 값이다.
         * 근거: 사람이 타고 내리면 보통 착좌가 잡히므로(타는 문·내리는 문 모두), 잡히지 않은 문 열림이 10번 중 7번 넘게 쌓이는 좌석은
         * 짐 싣기 같은 예외만으로 설명하기 어렵고 가벼운 승객이 안 잡힌다고 보는 편이 자연스럽다. 시연·실차 데이터로 조정한다.
         */
        const val WEAK_RATIO = 0.7f
        /** 문이 닫힌 뒤 이 시간 안에 착좌가 잡히면 그 문 열림은 "타는 문"으로 본다 */
        const val BOARD_WINDOW_MS = 15_000L
    }

    private class Pending(val closedAtMs: Long, val seatedBefore: Boolean)

    private val histories = mutableMapOf<SeatId, ArrayDeque<Boolean>>()
    private val prevDoor = mutableMapOf<SeatId, Boolean?>()
    private val seatedSinceClose = mutableMapOf<SeatId, Boolean>()
    private val pending = mutableMapOf<SeatId, Pending>()
    private val lastOccupied = mutableMapOf<SeatId, Boolean?>()

    init {
        restored.forEach { (seat, h) -> histories[seat] = ArrayDeque(h.takeLast(WINDOW)) }
    }

    /** 좌석별 기록(오래된 것부터, true = 착좌가 함께 잡힘). 저장용. */
    fun history(seat: SeatId): List<Boolean> = histories[seat]?.toList().orEmpty()
    fun allHistory(): Map<SeatId, List<Boolean>> = histories.filterValues { it.isNotEmpty() }.mapValues { it.value.toList() }

    private fun record(seat: SeatId, seated: Boolean) {
        val h = histories.getOrPut(seat) { ArrayDeque() }
        h.addLast(seated)
        while (h.size > WINDOW) h.removeFirst()
    }

    /** 스냅샷이 올 때마다 좌석 하나를 넣는다. 값이 null 이면 그 신호는 없는 것으로 본다. */
    fun observe(seat: SeatId, doorOpen: Boolean?, occupied: Boolean?, nowMs: Long) {
        lastOccupied[seat] = occupied
        pending[seat]?.let { p ->
            when {
                occupied == true && nowMs - p.closedAtMs <= BOARD_WINDOW_MS -> { record(seat, true); pending.remove(seat) }
                nowMs - p.closedAtMs > BOARD_WINDOW_MS -> { record(seat, p.seatedBefore); pending.remove(seat) }
            }
        }
        if (occupied == true) seatedSinceClose[seat] = true
        val before = prevDoor[seat]
        prevDoor[seat] = doorOpen
        if (before == null || doorOpen == null) return
        if (!before && doorOpen) {
            // 새 문 열림: 아직 결론 나지 않은 이전 문 열림은 지금까지의 사실로 확정한다.
            pending.remove(seat)?.let { record(seat, it.seatedBefore) }
        } else if (before && !doorOpen) {
            val seatedBefore = seatedSinceClose[seat] == true
            seatedSinceClose[seat] = occupied == true          // 닫힌 뒤부터 다시 센다(계속 앉아 있으면 이어진다)
            if (seatedBefore) record(seat, true) else pending[seat] = Pending(nowMs, false)
        }
    }

    /**
     * 좌석의 신호 상태. [mode] 가 FULL 이 아니거나 지금 착좌 값이 null 이면 [SeatSignalStatus.NO_SIGNAL],
     * 기록이 [MIN_SAMPLES] 이상이고 잡히지 않은 비율이 [WEAK_RATIO] 이상이면 [SeatSignalStatus.WEAK], 아니면 NORMAL.
     */
    fun status(seat: SeatId, mode: SeatSignalMode): SeatSignalStatus {
        if (mode != SeatSignalMode.FULL || lastOccupied[seat] == null) return SeatSignalStatus.NO_SIGNAL
        val h = histories[seat] ?: return SeatSignalStatus.NORMAL
        if (h.size < MIN_SAMPLES) return SeatSignalStatus.NORMAL
        val missed = h.count { !it }
        return if (missed.toFloat() / h.size >= WEAK_RATIO) SeatSignalStatus.WEAK else SeatSignalStatus.NORMAL
    }

    /** 이 좌석 기록을 지운다(기록 초기화 등) */
    fun clear() {
        histories.clear(); prevDoor.clear(); seatedSinceClose.clear(); pending.clear(); lastOccupied.clear()
    }
}
