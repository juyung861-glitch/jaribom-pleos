package com.jaribom.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F8-3: 아이 좌석에 어른이 탄 경우 [어른이 탔어요] — 이번 운행만 그 좌석을 일반(존댓말) 안내·일반 남은 물건 규칙으로 다룬다.
 * 버튼 자체는 엔진 이벤트로 쓰기를 만들지 않는다. P 에서의 잠금 해제는 컨트롤러가 adultUnlockCommand 로만 낸다(F12-4, KidsLockTest).
 * 운행이 끝나면 키즈락 모드로 돌아온다. 오래된 알림의 버튼은 무시한다.
 */
class AdultAboardTest {

    private val seat = SeatId.ROW_2_RIGHT
    private val base = SeatState(windowOpenPercent = 0, heaterLevel = 0, ventilationLevel = 0, doorOpen = false, windowLocked = false)

    private fun snap(t: Long, s: SeatState) = VehicleSnapshot(
        seats = SeatId.entries.associateWith { SeatState() } + (seat to s), gear = Gear.PARK, timestampMs = t,
    )

    private fun engine(child: Boolean) = CabinRoutineEngine().also { if (child) it.childSeats = setOf(seat) }

    /** 탑승 확정까지. 아이 좌석 모드 탑승 알림(버튼 포함)을 돌려준다. */
    private fun board(e: CabinRoutineEngine, t0: Long = 0): List<RoutineEvent> {
        val out = mutableListOf<RoutineEvent>()
        out += e.onSnapshot(snap(t0, base.copy(occupied = true, beltBuckled = true)))
        out += e.onSnapshot(snap(t0 + 5_000, base.copy(occupied = true, beltBuckled = true)))
        return out
    }

    /** 일어남 → 문 열림. 동승자 안내 음성 문장을 돌려준다. */
    private fun doorOpenSpeech(e: CabinRoutineEngine, t0: Long = 0): List<String> {
        e.onSnapshot(snap(t0 + 10_000, base.copy(occupied = false, beltBuckled = false, windowLocked = true)))
        return e.onSnapshot(snap(t0 + 11_000, base.copy(occupied = false, doorOpen = true, windowLocked = true)))
            .filterIsInstance<RoutineEvent.Speak>().map { it.text }
    }

    private fun List<RoutineEvent>.adultNotice() = filterIsInstance<RoutineEvent.CleanupNotice>().firstOrNull { it.adultSeat != null }

    @Test
    fun `아이 좌석 탑승 확정 때 운전자 알림과 어른이 탔어요 버튼 대상이 나온다`() {
        val n = board(engine(child = true)).adultNotice()
        assertNotNull(n)
        assertEquals("${seat.label} 키즈락 모드로 탑승을 확인했어요.", n!!.text)
        assertEquals(seat, n.adultSeat)
        assertTrue(board(engine(child = false)).adultNotice() == null)
    }

    @Test
    fun `버튼을 누르면 이번 운행은 하차 안내가 일반 존댓말 문구가 된다`() {
        val childE = engine(child = true).also { board(it) }
        val childSpeech = doorOpenSpeech(childE)

        val e = engine(child = true)
        val n = board(e).adultNotice()!!
        e.onAdultAboard(n.key, seat, n.buttonEpisodes.getValue(seat))
        val adultSpeech = doorOpenSpeech(e)

        val plainE = engine(child = false).also { board(it) }
        val plainSpeech = doorOpenSpeech(plainE)

        assertNotEquals("아이 말투가 그대로면 안 됨", childSpeech, adultSpeech)
        assertEquals(plainSpeech, adultSpeech)
    }

    @Test
    fun `버튼은 쓰기를 만들지 않고 잠금 해제 명령도 없다 - 알림만 갱신`() {
        val e = engine(child = true)
        val n = board(e).adultNotice()!!
        val r = e.onAdultAboard(n.key, seat, n.buttonEpisodes.getValue(seat))
        // F12-fix2: "내리면 풀려요"는 없앤다(탑승 전부터 잠긴 좌석에서 사실과 달랐다). 차량 상태 없이 누르면 안내 문장만.
        assertEquals("${seat.label}은 이번 운행만 어른 좌석으로 안내할게요.", r.text)
        assertTrue(r.adultSeat == null && r.climateOff.isEmpty() && r.windowClose.isEmpty() && r.failed.isEmpty())
        // 버튼을 누른 뒤 탑승 중에는 잠금 해제(SetWindowLock false) 쓰기가 나오지 않는다
        val during = e.onSnapshot(snap(7_000, base.copy(occupied = true, beltBuckled = true, windowLocked = true)))
        assertTrue(during.filterIsInstance<RoutineEvent.Execute>().none { ex -> ex.commands.any { it is VehicleCommand.SetWindowLock && !it.locked } })
    }

    @Test
    fun `운행이 끝나면 다음 운행은 다시 아이 말투다`() {
        val e = engine(child = true)
        val n = board(e).adultNotice()!!
        e.onAdultAboard(n.key, seat, n.buttonEpisodes.getValue(seat))
        val adultSpeech = doorOpenSpeech(e)
        e.endTrip()
        assertTrue(e.isChildNow(seat))
        val nextTrip = engine(child = true).also { board(it, 100_000) }
        val childSpeech = doorOpenSpeech(nextTrip, 100_000)
        assertNotEquals(adultSpeech, childSpeech)
    }

    @Test
    fun `지난 운행 알림의 버튼은 무시한다 - 어른 표시도 하지 않는다`() {
        val e = engine(child = true)
        val n = board(e).adultNotice()!!
        val episode = n.buttonEpisodes.getValue(seat)
        e.endTrip()                                         // 운행이 끝나 모든 좌석이 새 에피소드
        val r = e.onAdultAboard(n.key, seat, episode)
        assertEquals(ButtonGuard.STALE_TEXT, r.text)
        assertTrue(e.isChildNow(seat))
    }

    @Test
    fun `알림 뒤 그 좌석 문이 다시 열렸으면 오래된 알림이다`() {
        val e = engine(child = true)
        val n = board(e).adultNotice()!!
        e.onSnapshot(snap(8_000, base.copy(occupied = true, beltBuckled = true, doorOpen = true)))   // 다음 손님 에피소드
        assertEquals(ButtonGuard.STALE_TEXT, e.onAdultAboard(n.key, seat, n.buttonEpisodes.getValue(seat)).text)
    }
}
