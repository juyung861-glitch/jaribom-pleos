package com.jaribom.app.platform

import android.content.Context
import com.jaribom.app.speech.AndroidTtsAnnouncer
import com.jaribom.app.speech.Announcer
import com.jaribom.app.vehicle.FakeVehicleGateway
import com.jaribom.app.vehicle.VehicleGateway
import com.jaribom.app.weather.FakeWeatherProvider
import com.jaribom.app.data.ItemStore
import com.jaribom.app.weather.LocationProvider
import com.jaribom.app.weather.RegionLocationProvider
import com.jaribom.app.weather.WeatherProvider

/** mock 플레이버: SDK 없이 가짜 차량·가짜 날씨·Android TTS */
object MockBindings : PlatformBindings {
    override val isMock = true
    val fakeVehicle = FakeVehicleGateway()
    val fakeWeather = FakeWeatherProvider()
    override fun vehicleGateway(context: Context): VehicleGateway = fakeVehicle
    override fun announcer(context: Context): Announcer = AndroidTtsAnnouncer(context)
    override fun locationProvider(context: Context): LocationProvider {
        val store = ItemStore(context.applicationContext)
        return RegionLocationProvider { store.loadRegion() }
    }
    override fun weatherProvider(context: Context): WeatherProvider = fakeWeather
}

fun platformBindings(): PlatformBindings = MockBindings