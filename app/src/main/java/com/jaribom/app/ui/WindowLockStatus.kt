package com.jaribom.app.ui

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.sp
import com.jaribom.app.BuildConfig
import com.jaribom.app.core.SeatId

/**
 * debug 빌드 전용 "뒷좌석 창문 잠금" 표시. 시연 영상에서 아이 좌석 잠금(탑승 잠김 → 하차 풀림)을 보여 주기 위한 것.
 * 값은 차량 구독값(pleos: Window.registerWindowLockState, mock: 가짜 차량)이다. 읽을 수 없으면 "알 수 없음".
 * Pleos 에뮬레이터의 Virtual Console Window Lock 표시는 앱이 읽고 쓰는 값과 이어져 있지 않아(양방향 미반영) 시연에 쓰지 않는다.
 * release 빌드에서는 아무것도 그리지 않는다.
 */
@Composable
fun WindowLockStatus(locks: Map<SeatId, Boolean?>) {
    if (!BuildConfig.DEBUG) return
    fun label(v: Boolean?) = when (v) { true -> "잠김"; false -> "풀림"; null -> "알 수 없음" }
    Text(
        "뒷좌석 창문 잠금: 왼쪽 ${label(locks[SeatId.ROW_2_LEFT])} · 오른쪽 ${label(locks[SeatId.ROW_2_RIGHT])}",
        fontSize = 16.sp,
    )
}
