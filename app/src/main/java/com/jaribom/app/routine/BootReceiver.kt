package com.jaribom.app.routine

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** 시동(부팅) 직후와 앱 업데이트 직후에 서비스를 띄운다. 운전자가 앱을 한 번도 열지 않아도 동작하게 하기 위함. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> JariBomService.start(context)
        }
    }
}