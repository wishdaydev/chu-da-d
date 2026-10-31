package com.chudad.game.logic

import com.chudad.game.model.Card
import com.chudad.game.model.CardGroup
import com.chudad.game.model.CardType
import com.chudad.game.model.Suit
import com.chudad.game.model.createDeck

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
 *   用 (对手最小手数 − 我的手数) 与 (对手最小张数 − 我的张数) 合成从我视角的值；
 *   并叠加「控制权项」（Level 1 + 2）：静态手牌强度（2 / 同花顺 / 四带一）+ 当前出牌权
 *   （自由领出 / 跟牌且能压制夺取领出权），对手取最坏者，用于缓解联盟建模导致的过度防御。
 *
 * 走法生成直接复用 [CardRuleEngine.findAllValidPlays]（含同花顺/四带一/葫芦/同花五等完整牌型，
 * 不裁剪候选集），保证搜索不会因候选集受限而漏掉最优解。
 *
 * 性能：辅以置换表（TT）——同一逻辑局面（四家手牌 + 桌面 top + 出牌权）会从大量不同出牌顺序中反复到达
 * （尤其连续 3 次过牌后领出权回到 topPlayer），TT 令其只搜索一次，并缓存该局面下的最优走法用于走法排序，
 * 在固定思考时间内显著放大有效搜索深度。
 */
internal object MasterSearch {

    private const val WIN = 1_000_000          // 终局胜负值（带 ply 偏移后仍在 ±WIN 内）
    private const val INF = 2_000_000          // alpha-beta 初始边界

    /**
     * 迭代加深的深度上限由「牌局长度」自动推导，而不是拍一个固定常数。
     *
     * 牌局本身是有限长的：一手出牌最多牵出 3 次过牌，即 1 张牌最多对应 [PLY_PER_CARD] 层；
     * 于是「从当前局面到终局」的 ply 上界 = 剩余总牌数 × [PLY_PER_CARD]。
     * 只要深度上限不小于这个上界，撞上限的那一层必然已经覆盖到终局（评估是精确胜负，
     * 不是被截断的启发值），深度上限就不会在「还没算完」的时候偷走棋力。
     *
     * 因此它只是防递归过深的安全阀，时间/节点预算才是强度约束。
     * 实测：5 秒预算下开局也只能跑到 33~37 层，离上界很远；残局牌少时上界自动收紧，
     * 也不会因为「上限只有 64」而被平白截断。
     * 仿真可用 [solve] 的 maxDepth 参数单独覆盖（如压低到 8 层做快速对照）。
     */
    private const val PLY_PER_CARD = 4

    /**
     * 深度上限的绝对硬顶（防失控递归）。
     *
     * 取 256 而不是 128：满场 52 张推导出的自然上界为 212 层，硬顶必须在其之上，
     * 否则中后盘（实测 50ms 就能跑到 124 层）会被硬顶截断。
     * 栈开销实测：1MB 栈可跑通 124 层且无溢出，线上搜索线程给 8MB，
     * 212 层约需 1.7MB，余量约 4 倍。
     */
    private const val MAX_DEPTH_CAP = 256

    /**
     * 线上默认节点预算（安全阀，不是强度参数）。
     *
     * 实测参考：沙箱 5 秒约 227 万节点，手机同预算约为其 1/3~1/5；600 万 ≈ 沙箱 13 秒的量，
     * 即默认 2~5 秒档远不会触及，只有把「最大思考时间」拉到 30 秒以上才可能先行截断。
     * 保留它只为防止搜索失控，线上一律用 [GameEngine.masterDeadlineMs] 调强弱。
     */
    const val ONLINE_NODE_LIMIT = 6_000_000

    /** 手数竞速评估中各分量的权重（值需落在 (-WIN/2, WIN/2) 内，避免淹没终局值） */
    private const val W_PLAYS = 600
    private const val W_CARDS = 60

    /**
     * 控制权项权重（Level 1 + 2）。用来缓解 me-vs-field 联盟建模导致的「过度防御」：
     * 让 AI 认识到「我握有 2 / 同花顺 / 四带一、当前掌控出牌权」时其实很强，不必盲目拆大牌去减手数。
     * 其值需使控制权差（myCtrl − 最坏对手 oppCtrl）最大约 ±3000~5000，作为手数竞速的次级引导，
     * 不淹没主项也不被主项完全覆盖。单张 2 贡献 CTRL_TWO×W_CTRL = 600，约等于一手之差。
     */
    private const val W_CTRL = 15
    private const val LEAD_RIGHT_BONUS = 50      // 自由领出（top==null 且轮到我）：直接掌控节奏
    private const val SEIZE_BONUS = 40          // 跟牌且我能压制领出方、夺取出牌权
    private const val CTRL_TWO = 40             // 每张 2 的控场资本（任何牌型都最大、绝对不可压）
    private const val CTRL_STRAIGHT_FLUSH = 30  // 同花顺（顶级牌型）
    private const val CTRL_FOUR_WITH_ONE = 25   // 四带一（顶级牌型）

