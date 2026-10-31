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
     * 决策入口
     * @param snapshot 全量快照（4 家手牌）
     * @param deadlineMs 搜索时长预算（毫秒）——迭代加深会持续到该预算耗尽
     * @param nodeLimit 搜索节点预算
     * @param cancelled 取消判定（重开局后旧搜索应尽快退出，避免占用后台线程）
     * @return 决定出的牌组；null 表示过牌
     */
    fun decide(
        snapshot: Snapshot,
        deadlineMs: Long = 250L,
        nodeLimit: Int = 500_000,
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
                cancelled = cancelled
            )
            if (result != null) return result                 // 搜索给出的一手出牌
            if (snapshot.lastPlay != null) return null        // 跟牌且搜索判「过牌」最优 → 过牌
            // 自由出牌却得到 null（异常情形，不应发生）→ 落入下方启发式兜底
        } catch (e: Exception) {
            // 搜索异常（不应发生）则回退启发式，保证对局不中断
        }

        // 回退：普通模式启发式
        val opponentCardCounts = snapshot.hands
            .filterKeys { it != snapshot.myIndex }
            .values
            .map { it.size }
            .toIntArray()
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
