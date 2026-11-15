package com.jaribom.app.weather

import com.jaribom.app.JbLog
import com.jaribom.app.core.WeatherInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * 기상청 단기예보(초단기실황 + 초단기예보) 공공데이터 API.
 *  - 상업적 이용 가능, 출처 표기 의무(공공누리) → 앱 정보 화면에 "기상청 공공데이터" 표기 (A2 권리 검토 항목)
 *  - 서비스 키는 BuildConfig/로컬 설정으로 주입. 저장소에 올리지 말 것.
 *  - 네트워크 실패 시 WeatherInfo() (모두 null) 을 돌려주고 멘트 규칙은 날씨 조건을 건너뛴다 → 앱은 멈추지 않는다.
 *
 * 초단기실황(getUltraSrtNcst): T1H 기온, RN1 1시간 강수량, PTY 강수형태
 * 초단기예보(getUltraSrtFcst): 다음 1시간의 PTY 로 "곧 비" 판단
 * 미세먼지는 다루지 않는다(A8: 안내 규칙에서 쓰지 않아 WeatherInfo 에서 모두 뻐다).
 */
class KmaWeatherProvider(private val serviceKey: String) : WeatherProvider {

    override suspend fun fetch(lat: Double, lon: Double): WeatherInfo = withContext(Dispatchers.IO) {
        val (nx, ny) = KmaGrid.toGrid(lat, lon)
        // 키는 로그에 남기지 않는다. 좌표(격자)만 남긴다.
        if (serviceKey.isBlank()) {
            JbLog.i(TAG, "기상청 격자 nx=$nx, ny=$ny — 서비스 키 없음, 호출 생략")
            return@withContext WeatherInfo()
        }
        JbLog.i(TAG, "기상청 호출: 격자 nx=$nx, ny=$ny")
        runCatching {
            val (baseDate, baseTime) = baseDateTime()
            val ncst = call("getUltraSrtNcst", nx, ny, baseDate, baseTime)
            val fcst = call("getUltraSrtFcst", nx, ny, baseDate, baseTime)

            var temp: Float? = null
            var pty = 0
            for (item in ncst) {
                when (item.optString("category")) {
                    "T1H" -> temp = item.optString("obsrValue").toFloatOrNull()
                    "PTY" -> pty = item.optString("obsrValue").toIntOrNull() ?: 0
                }
            }
            val soon = fcst.any { it.optString("category") == "PTY" && (it.optString("fcstValue").toIntOrNull() ?: 0) != 0 }
            WeatherInfo(
                rainingNow = pty != 0,
                rainWithinHour = soon,
                outdoorTempC = temp,
                fetchedAtMs = System.currentTimeMillis(),
            )
        }.getOrElse { WeatherInfo() }
    }

    private fun call(op: String, nx: Int, ny: Int, baseDate: String, baseTime: String): List<JSONObject> {
        val url = "https://apis.data.go.kr/1360000/VilageFcstInfoService_2.0/$op" +
            "?serviceKey=" + URLEncoder.encode(serviceKey, "UTF-8") +
            "&pageNo=1&numOfRows=60&dataType=JSON&base_date=$baseDate&base_time=$baseTime&nx=$nx&ny=$ny"
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 4000; readTimeout = 4000
        }
        try {
            val body = conn.inputStream.bufferedReader().readText()
            val items = JSONObject(body).getJSONObject("response").getJSONObject("body")
                .getJSONObject("items").getJSONArray("item")
            return (0 until items.length()).map { items.getJSONObject(it) }
        } finally {
            conn.disconnect()
        }
    }

    /** 초단기실황은 매시 40분 이후 제공 → 그 전이면 한 시간 전 자료를 쓴다. */
    private fun baseDateTime(): Pair<String, String> {
        var t = LocalDateTime.now()
        if (t.minute < 45) t = t.minusHours(1)
        return t.format(DateTimeFormatter.ofPattern("yyyyMMdd")) to t.format(DateTimeFormatter.ofPattern("HH00"))
    }

    private companion object {
        const val TAG = "JariBomWeather"
    }
}