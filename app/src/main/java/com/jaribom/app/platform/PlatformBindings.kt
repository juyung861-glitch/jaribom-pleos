package com.jaribom.app.platform

import android.content.Context
import com.jaribom.app.speech.Announcer
import com.jaribom.app.vehicle.VehicleGateway
import com.jaribom.app.weather.LocationProvider
import com.jaribom.app.weather.WeatherProvider

/**
 * 플레이버별로 실제 구현이 바뀌는 부품 묶음.
 * 구현은 src/mock/.../platform/Bindings.kt 와 src/pleos/.../platform/Bindings.kt 에 있다.
 */
interface PlatformBindings {
    val isMock: Boolean
    fun vehicleGateway(context: Context): VehicleGateway
    fun announcer(context: Context): Announcer
    fun locationProvider(context: Context): LocationProvider
    fun weatherProvider(context: Context): WeatherProvider
}