package com.jaribom.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CabinRoutineEngineTest {

    private val seat = SeatId.ROW_1_RIGHT

    private fun snap(t: Long, s: SeatState, gear: Gear = Gear.PARK, speed: Float = 0f) = VehicleSnapshot(
        seats = SeatId.entries.associateWith { SeatState() } + (seat to s),
        gear = gear, timestampMs = t,
    )

    /** 탑승 → 주행 중 창문 40%·열선 2단 → 정차·하차. (문 열림 이벤트, 문 닫힘 이벤트) 를 돌려준다. */
    private fun rideAndLeave(e: CabinRoutineEngine): Pair<List<RoutineEvent>, List<RoutineEvent>> {
        e.onSnapshot(snap(0, SeatState(occupied = true, beltBuckled = true, windowOpenPercent = 0, heaterLevel = 0, ventilationLevel = 0)))
        val riding = SeatState(occupied = true, beltBuckled = true, windowOpenPercent = 40, heaterLevel = 2, ventilationLevel = 0)
        e.onSnapshot(snap(10_000, riding, gear = Gear.DRIVE, speed = 30f))
        val off = riding.copy(occupied = false, beltBuckled = false)
        e.onSnapshot(snap(20_000, off))
        val opened = e.onSnapshot(snap(21_000, off.copy(doorOpen = true)))
        val closed = e.onSnapshot(snap(22_000, off.copy(doorOpen = false)))
        return opened to closed
    }

    private val other = SeatId.ROW_2_LEFT

    /** 조수석([a])과 뒷좌석 왼쪽([b]) 두 좌석 스냅샷 */
    private fun snap2(t: Long, a: SeatState, b: SeatState, gear: Gear = Gear.PARK) = VehicleSnapshot(
        seats = SeatId.entries.associateWith { SeatState() } + (seat to a) + (other to b),
        gear = gear, timestampMs = t,
    )

    /** 탈 때 [boarding] 상태로 앉아 타는 동안 [riding] 으로 바뀐 뒤 P 에서 내린다. 문 닫힘 이벤트를 돌려준다. */
    private fun board(e: CabinRoutineEngine, boarding: SeatState, riding: SeatState): List<RoutineEvent> {
        e.onSnapshot(snap(0, boarding.copy(occupied = true, beltBuckled = true)))
        e.onSnapshot(snap(5_000, riding.copy(occupied = true, beltBuckled = true)))
        val off = riding.copy(occupied = false, beltBuckled = false)
        e.onSnapshot(snap(10_000, off))
        e.onSnapshot(snap(11_000, off.copy(doorOpen = true)))
        return e.onSnapshot(snap(12_000, off.copy(doorOpen = false)))
    }

    @Test
    fun `문이 열리면 동승자 안내, 닫히면 창문 닫고 열선 끄는 정리 명령`() {
        val e = CabinRoutineEngine()
        val (opened, closed) = rideAndLeave(e)
        assertTrue(opened.any { it is RoutineEvent.Speak && it.text.startsWith("두고 가시는 물건은 없나요?") })
        val exec = closed.filterIsInstance<RoutineEvent.Execute>().single()
        assertEquals(seat, exec.seat)
        assertEquals(setOf<VehicleCommand>(VehicleCommand.SetWindow(seat, 0), VehicleCommand.SetSeatHeater(seat, 0)), exec.commands.toSet())
        assertTrue(e.ridingSeats.isEmpty())
        assertEquals(1, e.stats.rides)
    }

    @Test
    fun `탈 때 창문 30퍼센트 - 타는 동안 닫음 - 하차해도 창문을 다시 열지 않는다`() {
        val e = CabinRoutineEngine()
        val closed = board(e, SeatState(windowOpenPercent = 30, heaterLevel = 0, ventilationLevel = 0), SeatState(windowOpenPercent = 0, heaterLevel = 0, ventilationLevel = 0))
        assertTrue(closed.any { it is RoutineEvent.Alighted })
        assertTrue(closed.none { it is RoutineEvent.Execute })                  // 이미 닫혀 있음 → 명령 없음
    }

    @Test
    fun `탈 때 열선 2 - 하차하면 열선 0`() {
        val e = CabinRoutineEngine()
        val on = SeatState(windowOpenPercent = 0, heaterLevel = 2, ventilationLevel = 0)
        val exec = board(e, on, on).filterIsInstance<RoutineEvent.Execute>().single()
        assertEquals(listOf<VehicleCommand>(VehicleCommand.SetSeatHeater(seat, 0)), exec.commands)
    }

    @Test
    fun `실행 결과를 돌려주면 된 일만 운전자에게 보고한다`() {
        val e = CabinRoutineEngine()
        val (_, closed) = rideAndLeave(e)
        val exec = closed.filterIsInstance<RoutineEvent.Execute>().single()
        val results = exec.commands.map {
            if (it is VehicleCommand.SetWindow) CommandResult(it, CommandResult.Status.REJECTED, "사람이 다시 앉아 있음")
            else CommandResult(it, CommandResult.Status.OK)
        }
        // 보고는 하차 묶음 합류 창(문 열림 21초 + 3초)이 닫힌 뒤에 나온다(P0-3)
        val ev = e.onJobFinished(exec.seat, results, snap(25_000, SeatState(occupied = true)))
        val text = ev.filterIsInstance<RoutineEvent.Speak>().single().text
        assertTrue(text.contains("조수석 열선을 껐어요."))
        assertTrue(text.contains("사람이 다시 앉아 있어서 조수석 창문은 그대로 뒀어요."))
        assertEquals(1, e.stats.restores)
        assertTrue(e.lastResult!!.finished)
    }

    @Test
    fun `하차 확정 뒤 다시 앉으면 정리를 취소하고 다음 하차 때 다시 정리한다`() {
        val e = CabinRoutineEngine()
        val (_, closed) = rideAndLeave(e)
        assertTrue(closed.any { it is RoutineEvent.Execute })
        assertTrue(e.cleanupStillValid(seat))                    // 바로 실행했다면 쓰기가 나갔을 시점

        // 문을 닫자마자 다시 열고 앉음(실행 쪽 1.5초 대기 안) → 정리 무효
        val reseated = SeatState(occupied = true, windowOpenPercent = 40, heaterLevel = 2, ventilationLevel = 0)
        e.onSnapshot(snap(22_200, reseated.copy(occupied = false, doorOpen = true)))
        e.onSnapshot(snap(22_500, reseated.copy(doorOpen = true)))
        assertEquals(false, e.cleanupStillValid(seat))
        assertTrue(e.cancelCleanup(seat).single() is RoutineEvent.Log)

        // 문 닫고 3초 → 다시 탑승 확정 → 다음에 내리면 그때 상태(창문 40·열선 2)를 정리한다
        e.onSnapshot(snap(23_000, reseated.copy(doorOpen = false)))
        e.onSnapshot(snap(26_000, reseated.copy(doorOpen = false)))
        assertTrue(seat in e.ridingSeats)
        val off = reseated.copy(occupied = false)
        e.onSnapshot(snap(30_000, off))
        e.onSnapshot(snap(31_000, off.copy(doorOpen = true)))
        val exec = e.onSnapshot(snap(32_000, off.copy(doorOpen = false))).filterIsInstance<RoutineEvent.Execute>().single()
        assertEquals(setOf<VehicleCommand>(VehicleCommand.SetWindow(seat, 0), VehicleCommand.SetSeatHeater(seat, 0)), exec.commands.toSet())
    }

    @Test
    fun `관리를 끈 좌석은 탑승 기록을 남기지 않고 정리도 하지 않는다`() {
        val e = CabinRoutineEngine()
        e.managedSeats = setOf(SeatId.ROW_2_LEFT, SeatId.ROW_2_RIGHT)
        val (_, closed) = rideAndLeave(e)
        assertTrue(closed.none { it is RoutineEvent.Execute })
        // 보고는 하차 묶음 합류 창이 닫힌 뒤(tick) 나온다(P0-3)
        val later = e.onSnapshot(snap(25_000, SeatState(occupied = false)))
        assertTrue(later.any { it is RoutineEvent.Speak && it.text.contains("관리를 꺼 둬서") })
    }

    @Test
    fun `두 좌석이 1초 차이로 내리면 동승자 안내 1회, 운전자 보고 1회, 알림 1건`() {
        val e = CabinRoutineEngine()
        val on = SeatState(occupied = true, beltBuckled = true, windowOpenPercent = 0, heaterLevel = 2, ventilationLevel = 0)
        e.onSnapshot(snap2(0, on, on))
        val off = on.copy(occupied = false, beltBuckled = false)
        e.onSnapshot(snap2(10_000, off, off))
        val all = mutableListOf<RoutineEvent>()
        all += e.onSnapshot(snap2(11_000, off.copy(doorOpen = true), off))                       // 조수석 문 열림
        all += e.onSnapshot(snap2(12_000, off.copy(doorOpen = true), off.copy(doorOpen = true)))  // 1초 뒤 뒷좌석 문 열림
        all += e.onSnapshot(snap2(12_500, off, off.copy(doorOpen = true)))                       // 조수석 닫힘
        all += e.onSnapshot(snap2(13_000, off, off))                                             // 뒷좌석 닫힘
        val execs = all.filterIsInstance<RoutineEvent.Execute>()
        assertEquals(setOf(seat, other), execs.mapNotNull { it.seat }.toSet())
        val cleaned = off.copy(heaterLevel = 0)
        for (x in execs) all += e.onJobFinished(x.seat, x.commands.map { CommandResult(it, CommandResult.Status.OK) }, snap2(15_000, cleaned, cleaned))

        val speaks = all.filterIsInstance<RoutineEvent.Speak>()
        assertEquals(1, speaks.count { it.text.startsWith("두고 가시는 물건은 없나요?") })
        val report = speaks.filter { it.text.contains("껐어요") }.single()
        assertTrue(report.text.contains("조수석·뒷좌석 왼쪽 열선을 껐어요."))
        // F12-2: 음성으로 나간 성공 보고는 알림을 남기지 않는다. 음성이 실패하면 묶음당 1건이 나온다.
        assertTrue(all.none { it is RoutineEvent.CleanupNotice })
        val notice = e.onSpeechFailed(report.text, "TIMEOUT").filterIsInstance<RoutineEvent.CleanupNotice>().single()
        assertEquals(listOf(seat, other), notice.seats)
    }

    @Test
    fun `탑승 멘트를 끄면 탑승 음성이 없다`() {
        val e = CabinRoutineEngine()
        e.boardingVoice = false
        val ev = e.onSnapshot(snap(0, SeatState(occupied = true, beltBuckled = true)))
        assertTrue(ev.any { it is RoutineEvent.Boarded })
        assertTrue(ev.none { it is RoutineEvent.Speak })
        assertTrue(seat in e.ridingSeats)                                         // 탑승 기록은 그대로
    }

    @Test
    fun `하차 멘트를 끄면 문 열림 음성은 없고 운전자 보고는 있다`() {
        val e = CabinRoutineEngine()
        e.exitVoice = false
        val (opened, closed) = rideAndLeave(e)
        assertTrue(opened.any { it is RoutineEvent.DoorOpened })
        assertTrue(opened.none { it is RoutineEvent.Speak })
        val exec = closed.filterIsInstance<RoutineEvent.Execute>().single()
        val done = e.onJobFinished(exec.seat, exec.commands.map { CommandResult(it, CommandResult.Status.OK) }, snap(25_000, SeatState(occupied = false)))
        assertTrue(done.filterIsInstance<RoutineEvent.Speak>().single().text.contains("조수석 창문을 닫고 열선을 껐어요."))
    }

    @Test
    fun `정리 취소 뒤 같은 사람이 다시 앉으면 다시 인사하지 않는다`() {
        val e = CabinRoutineEngine()
        rideAndLeave(e)
        val reseated = SeatState(occupied = true, windowOpenPercent = 40, heaterLevel = 2, doorOpen = false)
        e.onSnapshot(snap(22_200, reseated.copy(occupied = false, doorOpen = true)))
        e.onSnapshot(snap(22_500, reseated.copy(doorOpen = true)))
        e.cancelCleanup(seat)
        e.onSnapshot(snap(23_000, reseated))
        val again = e.onSnapshot(snap(26_000, reseated))
        assertTrue(again.any { it is RoutineEvent.Boarded })
        assertTrue(again.none { it is RoutineEvent.Speak })
    }

    @Test
    fun `관리를 끈 좌석은 탑승 인사를 하지 않는다`() {
        val e = CabinRoutineEngine()
        e.managedSeats = setOf(SeatId.ROW_2_LEFT)
        val ev = e.onSnapshot(snap(0, SeatState(occupied = true, beltBuckled = true)))
        assertTrue(ev.any { it is RoutineEvent.Boarded })
        assertTrue(ev.none { it is RoutineEvent.Speak })
    }

    @Test
    fun `기어 D 에서 하차하면 창문 닫기·열선 끄기를 바로 내보내고 보고는 알림만`() {
        // A1(4차 심사): 미루지 않는다. 기어는 보고 방식(P 면 음성+알림, 아니면 알림만)에만 쓰인다.
        val e = CabinRoutineEngine()
        val d = Gear.DRIVE
        e.onSnapshot(snap(0, SeatState(occupied = true, beltBuckled = true, windowOpenPercent = 0, heaterLevel = 0, ventilationLevel = 0), gear = d))
        val riding = SeatState(occupied = true, beltBuckled = true, windowOpenPercent = 40, heaterLevel = 2, ventilationLevel = 0)
        e.onSnapshot(snap(5_000, riding, gear = d))
        val off = riding.copy(occupied = false, beltBuckled = false)
        e.onSnapshot(snap(10_000, off, gear = d))
        val opened = e.onSnapshot(snap(11_000, off.copy(doorOpen = true), gear = d))
        assertTrue(opened.any { it is RoutineEvent.Speak && it.text.startsWith("두고 가시는 물건은 없나요?") })
        val closed = e.onSnapshot(snap(12_000, off.copy(doorOpen = false), gear = d))
        assertTrue(closed.any { it is RoutineEvent.Alighted })
        assertTrue(closed.none { it is RoutineEvent.CleanupNotice })                 // "정차하면 정리할게요" 대기 알림 없음
        val exec = closed.filterIsInstance<RoutineEvent.Execute>().single()
        assertEquals(seat, exec.seat)
        assertEquals(setOf<VehicleCommand>(VehicleCommand.SetWindow(seat, 0), VehicleCommand.SetSeatHeater(seat, 0)), exec.commands.toSet())
        for (c in exec.commands) assertTrue(SafetyGate.check(c, snap(13_500, off, gear = d)).allowed)

        val ok = exec.commands.map { CommandResult(it, CommandResult.Status.OK) }
        val done = e.onJobFinished(exec.seat, ok, snap(15_000, off.copy(windowOpenPercent = 0, heaterLevel = 0), gear = d))
        assertTrue(done.none { it is RoutineEvent.Speak })                             // 운전 중에는 말하지 않음
        val notice = done.filterIsInstance<RoutineEvent.CleanupNotice>().single()
        assertEquals(true, notice.final)
        assertTrue(notice.text.contains("조수석 창문을 닫고 열선을 껐어요."))
    }

    @Test
    fun `기어 D 에서 탑승해도 탑승 프리셋을 바로 내보낸다`() {
        val e = CabinRoutineEngine()
        e.weather = WeatherInfo(outdoorTempC = -2f) // 추움 → 탑승 프리셋이 열선을 켬
        val ev = e.onSnapshot(snap(0, SeatState(occupied = true, beltBuckled = true, heaterLevel = 0), gear = Gear.DRIVE, speed = 20f))
        val exec = ev.filterIsInstance<RoutineEvent.Execute>().single()
        assertTrue(exec.preset)
        assertEquals(seat, exec.seat)
        assertEquals(listOf<VehicleCommand>(VehicleCommand.SetSeatHeater(seat, 2)), exec.commands)
        assertTrue(e.presetStillValid(exec.jobId, seat))
    }

    @Test
    fun `탑승 직후 하차하면 대기 프리셋은 버려지고 빈 좌석 열선이 켜진 채 남지 않는다`() {
        val e = CabinRoutineEngine()
        e.weather = WeatherInfo(outdoorTempC = -2f)
        val boarded = SeatState(occupied = true, beltBuckled = true, heaterLevel = 0, windowOpenPercent = 0, ventilationLevel = 0)
        val exec = e.onSnapshot(snap(0, boarded)).filterIsInstance<RoutineEvent.Execute>().single()
        val off = boarded.copy(occupied = false, beltBuckled = false)
        e.onSnapshot(snap(500, off))
        e.onSnapshot(snap(800, off.copy(doorOpen = true)))
        val closed = e.onSnapshot(snap(1_200, off))
        assertTrue(closed.any { it is RoutineEvent.Alighted })
        assertEquals(false, e.presetStillValid(exec.jobId, seat))                     // 실행 직전 재확인에서 버려진다
        assertTrue(e.onPresetFinished(exec.jobId, seat, emptyList()).isEmpty())       // 아무것도 안 썼으면 되돌릴 것도 없음
    }

    @Test
    fun `프리셋을 쓰는 사이 하차가 확정되면 켠 열선을 바로 끈다`() {
        val e = CabinRoutineEngine()
        e.weather = WeatherInfo(outdoorTempC = -2f)
        val boarded = SeatState(occupied = true, beltBuckled = true, heaterLevel = 0, windowOpenPercent = 0, ventilationLevel = 0)
        val exec = e.onSnapshot(snap(0, boarded)).filterIsInstance<RoutineEvent.Execute>().single()
        val off = boarded.copy(occupied = false, beltBuckled = false)       // 열선 2 가 아직 스냅샷에 안 보임
        e.onSnapshot(snap(500, off))
        e.onSnapshot(snap(800, off.copy(doorOpen = true)))
        e.onSnapshot(snap(1_200, off))
        val ok = exec.commands.map { CommandResult(it, CommandResult.Status.OK) }
        val revert = e.onPresetFinished(exec.jobId, seat, ok).filterIsInstance<RoutineEvent.Execute>().single()
        assertEquals(listOf<VehicleCommand>(VehicleCommand.SetSeatHeater(seat, 0)), revert.commands)
        assertEquals(false, revert.preset)
    }

    /** 벨트로 타서 [t] 부터 내리기 시작. 문 열림 이벤트를 돌려준다. */
    private fun rideAgain(e: CabinRoutineEngine, t: Long): List<RoutineEvent> {
        e.onSnapshot(snap(t, SeatState(occupied = true, beltBuckled = true)))
        e.onSnapshot(snap(t + 5_000, SeatState(occupied = false)))
        val opened = e.onSnapshot(snap(t + 6_000, SeatState(occupied = false, doorOpen = true)))
        e.onSnapshot(snap(t + 7_000, SeatState(occupied = false, doorOpen = false)))
        return opened
    }

    @Test
    fun `붙일 문장이 없는 기본 멘트는 운행당 한 번만 말한다`() {
        val e = CabinRoutineEngine()
        assertTrue(rideAgain(e, 0).any { it is RoutineEvent.Speak && it.text == "두고 가시는 물건은 없나요?" })
        assertTrue(rideAgain(e, 20_000).none { it is RoutineEvent.Speak })          // 같은 운행 두 번째 하차
        e.endTrip()
        assertTrue(rideAgain(e, 40_000).any { it is RoutineEvent.Speak && it.text == "두고 가시는 물건은 없나요?" })
    }

    @Test
    fun `기록은 관리 좌석 하차·정리한 하차 묶음·실제로 말한 안내만 센다`() {
        val e = CabinRoutineEngine()
        e.weather = WeatherInfo(rainingNow = true)
        val (_, closed) = rideAndLeave(e)
        val exec = closed.filterIsInstance<RoutineEvent.Execute>().single()
        e.onJobFinished(exec.seat, exec.commands.map { CommandResult(it, CommandResult.Status.OK) }, snap(25_000, SeatState(occupied = false)))
        assertEquals(CabinRoutineEngine.Stats(rides = 1, restores = 1, reminders = 1, windowsClosed = 1, heatersOff = 1, rainReminders = 1), e.stats)

        // 하차 멘트를 끄면 비 안내를 말하지 않으므로 세지 않는다. 정리할 것이 없으면 정리한 하차도 아니다.
        e.exitVoice = false
        rideAgain(e, 40_000)
        e.onSnapshot(snap(50_000, SeatState(occupied = false)))
        assertEquals(2, e.stats.rides)
        assertEquals(1, e.stats.reminders)
        assertEquals(1, e.stats.restores)

        // 관리를 끄 좌석의 하차는 세지 않는다
        e.managedSeats = setOf(SeatId.ROW_2_LEFT)
        rideAgain(e, 60_000)
        assertEquals(2, e.stats.rides)
    }

    @Test
    fun `하차 30초 뒤에 나오는 다시 시도 보고에는 아까 내리신 좌석 맥락을 붙인다`() {
        val e = CabinRoutineEngine()
        val (_, closed) = rideAndLeave(e)                                                // 22초에 하차 확정
        val exec = closed.filterIsInstance<RoutineEvent.Execute>().single()
        val failed = e.onJobFinished(exec.seat, exec.commands.map { CommandResult(it, CommandResult.Status.FAILED) }, snap(25_000, SeatState(occupied = false)))
        assertTrue(failed.filterIsInstance<RoutineEvent.Speak>().single().text.startsWith("조수석 창문·열선 정리에 실패했어요."))
        // 알림의 [다시 시도]를 1분 뒤에 누름
        val retried = e.onJobFinished(exec.seat, exec.commands.map { CommandResult(it, CommandResult.Status.OK) }, snap(82_000, SeatState(occupied = false)))
        val text = retried.filterIsInstance<RoutineEvent.Speak>().single().text
        assertTrue(text, text.startsWith("아까 내리신 조수석 창문을 닫고 열선을 껐어요."))
    }
}
