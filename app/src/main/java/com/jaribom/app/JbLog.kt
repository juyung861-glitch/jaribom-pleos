package com.jaribom.app

import android.util.Log
import com.jaribom.app.core.LogRedactor

/**
 * 앱 로그. release 빌드에서는 아무것도 남기지 않는다(Pleos 보안 가이드: 배포 앱의 디버그 로그 비활성화).
 * R8 규칙(-assumenosideeffects)은 이 프로젝트의 optimization.packageScope 가 androidx·kotlin 으로 한정돼
 * 앱 코드에 적용되지 않으므로, BuildConfig.DEBUG 로 직접 막는다.
 * 모든 메시지는 [LogRedactor] 를 거쳐 설정한 아이 이름이 logcat 에 남지 않게 한다(F11-3).
 */
object JbLog {
    fun i(tag: String, msg: String) { if (BuildConfig.DEBUG) Log.i(tag, LogRedactor.redact(msg)) }
    fun w(tag: String, msg: String, t: Throwable? = null) { if (BuildConfig.DEBUG) Log.w(tag, LogRedactor.redact(msg), t) }
    fun e(tag: String, msg: String, t: Throwable? = null) { if (BuildConfig.DEBUG) Log.e(tag, LogRedactor.redact(msg), t) }
}
