package com.chudad.game.logic

import com.chudad.game.model.Card
import com.chudad.game.model.CardGroup
import com.chudad.game.model.CardType
import com.chudad.game.model.Suit

/**
 * 普通模式 AI（启发式规则）—— 锄大D 版
 *
 * 锄大D 为 4 人各自为战，不存在阵营、队友、叫分等概念，
 * AI 采用「四人博弈下的通用直觉」：
 *
 *  【自由出牌】
 *   - 能一把出完则直接出完（获胜）
 *   - 倾向多出牌、先出小牌，保留 2 / A 等大牌后手
 *   - 不轻易首出四条、同花顺（留作拦截或收官）
 *   - 若某张单牌已是「未见最大牌」，优先首出以稳定控场
 *
 *  【跟牌】
 *   - 能一把出完则出完
 *   - 用「恰好压住」的最小代价牌跟牌，避免浪费大牌
 *   - 有对手接近出完（手牌 ≤2）时转为激进：允许动用 2 / 四带一·同花顺等强牌拦截
 *   - 不紧急时，若只能用 2 或四带一·同花顺等强牌才能压上，则选择过牌保存实力
 */
object AIDecision {

    /** 触发「紧急拦截」的对手手牌阈值：对手剩 ≤2 张时必须尽力压住 */
    private const val URGENT_OPPONENT_CARDS = 2

    /** 2 的点数（最大牌，不可轻易动用） */
    private const val RANK_TWO = 15

    /** 顺子固定长度（与 CardRuleEngine 一致） */
    private const val STRAIGHT_LEN = 5

    /** 首出打分中「出完该手后剩余手数」的权重（每多一手扣该值） */
    private const val HAND_COST = 20

    /**
     * 决策入口
     * @param hand 我方手牌
     * @param lastPlay 上一手牌（null 表示自由出牌）
     * @param opponentCardCounts 其余 3 家的手牌数
     * @param unseenCounts 我方未见各点数剩余张数（下标即 rank）
     * @param requireDiamond3 本手是否必须包含方块 3（整场第一局首手）
     * @param mustTopBig 下家是否已报单（顶大义务：出单张须为手中最大）
     * @return 决定出的牌组；null 表示过牌
     */
    fun decide(
        hand: List<Card>,
        lastPlay: CardGroup?,
        opponentCardCounts: IntArray,
        unseenCounts: IntArray,
        requireDiamond3: Boolean = false,
        mustTopBig: Boolean = false
    ): CardGroup? {
        if (hand.isEmpty()) return null

        var legal = CardRuleEngine.findAllValidPlays(hand, lastPlay)
        if (legal.isEmpty()) return null

        // 流程性约束：先把手牌候选收窄到规则允许的范围，避免策略判断被引擎兜底覆盖
        if (requireDiamond3) {
            val d3Id = hand.firstOrNull { it.rank == 3 && it.suit == Suit.DIAMOND }?.id
            if (d3Id != null) {
                val withD3 = legal.filter { g -> g.cards.any { it.id == d3Id } }
                legal = withD3.ifEmpty {
                    val d3 = hand.first { it.id == d3Id }
                    listOf(CardGroup(CardType.SINGLE, 3, 1, listOf(d3), d3.suit.order))
                }
            }
        }
        if (mustTopBig) {
            val max = hand.maxWithOrNull(compareBy({ it.rank }, { it.suit.order }))
            if (max != null) {
                val topOnly = legal.filter { g ->
                    g.type != CardType.SINGLE || g.cards.singleOrNull()?.id == max.id
                }
                if (topOnly.isNotEmpty()) legal = topOnly
            }
        }

        // 能一把出完 → 直接获胜
        legal.firstOrNull { it.cards.size == hand.size }?.let { return it }

        return if (lastPlay == null || lastPlay.type == CardType.INVALID) {
            freeLead(hand, legal, unseenCounts)
        } else {
            follow(hand, legal, opponentCardCounts)
        }
    }

    // ============================ 自由出牌 ============================

    private fun freeLead(
        hand: List<Card>,
        legal: List<CardGroup>,
        unseenCounts: IntArray
    ): CardGroup? {
        if (legal.isEmpty()) return null
        return legal.maxByOrNull { scoreLead(it, hand, unseenCounts) }
    }

    /**
     * 首出打分（越大越优先）：
     *  - 多出牌加分（尽快消耗手牌）
     *  - 主牌点数越小越优先（保存大牌后手）
     *  - 出完该手后「剩余手数」越少越好（避免为多出一张而拆散能一次走完的结构）
     *  - 动用 2 / 重牌 重罚；同花五作为五张中层牌型轻罚
     *  - 已「未见最大」的单牌额外加分（出出去无人能压，稳赚一手）
     */
    private fun scoreLead(g: CardGroup, hand: List<Card>, unseenCounts: IntArray): Int {
        var s = g.cards.size * 5
        s -= g.mainRank

        // 只判主体点数：A-2-3-4-5 顺子的主体是 5，虽含 2 却是最小顺子，不该被罚
        if (g.mainRank == RANK_TWO) s -= 25
        if (g.type == CardType.FLUSH) s -= 18
        if (g.type == CardType.FOUR_WITH_ONE) s -= 45
        if (g.type == CardType.STRAIGHT_FLUSH) s -= 45

        // 拆散牌型结构的惩罚（拆对子/三条/四条）
        s -= breakPenalty(g, hand)

        // 出完这手后剩余手数：只看本手出牌量会被「多出一张」骗到，
        // 例如 3,3,4,5,6,7,8 出顺子后剩两张散牌（3 手），出对子 33 后恰好剩一手顺子（2 手）。
        s -= estimateHands(remainingHand(hand, g)) * HAND_COST

        // 单张且已是未见最大牌 → 稳定控场
        if (g.type == CardType.SINGLE && isTopUnseenSingle(g, unseenCounts)) s += 30

        return s
    }

