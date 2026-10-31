package com.chudad.sim

import com.chudad.game.logic.AIDecision
import com.chudad.game.logic.CardRuleEngine
import com.chudad.game.logic.MasterSearch
import com.chudad.game.model.createDeck
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
 *
 * 人类建模调参（对手建模）环境变量：
 *   HUMAN_MODEL_SEAT   = 被当普通模式代理的座位（默认 -1=不建模，四家均用大师搜索）；设 0..3 则
 *                        该座位改用 AIDecision（即「真实人类」一方），其余 3 座用带 modelHuman 的大师搜索。
 *   HUMAN_MODEL_WEIGHT = 建模强度（默认 0.0）。1=完全信任（人类节点仅 AIDecision 单手），
 *                        0~1=软建模（按权重补充弱人类候选），<=0=不建模（其余 3 座退化为全量展开做对照）。
 *   典型用法：
 *     全大师基线:           HUMAN_MODEL_SEAT=-1
 *     对照(人类代理+不建模): HUMAN_MODEL_SEAT=0 HUMAN_MODEL_WEIGHT=0
 *     建模寻优扫描:          HUMAN_MODEL_SEAT=0 HUMAN_MODEL_WEIGHT=1  （再扫 0.25/0.5/0.75）
 */
/** id → rank 查表（从已出牌集合反推点数，供人类代理构造有限信息 unseenCounts） */
private val ID_TO_RANK_SIM = createDeck().associate { it.id to it.rank }

