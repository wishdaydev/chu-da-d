package com.chudad.sim

import com.chudad.game.logic.AIDecision
import com.chudad.game.logic.CardRuleEngine
import com.chudad.game.logic.MasterSearch
import com.chudad.game.model.Card
import com.chudad.game.model.CardGroup
import com.chudad.game.model.CardType
import com.chudad.game.model.Suit
import com.chudad.game.model.createDeck
import kotlin.math.sqrt
import kotlin.random.Random
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * 锄大D 4 人仿真 harness（纯 JVM，配合 sim/run-sim.sh，完整用法见 sim/README.md）。
 *
 * 默认四家全部使用大师模式完美信息搜索 [MasterSearch] 决策，用于标定搜索强度与性能：
 *   - 各家胜率分布（均等牌力下应接近 25%，可观察搜索是否偏向某座位/先后手）
 *   - 平均回合数（反映搜索推进效率）
 *   - 非法走法次数（应恒为 0，验证搜索始终输出合法决策）
 *   - 平均每步搜索节点数/深度（把预算折算到别的设备时用，例如沙箱 → 手机）
 *
 * 用法: ./sim/run-sim.sh [games] [deadlineMs] [nodeLimit] [threads] [baseSeed]
 *   固定节点预算（推荐做 A/B）：SIM_NODES=120000 ./sim/run-sim.sh 100 0 0 4 7
 *
 * 环境变量（对手建模相关，均按需设置）：
 *   HUMAN_MODEL_SEAT   = 被当普通模式代理的座位（默认 -1=不建模，四家均用大师搜索）；设 0..3 则
 *                        该座位改用 AIDecision（即「真实人类」一方），其余 3 座用带 modelHuman 的大师搜索。
 *   HUMAN_MODEL_WEIGHT = 建模强度（默认 0.0）。1=完全信任（人类节点仅 AIDecision 单手），
 *                        0~1=软建模（按权重补充弱人类候选），<=0=不建模（其余 3 座退化为全量展开做对照）。
 *   HEURISTIC_SEATS    = 哪些座位用启发式（默认：仅 HUMAN_MODEL_SEAT 指定的那一个）
 *   SELFISH_SEATS      = 哪些座位用 selfish 对手模型（默认全部；线上即为全部）
 *   OPP_TOPK           = 对手模型保留候选数（默认 2）
 *   MAX_DEPTH          = 迭代深度上限（默认「自动」：按剩余牌数推导，等同线上；压到 8~12 可做浅搜索对照）
 *
 * 典型用法：
 *   全大师基线:            HUMAN_MODEL_SEAT=-1
 *   对照(人类代理+不建模):  HUMAN_MODEL_SEAT=0 HUMAN_MODEL_WEIGHT=0
 *   线上配置(w=0.8):        HUMAN_MODEL_SEAT=0 HUMAN_MODEL_WEIGHT=0.8
 *   3 大师 vs 1 启发式:     HEURISTIC_SEATS=0 SELFISH_SEATS=1,2,3
 */
/** id → rank 查表（从已出牌集合反推点数，供人类代理构造有限信息 unseenCounts） */
private val ID_TO_RANK_SIM = createDeck().associate { it.id to it.rank }

