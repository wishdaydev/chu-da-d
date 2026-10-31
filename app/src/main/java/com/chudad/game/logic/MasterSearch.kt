package com.chudad.game.logic

import com.chudad.game.model.Card
import com.chudad.game.model.CardGroup
import com.chudad.game.model.CardType
import com.chudad.game.model.Suit

/**
 * 大师模式「4 人完美信息搜索」求解器（锄大D / Big Two）
 *
 * 模型假设：引擎已知全部 4 家手牌（[MasterAIDecision.Snapshot] 已提供），
 * 在完美信息下做博弈树搜索，选出当前决策方（myIndex）的最优一手。
 *
 * 建模视角：4 人各自为战，但为支持 alpha-beta 裁剪（否则 4 人 max^n 无界剪枝），
 * 将「其余 3 家」按联盟视角处理——它们共同最小化 myIndex 的胜算。
 * 这是 n 人 Big Two 完美信息搜索的标准、稳妥简化：对「我能否先出完」给出
 * 偏保守（worst-case）但合理强大的决策，且计算可行（设备上 5s 预算内可迭代加深）。
 *
 * 终局：任意一家手牌出空即结束（与游戏「先出完者胜」结算一致），
 * 该家若是 myIndex 计为胜、否则计为负，胜负带层数（ply）使「更快取胜 / 更晚落败」被偏好。
 *
 * 评估（非终局截断处）：以「手数竞速」为核心——
 *   每家的 [minPlays]（把手牌拆成最少合法牌组数）越小越接近出完；
 *   用 (对手最小手数 − 我的手数) 与 (对手最小张数 − 我的张数) 合成从我视角的值。
 *
 * 走法生成直接复用 [CardRuleEngine.findAllValidPlays]（含同花顺/四带一/葫芦/同花五等完整牌型，
 * 不裁剪候选集），保证搜索不会因候选集受限而漏掉最优解。
 */
internal object MasterSearch {

    private const val WIN = 1_000_000          // 终局胜负值（带 ply 偏移后仍在 ±WIN 内）
    private const val INF = 2_000_000          // alpha-beta 初始边界

    /**
     * 迭代加深深度上限。
     * 仅作「防止无意义递归」的安全阀——真正的停止条件是时间/节点预算，
     * 因此取值需远大于预算内实际可达深度（4 人局通常 5~12 层），
     * 否则搜索会在预算耗尽前提前收敛，导致「最大思考时间」设置形同虚设。
     */
    private const val MAX_DEPTH = 32

    /** 手数竞速评估中各分量的权重（值需落在 (-WIN/2, WIN/2) 内，避免淹没终局值） */
    private const val W_PLAYS = 600
    private const val W_CARDS = 60

    /** A-2-3-4-5：A 作 1 时的最小顺子（点数序列，14=A、15=2） */
    private val A2345 = listOf(3, 4, 5, 14, 15)

    /** 单张牌的位掩码（id ∈ 0..51，适配 64 位） */
    private fun cardMask(card: Card): Long = 1L shl card.id

    /** 走法：出牌 或 过牌 */
    private sealed class Move {
        data class Play(val group: CardGroup) : Move()
        object Pass : Move()
    }

    /** 应用一步走法前的状态快照，用于撤销 */
    private class Undo(
        val player: Int,
        val removed: List<Card>?,          // 出牌时被移除的牌（过牌为 null）
        val prevCur: Int,
        val prevTop: CardGroup?,
        val prevTopPlayer: Int,
        val prevPasses: Int,
        val winner: Int                    // 应用后是否有人出完（-1 表示无）
    )

    /** 内部搜索状态（就地变更 + 撤销，避免大量拷贝） */
    private class State(
        val hands: Array<MutableList<Card>>,
        var cur: Int,
        var top: CardGroup?,
        var topPlayer: Int,
        var passes: Int
    )

    // 手数评估缓存（纯函数，按手牌位掩码命中；ConcurrentHashMap 以支持仿真多线程）
    private val minPlaysCache = java.util.concurrent.ConcurrentHashMap<Long, Int>()

