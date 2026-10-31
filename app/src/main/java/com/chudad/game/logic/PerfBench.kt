package com.chudad.game.logic

import com.chudad.game.model.Card
import com.chudad.game.model.CardGroup
import com.chudad.game.model.Suit
import com.chudad.game.model.createDeck
import kotlin.random.Random

/**
 * 搜索性能基准（App 内「设置 → 性能测试」与 sim/PerfMain 共用同一份实现）。
 *
 * 目的：测出「给定时间预算，本机能搜多少节点、多少层」，从而把仿真结论从一台机器折算到另一台。
 *
 * 为什么用节点数而不是耗时做基线：节点数与 CPU、GC、后台负载无关，是可移植的算力度量。
 * 时间预算下同样 300ms，开局能搜约 9.6 万节点、中局能搜约 16 万（走法生成成本不同），
 * 所以「某档预算 ≈ 多少节点」必须实测，不能按 CPU 主频推算。
 *
 * 口径（与 sim 四家全大师基线一致，保证手机与沙箱数字可直接相比）：
 *  - 标准 52 张开局，四家各 13 张；
 *  - 把持 ♦3 的手牌整体换到 0 号位，使「0 号位先出、且首手必须含 ♦3」恒成立；
 *  - 无人类建模（humanModelSeat = -1），对手模型用线上默认（各家各自最优）；
 *  - 每组只跑「第一手领出」这一次决策，取该次搜索的节点数与完整跑完的深度；
 *  - 先跑 1 组预热并丢弃（JIT / ART 需要热点才达到稳定速度，否则首组会明显偏慢）。
 */
object PerfBench {

    /** 默认组数：10 组，噪声已足以看出量级（每组是独立发牌） */
    const val DEFAULT_ROUNDS = 10

    /** 预热用种子，固定值，保证预热局面稳定 */
    private const val WARMUP_SEED = 20260101L

    /** 单组结果 */
    data class Sample(
        val index: Int,
        val seed: Long,
        val nodes: Int,
        val depth: Int,
        val elapsedMs: Long,
        val move: String,
        /** 是否跑满深度上限（正常开局恒为 false：时间先到） */
        val depthCapped: Boolean = false
    )

    /** 汇总报表 */
    class Report(
        val budgetMs: Long,
        val samples: List<Sample>,
        /** 调用方附加的设备信息行（App 端填机型/Android 版本，sim 端填 CPU 核数） */
        val deviceInfo: String = ""
    ) {
        val avgNodes: Int get() = if (samples.isEmpty()) 0 else samples.sumOf { it.nodes } / samples.size
        val medNodes: Int get() = median(samples.map { it.nodes })
        val minNodes: Int get() = samples.minOfOrNull { it.nodes } ?: 0
        val maxNodes: Int get() = samples.maxOfOrNull { it.nodes } ?: 0
        val avgDepth: Double get() = if (samples.isEmpty()) 0.0 else samples.sumOf { it.depth }.toDouble() / samples.size
        val avgElapsedMs: Double get() = if (samples.isEmpty()) 0.0 else samples.sumOf { it.elapsedMs }.toDouble() / samples.size

        /**
         * 单位时间搜索量：本机算力的核心指标（节点/毫秒）。
         * 用「节点总和 / 耗时总和」而非「均值之比」，后者会被单组异常放大。
         */
        val nodesPerMs: Double
            get() {
                val totalMs = samples.sumOf { it.elapsedMs }
                return if (totalMs <= 0L) 0.0 else samples.sumOf { it.nodes }.toDouble() / totalMs
            }

        /** 每 1 万节点所需毫秒数，便于心算折算 */
        val msPer10kNodes: Double get() = if (nodesPerMs <= 0.0) 0.0 else 10_000.0 / nodesPerMs

        /** 可复制的纯文本报表：App 端弹窗展示 + 一键复制给开发者做机型对齐 */
        fun toText(): String {
            val sb = StringBuilder()
            sb.append("== 锄大D 搜索性能基准 ==\n")
            if (deviceInfo.isNotBlank()) sb.append(deviceInfo.trimEnd()).append('\n')
            sb.append("预算 ${budgetMs}ms × ${samples.size} 组（已丢弃 2 组预热）\n")
            sb.append("\n")
            sb.append(String.format("%2s  %8s  %4s  %5s  %s\n", "#", "节点", "深度", "耗时", "首手"))
            for (s in samples) {
                sb.append(
                    String.format(
                        "%2d  %8d  %4d  %4dms  %s\n",
                        s.index, s.nodes, s.depth, s.elapsedMs, s.move
                    )
                )
            }
            sb.append("\n")
            sb.append("---- 汇总 ----\n")
            sb.append(String.format("平均节点 %d（中位 %d，范围 %d~%d）\n", avgNodes, medNodes, minNodes, maxNodes))
            sb.append(String.format("平均深度 %.1f 层（1 层=一手，4 层≈一圈）\n", avgDepth))
            sb.append(String.format("平均耗时 %.0fms\n", avgElapsedMs))
            sb.append(String.format("搜索速率 %.0f 节点/ms（每万节点 ≈ %.1fms）\n", nodesPerMs, msPer10kNodes))
            val capped = samples.count { it.depthCapped }
            sb.append(
                if (capped == 0) "深度上限：未触及（时间先到，时间预算是唯一瓶颈）\n"
                else "深度上限：$capped/${samples.size} 组跑满（这些组已算到终局）\n"
            )
            return sb.toString()
        }
    }