fun main(args: Array<String>) {
    val games = args.getOrNull(0)?.toIntOrNull() ?: 200
    val deadlineMs = args.getOrNull(1)?.toLongOrNull() ?: 50L
    // 节点上限：不传（或传 0）＝不限，与线上同档（600 万只是安全阀）；
    // 要做「固定节点预算」的确定性 A/B 时按需显式传入，如 SIM_NODES=120000
    val nodeLimit = args.getOrNull(2)?.toIntOrNull()?.takeIf { it > 0 } ?: MasterSearch.ONLINE_NODE_LIMIT
    val threads = (args.getOrNull(3)?.toIntOrNull() ?: 1).coerceAtLeast(1)
    val baseSeed = args.getOrNull(4)?.toLongOrNull() ?: 1L

    // 人类建模（对手建模）：把某个座位当普通模式 AIDecision 代理，其余座位开启 modelHuman。
    val humanSeat = System.getenv("HUMAN_MODEL_SEAT")?.toIntOrNull()?.coerceIn(-1, 3) ?: -1
    val humanWeight = System.getenv("HUMAN_MODEL_WEIGHT")?.toDoubleOrNull() ?: 0.0

    // 哪些座位用启发式出牌（其余用大师搜索）。
    // 未显式指定 HEURISTIC_SEATS 时退回旧语义：HUMAN_MODEL_SEAT 指定的座位即为唯一启发式座位。
    val heuristicSeats: Set<Int> =
        System.getenv("HEURISTIC_SEATS")?.split(',')
            ?.mapNotNull { it.trim().toIntOrNull() }?.filter { it in 0..3 }?.toSet()
            ?: if (humanSeat in 0..3) setOf(humanSeat) else emptySet()

    // 大师搜索的对手模型：selfish（默认，与线上一致：各家各自最优）｜paranoid（旧行为：三家联盟 MIN）
    val oppModelName = System.getenv("OPP_MODEL") ?: "selfish"
    val oppModel = if (oppModelName == "selfish") 1 else 0
    val oppTopK = System.getenv("OPP_TOPK")?.toIntOrNull() ?: 2
    // 迭代加深深度上限：不设＝MasterSearch 线上默认（按剩余牌数自动推导，保证不会早于终局截断）；
    // 压到 8~12 可做「浅搜索」对照，验证深度对结论的影响
    val maxDepth = System.getenv("MAX_DEPTH")?.toIntOrNull()
    // 按座位指定谁用 selfish：用于「混合对局」量化模型优势（不设则 OPP_MODEL 全局生效）
    val selfishSeats: Set<Int> = System.getenv("SELFISH_SEATS")?.split(',')
        ?.mapNotNull { it.trim().toIntOrNull() }?.filter { it in 0..3 }?.toSet()
        ?: if (oppModel == 1) setOf(0, 1, 2, 3) else emptySet()

    println(
        "锄大D 4人仿真: games=$games deadline=${deadlineMs}ms " +
            "nodeLimit=$nodeLimit threads=$threads baseSeed=$baseSeed " +
            "HUMAN_MODEL_SEAT=$humanSeat HUMAN_MODEL_WEIGHT=$humanWeight " +
            "HEURISTIC_SEATS=$heuristicSeats SELFISH_SEATS=$selfishSeats K=$oppTopK " +
            "MAX_DEPTH=${maxDepth ?: "自动"}"
    )

    val wins = IntArray(4)
    val totalMoves = AtomicLong(0)
    val illegal = AtomicInteger(0)
    val crashed = AtomicInteger(0)
    val finished = AtomicInteger(0)
    // 搜索实测量：平均每步节点/深度反映「预算实际花掉多少」，用于把结论折算到别的设备
    val totalNodes = AtomicLong(0)
    val totalDepth = AtomicLong(0)
    val totalSteps = AtomicLong(0)
    val deepest = AtomicInteger(0)
    val illegalSamples = java.util.Collections.synchronizedList(ArrayList<String>())
    val t0 = System.currentTimeMillis()

    val runOne: (Int) -> Unit = { idx ->
        val seed = baseSeed + idx * 2654435761L
        try {
            val r = playGame(
                seed, deadlineMs, nodeLimit, humanSeat, humanWeight,
                heuristicSeats, oppTopK, selfishSeats, maxDepth
            )
            synchronized(wins) { if (r.winner in 0..3) wins[r.winner]++ }
            totalMoves.addAndGet(r.moves.toLong())
            if (r.winner in 0..3) finished.incrementAndGet()
            totalNodes.addAndGet(r.totalNodes.toLong())
            totalDepth.addAndGet(r.totalDepth.toLong())
            totalSteps.addAndGet(r.searchSteps.toLong())
            if (r.maxDepth > deepest.get()) deepest.set(r.maxDepth)
            if (r.illegalSample != null) {
                illegal.incrementAndGet()
                if (illegalSamples.size < 3) illegalSamples.add(r.illegalSample)
            }
        } catch (e: Throwable) {
            crashed.incrementAndGet()
        }
    }

    // 固定大小线程池：此前是「每局起一个线程」，games=100 时会创建 100 个线程，
    // 远超 CPU 核数 → 每个决策的 15ms 墙钟预算被线程争抢严重稀释，搜索深度不稳、结果噪声放大。
    // 改为 threads 个常驻 worker 从原子计数取任务，并发度严格等于 threads。
    val workers = threads.coerceAtMost(games).coerceAtLeast(1)
    val nextIdx = AtomicInteger(0)
    val pool = (0 until workers).map {
        Thread {
            while (true) {
                val i = nextIdx.getAndIncrement()
                if (i >= games) break
                runOne(i)
            }
        }
    }
    pool.forEach { it.start() }
    pool.forEach { it.join() }

    val dt = System.currentTimeMillis() - t0
    val n = finished.get()
    val avg = if (n > 0) totalMoves.get().toDouble() / n else 0.0
    println("=== 结果 ===")
    for (p in 0..3) {
        val wr = if (n > 0) wins[p] * 100.0 / n else 0.0
        val se = if (n > 0) 100.0 * sqrt(0.25 * 0.75 / n) else 0.0
        println("玩家$p 胜率: ${wins[p]}/$n (${"%.1f".format(wr)}% ±${"%.1f".format(1.96 * se)})")
    }
    if (humanSeat in 0..3 && humanWeight > 0.0) {
        println("建模强度: weight=$humanWeight  seat=$humanSeat（其余 3 座对其实施人类建模）")
    } else if (humanSeat in 0..3) {
        println("对照: seat=$humanSeat 用 AIDecision 代理，其余 3 座不做人类建模（全量展开）")
    }
    println("平均回合数: ${"%.1f".format(avg)}  (完成局数 $n/$games, 异常 $crashed)")
    val steps = totalSteps.get()
    if (steps > 0) {
        val avgNodes = totalNodes.get().toDouble() / steps
        val avgDepth = totalDepth.get().toDouble() / steps
        val budgetTag = if (deadlineMs >= 100_000L) "固定节点 ${nodeLimit}节点" else "${deadlineMs}ms"
        println(
            "搜索量: 平均每步 ${"%.0f".format(avgNodes)} 节点 / ${"%.1f".format(avgDepth)} 层 " +
                "（最深 ${deepest.get()} 层，步数 $steps，预算 $budgetTag）"
        )
    }
    println("非法走法次数: ${illegal.get()}")
    illegalSamples.forEach { println("  非法样本: $it") }
    println("总耗时: ${dt}ms  (worker=$workers)")
}

