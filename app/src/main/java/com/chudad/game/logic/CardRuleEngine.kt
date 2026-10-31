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
 * 二、牌型与层级（低 → 高）
 *   单张(1) < 对子(2) < 三条(3) < 顺子(5+) < 葫芦(3+2) < 四带一(4+1) < 同花顺(5+)
 *   - 顺子：5 张及以上连续单牌，不可含 2（即点数上限 A=14）
 *   - 葫芦：三条 + 对子（5 张），比较时看三条点数
 *   - 四带一：四条 + 1 张散牌（5 张），比较时看四条点数
 *   - 同花顺：5 张及以上连续且同花色，为最大牌型
 *
 * 三、跟牌规则
 *   - 必须同牌型、同长度且更大（顺子/同花顺长度须一致，比较最大牌）
 *   - 炸弹例外：四带一可压任意「非同花顺」牌型；同花顺可压任意「非同花顺」牌型
 *   - 同花顺之间：须同长度且更大
 */
object CardRuleEngine {

    /** 顺子/同花顺最短长度 */
    const val MIN_STRAIGHT = 5

    /** 顺子可使用的最大点数（A=14；2=15 不参与顺子） */
    const val STRAIGHT_MAX_RANK = 14

    /** 顺子/同花顺最长长度（3..A 共 12 个点数） */
    private const val MAX_STRAIGHT = 12

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
            n == 1 -> group(CardType.SINGLE, sorted[0].rank, 1, sorted)

            // 对子：2 张同点
            n == 2 && counts == listOf(2) -> group(CardType.PAIR, sorted[0].rank, 1, sorted)

            // 三条：3 张同点
            n == 3 && counts == listOf(3) -> group(CardType.TRIPLE, sorted[0].rank, 1, sorted)

            // 顺子 / 同花顺：5+ 张、点数互不相同、连续、且不含 2
            n >= MIN_STRAIGHT &&
                    rankCounts.size == n &&
                    isConsecutive(sorted.map { it.rank }) &&
                    sorted.all { it.rank <= STRAIGHT_MAX_RANK } -> {
                val flush = sorted.all { it.suit == sorted[0].suit }
                group(
                    if (flush) CardType.STRAIGHT_FLUSH else CardType.STRAIGHT,
                    sorted.last().rank,
                    n,
                    sorted
                )
            }

            // 葫芦（三带二）：5 张 = 3 + 2
            n == 5 && counts == listOf(2, 3) -> {
                val tripleRank = rankCounts.entries.first { it.value == 3 }.key
                group(CardType.FULL_HOUSE, tripleRank, 1, sorted)
            }

            // 四带一：5 张 = 4 + 1
            n == 5 && counts == listOf(1, 4) -> {
                val fourRank = rankCounts.entries.first { it.value == 4 }.key
                group(CardType.FOUR_WITH_ONE, fourRank, 1, sorted)
            }

