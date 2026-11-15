package com.jaribom.app.core

/**
 * 한국어 조사 처리(7차 F10-6). 받침 여부로 이랑/랑, 은/는, 이/가, 을/를, 과/와를 고른다. 순수 함수라 JVM 테스트로 검증한다.
 * 한글 음절로 끝나지 않는 이름(영문·숫자)은 받침을 알 수 없다([hasBatchim] = null). 그런 이름에는 조사를 붙이지 않고
 * 나열을 쉼표형으로 바꿔 어색한 조사가 나가지 않게 한다.
 */
object KoreanPostposition {

    /**
     * 마지막 한글 음절의 받침 여부. 끝의 공백·괄호 같은 기호는 건너뛴다. 마지막 글자가 한글 음절이 아니면(영문·숫자 등) null.
     */
    fun hasBatchim(word: String): Boolean? {
        val c = word.trimEnd { !it.isLetterOrDigit() }.lastOrNull() ?: return null
        if (c !in '가'..'힣') return null
        return (c - '가') % 28 != 0
    }

    private fun attach(word: String, withBatchim: String, without: String): String =
        when (hasBatchim(word)) {
            true -> word + withBatchim
            false -> word + without
            null -> word
        }

    fun iRang(word: String) = attach(word, "이랑", "랑")
    fun eunNeun(word: String) = attach(word, "은", "는")
    fun iGa(word: String) = attach(word, "이", "가")
    fun eulReul(word: String) = attach(word, "을", "를")
    fun gwaWa(word: String) = attach(word, "과", "와")

    /**
     * 이름을 부르는 말(F11-3): 받침 있으면 "아", 없으면 "야"(지민아, 수아야). 한글로 끝나지 않으면 이름만 쓰고 조사는 붙이지 않는다. 빈 이름은 빈 문자열.
     * 받침 판정은 [hasBatchim] 과 같다(끝의 공백·기호는 건너뜀).
     */
    fun vocative(name: String): String {
        val n = name.trim()
        if (n.isEmpty()) return ""
        return when (hasBatchim(n)) {
            true -> n + "아"
            false -> n + "야"
            null -> n
        }
    }

    /** 아이 말투 나열: "지갑이랑 우산", "지갑, 우산이랑 마스크". 조사를 붙일 이름의 받침을 모르면 전부 쉼표형. */
    fun joinChild(names: List<String>): String = join(names, ::iRang)

    /** 존댓말 나열: "지갑과 우산", "지갑, 우산과 마스크". 조사를 붙일 이름의 받침을 모르면 전부 쉼표형. */
    fun joinPolite(names: List<String>): String = join(names, ::gwaWa)

    /** 마지막 이름 앞의 이름에 조사를 붙이고, 그 앞은 쉼표로 잇는다. */
    private fun join(names: List<String>, particle: (String) -> String): String {
        if (names.size <= 1) return names.joinToString("")
        val beforeLast = names[names.size - 2]
        if (hasBatchim(beforeLast) == null) return names.joinToString(", ")
        val head = names.dropLast(2)
        val tail = particle(beforeLast) + " " + names.last()
        return (head + tail).joinToString(", ")
    }
}
