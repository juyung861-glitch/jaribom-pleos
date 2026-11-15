package com.jaribom.app.weather

import com.jaribom.app.core.WeatherInfo

/** 위치(위도·경도) 제공. 지금은 설정한 날씨 지역의 좌표([RegionLocationProvider]). 나중에 위치 SDK 로 바꿔 끼운다. */
interface LocationProvider {
    suspend fun current(): Pair<Double, Double>?
}

interface WeatherProvider {
    suspend fun fetch(lat: Double, lon: Double): WeatherInfo
}

/** 디버그 패널에서 값을 직접 넣는 가짜 날씨 */
class FakeWeatherProvider : WeatherProvider {
    @Volatile var value: WeatherInfo = WeatherInfo(rainingNow = false, rainWithinHour = false, outdoorTempC = 18f)
    override suspend fun fetch(lat: Double, lon: Double): WeatherInfo = value.copy(fetchedAtMs = System.currentTimeMillis())
}