    /**
     * 跑基准。
     * @param budgetMs 单步思考时间预算（毫秒），即线上「ai最大思考时间」对应的每步上限
     * @param rounds 组数，默认 10
     * @param onProgress 进度回调（已完成组数, 总组数），供 UI 显示；在调用线程上同步触发
     */
    fun run(
        budgetMs: Long,
        rounds: Int = DEFAULT_ROUNDS,
        onProgress: ((Int, Int) -> Unit)? = null
    ): Report {
        val budget = budgetMs.coerceIn(10L, 60_000L)
        val n = rounds.coerceIn(1, 50)

        // 预热：不计入统计。首组往往因 JIT/ART 未编译热点而偏慢，混进均值会让「手机 vs 沙箱」失真。
        // 用固定 400ms×2 组（而非「预算×2」）：短预算档位若按预算预热，热度不够会测出明显偏低的速率，
        // 让不同档位之间不可比。固定预热成本约 0.8s，可忽略。
        val warmBudget = maxOf(budget, 400L)
        repeat(2) { i -> solveOnce(dealForSeat0(WARMUP_SEED + i), warmBudget) }

        val list = ArrayList<Sample>(n)
        for (i in 1..n) {
            val seed = 1000L + i
            val hands = dealForSeat0(seed)
            val t0 = System.nanoTime()
            val g = solveOnce(hands, budget)
            val ms = (System.nanoTime() - t0) / 1_000_000
            list.add(
                Sample(
                    index = i,
                    seed = seed,
                    nodes = MasterSearch.lastSearchNodes,
                    depth = MasterSearch.lastSearchDepth,
                    elapsedMs = ms,
                    move = describe(g),
                    depthCapped = MasterSearch.lastSearchDepthCapped
                )
            )
            onProgress?.invoke(i, n)
        }
        return Report(budget, list)
    }

    /** 单次领出决策：0 号位先出、首手含 ♦3、不建人类模型、对手模型取线上默认 */
    private fun solveOnce(hands: Map<Int, List<Card>>, budgetMs: Long): CardGroup? =
        MasterSearch.solve(
            hands = hands,
            myIndex = 0,
            topGroup = null,
            topPlayer = -1,
            passes = 0,
            current = 0,
            deadlineMs = budgetMs,
            // 与线上同档：600 万节点只是安全阀，基准测的是「预算内能搜多少」，不应被节点先掐断
            nodeLimit = MasterSearch.ONLINE_NODE_LIMIT,
            mustContainDiamond3 = true,
            humanModelSeat = -1,
            humanModelWeight = 0.0
        )

    /**
     * 标准开局发牌，并把持 ♦3 的手牌整体换到 0 号位。
     * 换座位而不是改牌：牌局完全等价，但「谁先出」被固定下来，10 组之间口径一致，
     * 也让本基准与 sim（0 号位先出）的统计直接可比。
     */
    private fun dealForSeat0(seed: Long): Map<Int, List<Card>> {
        val deck = createDeck().toMutableList()
        deck.shuffle(Random(seed))
        val hands = Array(4) { i -> deck.subList(i * 13, i * 13 + 13).toMutableList() }
        val d3Seat = hands.indexOfFirst { h -> h.any { it.suit == Suit.DIAMOND && it.rank == 3 } }
        if (d3Seat > 0) {
            val tmp = hands[0]
            hands[0] = hands[d3Seat]
            hands[d3Seat] = tmp
        }
        return (0..3).associateWith { hands[it].toList() }
    }

    private fun describe(g: CardGroup?): String =
        if (g == null) "过牌" else "${g.type}(${g.cards.size}张)"

    private fun median(xs: List<Int>): Int {
        if (xs.isEmpty()) return 0
        val s = xs.sorted()
        return s[s.size / 2]
    }
}
