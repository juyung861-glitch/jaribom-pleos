package com.jaribom.app.core

/**
 * 로그에서 아이 이름을 가린다(7차 F11-3). 이름은 기기 안 설정에만 있고 통계·logcat·알림 기록에 남지 않는다.
 * logcat 에는 이름 대신 `name=set`/`name=unset` 만 남긴다([marker]). 음성 문장에 호격으로 이름이 섞여 로그에 찍힐 수 있는 곳이
 * 많아(안내 버림·음성 실패 등) [JbLog] 가 모든 로그를 이 함수에 통과시킨다.
 * 이름은 호격 조사가 붙은 형태("지민아")도 이름 부분이 포함되므로 이름 글자열을 가리면 함께 가려진다.
 */
object LogRedactor {
    const val MASK = "[이름]"

    @Volatile var names: Set<String> = emptySet()
        private set

    /** 지금 설정된 이름들로 바꾼다. 빈 이름은 무시한다. */
    fun setNames(all: Collection<String>) { names = all.map { it.trim() }.filter { it.isNotEmpty() }.toSet() }

    fun redact(msg: String): String {
        var out = msg
        // 긴 이름부터 가려야 짧은 이름이 긴 이름의 일부일 때 남는 글자가 없다.
        for (n in names.sortedByDescending { it.length }) out = out.replace(n, MASK)
        return out
    }

    /** logcat 에 남기는 이름 상태 표시 */
    fun marker(name: String?): String = if (name.isNullOrBlank()) "name=unset" else "name=set"
}