    /**
     * 求解当前局面下 myIndex 的最优一手。
     *
     * @param mustContainDiamond3 本手是否必须包含方块 3（整场第一局首手）；为 true 时
     *        搜索只在含 ♦3 的走法中择优，避免选出会被引擎兜底改写的非法首手
     * @param mustTopBig 下家是否已报单（顶大义务）：为 true 时单张走法只保留手中最大的那张，
     *        与引擎侧的顶大校验保持一致
     * @param cancelled 取消判定（如新开一局后旧搜索应立即退出）；返回 true 时尽快结束
     * @return 最优出牌（[CardGroup]）；跟牌且搜索认为过牌最优时返回 null。
     */
    fun solve(
        hands: Map<Int, List<Card>>,
        myIndex: Int,
        topGroup: CardGroup?,
        topPlayer: Int,
        passes: Int,
        current: Int,
        deadlineMs: Long,
        nodeLimit: Int,
        mustContainDiamond3: Boolean = false,
        mustTopBig: Boolean = false,
        cancelled: () -> Boolean = { false }
    ): CardGroup? {
        val myHand = hands[myIndex] ?: return null
        if (myHand.isEmpty()) return null

        val state = State(
            hands = Array(4) { i -> hands[i]?.toMutableList() ?: mutableListOf() },
            cur = current,
            top = if (topGroup?.type == CardType.INVALID) null else topGroup,
            topPlayer = topPlayer,
            passes = passes
        )

        val rawMoves = generateMoves(state, myIndex)
        if (rawMoves.isEmpty()) return null   // 不应发生：自由出牌必有走法
        // 流程性约束先落到根走法集上，使搜索结果本身就是合法的一手
        val rootMoves = applyRootConstraints(rawMoves, myHand, mustContainDiamond3, mustTopBig)

        val deadline = System.currentTimeMillis() + deadlineMs
        val counter = longArrayOf(0L)

        var bestMove: Move? = rootMoves.first()

        for (depth in 1..MAX_DEPTH) {
            if (cancelled()) break
            val rootOrdered = orderMoves(state, myIndex, rootMoves, isMax = true)
            var bestVal = -INF
            var localBest: Move? = null
            var alpha = -INF

            for (mv in rootOrdered) {
                if (System.currentTimeMillis() > deadline || counter[0] > nodeLimit || cancelled()) break
                val undo = applyMove(state, myIndex, mv)
                val v = when {
                    undo.winner == myIndex -> WIN
                    undo.winner >= 0 -> -WIN
                    else -> search(state, myIndex, alpha, INF, depth - 1, 1, deadline, counter, nodeLimit, cancelled)
                }
                undoMove(state, undo)

                if (v > bestVal) { bestVal = v; localBest = mv }
                if (bestVal > alpha) alpha = bestVal
            }

            // 仅采纳完整跑完当前深度的结果
            if (System.currentTimeMillis() <= deadline && counter[0] <= nodeLimit && !cancelled()) {
                if (localBest != null) bestMove = localBest
                // 已找到必胜首手，提前结束
                if (bestVal >= WIN - 1) break
            } else {
                break
            }
        }

        return when (val m = bestMove) {
            is Move.Play -> m.group
            is Move.Pass -> null
            null -> null
        }
    }

    /**
     * 把「首手含方块 3」「顶大」这两条流程规则施加到根走法集合上。
     * 两者同时成立时取交集；交集为空则优先满足更硬的规则（首手 ♦3）。
     */
    private fun applyRootConstraints(
        moves: List<Move>,
        hand: List<Card>,
        mustContainDiamond3: Boolean,
        mustTopBig: Boolean
    ): List<Move> {
        var result = moves

        if (mustContainDiamond3) {
            val d3 = hand.firstOrNull { it.rank == 3 && it.suit == Suit.DIAMOND }
            if (d3 != null) {
                val withD3 = result.filter { mv ->
                    mv is Move.Play && mv.group.cards.any { it.id == d3.id }
                }
                result = withD3.ifEmpty {
                    listOf(Move.Play(CardGroup(CardType.SINGLE, 3, 1, listOf(d3), d3.suit.order)))
                }
            }
        }

        if (mustTopBig) {
            val max = hand.maxWithOrNull(compareBy({ it.rank }, { it.suit.order }))
            if (max != null) {
                val topOnly = result.filter { mv ->
                    mv !is Move.Play || mv.group.type != CardType.SINGLE ||
                        mv.group.cards.singleOrNull()?.id == max.id
                }
                // 过滤后为空说明只剩非最大单张可选，此时保留原集合（引擎侧仍会兜底改判）
                if (topOnly.isNotEmpty()) result = topOnly
            }
        }

        return result.ifEmpty { moves }
    }