    /** 出掉 [played] 之后剩余的手牌（按 id 排除） */
    private fun remainingHand(hand: List<Card>, played: CardGroup): List<Card> {
        val ids = played.cards.map { it.id }.toSet()
        return hand.filter { it.id !in ids }
    }

    /**
     * 剩余手牌的最小手数估算（贪心下界）：
     *  - 同点数 ≥2 张合并为 1 手（对子/三条/四条）
     *  - 散牌中每凑成一个 5 连顺子，5 手并成 1 手
     * 不计 A-2-3-4-5 与同花类牌型，属保守估算，只用于比较不同出牌的相对优劣。
     */
    private fun estimateHands(cards: List<Card>): Int {
        if (cards.isEmpty()) return 0
        val byRank = cards.groupingBy { it.rank }.eachCount()
        var hands = byRank.size
        val singles = byRank.filterValues { it == 1 }.keys.sorted()
        var i = 0
        while (i < singles.size) {
            var j = i
            while (j + 1 < singles.size && singles[j + 1] == singles[j] + 1) j++
            hands -= ((j - i + 1) / STRAIGHT_LEN) * (STRAIGHT_LEN - 1)
            i = j + 1
        }
        return hands
    }

    /** 该单张是否已是「未见牌中最大的」：更高点数已全部现出 */
    private fun isTopUnseenSingle(g: CardGroup, unseenCounts: IntArray): Boolean {
        val card = g.cards.firstOrNull() ?: return false
        for (r in (card.rank + 1)..RANK_TWO) {
            if (unseenCounts.getOrElse(r) { 0 } > 0) return false
        }
        return true
    }

    // ============================ 跟牌 ============================

    private fun follow(
        hand: List<Card>,
        legal: List<CardGroup>,
        opponentCardCounts: IntArray
    ): CardGroup? {
        val minOppCards = opponentCardCounts.minOrNull() ?: 13
        val urgent = minOppCards <= URGENT_OPPONENT_CARDS

        val bombs = legal.filter {
            it.type == CardType.FOUR_WITH_ONE || it.type == CardType.STRAIGHT_FLUSH
        }
        val normal = legal.filter {
            it.type != CardType.FOUR_WITH_ONE && it.type != CardType.STRAIGHT_FLUSH
        }

        // 1) 优先用「主体不是 2」的最便宜牌跟牌
        val cheap = normal.filter { it.mainRank != RANK_TWO }
        if (cheap.isNotEmpty()) return cheap.minByOrNull { cost(it, hand) }

        // 2) 紧急（对手即将出完）：允许动用 2
        if (urgent && normal.isNotEmpty()) return normal.minByOrNull { cost(it, hand) }

        // 3) 紧急且只能靠重牌拦截：动用四带一/同花顺
        if (urgent && bombs.isNotEmpty()) return bombs.minByOrNull { cost(it, hand) }

        // 4) 不紧急：保存实力，过牌
        return null
    }

    /**
     * 跟牌代价（越小越优先）：
     *  - 主牌点数越大代价越高（尽量用最小牌恰好压住）
     *  - 同张数下跨牌型时按牌型层级加价（能出顺子就不出葫芦，保留更强牌型）
     *  - 主体点数为 2 重罚
     *  - 拆散对子/三条/四条结构有额外惩罚
     *  - 出完该手后剩余手数越少越优先（与首出同一口径）
     */
    private fun cost(g: CardGroup, hand: List<Card>): Int {
        var c = g.mainRank * 2 + typeSurcharge(g.type)
        if (g.mainRank == RANK_TWO) c += 30
        c += breakPenalty(g, hand)
        c += estimateHands(remainingHand(hand, g)) * HAND_COST / 2
        return c
    }

    /** 牌型层级加价：层级越高越舍不得用（仅五张牌型会真正跨型比较） */
    private fun typeSurcharge(type: CardType): Int = when (type) {
        CardType.FLUSH -> 10
        CardType.FULL_HOUSE -> 18
        CardType.FOUR_WITH_ONE -> 45
        CardType.STRAIGHT_FLUSH -> 50
        else -> 0          // 单张 / 对子 / 三条 / 顺子
    }

    /**
     * 拆牌惩罚：本次出牌只用了某点数的一部分，会破坏对子/三条/四条等结构
     */
    private fun breakPenalty(g: CardGroup, hand: List<Card>): Int {
        val handCounts = hand.groupingBy { it.rank }.eachCount()
        val usedCounts = g.cards.groupingBy { it.rank }.eachCount()
        var penalty = 0
        for ((rank, used) in usedCounts) {
            val total = handCounts[rank] ?: 0
            if (total > used && total >= 2) {
                // 拆对子/三条：手中该点数越多，拆散损失越大
                penalty += (total - 1) * 6
            }
        }
        return penalty
    }
}
