package com.jaribom.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jaribom.app.BuildConfig
import com.jaribom.app.routine.JariBomActions

/**
 * debug 빌드 전용 "기록 초기화". 월별 기록(이번 달 대신한 일 카드)을 모두 지운다.
 * 한 번 누르면 확인으로 바뀌고 다시 눌러야 지운다. release 빌드에서는 아무것도 그리지 않는다(컨트롤러도 무시).
 */
@Composable
fun StatsReset(actions: JariBomActions) {
    if (!BuildConfig.DEBUG) return
    var confirm by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(
            onClick = { if (confirm) { actions.clearStats(); confirm = false } else confirm = true },
            Modifier.heightIn(min = 48.dp),
        ) { Text(if (confirm) "정말 지우기" else "기록 초기화") }
        Text(if (confirm) "월별 기록을 모두 지웁니다" else "debug 전용 · 월별 기록 삭제", fontSize = 14.sp)
    }
}
