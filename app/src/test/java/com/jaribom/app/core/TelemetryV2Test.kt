package com.jaribom.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** F10-3: 통계 스키마 v2 — 새 키, 성공률 정의, 주 경계, 버튼 3종 집계, v1 기록 호환. 금지 필드 원칙은 그대로다. */
class TelemetryV2Test {

    private val v2Keys = listOf(
        "schemaVersion", "month", "installMonth", "rides", "restores", "restoreFailures", "doneTotal", "childLocks", "leftoverAlerts",
        "childReminders", "taxiLeftoverAlerts", "suppressedAlerts", "activeTripDays", "activeWeeks", "retained",
        "heatOffTaps", "adultTaps", "windowCloseTaps", "windowStops", "speechFailures",
    )

    private fun build(s: CabinRoutineEngine.Stats = CabinRoutineEngine.Stats(), days: Set<Int> = emptySet()) =
        UsageTelemetry.build("2026-11", s, days, installMonth = "2026-09")

    @Test
    fun `새 키 목록이 고정이고 schemaVersion 은 2 이다`() {
        assertEquals(v2Keys, UsageTelemetry.KEYS)
        assertEquals(2, UsageTelemetry.SCHEMA_VERSION)
        // JSON 의 키 순서도 목록과 같다
        val jsonKeys = Regex("\"([A-Za-z]+)\":").findAll(UsageTelemetry.toJson(build())).map { it.groupValues[1] }.toList()
        assertEquals(v2Keys, jsonKeys)
        assertTrue(UsageTelemetry.toJson(build()).contains("\"installMonth\": \"2026-09\""))
    }

    @Test
    fun `금지 필드가 없다 - 기기 ID 계정 위치 시각 물건 이름 좌석별 내역`() {
        val json = UsageTelemetry.toJson(build(CabinRoutineEngine.Stats(rides = 5, restores = 4), setOf(1, 9)))
        val forbidden = listOf("device", "account", "email", "location", "lat", "lng", "time", "hour", "item", "name", "seat", "ROW_")
        forbidden.forEach { assertFalse("금지 필드 $it", json.contains(it, ignoreCase = true)) }
        // installMonth 는 월 단위(YYYY-MM)뿐이다
        assertTrue(Regex("\"installMonth\": \"\\d{4}-\\d{2}\"").containsMatchIn(json))
    }

    @Test
    fun `정리 성공률의 분모는 restores 더하기 restoreFailures 이다`() {
        assertEquals(0.75, UsageTelemetry.successRate(CabinRoutineEngine.Stats(restores = 3, restoreFailures = 1))!!, 1e-9)
        assertEquals(1.0, UsageTelemetry.successRate(CabinRoutineEngine.Stats(restores = 2))!!, 1e-9)
        assertNull("분모가 0 이면 비율이 없다", UsageTelemetry.successRate(CabinRoutineEngine.Stats()))
    }

    @Test
    fun `activeWeeks 는 하차가 있던 날을 7일 단위 주로 센다 - 경계`() {
        fun weeks(vararg d: Int) = UsageTelemetry.activeWeeks(d.toSet())
        assertEquals(0, weeks())
        assertEquals(1, weeks(1, 7))            // 1~7일 = 첫째 주
        assertEquals(2, weeks(1, 8))            // 8일부터 둘째 주
        assertEquals(1, weeks(8, 14))
        assertEquals(5, weeks(1, 8, 15, 22, 29))
        assertEquals(1, weeks(29, 31))          // 29~31일은 같은 다섯째 주
        assertEquals(5, build(days = setOf(1, 8, 15, 22, 29)).activeWeeks)
    }

    @Test
    fun `버튼 3종을 실행하면 각 횟수가 늘고 JSON 에 나온다`() {
        val seat = SeatId.ROW_2_RIGHT
        val e = CabinRoutineEngine().apply { childSeats = setOf(seat) }
        val key = 1
        // 어른이 탔어요
        val snap = VehicleSnapshot(seats = SeatId.entries.associateWith { SeatState() } + (seat to SeatState(occupied = true, beltBuckled = true, doorOpen = false)), gear = Gear.PARK, timestampMs = 0)
        val events = e.onSnapshot(snap) + e.onSnapshot(snap.copy(timestampMs = 5_000))      // 시작 직후 이미 앉아 있으면 첫 스냅샷에서 확정
        val notice = events.filterIsInstance<RoutineEvent.CleanupNotice>().firstOrNull { it.adultSeat != null }
        assertTrue("아이 좌석 탑승 알림이 있어야 함", notice != null)
        if (notice != null) e.onAdultAboard(key, seat, notice.buttonEpisodes.getValue(seat))
        // 열선 끄기, 창문 닫기
        e.onClimateOffResult(key, listOf(CommandResult(VehicleCommand.SetSeatHeater(seat, 0), CommandResult.Status.OK)))
        e.onWindowCloseResult(key, listOf(CommandResult(VehicleCommand.SetWindow(seat, 0), CommandResult.Status.OK)))
        e.onWindowCloseResult(key, listOf(CommandResult(VehicleCommand.SetWindow(seat, 0), CommandResult.Status.ABORTED, "DOOR")))
        val s = e.stats
        assertEquals(1, s.adultTaps)
        assertEquals(1, s.leftoverHeatOffs)
        assertEquals(2, s.windowCloseTaps)       // 성공이든 중단이든 눌러서 실행한 횟수
        assertEquals(1, s.windowAborts)
        val json = UsageTelemetry.toJson(UsageTelemetry.build("2026-11", s, emptySet(), "2026-09"))
        assertTrue(json.contains("\"adultTaps\": 1"))
        assertTrue(json.contains("\"heatOffTaps\": 1"))
        assertTrue(json.contains("\"windowCloseTaps\": 2"))
        assertTrue(json.contains("\"windowStops\": 1"))
    }

    @Test
    fun `예전 기록은 그대로 읽힌다 - 새 칸은 0`() {
        val v1 = StatsCodec.decode("12,9,5,7,5,0,3,2,1,4,6,2,1,3,4,5")        // F9 의 16칸
        assertEquals(12, v1.rides); assertEquals(5, v1.speechFailures)
        assertEquals(0, v1.adultTaps); assertEquals(0, v1.windowCloseTaps)
        assertEquals(0, StatsCodec.decode("12,9,5,7,5,0,3").adultTaps)         // 7칸
        assertEquals(0, StatsCodec.decode("12,9,5,7,5,0,3,2,1,4").windowCloseTaps)   // 10칸
        val s = CabinRoutineEngine.Stats(rides = 3, adultTaps = 2, windowCloseTaps = 4)
        assertEquals(s, StatsCodec.decode(StatsCodec.encode(s)))                 // 18칸 왕복
    }
}
