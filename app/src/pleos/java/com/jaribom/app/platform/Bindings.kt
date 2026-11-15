package com.jaribom.app.platform

import android.content.Context
import com.jaribom.app.BuildConfig
import com.jaribom.app.speech.Announcer
import com.jaribom.app.vehicle.VehicleGateway
import com.jaribom.app.data.ItemStore
import com.jaribom.app.weather.KmaWeatherProvider
import com.jaribom.app.weather.LocationProvider
import com.jaribom.app.weather.RegionLocationProvider
import com.jaribom.app.weather.WeatherProvider

/**
 * pleos 플레이버: Pleos Vehicle SDK + Pleos TTS(온디바이스) + 기상청 API.
 *  - 위치: 설정의 "주로 다니는 지역"(기본 서울 중구)의 좌표. FusedLocation SDK 는 Playground 에서 아직 "준비 중"이라 연결하지 않았다.
 *  - 기상청 서비스 키: local.properties 의 KMA_SERVICE_KEY → BuildConfig. 없으면 날씨 조건은 건너뛴다.
 */
object PleosBindings : PlatformBindings {
    override val isMock = false
    private var gateway: PleosVehicleGateway? = null
    override fun vehicleGateway(context: Context): VehicleGateway =
        gateway ?: PleosVehicleGateway(context.applicationContext).also { gateway = it }
    override fun announcer(context: Context): Announcer = PleosTtsAnnouncer(context)
    override fun locationProvider(context: Context): LocationProvider {
        val store = ItemStore(context.applicationContext)
        return RegionLocationProvider { store.loadRegion() }
    }
    override fun weatherProvider(context: Context): WeatherProvider = KmaWeatherProvider(BuildConfig.KMA_SERVICE_KEY)
}

fun platformBindings(): PlatformBindings = PleosBindings