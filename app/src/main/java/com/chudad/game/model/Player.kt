package com.chudad.game.model

/**
 * 玩家难度枚举
 */
enum class Difficulty {
    NORMAL, // 普通模式：启发式规则 AI
    MASTER  // 大师模式：电脑完美信息搜索 AI
}

/**
 * 玩家数据类
 * 锄大D 为 4 人各自为战：无地主/农民角色、无叫分、无底牌。
 *
 * @param index 玩家索引（0=底部人类玩家, 1/2/3=AI 玩家）
 * @param name 显示名称
 * @param isHuman 是否为人类玩家
 * @param difficulty AI 难度（仅 AI 玩家有效）
 */
data class Player(
    val index: Int,
    val name: String,
    val isHuman: Boolean,
    var difficulty: Difficulty = Difficulty.NORMAL
) {
    /** 手牌列表（CopyOnWrite：绘制线程与 UI 线程并发读写安全） */
    val handCards: MutableList<Card> = java.util.concurrent.CopyOnWriteArrayList()

    /** 获取手牌数量 */
    val cardCount: Int get() = handCards.size
}
