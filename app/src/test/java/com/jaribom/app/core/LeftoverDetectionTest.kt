package com.jaribom.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 남은 물건 감지(무거운 가방이 남아 착좌가 안 꺼짐).
 * 후보 (a) 같은 하차 묶음의 다른 좌석이 하차 확정 + 이 좌석은 벨트 풀림·착좌 유지 + 이 좌석 문이 열렸다 닫힘 → 엔진에 넣은 규칙
 * 후보 (b) 운행 종료(기어 P + 운전석 문 열림) 때 벨트 풀림·착좌 유지인 동승 좌석 → 비교용으로 이 테스트 안에만 둔다
 * 같은 시나리오 6개를 두 후보에 넣어 오탐·누락을 센다([candidates are compared]).
 */
class LeftoverDetectionTest {

    private val x = SeatId.ROW_2_LEFT      // 가방이 남을 수 있는 좌석
    private val p = SeatId.ROW_2_RIGHT     // 같이 내리는 다른 동승자
    private val driver = SeatId.ROW_1_LEFT

    private data class Frame(val t: Long, val gear: Gear, val seats: Map<SeatId, SeatState>)

    private fun snapOf(f: Frame) = VehicleSnapshot(
        seats = SeatId.entries.associateWith { SeatState(occupied = false, beltBuckled = false, doorOpen = false) } +
            (driver to SeatState(occupied = true, beltBuckled = true, doorOpen = false)) + f.seats,
        gear = f.gear, timestampMs = f.t,
    )

    /** 시나리오 작성기. 좌석 상태를 바꿔 가며 프레임을 쌓는다. */
    private class Script {
        val frames = mutableListOf<Frame>()
        val st = mutableMapOf<SeatId, SeatState>()
        var gear = Gear.PARK
        fun at(t: Long, vararg change: Pair<SeatId, (SeatState) -> SeatState>) {
            change.forEach { (s, f) -> st[s] = f(st[s] ?: SeatState(occupied = false, beltBuckled = false, doorOpen = false)) }
            frames += Frame(t, gear, st.toMap())
        }
    }

    private fun sit(belt: Boolean = true): (SeatState) -> SeatState = { it.copy(occupied = true, beltBuckled = belt) }
    private val unbuckle: (SeatState) -> SeatState = { it.copy(beltBuckled = false) }
    private val standUp: (SeatState) -> SeatState = { it.copy(occupied = false, beltBuckled = false) }
    private val open: (SeatState) -> SeatState = { it.copy(doorOpen = true) }
    private val close: (SeatState) -> SeatState = { it.copy(doorOpen = false) }

    /** 탑승(벨트 체결) → 주행 → 정차. 끝에 운전자가 내려 운전석 문을 연다(운행 종료). */
    private fun script(seats: List<SeatId>, body: Script.() -> Unit): List<Frame> = Script().apply {
        at(0, *seats.map { it to sit() }.toTypedArray())
        gear = Gear.DRIVE; at(5_000)
        gear = Gear.PARK; at(9_000)
        body()
        at(30_000, driver to open)                        // 운행 종료: P + 운전석 문 열림
    }.frames

    /** 시나리오: (이름, 정답 = 실제로 물건이 남았나, 프레임) */
    private val scenarios: List<Triple<String, Boolean, List<Frame>>> by lazy { listOf(
        Triple("S1 혼자 탄 동승자가 가방을 두고 내림", true, script(listOf(x)) {
            at(10_000, x to unbuckle); at(11_000, x to open); at(12_000, x to close)
        }),
        Triple("S2 두 명이 내리고 한 좌석에 가방이 남음", true, script(listOf(x, p)) {
            at(10_000, x to unbuckle, p to standUp); at(11_000, x to open, p to open); at(12_000, p to close); at(12_500, x to close)
        }),
        Triple("S3 다른 동승자만 내리고 이 좌석 사람은 벨트를 맨 채 문을 여닫음", false, script(listOf(x, p)) {
            at(10_000, p to standUp); at(11_000, x to open, p to open); at(12_000, p to close); at(12_500, x to close)
        }),
        Triple("S4 다른 동승자만 내리고 이 좌석 사람은 벨트를 푼 채 그대로 앉아 있음", false, script(listOf(x, p)) {
            at(10_000, x to unbuckle, p to standUp); at(11_000, p to open); at(12_000, p to close)
        }),
        Triple("S5 벨트를 푼 채 앉아서 자기 문을 열어 내리는 사람을 배웅", false, script(listOf(x, p)) {
            at(10_000, x to unbuckle, p to standUp); at(11_000, x to open, p to open); at(12_000, p to close); at(12_500, x to close)
        }),
        Triple("S6 동승자는 벨트를 풀고 차에서 기다리고 운전자만 내림", false, script(listOf(x)) {
            at(10_000, x to unbuckle)
        }),
    ) }

    /** 남은 물건 문장이 들어간 알림(단독 또는 하차 정리 보고에 합쳐진 것, 5차 심사 F3) */
    private fun List<RoutineEvent>.leftoverNotices() =
        filterIsInstance<RoutineEvent.CleanupNotice>().filter { it.text.contains("남아 있는 것 같아요") }

