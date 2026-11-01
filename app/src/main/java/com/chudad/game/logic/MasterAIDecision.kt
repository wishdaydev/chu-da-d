package com.chudad.game.logic

import com.chudad.game.model.Card
import com.chudad.game.model.CardGroup

/**
 * 大师模式 AI 决策（锄大D）
 *
 * 锄大D 为 4 人各自为战，不存在地主/农民阵营；决策采用「四人各自最大化
 * 自身先出完概率」的独立视角（非两队零和）。
 *
 * 决策核心为 [MasterSearch] 的 4 人完美信息搜索：位压缩覆盖 4 家手牌，
 * 走法生成覆盖锄大D 全部牌型（含顺子/同花五/葫芦/四带一/同花顺），评估为「手数竞速 + 牌质」，
 * 采用 alpha-beta + 迭代加深（根节点 PV 优先）。搜索无可行解或超时回退到普通模式
 * 启发式 [AIDecision]，保证大师模式始终可完整对局。
 */
object MasterAIDecision {

    /**
     * 大师模式决策所需的全量快照（普通模式不会构造此对象）
     * @param myIndex 当前决策 AI 的玩家索引
     * @param hands 四家真实手牌（含人类玩家），键为玩家索引
     * @param lastPlay 上一手出牌（自由出牌时为 null）
     * @param lastPlayerIndex 上一手出牌玩家索引（-1 表示新一轮自由出牌）
     * @param passes 自上一手以来的过牌数（接力轮转用，来自状态机 passCount）
     * @param currentPlayerIndex 当前轮到谁（必等于 [myIndex]）
     * @param mustContainDiamond3 本手是否必须包含方块 3（整场第一局首手）
     * @param mustTopBig 下家是否已报单（顶大义务：出单张须为手中最大）
     */
    data class Snapshot(
        val myIndex: Int,
        val hands: Map<Int, List<Card>>,
        val lastPlay: CardGroup?,
        val lastPlayerIndex: Int,
        val passes: Int = 0,
        val currentPlayerIndex: Int,
        val mustContainDiamond3: Boolean = false,
        val mustTopBig: Boolean = false
    )

    /**
     * 被建模为「普通模式玩家」的座位：人类固定占 0 号座（见 GameEngine 发牌与出牌流程）。
     * 其余三个 AI 座位搜索时会把 0 号座当成按 [AIDecision] 出牌的真人来预测。
     */
    const val HUMAN_SEAT = 0

    /**
     * 决策入口
     * @param snapshot 全量快照（4 家手牌）
     * @param deadlineMs 搜索时长预算（毫秒）——迭代加深会持续到该预算耗尽
     * @param nodeLimit 搜索节点预算（线上给到极大值，让时间成为唯一瓶颈）
     * @param humanModelWeight 人类座位建模强度：0=全量展开（把人类当完美玩家）、
     *        1=只信 [AIDecision] 单手、中间值=软建模（代理走法 + 少量次优候选）
     * @param cancelled 取消判定（重开局后旧搜索应尽快退出，避免占用后台线程）
     * @return 决定出的牌组；null 表示过牌
     */
    fun decide(
        snapshot: Snapshot,
        deadlineMs: Long = 250L,
        nodeLimit: Int = 500_000,
        humanModelWeight: Double = 0.8,
        humanModelSeat: Int = HUMAN_SEAT,
        cancelled: () -> Boolean = { false }
    ): CardGroup? {
        val hand = snapshot.hands[snapshot.myIndex] ?: return null
        if (hand.isEmpty()) return null

        // 阶段 2：4 人完美信息搜索（me-vs-field 博弈树 + 迭代加深 + alpha-beta）
        try {
            val result = MasterSearch.solve(
                hands = snapshot.hands,
                myIndex = snapshot.myIndex,
                topGroup = snapshot.lastPlay,
                topPlayer = snapshot.lastPlayerIndex,
                passes = snapshot.passes,
                current = snapshot.currentPlayerIndex,
                deadlineMs = deadlineMs,
                nodeLimit = nodeLimit,
                mustContainDiamond3 = snapshot.mustContainDiamond3,
                mustTopBig = snapshot.mustTopBig,
                // 人类建模：把 0 号座当普通模式玩家预测（对方模型比「完美联盟」更贴近实战）；
                // 若当前决策者就是该座（理论不会发生），则关闭建模避免自我套娃
                humanModelWeight = if (snapshot.myIndex == humanModelSeat) 0.0 else humanModelWeight,
                humanModelSeat = if (snapshot.myIndex == humanModelSeat) -1 else humanModelSeat,
                cancelled = cancelled
            )
            if (result != null) return result                 // 搜索给出的一手出牌
            if (snapshot.lastPlay != null) return null        // 跟牌且搜索判「过牌」最优 → 过牌
            // 自由出牌却得到 null（异常情形，不应发生）→ 落入下方启发式兜底
        } catch (e: Exception) {
            // 搜索异常（不应发生）则回退启发式，保证对局不中断
        }

        // 回退：普通模式启发式
        // 对手张数必须与 [com.chudad.game.logic.GameEngine.getOpponentCardCounts] 同口径：
        // [0] 恒为下家。Map 的 values 是座位号升序，直接塞给 AIDecision 会在 myIndex=1/2 时
        // 把上家当成「下家报单」的保护对象。
        val opponentCardCounts = IntArray(3) { i ->
            snapshot.hands[(snapshot.myIndex + i + 1) % 4]?.size ?: 13
        }
        val unseenCounts = IntArray(16)
        snapshot.hands
            .filterKeys { it != snapshot.myIndex }
            .values
            .flatten()
            .forEach { unseenCounts[it.rank]++ }
        return AIDecision.decide(
            hand = hand,
            lastPlay = snapshot.lastPlay,
            opponentCardCounts = opponentCardCounts,
            unseenCounts = unseenCounts,
            requireDiamond3 = snapshot.mustContainDiamond3,
            mustTopBig = snapshot.mustTopBig
        )
    }
}
