package com.chudad.game.logic

import com.chudad.game.model.Card
import com.chudad.game.model.CardGroup
import com.chudad.game.model.Difficulty

/**
 * 大师模式 AI 决策（锄大D）
 *
 * 与斗地主版的核心差异：锄大D 为 4 人各自为战，不存在地主/农民阵营，
 * 搜索视角从「两队零和」改为「四人各自最大化自身先出完的概率」。
 *
 * 【阶段 1 现状】
 *   骨架与 [Snapshot] 已就位（含 4 家真实手牌，满足「已知全手牌」的完美信息假设），
 *   决策暂时委托给普通模式启发式 [AIDecision]，保证大师模式可完整对局、可验证。
 *
 * 【阶段 2 计划】
 *   接入 [MasterSearch] 的 4 人完美信息搜索：
 *    - 位压缩由 3 家扩展为 4 家
 *    - 走法生成覆盖锄大D 全部牌型（含葫芦/四带一/同花顺）
 *    - alpha-beta + 置换表 + 迭代加深，评估改为「手数竞速 + 牌质」
 *    - 去掉 rootIsLandlord 队伍视角，改为四人独立视角
 */
object MasterAIDecision {

    /**
     * 大师模式决策所需的全量快照（普通模式不会构造此对象）
     * @param myIndex 当前决策 AI 的玩家索引
     * @param hands 四家真实手牌（含人类玩家），键为玩家索引
     * @param lastPlay 上一手出牌（自由出牌时为 null）
     * @param lastPlayerIndex 上一手出牌玩家索引（-1 表示新一轮自由出牌）
     * @param currentPlayerIndex 当前轮到谁（必等于 [myIndex]）
     */
    data class Snapshot(
        val myIndex: Int,
        val hands: Map<Int, List<Card>>,
        val lastPlay: CardGroup?,
        val lastPlayerIndex: Int,
        val currentPlayerIndex: Int
    )

    /**
     * 决策入口
     * @param snapshot 全量快照（4 家手牌）
     * @param deadlineMs 搜索时长预算（毫秒）
     * @param nodeLimit 搜索节点预算
     * @return 决定出的牌组；null 表示过牌
     */
    fun decide(
        snapshot: Snapshot,
        deadlineMs: Long = 250L,
        nodeLimit: Int = 500_000
    ): CardGroup? {
        val hand = snapshot.hands[snapshot.myIndex] ?: return null
        if (hand.isEmpty()) return null

        // 其余 3 家的手牌数（大师模式已知全手牌）
        val opponentCardCounts = snapshot.hands
            .filterKeys { it != snapshot.myIndex }
            .values
            .map { it.size }
            .toIntArray()

        // 未见牌 = 其余 3 家手牌（按点数统计）
        val unseenCounts = IntArray(16)
        snapshot.hands
            .filterKeys { it != snapshot.myIndex }
            .values
            .flatten()
            .forEach { unseenCounts[it.rank]++ }

        // 阶段 1：委托普通模式启发式（阶段 2 将替换为 MasterSearch 四人搜索）
        return AIDecision.decide(
            hand = hand,
            lastPlay = snapshot.lastPlay,
            difficulty = Difficulty.MASTER,
            myIndex = snapshot.myIndex,
            opponentCardCounts = opponentCardCounts,
            unseenCounts = unseenCounts
        )
    }
}