    /** 후보 (a): 엔진. 알린 좌석을 돌려준다. */
    private fun candidateA(frames: List<Frame>): Set<SeatId> {
        val e = CabinRoutineEngine()
        val notices = frames.flatMap { e.onSnapshot(snapOf(it)) }.leftoverNotices()
        return SeatId.passengers.filter { s -> notices.any { it.text.contains("${s.label}에 무언가") } }.toSet()
    }

    /** 후보 (b): 운전석 문이 P 에서 열릴 때 벨트 풀림·착좌 유지인 동승 좌석. */
    private fun candidateB(frames: List<Frame>): Set<SeatId> {
        var prev = false
        val hit = mutableSetOf<SeatId>()
        for (f in frames) {
            val s = snapOf(f)
            val d = s.seat(driver).doorOpen == true
            if (d && !prev && s.isParked) hit += SeatId.passengers.filter { s.seat(it).occupied == true && s.seat(it).beltBuckled == false }
            prev = d
        }
        return hit
    }

    private data class Score(val tp: Int, val fp: Int, val fn: Int)

    private fun score(c: (List<Frame>) -> Set<SeatId>): Score {
        var tp = 0; var fp = 0; var fn = 0
        for ((_, truth, frames) in scenarios) {
            val alerted = x in c(frames)
            when {
                truth && alerted -> tp++
                truth -> fn++
                alerted -> fp++
            }
        }
        return Score(tp, fp, fn)
    }

    @Test
    fun `candidates are compared - (a) 는 오탐 1·누락 1, (b) 는 오탐 3·누락 0`() {
        val table = scenarios.joinToString("\n") { (name, truth, f) -> "$name | 정답 $truth | a ${x in candidateA(f)} | b ${x in candidateB(f)}" }
        println(table)
        assertEquals(table, Score(tp = 1, fp = 1, fn = 1), score(::candidateA))
        assertEquals(table, Score(tp = 2, fp = 3, fn = 0), score(::candidateB))
    }

    @Test
    fun `벨트를 맨 사람이 그대로 앉아 있으면 알리지 않는다`() {
        val frames = scenarios.first { it.first.startsWith("S3") }.third
        assertTrue(candidateA(frames).isEmpty())
    }

    @Test
    fun `가방이 남으면 운전자에게 한 번 알리고 그 좌석은 정리하지 않는다`() {
        val frames = scenarios.first { it.first.startsWith("S2") }.third
        val e = CabinRoutineEngine()
        val events = frames.flatMap { e.onSnapshot(snapOf(it)) }
        val speech = events.filterIsInstance<RoutineEvent.Speak>().map { it.text }
        assertTrue(speech.toString(), speech.contains("뒷좌석 왼쪽에 무언가 남아 있는 것 같아요. 확인해 주세요."))
        assertTrue(events.filterIsInstance<RoutineEvent.Execute>().none { it.seat == x })
        assertTrue(events.filterIsInstance<RoutineEvent.Alighted>().none { it.seat == x })
    }

    @Test
    fun `같은 좌석은 착좌가 꺼질 때까지 한 번만 알린다`() {
        val frames = Script().apply {
            at(0, x to sit(), p to sit()); at(10_000, x to unbuckle, p to standUp)
            at(11_000, x to open, p to open); at(12_000, p to close); at(12_500, x to close)
            at(13_000, x to open); at(13_500, x to close)           // 다시 여닫음 → 또 알리지 않음
            at(20_000)                                              // 하차 묶음 창이 닫힌 뒤 보고(남은 물건은 보고에 합쳐짐)
        }.frames
        val e = CabinRoutineEngine()
        val notices = frames.flatMap { e.onSnapshot(snapOf(it)) }.leftoverNotices()
        assertEquals(1, notices.size)
    }

    @Test
    fun `벨트 신호가 없으면 알리지 않고 기록만 한다`() {
        val frames = Script().apply {
            at(0, x to sit(), p to sit()); at(10_000, x to { it.copy(beltBuckled = null) }, p to standUp)
            at(11_000, x to open, p to open); at(12_000, p to close); at(12_500, x to close)
        }.frames
        val e = CabinRoutineEngine()
        val events = frames.flatMap { e.onSnapshot(snapOf(it)) }
        assertTrue(events.leftoverNotices().isEmpty())
        assertTrue(events.filterIsInstance<RoutineEvent.Log>().any { it.text.contains("벨트 신호 없음") })
    }

    @Test
    fun `P 가 아니면 음성 없이 알림만`() {
        val frames = Script().apply {
            at(0, x to sit(), p to sit()); at(10_000, x to unbuckle, p to standUp)
            gear = Gear.DRIVE
            at(11_000, x to open, p to open); at(12_000, p to close); at(12_500, x to close)
            at(20_000)
        }.frames
        val e = CabinRoutineEngine()
        val events = frames.flatMap { e.onSnapshot(snapOf(it)) }
        assertTrue(events.leftoverNotices().isNotEmpty())
        assertTrue(events.filterIsInstance<RoutineEvent.Speak>().none { it.text.contains("남아 있는") })
    }
}
