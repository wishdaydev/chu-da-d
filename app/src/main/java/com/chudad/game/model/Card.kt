package com.chudad.game.model

/**
 * 扑克牌花色枚举
 * 锄大D 使用 52 张牌（四种花色 × 13 个点数），无大小王。
 *
 * 花色序（同点数单张比较用，由小到大）：♦方块 < ♣梅花 < ♥红桃 < ♠黑桃
 */
enum class Suit(val order: Int) {
    DIAMOND(0), // 方块
    CLUB(1),    // 梅花
    HEART(2),   // 红桃
    SPADE(3)    // 黑桃
}

/**
 * 单张扑克牌数据类
 * @param suit 花色
 * @param rank 点数（3=3, 4=4, ..., 10=10, 11=J, 12=Q, 13=K, 14=A, 15=2）
 *             2(15) 为最大牌；锄大D 无王
 * @param id   唯一标识（0~51），用于区分 52 张牌
 */
data class Card(
    val suit: Suit,
    val rank: Int,
    val id: Int
) : Comparable<Card> {

    /** 获取牌面显示文字 */
    val displayText: String
        get() = when (rank) {
            11 -> "J"
            12 -> "Q"
            13 -> "K"
            14 -> "A"
            15 -> "2"
            else -> rank.toString()
        }

    /** 获取花色符号 */
    val suitSymbol: String
        get() = when (suit) {
            Suit.SPADE -> "♠"
            Suit.HEART -> "♥"
            Suit.CLUB -> "♣"
            Suit.DIAMOND -> "♦"
        }

    /** 是否为红色花色 */
    val isRed: Boolean
        get() = suit == Suit.HEART || suit == Suit.DIAMOND

    /** 花色序：同点数比较大小用 */
    val suitOrder: Int get() = suit.order

    /** 先比点数，点数相同再比花色 */
    override fun compareTo(other: Card): Int {
        val r = this.rank.compareTo(other.rank)
        return if (r != 0) r else this.suit.order.compareTo(other.suit.order)
    }

    override fun toString(): String = "$suitSymbol$displayText"
}

/**
 * 创建一副 52 张扑克牌（无王）
 * id 取牌在整副牌中的序号（0~51），保证唯一
 */
fun createDeck(): MutableList<Card> {
    val deck = mutableListOf<Card>()
    for ((index, suit) in listOf(Suit.DIAMOND, Suit.CLUB, Suit.HEART, Suit.SPADE).withIndex()) {
        for (rank in 3..15) {
            deck.add(Card(suit, rank, index * 13 + (rank - 3)))
        }
    }
    return deck
}
