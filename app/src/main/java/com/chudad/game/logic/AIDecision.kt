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
 *   - 下家报单（≤2 张）或任意对手只剩 1 张时转为激进：允许动用 2 / 四带一·同花顺等强牌拦截
 *   - 不紧急时，若只能用 2 或四带一·同花顺等强牌才能压上，则选择过牌保存实力
 */
object AIDecision {

    /** 触发「紧急拦截」的对手手牌阈值：对手剩 ≤2 张时必须尽力压住 */
    private const val URGENT_OPPONENT_CARDS = 2

    /** 2 的点数（最大牌，不可轻易动用） */
    private const val RANK_TWO = 15

    /** 顺子固定长度（与 CardRuleEngine 一致） */
    private const val STRAIGHT_LEN = 5

    /** A-2-3-4-5 轮式顺子的点数序列（14=A、15=2） */
    private val A2345 = listOf(3, 4, 5, 14, 15)

    /** 首出打分中「出完该手后剩余手数」的权重（每多一手扣该值） */
    private const val HAND_COST = 20

    /**
     * 拆组惩罚的单价：每个被拆散的点数按 `total-1` 倍计入代价。
     *
     * 旧值 6 太小，跟牌时「拆对子出小牌」与「出大 8 点的散牌」恰好打平（点数按 ×2 计价，
     * 拆组只罚 6、手数差 10，合计 16 = 8 点），于是 对6 + 散A 面对单 5 时 minBy 取到了
     * 先出现的 6，把对子拆成两张散牌。
     *
     * 提到 18 后：对6 + 散A 场景下出 6 是 12+18+20=50、出 A 是 28+0+10=38，散牌胜出。
     * 3200 局同场对抗实测（4 家全启发式、新旧版对调座位、4 个 seed）新版两座合计 49.0%，
     * 相对 50% 为 -1.0pp / 95%CI ±3.0pp —— 在噪声内，换到「不无故拆对子」的手感。
     */
    private const val BREAK_UNIT = 18