    /**
     * 博弈树搜索（值始终从 myIndex 视角返回）。
     * cur == myIndex 为 MAX 节点，否则为 MIN 节点（全场联盟）。
     */
    private fun search(
        state: State,
        myIndex: Int,
        alphaIn: Int,
        betaIn: Int,
        depth: Int,
        ply: Int,
        deadline: Long,
        counter: LongArray,
        nodeLimit: Int,
        cancelled: () -> Boolean
    ): Int {
        counter[0]++
        if (System.currentTimeMillis() > deadline || counter[0] > nodeLimit || cancelled()) {
            return evaluate(state, myIndex)
        }
        if (depth <= 0) return evaluate(state, myIndex)

        val cur = state.cur
        val moves = generateMoves(state, cur)
        if (moves.isEmpty()) return evaluate(state, myIndex)

        val isMax = (cur == myIndex)
        val ordered = orderMoves(state, cur, moves, isMax)
        var alpha = alphaIn
        var beta = betaIn
        var best = if (isMax) -INF else INF

        for (mv in ordered) {
            if (System.currentTimeMillis() > deadline || counter[0] > nodeLimit) break
            val undo = applyMove(state, cur, mv)
            val v = when {
                undo.winner == myIndex -> WIN - ply
                undo.winner >= 0 -> -(WIN - ply)
                else -> search(state, myIndex, alpha, beta, depth - 1, ply + 1, deadline, counter, nodeLimit, cancelled)
            }
            undoMove(state, undo)

            if (isMax) {
                if (v > best) best = v
                if (best > alpha) alpha = best
                if (alpha >= beta) break
            } else {
                if (v < best) best = v
                if (best < beta) beta = best
                if (alpha >= beta) break
            }
        }
        return best
    }

    /** 生成某玩家的全部合法走法（含过牌，若跟牌） */
    private fun generateMoves(state: State, player: Int): List<Move> {
        val hand = state.hands[player]
        val moves = mutableListOf<Move>()
        val plays = CardRuleEngine.findAllValidPlays(hand, state.top)
        for (g in plays) moves.add(Move.Play(g))
        if (state.top != null) moves.add(Move.Pass)   // 跟牌才可过牌；自由出牌必须出
        return moves
    }

    /** 应用走法（就地变更 state），返回可撤销的快照 */
    private fun applyMove(state: State, player: Int, mv: Move): Undo {
        val prevCur = state.cur
        val prevTop = state.top
        val prevTopPlayer = state.topPlayer
        val prevPasses = state.passes

        return when (mv) {
            is Move.Pass -> {
                state.passes++
                if (state.passes >= 3) {          // 其余 3 家全过 → 上一手出牌者重新领出
                    state.cur = state.topPlayer
                    state.top = null
                    state.topPlayer = -1
                    state.passes = 0
                } else {
                    state.cur = (state.cur + 1) % 4
                }
                Undo(player, null, prevCur, prevTop, prevTopPlayer, prevPasses, -1)
            }
            is Move.Play -> {
                val g = mv.group
                val cards = g.cards
                state.hands[player].removeAll(cards)
                val winner = if (state.hands[player].isEmpty()) player else -1
                state.top = g
                state.topPlayer = player
                state.passes = 0
                state.cur = (player + 1) % 4
                Undo(player, cards, prevCur, prevTop, prevTopPlayer, prevPasses, winner)
            }
        }
    }

    /** 撤销走法 */
    private fun undoMove(state: State, undo: Undo) {
        state.cur = undo.prevCur
        state.top = undo.prevTop
        state.topPlayer = undo.prevTopPlayer
        state.passes = undo.prevPasses
        if (undo.removed != null) {
            state.hands[undo.player].addAll(undo.removed)
        }
    }

    /**
     * 走法排序：好的候选先搜，提升 alpha-beta 裁剪效率。
     * - MAX：能一手出完者优先；否则按主牌点数升序（小牌先出、保留大牌）。
     * - MIN：能一手出完者优先（对手出完即我负，需尽快探明）；否则按主牌点数降序（大牌压我）。
     * 过牌在两类中均排最后。
     */
    private fun orderMoves(state: State, player: Int, moves: List<Move>, isMax: Boolean): List<Move> {
        val handSize = state.hands[player].size
        val scored = moves.map { mv ->
            val priority = when (mv) {
                is Move.Play -> {
                    val empties = handSize == mv.group.cards.size
                    val base = if (empties) 1 else 0
                    base * 1_000_000 + mv.group.mainRank * (if (isMax) 1 else -1)
                }
                is Move.Pass -> Int.MAX_VALUE       // 过牌在两类节点中都排最后
            }
            mv to priority
        }
        return scored.sortedBy { it.second }.map { it.first }
    }

    /** 从我视角评估局面：正=我领先，负=我落后 */
    private fun evaluate(state: State, myIndex: Int): Int {
        val myPlays = minPlays(state.hands[myIndex])
        val myCards = state.hands[myIndex].size
        var oppPlays = Int.MAX_VALUE
        var oppCards = Int.MAX_VALUE
        for (i in 0..3) {
            if (i == myIndex) continue
            oppPlays = minOf(oppPlays, minPlays(state.hands[i]))
            oppCards = minOf(oppCards, state.hands[i].size)
        }
        val v = (oppPlays - myPlays) * W_PLAYS + (oppCards - myCards) * W_CARDS
        return v.coerceIn(-WIN / 2, WIN / 2)
    }

