package com.jaribom.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jaribom.app.core.CabinRoutineEngine
import com.jaribom.app.core.CarryItem
import com.jaribom.app.core.ChildProfile
import com.jaribom.app.core.ChildProfiles
import com.jaribom.app.core.ClimateSupport
import com.jaribom.app.core.SeatSignals
import com.jaribom.app.core.DriverReportMode
import com.jaribom.app.core.ExitMessageRules
import com.jaribom.app.core.ItemCondition
import com.jaribom.app.core.QuickItems
import com.jaribom.app.core.SeatId
import com.jaribom.app.core.SeatSignalMode
import com.jaribom.app.core.SeatSignalStatus
import com.jaribom.app.core.SpeedSignal
import com.jaribom.app.core.SpeedSignalText
import com.jaribom.app.core.UxPolicy
import com.jaribom.app.routine.JariBomActions
import com.jaribom.app.routine.RoutineState
import com.jaribom.app.ui.theme.JariBomTheme
import com.jaribom.app.weather.Region
import com.jaribom.app.weather.Regions
import java.time.DayOfWeek

/**
 * 자리봄 설정 화면 한 장. 차량 상태(좌석·창문·열선)는 Pleos 기본 화면이 보여 주므로 여기서는 다루지 않는다.
 * 앱 본체는 백그라운드 서비스이고, 이 화면은 가끔 여는 설정 용도다.
 *  - Pleos 기본 앱처럼 밝은 바탕·세로 1단·큰 글자와 터치 영역
 *  - 주행 중에는 설정을 잠그고 "정차 후 이용" 안내만 보여 준다(Pleos 주행 규제 가이드)
 */
private val TouchMin = 56.dp

@Composable
fun SettingsScreen(
    state: RoutineState,
    actions: JariBomActions,
    debugPanel: @Composable () -> Unit = {},
    /** 속도 권한 [권한 허용] 버튼(F10-2). 거부 상태일 때만 보인다. */
    onRequestSpeedPermission: () -> Unit = {},
) {
    // Back 은 앱을 끝내지 않고(moveTaskToBack), Pleos 는 이 화면을 오른쪽 패널에 계속 띄워 둔다(멈춤·종료 없음).
    // 입력칸 포커스가 남아 있으면 이 창을 다시 누를 때마다 키보드가 뜨므로, 창이 입력 포커스를 잃으면
    // (주행 화면·다른 앱을 누름, Back) 입력칸 포커스를 놓는다. 키보드는 창 포커스를 가져가지 않아 입력 중에는 영향이 없다.
    val focusManager = LocalFocusManager.current
    val windowInfo = LocalWindowInfo.current
    LaunchedEffect(windowInfo) {
        snapshotFlow { windowInfo.isWindowFocused }.collect { focused -> if (!focused) focusManager.clearFocus() }
    }
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.widthIn(max = 760.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 32.dp, vertical = 28.dp),
            verticalArrangement = Arrangement.spacedBy(28.dp),
        ) {
            Header(state)
            DoneThisMonth(state.stats, state.lastMonthStats)
            if (state.uxRestricted) {
                DrivingLock()
                // 익명 통계 끄기(옵트아웃)는 주행 중에도 된다(F12-fix2). 켜기는 정차 후. 나머지 설정은 잠근다.
                Section("익명 사용 통계 보내기", "주행 중에는 끄기만 할 수 있어요.") { TelemetrySection(state, actions) }
            } else {
                SpeedSignalNotice(state.speedSignal, onRequestSpeedPermission)
                KidsLockMain(state, actions)
                state.lastResult?.takeIf { it.finished }?.let { LastCleanup(it) }
                Section("챙길 물건", "등록하지 않아도 비가 오면 우산, 3℃ 이하면 따뜻하게 입으라고 자동으로 안내해요. 자주 챙기는 물건만 눌러 두세요(한 번에 3개까지 말해요).") { ItemsEditor(state.items, actions) }
                Section("관리할 좌석", "끈 좌석은 창문·열선·통풍을 건드리지 않아요. 운전석은 항상 제어하지 않아요.") {
                    ManagedSeats(state.managedSeats, state.seatModes, state.seatSignals, state.climateSupport, actions)
                    RearVent(state, actions)
                }
                Section("키즈락 좌석", "지정한 뒷자리에 탑승이 확인되면 그 좌석 창문을 잠갔다가 내리면 원래대로 돌려요. 내릴 때는 아이 말투로 안내해요. 뒷자리 여러 곳을 고를 수 있어요.") { ChildSeats(state.childSeats, state.seatModes, state.seatSignals, state.childProfiles, actions) }
                Section("택시 모드", "켜면 모든 동승 좌석에서 손님이 두고 내린 물건이 있을 수 있을 때 출발하면서 알림으로 알려 드려요. 음성은 없어요.") {
                    SwitchRow("택시 모드", "문을 열었다 닫은 뒤에도 좌석이 눌려 있고 벨트가 풀려 있으면 알려요.", state.taxiMode) { actions.setTaxiMode(it) }
                }
                Section("운전자 보고", "문이 열려 창문을 못 닫음·정리 실패·남은 물건·문 열림 같은 경고는 이 설정과 상관없이 항상 알려요. P가 아니면 알림만 내요.") { ReportSettings(state, actions) }
                Section("음성 안내", "동승자에게 하는 말이에요. 운전자 보고는 위 설정을 따라요.") { VoiceSwitches(state, actions) }
                Section("안내할 내용이 없을 때", null) { QuietPolicy(state.quietPolicy, actions) }
                Section("주로 다니는 지역", "비·추위 안내에 쓰는 날씨 지역이에요. 위치 정보는 쓰지 않아요.") { RegionPicker(state.region, actions) }
                Section("익명 사용 통계 보내기", "월별 횟수만 모읍니다. 위치·시각·좌석별 기록은 담지 않아요.") { TelemetrySection(state, actions) }
                debugPanel()
            }
        }
    }
}

