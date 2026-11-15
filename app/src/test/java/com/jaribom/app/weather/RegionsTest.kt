package com.jaribom.app.weather

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RegionsTest {

    @Test
    fun `기본 지역은 서울 중구이고 기상청 격자 60,127`() {
        assertEquals("서울 중구", Regions.default.label)
        assertEquals(60 to 127, Regions.default.grid)
    }

    @Test
    fun `모든 지역은 시도별로 고를 수 있고 격자가 기상청 범위 안이다`() {
        assertTrue(Regions.all.size in 20..40)
        for (s in Regions.sidos) assertTrue(Regions.sigungus(s).isNotEmpty())
        for (r in Regions.all) {
            val (nx, ny) = r.grid
            assertTrue(r.label, nx in 1..149 && ny in 1..253)
        }
        assertNotEquals(Regions.default.grid, Regions.find("부산", "해운대구")!!.grid)
    }
}