    /**
     * 把手牌拆成「最少合法牌组数」的贪心估计（越小越接近出完）。
     * 非精确下界，但各玩家一致，比较有含义；用于评估与排序。
     * 直接基于剩余牌列表就地移除，避免 rank 计数与花色索引不同步导致误判。
     */
    private fun minPlays(cards: List<Card>): Int {
        if (cards.isEmpty()) return 0
        var mask = 0L
        for (c in cards) mask = mask or cardMask(c)
        minPlaysCache[mask]?.let { return it }

        val remaining = cards.toMutableList()
        var plays = 0

        while (remaining.isNotEmpty()) {
            var did = false

            // 1) 最长同花顺（取该花色最长连续段，每点数取一张）
            for (s in 0..3) {
                val rankSet = remaining.filter { it.suit.order == s }.map { it.rank }.toSortedSet()
                val run = longestRun(rankSet.toList(), 3, 14)
                if (run.size >= 5) {
                    // 顺子/同花顺固定 5 张：长连续段需拆成多次出牌
                    val five = run.take(5)
                    val toRemove = five.mapNotNull { r -> remaining.firstOrNull { c -> c.suit.order == s && c.rank == r } }
                    if (toRemove.size == five.size) {
                        remaining.removeAll(toRemove)
                        plays++; did = true; break
                    }
                }
                // A-2-3-4-5 同花顺（A 作 1，为最小顺子）
                if (A2345.all { it in rankSet }) {
                    val toRemove = A2345.mapNotNull { r ->
                        remaining.firstOrNull { c -> c.suit.order == s && c.rank == r }
                    }
                    if (toRemove.size == A2345.size) {
                        remaining.removeAll(toRemove)
                        plays++; did = true; break
                    }
                }
            }
            if (did) continue

            // 2) 最长顺子（任意花色，每点数取一张）
            val cnt = IntArray(16)
            for (c in remaining) cnt[c.rank]++
            val allRanks = (3..14).filter { cnt[it] > 0 }
            val run2 = longestRun(allRanks, 3, 14)
            val straight: List<Int>? = when {
                run2.size >= 5 -> run2.take(5)
                A2345.all { cnt[it] > 0 } -> A2345      // A-2-3-4-5（A 作 1）
                else -> null
            }
            if (straight != null) {
                val toRemove = straight.mapNotNull { r -> remaining.firstOrNull { c -> c.rank == r } }
                if (toRemove.size == straight.size) {
                    remaining.removeAll(toRemove)
                    plays++; continue
                }
            }

            // 3) 四带一（4 张同点 + 1 张散牌作带牌）
            val byRank = remaining.groupBy { it.rank }
            val fourEntry = byRank.entries.firstOrNull { it.value.size >= 4 }
            if (fourEntry != null && remaining.size > 4) {
                val four = fourEntry.value.take(4)
                val kicker = remaining.firstOrNull { it.rank != fourEntry.key }
                if (kicker != null) {
                    remaining.removeAll(four + kicker)
                    plays++; continue
                }
            }

            // 4) 葫芦（三条 + 对子）
            val triple = byRank.entries.firstOrNull { it.value.size >= 3 }
            if (triple != null) {
                val pair = byRank.entries.firstOrNull { it.key != triple.key && it.value.size >= 2 }
                if (pair != null) {
                    remaining.removeAll(triple.value.take(3) + pair.value.take(2))
                    plays++; continue
                }
            }

            // 5) 三条
            if (triple != null) {
                remaining.removeAll(triple.value.take(3)); plays++; continue
            }

            // 6) 对子
            val pr = byRank.entries.firstOrNull { it.value.size >= 2 }
            if (pr != null) {
                remaining.removeAll(pr.value.take(2)); plays++; continue
            }

            // 7) 单张（取任意一张）
            remaining.removeAt(0); plays++; continue
        }

        minPlaysCache[mask] = plays
        return plays
    }

    /** 在 [lo, hi] 范围内找最长的连续点数段 */
    private fun longestRun(ranks: List<Int>, lo: Int, hi: Int): List<Int> {
        val filtered = ranks.filter { it in lo..hi }
        if (filtered.isEmpty()) return emptyList()
        var best = listOf<Int>()
        var i = 0
        while (i < filtered.size) {
            var j = i
            while (j + 1 < filtered.size && filtered[j + 1] == filtered[j] + 1) j++
            val run = filtered.subList(i, j + 1)
            if (run.size > best.size) best = run
            i = j + 1
        }
        return best
    }
}
