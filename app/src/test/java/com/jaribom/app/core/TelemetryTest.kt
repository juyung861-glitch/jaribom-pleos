package com.jaribom.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 6차 F6-7: 옵트인 익명 사용 통계. 월별 횟수만, 기본 꺼짐, 네트워크 없음(전송부는 인터페이스만). */
class TelemetryTest {

    private class MemCopies : TelemetryCopies {
        val map = linkedMapOf<String, String>()
        override fun put(month: String, json: String) { map[month] = json }
        override fun all(): Map<String, String> = map.toMap()
        override fun clear() = map.clear()
    }

    private class RecordingSink : TelemetrySink {
        val sent = mutableListOf<String>()
        override fun send(json: String) { sent += json }
    }

    private val stats = CabinRoutineEngine.Stats(
        rides = 5, restores = 4, windowsClosed = 3, heatersOff = 2, childLocks = 1, leftoverAlerts = 1, childReminders = 2,
        restoreFailures = 1, taxiLeftoverAlerts = 2, suppressedAlerts = 3,
    )

    private val days3 = setOf(1, 2, 3)

    @Test
    fun `기본값은 꺼짐이고 꺼져 있으면 사본을 만들지 않고 미리 보기는 보내지 않음이다`() {
        assertFalse(TelemetryManager.DEFAULT_ENABLED)
        val copies = MemCopies()
        val m = TelemetryManager(copies, RecordingSink(), enabled = TelemetryManager.DEFAULT_ENABLED)
        m.update("2026-11", stats, days3, "2026-09")
        assertTrue(copies.map.isEmpty())
        assertEquals("보내지 않음", m.preview("2026-11", stats, days3, "2026-09"))
    }

    @Test
    fun `보내는 JSON의 키는 고정 목록뿐이고 개인을 가리킬 값이 없다`() {
        val json = UsageTelemetry.toJson(UsageTelemetry.build("2026-11", stats, days3, "2026-09"))
        val keys = Regex("\"(\\w+)\"\\s*:").findAll(json).map { it.groupValues[1] }.toList()
        val expected = listOf(
            "schemaVersion", "month", "installMonth", "rides", "restores", "restoreFailures", "doneTotal", "childLocks", "leftoverAlerts",
            "childReminders", "taxiLeftoverAlerts", "suppressedAlerts", "activeTripDays", "activeWeeks", "retained",
            "heatOffTaps", "adultTaps", "windowCloseTaps", "windowStops", "speechFailures",
        )
        assertEquals(expected, keys)
        assertEquals(expected, UsageTelemetry.KEYS)
        for (k in keys) for (bad in listOf("device", "account", "location", "lat", "lng", "seat", "item", "name", "time", "date", "user", "phone", "email")) {
            assertFalse("금지 필드: $k", k.lowercase().contains(bad))
        }
        assertTrue(json.contains("\"month\": \"2026-11\""))
        assertTrue(json.contains("\"rides\": 5"))
    }

    @Test
    fun `동승 하차 4회 이상이면 retained 가 true 이다`() {
        assertFalse(UsageTelemetry.build("2026-11", CabinRoutineEngine.Stats(rides = 3), setOf(1), "2026-09").retained)
        assertTrue(UsageTelemetry.build("2026-11", CabinRoutineEngine.Stats(rides = 4), setOf(1), "2026-09").retained)
        assertTrue(UsageTelemetry.build("2026-11", CabinRoutineEngine.Stats(rides = 9), setOf(1), "2026-09").retained)
    }

    @Test
    fun `토글을 끄면 지금까지 만든 집계 사본을 지운다`() {
        val copies = MemCopies()
        val sink = RecordingSink()
        val m = TelemetryManager(copies, sink, enabled = true)
        m.update("2026-10", stats, setOf(1, 2), "2026-09")
        m.update("2026-11", stats, days3, "2026-09")
        assertEquals(setOf("2026-10", "2026-11"), copies.map.keys)
        m.flush()
        assertEquals(2, sink.sent.size)
        m.setEnabled(false)
        assertTrue(copies.map.isEmpty())
        m.flush()
        assertEquals(2, sink.sent.size)                       // 꺼진 뒤에는 더 보내지 않는다
        assertEquals("보내지 않음", m.preview("2026-11", stats, days3, "2026-09"))
    }

    @Test
    fun `예전 월별 기록(7칸·10칸)을 그대로 읽는다`() {
        val seven = StatsCodec.decode("12,9,5,7,5,0,3")
        assertEquals(CabinRoutineEngine.Stats(12, 9, 5, 7, 5, 0, 3), seven)
        val ten = StatsCodec.decode("12,9,5,7,5,0,3,2,1,4")
        assertEquals(2, ten.childLocks); assertEquals(1, ten.leftoverAlerts); assertEquals(4, ten.childReminders)
        assertEquals(0, ten.restoreFailures); assertEquals(0, ten.taxiLeftoverAlerts); assertEquals(0, ten.suppressedAlerts)
        assertEquals(CabinRoutineEngine.Stats(), StatsCodec.decode(null))
        // 새 형식은 되돌려 읽으면 같다
        assertEquals(stats, StatsCodec.decode(StatsCodec.encode(stats)))
    }
}