/** 发牌：52 张洗牌后均分 4 家各 13 张；用种子保证可复现 */
private fun deal(seed: Long): Array<MutableList<Card>> {
    val deck = createDeck()
    deck.shuffle(Random(seed))
    return Array(4) { i -> deck.subList(i * 13, i * 13 + 13).toMutableList() }
}

/** 单局结果：赢家、回合数、首个非法走法样本（null 表示全程合法）+ 搜索实测量 */
private class GameResult(
    val winner: Int,
    val moves: Int,
    val illegalSample: String?,
    /** 走大师搜索的步数（启发式代理的步不计） */
    val searchSteps: Int,
    /** 上述步累计的搜索节点数 */
    val totalNodes: Long,
    /** 上述步累计的「完整跑完的深度」之和，除以 searchSteps 即平均深度 */
    val totalDepth: Long,
    /** 本局单步达到的最深迭代深度 */
    val maxDepth: Int
)

/**
 * 单局：
 *  - [humanSeat] 在 0..3 时，该座位用普通模式 [AIDecision] 当「真实人类」代理；
 *  - 其余座位用 [MasterSearch] 决策，并对 [humanSeat] 开启人类建模（[humanWeight]）。
 *  - [humanSeat] 为 -1 时四家均用大师搜索（全大师基线）。
 */