    /**
     * 当前搜索使用的控制权项权重（按线程隔离），可由 [solve] 的 [ctrlWeight] 参数临时覆盖，
     * 以支持多线程仿真中不同座位使用不同权重做非对称对比（不影响默认行为与线上逻辑）。
     */
    private val ctrlWeightTL = ThreadLocal<Int>()

    /**
     * 人类建模强度默认值（0=关闭建模、1=完全信任 [AIDecision] 单手）。
     *
     * 线上取 0.8 的「软建模」：人类节点保留「代理走法 + 少量次优候选」，
     * 既利用「真人多数情况下按启发式思路出牌」的先验来收紧搜索树，
     * 又留出人类不按套路出牌时的容错空间（硬 1.0 会在真人反制时被反杀）。
     */
    private const val DEFAULT_HUMAN_MODEL_WEIGHT = 0.8

    /**
     * 人类建模（对手建模）配置，按线程隔离，可由 [solve] 参数覆盖。
     * - [humanModelWeightTL]：建模强度。0（或关闭）表示退化为全量展开、完全不影响现行逻辑；
     *   1 表示完全信任普通模式 [AIDecision]，该人类节点仅生成其启发式走法（单子节点 MIN），
     *   alpha-beta 语义仍成立；0~1 之间为「软建模」，在 [AIDecision] 走法基础上按权重补充若干「弱人类」候选。
     *   未指定时取 [DEFAULT_HUMAN_MODEL_WEIGHT]。
     * - [humanModelSeatTL]：被建模为普通模式玩家的座位（-1 表示不建模）。
     *   其它座位的大师搜索会把该座位当代理，只生成其启发式走法而非穷举。
     */
    private val humanModelWeightTL = ThreadLocal<Double>()
    private val humanModelSeatTL = ThreadLocal<Int>()

    /**
     * 对手模型（线上默认 [OPP_SELFISH]）。
     * - [OPP_PARANOID]：其余三家视为一个「联盟」，统一按 MIN 节点取「对我最不利」的走法。
     *   四人各自为战时这个假设过于悲观：对手并不合力坑我，搜索会因此过度防御。
     * - [OPP_SELFISH]：每家「各自最优」——对手节点按**该对手自己视角**的评估选走法
     *   （保留前 K 个候选，K=[oppTopKTL]），节点值仍按我的视角取最坏情况。
     *   即「他打他自己的算盘，我按其中对我最不利的那条做打算」，是多智能体搜索里
     *   max^n / 贪心 best-response 的轻量近似，比联盟假设更贴近真实对局。
     */
    private const val OPP_PARANOID = 0
    private const val OPP_SELFISH = 1
    private val oppModelTL = ThreadLocal<Int>()
    private val oppTopKTL = ThreadLocal<Int>()

    /**
     * 人类建模节点的决策缓存：单次 solve 内按局面键命中，避免同一人类节点被反复展开时
     * 重复跑 [AIDecision] + 全量走法枚举（深层搜索里这类重复极多）。
     * 用 ThreadLocal 隔离，仿真多线程各用各的表。
     */
    private val humanNodeCacheTL = ThreadLocal<HashMap<TransKey, HumanNode>>()

    /**
     * 最近一次 [solve] 的搜索实测量（按线程隔离，仅诊断/性能基准用，不参与决策）。
     *
     * 存在意义：让「预算 → 实际搜索量」可观测。时间预算下同样 300ms，开局与中局能搜到的
     * 节点数相差近 1.7 倍（走法生成成本不同），只有测出节点数才能把某个设备/某个档位
     * 折算到另一个设备（例如把沙箱仿真折算到手机真机）。
     */
    private val lastNodesTL = ThreadLocal<Int>()
    private val lastDepthTL = ThreadLocal<Int>()
    private val depthCappedTL = ThreadLocal<Boolean>()

    /** 最近一次 solve 的搜索节点数（未开始搜索时为 0） */
    val lastSearchNodes: Int get() = lastNodesTL.get() ?: 0

    /** 最近一次 solve 完整跑完的迭代深度（层 = ply，4 层约一圈） */
    val lastSearchDepth: Int get() = lastDepthTL.get() ?: 0

