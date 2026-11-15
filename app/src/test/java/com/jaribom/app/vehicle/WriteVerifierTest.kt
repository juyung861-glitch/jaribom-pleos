package com.jaribom.app.vehicle

import com.jaribom.app.core.SeatId
import com.jaribom.app.core.VehicleCommand
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 5차 심사 F2: 쓰기 검증. 응답 시간 초과도 다시 읽어 실제 값으로 판정하고(늦은 성공은 성공),
 * 열선·통풍·잠금은 구독값이 목표에 닿을 때까지 최대 1.5초 기다린다. 재시도는 검증 실패일 때만.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WriteVerifierTest {

    private val seat = SeatId.ROW_2_LEFT
    private val heaterOff = VehicleCommand.SetSeatHeater(seat, 2)

    private fun fake(mode: FakeVehicleGateway.WriteMode, afterMs: Long) =
        FakeVehicleGateway().apply { writeMode = mode; modeDelayMs = afterMs }

    @Test
    fun `지연 응답 - 1초 뒤 답이 오면 성공`() = runTest {
        val r = fake(FakeVehicleGateway.WriteMode.DELAY, 1_000).execute(heaterOff)
        assertTrue(r.exceptionOrNull()?.message, r.isSuccess)
        assertTrue("걸린 시간 $currentTime", currentTime in 1_000..1_100)
    }

    @Test
    fun `무응답 - 시간 초과 뒤 다시 읽어도 값이 그대로면 검증 실패(재시도 대상)`() = runTest {
        val r = fake(FakeVehicleGateway.WriteMode.NO_RESPONSE, 0).execute(heaterOff)
        assertTrue(r.isFailure)
        val e = r.exceptionOrNull()!!
        assertTrue(e.toString(), e is WriteVerifier.VerifyFailed)
        assertTrue(WriteVerifier.shouldRetry(e))
        assertEquals(WriteVerifier.WRITE_TIMEOUT_MS + WriteVerifier.SETTLE_MS, currentTime)
    }

    @Test
    fun `늦은 성공 - 응답은 시간 초과지만 값이 목표에 닿으면 성공`() = runTest {
        val r = fake(FakeVehicleGateway.WriteMode.LATE_SUCCESS, WriteVerifier.WRITE_TIMEOUT_MS + 500).execute(heaterOff)
        assertTrue(r.exceptionOrNull()?.message, r.isSuccess)
    }

    @Test
    fun `차량이 명시적으로 거절한 쓰기는 재시도하지 않는다`() = runTest {
        val f = FakeVehicleGateway().apply { failRemaining = 1 }
        val r = f.execute(heaterOff)
        assertTrue(r.isFailure)
        assertFalse(WriteVerifier.shouldRetry(r.exceptionOrNull()!!))
    }

    @Test
    fun `재시도를 포함한 최악 시간이 예전(12초 넘게)보다 짧다`() {
        // 예전: 쓰기 3초 + 다시 읽기 3초, 재시도 2회 + 300ms = 12.3초
        assertTrue(WriteVerifier.worstCaseMs(heaterOff) <= 9_300)
    }
}
