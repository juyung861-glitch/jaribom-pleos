package com.jaribom.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.jaribom.app.core.SpeedSignal
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import com.jaribom.app.routine.JariBomService
import com.jaribom.app.routine.RoutineController
import com.jaribom.app.ui.DebugPanel
import com.jaribom.app.ui.SettingsScreen
import com.jaribom.app.ui.theme.JariBomTheme

/**
 * 설정 화면. 처음 열 때 백그라운드 서비스를 띄우고 알림 권한을 요청한다(이후에는 부팅 시 자동 시작).
 * Back 하드키: 화면이 한 장뿐이라 항상 최상단이다. Pleos 정책상 최상단에서 앱을 종료(finish)하면 심사 반려이므로
 * moveTaskToBack(true) 로 숨긴다. 숨긴 뒤 프로세스가 정리돼도 설정은 ItemStore 에 이미 저장돼 있다.
 */
class MainActivity : ComponentActivity() {

    private val requestNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    /** 차속 권한(CAR_SPEED, 런타임 요청, F10-2). 결과가 허용이면 속도 구독을 다시 시도한다. */
    private val requestSpeed = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        RoutineController.get(this).onSpeedPermissionResult(granted)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        onBackPressedDispatcher.addCallback(this) { moveTaskToBack(true) }
        JariBomService.start(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        val controller = RoutineController.get(this)
        // 속도 권한은 게이트웨이가 "거부"라고 알릴 때, 정차 상태에서, 한 번만 묻는다(F10-2). 거부돼도 다시 묻지 않는다.
        lifecycleScope.launch {
            controller.state.map { it.speedSignal to it.uxRestricted }.distinctUntilChanged().collect { (signal, _) ->
                if (signal == SpeedSignal.PERMISSION_DENIED && controller.shouldAskSpeedPermission()) {
                    controller.onSpeedPermissionAsked()
                    requestSpeed.launch(SPEED_PERMISSION)
                }
            }
        }
        setContent {
            JariBomTheme {
                val state by controller.state.collectAsStateWithLifecycle()
                SettingsScreen(
                    state, controller, debugPanel = { DebugPanel(state, controller) },
                    onRequestSpeedPermission = { requestSpeed.launch(SPEED_PERMISSION) },
                )
            }
        }
    }

    private companion object { const val SPEED_PERMISSION = "android.car.permission.CAR_SPEED" }
}