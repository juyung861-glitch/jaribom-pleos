package com.jaribom.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jaribom.app.BuildConfig
import com.jaribom.app.routine.JariBomActions

/**
 * debug 빌드 전용 "바깥 기온 강제값"(4차 심사 A7). 시연·검증에서 탑승 프리셋(≤3℃ 열선, ≥28℃ 통풍)을 띄우기 위한 것.
 * 비워 두고 적용하면 기상청 값으로 돌아간다. release 빌드에서는 아무것도 그리지 않는다(컨트롤러도 강제값을 무시).
 */
@Composable
fun OutdoorTempOverride(current: Float?, actions: JariBomActions) {
    if (!BuildConfig.DEBUG) return
    var text by remember(current) { mutableStateOf(current?.let { if (it % 1f == 0f) it.toInt().toString() else it.toString() } ?: "") }
    val focusManager = LocalFocusManager.current
    val apply = {
        actions.setOutdoorTempOverride(text.trim().toFloatOrNull())
        focusManager.clearFocus()
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("바깥 기온 강제값 (debug 전용 · 비우면 기상청 값)", fontSize = 14.sp)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = text, onValueChange = { text = it }, singleLine = true,
                placeholder = { Text("예: 2") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { apply() }),
            )
            OutlinedButton(onClick = apply, Modifier.heightIn(min = 48.dp)) { Text("적용") }
        }
        Text(if (current == null) "지금: 기상청 값" else "지금: ${current}℃ 강제", fontSize = 14.sp)
    }
}
