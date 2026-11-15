package com.jaribom.app.routine

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 하차 정리 알림의 [다시 시도] 버튼. 자기 알림의 실패한 원복 명령만 안전 게이트를 다시 거쳐 실행한다.
 * 알림마다 PendingIntent requestCode(= 알림 ID)와 알림 키를 따로 둬서, 예전 알림의 버튼이 다른 알림의 실패를 실행하지 않는다.
 */
class NotificationActionReceiver : BroadcastReceiver() {

    companion object {
        private const val ACTION_RETRY = "com.jaribom.app.action.RETRY_CLEANUP"
        private const val ACTION_CLIMATE_OFF = "com.jaribom.app.action.CLIMATE_OFF"
        private const val ACTION_WINDOW_CLOSE = "com.jaribom.app.action.WINDOW_CLOSE"
        private const val ACTION_ADULT_ABOARD = "com.jaribom.app.action.ADULT_ABOARD"
        private const val EXTRA_KEY = "notice_key"

        /** [어른이 탔어요] 버튼(F8-3). 이번 운행만 그 아이 좌석을 일반 안내로 다룬다. 쓰기는 없다. */
        fun adultAboardIntent(context: Context, key: Int, requestCode: Int): PendingIntent = PendingIntent.getBroadcast(
            context, requestCode,
            Intent(context, NotificationActionReceiver::class.java).setAction(ACTION_ADULT_ABOARD).putExtra(EXTRA_KEY, key),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        /** [창문 닫기] 버튼(F9-3, 착좌 신호가 없는 좌석). 운전자가 눌러야 닫는다. */
        fun windowCloseIntent(context: Context, key: Int, requestCode: Int): PendingIntent = PendingIntent.getBroadcast(
            context, requestCode,
            Intent(context, NotificationActionReceiver::class.java).setAction(ACTION_WINDOW_CLOSE).putExtra(EXTRA_KEY, key),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        /**
         * [열선 끄기] 버튼(F9-2). 알림 키와 requestCode 가 알림마다 달라 버튼은 자기 알림의 좌석만 끈다.
         * 액션 문자열이 [다시 시도] 와 달라 같은 requestCode 여도 서로 다른 PendingIntent 다.
         */
        fun climateOffIntent(context: Context, key: Int, requestCode: Int): PendingIntent = PendingIntent.getBroadcast(
            context, requestCode,
            Intent(context, NotificationActionReceiver::class.java).setAction(ACTION_CLIMATE_OFF).putExtra(EXTRA_KEY, key),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        fun retryIntent(context: Context, key: Int, requestCode: Int): PendingIntent = PendingIntent.getBroadcast(
            context, requestCode,
            Intent(context, NotificationActionReceiver::class.java).setAction(ACTION_RETRY).putExtra(EXTRA_KEY, key),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (!intent.hasExtra(EXTRA_KEY)) return
        val key = intent.getIntExtra(EXTRA_KEY, 0)
        when (intent.action) {
            ACTION_RETRY -> RoutineController.get(context).retryFailed(key)
            ACTION_CLIMATE_OFF -> RoutineController.get(context).turnOffClimate(key)
            ACTION_WINDOW_CLOSE -> RoutineController.get(context).closeWindows(key)
            ACTION_ADULT_ABOARD -> RoutineController.get(context).adultAboard(key)
        }
    }
}
