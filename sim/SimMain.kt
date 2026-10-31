package com.chudad.sim

import com.chudad.game.logic.CardRuleEngine
import com.chudad.game.logic.MasterSearch
import com.chudad.game.model.Card
import com.chudad.game.model.CardGroup
import com.chudad.game.model.createDeck
import kotlin.random.Random
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * 锄大D 4 人仿真 harness（纯 JVM，配合 sim/run-sim.sh）。
 *
 * 四家全部使用大师模式完美信息搜索 [MasterSearch] 决策，用于标定搜索强度与性能：
 *   - 各家胜率分布（均等牌力下应接近 25%，可观察搜索是否偏向某座位/先后手）
 *   - 平均回合数（反映搜索推进效率）
 *   - 非法走法次数（应恒为 0，验证搜索始终输出合法决策）
 *
 * 用法: ./sim/run-sim.sh [games] [deadlineMs] [nodeLimit] [threads] [baseSeed]
 */
fun main(args: Array<String>) {
    val games = args.getOrNull(0)?.toIntOrNull() ?: 200
    val deadlineMs = args.getOrNull(1)?.toLongOrNull() ?: 50L
    val nodeLimit = args.getOrNull(2)?.toIntOrNull() ?: 150_000
    val threads = (args.getOrNull(3)?.toIntOrNull() ?: 1).coerceAtLeast(1)
    val baseSeed = args.getOrNull(4)?.toLongOrNull() ?: 1L

    println(
        "锄大D 4人仿真: games=$games deadline=${deadlineMs}ms " +
            "nodeLimit=$nodeLimit threads=$threads baseSeed=$baseSeed"
    )

    val wins = IntArray(4)
    val totalMoves = AtomicLong(0)
    val illegal = AtomicInteger(0)
    val t0 = System.currentTimeMillis()

    val runOne: (Int) -> Unit = { idx ->
        val seed = baseSeed + idx * 2654435761L
        val (winner, moves, bad) = playGame(seed, deadlineMs, nodeLimit)
        synchronized(wins) { if (winner in 0..3) wins[winner]++ }
        totalMoves.addAndGet(moves.toLong())
        if (bad) illegal.incrementAndGet()
    }

    if (threads <= 1) {
        for (i in 0 until games) runOne(i)
    } else {
        val jobs = (0 until games).map { i -> Thread { runOne(i) } }
        jobs.forEach { it.start() }
        jobs.forEach { it.join() }
    }

    val dt = System.currentTimeMillis() - t0
    val avg = if (games > 0) totalMoves.get().toDouble() / games else 0.0
    println("=== 结果 ===")
    for (p in 0..3) {
        val wr = if (games > 0) wins[p] * 100.0 / games else 0.0
        println("玩家$p 胜率: ${wins[p]}/$games (${"%.1f".format(wr)}%)")
    }
    println("平均回合数: ${"%.1f".format(avg)}")
    println("非法走法次数: ${illegal.get()}")
    println("总耗时: ${dt}ms")
}

/** 发牌：52 张洗牌后均分 4 家各 13 张；用种子保证可复现 */
private fun deal(seed: Long): Array<MutableList<Card>> {
    val deck = createDeck()
    deck.shuffle(Random(seed))
    return Array(4) { i -> deck.subList(i * 13, i * 13 + 13).toMutableList() }
}

/** 单局：四家均用 [MasterSearch] 决策。返回 (winner, moves, illegalFlag) */
private fun playGame(
    seed: Long,
    deadlineMs: Long,
    nodeLimit: Int
): Triple<Int, Int, Boolean> {
    val hands = deal(seed)
    var cur = 0
    var top: CardGroup? = null
    var topPlayer = -1
    var passes = 0
    var moves = 0
    var illegal = false

    while (true) {
        val hand = hands[cur]
        if (hand.isEmpty()) return Triple(cur, moves, illegal)

        val decision = MasterSearch.solve(
            hands = hands.mapIndexed { i, h -> i to h.toList() }.toMap(),
            myIndex = cur,
            topGroup = top,
            topPlayer = topPlayer,
            passes = passes,
            current = cur,
            deadlineMs = deadlineMs,
            nodeLimit = nodeLimit
        )
        val legal = CardRuleEngine.findAllValidPlays(hand, top)
        val passAllowed = top != null
        val ok = if (decision == null) passAllowed
        else legal.any { it.type == decision.type && it.cards.toSet() == decision.cards.toSet() }
        if (!ok) illegal = true   // 统计非法；下方仍按 decision 推进，避免卡死

        if (decision == null) {
            passes++
            if (passes >= 3) {
                val lead = topPlayer
                top = null; topPlayer = -1; passes = 0; cur = lead
            } else {
                cur = (cur + 1) % 4
            }
        } else {
            hand.removeAll(decision.cards)
            if (hand.isEmpty()) return Triple(cur, moves, illegal)
            top = decision; topPlayer = cur; passes = 0; cur = (cur + 1) % 4
        }
        moves++
        if (moves > 2000) return Triple(-1, moves, illegal)  // 异常保护
    }
}