    /**
     * 最近一次 solve 是否跑满了深度上限（而非被时间/节点掐断）。
     *
     * 默认深度上限按剩余牌数推导，正常局面下时间先到、此值为 false；
     * 残局牌少时上限自动收紧，为 true 即代表「该次搜索已经算到终局」，不是被截断。
     */
    val lastSearchDepthCapped: Boolean get() = depthCappedTL.get() ?: false

    /** 人类建模节点缓存条目：[aiMove] 为代理选中走法，[fullMoves] 为该局面全部合法走法（软建模用） */
    private class HumanNode(val aiMove: Move, val fullMoves: List<Move>)

    /** A-2-3-4-5：A 作 1 时的最小顺子（点数序列，14=A、15=2） */
    private val A2345 = listOf(3, 4, 5, 14, 15)

    /** 单张牌的位掩码（id ∈ 0..51，适配 64 位） */
    private fun cardMask(card: Card): Long = 1L shl card.id

    /** id → rank 查表（从「已出牌位掩码」反推点数，用于构造有限信息 unseenCounts） */
    private val ID_TO_RANK: IntArray = run {
        val a = IntArray(52)
        for (c in createDeck()) a[c.id] = c.rank
        a
    }

    /** 单手牌的位掩码（按 card.id 置位，与顺序无关，直接作为局面键的可规范分量） */
    private fun handMask(hand: List<Card>): Long {
        var m = 0L
        for (c in hand) m = m or cardMask(c)
        return m
    }

