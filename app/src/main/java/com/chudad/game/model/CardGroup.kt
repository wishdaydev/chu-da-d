package com.chudad.game.model

/**
 * 牌型枚举（港式锄大D / Big Two）
 *
 * 牌型层级由低到高：
 *   单张 < 对子 < 三条 < 顺子 < 葫芦 < 四带一 < 同花顺
 * level 即层级，用于跨牌型比较。
 */
enum class CardType(val level: Int) {
    INVALID(-1),        // 无效牌型
    SINGLE(0),          // 单张（1 张）
    PAIR(1),            // 对子（2 张同点）
    TRIPLE(2),          // 三条（3 张同点）
    STRAIGHT(3),        // 顺子（5+ 张连续，不含 2）
    FULL_HOUSE(4),      // 葫芦 / 三带二（3+2，共 5 张）
    FOUR_WITH_ONE(5),   // 四带一（4 张同点 + 1 张散牌，共 5 张）
    STRAIGHT_FLUSH(6)   // 同花顺（5+ 张连续且同花色，最大牌型）
}

/**
 * 一组牌的描述信息
 * @param type 牌型
 * @param mainRank 主牌点数（比较大小用：
 *                 单张/对子/三条取该点数；顺子/同花顺取最大牌；葫芦取三条点数；四带一取四条点数）
 * @param length 连续长度（顺子/同花顺为张数，其余固定为 1）
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
