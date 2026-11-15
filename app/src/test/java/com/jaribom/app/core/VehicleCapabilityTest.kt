package com.jaribom.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 원칙(팀 결정 11-02): 운전 기능·도어 열림/닫힘(잠금·차일드락 포함)은 앱에 제어 능력이 없다.
 * 권한·명령·호출을 추가하지 않는다. 이 테스트들은 "막는 코드"가 아니라 "능력이 없음"을 고정한다.
 *  1) 명령: VehicleCommand 하위 타입은 허용 목록(창문·열선·통풍·창문 잠금)뿐. 창문 잠금은 창문 도메인(CONTROL_CAR_WINDOWS)이다.
 *  2) 권한: 매니페스트(소스·병합본)에 CONTROL_CAR_DOORS·CONTROL_CAR_DRIVE 가 없음
 *  3) 호출: Kotlin 소스에 도어·기어 도메인의 쓰기(set/lock/unlock/open/close) 호출이 없음
 * 새 능력이 필요해지면 이 테스트를 고치는 것부터 팀 결정이 필요하다.
 */
class VehicleCapabilityTest {

    /** 모듈 폴더(app). Gradle 단위 테스트는 보통 모듈 폴더에서 돈다. */
    private val moduleDir: File = listOf(File("."), File("app")).first { File(it, "src/main").isDirectory }

    // ── 1) 허용 목록 ────────────────────────────────────────────
    private val allowed = setOf(
        VehicleCommand.SetWindow::class.java,
        VehicleCommand.SetSeatHeater::class.java,
        VehicleCommand.SetSeatVentilation::class.java,
        VehicleCommand.SetWindowLock::class.java,
    )

    /** 새 하위 타입이 생기면 이 when 이 컴파일되지 않는다(else 없음) → 이 테스트부터 고쳐야 한다. */
    private fun allowedKind(c: VehicleCommand): String = when (c) {
        is VehicleCommand.SetWindow -> "창문"
        is VehicleCommand.SetSeatHeater -> "열선"
        is VehicleCommand.SetSeatVentilation -> "통풍"
        is VehicleCommand.SetWindowLock -> "창문 잠금"
    }

    @Test
    fun `차량 쓰기 명령은 창문·열선·통풍·창문 잠금 허용 목록뿐이다`() {
        val nested = VehicleCommand::class.java.declaredClasses
            .filter { it != VehicleCommand::class.java && VehicleCommand::class.java.isAssignableFrom(it) }.toSet()
        assertEquals(allowed, nested)
        // 중첩 클래스가 아닌 곳에서 VehicleCommand 를 상속한 타입도 찾는다(소스 검색).
        val subclassDecl = Regex(""":\s*VehicleCommand\s*\(\s*\)""")
        val decls = kotlinSources().flatMap { f -> subclassDecl.findAll(f.readText()).map { f.name } }.toList()
        assertEquals(listOf("Model.kt", "Model.kt", "Model.kt", "Model.kt"), decls)
        assertEquals(listOf("창문", "열선", "통풍", "창문 잠금"), listOf(
            VehicleCommand.SetWindow(SeatId.ROW_1_RIGHT, 0), VehicleCommand.SetSeatHeater(SeatId.ROW_1_RIGHT, 0),
            VehicleCommand.SetSeatVentilation(SeatId.ROW_1_RIGHT, 0), VehicleCommand.SetWindowLock(SeatId.ROW_2_LEFT, true),
        ).map(::allowedKind))
    }

    // ── 2) 매니페스트 ──────────────────────────────────────────
    private val forbiddenPermissions = listOf("CONTROL_CAR_DOORS", "CONTROL_CAR_DRIVE")

