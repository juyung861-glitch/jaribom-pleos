package com.jaribom.app.core

/**
 * "자주 쓰는 물건" 칩(F12-3). 한 번 탭하면 기본 조건이 붙은 물건으로 등록되고, 다시 탭하면 빠진다.
 * 저장은 기존 물건 목록([CarryItem], ItemStore "items")을 그대로 쓴다 — 예전에 등록한 물건과 F11 요일 물건은 바뀌지 않는다.
 * 같은 이름(공백 무시)의 좌석 지정이 아닌 물건이 있으면 칩이 켜진 것으로 본다. 좌석 지정 물건은 '더 보기'에서만 다룬다.
 */
object QuickItems {
    data class Preset(val name: String, val condition: ItemCondition)

    /** 우산은 비 올 때만. 비 오는 날에는 날씨 문장이 우산을 말하므로 물건 목록에서는 빠진다(중복 제거 규칙 그대로). */
    val presets: List<Preset> = listOf(
        Preset("지갑", ItemCondition.ALWAYS),
        Preset("휴대폰", ItemCondition.ALWAYS),
        Preset("가방", ItemCondition.ALWAYS),
        Preset("물통", ItemCondition.ALWAYS),
        Preset("우산", ItemCondition.RAIN),
    )

    private fun key(s: String) = s.filterNot { it.isWhitespace() }

    private fun matches(item: CarryItem, p: Preset) = item.condition != ItemCondition.SEAT && key(item.name) == key(p.name)

    fun isOn(items: List<CarryItem>, p: Preset): Boolean = items.any { matches(it, p) }

    /** 켜져 있으면 같은 이름(좌석 지정 제외)을 모두 빼고, 꺼져 있으면 기본 조건으로 하나 넣는다. */
    fun toggle(items: List<CarryItem>, p: Preset, newId: Long): List<CarryItem> =
        if (isOn(items, p)) items.filterNot { matches(it, p) } else items + CarryItem(newId, p.name, p.condition)

    /** 칩으로 나타나지 않는 물건('더 보기' 목록): 칩 이름이 아니거나 좌석 지정 물건 */
    fun others(items: List<CarryItem>): List<CarryItem> = items.filterNot { i -> presets.any { matches(i, it) } }
}
