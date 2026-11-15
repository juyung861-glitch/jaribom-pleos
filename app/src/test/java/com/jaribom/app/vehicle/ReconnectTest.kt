package com.jaribom.app.vehicle

import com.jaribom.app.core.Backoff
import com.jaribom.app.core.CabinRoutineEngine
import com.jaribom.app.core.CommandResult
import com.jaribom.app.core.Gear
import com.jaribom.app.core.RoutineEvent
import com.jaribom.app.core.SeatId
import com.jaribom.app.core.SeatState
import com.jaribom.app.core.VehicleCommand
import com.jaribom.app.core.VehicleSnapshot
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 6차 F6-4: connect 백오프(1·2·4·8·16·30초, 최대 30 유지, 성공하면 초기화)와 서비스 사망 재연결. */
class ReconnectTest {

    @Test
    fun `재시도 간격은 1·2·4·8·16·30초 순서이고 최대 30초를 유지한다`() = runBlocking {
        val g = FakeVehicleGateway()
        g.connectFailures = 8
        val waits = mutableListOf<Long>()
        g.sleepFn = { waits += it }
        g.connect()
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L, 30_000L), waits)
        assertEquals(Backoff.STEPS_MS, waits.take(6))
        // 성공했으면 초기화: 다음 사망 뒤 첫 재시도는 다시 1초
        g.connectFailures = 1
        waits.clear()
        g.simulateServiceDeath()
        assertEquals(listOf(1_000L), waits)
    }

    @Test
    fun `재연결 뒤에도 리스너는 1배다 - 사망 때 해제하고 다시 등록`() = runBlocking {
        val g = FakeVehicleGateway()
        g.connect()
        assertEquals(1, g.listenerCount)
        g.simulateServiceDeath()
        assertEquals(1, g.listenerCount)
        g.connectFailures = 2
        g.sleepFn = {}
        g.simulateServiceDeath()
        assertEquals(1, g.listenerCount)
        // 재연결 대기 중에 사망 신호가 다시 와도 등록은 한 번만 늘어난다
        g.connectFailures = 1
        var nested = 0
        g.sleepFn = { if (nested++ == 0) g.simulateServiceDeath() }
        g.simulateServiceDeath()
        assertEquals(1, g.listenerCount)
    }

    @Test
    fun `재시도 중 stop 바로 뒤 start 되어도 다시 연결한다`() = runBlocking {
        val g = FakeVehicleGateway()
        g.connectFailures = 100
        g.sleepFn = { kotlinx.coroutines.delay(60_000) }      // 대기 중에 멈춘다
        val loop = launch { g.connect() }
        kotlinx.coroutines.yield()                           // 재시도 대기에 들어감
        loop.cancel()                                        // stop(): 취소는 비동기
        g.disconnect()
        g.connectFailures = 0
        g.sleepFn = {}
        g.connect()                                          // 바로 start()
        assertEquals(1, g.listenerCount)
    }

    @Test
    fun `재연결 중 쓰기는 연결 없음으로 실패하고 F1 실패 보고 경로로 알림이 나간다`() = runBlocking {
        val left = SeatId.ROW_2_LEFT
        val g = FakeVehicleGateway()
        g.connect()
        var during: Result<Unit>? = null
        val cmd = VehicleCommand.SetWindowLock(left, false)
        g.connectFailures = 1
        g.sleepFn = { during = g.execute(cmd) }          // 재연결 대기 중에 쓰기가 들어온다
        g.simulateServiceDeath()
        val r = during!!
        assertTrue(r.isFailure)
        assertTrue(r.exceptionOrNull()!!.message!!.contains("연결 없음"))

        // 엔진: 아이 좌석 하차 → 잠금 복원 쓰기가 위 실패로 끝났다고 보고하면 실패 알림(다시 시도 대상)이 나온다
        fun snap(t: Long, s: SeatState) = VehicleSnapshot(
            seats = SeatId.entries.associateWith { SeatState() } + (left to s), gear = Gear.PARK, timestampMs = t,
        )
        val e = CabinRoutineEngine().apply { childSeats = setOf(left) }
        val unlocked = SeatState(windowOpenPercent = 0, heaterLevel = 0, ventilationLevel = 0, windowLocked = false)
        e.onSnapshot(snap(0, unlocked.copy(occupied = true, beltBuckled = true)))
        val locked = unlocked.copy(windowLocked = true)
        e.onSnapshot(snap(10_000, locked.copy(occupied = false, beltBuckled = false)))
        e.onSnapshot(snap(11_000, locked.copy(doorOpen = true)))
        val restore = e.onSnapshot(snap(12_000, locked.copy(doorOpen = false))).filterIsInstance<RoutineEvent.Execute>().single()
        val notice = e.onJobFinished(left, restore.commands.map { CommandResult(it, CommandResult.Status.FAILED, r.exceptionOrNull()!!.message!!) }, snap(20_000, locked))
            .filterIsInstance<RoutineEvent.CleanupNotice>().single()
        assertTrue("다시 시도 대상이 있어야 한다", notice.failed.isNotEmpty())
    }
}