            else -> CardGroup.INVALID
        }
    }

    /** 判断排序后的点数序列是否严格连续 */
    private fun isConsecutive(ranks: List<Int>): Boolean {
        for (i in 1 until ranks.size) {
            if (ranks[i] != ranks[i - 1] + 1) return false
        }
        return true
    }

    /** 构造 CardGroup，自动计算决定性的花色序 */
    private fun group(type: CardType, mainRank: Int, length: Int, cards: List<Card>): CardGroup =
        CardGroup(type, mainRank, length, cards, topSuitOf(cards))

    /**
     * 决定性花色序：取「最大点数」的那些牌中花色最大的一张
     * 单张→自身花色；对子/三条/四条→其中最大花色；顺子→最大牌的花色
     */
    private fun topSuitOf(cards: List<Card>): Int {
        if (cards.isEmpty()) return 0
        val maxRank = cards.maxOf { it.rank }
        return cards.filter { it.rank == maxRank }.maxOf { it.suit.order }
    }

    // ============================== 比较 ==============================

    /**
     * 比较两组牌的大小
     * @return 正数表示 group1 大，负数表示 group2 大，0 表示不可比/相等
     */
    fun compare(group1: CardGroup, group2: CardGroup): Int {
        if (group1.type == CardType.INVALID || group2.type == CardType.INVALID) return 0

        // 不同牌型：按层级比较
        if (group1.type != group2.type) return group1.type.level.compareTo(group2.type.level)

        // 同牌型：长度必须一致（顺子/同花顺）
        if (group1.length != group2.length) return 0

        // 先比主牌点数，再比决定性花色
        val r = group1.mainRank.compareTo(group2.mainRank)
        return if (r != 0) r else group1.topSuitOrder.compareTo(group2.topSuitOrder)
    }

    // ============================== 合法性 ==============================

    /**
     * 校验出牌是否合法
     * @param played 当前要出的牌
     * @param lastPlay 上一手牌（null 或 INVALID 表示自由出牌）
     */
    fun isValidPlay(played: CardGroup, lastPlay: CardGroup?): Boolean {
        if (played.type == CardType.INVALID) return false

        // 自由出牌：首家可出任意合法牌型
        if (lastPlay == null || lastPlay.type == CardType.INVALID) return true

        // 同花顺：终极牌型，可压任意非同花顺；同花顺之间须同长度且更大
        if (played.type == CardType.STRAIGHT_FLUSH) {
            return if (lastPlay.type == CardType.STRAIGHT_FLUSH) {
                played.length == lastPlay.length && compare(played, lastPlay) > 0
            } else true
        }
        // 上家是同花顺：只能被更高同花顺压
        if (lastPlay.type == CardType.STRAIGHT_FLUSH) return false

        // 四带一：炸弹，可压任意非同花顺牌型；四带一之间比四条点数
        if (played.type == CardType.FOUR_WITH_ONE) {
            return if (lastPlay.type == CardType.FOUR_WITH_ONE) {
                compare(played, lastPlay) > 0
            } else true
        }

        // 其余：必须同牌型、同长度且更大
        if (played.type == lastPlay.type && played.length == lastPlay.length) {
            return compare(played, lastPlay) > 0
        }

        return false
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

        val results = mutableListOf<CardGroup>()
        val byRank = byRank(sorted)

        when (lastPlay.type) {
            CardType.SINGLE -> {
                for (c in sorted) {
                    if (c.rank > lastPlay.mainRank ||
                        (c.rank == lastPlay.mainRank && c.suit.order > lastPlay.topSuitOrder)
                    ) {
                        results.add(group(CardType.SINGLE, c.rank, 1, listOf(c)))
                    }
                }
            }

            CardType.PAIR -> {
                for ((rank, cards) in byRank) {
                    if (cards.size < 2) continue
                    val g = group(CardType.PAIR, rank, 1, cards.takeLast(2))
                    if (compare(g, lastPlay) > 0) results.add(g)
                }
            }

            CardType.TRIPLE -> {
                for ((rank, cards) in byRank) {
                    if (cards.size < 3) continue
                    val g = group(CardType.TRIPLE, rank, 1, cards.takeLast(3))
                    if (compare(g, lastPlay) > 0) results.add(g)
                }
            }

            CardType.STRAIGHT -> {
                genStraights(sorted, lastPlay.length, false)
                    .filterTo(results) { compare(it, lastPlay) > 0 }
            }

            CardType.FULL_HOUSE -> {
                genFullHouses(sorted).filterTo(results) { compare(it, lastPlay) > 0 }
            }

            CardType.FOUR_WITH_ONE -> {
                // 四带一之间比四条点数（已在上面分支内筛选更大的）
                genFourWithOne(sorted).filterTo(results) { compare(it, lastPlay) > 0 }
            }

            CardType.STRAIGHT_FLUSH -> {
                genStraights(sorted, lastPlay.length, true)
                    .filterTo(results) { compare(it, lastPlay) > 0 }
            }

            else -> {}
        }

        // 炸弹补充：上家不是同花顺时，四带一/同花顺 均可压
        if (lastPlay.type != CardType.STRAIGHT_FLUSH) {
            // 四带一：若上家不是四带一，任意四带一都可压（炸弹）
            if (lastPlay.type != CardType.FOUR_WITH_ONE) {
                results.addAll(genFourWithOne(sorted))
            }
            // 同花顺：任意同花顺都可压非同花顺
            results.addAll(genStraights(sorted, null, true))
        }

        return results.distinct()
    }

    /** 自由出牌时的全部候选 */
    private fun findAllPossiblePlays(sorted: List<Card>): List<CardGroup> {
        val results = mutableListOf<CardGroup>()
        results.addAll(genSingles(sorted))
        results.addAll(genPairs(sorted))
        results.addAll(genTriples(sorted))
        results.addAll(genStraights(sorted, null, false))
        results.addAll(genStraights(sorted, null, true))
        results.addAll(genFullHouses(sorted))
        results.addAll(genFourWithOne(sorted))
        return results.distinct()
    }

    /** 按点数分组，组内按花色序升序（便于 takeLast 取大花色） */
    private fun byRank(sorted: List<Card>): Map<Int, List<Card>> =
        sorted.groupBy { it.rank }
            .mapValues { it.value.sortedBy { c -> c.suit.order } }
            .toSortedMap()

    private fun genSingles(sorted: List<Card>): List<CardGroup> =
        sorted.map { group(CardType.SINGLE, it.rank, 1, listOf(it)) }

    private fun genPairs(sorted: List<Card>): List<CardGroup> {
        val results = mutableListOf<CardGroup>()
        for ((rank, cards) in byRank(sorted)) {
            if (cards.size >= 2) results.add(group(CardType.PAIR, rank, 1, cards.takeLast(2)))
        }
        return results
    }

    private fun genTriples(sorted: List<Card>): List<CardGroup> {
        val results = mutableListOf<CardGroup>()
        for ((rank, cards) in byRank(sorted)) {
            if (cards.size >= 3) results.add(group(CardType.TRIPLE, rank, 1, cards.takeLast(3)))
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
                results.add(group(CardType.FULL_HOUSE, t, 1, triple + pair))
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
            results.add(group(CardType.FOUR_WITH_ONE, rank, 1, four + kicker))
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
     * 生成顺子 / 同花顺
     * @param requireLen 指定长度（跟牌时与上家一致）；null 表示生成 5..最大 的所有长度
     * @param flush true 生成同花顺（按花色分别找连续段）
     */
    private fun genStraights(
        sorted: List<Card>,
        requireLen: Int?,
        flush: Boolean
    ): List<CardGroup> {
        val results = mutableListOf<CardGroup>()

        val pools: List<Map<Int, List<Card>>> = if (flush) {
            Suit.values().map { s ->
                sorted.filter { it.suit == s }.groupBy { it.rank }
            }
        } else {
            listOf(byRank(sorted))
        }

        for (pool in pools) {
            val ranks = pool.keys.filter { it in 3..STRAIGHT_MAX_RANK }.sorted()
            if (ranks.isEmpty()) continue

            var i = 0
            while (i < ranks.size) {
                var j = i
                while (j + 1 < ranks.size && ranks[j + 1] == ranks[j] + 1) j++
                val runLen = j - i + 1

                val from = requireLen ?: MIN_STRAIGHT
                val to = requireLen ?: MAX_STRAIGHT

                for (len in from..to) {
                    if (len > runLen) break
                    if (len < MIN_STRAIGHT) continue
                    for (start in i..(j - len + 1)) {
                        val seqRanks = (start until start + len).map { ranks[it] }
                        // 每个点数取花色最大的一张（最强代表）
                        val cards = seqRanks.map { r -> pool[r]!!.last() }
                        val type = if (flush) CardType.STRAIGHT_FLUSH else CardType.STRAIGHT
                        results.add(group(type, seqRanks.last(), len, cards))
                    }
                }
                i = j + 1
            }
        }
        return results
    }
}
