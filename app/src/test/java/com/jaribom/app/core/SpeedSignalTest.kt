package com.jaribom.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F10-2: 속도 권한(CAR_SPEED)은 런타임 요청으로 받는다. 거부·미지원은 설정 문구로 구분하고, 속도가 없으면 기어로 판정한다.
 * 탑승 인사는 기어 P이거나 그 좌석 문이 열려 있을 때만 말한다.
 */
class SpeedSignalTest {

    private fun snap(gear: Gear, speed: Float?, t: Long = 10_000, slowSince: Long? = null) =
        VehicleSnapshot(gear = gear, speedMps = speed, slowSinceMs = slowSince, timestampMs = t)

    @Test
    fun `거부 - 설정 문구가 나오고 속도는 null이라 기어로 판정한다`() {
        assertEquals("속도 권한 없음: 기어로만 판정해요", SpeedSignalText.line(SpeedSignal.PERMISSION_DENIED))
        assertTrue(SpeedSignalText.canRequest(SpeedSignal.PERMISSION_DENIED))
        assertTrue(VehicleMotion.isStopped(snap(Gear.PARK, null)))
        assertFalse(VehicleMotion.isStopped(snap(Gear.DRIVE, null)))
    }

    @Test
    fun `허용과 값이 있으면 속도로 판정한다 - 기어와 무관`() {
        assertNull(SpeedSignalText.line(SpeedSignal.OK))
        assertTrue(VehicleMotion.isStopped(snap(Gear.DRIVE, 0.2f, t = 10_000, slowSince = 8_000)))     // D 여도 서 있으면 정차
        assertFalse(VehicleMotion.isStopped(snap(Gear.PARK, 3f)))                                       // P 여도 움직이면 정차 아님
        assertTrue(VehicleMotion.isDriving(snap(Gear.PARK, 3f)))
    }

    @Test
    fun `허용했는데 속성이 미지원이면 거부와 다른 문구다`() {
        val unsupported = SpeedSignalText.line(SpeedSignal.UNSUPPORTED)
        assertEquals("이 차는 속도 신호가 없어요", unsupported)
        assertNotEquals(SpeedSignalText.line(SpeedSignal.PERMISSION_DENIED), unsupported)
        assertFalse("미지원이면 권한 허용 버튼이 필요 없다", SpeedSignalText.canRequest(SpeedSignal.UNSUPPORTED))
        assertNull(SpeedSignalText.line(SpeedSignal.UNKNOWN))
    }

    // ── 탑승 인사 ──
    private val seat = SeatId.ROW_1_RIGHT
    private fun boardSpeech(gear: Gear): List<String> {
        val e = CabinRoutineEngine()
        val base = SeatState(doorOpen = false)
        fun s(t: Long, st: SeatState) = VehicleSnapshot(seats = SeatId.entries.associateWith { SeatState() } + (seat to st), gear = gear, timestampMs = t)
        val out = mutableListOf<RoutineEvent>()
        out += e.onSnapshot(s(0, base.copy(occupied = true, beltBuckled = false)))
        out += e.onSnapshot(s(1_000, base.copy(occupied = true, beltBuckled = false, doorOpen = true)))
        out += e.onSnapshot(s(2_000, base.copy(occupied = true, beltBuckled = false)))
        out += e.onSnapshot(s(2_500, base.copy(occupied = true, beltBuckled = true)))   // 벨트 체결 = 즉시 확정
        return out.filterIsInstance<RoutineEvent.Speak>().map { it.text }.filter { "탑승을 확인했어요" in it }
    }

    @Test
    fun `탑승 인사 - D 이고 문이 닫혀 있으면 음성이 없다`() {
        assertTrue(boardSpeech(Gear.DRIVE).isEmpty())
    }

    @Test
    fun `탑승 인사 - P 이면 말한다`() {
        // 탑승 확정은 문이 닫힌 뒤에만 되므로 "D + 그 좌석 문 열림" 조합은 실제로 나오지 않는다(조건은 코드에 그대로 두었다).
        assertEquals(1, boardSpeech(Gear.PARK).size)
    }
}