fun main(args: Array<String>) {
    val games = args.getOrNull(0)?.toIntOrNull() ?: 200
    val deadlineMs = args.getOrNull(1)?.toLongOrNull() ?: 50L
    val nodeLimit = args.getOrNull(2)?.toIntOrNull() ?: 150_000
    val threads = (args.getOrNull(3)?.toIntOrNull() ?: 1).coerceAtLeast(1)
    val baseSeed = args.getOrNull(4)?.toLongOrNull() ?: 1L

    // 调参支持：CTRL_W=治疗组权重（默认 15），CTRL_W_BASE=基线组权重（默认 null=与 CTRL_W 相同做对称仿真），
    // TREAT_SEATS=使用 CTRL_W 的座位（逗号分隔，默认 "0"）。用于非对称对比：带控制权项的一方 vs 旧版(=0)。
    val ctrlW = System.getenv("CTRL_W")?.toIntOrNull() ?: 15
    val ctrlWBase = System.getenv("CTRL_W_BASE")?.toIntOrNull()
    val treatSeats = (System.getenv("TREAT_SEATS") ?: "0").split(',').mapNotNull { it.trim().toIntOrNull() }.toSet()

    // 人类建模（对手建模）：把某个座位当普通模式 AIDecision 代理，其余座位开启 modelHuman。
    val humanSeat = System.getenv("HUMAN_MODEL_SEAT")?.toIntOrNull()?.coerceIn(-1, 3) ?: -1
    val humanWeight = System.getenv("HUMAN_MODEL_WEIGHT")?.toDoubleOrNull() ?: 0.0

    println(
        "锄大D 4人仿真: games=$games deadline=${deadlineMs}ms " +
            "nodeLimit=$nodeLimit threads=$threads baseSeed=$baseSeed " +
            "CTRL_W=$ctrlW CTRL_W_BASE=$ctrlWBase TREAT_SEATS=$treatSeats " +
            "HUMAN_MODEL_SEAT=$humanSeat HUMAN_MODEL_WEIGHT=$humanWeight"
    )

    val wins = IntArray(4)
    val totalMoves = AtomicLong(0)
    val illegal = AtomicInteger(0)
    val t0 = System.currentTimeMillis()

    val runOne: (Int) -> Unit = { idx ->
        val seed = baseSeed + idx * 2654435761L
        val (winner, moves, bad) = playGame(
            seed, deadlineMs, nodeLimit, ctrlW, ctrlWBase, treatSeats, humanSeat, humanWeight
        )
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

/**
 * 单局：
 *  - [humanSeat] 在 0..3 时，该座位用普通模式 [AIDecision] 当「真实人类」代理；
 *  - 其余座位用 [MasterSearch] 决策，并对 [humanSeat] 开启人类建模（[humanWeight]）。
 *  - [humanSeat] 为 -1 时四家均用大师搜索（全大师基线，与历史行为一致）。
 * 返回 (winner, moves, illegalFlag)
 */
private fun playGame(
    seed: Long,
    deadlineMs: Long,
    nodeLimit: Int,
    ctrlW: Int,
    ctrlWBase: Int?,
    treatSeats: Set<Int>,
    humanSeat: Int,
    humanWeight: Double
): Triple<Int, Int, Boolean> {
    val hands = deal(seed)
    var cur = 0
    var top: CardGroup? = null
    var topPlayer = -1
    var passes = 0
    var moves = 0
    var illegal = false
    val played = mutableSetOf<Int>()   // 已出过的牌 id（用于给人类代理构造有限信息 unseenCounts）

    while (true) {
        val hand = hands[cur]
        if (hand.isEmpty()) return Triple(cur, moves, illegal)

        // 非对称调参：治疗组座位用 CTRL_W，基线组用 CTRL_W_BASE（未设则全部同用 CTRL_W）
        val w = if (ctrlWBase != null && cur !in treatSeats) ctrlWBase else ctrlW

        // 人类建模实验：指定的「人类座位」用 AIDecision 当真实对手；其余座位开启 modelHuman。
        val useProxy = humanSeat in 0..3 && cur == humanSeat
        val decision = if (useProxy) {
            aiDecideForSim(hands, cur, top, played)
        } else {
            MasterSearch.solve(
                hands = hands.mapIndexed { i, h -> i to h.toList() }.toMap(),
                myIndex = cur,
                topGroup = top,
                topPlayer = topPlayer,
                passes = passes,
                current = cur,
                deadlineMs = deadlineMs,
                nodeLimit = nodeLimit,
                ctrlWeight = w,
                humanModelWeight = if (useProxy) 0.0 else humanWeight,
                humanModelSeat = if (useProxy) -1 else humanSeat
            )
        }
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
            for (c in decision.cards) played.add(c.id)
            hand.removeAll(decision.cards)
            if (hand.isEmpty()) return Triple(cur, moves, illegal)
            top = decision; topPlayer = cur; passes = 0; cur = (cur + 1) % 4
        }
        moves++
        if (moves > 2000) return Triple(-1, moves, illegal)  // 异常保护
    }
}

/** 仿真中把某座位当普通模式 [AIDecision] 代理（人类建模里的「真实人类」一方）
 *  [playedIds] 为已出过的牌 id 集合，用于让代理只掌握有限信息（自己手牌 + 已出牌），
 *  而非完美信息全知——更贴近真实人类，使「建模人类」的实验结论可信。 */
private fun aiDecideForSim(
    hands: Array<MutableList<Card>>,
    cur: Int,
    top: CardGroup?,
    playedIds: Set<Int>
): CardGroup? {
    val hand = hands[cur]
    val oppCounts = IntArray(3)
    val own = IntArray(16)
    for (c in hand) own[c.rank]++
    val playedCnt = IntArray(16)
    for (id in playedIds) playedCnt[ID_TO_RANK_SIM[id] ?: 0]++
    // 未见张数 = 各点数总数(4) − 自己手中 − 已出过
    val unseen = IntArray(16)
    for (r in 3..15) unseen[r] = 4 - own[r] - playedCnt[r]
    var k = 0
    for (i in 0..3) {
        if (i == cur) continue
        oppCounts[k++] = hands[i].size
    }
    val topBig = hands[(cur + 1) % 4].size == 1
    val isGameStart = top == null && hands.all { it.size == 13 }
    return AIDecision.decide(
        hand = hand,
        lastPlay = top,
        opponentCardCounts = oppCounts,
        unseenCounts = unseen,
        requireDiamond3 = isGameStart,
        mustTopBig = topBig
    )
}
