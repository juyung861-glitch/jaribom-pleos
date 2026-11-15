package com.jaribom.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jaribom.app.core.Gear
import com.jaribom.app.core.SeatId
import com.jaribom.app.core.WeatherInfo
import com.jaribom.app.platform.MockBindings
import com.jaribom.app.routine.JariBomActions
import com.jaribom.app.routine.RoutineState

/**
 * mock 플레이버 전용 "가짜 Virtual Console".
 * Pleos 에뮬레이터의 Virtual Console 과 같은 순서로 조작할 수 있게 버튼을 배치했다.
 * (pleos 플레이버에서는 이 패널 대신 실제 Virtual Console 을 쓴다.)
 */
@Composable
fun DebugPanel(state: RoutineState, actions: JariBomActions) {
    val fake = MockBindings.fakeVehicle
    val fakeWeather = MockBindings.fakeWeather
    var seat by remember { mutableStateOf(SeatId.ROW_2_LEFT) }

    Column(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(16.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("디버그 패널 (mock = 가짜 Virtual Console)", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)

        Text("좌석 선택", fontSize = 14.sp)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SeatId.entries.forEach { s -> FilterChip(selected = seat == s, onClick = { seat = s }, label = { Text(s.label, fontSize = 14.sp) }) }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(onClick = { fake.setOccupied(seat, true) }) { Text("착좌 ON") }
            OutlinedButton(onClick = { fake.setOccupied(seat, false) }) { Text("착좌 OFF") }
            OutlinedButton(onClick = { fake.setBelt(seat, true) }) { Text("벨트 ON") }
            OutlinedButton(onClick = { fake.setBelt(seat, false) }) { Text("벨트 OFF") }
            OutlinedButton(onClick = { fake.simulateDoorOpened(seat, true) }) { Text("문 열기") }
            OutlinedButton(onClick = { fake.simulateDoorOpened(seat, false) }) { Text("문 닫기") }
            OutlinedButton(onClick = { fake.setWindow(seat, 40) }) { Text("창문 40%") }
            OutlinedButton(onClick = { fake.setHeater(seat, 2) }) { Text("열선 2") }
            OutlinedButton(onClick = { fake.setVentilation(seat, 3) }) { Text("통풍 3") }
            OutlinedButton(onClick = { fake.setWindowLockSwitch(seat, true) }) { Text("창문 잠금") }
            OutlinedButton(onClick = { fake.setWindowLockSwitch(seat, false) }) { Text("잠금 해제") }
        }
        Text("차량", fontSize = 14.sp)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(onClick = { fake.simulateGearSelected(Gear.PARK); fake.setSpeedMps(null) }) { Text("기어 P") }
            OutlinedButton(onClick = { fake.simulateGearSelected(Gear.DRIVE); fake.setSpeedMps(8.3f) }) { Text("기어 D·30km/h") }
            OutlinedButton(onClick = { fake.setCabinTemp(33f) }) { Text("실내 33℃") }
            OutlinedButton(onClick = { fake.setCabinTemp(24f) }) { Text("실내 24℃") }
            OutlinedButton(onClick = { fake.failRemaining = 1 }) { Text("다음 제어 실패") }
        }
        Text("날씨", fontSize = 14.sp)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(onClick = { fakeWeather.value = WeatherInfo(rainingNow = true, rainWithinHour = true, outdoorTempC = 15f); actions.refreshWeatherNow() }) { Text("비") }
            OutlinedButton(onClick = { fakeWeather.value = WeatherInfo(rainingNow = false, rainWithinHour = true, outdoorTempC = 18f); actions.refreshWeatherNow() }) { Text("곧 비") }
            OutlinedButton(onClick = { fakeWeather.value = WeatherInfo(rainingNow = false, rainWithinHour = false, outdoorTempC = -3f); actions.refreshWeatherNow() }) { Text("영하") }
            OutlinedButton(onClick = { fakeWeather.value = WeatherInfo(rainingNow = false, rainWithinHour = false, outdoorTempC = 20f); actions.refreshWeatherNow() }) { Text("맑음") }
        }
        WindowLockStatus(state.rearWindowLocks)
        OutdoorTempOverride(state.outdoorTempOverride, actions)
        StatsReset(actions)
    }
}