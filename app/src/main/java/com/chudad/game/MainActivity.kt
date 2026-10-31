package com.chudad.game

import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.Window
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import com.chudad.game.logic.GameEngine
import com.chudad.game.logic.GameEngineCallback
import com.chudad.game.logic.ScoreCalculator
import com.chudad.game.model.Card
import com.chudad.game.model.CardGroup
import com.chudad.game.model.CardType
import com.chudad.game.model.Difficulty
import com.chudad.game.model.Suit
import com.chudad.game.ui.GameSurfaceView
import org.json.JSONObject
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

/**
 * 锄大D 游戏主 Activity
 * 使用 SurfaceView 进行 Canvas 绘制，实现完整的游戏 UI。
 *
 * 发牌动画结束后直接进入出牌阶段；
 * 统计按模式（普通/大师）独立，不区分子角色；大师策略表示「电脑是否全信息搜索」。
 */
class MainActivity : AppCompatActivity(), GameEngineCallback {

    /** 游戏引擎 */
    private lateinit var gameEngine: GameEngine

    /** 游戏绘制视图 */
    private lateinit var gameSurfaceView: GameSurfaceView

    /** 按模式分别统计：积分 / 局数 / 胜场（普通、大师各自独立） */
    private data class GameStats(
        var score: Int = 0, var games: Int = 0, var wins: Int = 0
    )

    private val stats = mutableMapOf(
        Difficulty.NORMAL to GameStats(),
        Difficulty.MASTER to GameStats()
    )

    /** 统计持久化：使用 Activity 级 SharedPreferences，进程重启后仍能恢复 */
    private val statsPrefs by lazy { getPreferences(MODE_PRIVATE) }

    private fun statKey(mode: Difficulty, suffix: String) = "${mode.name.lowercase()}_$suffix"

    private fun loadStats() {
        for (mode in stats.keys) {
            val st = stats[mode]!!
            st.score = statsPrefs.getInt(statKey(mode, "score"), 0)
            st.games = statsPrefs.getInt(statKey(mode, "games"), 0)
            st.wins = statsPrefs.getInt(statKey(mode, "wins"), 0)
        }
    }

    private fun saveStats() {
        statsPrefs.edit().apply {
            for ((mode, st) in stats) {
                putInt(statKey(mode, "score"), st.score)
                putInt(statKey(mode, "games"), st.games)
                putInt(statKey(mode, "wins"), st.wins)
            }
            apply()
        }
    }

    /**
     * 大师模式 AI 策略持久化（独立文件，与普通统计无关）
     *
     * 存储键 `master_ai_strategy` 表示「大师模式下电脑玩家是否使用全信息搜索」
     * （锄大D 四人各自为战，不存在队友概念）。
     */
    private val strategyPrefs by lazy { getSharedPreferences("master_strategy", MODE_PRIVATE) }
    private var aiMasterStrategy = Difficulty.NORMAL
    private var playerHintStrategy = Difficulty.NORMAL
    private var masterThinkSeconds = 5

    private fun loadStrategy(key: String, default: Difficulty): Difficulty {
        val s = strategyPrefs.getString(key, default.name) ?: default.name
        return try { Difficulty.valueOf(s) } catch (_: IllegalArgumentException) { default }
    }

    private fun loadMasterStrategy() {
        aiMasterStrategy = loadStrategy("master_ai_strategy", Difficulty.NORMAL)
        playerHintStrategy = loadStrategy("player_hint_strategy", Difficulty.NORMAL)
        masterThinkSeconds = strategyPrefs.getInt("master_think_seconds", 5)
    }

    private fun saveMasterStrategy() {
        strategyPrefs.edit().apply {
            putString("master_ai_strategy", aiMasterStrategy.name)
            putString("player_hint_strategy", playerHintStrategy.name)
            putInt("master_think_seconds", masterThinkSeconds)
            apply()
        }
    }

    // ===== 备份与还原（SAF：无需存储权限，文件存于用户自选位置，重装后可导入） =====

