package com.chudad.game.logic

import com.chudad.game.model.Card
import com.chudad.game.model.CardGroup
import com.chudad.game.model.CardType
import com.chudad.game.model.Suit

/**
 * 牌型规则引擎（港式锄大D / Big Two）
 *
 * 一、牌数与点数
 *   - 52 张牌（4 花色 × 13 点数），无大小王；4 人各 13 张
 *   - 点数序：3 < 4 < … < 10 < J < Q < K < A < 2（2 最大）
 *   - 花色序（同点数比较）：♦方块(0) < ♣梅花(1) < ♥红桃(2) < ♠黑桃(3)
 *
 * 二、牌型
 *   单张(1) / 对子(2) / 三条(3)
 *   五张牌型（层级低 → 高）：顺子 < 同花五 < 葫芦 < 四带一 < 同花顺
 *   - 顺子：5 张连续，A 可作 1（A-2-3-4-5，最小）或作最大（10-J-Q-K-A）
 *   - 同花五：5 张同花色但点数不连续
 *   - 葫芦：三条 + 对子
 *   - 四带一：四条 + 1 张散牌
 *   - 同花顺：5 张连续且同花色
 *
 * 三、跟牌规则
 *   - 张数必须相同；单张/对子/三条须同牌型且更大
 *   - 五张牌型按上表层级跨型比较；同型只比主体最大牌点，不再比花色
 */
object CardRuleEngine {

    /** 顺子 / 同花顺固定张数 */
    const val STRAIGHT_LEN = 5

    /** A 的点数 */
    private const val RANK_A = 14

    /** 2 的点数（顺子中仅可与 A-3-4-5 组成最小顺子） */
    private const val RANK_TWO = 15

    // ============================== 识别 ==============================

    /**
     * 识别一组牌的牌型
     * @param cards 待识别的牌列表
     * @return 识别出的牌组；不合法返回 CardGroup.INVALID
     */
    fun identify(cards: List<Card>): CardGroup {
        if (cards.isEmpty()) return CardGroup.INVALID

        val sorted = cards.sortedWith(compareBy({ it.rank }, { it.suit.order }))
        val n = sorted.size
        val rankCounts = sorted.groupingBy { it.rank }.eachCount()
        val counts = rankCounts.values.sorted()

        return when {
            // 单张
            n == 1 -> group(CardType.SINGLE, sorted[0].rank, sorted)

            // 对子：2 张同点
            n == 2 && counts == listOf(2) -> group(CardType.PAIR, sorted[0].rank, sorted)

            // 三条：3 张同点
            n == 3 && counts == listOf(3) -> group(CardType.TRIPLE, sorted[0].rank, sorted)

            // 五张且点数互不相同：顺子 / 同花顺 / 同花五
            n == STRAIGHT_LEN && rankCounts.size == n -> {
                val flush = sorted.all { it.suit == sorted[0].suit }
                val high = straightHighRank(sorted.map { it.rank })
                when {
                    high != null -> group(
                        if (flush) CardType.STRAIGHT_FLUSH else CardType.STRAIGHT,
                        high, sorted
                    )
                    flush -> group(CardType.FLUSH, sorted.last().rank, sorted)
                    else -> CardGroup.INVALID
                }
            }

            // 葫芦（三带二）：3 + 2
            n == STRAIGHT_LEN && counts == listOf(2, 3) -> {
                val tripleRank = rankCounts.entries.first { it.value == 3 }.key
                group(CardType.FULL_HOUSE, tripleRank, sorted)
            }

            // 四带一：4 + 1
            n == STRAIGHT_LEN && counts == listOf(1, 4) -> {
                val fourRank = rankCounts.entries.first { it.value == 4 }.key
                group(CardType.FOUR_WITH_ONE, fourRank, sorted)
            }

            else -> CardGroup.INVALID
        }
    }

