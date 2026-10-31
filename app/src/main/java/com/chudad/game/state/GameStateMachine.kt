package com.chudad.game.state

import com.chudad.game.model.Card
import com.chudad.game.model.CardGroup
import com.chudad.game.model.CardType

/**
 * 游戏状态枚举
 * 锄大D 流程：发牌后直接出牌，先出完手牌者胜。
 */
enum class GamePhase {
    MENU,           // 主界面（难度选择）
    DEALING,        // 发牌中
    PLAYING,        // 出牌阶段
    SETTLING,       // 结算阶段
    GAME_OVER       // 游戏结束
}

/** 锄大D 固定 4 人 */
const val PLAYER_COUNT = 4

/**
 * 游戏状态机（锄大D：4 人各自为战）
 * 管理游戏流程的状态转换、回合轮转与牌桌记录。
 */
class GameStateMachine {
    /** 当前游戏阶段 */
    var phase: GamePhase = GamePhase.MENU
        private set

    /** 当前操作的玩家索引（0=底部人类, 1/2/3=AI） */
    var currentPlayerIndex: Int = 0

    /** 上一手出牌的牌组信息 */
    var lastPlayedGroup: CardGroup? = null

    /** 上一手出牌的玩家索引 */
    var lastPlayedPlayerIndex: Int = -1

    /** 连续不出（过牌）的人数 */
    var passCount: Int = 0

    /** 出牌记录（玩家索引 + 所出牌） */
    val playHistory: MutableList<Pair<Int, List<Card>>> = mutableListOf()

    /** 胜负已定时的获胜玩家索引（-1 表示未定） */
    var winnerIndex: Int = -1

    /** 本局首手是否必须包含方块 3（仅整场第一局为 true，后续局不再要求） */
    var requireDiamond3Lead: Boolean = false

    /**
     * 顶大失职玩家索引（-1 表示无）：
     * 下家报单时，该玩家本可用单张拦截却选择过牌；若报单者最终获胜，则据此结算惩罚。
     * 该玩家一旦实际出牌（无论单张还是其它牌型）即清除——只有「不出」才算未顶大。
     */
    var topBigFailIndex: Int = -1

    // ============================ 阶段转换 ============================

    /** 转换到发牌阶段：清空本局全部状态 */
    fun startDealing() {
        phase = GamePhase.DEALING
        currentPlayerIndex = 0
        lastPlayedGroup = null
        lastPlayedPlayerIndex = -1
        passCount = 0
        playHistory.clear()
        winnerIndex = -1
        requireDiamond3Lead = false
        topBigFailIndex = -1
    }

    /** 进入主界面（难度选择）：清空本局残留状态，避免主界面残留上局牌 */
    fun startMenu() {
        phase = GamePhase.MENU
        lastPlayedGroup = null
        lastPlayedPlayerIndex = -1
        passCount = 0
        playHistory.clear()
        winnerIndex = -1
        requireDiamond3Lead = false
        topBigFailIndex = -1
    }

    /**
     * 转换到出牌阶段
     * @param firstPlayer 首发玩家索引（锄大D 可由持有特定牌者先出，默认 0）
     */
    fun startPlaying(firstPlayer: Int = 0) {
        phase = GamePhase.PLAYING
        currentPlayerIndex = firstPlayer
        lastPlayedGroup = null
        lastPlayedPlayerIndex = -1
        passCount = 0
        topBigFailIndex = -1
    }

    // ============================ 出牌 / 过牌 ============================

    /**
     * 处理出牌
     * @param playerIndex 出牌玩家
     * @param group 出的牌组
     * @return 下一个出牌的玩家索引
     */
    fun processPlay(playerIndex: Int, group: CardGroup): Int {
        lastPlayedGroup = group
        lastPlayedPlayerIndex = playerIndex
        passCount = 0
        playHistory.add(Pair(playerIndex, group.cards))
        return nextPlayer(playerIndex)
    }

    /**
     * 处理不出（过牌）
     * @param playerIndex 过牌玩家
     * @return 下一个出牌的玩家索引；其余 3 家均过时开启新一轮（清空上一手）
     */
    fun processPass(playerIndex: Int): Int {
        passCount++
        // 4 人局：另外 3 家都过 → 新一轮，由上次出牌者自由出牌
        if (passCount >= PLAYER_COUNT - 1) {
            lastPlayedGroup = null
            lastPlayedPlayerIndex = -1
            passCount = 0
        }
        return nextPlayer(playerIndex)
    }

    /** 下一个玩家索引（4 人轮转） */
    fun nextPlayer(current: Int): Int = (current + 1) % PLAYER_COUNT

    /**
     * 检查游戏是否结束（有人出完手牌即胜）
     * @param playerCards 各玩家剩余手牌数量列表
     * @return 获胜玩家索引，-1 表示未结束
     */
    fun checkGameOver(playerCards: List<Int>): Int {
        for (i in playerCards.indices) {
            if (playerCards[i] == 0) return i
        }
        return -1
    }

    /** 当前玩家是否必须出牌（自由出牌时不能过） */
    fun mustPlay(): Boolean =
        lastPlayedGroup == null || lastPlayedPlayerIndex == currentPlayerIndex

    /** 转换到结算阶段 */
    fun startSettling(winner: Int) {
        phase = GamePhase.SETTLING
        winnerIndex = winner
    }

    /** 转换到游戏结束 */
    fun endGame() {
        phase = GamePhase.GAME_OVER
    }
}
