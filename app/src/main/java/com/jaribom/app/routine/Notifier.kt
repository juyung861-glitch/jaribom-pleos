package com.jaribom.app.routine

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.jaribom.app.MainActivity
import com.jaribom.app.R
import com.jaribom.app.core.SeatId

/**
 * Pleos 시스템 알림(OS 기본 알림)으로 결과를 남긴다. 앱 화면을 열지 않아도 운전자가 확인할 수 있게 하기 위함.
 *  - 상주 채널: 백그라운드 서비스용 조용한 알림(필수)
 *  - 정리 결과 채널: 하차 정리 결과 한 장. 원복 실패 시에만 [다시 시도] 버튼을 붙인다.
 * Pleos 디자인 가이드: 운전자에게 필요한 경우에만 알림을 띄우고, 광고 문구는 넣지 않는다.
 */
class Notifier(private val context: Context) {

    companion object {
        const val CHANNEL_RUNNING = "jaribom_running"
        /** 중요도를 HIGH→DEFAULT 로 낮추며 채널 ID 를 바꿨다(이미 만든 채널의 중요도는 앱이 바꿀 수 없다). */
        const val CHANNEL_CLEANUP = "jaribom_cleanup_v2"
        private const val OLD_CHANNEL_CLEANUP = "jaribom_cleanup"
        const val ID_RUNNING = 1
        private const val ID_CLEANUP_BASE = 100
    }

    private val nm = NotificationManagerCompat.from(context)

    init {
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_RUNNING, "자리봄 동작 중", NotificationManager.IMPORTANCE_MIN).apply {
                description = "동승자 탑승·하차를 감지하는 동안 표시돼요."
                setShowBadge(false)
            }
        )
        nm.createNotificationChannel(
            // DEFAULT: 알림 센터에 남기되 헤드업으로 띄우지 않는다. 실시간 안내는 음성이 맡는다.
            NotificationChannel(CHANNEL_CLEANUP, "하차 정리 결과", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "동승자가 내린 뒤 창문·열선·통풍을 어떻게 정리했는지 알려 드려요."
            }
        )
    }

    init { nm.deleteNotificationChannel(OLD_CHANNEL_CLEANUP) }

    fun running(): Notification =
        NotificationCompat.Builder(context, CHANNEL_RUNNING)
            .setSmallIcon(R.drawable.ic_stat_jaribom)
            .setContentTitle("자리봄")
            .setContentText("동승자 좌석을 살피고 있어요")
            .setContentIntent(openSettings())
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()

    /**
     * 하차 정리 알림. 같은 [key] 는 같은 알림을 덮어쓴다("정차하면 정리할게요" → 실제 결과).
     * [final] = false 면 진행 안내(대기), true 면 실제로 한 일의 결과다.
     */
    fun showCleanup(
        key: Int, seats: List<SeatId>, report: String, final: Boolean, canRetry: Boolean, title: String? = null,
        /** [열선 끄기] 버튼 문구(F9-2). null 이면 버튼 없음 */
        climateOffLabel: String? = null,
        /** [창문 닫기] 버튼 문구(F9-3). null 이면 버튼 없음 */
        windowCloseLabel: String? = null,
        /** [어른이 탔어요] 버튼 문구(F8-3). null 이면 버튼 없음 */
        adultLabel: String? = null,
        /** 버튼만 떼거나 붙이는 갱신(F10-4·F12-fix). 소리·진동·헤드업 없이 조용히 바꾼다. */
        quiet: Boolean = false,
    ) {
        if (report.isBlank() || !canNotify()) return
        val who = seats.joinToString("·") { it.label }
        val b = NotificationCompat.Builder(context, CHANNEL_CLEANUP)
            .setSmallIcon(R.drawable.ic_stat_jaribom)
            .setContentTitle(title ?: if (final) "$who 하차 정리" else "$who 하차 정리 대기")
            .setContentText(report)
            .setStyle(NotificationCompat.BigTextStyle().bigText(report))
            .setContentIntent(openSettings())
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
        if (quiet) b.setOnlyAlertOnce(true).setSilent(true)
        if (canRetry) {
            // 알림마다 requestCode 와 키가 달라 버튼은 자기 알림의 실패만 실행한다(5차 심사 F1).
            b.addAction(0, "다시 시도", NotificationActionReceiver.retryIntent(context, key, ID_CLEANUP_BASE + key))
        }
        if (climateOffLabel != null) {
            b.addAction(0, climateOffLabel, NotificationActionReceiver.climateOffIntent(context, key, ID_CLEANUP_BASE + key))
        }
        if (windowCloseLabel != null) {
            b.addAction(0, windowCloseLabel, NotificationActionReceiver.windowCloseIntent(context, key, ID_CLEANUP_BASE + key))
        }
        if (adultLabel != null) {
            b.addAction(0, adultLabel, NotificationActionReceiver.adultAboardIntent(context, key, ID_CLEANUP_BASE + key))
        }
        @Suppress("MissingPermission")
        nm.notify(ID_CLEANUP_BASE + key, b.build())
    }

    fun cancelCleanup(key: Int) = nm.cancel(ID_CLEANUP_BASE + key)

    private fun canNotify(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun openSettings(): PendingIntent = PendingIntent.getActivity(
        context, 0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}