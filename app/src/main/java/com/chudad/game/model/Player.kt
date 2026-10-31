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
 * 锄大D 为 4 人各自为战：无角色区分、发牌即开局、无底牌。
 * 难度由引擎统一持有（[com.chudad.game.logic.GameEngine.aiDifficulty]），玩家本身不存难度。
 *
 * @param index 玩家索引（0=底部人类玩家, 1/2/3=AI 玩家）
 * @param name 显示名称
 * @param isHuman 是否为人类玩家
 */
data class Player(
    val index: Int,
    val name: String,
    val isHuman: Boolean
) {
    /** 手牌列表（CopyOnWrite：绘制线程与 UI 线程并发读写安全） */
    val handCards: MutableList<Card> = java.util.concurrent.CopyOnWriteArrayList()

    /** 获取手牌数量 */
    val cardCount: Int get() = handCards.size
}