    /**
     * 5 张点数是否构成顺子。
     * @return 顺子的「主体最大牌点」（A-2-3-4-5 返回 5，10-J-Q-K-A 返回 14）；非顺子返回 null
     */
    fun straightHighRank(ranks: List<Int>): Int? {
        if (ranks.size != STRAIGHT_LEN || ranks.toSet().size != STRAIGHT_LEN) return null
        val sorted = ranks.sorted()
        // 普通顺子：3..A 内严格连续
        if (sorted.all { it in 3..RANK_A } &&
            sorted.zipWithNext().all { (a, b) -> b == a + 1 }
        ) {
            return sorted.last()
        }
        // A-2-3-4-5：A 作 1，主体最大牌点为 5（最小顺子）
        if (sorted == listOf(3, 4, 5, RANK_A, RANK_TWO)) return 5
        return null
    }

    /** 构造 CardGroup：五张牌型 length=5，其余 length=1 */
    private fun group(type: CardType, mainRank: Int, cards: List<Card>): CardGroup {
        val len = if (cards.size == STRAIGHT_LEN) STRAIGHT_LEN else 1
        return CardGroup(type, mainRank, len, cards, topSuitOf(cards))
    }

    /**
     * 决定性花色序：取「最大点数」的那些牌中花色最大的一张。
     * 仅用于单张 / 对子 / 三条的同点比较；五张牌型只比主体点数，不参与花色比较。
     */
    private fun topSuitOf(cards: List<Card>): Int {
        if (cards.isEmpty()) return 0
        val maxRank = cards.maxOf { it.rank }
        return cards.filter { it.rank == maxRank }.maxOf { it.suit.order }
    }

    // ============================== 比较 ==============================

    /**
     * 比较两组牌的大小
     * @return 正数表示 group1 大，负数表示 group2 大，0 表示不可比 / 相等
     */
    fun compare(group1: CardGroup, group2: CardGroup): Int {
        if (group1.type == CardType.INVALID || group2.type == CardType.INVALID) return 0

        // 不同牌型：仅五张牌型之间按层级跨型比较，其余不可比
        if (group1.type != group2.type) {
            if (group1.cards.size == STRAIGHT_LEN && group2.cards.size == STRAIGHT_LEN) {
                return group1.type.level.compareTo(group2.type.level)
            }
            return 0
        }

        // 同牌型：先比主体点数
        val r = group1.mainRank.compareTo(group2.mainRank)
        if (r != 0) return r

        // 五张牌型同点即相等（不比花色）；单张 / 对子 / 三条再比花色
        if (group1.cards.size == STRAIGHT_LEN) return 0
        return group1.topSuitOrder.compareTo(group2.topSuitOrder)
    }

    // ============================== 合法性 ==============================

    /**
     * 校验出牌是否合法（不含「首手带方块3」「顶大」等流程性约束，那些由 GameEngine 校验）
     * @param played 当前要出的牌
     * @param lastPlay 上一手牌（null 或 INVALID 表示自由出牌）
     */
    fun isValidPlay(played: CardGroup, lastPlay: CardGroup?): Boolean {
        if (played.type == CardType.INVALID) return false

        // 自由出牌：首家可出任意合法牌型
        if (lastPlay == null || lastPlay.type == CardType.INVALID) return true

        // 张数必须相同
        if (played.cards.size != lastPlay.cards.size) return false

        // 五张牌型：按层级跨型，同型比主体点数
        if (played.cards.size == STRAIGHT_LEN) return compare(played, lastPlay) > 0

        // 单张 / 对子 / 三条：必须同牌型且更大
        if (played.type != lastPlay.type) return false
        return compare(played, lastPlay) > 0
    }

    // ============================== 候选生成 ==============================

    /**
     * 从手牌中找出所有合法出牌组合
     * @param hand 手牌
     * @param lastPlay 上一手牌（null 表示自由出牌）
     */
    fun findAllValidPlays(hand: List<Card>, lastPlay: CardGroup?): List<CardGroup> {
        if (hand.isEmpty()) return emptyList()

        val sorted = hand.sortedWith(compareBy({ it.rank }, { it.suit.order }))

        // 自由出牌：返回所有可出的牌型
        if (lastPlay == null || lastPlay.type == CardType.INVALID) {
            return findAllPossiblePlays(sorted)
        }

        // 跟牌：只生成与上家相同张数的候选，再按规则筛选
        val candidates = when (lastPlay.cards.size) {
            1 -> genSingles(sorted)
            2 -> genPairs(sorted)
            3 -> genTriples(sorted)
            STRAIGHT_LEN -> genFiveCardPlays(sorted)
            else -> emptyList()
        }
        return candidates.filterTo(mutableListOf()) { isValidPlay(it, lastPlay) }.distinct()
    }

