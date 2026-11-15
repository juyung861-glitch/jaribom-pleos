package com.jaribom.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BoardingStateMachineTest {

    private fun snap(
        t: Long,
        occupied: Boolean? = null,
        belt: Boolean? = null,
        door: Boolean? = null,
        speed: Float? = 0f,
        gear: Gear = Gear.PARK,
        seat: SeatId = SeatId.ROW_2_LEFT,
    ) = VehicleSnapshot(
        seats = SeatId.entries.associateWith { SeatState() } +
            (seat to SeatState(occupied = occupied, beltBuckled = belt, doorOpen = door)),
        gear = gear, timestampMs = t,
    )

    /** 빈 차로 시작한다(앱 시작 시 이미 앉아 있던 좌석 예외를 쓰지 않도록) */
    private fun started(m: BoardingStateMachine) = m.also { it.update(snap(0, occupied = false, door = false)) }

    @Test
    fun `착좌 후 벨트를 채우면 즉시 탑승 확정`() {
        val m = started(BoardingStateMachine())
        assertTrue(m.update(snap(100, occupied = true)).isEmpty())
        val ev = m.update(snap(500, occupied = true, belt = true))
        assertEquals(listOf<RoutineEvent>(RoutineEvent.Boarded(SeatId.ROW_2_LEFT)), ev)
    }

    @Test
    fun `문 열림·닫힘 뒤 착좌가 3초 유지되면 탑승 확정`() {
        val m = started(BoardingStateMachine(boardingDebounceMs = 3000))
        m.update(snap(1_000, occupied = false, door = true))
        assertTrue(m.update(snap(1_500, occupied = true, door = true)).isEmpty())    // 문이 열린 채로 앉음
        assertTrue(m.update(snap(2_000, occupied = true, door = false)).isEmpty())   // 문 닫힘 → 여기서 3초
        assertTrue(m.update(snap(4_500, occupied = true, door = false)).isEmpty())
        assertEquals(listOf<RoutineEvent>(RoutineEvent.Boarded(SeatId.ROW_2_LEFT)), m.update(snap(5_000, occupied = true, door = false)))
    }

    @Test
    fun `착좌 후 새 신호가 없어도 시각만 바꾼 재판정으로 3초 뒤 탑승 확정`() {
        val m = started(BoardingStateMachine(boardingDebounceMs = 3000))
        m.update(snap(500, occupied = false, door = true))
        val first = snap(1_000, occupied = true, door = false)
        assertTrue(m.update(first).isEmpty())
        assertTrue(m.needsTick)
        assertTrue(m.update(first.copy(timestampMs = 3_500)).isEmpty())   // tick
        assertEquals(listOf<RoutineEvent>(RoutineEvent.Boarded(SeatId.ROW_2_LEFT)), m.update(first.copy(timestampMs = 4_000)))
        assertEquals(false, m.needsTick)
    }

    @Test
    fun `문 이력 없이 착좌만 들어오면 탑승으로 보지 않고 기록만 남긴다`() {
        val m = started(BoardingStateMachine(boardingDebounceMs = 3000))
        for (t in listOf(1_000L, 5_000L, 30_000L)) assertTrue(m.update(snap(t, occupied = true, door = false)).isEmpty())
        assertEquals(BoardingStateMachine.Phase.EMPTY, m.phaseOf(SeatId.ROW_2_LEFT))
        assertEquals(false, m.needsTick)
        assertEquals(1, m.drainNotes().count { it.contains("문 열림·닫힘 이력이 없어") })   // 한 번만 기록
    }

    @Test
    fun `시작할 때 이미 앉아 있던 좌석은 문 이력 없이 탑승 확정`() {
        val m = BoardingStateMachine(boardingDebounceMs = 3000)
        assertTrue(m.update(snap(0)).isEmpty())                                       // 연결 직후: 착좌값 아직 모름
        assertEquals(listOf<RoutineEvent>(RoutineEvent.Boarded(SeatId.ROW_2_LEFT)), m.update(snap(800, occupied = true, door = false)))
        assertTrue(m.drainNotes().single().contains("시작할 때 이미 앉아 있음"))
    }

    @Test
    fun `하차 - 착좌 OFF 뒤 문이 열리면 안내 닫히면 확정`() {
        val m = BoardingStateMachine()
        m.update(snap(0, occupied = true, belt = true))        // Boarded
        assertTrue(m.update(snap(10_000, occupied = false)).isEmpty())
        assertEquals(listOf<RoutineEvent>(RoutineEvent.DoorOpened(SeatId.ROW_2_LEFT)), m.update(snap(11_000, occupied = false, door = true)))
        val ev = m.update(snap(12_000, occupied = false, door = false))
        assertEquals(listOf<RoutineEvent>(RoutineEvent.Alighted(SeatId.ROW_2_LEFT)), ev)
        assertEquals(BoardingStateMachine.Phase.EMPTY, m.phaseOf(SeatId.ROW_2_LEFT))
    }

    @Test
    fun `하차 - 문을 먼저 열고 착좌가 풀려도 안내하고 닫히면 확정한다(순서 무관)`() {
        val m = BoardingStateMachine()
        m.update(snap(0, occupied = true, belt = true))                                   // Boarded
        assertTrue("앉은 채 문만 열면 안내하지 않는다", m.update(snap(10_000, occupied = true, belt = true, door = true)).isEmpty())
        // 문이 열린 채 일어남: 그 스냅샷 또는 다음 틱(시각만 바꾼 재판정)에서 안내가 나온다
        val up = snap(11_000, occupied = false, door = true)
        val opened = m.update(up) + m.update(up.copy(timestampMs = 11_500))
        assertEquals(listOf<RoutineEvent>(RoutineEvent.DoorOpened(SeatId.ROW_2_LEFT)), opened)
        assertEquals(listOf<RoutineEvent>(RoutineEvent.Alighted(SeatId.ROW_2_LEFT)), m.update(snap(12_000, occupied = false, door = false)))
        assertEquals(BoardingStateMachine.Phase.EMPTY, m.phaseOf(SeatId.ROW_2_LEFT))
    }

    @Test
    fun `뒤척임(착좌 OFF 후 다시 ON)은 하차가 아니다`() {
        val m = BoardingStateMachine()
        m.update(snap(0, occupied = true, belt = true))
        m.update(snap(10_000, occupied = false))
        assertTrue(m.update(snap(11_000, occupied = true)).isEmpty())
        assertEquals(BoardingStateMachine.Phase.RIDING, m.phaseOf(SeatId.ROW_2_LEFT))
    }

    @Test
    fun `짐만 넣고 문을 여닫으면(탑승 이력 없음) 아무 이벤트도 없다`() {
        val m = BoardingStateMachine()
        assertTrue(m.update(snap(0, occupied = false, door = true)).isEmpty())
        assertTrue(m.update(snap(1000, occupied = false, door = false)).isEmpty())
    }

    @Test
    fun `기어 D 에서도 착좌 OFF 면 문 열림을 기다린다`() {
        // P0-1(심사 3차): D 에 둔 채 브레이크만 밟고 내려 주는 경우도 하차로 잡아야 한다.
        val m = BoardingStateMachine()
        m.update(snap(0, occupied = true, belt = true))
        m.update(snap(10_000, occupied = false, speed = null, gear = Gear.DRIVE))
        assertEquals(BoardingStateMachine.Phase.ALIGHTING_WAIT_DOOR_OPEN, m.phaseOf(SeatId.ROW_2_LEFT))
    }

    @Test
    fun `기어 D 에서 착좌가 잠깐 꺼졌다 켜지면(문 안 열림) 탑승 유지`() {
        val m = BoardingStateMachine()
        m.update(snap(0, occupied = true, belt = true))
        assertTrue(m.update(snap(10_000, occupied = false, speed = null, gear = Gear.DRIVE)).isEmpty())
        assertTrue(m.update(snap(11_000, occupied = true, speed = null, gear = Gear.DRIVE)).isEmpty())
        assertEquals(BoardingStateMachine.Phase.RIDING, m.phaseOf(SeatId.ROW_2_LEFT))
    }

    @Test
    fun `착좌 OFF 후 60초 안에 문이 안 열리면 RIDING 으로 돌아가고 그 뒤로는 오가지 않는다`() {
        val m = BoardingStateMachine()
        m.update(snap(0, occupied = true, belt = true))
        m.update(snap(1_000, occupied = false))
        assertEquals(BoardingStateMachine.Phase.ALIGHTING_WAIT_DOOR_OPEN, m.phaseOf(SeatId.ROW_2_LEFT))
        assertTrue(m.needsTick)
        m.update(snap(61_000, occupied = false))                         // 문이 안 열리고 60초
        assertEquals(BoardingStateMachine.Phase.RIDING, m.phaseOf(SeatId.ROW_2_LEFT))
        assertEquals(false, m.needsTick)                                  // 0.5초 재판정 멈춤
        for (t in listOf(62_000L, 120_000L, 181_000L)) {                 // OFF 가 이어져도 재진입하지 않음
            assertTrue(m.update(snap(t, occupied = false)).isEmpty())
            assertEquals(BoardingStateMachine.Phase.RIDING, m.phaseOf(SeatId.ROW_2_LEFT))
            assertEquals(false, m.needsTick)
        }
        // 그 뒤 문 열림·닫힘 → 하차
        assertEquals(listOf<RoutineEvent>(RoutineEvent.DoorOpened(SeatId.ROW_2_LEFT)), m.update(snap(190_000, occupied = false, door = true)))
        assertEquals(listOf<RoutineEvent>(RoutineEvent.Alighted(SeatId.ROW_2_LEFT)), m.update(snap(191_000, occupied = false, door = false)))
        assertEquals(BoardingStateMachine.Phase.EMPTY, m.phaseOf(SeatId.ROW_2_LEFT))
    }

    @Test
    fun `대기 시간이 지난 좌석도 다시 앉았다 일어나면 하차 대기에 다시 들어간다`() {
        val m = BoardingStateMachine()
        m.update(snap(0, occupied = true, belt = true))
        m.update(snap(1_000, occupied = false))
        m.update(snap(61_000, occupied = false))                         // 타임아웃 → RIDING(비무장)
        m.update(snap(70_000, occupied = true))                          // 다시 앉음 → 재무장
        assertEquals(BoardingStateMachine.Phase.RIDING, m.phaseOf(SeatId.ROW_2_LEFT))
        m.update(snap(80_000, occupied = false))                         // 다시 일어남
        assertEquals(BoardingStateMachine.Phase.ALIGHTING_WAIT_DOOR_OPEN, m.phaseOf(SeatId.ROW_2_LEFT))
        assertTrue(m.needsTick)
    }

    @Test
    fun `운전석은 루틴 대상이 아니다`() {
        val m = BoardingStateMachine()
        assertTrue(m.update(snap(0, occupied = true, belt = true, seat = SeatId.ROW_1_LEFT)).isEmpty())
    }

    @Test
    fun `기어 D 에서도 문 열림·닫힘이면 하차 확정(문 열림 = 정차의 증거)`() {
        val m = BoardingStateMachine()
        m.update(snap(0, occupied = true, belt = true))
        m.update(snap(10_000, occupied = false, speed = null, gear = Gear.DRIVE))
        assertEquals(listOf<RoutineEvent>(RoutineEvent.DoorOpened(SeatId.ROW_2_LEFT)), m.update(snap(11_000, occupied = false, door = true, speed = null, gear = Gear.DRIVE)))
        assertEquals(listOf<RoutineEvent>(RoutineEvent.Alighted(SeatId.ROW_2_LEFT)), m.update(snap(12_000, occupied = false, door = false, speed = null, gear = Gear.DRIVE)))
    }

    @Test
    fun `문을 열고 다시 앉으면 하차가 아니다`() {
        val m = BoardingStateMachine()
        m.update(snap(0, occupied = true, belt = true))
        m.update(snap(10_000, occupied = false))
        m.update(snap(11_000, occupied = false, door = true))
        assertTrue(m.update(snap(12_000, occupied = true, door = true)).isEmpty())
        assertTrue(m.update(snap(13_000, occupied = true, door = false)).isEmpty())
        assertEquals(BoardingStateMachine.Phase.RIDING, m.phaseOf(SeatId.ROW_2_LEFT))
    }
}