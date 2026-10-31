package com.chudad.game.logic

/**
 * 锄大D 结算牌分计算。
 *
 * 基础牌分（n = 剩余手牌张数）：
 *   n < 8   → n
 *   8 ≤ n < 10  → 2n
 *   10 ≤ n < 13 → 3n
 *   n = 13  → 4n
 *
 * 翻倍：剩余 ≥ 8 张且手中包含黑桃 2 → 牌分 ×2。
 * 赢家得分 = 其余三家牌分之和。
 */
object ScoreCalculator {

    /** 基础牌分（未含黑桃 2 翻倍） */
    fun baseScore(remaining: Int): Int = when {
        remaining <= 0 -> 0
        remaining < 8 -> remaining
        remaining < 10 -> remaining * 2
        remaining < 13 -> remaining * 3
        else -> remaining * 4          // n == 13
    }

    /**
     * 某玩家最终应扣牌分。
     * @param remaining 剩余手牌数
     * @param hasSpadeTwo 剩余手牌中是否包含黑桃 2
     */
    fun scoreFor(remaining: Int, hasSpadeTwo: Boolean): Int {
        val base = baseScore(remaining)
        return if (remaining >= 8 && hasSpadeTwo) base * 2 else base
    }

    /**
     * 计算四家本局得分变化（零和）。
     *
     * 常规：赢家收取其余三家牌分，其余各家扣自己的牌分。
     * 顶大失职：仅当报单者（失职者的下家）获胜时生效——
     *   失职上家独自承担三家（自己 + 其余两家）的牌分，其余输家为 0，赢家仍收满三家。
     *
     * @param remaining 各家剩余张数
     * @param hasSpadeTwo 各家剩余手牌是否含黑桃 2
     * @param winner 赢家索引
     * @param topBigFail 顶大失职者索引（-1 表示无）
     */
    fun settle(
        remaining: IntArray,
        hasSpadeTwo: BooleanArray,
        winner: Int,
        topBigFail: Int
    ): IntArray {
        val n = remaining.size
        val scores = IntArray(n) { scoreFor(remaining[it], hasSpadeTwo.getOrElse(it) { false }) }
        val totalToWinner = (0 until n).filter { it != winner }.sumOf { scores[it] }
        val deltas = IntArray(n)

        val fail = if (topBigFail in 0 until n && winner == (topBigFail + 1) % n) {
            topBigFail
        } else {
            -1
        }

        for (i in 0 until n) {
            deltas[i] = when {
                i == winner -> totalToWinner
                fail < 0 -> -scores[i]
                i == fail -> -totalToWinner
                else -> 0
            }
        }
        return deltas
    }
}
