package com.jaribom.app.routine

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/**
 * 자리봄의 본체. 앱 화면을 열지 않아도 탑승·하차를 감지하고 원복·음성 안내를 한다.
 * Pleos 문서: "화면에서 사라진 앱이 종료되는 것은 아닙니다" — 다만 OS 가 프로세스를 정리하지 않도록
 * 포그라운드 서비스(specialUse)로 띄우고, 부팅 직후에도 [BootReceiver] 가 시작한다.
 */
class JariBomService : Service() {

    companion object {
        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, JariBomService::class.java))
        }
    }

    override fun onCreate() {
        super.onCreate()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, Notifier.ID_RUNNING, Notifier(this).running(), type)
        RoutineController.get(this).start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        RoutineController.get(this).stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}