package com.chudad.game.logic

import com.chudad.game.model.Card
import com.chudad.game.model.CardGroup
import com.chudad.game.model.CardType
import com.chudad.game.model.Difficulty
import com.chudad.game.model.Player
import com.chudad.game.model.Suit
import com.chudad.game.model.createDeck
import com.chudad.game.state.GamePhase
import com.chudad.game.state.GameStateMachine
import com.chudad.game.state.PLAYER_COUNT

/**
 * 游戏引擎回调接口
 * 用于通知 UI 层游戏状态变化（锄大D：无叫分/无地主）
 */
interface GameEngineCallback {
    /** 发牌完成（UI 触发手牌展示动画，动画结束后调用 beginPlaying） */
    fun onDealingComplete()
    /** 轮到某玩家出牌 */
    fun onPlayerTurn(playerIndex: Int)
    /** 玩家出牌 */
    fun onPlayerPlay(playerIndex: Int, cards: List<Card>, group: CardGroup)
    /** 玩家不出 */
    fun onPlayerPass(playerIndex: Int)
    /** 游戏结束（先出完手牌者胜） */
    fun onGameOver(winnerIndex: Int)
    /** 请求刷新 UI */
    fun onRequestRefresh()
}

/**
 * 游戏引擎（锄大D：4 人各自为战）
 * 控制整体游戏流程，协调状态机、规则引擎与 AI。
 *
 * 分层边界保持不变：UI 只读 players/stateMachine 做绘制，AI 只由本引擎调用，
 * 决策结果经 processPlay/processPass 回写状态后回调 UI 重绘。
 */
class GameEngine {

    /** 四位玩家：0=底部人类，1/2/3=AI */
    val players = listOf(
        Player(0, "你", isHuman = true),
        Player(1, "电脑A", isHuman = false, difficulty = Difficulty.NORMAL),
        Player(2, "电脑B", isHuman = false, difficulty = Difficulty.NORMAL),
        Player(3, "电脑C", isHuman = false, difficulty = Difficulty.NORMAL)
    )

    /** 游戏状态机 */
    val stateMachine = GameStateMachine()

    /** 当前 AI 难度（由主界面难度选择决定，新开对局时应用到 3 个 AI 玩家） */
    var aiDifficulty: Difficulty = Difficulty.NORMAL

    /**
     * 大师模式下「电脑玩家是否使用全信息搜索」策略。
     * 锄大D 为四人各自为战、无队友概念，故原「队友农民策略」在此语义重定义为：
     * 大师模式中三个电脑玩家出牌是否走完美信息搜索（开启=全开挂，关闭=走普通启发式）。
     */
    var aiMasterStrategy: Difficulty = Difficulty.NORMAL

    /** 大师模式「玩家提示 AI 策略」：人类点「提示」时是否使用大师全信息策略 */
    var playerHintStrategy: Difficulty = Difficulty.NORMAL

    /** 回调接口 */
    var callback: GameEngineCallback? = null

    /** 当前选中的手牌索引集合（CopyOnWrite：绘制线程与 UI 线程并发读写安全） */
    val selectedCardIndices = java.util.concurrent.CopyOnWriteArraySet<Int>()

    /** 4 位玩家各 rank 已打出张数（仅凭公开出牌记录推算，不做不可见推断） */
    private val playedByPlayer: Array<IntArray> = Array(PLAYER_COUNT) { IntArray(16) }

    /** 大师模式搜索「局代号」：开新局/退回菜单自增，用于丢弃后台搜索的过期结果 */
    private var searchEpoch = 0

    /** 大师模式搜索后台线程（单线程串行，避免同时跑多个搜索） */
    private val masterSearchExecutor: java.util.concurrent.ExecutorService by lazy {
        java.util.concurrent.Executors.newSingleThreadExecutor()
    }