/**
 * 메인 "키즈락 모드"(F12-4). 끄면 키즈락 좌석으로 지정한 자리도 일반 좌석으로 동작한다.
 * 설정 화면은 주행 중 통째로 잠기므로 바꾸는 것은 정차 중에만 된다.
 */
@Composable
private fun KidsLockMain(state: RoutineState, actions: JariBomActions) {
    val seats = state.childSeats.sortedBy { it.ordinal }.joinToString("·") { it.label }
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(16.dp)).padding(horizontal = 20.dp, vertical = 8.dp)) {
        SwitchRow(
            "키즈락 모드",
            if (seats.isEmpty()) "아래 '키즈락 좌석'에서 뒷자리를 골라 주세요."
            else if (state.kidsLockOn) "$seats: 창문 잠금 + 아이 말투" else "꺼 두면 $seats 도 일반 좌석으로 동작해요.",
            state.kidsLockOn,
        ) { actions.setKidsLockOn(it) }
    }
}

/** 속도 신호 상태(F10-2): 권한이 없거나 이 차에 속도 신호가 없으면 한 줄로 알린다. 정상이면 아무것도 보이지 않는다. */
@Composable
private fun SpeedSignalNotice(signal: SpeedSignal, onRequest: () -> Unit) {
    val line = SpeedSignalText.line(signal) ?: return
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(line, fontSize = 17.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        if (SpeedSignalText.canRequest(signal)) {
            OutlinedButton(onClick = onRequest, modifier = Modifier.heightIn(min = TouchMin)) { Text("권한 허용", fontSize = 17.sp) }
        }
    }
}

