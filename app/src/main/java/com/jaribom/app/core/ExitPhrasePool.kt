package com.jaribom.app.core

import kotlin.random.Random

/**
 * 하차 한마디 문장 풀(7차 F11-1). 같은 문장이 매번 나오면 질리므로 상황별로 3~5문장씩 두고 돌려 쓴다.
 * 말투는 둘뿐이다: 아이(반말) [Child], 성인·택시(존댓말) [Polite]. 택시 모드는 항상 [Polite] 풀만 쓴다(반말 풀을 아예 참조하지 않는다).
 * 각 풀의 0번은 F11 이전부터 쓰던 문장이다([PhrasePicker.LEGACY] 가 0번만 고르므로 예전 동작과 테스트가 그대로다).
 * `{items}` 자리에는 [KoreanPostposition] 으로 이은 물건 나열이 들어간다.
 */
object ExitPhrasePool {
    const val ITEMS_SLOT = "{items}"

    /** 상황 하나의 문장 풀. [id] 는 "직전 문장" 기록의 키다. */
    class Pool(val id: String, val sentences: List<String>) {
        init { require(sentences.size in 3..5) { "$id: 풀은 3~5문장" } }
    }

    object Child {
        /** 물건 없음 */
        val base = Pool("child.base", listOf(
            "두고 가는 거 없지? 조심히 내려.",
            "놓고 가는 건 없는지 한번 봐. 조심히 내려.",
            "빠뜨린 거 없나 확인했지? 조심해서 내려.",
            "잊은 거 없는지 둘러봐. 천천히 내려.",
        ))
        /** 물건 있음 */
        val items = Pool("child.items", listOf(
            "$ITEMS_SLOT 챙겼지? 조심히 내려.",
            "$ITEMS_SLOT 잊지 말고 챙겨. 조심히 내려.",
            "내리기 전에 $ITEMS_SLOT 확인해 봐. 조심해서 내려.",
            "$ITEMS_SLOT 가방에 있나 볼까? 천천히 내려.",
        ))
        val rainNow = Pool("child.rainNow", listOf(
            "밖에 비 와. 우산 챙겨.",
            "비가 오고 있어. 우산 꼭 챙겨.",
            "지금 비 와! 우산 펴고 내려.",
            "밖에 비 오니까 우산 잊지 마.",
        ))
        val rainSoon = Pool("child.rainSoon", listOf(
            "곧 비 온대. 우산 챙겨.",
            "조금 있으면 비가 온대. 우산 챙기자.",
            "한 시간 안에 비 올 거래. 우산 가져가.",
            "비 소식이 있어. 우산 꼭 챙겨.",
        ))
        val cold = Pool("child.cold", listOf(
            "밖이 추워. 따뜻하게 입어.",
            "바깥이 많이 추워. 옷 단단히 여며.",
            "추우니까 옷 잘 입고 내려.",
        ))
    }

    object Polite {
        val base = Pool("polite.base", listOf(
            "두고 가시는 물건은 없나요?",
            "놓고 내리시는 물건은 없으신가요?",
            "빠뜨리신 물건이 없는지 한 번 확인해 주세요.",
            "내리시기 전에 두고 가시는 것이 없는지 살펴봐 주세요.",
        ))
        val items = Pool("polite.items", listOf(
            "$ITEMS_SLOT 챙기셨어요?",
            "$ITEMS_SLOT 잊지 말고 챙겨 주세요.",
            "내리시기 전에 $ITEMS_SLOT 확인해 주세요.",
            "$ITEMS_SLOT 가지고 계신지 봐 주세요.",
        ))
        val rainNow = Pool("polite.rainNow", listOf(
            "밖에 비가 와요. 우산 챙기세요.",
            "지금 비가 오고 있어요. 우산 꼭 챙겨 주세요.",
            "밖에 비가 내려요. 우산을 펴고 내려 주세요.",
            "비가 오니 우산 잊지 마세요.",
        ))
        val rainSoon = Pool("polite.rainSoon", listOf(
            "한 시간 안에 비 소식이 있어요. 우산 챙기세요.",
            "곧 비가 온다고 해요. 우산 챙겨 주세요.",
            "한 시간 안에 비가 올 수 있어요. 우산 가져가세요.",
            "비 소식이 있어요. 우산 꼭 챙기세요.",
        ))
        val cold = Pool("polite.cold", listOf(
            "밖이 많이 추워요. 따뜻하게 가세요.",
            "바깥이 꽤 추워요. 옷을 든든히 여미세요.",
            "추우니 따뜻하게 입고 내리세요.",
        ))
    }

    /** 반말 풀 전부(택시 모드 테스트가 이 문장이 하나도 나오지 않는지 본다) */
    val allChild: List<Pool> = listOf(Child.base, Child.items, Child.rainNow, Child.rainSoon, Child.cold)
    val allPolite: List<Pool> = listOf(Polite.base, Polite.items, Polite.rainNow, Polite.rainSoon, Polite.cold)

    /** 템플릿의 `{items}` 를 물건 나열로 바꾼다. */
    fun fill(template: String, itemsText: String): String = template.replace(ITEMS_SLOT, itemsText)
}

/**
 * 풀에서 문장을 고른다. 같은 좌석·같은 풀에서 직전에 쓴 문장은 다시 고르지 않는다(풀이 2문장 이상일 때).
 * [random] 을 주입하면 시드로 결과를 고정할 수 있다. null 이면 항상 0번([LEGACY], 예전 동작).
 * 마지막으로 쓴 번호는 메모리에만 두고 저장하지 않는다.
 */
class PhrasePicker(private val random: Random? = null) {
    private val last = HashMap<String, Int>()

    fun pick(seat: SeatId?, pool: ExitPhrasePool.Pool): String {
        val r = random ?: return pool.sentences.first()
        val key = "${seat?.name}|${pool.id}"
        val prev = last[key]
        val candidates = pool.sentences.indices.filter { it != prev }
        val idx = candidates[r.nextInt(candidates.size)]
        last[key] = idx
        return pool.sentences[idx]
    }

    companion object {
        /** 항상 0번 문장. 엔진의 기본값이라 F11 이전 테스트가 그대로 통과한다. 실제 앱은 컨트롤러가 무작위 선택기를 넣는다. */
        val LEGACY = PhrasePicker(null)
    }
}