    /** 大师 AI 出牌前的「思考延迟」（毫秒），仅用于节奏，与搜索耗时叠加 */
    private val MASTER_DELAY_MS = 500L
    /** 大师 AI 后台搜索时长预算（毫秒），可由设置项「最大思考时间(秒)」覆盖 */
    @Volatile
    var masterDeadlineMs: Long = 5000L
    /** 大师 AI 后台搜索节点预算 */
    private val MASTER_NODE_LIMIT = 60_000_000
    /** 电脑玩家单回合最短耗时（毫秒）：避免电脑出牌过快导致玩家看不清节奏 */
    private val MIN_AI_TURN_MS = 1500L
    /** 提示（getHint）同步搜索时长预算（毫秒），必须小以免卡 UI */
    private val HINT_DEADLINE_MS = 300L
    /** 提示（getHint）同步搜索节点预算 */
    private val HINT_NODE_LIMIT = 450_000

    // ============================ 开局 / 结束 ============================

    /** 开始新游戏：洗牌发牌（4 人各 13 张） */
    fun startNewGame() {
        searchEpoch++                       // 使在途的后台搜索结果作废
        players.forEach {
            it.handCards.clear()
            if (!it.isHuman) it.difficulty = aiDifficulty
        }
        playedByPlayer.forEach { it.fill(0) }
        selectedCardIndices.clear()

        stateMachine.startDealing()
        dealCards()
        callback?.onDealingComplete()
    }

    /** 返回主界面（难度选择）：清空手牌并进入 MENU 阶段 */
    fun returnToMenu() {
        searchEpoch++
        players.forEach { it.handCards.clear() }
        stateMachine.startMenu()
    }

    /**
     * 发牌展示动画结束后进入出牌阶段。
     * 锄大D 规则：持有「方块 3（♦3）」的玩家先出牌。
     * @return 首发玩家索引
     */
    fun beginPlaying(): Int {
        if (stateMachine.phase != GamePhase.DEALING) return stateMachine.currentPlayerIndex
        val first = players.firstOrNull { p ->
            p.handCards.any { it.rank == 3 && it.suit == Suit.DIAMOND }
        }?.index ?: 0

        stateMachine.startPlaying(first)
        callback?.onPlayerTurn(first)
        if (!players[first].isHuman) scheduleAIPlay(first)
        callback?.onRequestRefresh()
        return first
    }

    /** 洗牌并发牌：52 张，4 人各 13 张（无底牌） */
    private fun dealCards() {
        val deck = createDeck()
        deck.shuffle()
        deck.forEachIndexed { i, card -> players[i % PLAYER_COUNT].handCards.add(card) }
        // 手牌排序：先按点数，再按花色，便于 UI 与 AI 稳定处理
        players.forEach {
            it.handCards.sortWith(compareBy({ c: Card -> c.rank }, { c: Card -> c.suit.order }))
        }
    }

    // ============================ 人类操作 ============================

    /** 人类玩家出牌 */
    fun humanPlay(selectedCards: List<Card>) {
        if (stateMachine.phase != GamePhase.PLAYING) return
        if (stateMachine.currentPlayerIndex != 0) return

        val group = CardRuleEngine.identify(selectedCards)
        if (group.type == CardType.INVALID) return
        if (!CardRuleEngine.isValidPlay(group, stateMachine.lastPlayedGroup)) return

        processPlay(0, selectedCards, group)
    }

    /** 人类玩家不出 */
    fun humanPass() {
        if (stateMachine.phase != GamePhase.PLAYING) return
        if (stateMachine.currentPlayerIndex != 0) return
        if (stateMachine.mustPlay()) return   // 自由出牌 / 自己压自己时不能过

        processPass(0)
    }

    // ============================ 出牌 / 过牌核心 ============================

    private fun processPlay(playerIndex: Int, cards: List<Card>, group: CardGroup) {
        val player = players[playerIndex]
        cards.forEach { card ->
            player.handCards.removeIf { it.id == card.id }
            playedByPlayer[playerIndex][card.rank]++
        }

        val nextPlayer = stateMachine.processPlay(playerIndex, group)
        selectedCardIndices.clear()
        callback?.onPlayerPlay(playerIndex, cards, group)

        // 先出完手牌者胜
        val winner = stateMachine.checkGameOver(players.map { it.cardCount })
        if (winner >= 0) {
            stateMachine.startSettling(winner)
            callback?.onGameOver(winner)
            stateMachine.endGame()
            return
        }

        stateMachine.currentPlayerIndex = nextPlayer
        callback?.onPlayerTurn(nextPlayer)
        if (!players[nextPlayer].isHuman) scheduleAIPlay(nextPlayer)
        callback?.onRequestRefresh()
    }

