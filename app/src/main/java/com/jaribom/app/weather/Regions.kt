package com.jaribom.app.weather

import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.tan

/**
 * "주로 다니는 지역"(4차 심사 A6). 시·도 → 시·군·구 2단계로 고른다. 기본은 서울 중구.
 * 좌표는 각 시·군·구청 부근의 위경도이고, 기상청 격자(nx, ny)는 [KmaGrid] 공개 변환식으로 구한다
 * (5km 격자라 청사 좌표로 충분하다). 위치 권한·GPS 없이 날씨 지역만 정한다.
 */
data class Region(val sido: String, val sigungu: String, val lat: Double, val lon: Double) {
    val label: String get() = "$sido $sigungu"
    /** 기상청 단기예보 격자 좌표 */
    val grid: Pair<Int, Int> get() = KmaGrid.toGrid(lat, lon)
}

object Regions {
    val all: List<Region> = listOf(
        Region("서울", "중구", 37.5638, 126.9976),
        Region("서울", "강남구", 37.5172, 127.0473),
        Region("서울", "송파구", 37.5145, 127.1059),
        Region("서울", "마포구", 37.5663, 126.9019),
        Region("서울", "노원구", 37.6542, 127.0568),
        Region("부산", "중구", 35.1063, 129.0323),
        Region("부산", "부산진구", 35.1628, 129.0532),
        Region("부산", "해운대구", 35.1631, 129.1635),
        Region("대구", "중구", 35.8693, 128.6062),
        Region("대구", "수성구", 35.8582, 128.6306),
        Region("인천", "남동구", 37.4475, 126.7313),
        Region("인천", "연수구", 37.4101, 126.6783),
        Region("광주", "서구", 35.1520, 126.8895),
        Region("광주", "북구", 35.1740, 126.9120),
        Region("대전", "서구", 36.3554, 127.3838),
        Region("대전", "유성구", 36.3623, 127.3562),
        Region("울산", "남구", 35.5439, 129.3301),
        Region("세종", "세종시", 36.4800, 127.2890),
        Region("경기", "수원시", 37.2636, 127.0286),
        Region("경기", "성남시", 37.4200, 127.1267),
        Region("경기", "고양시", 37.6584, 126.8320),
        Region("경기", "용인시", 37.2411, 127.1776),
        Region("강원", "춘천시", 37.8813, 127.7298),
        Region("강원", "강릉시", 37.7519, 128.8761),
        Region("충북", "청주시", 36.6424, 127.4890),
        Region("충남", "천안시", 36.8151, 127.1139),
        Region("전북", "전주시", 35.8242, 127.1480),
        Region("전남", "목포시", 34.8118, 126.3922),
        Region("전남", "여수시", 34.7604, 127.6622),
        Region("경북", "포항시", 36.0190, 129.3435),
        Region("경남", "창원시", 35.2280, 128.6811),
        Region("제주", "제주시", 33.4996, 126.5312),
        Region("제주", "서귀포시", 33.2541, 126.5600),
    )

    val default: Region = all.first()

    val sidos: List<String> = all.map { it.sido }.distinct()

    fun sigungus(sido: String): List<Region> = all.filter { it.sido == sido }

    fun find(sido: String?, sigungu: String?): Region? = all.find { it.sido == sido && it.sigungu == sigungu }
}

/** 기상청 위경도 → 격자(Lambert Conformal Conic). 기상청 공개 변환식. */
object KmaGrid {
    fun toGrid(lat: Double, lon: Double): Pair<Int, Int> {
        val re = 6371.00877 / 5.0
        val slat1 = Math.toRadians(30.0); val slat2 = Math.toRadians(60.0)
        val olon = Math.toRadians(126.0); val olat = Math.toRadians(38.0)
        val xo = 43.0; val yo = 136.0
        var sn = tan(Math.PI * 0.25 + slat2 * 0.5) / tan(Math.PI * 0.25 + slat1 * 0.5)
        sn = ln(cos(slat1) / cos(slat2)) / ln(sn)
        var sf = tan(Math.PI * 0.25 + slat1 * 0.5)
        sf = sf.pow(sn) * cos(slat1) / sn
        var ro = tan(Math.PI * 0.25 + olat * 0.5)
        ro = re * sf / ro.pow(sn)
        var ra = tan(Math.PI * 0.25 + Math.toRadians(lat) * 0.5)
        ra = re * sf / ra.pow(sn)
        var theta = Math.toRadians(lon) - olon
        if (theta > Math.PI) theta -= 2.0 * Math.PI
        if (theta < -Math.PI) theta += 2.0 * Math.PI
        theta *= sn
        val x = floor(ra * sin(theta) + xo + 0.5).toInt()
        val y = floor(ro - ra * cos(theta) + yo + 0.5).toInt()
        return x to y
    }
}

/** 설정한 날씨 지역의 좌표를 위치로 준다. LocationProvider 인터페이스는 그대로(나중에 실제 위치 SDK 로 바꿔 끼울 수 있게). */
class RegionLocationProvider(private val region: () -> Region) : LocationProvider {
    override suspend fun current(): Pair<Double, Double> = region().let { it.lat to it.lon }
}