    /** 导出：启动系统「创建文档」选择器，由用户选择保存位置与文件名 */
    private val exportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri -> uri ?: return@registerForActivityResult; exportBackup(uri) }

    /** 导入：启动系统「打开文档」选择器，由用户选择备份文件 */
    private val importLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri ?: return@registerForActivityResult; importBackup(uri) }

    private fun launchExportBackup() {
        val time = SimpleDateFormat("yyyyMMddHHmm", Locale.getDefault()).format(Date())
        exportLauncher.launch("chudad_backup_$time.json")
    }

    private fun launchImportBackup() {
        importLauncher.launch(arrayOf("application/json", "*/*"))
    }

    /** 组装备份 JSON（设置 + 双模式统计） */
    private fun buildBackupJson(): String {
        val root = JSONObject()
        root.put("schema", 2)
        root.put("appVersion", BuildConfig.VERSION_NAME)
        root.put("exportedAt", SimpleDateFormat("yyyyMMddHHmm", Locale.getDefault()).format(Date()))
        val settings = JSONObject()
        settings.put("master_ai_strategy", aiMasterStrategy.name)
        settings.put("player_hint_strategy", playerHintStrategy.name)
        settings.put("masterThinkSeconds", masterThinkSeconds)
        root.put("settings", settings)
        val statsObj = JSONObject()
        for ((mode, st) in stats) {
            val o = JSONObject()
            o.put("score", st.score)
            o.put("games", st.games)
            o.put("wins", st.wins)
            statsObj.put(mode.name, o)
        }
        root.put("stats", statsObj)
        return root.toString(2)
    }

    /** 解析备份 JSON，校验 schema 与取值范围；失败时抛异常 */
    private data class ImportedBackup(
        val aiMaster: Difficulty, val hint: Difficulty, val sec: Int,
        val stats: Map<Difficulty, GameStats>
    )

    private fun parseBackupJson(text: String): ImportedBackup {
        val root = JSONObject(text)
        val schema = root.optInt("schema", 1)
        if (schema !in 1..2) throw IllegalArgumentException("不支持的备份格式")
        val settings = root.getJSONObject("settings")
        val aiMaster = Difficulty.valueOf(settings.optString("master_ai_strategy", Difficulty.NORMAL.name))
        val hint = Difficulty.valueOf(settings.optString("player_hint_strategy", Difficulty.NORMAL.name))
        val sec = settings.optInt("masterThinkSeconds", 5).coerceIn(2, 60)
        val statsObj = root.optJSONObject("stats") ?: JSONObject()
        val imported = mutableMapOf<Difficulty, GameStats>()
        for (mode in listOf(Difficulty.NORMAL, Difficulty.MASTER)) {
            if (statsObj.has(mode.name)) {
                val o = statsObj.getJSONObject(mode.name)
                imported[mode] = GameStats(
                    o.optInt("score", 0), o.optInt("games", 0), o.optInt("wins", 0)
                )
            }
        }
        return ImportedBackup(aiMaster, hint, sec, imported)
    }

    private fun exportBackup(uri: Uri) {
        thread {
            try {
                val json = buildBackupJson()
                contentResolver.openOutputStream(uri)?.use { os ->
                    os.write(json.toByteArray(Charsets.UTF_8))
                } ?: throw IOException("无法写入文件")
                runOnUiThread { toast("已导出备份") }
            } catch (_: Exception) {
                runOnUiThread { toast("导出失败") }
            }
        }
    }

    /** 待确认的导入数据：解析成功后暂存，用户二次确认后再应用 */
    private var pendingImport: ImportedBackup? = null

    private fun importBackup(uri: Uri) {
        thread {
            try {
                val text = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                    ?: throw IOException("无法读取文件")
                val parsed = parseBackupJson(text)
                runOnUiThread {
                    pendingImport = parsed
                    gameSurfaceView.requestImportConfirm()
                }
            } catch (_: Exception) {
                runOnUiThread { toast("导入失败：文件无效") }
            }
        }
    }

    /** 用户确认导入后应用：覆盖设置与统计，并同步引擎与 UI */
    private fun applyImportedBackup() {
        val data = pendingImport ?: return
        pendingImport = null
        aiMasterStrategy = data.aiMaster
        playerHintStrategy = data.hint
        masterThinkSeconds = data.sec
        for ((m, st) in data.stats) stats[m] = st
        saveMasterStrategy()
        saveStats()
        gameEngine.aiMasterStrategy = data.aiMaster
        gameEngine.playerHintStrategy = data.hint
        gameEngine.masterDeadlineMs = data.sec * 1000L
        gameSurfaceView.setMasterStrategy(data.aiMaster, data.hint)
        gameSurfaceView.setMasterThinkSeconds(data.sec)
        for ((m, st) in stats) gameSurfaceView.setModeStats(m, st.score, st.games, st.wins)
        toast("已导入备份")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        loadStats()
        loadMasterStrategy()

        try {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
        } catch (_: Throwable) {}

        @Suppress("DEPRECATION")
        window.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        )

        gameSurfaceView = GameSurfaceView(this)

        gameEngine = GameEngine()
        gameEngine.callback = this
        gameSurfaceView.gameEngine = gameEngine
        for ((mode, st) in stats) {
            gameSurfaceView.setModeStats(mode, st.score, st.games, st.wins)
        }
        gameSurfaceView.setDisplayMode(Difficulty.NORMAL)

        // 主界面设置：重置某模式统计数据（清零并持久化）
        gameSurfaceView.onResetStats = { mode ->
            stats[mode]?.let {
                it.score = 0; it.games = 0; it.wins = 0
            }
            saveStats()
            gameSurfaceView.setModeStats(mode, 0, 0, 0)
            gameSurfaceView.refresh()
        }

        // 恢复大师模式 AI 策略到引擎与 UI 显示
        gameEngine.aiMasterStrategy = aiMasterStrategy
        gameEngine.playerHintStrategy = playerHintStrategy
        gameEngine.masterDeadlineMs = masterThinkSeconds * 1000L
        gameSurfaceView.setMasterStrategy(aiMasterStrategy, playerHintStrategy)
        gameSurfaceView.setMasterThinkSeconds(masterThinkSeconds)

        // 主界面设置：大师模式 AI 策略变更（持久化保存并实时生效）
        gameSurfaceView.onMasterStrategyChange = { aiMaster, hint ->
            aiMasterStrategy = aiMaster
            playerHintStrategy = hint
            gameEngine.aiMasterStrategy = aiMaster
            gameEngine.playerHintStrategy = hint
            saveMasterStrategy()
            gameSurfaceView.refresh()
        }

        // 主界面设置：大师模式 AI 思考时间
        gameSurfaceView.onMasterThinkTimeChange = { sec ->
            masterThinkSeconds = sec
            gameEngine.masterDeadlineMs = sec * 1000L
            saveMasterStrategy()
            gameSurfaceView.refresh()
        }

        // 备份与还原
        gameSurfaceView.onExportRequested = { launchExportBackup() }
        gameSurfaceView.onImportRequested = { launchImportBackup() }
        gameSurfaceView.onImportConfirmed = { applyImportedBackup() }

        setContentView(gameSurfaceView)
        applyImmersiveFullscreen()
    }

    /**
     * 沉浸式全屏 + 挖孔/刘海屏适配
     */
    private fun applyImmersiveFullscreen() {
        try {
            applyImmersiveFullscreenInner()
        } catch (_: Throwable) {}
    }

    private fun applyImmersiveFullscreenInner() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                window.attributes = window.attributes.apply {
                    layoutInDisplayCutoutMode = 3 // LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                }
            } catch (_: Throwable) {
                try {
                    window.attributes = window.attributes.apply {
                        layoutInDisplayCutoutMode =
                            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                    }
                } catch (_: Throwable) {}
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val controller = window.insetsController
            controller?.hide(WindowInsets.Type.navigationBars())
            controller?.systemBarsBehavior =
                WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            WindowCompat.setDecorFitsSystemWindows(window, false)
            @Suppress("DEPRECATION")
            window.setNavigationBarColor(android.graphics.Color.TRANSPARENT)
            try {
                window.isNavigationBarContrastEnforced = false
            } catch (_: Throwable) {}
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                )
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyImmersiveFullscreen()
    }

    override fun onResume() {
        super.onResume()
        if (gameEngine.stateMachine.phase == com.chudad.game.state.GamePhase.DEALING
            && gameEngine.players[0].handCards.isEmpty()) {
            gameEngine.startNewGame()
        }
    }

    // ===== GameEngineCallback 实现 =====

    override fun onDealingComplete() {
        runOnUiThread {
            // 3 秒手牌逐张滑入动画；动画结束后直接进入出牌阶段
            gameSurfaceView.startHandReveal {
                gameEngine.beginPlaying()
            }
        }
    }

    override fun onPlayerTurn(playerIndex: Int) {
        runOnUiThread { gameSurfaceView.refresh() }
    }

    override fun onPlayerPlay(playerIndex: Int, cards: List<Card>, group: CardGroup) {
        runOnUiThread {
            gameSurfaceView.setTablePlayedCards(playerIndex, cards)
            // 四带一 / 同花顺 是锄大D 中的强力牌型，用重音效；其余用普通甩牌声
            val isStrong = group.type == CardType.FOUR_WITH_ONE ||
                    group.type == CardType.STRAIGHT_FLUSH
            gameSurfaceView.playCardSound(isStrong = isStrong)

            val isLastCard = gameEngine.players[playerIndex].handCards.size == 1
            val isBig = isStrong || cards.size >= 5
            if (isBig && !isLastCard) {
                gameSurfaceView.playBigVibration()
            }
            if (isLastCard) {
                gameSurfaceView.playLastCardAlert()
                gameSurfaceView.playLastCardVibration()
            }
            gameSurfaceView.refresh()
        }
    }

    override fun onPlayerPass(playerIndex: Int) {
        runOnUiThread {
            gameSurfaceView.registerPassBubble(playerIndex)
            gameSurfaceView.refresh()
        }
    }

    override fun onGameOver(winnerIndex: Int) {
        runOnUiThread {
            val isHumanWin = winnerIndex == 0
            val playerCount = gameEngine.players.size

            // 每家剩余张数、是否含黑桃2（阶梯计分 + 黑桃2翻倍）
            val remaining = IntArray(playerCount) { gameEngine.getCardCount(it) }
            val hasSpadeTwo = BooleanArray(playerCount) { i ->
                gameEngine.players[i].handCards.any { it.rank == 15 && it.suit == Suit.SPADE }
            }
            val cardScores = IntArray(playerCount) { i ->
                ScoreCalculator.scoreFor(remaining[i], hasSpadeTwo[i])
            }

            // 顶大失职：仅当报单者（失职者的下家）最终获胜时生效
            val fail = gameEngine.topBigFailIndex
            val penalized = if (fail in 0 until playerCount &&
                winnerIndex == (fail + 1) % playerCount
            ) fail else -1

            // 四家得分变化（零和）：赢家收三家牌分；顶大失职者独自包赔三家
            val scoreDelta = ScoreCalculator.settle(remaining, hasSpadeTwo, winnerIndex, fail)[0]

            val anyDouble = (0 until playerCount).any {
                cardScores[it] > ScoreCalculator.baseScore(remaining[it])
            }
            val tag = when {
                penalized == 0 -> "顶大失职·包赔"
                penalized > 0 -> "顶大失职·代付"
                anyDouble -> "♠2 翻倍"
                else -> "剩 ${if (isHumanWin) (1 until playerCount).sumOf { remaining[it] } else remaining[0]} 张"
            }

            val mode = gameEngine.aiDifficulty
            val st = stats[mode]!!

            st.score += scoreDelta
            if (isHumanWin) gameSurfaceView.playWinSound() else gameSurfaceView.playLoseSound()

            gameSurfaceView.showSettlement(
                isHumanWin,
                scoreDelta = scoreDelta,
                multiplierText = tag,
                forceShowScore = penalized > 0
            )

            st.games++
            if (isHumanWin) st.wins++
            gameSurfaceView.setModeStats(mode, st.score, st.games, st.wins)
            gameSurfaceView.setDisplayMode(mode)
            gameSurfaceView.refresh()
            saveStats()
        }
    }

    /** 轻量系统提示（用于备份导入/导出等操作反馈） */
    private fun toast(msg: String) {
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()
    }

    override fun onPause() {
        super.onPause()
        saveStats()
        saveMasterStrategy()
    }

    override fun onRequestRefresh() {
        runOnUiThread { gameSurfaceView.refresh() }
    }
}