    /** 自由出牌时的全部候选 */
    private fun findAllPossiblePlays(sorted: List<Card>): List<CardGroup> {
        val results = mutableListOf<CardGroup>()
        results.addAll(genSingles(sorted))
        results.addAll(genPairs(sorted))
        results.addAll(genTriples(sorted))
        results.addAll(genFiveCardPlays(sorted))
        return results.distinct()
    }

    /** 五张牌型的全部候选：顺子 / 同花顺 / 同花五 / 葫芦 / 四带一 */
    private fun genFiveCardPlays(sorted: List<Card>): List<CardGroup> {
        val results = mutableListOf<CardGroup>()
        results.addAll(genStraights(sorted, flush = false))
        results.addAll(genStraights(sorted, flush = true))
        results.addAll(genFlushes(sorted))
        results.addAll(genFullHouses(sorted))
        results.addAll(genFourWithOne(sorted))
        return results
    }

    /** 按点数分组，组内按花色序升序（便于 last 取大花色） */
    private fun byRank(sorted: List<Card>): Map<Int, List<Card>> =
        sorted.groupBy { it.rank }
            .mapValues { it.value.sortedBy { c -> c.suit.order } }
            .toSortedMap()

    private fun genSingles(sorted: List<Card>): List<CardGroup> =
        sorted.map { group(CardType.SINGLE, it.rank, listOf(it)) }

    private fun genPairs(sorted: List<Card>): List<CardGroup> {
        val results = mutableListOf<CardGroup>()
        for ((rank, cards) in byRank(sorted)) {
            if (cards.size >= 2) results.add(group(CardType.PAIR, rank, cards.takeLast(2)))
        }
        return results
    }

    private fun genTriples(sorted: List<Card>): List<CardGroup> {
        val results = mutableListOf<CardGroup>()
        for ((rank, cards) in byRank(sorted)) {
            if (cards.size >= 3) results.add(group(CardType.TRIPLE, rank, cards.takeLast(3)))
        }
        return results
    }

    private fun genFullHouses(sorted: List<Card>): List<CardGroup> {
        val results = mutableListOf<CardGroup>()
        val byRank = byRank(sorted)
        val tripleRanks = byRank.filter { it.value.size >= 3 }.keys
        val pairRanks = byRank.filter { it.value.size >= 2 }.keys
        for (t in tripleRanks) {
            val triple = byRank[t]!!.takeLast(3)
            for (p in pairRanks) {
                if (p == t) continue
                val pair = byRank[p]!!.takeLast(2)
                results.add(group(CardType.FULL_HOUSE, t, triple + pair))
            }
        }
        return results
    }

    private fun genFourWithOne(sorted: List<Card>): List<CardGroup> {
        val results = mutableListOf<CardGroup>()
        val byRank = byRank(sorted)
        for ((rank, cards) in byRank) {
            if (cards.size < 4) continue
            val four = cards.takeLast(4)
            val kicker = pickKicker(sorted, rank) ?: continue
            results.add(group(CardType.FOUR_WITH_ONE, rank, four + kicker))
        }
        return results
    }

    /** 四带一的附带牌：优先挑一张不成对/不成条的散牌，尽量不破坏手牌结构 */
    private fun pickKicker(sorted: List<Card>, excludeRank: Int): Card? {
        val byRank = byRank(sorted)
        byRank.filterKeys { it != excludeRank }
            .filter { it.value.size == 1 }
            .toSortedMap()
            .values
            .firstOrNull()
            ?.let { return it.first() }
        // 没有散牌时退化为取最小的非四条牌
        return sorted.firstOrNull { it.rank != excludeRank }
    }