private fun playGame(
    seed: Long,
    deadlineMs: Long,
    nodeLimit: Int,
    humanSeat: Int,
    humanWeight: Double,
    heuristicSeats: Set<Int>,
    oppTopK: Int,
    selfishSeats: Set<Int>,
    maxDepth: Int?
): GameResult {
    val hands = deal(seed)
    // 真实开局：持 ♦3 者先出，且首手必须含 ♦3。
    // 此前固定座位 0 先出，等于让 0 号（含人类代理位）无条件拿先手，胜率对比被系统性污染。
    var cur = hands.indexOfFirst { h -> h.any { it.suit == Suit.DIAMOND && it.rank == 3 } }
    if (cur !in 0..3) cur = 0
    var top: CardGroup? = null
    var topPlayer = -1
    var passes = 0
    var moves = 0
    var playedMask = 0L
    var illegalSample: String? = null

    // 搜索实测量累加（反映「预算实际花掉多少」，用于跨设备折算）
    var steps = 0
    var nodesSum = 0L
    var depthSum = 0L
    var maxD = 0
    fun finish(winner: Int) = GameResult(winner, moves, illegalSample, steps, nodesSum, depthSum, maxD)

    while (true) {
        val hand = hands[cur]
        if (hand.isEmpty()) return finish(cur)

        // 首手（全场第一手）：必须含 ♦3
        val mustD3 = top == null && moves == 0

        // 人类建模实验：指定的「人类座位」用 AIDecision 代理；其余座位开启 modelHuman。
        val useProxy = cur in heuristicSeats
        val raw = if (useProxy) {
            aiDecideForSim(hands, cur, top, playedSet(playedMask))
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
                mustContainDiamond3 = mustD3,
                humanModelWeight = humanWeight,
                humanModelSeat = humanSeat,
                // 把「本局此前已出的牌」交给搜索：人类建模节点要靠它还原真实人类可见信息
                initialPlayedMask = playedMask,
                opponentModel = if (cur in selfishSeats) 1 else 0,
                opponentTopK = oppTopK,
                maxDepth = maxDepth
            ).also {
                steps++
                nodesSum += MasterSearch.lastSearchNodes
                val d = MasterSearch.lastSearchDepth
                depthSum += d
                if (d > maxD) maxD = d
            }
        }

        // 合法性校验（诊断用）：不合法时用「最小合法走法」推进，避免非法牌进入局面把后续统计带偏
        val legal = CardRuleEngine.findAllValidPlays(hand, top)
        val ok = if (raw == null) top != null
        else legal.any { it.type == raw.type && it.cards.toSet() == raw.cards.toSet() }
        var decision = raw
        if (!ok) {
            if (illegalSample == null) {
                illegalSample = "seat=$cur top=${top?.type ?: "自由出牌"} 手牌=${hand.size} " +
                    "决策=${raw?.let { "${it.type}(${it.cards.size}张)" } ?: "过牌"}"
            }
            decision = legal.minWithOrNull(compareBy({ it.mainRank }, { it.cards.size }))
        }
        // 自由出牌却无任何合法走法：局面已异常，终止本局（避免 cur=-1 后越界）
        if (decision == null && top == null) return finish(-1)

        if (decision == null) {
            passes++
            if (passes >= 3) {
                val lead = topPlayer
                top = null; topPlayer = -1; passes = 0; cur = lead
            } else {
                cur = (cur + 1) % 4
            }
        } else {
            for (c in decision.cards) {
                playedMask = playedMask or (1L shl c.id)
            }
            hand.removeAll(decision.cards)
            if (hand.isEmpty()) return finish(cur)
            top = decision; topPlayer = cur; passes = 0; cur = (cur + 1) % 4
        }
        moves++
        if (moves > 2000) return finish(-1)  // 异常保护
    }
}

/** 已出牌掩码 → 牌 id 集合（供人类代理构造有限信息） */
private fun playedSet(mask: Long): Set<Int> {
    if (mask == 0L) return emptySet()
    val s = HashSet<Int>()
    var m = mask
    while (m != 0L) {
        s.add(m.countTrailingZeroBits())
        m = m and (m - 1)
    }
    return s
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
    val d = AIDecision.decide(
        hand = hand,
        lastPlay = top,
        opponentCardCounts = oppCounts,
        unseenCounts = unseen,
        requireDiamond3 = isGameStart,
        mustTopBig = topBig
    )
    // 顶大规则兜底：下家报单且桌上是单张时，能压就必须出最大单张（真实引擎由 humanPass 拦截）。
    // 代理若在这里「过牌」，等于让人类享受规则外的宽松，建模实验结论会失真。
    if (topBig && top != null && top.type == CardType.SINGLE) {
        val maxCard = hand.maxWithOrNull(compareBy({ it.rank }, { it.suit.order }))
        if (maxCard != null) {
            val single = CardGroup(CardType.SINGLE, maxCard.rank, 1, listOf(maxCard), maxCard.suit.order)
            if (CardRuleEngine.isValidPlay(single, top)) return single
        }
    }
    return d
}
