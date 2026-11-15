package com.jaribom.app.core

/**
 * 재연결 뒤 창문 상태 확인(7차 F8-2). 차량 서비스가 죽을 때 "보냈지만 결과를 못 받은 창문 쓰기"는 차량이 받았는지 알 수 없다.
 * 다시 붙은 뒤 그 좌석 창문을 읽어 판정한다. 새 닫기 쓰기는 하지 않는다(움직이는 중 위험이 있을 때 정지만).
 */
data class PendingWindowWrite(
    val seat: SeatId,
    /** 보낸 목표 위치(열림 %) */
    val target: Int,
    /** 쓰기 직전 위치. 모르면 null */
    val before: Int?,
    /** 쓰기가 실패로 끝난 시각(ms) */
    val atMs: Long,
)

sealed class RecoveryVerdict {
    abstract val seat: SeatId
    /** 목표에 닿았고 위험이 없다 → 정리 성공, 알림 없음 */
    data class Done(override val seat: SeatId) : RecoveryVerdict()
    /** 아직 움직이는 중인데 문 열림·착좌·벨트가 있다 → 정지(F9-1 처리) */
    data class Stop(override val seat: SeatId, val target: Int, val reason: WindowAbortReason, val stopFailed: Boolean = false) : RecoveryVerdict()
    /** 끊긴 동안 움직였을 수 있다 → 운전자 알림 */
    data class Notify(override val seat: SeatId) : RecoveryVerdict()
    /** 위치가 그대로고 위험도 없다 → 할 일 없음(원래의 실패 보고가 남아 있다) */
    data class NoChange(override val seat: SeatId) : RecoveryVerdict()
}

object WindowRecoveryRules {
    /**
     * [now] 는 재연결 뒤 읽은 그 좌석 상태, [moving] 은 잠시 뒤 다시 읽었을 때 위치가 계속 변하는지.
     * 위험 = 문 열림·착좌·벨트 체결(F9-1과 같은 기준, 시작 상태는 비어 있다고 본다).
     *  1) 위험 + 움직이는 중 → 정지
     *  2) 위험(멈춰 있음) → 알림
     *  3) 목표 도달 → 성공
     *  4) 직전 위치와 다른데 미도달 → 알림
     *  5) 그 밖(위치 그대로, 위치 모름) → 아무것도 안 함
     */
    fun decide(p: PendingWindowWrite, now: SeatState, moving: Boolean): RecoveryVerdict {
        val hazard = WindowAbortReason.detect(SeatState(), now)
        val pos = now.windowOpenPercent
        return when {
            hazard != null && moving -> RecoveryVerdict.Stop(p.seat, p.target, hazard)
            hazard != null -> RecoveryVerdict.Notify(p.seat)
            pos != null && pos == p.target -> RecoveryVerdict.Done(p.seat)
            pos != null && pos != p.before -> RecoveryVerdict.Notify(p.seat)
            else -> RecoveryVerdict.NoChange(p.seat)
        }
    }
}

/**
 * 결과를 못 받은 창문 쓰기 목록. 쓰기가 실패로 끝날 때마다 [record] 하고, 같은 좌석의 쓰기가 성공하면 [confirmed] 로 지운다.
 * 서비스 사망을 감지하면 [onDied], 재연결되면 [takeAfterReconnect] 로 사망 시각 기준 [lookbackMs] 이내의 기록만 한 번 돌려준다.
 * 사망한 적이 없으면(첫 연결 등) 아무것도 돌려주지 않는다.
 */
class UnconfirmedWindowWrites(private val lookbackMs: Long = 60_000L) {
    private val list = mutableListOf<PendingWindowWrite>()
    private var diedAtMs: Long? = null

    fun record(w: PendingWindowWrite) {
        list.removeAll { it.seat == w.seat }
        list += w
    }

    fun confirmed(seat: SeatId) { list.removeAll { it.seat == seat } }

    fun onDied(nowMs: Long) { diedAtMs = nowMs }

    fun takeAfterReconnect(): List<PendingWindowWrite> {
        val died = diedAtMs ?: return emptyList()
        diedAtMs = null
        val hit = list.filter { it.atMs >= died - lookbackMs }
        list.clear()
        return hit
    }
}