    @Test
    fun `매니페스트에 도어·운전 제어 권한이 없다`() {
        val sources = File(moduleDir, "src").listFiles().orEmpty().map { File(it, "AndroidManifest.xml") }.filter { it.isFile }
        assertTrue("src/main·src/pleos 매니페스트를 찾지 못함", sources.any { it.path.contains("main") } && sources.any { it.path.contains("pleos") })
        // 빌드한 적이 있으면 병합된 매니페스트(SDK AAR 이 넣는 권한 포함)도 본다.
        val merged = File(moduleDir, "build/intermediates/merged_manifests").walk().filter { it.name == "AndroidManifest.xml" }.toList()
        val usesPermission = Regex("""<uses-permission[\w-]*[^>]*android:name\s*=\s*"([^"]+)"""")
        for (f in sources + merged) {
            val names = usesPermission.findAll(f.readText()).map { it.groupValues[1] }.toList()
            val bad = names.filter { n -> forbiddenPermissions.any { n.endsWith(it) } }
            assertTrue("${f.path} 에 금지 권한: $bad", bad.isEmpty())
        }
    }

    @Test
    fun `pleos 매니페스트 차량 권한은 허용 목록뿐이고 쓰기는 창문·좌석 둘뿐이다`() {
        val manifest = File(moduleDir, "src/pleos/AndroidManifest.xml")
        val names = Regex("""<uses-permission[^>]*android:name\s*=\s*"([^"]+)"""").findAll(manifest.readText())
            .map { it.groupValues[1] }.filter { it.contains(".car.permission.") }.toSet()
        val writes = setOf("pleos.car.permission.CONTROL_CAR_WINDOWS", "pleos.car.permission.CONTROL_CAR_SEATS")
        val reads = setOf(
            "pleos.car.permission.CAR_SEATS", "pleos.car.permission.CAR_SAFETY", "pleos.car.permission.CAR_DOORS",
            "pleos.car.permission.CAR_WINDOWS", "pleos.car.permission.CAR_CLIMATE", "pleos.car.permission.CAR_DRIVE",
            "pleos.car.permission.TTS_SERVICE",
            // 6차 F6 읽기 전용 추가분: 속도, 기어(파워트레인)
            "android.car.permission.CAR_SPEED", "android.car.permission.CAR_POWERTRAIN",
        )
        val extra = names - writes - reads
        assertTrue("허용 목록에 없는 차량 권한: $extra", extra.isEmpty())
        assertEquals(writes, names.filter { it.contains("CONTROL_CAR") }.toSet())
    }

    // ── 3) 코드 검색 ───────────────────────────────────────────
    private val forbiddenCalls = listOf(
        Regex("""get(Door|Gear)\(\)\s*\.\s*(set|lock|unlock|open|close)\w*"""),           // vehicle.getDoor().set…, getGear().set…
        Regex("""\b(door|gear)\s*\.\s*(set|lock|unlock|open|close)\w*\s*\("""),           // val door = vehicle.getDoor(); door.set…(
        Regex("""\b(set|lock|unlock|open|close)\w*(Door|Gear|ChildLock)\w*\s*\("""),     // setDoor(…), setDoorLock(…), setGearSelection(…)
        Regex("""CONTROL_CAR_(DOORS|DRIVE)"""),
    )

    private fun kotlinSources(): Sequence<File> = File(moduleDir, "src").walk()
        .filter { it.isFile && it.extension == "kt" }
        .filterNot { it.path.replace('\\', '/').let { p -> "/src/test/" in p || "/src/androidTest/" in p } }

    @Test
    fun `소스에 도어·기어 쓰기 호출이 없다`() {
        // 검출식이 실제로 잡는지 먼저 확인(검색이 헛돌지 않게)
        for (sample in listOf("vehicle.getDoor().setDoorLock(a, true)", "vehicle.getGear().setGearSelection(g)", "door.setChildLock(a)", "fake.setDoor(s, true)")) {
            assertTrue(sample, forbiddenCalls.any { it.containsMatchIn(sample) })
        }
        val files = kotlinSources().toList()
        assertTrue("검색한 소스가 너무 적음: ${files.size}", files.size >= 20)
        val hits = files.flatMap { f ->
            f.readLines().mapIndexedNotNull { i, line ->
                val code = line.substringBefore("//")
                if (forbiddenCalls.any { it.containsMatchIn(code) }) "${f.name}:${i + 1}: ${line.trim()}" else null
            }
        }
        assertTrue("도어·기어 쓰기 호출:\n" + hits.joinToString("\n"), hits.isEmpty())
    }
}
