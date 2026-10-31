package com.chudad.game.model

/**
 * 牌型枚举（港式锄大D / Big Two）
 *
 * 五张牌型层级由低到高：
 *   顺子 < 同花五 < 葫芦 < 四带一 < 同花顺
 * 单张 / 对子 / 三条 只能与同牌型、同张数比较。
 * level 仅用于五张牌型的跨型比较。
 */
enum class CardType(val level: Int) {
    INVALID(-1),        // 无效牌型
    SINGLE(0),          // 单张（1 张）
    PAIR(1),            // 对子（2 张同点）
    TRIPLE(2),          // 三条（3 张同点）
    STRAIGHT(3),        // 顺子（5 张连续，花色不全同；A 可作 1 组成 A-2-3-4-5）
    FLUSH(4),           // 同花五（5 张同花色、不连续）
    FULL_HOUSE(5),      // 葫芦 / 三带二（3+2）
    FOUR_WITH_ONE(6),   // 四带一 / 铁支（4 张同点 + 1 张散牌）
    STRAIGHT_FLUSH(7)   // 同花顺（5 张连续且同花色，最大牌型）
}

/**
 * 一组牌的描述信息
 * @param type 牌型
 * @param mainRank 主牌点数（比较大小用：
 *                 单张/对子/三条取该点数；顺子/同花顺/同花五取最大牌；
 *                 葫芦取三条点数；四带一取四条点数）
 * @param length 五张牌型为 5，其余固定为 1
 * @param cards 包含的所有牌
 * @param topSuitOrder 决定性牌的花色序（同点数比较用：♦0 < ♣1 < ♥2 < ♠3）
 */
data class CardGroup(
    val type: CardType,
    val mainRank: Int,
    val length: Int = 1,
    val cards: List<Card> = emptyList(),
    val topSuitOrder: Int = 0
) {
    /** 牌的总数 */
    val size: Int get() = cards.size

    companion object {
        /** 无效牌组 */
        val INVALID = CardGroup(CardType.INVALID, 0)
    }
}
