package com.jaribom.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Row
import com.jaribom.app.core.SeatId
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.jaribom.app.data.ItemStore
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jaribom.app.BuildConfig
import com.jaribom.app.routine.JariBomActions
import com.jaribom.app.routine.RoutineState

/**
 * pleos 플레이버의 디버그 패널. 차량 신호는 Pleos 에뮬레이터의 Virtual Console 로 바꾸므로 여기에는 없다.
 * debug 빌드에서만 "뒷좌석 창문 잠금" 표시, "바깥 기온 강제값"(A7), "기록 초기화"를 보여 준다. release 에서는 아무것도 그리지 않는다.
 */
@Composable
fun DebugPanel(state: RoutineState, actions: JariBomActions) {
    if (!BuildConfig.DEBUG) return
    Column(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(16.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("디버그 (debug 빌드 전용)", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        WindowLockStatus(state.rearWindowLocks)
        OutdoorTempOverride(state.outdoorTempOverride, actions)
        StatsReset(actions)
        OccupancyOverride()
        SignalOverrides()
    }
}

/**
 * 좌석별 "착좌 미지원"·"벨트 미지원" 강제(F9-3, debug 전용). 앱을 다시 켠 뒤부터 적용된다.
 * 둘 다 켜면 "신호가 없어 관리하지 않아요", 착좌만 켜면 "벨트·문으로 판단해요". 모두 끄면 원래대로.
 */
@Composable
private fun SignalOverrides() {
    val store = ItemStore(LocalContext.current.applicationContext)
    var cur by remember { mutableStateOf(store.loadSignalOverrides()) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("좌석별 신호 강제(다음 실행부터)", fontSize = 15.sp)
        SeatId.passengers.forEach { seat ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text(seat.label, fontSize = 15.sp, modifier = Modifier.weight(1f))
                OutlinedButton(onClick = {
                    val n = cur.copy(occupancyOff = if (seat in cur.occupancyOff) cur.occupancyOff - seat else cur.occupancyOff + seat)
                    store.saveSignalOverrides(n); cur = n
                }) { Text("착좌 미지원 ${if (seat in cur.occupancyOff) "✓" else "✗"}", fontSize = 14.sp) }
                OutlinedButton(onClick = {
                    val n = cur.copy(beltOff = if (seat in cur.beltOff) cur.beltOff - seat else cur.beltOff + seat)
                    store.saveSignalOverrides(n); cur = n
                }) { Text("벨트 미지원 ${if (seat in cur.beltOff) "✓" else "✗"}", fontSize = 14.sp) }
            }
        }
    }
}

/** 착좌 반전 수동 지정(F6-5, debug 전용). 앱을 다시 켠 뒤부터 적용된다. 자동 = 주행 중 운전석 신호로 한 번 판정한 저장값, 없으면 기본(true). */
@Composable
private fun OccupancyOverride() {
    val store = ItemStore(LocalContext.current.applicationContext)
    var cur by remember { mutableStateOf(store.loadOccupancyOverride()) }
    val auto = store.loadOccupancyInverted()
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("착좌 반전(다음 실행부터): 지정 ${cur?.toString() ?: "자동"} · 저장된 판정 ${auto?.toString() ?: "없음"}", fontSize = 15.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf<Pair<String, Boolean?>>("자동" to null, "반전 켜짐" to true, "반전 꺼짐" to false).forEach { (label, v) ->
                OutlinedButton(onClick = { store.saveOccupancyOverride(v); cur = v }) { Text(label, fontSize = 15.sp) }
            }
        }
    }
}