    /** 当前局面的规范键：四家手牌掩码 + 桌面 top 掩码 + 元信息（cur/topPlayer/passes） */
    private fun stateKey(state: State): TransKey {
        val topMask = state.top?.cards?.fold(0L) { acc, c -> acc or cardMask(c) } ?: 0L
        val curBits = (state.cur and 3).toLong()
        val topBits = (((state.topPlayer + 1) and 7).toLong() shl 3)
        val passBits = ((state.passes and 7).toLong() shl 6)
        val meta = curBits or topBits or passBits
        return TransKey(
            handMask(state.hands[0]), handMask(state.hands[1]),
            handMask(state.hands[2]), handMask(state.hands[3]),
            topMask, meta
        )
    }

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
        val myIndex: Int,
        var cur: Int,
        var top: CardGroup?,
        var topPlayer: Int,
        var passes: Int,
        var playedMask: Long = 0L
    )

    // 手数评估缓存（纯函数，按手牌位掩码命中；ConcurrentHashMap 以支持仿真多线程）
    private val minPlaysCache = java.util.concurrent.ConcurrentHashMap<Long, Int>()

    /**
     * 清空手数评估缓存，由 [GameEngine.startNewGame] 在每局发牌前调用。
     *
     * 缓存键是「某玩家当前手牌集合」的 52 位掩码，而新一局发牌完全不同，
     * 旧局的条目对新局几乎零命中——留着只占内存。因此把清空时机固定在
     * 局与局之间：既不丢任何有效条目（本局条目在下一局本就无用），
     * 也避免容量上限在某一局中途触发清空、把该局已算好的命中全部丢掉。
     */
    fun clearCache() {
        minPlaysCache.clear()
    }

    /**
     * 缓存容量兜底上限：满即整体清空。
     * 正常情况下每局条目数在千级，开局清空已足以控制内存；
     * 此上限仅防止单局内出现极端枚举时的无界增长。
     */
    private const val MIN_PLAYS_CACHE_MAX = 20_000

    // ===== 置换表（Transposition Table）=====
    // 同一逻辑局面会从大量不同出牌顺序中反复到达（过牌 3 次后领出权回到 topPlayer，多条历史路径
    // 漏斗式汇入相同「某人自由领出 + 固定手牌分布」子局面）。TT 令这些重复子树只搜索一次，并缓存
    // 「该局面下的最优走法」用于走法排序，显著放大 alpha-beta 剪枝、在相同思考时间内算得更深。
    // 作用域为单次 solve（每次决策新建、决策结束后随局部变量回收）：既避免跨决策污染，
    // 又能在迭代加深的层间复用（浅层深结果喂给深层），同时天然线程安全（多线程仿真各用各的局部表）。

    /** 置换表 bound 标志：条目值相对进入该节点时的原始窗口 [alphaIn, betaIn] 而言 */
    private const val TT_EXACT = 0   // 精确值（命中即直接返回）
    private const val TT_LOWER = 1   // 下界（真实值 ≥ value）：用于 fail-high 快速返回
    private const val TT_UPPER = 2   // 上界（真实值 ≤ value）：用于 fail-low 快速返回

    /** 单局决策内置换表容量兜底：满即整体清空（仅丢复用、不返回错误值），防止 60s 内内存无界增长 */
    private const val TT_CACHE_MAX = 200_000

    /** 置换表条目 */
    private data class TTEntry(val value: Int, val flag: Int, val depth: Int, val bestMove: Move?)

    /** 局面键（规范形式，与出牌顺序无关）：四家手牌掩码 + 桌面 top 掩码 + 元信息（cur/topPlayer/passes） */
    private data class TransKey(
        val h0: Long, val h1: Long, val h2: Long, val h3: Long,
        val top: Long, val meta: Long
    )

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
        ctrlWeight: Int? = null,
        humanModelWeight: Double? = null,
        humanModelSeat: Int? = null,
        initialPlayedMask: Long = 0L,
        opponentModel: Int? = null,
        opponentTopK: Int? = null,
        maxDepth: Int? = null,
        cancelled: () -> Boolean = { false }
    ): CardGroup? {
        ctrlWeightTL.set(ctrlWeight ?: W_CTRL)
        humanModelWeightTL.set(humanModelWeight ?: DEFAULT_HUMAN_MODEL_WEIGHT)
        humanModelSeatTL.set(humanModelSeat ?: -1)
        oppModelTL.set(opponentModel ?: OPP_SELFISH)
        oppTopKTL.set(opponentTopK ?: 2)
        // 先清零：本次若提前返回（无手牌/无走法），调用方读到的必须是 0 而不是上一次的残留值
        lastNodesTL.set(0)
        lastDepthTL.set(0)
        depthCappedTL.set(false)
        val myHand = hands[myIndex] ?: return null
        if (myHand.isEmpty()) return null

        val state = State(
            hands = Array(4) { i -> hands[i]?.toMutableList() ?: mutableListOf() },
            myIndex = myIndex,
            cur = current,
            // 本局此前已出的牌：人类建模节点要靠它还原「真实人类看到的信息」。
            // 若从 0 起算，搜索会把已被打出的牌也算作「未见」，人类模型会误判某点数仍有存货。
            playedMask = initialPlayedMask,
            top = if (topGroup?.type == CardType.INVALID) null else topGroup,
            topPlayer = topPlayer,
            passes = passes
        )

        val rawMoves = generateMoves(state, myIndex, forbidTopBigPass = true)
        if (rawMoves.isEmpty()) return null   // 不应发生：自由出牌必有走法
        // 流程性约束先落到根走法集上，使搜索结果本身就是合法的一手
        val rootMoves = applyRootConstraints(rawMoves, myHand, mustContainDiamond3, mustTopBig)

        // 深度上限：默认按「剩余牌数能撑出的最长牌局」推导，保证上限不会早于终局生效；
        // 仿真仍可用 maxDepth 显式压低做「浅搜索」对照。
        val remainingCards = state.hands.sumOf { it.size }
        val naturalDepth = remainingCards * PLY_PER_CARD + PLY_PER_CARD
        val depthCap = (maxDepth ?: naturalDepth).coerceIn(1, MAX_DEPTH_CAP)

        val deadline = System.currentTimeMillis() + deadlineMs
        val counter = longArrayOf(0L)
        // 置换表：单次决策内有效，随 solve 返回后回收；迭代加深层间复用、跨决策不污染、天然线程安全
        val tt = HashMap<TransKey, TTEntry>()
        // 人类建模节点缓存（仅在「建模开启且确实指定了座位」时建立）；本次决策结束前清掉
        if ((humanModelWeightTL.get() ?: 0.0) > 0.0 &&
            (humanModelSeatTL.get() ?: -1) in 0..3
        ) humanNodeCacheTL.set(HashMap())

        var bestMove: Move? = rootMoves.first()
        // 根走法顺序只与牌面有关，排一次即可；后续层把上一层最优手提到首位（PV 优先）
        var rootOrdered = orderMoves(state, myIndex, rootMoves, isMax = true)
        var completedDepth = 0

        for (depth in 1..depthCap) {
            if (cancelled()) break
            var bestVal = -INF
            var localBest: Move? = null
            var alpha = -INF

            for (mv in rootOrdered) {
                if (System.currentTimeMillis() > deadline || counter[0] > nodeLimit || cancelled()) break
                val undo = applyMove(state, myIndex, mv)
                val v = when {
                    undo.winner == myIndex -> WIN
                    undo.winner >= 0 -> -WIN
                    else -> search(state, myIndex, alpha, INF, depth - 1, 1, deadline, counter, nodeLimit, cancelled, tt)
                }
                undoMove(state, undo)

                if (v > bestVal) { bestVal = v; localBest = mv }
                if (bestVal > alpha) alpha = bestVal
            }

            // 仅采纳完整跑完当前深度的结果
            if (System.currentTimeMillis() <= deadline && counter[0] <= nodeLimit && !cancelled()) {
                val best = localBest
                if (best != null) {
                    bestMove = best
                    completedDepth = depth
                    // 上一层最优手提到首位：下一层更早拿到好上界，提升 alpha-beta 剪枝效率
                    rootOrdered = listOf(best) + rootOrdered.filter { it !== best }
                }
                // 已找到必胜首手，提前结束
                if (bestVal >= WIN - 1) break
            } else {
                break
            }
        }

        humanNodeCacheTL.remove()
        lastNodesTL.set(counter[0].toInt())
        lastDepthTL.set(completedDepth)
        // 跑满 depthCap 说明不是被时间/节点掐断的（残局时即「已算到终局」）
        depthCappedTL.set(completedDepth >= depthCap)
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
        cancelled: () -> Boolean,
        tt: MutableMap<TransKey, TTEntry>
    ): Int {
        counter[0]++
        if (System.currentTimeMillis() > deadline || counter[0] > nodeLimit || cancelled()) {
            return evaluate(state, myIndex)
        }
        if (depth <= 0) return evaluate(state, myIndex)

        // —— 置换表查询：相同逻辑局面只搜索一次 ——
        val key = stateKey(state)
        val entry = tt[key]
        // 浅层命中（depth 不足）不取缓存值，但仍提供走法排序提示
        var ttPreferred: Move? = entry?.bestMove
        var alpha = alphaIn
        var beta = betaIn
        if (entry != null && entry.depth >= depth) {
            when (entry.flag) {
                TT_EXACT -> return entry.value                          // 精确命中，直接返回
                TT_LOWER -> if (entry.value >= beta) return entry.value // 下界 ≥ β → 可 fail-high
                TT_UPPER -> if (entry.value <= alpha) return entry.value // 上界 ≤ α → 可 fail-low
            }
        }

        val cur = state.cur
        val moves = generateMoves(state, cur, forbidTopBigPass = cur == myIndex)
        if (moves.isEmpty()) return evaluate(state, myIndex)

        val isMax = (cur == myIndex)
        val ordered = if (!isMax && (oppModelTL.get() ?: OPP_PARANOID) == OPP_SELFISH) {
            // 各自最优：先按「该对手自己的评估」筛出他真会考虑的前 K 手，再按我的视角取最坏
            val k = (oppTopKTL.get() ?: 2).coerceAtLeast(1)
            orderBySelfInterest(state, cur, moves).take(k)
        } else {
            orderMoves(state, cur, moves, isMax, ttPreferred)
        }
        var best = if (isMax) -INF else INF
        var bestMoveAtNode: Move? = null

        var aborted = false
        for (mv in ordered) {
            if (System.currentTimeMillis() > deadline || counter[0] > nodeLimit) { aborted = true; break }
            val undo = applyMove(state, cur, mv)
            val v = when {
                undo.winner == myIndex -> WIN - ply
                undo.winner >= 0 -> -(WIN - ply)
                else -> search(state, myIndex, alpha, beta, depth - 1, ply + 1, deadline, counter, nodeLimit, cancelled, tt)
            }
            undoMove(state, undo)

            if (isMax) {
                if (v > best) { best = v; bestMoveAtNode = mv }
                if (best > alpha) alpha = best
                if (alpha >= beta) break
            } else {
                if (v < best) { best = v; bestMoveAtNode = mv }
                if (best < beta) beta = best
                if (alpha >= beta) break
            }
        }

        // 仅在完整跑完本节点时落库（避免把「被时间预算打断的部分搜索」误存为精确值）。
        // bound 类型以进入节点时的原始窗口 [alphaIn, betaIn] 判定，保证后续命中正确。
        if (!aborted) {
            val flag = when {
                best <= alphaIn -> TT_UPPER
                best >= betaIn -> TT_LOWER
                else -> TT_EXACT
            }
            tt[key] = TTEntry(best, flag, depth, bestMoveAtNode)
            if (tt.size >= TT_CACHE_MAX) tt.clear()
        }
        return best
    }

    /**
     * 生成某玩家的全部合法走法（含过牌，若跟牌）
     * @param forbidTopBigPass 顶大局面下是否禁止过牌（仅敌方有该义务时由我方调用）。
     *        顶大规则中「能压却过牌」会被判失职、独自承担三家牌分，
     *        惩罚远重于让出一张大牌，因此对我方直接排除这一选项。
     */
    private fun generateMoves(
        state: State,
        player: Int,
        forbidTopBigPass: Boolean = false
    ): List<Move> {
        // 人类建模分支（仅仿真/实验）：把指定座位当成普通模式 [AIDecision] 代理，
        // 只生成其启发式走法而非穷举。权重 0 / 关闭时直接走下方全量展开，与现行行为一致。
        val hmSeat = humanModelSeatTL.get() ?: -1
        val hmW = humanModelWeightTL.get() ?: 0.0
        if (hmW > 0.0 && hmSeat == player && state.myIndex != hmSeat) {
            return generateHumanMoves(state, player, hmW)
        }

        val hand = state.hands[player]
        val moves = mutableListOf<Move>()
        val plays = CardRuleEngine.findAllValidPlays(hand, state.top)
        // 顶大：下家报单（剩 1 张）时，本家出单张只能是手中最大那张。
        // 若只在根节点过滤，搜索内部仍会按「可出任意小单张」估计局面，
        // 搜出的最优线在真实对局中会被引擎兜底改写，导致决策失真。
        val topBig = state.hands[(player + 1) % 4].size == 1
        val maxCard = if (topBig) {
            hand.maxWithOrNull(compareBy({ it.rank }, { it.suit.order }))
        } else {
            null
        }
        for (g in plays) {
            if (maxCard != null && g.type == CardType.SINGLE &&
                g.cards.singleOrNull()?.id != maxCard.id
            ) continue
            moves.add(Move.Play(g))
        }
        // 跟牌才可过牌；自由出牌必须出。顶大且尚有牌可压时，我方不允许过牌（避免失职）
        val mayPass = state.top != null && !(forbidTopBigPass && topBig && moves.isNotEmpty())
        if (mayPass) moves.add(Move.Pass)
        return moves
    }

    /**
     * 人类建模走法生成：把 [player] 当成普通模式 [AIDecision] 代理。
     * - 权重 ≥1：完全信任，仅返回 [AIDecision] 给出的那手（单子节点 MIN），alpha-beta 仍成立。
     * - 0<权重<1（软建模）：在 [AIDecision] 走法基础上，按权重插值补充「弱人类」候选走法
     *   （便宜优先，近似普通模式倾向），既不盲目信模型、也给搜索留一点最坏情况余地。
     * 上下文（其余 3 家手牌数、未见各点数张数、顶大义务、首手方块 3）由完美信息局面直接构造。
     */
    private fun generateHumanMoves(state: State, player: Int, weight: Double): List<Move> {
        val hand = state.hands[player]
        val topBig = state.hands[(player + 1) % 4].size == 1

        // 缓存命中：同一局面（四家手牌 + 桌面 + 元信息）下人类走法完全相同，直接复用，
        // 省掉重复的 AIDecision 推理与全量走法枚举——深层搜索里同一节点会被反复展开。
        val cacheKey = stateKey(state)
        val cache = humanNodeCacheTL.get()
        val cached = cache?.get(cacheKey)
        if (cached != null) {
            return if (weight >= 1.0) listOf(cached.aiMove)
            else beamPick(cached.aiMove, cached.fullMoves, weight)
        }

        // 顶大规则兜底：下家报单且桌面上是单张时，只要能压就必须出「最大单张」，不允许过牌。
        // AIDecision 在「只有 2 或炸弹能压」时会建议过牌，那在规则上是非法的，
        // 这里强制改写，保证被建模的人类不会走出规则禁止的过牌。
        val forcedTopBig: Move? = if (topBig) {
            val top = state.top
            val maxCard = hand.maxWithOrNull(compareBy({ it.rank }, { it.suit.order }))
            if (top != null && top.type == CardType.SINGLE && maxCard != null) {
                val single = CardGroup(CardType.SINGLE, maxCard.rank, 1, listOf(maxCard), maxCard.suit.order)
                if (CardRuleEngine.isValidPlay(single, top)) Move.Play(single) else null
            } else null
        } else null

        val isGameStart = state.top == null && state.hands.all { it.size == 13 }

        // 构造 AIDecision 上下文：其余 3 家手牌数（可见）+ 各点数未见张数（= 不在我手且尚未被出过的牌，
        // 即真实人类能掌握的信息上限，而非完美信息全知）。开局时未见=其余 39 张；随出牌推进逐步减少。
        val oppCounts = IntArray(3)
        val own = IntArray(16)
        for (c in hand) own[c.rank]++
        val playedCnt = IntArray(16)
        var m = state.playedMask
        while (m != 0L) {
            val id = m.countTrailingZeroBits()
            m = m and (m - 1)
            playedCnt[ID_TO_RANK[id]]++
        }
        val unseen = IntArray(16)
        for (r in 3..15) unseen[r] = 4 - own[r] - playedCnt[r]
        var k = 0
        for (i in 0..3) {
            if (i == player) continue
            oppCounts[k++] = state.hands[i].size
        }
        val decision = forcedTopBig ?: run {
            val d = AIDecision.decide(
                hand = hand,
                lastPlay = state.top,
                opponentCardCounts = oppCounts,
                unseenCounts = unseen,
                requireDiamond3 = isGameStart,
                mustTopBig = topBig
            )
            if (d == null) Move.Pass else Move.Play(d)
        }
        val aiMove: Move = decision

        if (weight >= 1.0) {
            cache?.put(cacheKey, HumanNode(aiMove, emptyList()))
            return listOf(aiMove)
        }

        // 软建模：枚举全部合法走法（含顶大过滤），按「便宜优先」排序补充候选，数量随权重插值。
        // 权重→0 时 beam 趋近全量，退化为现行全展开行为（但此时分支已不进入，这里仅作安全阀）。
        val fullMoves = mutableListOf<Move>()
        for (g in CardRuleEngine.findAllValidPlays(hand, state.top)) fullMoves.add(Move.Play(g))
        if (state.top != null) fullMoves.add(Move.Pass)
        if (topBig) {
            val maxCard = hand.maxWithOrNull(compareBy({ it.rank }, { it.suit.order }))
            fullMoves.removeIf { mv ->
                mv is Move.Play && mv.group.type == CardType.SINGLE &&
                    maxCard != null && mv.group.cards.singleOrNull()?.id != maxCard.id
            }
            // 能压必须出：顶大局面下过牌非法，从候选里去掉
            if (forcedTopBig != null) fullMoves.removeIf { it === Move.Pass }
        }
        cache?.put(cacheKey, HumanNode(aiMove, fullMoves))
        return beamPick(aiMove, fullMoves, weight)
    }

    /**
     * 软建模的候选裁剪：以 [aiMove] 为主选，按权重插值补充「便宜优先」候选。
     * 权重 1 → 只剩主选；权重越低候选越多，趋近全量展开。
     */
    private fun beamPick(aiMove: Move, fullMoves: List<Move>, weight: Double): List<Move> {
        if (fullMoves.isEmpty()) return listOf(aiMove)
        val beam = maxOf(1, kotlin.math.round(1 + (fullMoves.size - 1) * (1 - weight)).toInt())
        if (beam >= fullMoves.size) return fullMoves
        val rest = fullMoves.filter { it !== aiMove }.map { m ->
            val key = when (m) {
                is Move.Pass -> -1
                is Move.Play -> m.group.mainRank
            }
            m to key
        }.sortedBy { it.second }.map { it.first }.take(beam - 1)
        return listOf(aiMove) + rest
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
                var pm = state.playedMask
                for (c in cards) pm = pm or cardMask(c)
                state.playedMask = pm
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
            var mask = 0L
            for (c in undo.removed) mask = mask or cardMask(c)
            state.playedMask = state.playedMask and mask.inv()
        }
    }

    /**
     * 走法排序：好的候选先搜，提升 alpha-beta 裁剪效率。
     * - MAX：能一手出完者优先；否则按主牌点数升序（小牌先出、保留大牌）。
     * - MIN：能一手出完者优先（对手出完即我负，需尽快探明）；否则按主牌点数降序（大牌压我）。
     * 过牌在两类中均排最后。
     */
    private fun orderMoves(state: State, player: Int, moves: List<Move>, isMax: Boolean, preferred: Move? = null): List<Move> {
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
        val ordered = scored.sortedBy { it.second }.map { it.first }
        // 置换表命中的最优走法提到首位：更早拿到好上界，放大 alpha-beta 剪枝
        return if (preferred != null && ordered.contains(preferred)) {
            listOf(preferred) + ordered.filter { it !== preferred }
        } else ordered
    }

    /**
     * 「各自最优」对手模型的走法筛选：把每个候选走法落地后，用**该对手自己的视角**打分，
     * 降序排列——他只会真正考虑排在最前面的几手，而不是「怎么坑我最狠就怎么出」。
     *
     * 用 [quickEval] 而非完整 [evaluate]：后者每步都要跑一次合法走法枚举（判控制权），
     * 放在对手节点的每个候选上会把开销放大一个量级；而「各自最优」只用于筛走法，
     * 手数差这一主项足够区分对手的倾向。
     */
    private fun orderBySelfInterest(state: State, player: Int, moves: List<Move>): List<Move> {
        val scored = moves.map { mv ->
            val undo = applyMove(state, player, mv)
            val v = if (undo.winner == player) WIN else quickEval(state, player)
            undoMove(state, undo)
            mv to v
        }
        // 能一手出完的走法自然拿到 WIN（最高），会被排到最前
        return scored.sortedByDescending { it.second }.map { it.first }
    }

    /** 轻量评估（对手自评用）：只看「我离出完还差几手 / 几张」相对最强对手的差距 */
    private fun quickEval(state: State, player: Int): Int {
        val myPlays = minPlays(state.hands[player])
        val myCards = state.hands[player].size
        var oppPlays = Int.MAX_VALUE
        var oppCards = Int.MAX_VALUE
        for (i in 0..3) {
            if (i == player) continue
            oppPlays = minOf(oppPlays, minPlays(state.hands[i]))
            oppCards = minOf(oppCards, state.hands[i].size)
        }
        return ((oppPlays - myPlays) * W_PLAYS + (oppCards - myCards) * W_CARDS)
            .coerceIn(-WIN / 2, WIN / 2)
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

        // 控制权项（Level 1 + 2）：缓解 me-vs-field 联盟建模导致的过度防御。
        // 静态手牌强度（仅依赖自己手牌，零跨玩家依赖）+ 当前出牌权（自由领出 / 跟牌且能压制夺取领出权）。
        // 对手控制力取 max（最坏对手），与 oppPlays 取 min 的"最坏情况"语义一致。
        // 当前出牌方能否压制由 findAllValidPlays 判定，整局评估仅调用一次（state.cur 恒为单一玩家）。
        val curCanSeize = state.top != null &&
            CardRuleEngine.findAllValidPlays(state.hands[state.cur], state.top).isNotEmpty()

        val wCtrl = ctrlWeightTL.get() ?: W_CTRL
        val myCtrl = handStrength(state.hands[myIndex]) + currentPlayerBonus(state, myIndex, curCanSeize)
        var oppCtrl = 0
        for (i in 0..3) {
            if (i == myIndex) continue
            val c = handStrength(state.hands[i]) + currentPlayerBonus(state, i, curCanSeize)
            oppCtrl = maxOf(oppCtrl, c)
        }

        val v = (oppPlays - myPlays) * W_PLAYS
            + (oppCards - myCards) * W_CARDS
            + (myCtrl - oppCtrl) * wCtrl
        return v.coerceIn(-WIN / 2, WIN / 2)
    }

    /**
     * 当前出牌者（[player]）是否握有出牌权：
     * - 自由领出（[state.top]==null 且轮到他）→ 领出权加分；
     * - 跟牌且能压制当前 top（[curCanSeize]）→ 夺取领出权加分。
     * 非当前出牌者返回 0（出牌权在别人手里，无控制权增益）。
     */
    private fun currentPlayerBonus(state: State, player: Int, curCanSeize: Boolean): Int {
        if (state.cur != player) return 0
        return if (state.top == null) LEAD_RIGHT_BONUS else if (curCanSeize) SEIZE_BONUS else 0
    }

    /**
     * 静态手牌控制力（仅依赖自己手牌，零跨玩家依赖）：
     * - 每张 2（rank 15）在任何牌型都最大、绝对不可压 → 最强控场资本；
     * - 同花顺 / 四带一为顶级牌型，压制力强。
     * 用于 [evaluate] 的控制权项，缓解联盟建模造成的过度防御。
     */
    private fun handStrength(cards: List<Card>): Int {
        var s = 0
        for (c in cards) if (c.rank == 15) s += CTRL_TWO
        if (hasStraightFlush(cards)) s += CTRL_STRAIGHT_FLUSH
        if (hasFourWithOne(cards)) s += CTRL_FOUR_WITH_ONE
        return s
    }

    /** 是否存在同花顺（同花色连续 5 张，或 A-2-3-4-5 同花） */
    private fun hasStraightFlush(cards: List<Card>): Boolean {
        for (s in 0..3) {
            val ranks = cards.filter { it.suit.order == s }.map { it.rank }.toSet()
            for (start in 3..10) {
                if ((start..start + 4).all { it in ranks }) return true
            }
            if (A2345.all { it in ranks }) return true   // A-2-3-4-5（A 作 1）
        }
        return false
    }

    /** 是否存在四带一（任一点数 ≥ 4 张） */
    private fun hasFourWithOne(cards: List<Card>): Boolean {
        val cnt = IntArray(16)
        for (c in cards) cnt[c.rank]++
        for (r in 3..15) if (cnt[r] >= 4) return true
        return false
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

        if (minPlaysCache.size >= MIN_PLAYS_CACHE_MAX) minPlaysCache.clear()
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
