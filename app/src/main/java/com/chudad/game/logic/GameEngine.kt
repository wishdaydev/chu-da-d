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
 * 用于通知 UI 层游戏状态变化（锄大D：发牌即进入出牌）
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
        Player(1, "电脑A", isHuman = false),
        Player(2, "电脑B", isHuman = false),
        Player(3, "电脑C", isHuman = false)
    )

    /** 游戏状态机 */
    val stateMachine = GameStateMachine()

    /** 当前 AI 难度（由主界面难度选择决定，新开对局时应用到 3 个 AI 玩家） */
    var aiDifficulty: Difficulty = Difficulty.NORMAL

    /**
     * 大师模式「玩家提示 AI 策略」：人类点「提示」时是否使用大师全信息策略。
     * 电脑玩家在大师模式下固定走全信息搜索，不再提供开关。
     */
    var playerHintStrategy: Difficulty = Difficulty.NORMAL

    /** 回调接口 */
    var callback: GameEngineCallback? = null

    /** 当前选中的手牌索引集合（CopyOnWrite：绘制线程与 UI 线程并发读写安全） */
    val selectedCardIndices = java.util.concurrent.CopyOnWriteArraySet<Int>()

    /** 4 位玩家各 rank 已打出张数（仅凭公开出牌记录推算，不做不可见推断） */
    private val playedByPlayer: Array<IntArray> = Array(PLAYER_COUNT) { IntArray(16) }

    /** 大师模式搜索「局代号」：开新局/退回菜单自增，用于丢弃后台搜索的过期结果 */
    @Volatile
    private var searchEpoch = 0

    /**
     * 本次进程内是否已经开过第一局。
     * 仅第一局的首手强制包含方块 3；后续局按规则应由上局冠军先出，
     * 本项目未实现跨局记忆，仍由方块 3 持有者先出，但不再强制首手带方块 3。
     */
    private var hasStartedFirstGame = false

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
        MasterSearch.clearCache()           // 手数缓存按手牌掩码索引，跨局零命中，开局即清
        players.forEach { it.handCards.clear() }
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
        // 仅整场第一局要求首手带方块 3；后续局不再要求
        stateMachine.requireDiamond3Lead = !hasStartedFirstGame
        hasStartedFirstGame = true
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
        // 手牌排序：人类按「牌组」聚类展示（同点成束、束间按张数升序、单张在左、大束在右）；
        // 其余三家不展示单牌，仅按点数排序便于稳定处理
        sortHandByGroup(0)
        players.filter { it.index != 0 }.forEach {
            it.handCards.sortWith(compareBy({ c: Card -> c.rank }, { c: Card -> c.suit.order }))
        }
    }

    /** 把牌列表按「牌组聚类 + 从大到小」原地重排：同花大顺→四带一→葫芦→同花→顺子→三条→对子→单张，
     *  组内按牌型强度升序（单张最左、同花大顺最右），同强度按点数升序（大点数在右），
     *  同点数再按组内最大花色降序（♠ 在最右）。与人类手牌展示规则完全一致。 */
    private fun clusterSort(cards: MutableList<Card>) {
        val remaining = cards.toMutableList()
        val groups = mutableListOf<ComboGroup>()

        // 1. 同花大顺（最强，level 7）
        extractStraights(remaining, groups, straightFlush = true, level = 7)
        // 2. 四带一（level 6，主牌取最大四条，配牌取不拆其它 5 张牌组的最小单张）
        extractFourWithOne(remaining, groups)
        // 3. 葫芦（level 5，主牌取最大三条，配牌取不拆其它 5 张牌组的最小对子；
        //    强于同花，须在同花/顺子之前抽取）
        extractFullHouse(remaining, groups)
        // 4. 同花（level 4，强于顺子，须在顺子之前抽取）
        extractFlushes(remaining, groups)
        // 5. 顺子（level 3）
        extractStraights(remaining, groups, straightFlush = false, level = 3)
        // 6. 三条（level 2）
        extractByRankCount(remaining, groups, 3, 2)
        // 7. 对子（level 1）
        extractByRankCount(remaining, groups, 2, 1)
        // 8. 剩余单张（level 0）
        for (c in remaining) groups.add(ComboGroup(0, c.rank, listOf(c)))

        // 排序：先按牌型强度升序（单张最左、同花大顺最右），
        // 同强度内按点数升序（大点数在右），同点数再按组内最大花色降序（♠ 在最右）
        val ordered = groups
            .sortedWith(
                compareBy<ComboGroup> { it.level }
                    .thenBy { it.mainRank }
                    .thenByDescending { g -> g.cards.maxOf { it.suit.order } }
            )
            .flatMap { g -> g.cards.sortedWith(compareBy({ it.rank }, { it.suit.order })) }
        cards.clear()
        cards.addAll(ordered)
    }

    /** 仅人类手牌在游戏中需要聚类展示：发牌与出牌后调用，原地重排其 [handCards] */
    private fun sortHandByGroup(playerIndex: Int) {
        val p = players.getOrNull(playerIndex) ?: return
        if (p.index != 0) return   // 仅人类手牌需要聚类展示
        clusterSort(p.handCards)
    }

    /** 返回某玩家手牌按「牌组聚类 + 从大到小」排序后的拷贝（不改变原手牌顺序），供结算画面展示用 */
    fun getGroupedHand(playerIndex: Int): List<Card> {
        val copy = players.getOrNull(playerIndex)?.handCards?.toMutableList() ?: mutableListOf()
        clusterSort(copy)
        return copy
    }

    /** 一组被聚类出来的牌：level 为牌型强度（越大越强），mainRank 用于同强度内排序 */
    private data class ComboGroup(val level: Int, val mainRank: Int, val cards: List<Card>)

    /** 同点聚束：把出现次数 >= [count] 的牌点各取 [count] 张作为一个牌组（对子/三条/炸弹）。
     *  按点数降序遍历（大牌优先成组），同点多张时优先取大花色（♠ 优先留在组内）。 */
    private fun extractByRankCount(
        remaining: MutableList<Card>,
        groups: MutableList<ComboGroup>,
        count: Int,
        level: Int
    ) {
        val byRank = remaining.groupBy { it.rank }
        for ((rank, cards) in byRank.entries.sortedByDescending { it.key }) {
            if (cards.size >= count) {
                val taken = cards.sortedByDescending { it.suit.order }.take(count)
                groups.add(ComboGroup(level, rank, taken))
                remaining.removeAll(taken)
            }
        }
    }

    /**
     * 四带一（炸弹，level 6）：主牌取「点数最大」的四条；配牌（1 张）取「不拆散其它 5 张牌组」的
     * 最小单张——优先选不属于任何顺子/同花/同花顺候选的牌，其次点数最小、花色最大，
     * 使四带一尽量不破坏更强牌型。
     */
    private fun extractFourWithOne(remaining: MutableList<Card>, groups: MutableList<ComboGroup>) {
        val byRank = remaining.groupBy { it.rank }
        val fourEntry = byRank.entries.filter { it.value.size >= 4 }.maxByOrNull { it.key } ?: return
        val fourCards = fourEntry.value.sortedByDescending { it.suit.order }.take(4)

        val others = remaining.filter { it.rank != fourEntry.key }
        if (others.isEmpty()) return   // 无配牌则不组四带一（四条留待后续单独成组）
        // 在主牌之外判断「其它 5 张牌组」，避免主牌自身的同花/顺子贡献干扰配牌选择
        val inFive = cardsInFiveCardCombos(others)
        // 优先：不在其它 5 张牌型候选中（不拆散更强牌组）；其次：点数最小、花色最大
        val kicker = others.minWithOrNull(
            compareBy<Card> { c -> if (c in inFive) 1 else 0 }
                .thenBy { c -> c.rank }
                .thenByDescending { c -> c.suit.order }
        ) ?: return
        val combo = fourCards + kicker
        groups.add(ComboGroup(6, fourEntry.key, combo))
        remaining.removeAll(combo)
    }

    /**
     * 葫芦（三带二，level 5）：主牌取「点数最大」的三条；配牌（对子）取「不拆散其它 5 张牌组」的
     * 最小对子——优先选两张都不属于任何顺子/同花/同花顺候选的对子，其次点数最小。
     * 若没有可配的对子，则不组葫芦（三条留给后续单独成组）。
     */
    private fun extractFullHouse(remaining: MutableList<Card>, groups: MutableList<ComboGroup>) {
        val byRank = remaining.groupBy { it.rank }
        val tripleEntry = byRank.entries.filter { it.value.size >= 3 }.maxByOrNull { it.key } ?: return
        val tripleCards = tripleEntry.value.sortedByDescending { it.suit.order }.take(3)

        val pairCandidates = byRank.entries.filter { it.key != tripleEntry.key && it.value.size >= 2 }
        if (pairCandidates.isEmpty()) return   // 没有可配对子 → 不组葫芦

        // 在主牌之外判断「其它 5 张牌组」，避免主牌自身的同花/顺子贡献干扰配牌选择
        val inFive = cardsInFiveCardCombos(remaining - tripleCards)
        // 候选对子的两张都不在其它 5 张牌组候选中 → 优先（值 0）；否则值 1；同档再比点数最小
        val pairEntry = pairCandidates.minWithOrNull(
            compareBy<Map.Entry<Int, List<Card>>> { e ->
                if (e.value.take(2).any { it in inFive }) 1 else 0
            }.thenBy { e -> e.key }
        ) ?: return
        val pairCards = pairEntry.value.sortedByDescending { it.suit.order }.take(2)

        val combo = tripleCards + pairCards
        groups.add(ComboGroup(5, tripleEntry.key, combo))
        remaining.removeAll(combo)
    }

    /**
     * 返回 [cards] 中「可参与组成 5 张牌型（顺子/同花/同花顺）」的牌集合：
     * - 某花色 ≥5 张 → 这些同花色牌都算（任意 5 张即可组成同花）；
     * - 某连续 5 点段各点数至少有一张（含 A-2-3-4-5 轮式）→ 这些牌都算。
     * 用于葫芦/四带一配牌选择时「尽量不拆散其它 5 张牌组」。
     */
    private fun cardsInFiveCardCombos(cards: List<Card>): Set<Card> {
        val result = mutableSetOf<Card>()
        // 同花 / 同花顺：每个花色 ≥5 张即全部计入（任意 5 张组成同花）
        for (s in Suit.values()) {
            val suited = cards.filter { it.suit == s }
            if (suited.size >= 5) result.addAll(suited)
        }
        // 顺子 / 同花顺：连续 5 点段（2 仅作 A-2-3-4-5 轮式，不进普通顺子）
        val rankSet = cards.map { it.rank }.toSet()
        for (start in 10 downTo 3) {
            val seq = (start..start + 4)
            if (seq.all { it in rankSet }) result.addAll(cards.filter { it.rank in seq })
        }
        // A-2-3-4-5 轮式（A=14, 2=15）
        val wheel = listOf(3, 4, 5, 14, 15)
        if (wheel.all { it in rankSet }) result.addAll(cards.filter { it.rank in wheel })
        return result
    }

    /** 同花：同花色 >=5 张，取点数最大的 5 张作为一个牌组（level 4）。
     *  大牌优先成组使同花组 mainRank 更大（展示更靠右），剩最小单张；花色按 order 降序（♠ 优先成组）。 */
    private fun extractFlushes(remaining: MutableList<Card>, groups: MutableList<ComboGroup>) {
        val bySuit = remaining.groupBy { it.suit }
            .entries
            .sortedByDescending { it.key.order }
        for ((_, cards) in bySuit) {
            if (cards.size >= 5) {
                val taken = cards
                    .sortedWith(compareByDescending<Card> { it.rank }.thenByDescending { it.suit.order })
                    .take(5)
                groups.add(ComboGroup(4, taken.maxOf { it.rank }, taken))
                remaining.removeAll(taken)
            }
        }
    }

    /**
     * 顺子 / 同花顺：从剩余牌中反复抽取 5 张连续的牌组成牌组。
     * @param straightFlush true 时只抽「同花色」的连续五张（同花顺）；false 时抽任意花色的连续五张（顺子）。
     */
    private fun extractStraights(
        remaining: MutableList<Card>,
        groups: MutableList<ComboGroup>,
        straightFlush: Boolean,
        level: Int
    ) {
        while (true) {
            val taken = if (straightFlush) {
                // 同花顺：花色按 order 降序（♠ 最大优先成组）；同花色同点唯一，直接取
                remaining.groupBy { it.suit }
                    .entries
                    .sortedByDescending { it.key.order }
                    .map { it.value }
                    .firstNotNullOfOrNull { cards ->
                        val seq = findStraightRun(cards.map { it.rank }.toSet()) ?: return@firstNotNullOfOrNull null
                        seq.map { r -> cards.first { it.rank == r } }
                    }
            } else {
                val seq = findStraightRun(remaining.map { it.rank }.toSet()) ?: return
                // 同点数多张时优先取最小花色（♦ 最小），把大花色（♠）留给同花组
                seq.map { r -> remaining.filter { it.rank == r }.minByOrNull { it.suit.order }!! }
            }
            if (taken == null || taken.isEmpty()) return
            groups.add(ComboGroup(level, taken.maxOf { it.rank }, taken))
            remaining.removeAll(taken)
        }
    }

    /**
     * 在给定点数集合中找一段「连续五张」：从大点数窗口向小扫描（优先 4-8 而非 3-7），
     * 使大点数的顺子优先成组、把小牌留给对子/三条；或特殊顺子 A-2-3-4-5。
     * 返回所选手点（无序），找不到返回 null。
     */
    private fun findStraightRun(ranks: Set<Int>): List<Int>? {
        for (start in 10 downTo 3) {
            val seq = (start..start + 4)
            if (seq.all { it in ranks }) return seq.toList()
        }
        val wheel = listOf(14, 15, 3, 4, 5)   // A-2-3-4-5（A=14, 2=15），仅在无更大普通顺时选用
        if (wheel.all { it in ranks }) return wheel
        return null
    }

    // ============================ 人类操作 ============================

    /** 人类玩家出牌 */
    fun humanPlay(selectedCards: List<Card>) {
        if (stateMachine.phase != GamePhase.PLAYING) return
        if (stateMachine.currentPlayerIndex != 0) return

        val group = CardRuleEngine.identify(selectedCards)
        if (group.type == CardType.INVALID) return
        if (!CardRuleEngine.isValidPlay(group, stateMachine.lastPlayedGroup)) return
        if (isDiamond3LeadRequired() && !containsDiamond3(selectedCards)) return
        if (mustTopBig(0) && group.type == CardType.SINGLE && !isMaxHandSingle(0, selectedCards[0])) return

        processPlay(0, selectedCards, group)
    }

    /** 人类玩家不出 */
    fun humanPass() {
        if (stateMachine.phase != GamePhase.PLAYING) return
        if (stateMachine.currentPlayerIndex != 0) return
        if (stateMachine.mustPlay()) return   // 自由出牌 / 自己压自己时不能过
        if (mustTopBigPlay(0)) return         // 顶大义务：能压必须出最大单张，不可过牌

        recordTopBigFailIfNeeded(0)
        processPass(0)
    }

    // ============================ 流程性规则（方块3 / 顶大） ============================

    /** 本局首手是否必须包含方块 3（供 UI 预校验） */
    fun isDiamond3LeadRequired(): Boolean =
        stateMachine.requireDiamond3Lead && stateMachine.playHistory.isEmpty()

    /**
     * 「顶大」义务：当前玩家的下家是否已报单（剩 1 张）。
     * 有义务时若选择出单张，必须是手中最大的单张；出其它牌型不受限。
     */
    fun mustTopBig(playerIndex: Int): Boolean =
        players[(playerIndex + 1) % PLAYER_COUNT].cardCount == 1

    /**
     * 顶大强制出牌：下家报单（剩 1 张）、本轮为单张、且手中确有能压过它的单张
     * → 该玩家「必须出最大的单张」，不得过牌。
     * 这是比 [mustTopBig] 更硬的义务（mustTopBig 只约束「出单张时必须最大」，本函数约束「必须出」）。
     */
    fun mustTopBigPlay(playerIndex: Int): Boolean =
        mustTopBig(playerIndex) && canTopBigWithSingle(playerIndex)

    /** 该玩家是否可「顶大」：桌面上是单张，且手中有能压过它的单张 */
    private fun canTopBigWithSingle(playerIndex: Int): Boolean {
        val last = stateMachine.lastPlayedGroup ?: return false
        if (last.type != CardType.SINGLE) return false
        return players[playerIndex].handCards.any { c ->
            CardRuleEngine.isValidPlay(
                CardGroup(CardType.SINGLE, c.rank, 1, listOf(c), c.suit.order),
                last
            )
        }
    }

    /** 出牌是否为该玩家手中最大单张（按点数、花色） */
    private fun isMaxHandSingle(playerIndex: Int, card: Card): Boolean {
        val max = players[playerIndex].handCards
            .maxWithOrNull(compareBy({ it.rank }, { it.suit.order })) ?: return false
        return max.id == card.id
    }

    /**
     * 顶大失职标记：下家报单时选择「不出」，且手中本有能压过桌面单张的牌 → 记一次失职。
     * 该玩家一旦实际出牌（任意牌型）即清除，符合「不强制只能出单张」的语义。
     */
    private fun recordTopBigFailIfNeeded(playerIndex: Int) {
        if (!mustTopBig(playerIndex)) return
        if (canTopBigWithSingle(playerIndex)) stateMachine.topBigFailIndex = playerIndex
    }

    private fun containsDiamond3(cards: List<Card>): Boolean =
        cards.any { it.rank == 3 && it.suit == Suit.DIAMOND }

    // ============================ 出牌 / 过牌核心 ============================

    private fun processPlay(playerIndex: Int, cards: List<Card>, group: CardGroup) {
        // 已实际出牌 → 顶大失职标记作废（本规则只惩罚「能顶却选择不出」）
        if (stateMachine.topBigFailIndex == playerIndex) stateMachine.topBigFailIndex = -1
        val player = players[playerIndex]
        cards.forEach { card ->
            player.handCards.removeIf { it.id == card.id }
            playedByPlayer[playerIndex][card.rank]++
        }

        val nextPlayer = stateMachine.processPlay(playerIndex, group)
        selectedCardIndices.clear()
        // 人类出牌后按「牌组」重新聚类排序（此时选择已清空，无索引错位）
        if (playerIndex == 0) sortHandByGroup(0)
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
     * 该 AI 是否走大师全信息搜索：大师模式下三个电脑玩家固定使用（无开关）。
     */
    private fun shouldUseMasterPlay(playerIndex: Int): Boolean =
        aiDifficulty == Difficulty.MASTER && !players[playerIndex].isHuman

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
                    opponentCardCounts = getOpponentCardCounts(playerIndex),
                    unseenCounts = getUnseenRankCounts(playerIndex),
                    requireDiamond3 = isDiamond3LeadRequired(),
                    mustTopBig = mustTopBig(playerIndex)
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
                nodeLimit = MASTER_NODE_LIMIT,
                // 重开局 / 退回菜单后旧搜索立即让出线程，避免长预算下阻塞新一局
                cancelled = { epoch != searchEpoch }
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
    private fun applyAIDecision(playerIndex: Int, decisionIn: CardGroup?) {
        val player = players[playerIndex]
        var decision = decisionIn

        // 首手必须带方块 3：AI 决策未包含则兜底出方块 3 单张
        if (isDiamond3LeadRequired()) {
            val d3 = player.handCards.firstOrNull { it.rank == 3 && it.suit == Suit.DIAMOND }
            if (d3 != null && (decision == null || decision.cards.none { it.id == d3.id })) {
                decision = CardGroup(CardType.SINGLE, 3, 1, listOf(d3), d3.suit.order)
            }
        }

        // 顶大：下家报单时，AI 若出单张就必须出最大单张
        if (decision != null && decision.type == CardType.SINGLE && mustTopBig(playerIndex)) {
            val max = player.handCards
                .maxWithOrNull(compareBy({ it.rank }, { it.suit.order }))
            if (max != null && decision.cards.single().id != max.id) {
                decision = CardGroup(CardType.SINGLE, max.rank, 1, listOf(max), max.suit.order)
            }
        }

        // 顶大强制：下家报单且手中能压过桌面单张时，即使策略判「过牌」也必须出最大单张
        if ((decision == null || decision.type == CardType.INVALID) && mustTopBigPlay(playerIndex)) {
            val max = player.handCards
                .maxWithOrNull(compareBy({ it.rank }, { it.suit.order }))
            if (max != null) {
                decision = CardGroup(CardType.SINGLE, max.rank, 1, listOf(max), max.suit.order)
            }
        }

        if (decision != null && decision.type != CardType.INVALID) {
            processPlay(playerIndex, decision.cards, decision)
            return
        }
        if (stateMachine.mustPlay()) {
            // 必须出牌时的兜底：顶大义务下改出最大单张，避免兜底小牌违规
            val fallback = if (mustTopBig(playerIndex)) {
                player.handCards.maxWithOrNull(compareBy({ it.rank }, { it.suit.order }))
            } else {
                player.handCards.minWithOrNull(compareBy({ it.rank }, { it.suit.order }))
            }
            if (fallback != null) {
                val single = CardGroup(
                    CardType.SINGLE, fallback.rank, 1,
                    listOf(fallback), fallback.suit.order
                )
                processPlay(playerIndex, listOf(fallback), single)
                return
            }
        }
        recordTopBigFailIfNeeded(playerIndex)
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

    /** 顶大失职玩家索引（结算用）：-1 表示本轮无失职 */
    val topBigFailIndex: Int get() = stateMachine.topBigFailIndex

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
            passes = stateMachine.passCount,
            currentPlayerIndex = stateMachine.currentPlayerIndex,
            // 把流程性规则一并交给搜索，避免搜出的最优解被下面的兜底逻辑改写
            mustContainDiamond3 = isDiamond3LeadRequired(),
            mustTopBig = mustTopBig(myIndex)
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
                opponentCardCounts = getOpponentCardCounts(0),
                unseenCounts = getUnseenRankCounts(0),
                requireDiamond3 = isDiamond3LeadRequired(),
                mustTopBig = mustTopBig(0)
            )
        }

        // 流程性约束：首手必须带方块 3；顶大时若出单张必须最大
        if (isDiamond3LeadRequired()) {
            val d3 = hand.firstOrNull { it.rank == 3 && it.suit == Suit.DIAMOND }
            if (d3 != null && (decision == null || decision.cards.none { it.id == d3.id })) {
                return listOf(d3)
            }
        }
        if (decision != null && decision.type == CardType.SINGLE && mustTopBig(0)) {
            val max = hand.maxWithOrNull(compareBy({ it.rank }, { it.suit.order }))
            if (max != null) return listOf(max)
        }

        if (decision != null && decision.type != CardType.INVALID) return decision.cards

        // 兜底：策略判定「过牌 / 无解」时，只要手中仍有合法走法，就提示「最小可打的牌型」——
        // 需要时拆散对子/三条、甚至动用 2 也在所不惜，避免明明能出却提示「没有能出的牌」。
        // 仅当确实一手都出不了（findAllValidPlays 为空）才返回 null。
        val legal = CardRuleEngine.findAllValidPlays(hand, lastPlay)
        if (legal.isEmpty()) return null

        // 顶大义务下必须给出「最大单张」（能压的情况下），与引擎强制出牌规则一致
        if (mustTopBigPlay(0)) {
            val maxSingle = legal.filter { it.type == CardType.SINGLE }
                .maxByOrNull { it.mainRank * 16 + it.topSuitOrder }
            if (maxSingle != null) return maxSingle.cards
        }
        // 否则取「最小可打」：主体点数最小、其次花色最小（同张数/同型内比较）
        val pick = legal.minWithOrNull(
            compareBy<CardGroup> { it.mainRank }.thenBy { it.topSuitOrder }
        )
        return pick?.cards
    }
}