    /**
     * 决策入口
     * @param hand 我方手牌
     * @param lastPlay 上一手牌（null 表示自由出牌）
     * @param opponentCardCounts 其余 3 家的手牌数，**下标 0 固定为下家**，即按出牌顺序
     *        `(myIndex+1) % 4`、`(myIndex+2) % 4`、`(myIndex+3) % 4` 排列（不是座位号升序，
     *        也不是屏幕方位）。[follow] 用 [0] 判断「下家报单」，[freeLead] 用 [0] 当 nextCards。
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
            freeLead(hand, legal, unseenCounts, opponentCardCounts.getOrElse(0) { 13 })
        } else {
            follow(hand, legal, opponentCardCounts)
        }
    }

    // ============================ 自由出牌 ============================

    private fun freeLead(
        hand: List<Card>,
        legal: List<CardGroup>,
        unseenCounts: IntArray,
        nextCards: Int
    ): CardGroup? {
        if (legal.isEmpty()) return null
        return legal.maxByOrNull { scoreLead(it, hand, unseenCounts, nextCards) }
    }

    /**
     * 首出打分（越大越优先）：
     *  - 多出牌加分（尽快消耗手牌）
     *  - 主牌点数越小越优先（保存大牌后手）
     *  - 出完该手后「剩余手数」越少越好（避免为多出一张而拆散能一次走完的结构）
     *  - 动用 2 / 重牌 重罚；同花五作为五张中层牌型轻罚
     *  - 已「未见最大」的单牌额外加分（出出去无人能压，稳赚一手）
     *  - 下家报单（1~2 张）时压低单张，避免送对手直接出完
     */
    private fun scoreLead(g: CardGroup, hand: List<Card>, unseenCounts: IntArray, nextCards: Int): Int {
        var s = g.cards.size * 5
        s -= g.mainRank

        // 只判主体点数：A-2-3-4-5 顺子的主体是 5，虽含 2 却是最小顺子，不该被罚
        if (g.mainRank == RANK_TWO) s -= 25
        if (g.type == CardType.FLUSH) s -= 18
        if (g.type == CardType.FOUR_WITH_ONE) s -= 45
        if (g.type == CardType.STRAIGHT_FLUSH) s -= 45

        // 下家报单的保护：下家只剩 1 张时出单张＝把出完机会直接送给他（他一压就赢），
        // 必须优先出对子/三条/顺子等「他单张压不动」的牌型；若只能出单张，则出点数最大的那张。
        if (nextCards == 1 && g.type == CardType.SINGLE) {
            s += g.mainRank * 3 - 70
        } else if (nextCards == 2 && g.type == CardType.SINGLE) {
            // 下家 2 张很可能是一对：单张同样容易被接走，轻度回避
            s -= 25
        }

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
     *  - 先抽顺子（含 A-2-3-4-5 轮式）：5 张并成 1 手。允许从对子/三条里拆 1 张凑顺，
     *    但优先选「不需要拆桶」的窗口、同等条件取点数更大的窗口（大牌先消耗、小牌留作顺子成本低）
     *  - 剩余按同点数合并：四条 / 三条 / 对子各 1 手
     *  - 葫芦（3+2）、四带一（4+1）各并成 1 手
     * 不计同花与同花顺，属保守估算，只用于比较不同出牌的相对优劣。
     *
     * 旧实现只从「单张点数」里找顺子，导致 5,6,7,8,9,9 这种「对子可拆出一张成顺」的手牌
     * 被估成 5 手（实际 2 手），首出/跟牌打分因此系统性偏向错误方案。
     */
    private fun estimateHands(cards: List<Card>): Int {
        if (cards.isEmpty()) return 0
        val cnt = IntArray(16)
        for (c in cards) cnt[c.rank]++

        var hands = 0
        // 1) 反复抽顺子（每抽一个 5 连，手数 +1）
        while (true) {
            val window = pickStraight(cnt) ?: break
            for (r in window) cnt[r]--
            hands++
        }

        // 2) 剩余按同点数归并
        var fours = 0
        var triples = 0
        var pairs = 0
        var singles = 0
        for (r in 3..15) {
            when (cnt[r]) {
                4 -> fours++
                3 -> triples++
                2 -> pairs++
                1 -> singles++
            }
        }
        hands += fours
        // 四带一：4+1 并成 1 手（四条已计 1 手；被带走的单张不再单独计入）
        val fourWithOne = minOf(fours, singles)
        singles -= fourWithOne
        // 葫芦：3+2 并成 1 手（三条与对子此前都未计入，合并后计 1 手）
        val fullHouse = minOf(triples, pairs)
        triples -= fullHouse
        pairs -= fullHouse
        hands += fullHouse
        hands += triples + pairs + singles
        return hands
    }

    /**
     * 从点数计数里挑一个可出的顺子窗口：
     * 优先「不拆对子/三条」（窗口内各点数都只用 1 张），同等拆桶代价下取点数更大的窗口
     * （把 4-8 留给顺子比 3-7 更划算，小牌单独出更容易被放走）。
     */
    private fun pickStraight(cnt: IntArray): IntArray? {
        var best: IntArray? = null
        var bestCost = Int.MAX_VALUE
        for (start in 10 downTo 3) {
            var ok = true
            var cost = 0
            for (r in start until start + STRAIGHT_LEN) {
                if (cnt[r] <= 0) { ok = false; break }
                if (cnt[r] > 1) cost++
            }
            if (!ok) continue
            if (cost < bestCost) {
                bestCost = cost
                best = IntArray(STRAIGHT_LEN) { start + it }
                if (cost == 0) return best   // 无需拆桶即最优，提前返回（保留最大窗口）
            }
        }
        // A-2-3-4-5 轮式：是最小顺子，只在「比常规窗口更省」时采纳，避免抢走 4-8 之类更实用的窗口
        if (A2345.all { cnt[it] > 0 }) {
            val cost = A2345.count { cnt[it] > 1 }
            if (cost < bestCost) best = A2345.toIntArray()
        }
        return best
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
        // 紧迫性只认「下家近出完」与「任何一家只剩 1 张」：
        //  - 下家 ≤2 张时我若过牌，等于把上手机会直接让给他；
        //  - 其他对手剩 2 张时贸然动用 2/重牌去压往往是白送大牌（他这一手未必轮得到出），
        //    但只剩 1 张就不同——他一旦拿到牌权就直接赢，必须尽力拦。
        // 旧实现用「全场最小手牌数 ≤2」触发，等于任意一家接近出完都激进，浪费大牌。
        val nextCards = opponentCardCounts.getOrElse(0) { 13 }
        val urgent = nextCards <= URGENT_OPPONENT_CARDS || opponentCardCounts.any { it <= 1 }

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
                penalty += (total - 1) * BREAK_UNIT
            }
        }
        return penalty
    }
}