@Composable
private fun Header(state: RoutineState) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("자리봄", fontSize = 34.sp, fontWeight = FontWeight.Bold)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(12.dp).background(if (state.running) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.outline, CircleShape))
            Spacer(Modifier.size(10.dp))
            Text(
                if (state.running) "동승자 좌석을 살피고 있어요" else "멈춰 있어요",
                fontSize = 20.sp, fontWeight = FontWeight.Medium,
            )
        }
        Text(
            "이 화면을 닫아도 동승자가 타고 내리는 것을 감지해 음성과 알림으로 알려 드려요.",
            fontSize = 17.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val st = state.stats
        Text("이번 달 동승 하차 ${st.rides}회 · 정리한 하차 ${st.restores}회 · 챙김 안내 ${st.reminders}회", fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** "이번 달 대신한 일" 카드(A5). 예: 창문 닫음 7 · 열선 끔 5 · 비 오는 날 안내 3 · 지난달보다 +2 */
@Composable
private fun DoneThisMonth(st: CabinRoutineEngine.Stats, last: CabinRoutineEngine.Stats) {
    val diff = st.doneTotal - last.doneTotal
    Column(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(16.dp)).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("이번 달 대신한 일", fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(st.doneSummary() + " · 지난달보다 " + (if (diff >= 0) "+$diff" else "$diff"), fontSize = 19.sp)
    }
}

@Composable
private fun DrivingLock() {
    Column(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(16.dp)).padding(28.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(UxPolicy.LOCK_MESSAGE, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Text(UxPolicy.DRIVING_NOTE, fontSize = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun LastCleanup(r: CabinRoutineEngine.AlightResult) {
    Column(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(16.dp)).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("최근 하차 정리 · ${r.seats.joinToString("·") { it.label }}", fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(r.report.ifBlank { "바뀐 것이 없어 그대로 뒀어요." }, fontSize = 19.sp)
    }
}

@Composable
private fun Section(title: String, desc: String?, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        Spacer(Modifier.height(4.dp))
        Text(title, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        if (desc != null) Text(desc, fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        content()
    }
}

/**
 * 챙길 물건(F12-3). "자주 쓰는 물건" 칩을 한 번 누르면 기본 조건으로 등록·해제한다.
 * 조건·좌석 지정·직접 입력과 칩에 없는 물건 목록은 "더 보기" 안에 있다. 예전에 등록한 물건은 그대로 읽는다.
 */
@Composable
private fun ItemsEditor(items: List<CarryItem>, actions: JariBomActions) {
    var more by remember { mutableStateOf(false) }
    val others = QuickItems.others(items)
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("자주 쓰는 물건", fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            QuickItems.presets.forEach { p ->
                val label = if (p.condition == ItemCondition.RAIN) "${p.name}(비 올 때만)" else p.name
                Choice(label, QuickItems.isOn(items, p)) { actions.toggleQuickItem(p) }
            }
        }
        TextButton(onClick = { more = !more }, Modifier.heightIn(min = TouchMin)) {
            Text(if (more) "접기" else "더 보기" + if (others.isNotEmpty()) " (직접 등록 ${others.size}개)" else "", fontSize = 17.sp)
        }
        if (more) MoreItemsEditor(others, actions)
    }
}

/** "더 보기": 칩에 없는 물건 목록과 직접 입력·조건·좌석 지정 */
@Composable
private fun MoreItemsEditor(items: List<CarryItem>, actions: JariBomActions) {
    var name by remember { mutableStateOf("") }
    var cond by remember { mutableStateOf(ItemCondition.ALWAYS) }
    var seat by remember { mutableStateOf(SeatId.ROW_1_RIGHT) }
    val focusManager = LocalFocusManager.current
    /** 추가하면 키보드를 닫는다(차량 화면을 키보드가 오래 가리지 않게). */
    val add = {
        if (name.isNotBlank()) { actions.addItem(name, cond, if (cond == ItemCondition.SEAT) seat else null); name = "" }
        focusManager.clearFocus()
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        items.forEach { item ->
            Row(Modifier.fillMaxWidth().heightIn(min = TouchMin), verticalAlignment = Alignment.CenterVertically) {
                Text(item.name, Modifier.weight(1f), fontSize = 20.sp)
                Text(item.seat?.let { "${it.label}에서만" } ?: item.condition.label, fontSize = 17.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = { actions.removeItem(item.id) }, Modifier.heightIn(min = TouchMin)) { Text("삭제", fontSize = 17.sp) }
            }
        }
        // 키보드 입력은 정차 중 설정 화면에서만. 조건은 프리셋 선택(Pleos 주행 가이드: 키보드보다 프리셋 우선).
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = name, onValueChange = { name = it }, singleLine = true,
                placeholder = { Text("물건 이름 (예: 우산)", fontSize = 18.sp) },
                textStyle = MaterialTheme.typography.bodyLarge.copy(fontSize = 20.sp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { add() }),
                modifier = Modifier.weight(1f),
            )
            Button(onClick = add, enabled = name.isNotBlank(), modifier = Modifier.heightIn(min = TouchMin)) {
                Text("추가", fontSize = 18.sp)
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ItemCondition.entries.forEach { c -> Choice(c.label, cond == c) { cond = c } }
        }
        if (cond == ItemCondition.SEAT) {
            // 이 좌석에서 내릴 때만 안내한다(예: 뒷좌석 카시트 가방). 운전석은 고를 수 없다.
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SeatId.passengers.forEach { s -> Choice(s.label, seat == s) { seat = s } }
            }
        }
    }
}

@Composable
private fun ManagedSeats(
    managed: Set<SeatId>, modes: Map<SeatId, SeatSignalMode>, signals: Map<SeatId, SeatSignalStatus>,
    climate: Map<SeatId, ClimateSupport>, actions: JariBomActions,
) {
    Column {
        SeatId.passengers.forEach { seat ->
            Row(Modifier.fillMaxWidth().heightIn(min = TouchMin + 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(seat.label, fontSize = 20.sp)
                    // 착좌·문으로 판단하는 좌석(전체)은 보통이라 한 줄을 달지 않는다(F9-3).
                    modes[seat]?.takeIf { it != SeatSignalMode.FULL }?.let { Text(it.label, fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    SignalLine(signals[seat])
                    // 열선·통풍이 없는 좌석(F12-5). 탑승 프리셋은 조용히 건너뛴다.
                    climate[seat]?.line?.let { Text(it, fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                Switch(
                    checked = seat in managed, onCheckedChange = { actions.toggleManaged(seat) },
                    colors = SwitchDefaults.colors(checkedTrackColor = MaterialTheme.colorScheme.tertiary, checkedThumbColor = Color.White),
                )
            }
        }
    }
}

/**
 * "뒷좌석 통풍도 켜기"(F12-5, 기본 꺼짐). 더운 날 탑승 통풍 자동 켜기는 조수석만 하고,
 * capability 가 뒷좌석 통풍 지원을 보고한 차에서만 이 스위치를 보여 준다.
 */
@Composable
private fun RearVent(state: RoutineState, actions: JariBomActions) {
    val rear = SeatId.passengers.filter { it != SeatId.ROW_1_RIGHT && state.climateSupport[it]?.vent == SeatSignals.Support.SUPPORTED }
    if (rear.isEmpty()) return
    SwitchRow(
        "뒷좌석 통풍도 켜기", "더운 날(28℃ 이상) 타면 ${rear.joinToString("·") { it.label }} 통풍도 켜요. 끄면 조수석만 켜요.",
        state.rearVentOnBoard,
    ) { actions.setRearVentOnBoard(it) }
}

/** 좌석 신호 상태 한 줄(F11-4). 정상이면 아무것도 보이지 않는다. 설정 화면에서만 보이고 음성·알림으로는 알리지 않는다. */
@Composable
private fun SignalLine(status: SeatSignalStatus?) {
    val line = status?.line ?: return
    Text(line, fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** 아이 좌석(뒷좌석 왼쪽·오른쪽, 0~2개). 운전석·조수석은 목록에 없다. 켠 좌석 아래에 그 아이의 기본·요일별 물건을 고친다(F11-2). */
@Composable
private fun ChildSeats(child: Set<SeatId>, modes: Map<SeatId, SeatSignalMode>, signals: Map<SeatId, SeatSignalStatus>, profiles: Map<SeatId, ChildProfile>, actions: JariBomActions) {
    Column {
        CabinRoutineEngine.CHILD_SEAT_CANDIDATES.sortedBy { it.ordinal }.forEach { seat ->
            val none = modes[seat] == SeatSignalMode.NONE
            Row(Modifier.fillMaxWidth().heightIn(min = TouchMin + 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(seat.label, fontSize = 20.sp)
                    if (none) Text(SeatSignalMode.NONE.label, fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    SignalLine(signals[seat])
                }
                Switch(
                    checked = seat in child, onCheckedChange = { actions.toggleChildSeat(seat) },
                    enabled = !none || seat in child,
                    colors = SwitchDefaults.colors(checkedTrackColor = MaterialTheme.colorScheme.tertiary, checkedThumbColor = Color.White),
                )
            }
            if (seat in child) ChildProfileEditor(seat, profiles[seat] ?: ChildProfile(), actions)
        }
    }
}

private val DayLabels = mapOf(
    DayOfWeek.MONDAY to "월", DayOfWeek.TUESDAY to "화", DayOfWeek.WEDNESDAY to "수", DayOfWeek.THURSDAY to "목",
    DayOfWeek.FRIDAY to "금", DayOfWeek.SATURDAY to "토", DayOfWeek.SUNDAY to "일",
)

/**
 * 아이 좌석 하나의 물건 편집(F11-2). "기본" 탭은 매일, 요일 탭은 그 요일에만 말한다.
 * 말할 때는 오늘 요일 물건 먼저, 그다음 기본 물건을 합쳐 중복을 빼고 최대 ${ChildProfiles.MAX_SPOKEN_ITEMS}개까지다.
 * 설정 화면은 주행 중에는 통째로 잠기므로(편집 제한) 입력은 정차 중에만 된다.
 */
@Composable
private fun ChildProfileEditor(seat: SeatId, profile: ChildProfile, actions: JariBomActions) {
    var tab by remember(seat) { mutableStateOf<DayOfWeek?>(null) }
    var text by remember(seat, tab) { mutableStateOf("") }
    val focusManager = LocalFocusManager.current
    val shown = if (tab == null) profile.baseItems else profile.dayItems[tab].orEmpty()
    val add = {
        if (text.isNotBlank()) { actions.addChildItem(seat, tab, text); text = "" }
        focusManager.clearFocus()
    }
    var nameText by remember(seat, profile.name) { mutableStateOf(profile.name) }
    val saveName = { actions.setChildName(seat, nameText); focusManager.clearFocus() }
    Column(Modifier.fillMaxWidth().padding(start = 12.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // 이름은 선택이다. 비우면 이름 없이 말한다. 이 기기 안에만 저장하고 통계·기록에 남기지 않는다(F11-3).
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = nameText, onValueChange = { nameText = it.take(ChildProfiles.MAX_NAME_CHARS) }, singleLine = true,
                placeholder = { Text("아이 이름 (선택, 예: 지민)", fontSize = 18.sp) },
                textStyle = MaterialTheme.typography.bodyLarge.copy(fontSize = 20.sp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { saveName() }),
                modifier = Modifier.weight(1f),
            )
            Button(onClick = saveName, enabled = nameText.trim() != profile.name, modifier = Modifier.heightIn(min = TouchMin)) { Text("저장", fontSize = 18.sp) }
        }
        Text("이름을 부르며 안내해요(예: \"지민아, …\"). 비워 두면 이름 없이 말하고, 이 기기에만 저장돼요.", fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("${seat.label} 챙길 물건 (오늘 요일 물건 → 기본 물건 순, 최대 ${ChildProfiles.MAX_SPOKEN_ITEMS}개)", fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Choice("기본", tab == null) { tab = null }
            DayOfWeek.entries.forEach { d -> Choice(DayLabels.getValue(d), tab == d) { tab = d } }
        }
        shown.forEach { n ->
            Row(Modifier.fillMaxWidth().heightIn(min = TouchMin), verticalAlignment = Alignment.CenterVertically) {
                Text(n, Modifier.weight(1f), fontSize = 20.sp)
                TextButton(onClick = { actions.removeChildItem(seat, tab, n) }, Modifier.heightIn(min = TouchMin)) { Text("삭제", fontSize = 17.sp) }
            }
        }
        if (shown.isEmpty()) Text("아직 없어요", fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = text, onValueChange = { text = it.take(ChildProfiles.MAX_ITEM_CHARS) }, singleLine = true,
                placeholder = { Text(if (tab == null) "기본 물건 (예: 물통)" else "${DayLabels.getValue(tab!!)}요일 물건 (예: 수영 가방)", fontSize = 18.sp) },
                textStyle = MaterialTheme.typography.bodyLarge.copy(fontSize = 20.sp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { add() }),
                modifier = Modifier.weight(1f),
            )
            Button(onClick = add, enabled = text.isNotBlank(), modifier = Modifier.heightIn(min = TouchMin)) { Text("추가", fontSize = 18.sp) }
        }
    }
}

/** 운전자 보고(F12-1): 모두 / 경고만 / 끔 과 운전석 창문·실내 온도 안내 켜기/끄기 */
@Composable
private fun ReportSettings(state: RoutineState, actions: JariBomActions) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            DriverReportMode.entries.forEach { m -> Choice(m.label, state.reportMode == m) { actions.setReportMode(m) } }
        }
        Text(state.reportMode.desc, fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SwitchRow("운전석 창문 안내", "비가 오거나 1시간 안에 비 소식이 있을 때 운전석 창문이 열려 있으면 알려요.", state.driverWindowNotice) { actions.setDriverWindowNotice(it) }
        SwitchRow("실내 온도 안내", "실내가 30℃ 이상이면 환기해 달라고 알려요.", state.cabinHeatNotice) { actions.setCabinHeatNotice(it) }
    }
}

@Composable
private fun VoiceSwitches(state: RoutineState, actions: JariBomActions) {
    Column {
        SwitchRow("탑승 멘트", "동승자가 앉으면 \"탑승을 확인했어요\"라고 말해요.", state.boardingVoice) { actions.setBoardingVoice(it) }
        SwitchRow("하차 멘트", "문이 열리면 동승자에게 물건·날씨를 안내해요.", state.exitVoice) { actions.setExitVoice(it) }
        // F12-6 대체안: 순정 안전 하차 보조 상태를 읽지 못해 후측방 경고가 아니라 한 문장 안내다.
        SwitchRow(
            "도로변 하차 때 뒤 차 확인", "D·N 에서 문이 열리면 \"뒤에 오는 차를 확인하고 내리세요\"를 먼저 말해요. 뒤 차를 감지하는 기능은 아니에요.\n키즈락 좌석은 항상 안내해요.",
            state.roadsideRearCheck,
        ) { actions.setRoadsideRearCheck(it) }
    }
}

/** 옵트인 익명 사용 통계(F6-7). 기본 꺼짐. 미리 보기에는 보낼 JSON 그대로 보인다(꺼져 있으면 "보내지 않음"). */
@Composable
private fun TelemetrySection(state: RoutineState, actions: JariBomActions) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SwitchRow(
            "익명 사용 통계 보내기", "끄면 지금까지 만든 집계 사본을 지워요.", state.telemetryOn,
            enabled = UxPolicy.telemetrySwitchEnabled(state.telemetryOn, state.uxRestricted),
        ) { actions.setTelemetry(it) }
        Text("보낼 데이터 미리 보기", fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            state.telemetryPreview, fontSize = 15.sp, fontFamily = FontFamily.Monospace,
            modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp)).padding(16.dp),
        )
        // retained 는 이름은 유지하지만 뜻은 "사용 강도"다(F10-3). 유지율(Retention)은 installMonth 코호트로 따로 계산한다.
        if (state.telemetryOn) {
            Text("retained: 이번 달 4회 이상 사용(사용 강도) · installMonth: 설치한 달(월 단위만)", fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SwitchRow(title: String, desc: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = TouchMin + 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 20.sp)
            Text(desc, fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(
            checked = checked, onCheckedChange = onChange, enabled = enabled,
            colors = SwitchDefaults.colors(checkedTrackColor = MaterialTheme.colorScheme.tertiary, checkedThumbColor = Color.White),
        )
    }
}

@Composable
private fun QuietPolicy(p: ExitMessageRules.QuietPolicy, actions: JariBomActions) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Choice("\"두고 가시는 물건은 없나요?\"", p == ExitMessageRules.QuietPolicy.SHORT_PHRASE) { actions.setQuietPolicy(ExitMessageRules.QuietPolicy.SHORT_PHRASE) }
        Choice("말하지 않기", p == ExitMessageRules.QuietPolicy.SILENT) { actions.setQuietPolicy(ExitMessageRules.QuietPolicy.SILENT) }
        Spacer(Modifier.weight(1f))
        OutlinedButton(onClick = { actions.speakTest() }, Modifier.heightIn(min = TouchMin)) { Text("음성 듣기", fontSize = 17.sp) }
    }
}

/** 시·도 → 시·군·구 2단계 선택(A6). 시·도를 누르면 그 시·도의 목록을 펼치고, 시·군·구를 눌러야 저장한다. */
@Composable
private fun RegionPicker(region: Region, actions: JariBomActions) {
    var sido by remember(region) { mutableStateOf(region.sido) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("지금: ${region.label}", fontSize = 19.sp)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Regions.sidos.forEach { s -> Choice(s, sido == s) { sido = s } }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Regions.sigungus(sido).forEach { r -> Choice(r.sigungu, r == region) { actions.setRegion(r) } }
        }
    }
}

@Composable
private fun Choice(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected, onClick = onClick,
        label = { Text(label, fontSize = 17.sp) },
        modifier = Modifier.heightIn(min = 48.dp),
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.primary,
            selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
        ),
        border = if (selected) null else FilterChipDefaults.filterChipBorder(enabled = true, selected = false),
    )
}

// ── 미리보기: Pleos 앱 영역(세로 1창) ─────────────────────────────
private object PreviewActions : JariBomActions {
    override fun addItem(name: String, condition: ItemCondition, seat: SeatId?) {}
    override fun removeItem(id: Long) {}
    override fun toggleQuickItem(preset: QuickItems.Preset) {}
    override fun setQuietPolicy(p: ExitMessageRules.QuietPolicy) {}
    override fun toggleManaged(seat: SeatId) {}
    override fun setBoardingVoice(on: Boolean) {}
    override fun setExitVoice(on: Boolean) {}
    override fun speakTest() {}
    override fun refreshWeatherNow() {}
    override fun setRegion(region: Region) {}
    override fun setOutdoorTempOverride(tempC: Float?) {}
    override fun clearStats() {}
    override fun setTelemetry(on: Boolean) {}
    override fun setTaxiMode(on: Boolean) {}
    override fun toggleChildSeat(seat: SeatId) {}
    override fun addChildItem(seat: SeatId, day: DayOfWeek?, name: String) {}
    override fun removeChildItem(seat: SeatId, day: DayOfWeek?, name: String) {}
    override fun setChildName(seat: SeatId, name: String) {}
    override fun setReportMode(mode: DriverReportMode) {}
    override fun setKidsLockOn(on: Boolean) {}
    override fun setRearVentOnBoard(on: Boolean) {}
    override fun setRoadsideRearCheck(on: Boolean) {}
    override fun setDriverWindowNotice(on: Boolean) {}
    override fun setCabinHeatNotice(on: Boolean) {}
}

private fun previewState(driving: Boolean) = RoutineState(
    running = true,
    vehicleStatus = "Vehicle SDK 연결됨",
    uxRestricted = driving,
    items = listOf(CarryItem(1, "우산", ItemCondition.RAIN), CarryItem(2, "지갑", ItemCondition.ALWAYS)),
    managedSeats = setOf(SeatId.ROW_1_RIGHT, SeatId.ROW_2_LEFT),
    lastResult = CabinRoutineEngine.AlightResult(SeatId.ROW_2_LEFT, 0, emptyList(), report = "뒷좌석 왼쪽 창문을 닫고 열선을 껐어요.", finished = true),
    stats = CabinRoutineEngine.Stats(rides = 12, restores = 9, reminders = 5, windowsClosed = 7, heatersOff = 5, rainReminders = 3),
    lastMonthStats = CabinRoutineEngine.Stats(windowsClosed = 6, heatersOff = 4, rainReminders = 3),
)

@Preview(name = "Pleos · 정차", widthDp = 1050, heightDp = 760, showBackground = true)
@Composable
private fun ParkedPreview() {
    JariBomTheme { SettingsScreen(previewState(driving = false), PreviewActions) }
}

@Preview(name = "Pleos · 주행 중", widthDp = 1050, heightDp = 760, showBackground = true)
@Composable
private fun DrivingPreview() {
    JariBomTheme { SettingsScreen(previewState(driving = true), PreviewActions) }
}