    /**
     * 生成顺子 / 同花顺（均固定 5 张，含 A-2-3-4-5）
     * @param flush true 生成同花顺（按花色分别找连续段）
     */
    private fun genStraights(sorted: List<Card>, flush: Boolean): List<CardGroup> {
        val results = mutableListOf<CardGroup>()
        val type = if (flush) CardType.STRAIGHT_FLUSH else CardType.STRAIGHT

        val pools: List<Map<Int, List<Card>>> = if (flush) {
            Suit.values().map { s -> sorted.filter { it.suit == s }.groupBy { it.rank } }
        } else {
            listOf(sorted.groupBy { it.rank })
        }

        for (pool in pools) {
            val ranks = pool.keys.filter { it in 3..RANK_A }.sorted()
            var i = 0
            while (i < ranks.size) {
                var j = i
                while (j + 1 < ranks.size && ranks[j + 1] == ranks[j] + 1) j++
                if (j - i + 1 >= STRAIGHT_LEN) {
                    for (start in i..(j - STRAIGHT_LEN + 1)) {
                        val seq = (start until start + STRAIGHT_LEN).map { ranks[it] }
                        val cards = if (flush) {
                            seq.map { r -> pool[r]!!.last() }
                        } else {
                            // 非同花分支若恰好凑成同花，实为同花顺（由 flush 分支生成），
                            // 不能标成顺子；但也不能整手丢弃——换一张其它花色即可得到
                            // 同样点数段的普通顺子，否则会「只能出同花顺」，白白浪费强牌。
                            pickNonFlush(pool, seq) ?: continue
                        }
                        results.add(group(type, seq.last(), cards))
                    }
                }
                i = j + 1
            }

            // A-2-3-4-5（A 作 1）：主体最大牌点为 5
            if (pool.containsKey(3) && pool.containsKey(4) && pool.containsKey(5) &&
                pool.containsKey(RANK_A) && pool.containsKey(RANK_TWO)
            ) {
                val seq = listOf(3, 4, 5, RANK_A, RANK_TWO)
                val cards = if (flush) {
                    seq.map { r -> pool[r]!!.last() }
                } else {
                    pickNonFlush(pool, seq) ?: continue
                }
                results.add(group(type, 5, cards))
            }
        }
        return results
    }

    /**
     * 从各点数的候选牌中凑一手「非同花」组合（用于生成普通顺子）。
     * 先取每个点数的最大花色；若恰好全同花，则把其中一张换成其它花色。
     * @return 非同花组合；该点数段在手中无法凑出非同花时返回 null
     */
    private fun pickNonFlush(pool: Map<Int, List<Card>>, ranks: List<Int>): List<Card>? {
        val picked = ranks.map { r -> pool[r]!!.last() }.toMutableList()
        if (picked.any { it.suit != picked[0].suit }) return picked
        for (idx in picked.indices) {
            val other = pool[picked[idx].rank]!!.firstOrNull { it.suit != picked[idx].suit }
            if (other != null) {
                picked[idx] = other
                return picked
            }
        }
        return null
    }

    /**
     * 生成同花五（5 张同花色、点数不连续）。
     * 每个花色按点数升序取 5 张滑窗，跳过恰好成顺子的窗口（那是同花顺）；
     * 若整副花色都是连续段，则用第 6 张替换中间张构造一个非顺子组合。
     */
    private fun genFlushes(sorted: List<Card>): List<CardGroup> {
        val results = mutableListOf<CardGroup>()
        for (s in Suit.values()) {
            val cards = sorted.filter { it.suit == s }.sortedBy { it.rank }
            if (cards.size < STRAIGHT_LEN) continue

            for (hi in STRAIGHT_LEN - 1 until cards.size) {
                val win = cards.subList(hi - STRAIGHT_LEN + 1, hi + 1).toList()
                if (straightHighRank(win.map { it.rank }) != null) continue
                results.add(group(CardType.FLUSH, win.last().rank, win))
            }

            // 兜底：窗口全是顺子时，替换中间一张构造非顺子同花
            if (cards.size > STRAIGHT_LEN) {
                val base = cards.take(STRAIGHT_LEN).toMutableList()
                if (straightHighRank(base.map { it.rank }) != null) {
                    base[STRAIGHT_LEN / 2] = cards[STRAIGHT_LEN]
                    val fixed = base.sortedBy { it.rank }
                    if (straightHighRank(fixed.map { it.rank }) == null) {
                        results.add(group(CardType.FLUSH, fixed.last().rank, fixed))
                    }
                }
            }
        }
        return results
    }
}
