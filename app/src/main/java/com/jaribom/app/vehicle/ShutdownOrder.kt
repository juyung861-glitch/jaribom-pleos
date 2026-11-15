package com.jaribom.app.vehicle

import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 서비스 stop 순서(6차 F9 리뷰 반영). 창문을 닫는 중에 stop 되면 [WindowCloser] 가 취소 불가 구간에서 정지를 보내는데,
 * 그 사이 disconnect(teardown)가 먼저 돌면 새 Vehicle 로 바뀌어 정지가 실패한다. 그래서 닫는 중이던 작업([pending])이
 * 끝나기를(= 정지 쓰기가 나가기를) 최대 [graceMs] 기다린 뒤 disconnect 한다. 그 안에 끝나지 않아도 disconnect 는 진행하고 로그를 남긴다.
 */
object ShutdownOrder {
    const val GRACE_MS = 1_000L

    suspend fun run(pending: List<Job>, graceMs: Long = GRACE_MS, log: (String) -> Unit, disconnect: suspend () -> Unit) {
        if (pending.isNotEmpty()) {
            val done = withTimeoutOrNull(graceMs) { pending.joinAll(); true }
            if (done == null) log("닫는 중이던 창문 정지가 ${graceMs}ms 안에 끝나지 않음 — disconnect 진행")
        }
        disconnect()
    }
}