    private fun processPass(playerIndex: Int) {
        val nextPlayer = stateMachine.processPass(playerIndex)
        callback?.onPlayerPass(playerIndex)

        stateMachine.currentPlayerIndex = nextPlayer
        callback?.onPlayerTurn(nextPlayer)
        if (!players[nextPlayer].isHuman) scheduleAIPlay(nextPlayer)
        callback?.onRequestRefresh()
    }

    // ============================ AI 调度 ============================

    /**
     * 该 AI 是否走大师全信息搜索：仅大师模式生效，且受 [aiMasterStrategy] 设置控制
     */
    private fun shouldUseMasterPlay(playerIndex: Int): Boolean {
        if (aiDifficulty != Difficulty.MASTER) return false
        if (players[playerIndex].isHuman) return false
        return aiMasterStrategy == Difficulty.MASTER
    }

    private fun scheduleAIPlay(playerIndex: Int) {
        val turnStartMs = System.currentTimeMillis()
        val epoch = searchEpoch
        val delay = if (shouldUseMasterPlay(playerIndex)) MASTER_DELAY_MS else 1200L

        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            if (stateMachine.phase != GamePhase.PLAYING) return@postDelayed
            if (stateMachine.currentPlayerIndex != playerIndex) return@postDelayed

            if (shouldUseMasterPlay(playerIndex)) {
                scheduleMasterPlay(playerIndex, turnStartMs)
            } else {
                val decision = AIDecision.decide(
                    hand = players[playerIndex].handCards,
                    lastPlay = stateMachine.lastPlayedGroup,
                    difficulty = Difficulty.NORMAL,
                    myIndex = playerIndex,
                    opponentCardCounts = getOpponentCardCounts(playerIndex),
                    unseenCounts = getUnseenRankCounts(playerIndex)
                )
                applyAIDecisionPaced(playerIndex, decision, turnStartMs, epoch)
            }
        }, delay)
    }

    /** 大师模式：后台线程跑完美信息搜索，主线程应用结果（[searchEpoch] 防过期） */
    private fun scheduleMasterPlay(playerIndex: Int, turnStartMs: Long) {
        val snapshot = buildMasterSnapshot(playerIndex)
        val epoch = searchEpoch
        masterSearchExecutor.execute {
            val decision = MasterAIDecision.decide(
                snapshot,
                deadlineMs = masterDeadlineMs,
                nodeLimit = MASTER_NODE_LIMIT
            )
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                if (epoch != searchEpoch) return@post
                if (stateMachine.phase != GamePhase.PLAYING) return@post
                if (stateMachine.currentPlayerIndex != playerIndex) return@post
                applyAIDecisionPaced(playerIndex, decision, turnStartMs, epoch)
            }
        }
    }

    /** 按最短回合时长节流后应用电脑决策 */
    private fun applyAIDecisionPaced(
        playerIndex: Int, decision: CardGroup?,
        turnStartMs: Long, epoch: Int
    ) {
        val remain = MIN_AI_TURN_MS - (System.currentTimeMillis() - turnStartMs)
        if (remain <= 0L) {
            applyAIDecision(playerIndex, decision)
            return
        }
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            if (epoch != searchEpoch) return@postDelayed
            if (stateMachine.phase != GamePhase.PLAYING) return@postDelayed
            if (stateMachine.currentPlayerIndex != playerIndex) return@postDelayed
            applyAIDecision(playerIndex, decision)
        }, remain)
    }

    /** 统一应用 AI 决策：合法则出牌；本轮首家必须出时兜底最小单张；否则过牌 */
    private fun applyAIDecision(playerIndex: Int, decision: CardGroup?) {
        val player = players[playerIndex]
        if (decision != null && decision.type != CardType.INVALID) {
            processPlay(playerIndex, decision.cards, decision)
            return
        }
        if (stateMachine.mustPlay()) {
            val fallback = player.handCards.minWithOrNull(
                compareBy({ it.rank }, { it.suit.order })
            )
            if (fallback != null) {
                val single = CardGroup(
                    CardType.SINGLE, fallback.rank, 1,
                    listOf(fallback), fallback.suit.order
                )
                processPlay(playerIndex, listOf(fallback), single)
                return
            }
        }
        processPass(playerIndex)
    }

    // ============================ 信息推导（仅凭公开信息） ============================

    /** 其余 3 家的手牌数 */
    private fun getOpponentCardCounts(myIndex: Int): IntArray =
        players.filter { it.index != myIndex }
            .map { legalCardCount(it.index) }
            .toIntArray()

    /** 某玩家剩余手牌数 = 13 - 其已打出张数（公共记录推导） */
    private fun legalCardCount(playerIndex: Int): Int {
        var played = 0
        for ((p, cards) in stateMachine.playHistory) {
            if (p == playerIndex) played += cards.size
        }
        return (13 - played).coerceAtLeast(0)
    }

    /** 获取某玩家的合法剩余手牌数 */
    fun getCardCount(index: Int): Int = legalCardCount(index)

    /**
     * 我方未见各点数剩余张数：每点数共 4 张 − 自己手牌 − 已打出
     * @return 长度 16 的数组（下标即 rank，rank 范围 3..15）
     */
    private fun getUnseenRankCounts(myIndex: Int): IntArray {
        val known = IntArray(16)
        players[myIndex].handCards.forEach { known[it.rank]++ }
        stateMachine.playHistory.forEach { (_, cards) -> cards.forEach { known[it.rank]++ } }
        val counts = IntArray(16)
        for (rank in 3..15) {
            counts[rank] = (4 - known[rank]).coerceAtLeast(0)
        }
        return counts
    }

    /** 构造大师模式全量快照（含 4 家真实手牌），仅大师模式调用 */
    private fun buildMasterSnapshot(myIndex: Int): MasterAIDecision.Snapshot =
        MasterAIDecision.Snapshot(
            myIndex = myIndex,
            hands = players.associate { it.index to it.handCards.toList() },
            lastPlay = stateMachine.lastPlayedGroup,
            lastPlayerIndex = stateMachine.lastPlayedPlayerIndex,
            currentPlayerIndex = stateMachine.currentPlayerIndex
        )

    // ============================ 提示 ============================

    /**
     * 获取人类玩家的提示出牌
     * 大师模式且「玩家提示策略」=大师时用全信息求解器；否则用普通启发式
     */
    fun getHint(): List<Card>? {
        val hand = players[0].handCards
        val lastPlay = stateMachine.lastPlayedGroup

        val decision = if (aiDifficulty == Difficulty.MASTER && playerHintStrategy == Difficulty.MASTER) {
            MasterAIDecision.decide(
                buildMasterSnapshot(0),
                deadlineMs = HINT_DEADLINE_MS,
                nodeLimit = HINT_NODE_LIMIT
            )
        } else {
            AIDecision.decide(
                hand = hand,
                lastPlay = lastPlay,
                difficulty = Difficulty.NORMAL,
                myIndex = 0,
                opponentCardCounts = getOpponentCardCounts(0),
                unseenCounts = getUnseenRankCounts(0)
            )
        }

        if (decision != null && decision.type != CardType.INVALID) return decision.cards

        // 自由出牌（无上一手）且启发式未给决策时，兜底提示最小单张；
        // 跟牌情境下若启发式判定「过牌」更优（decision==null），则尊重该判断，提示「不出」。
        if (lastPlay == null) {
            val minCard = hand.minWithOrNull(compareBy({ it.rank }, { it.suit.order }))
            if (minCard != null) return listOf(minCard)
        }
        return null
    }
}
