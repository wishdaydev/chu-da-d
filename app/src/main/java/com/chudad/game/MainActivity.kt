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
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import com.chudad.game.logic.GameEngine
import com.chudad.game.logic.GameEngineCallback
import com.chudad.game.logic.PerfBench
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

    /** 备份格式版本：仅校验本版本，旧格式不做兼容 */
    private companion object {
        const val BACKUP_SCHEMA = 3
    }

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
     * 大师模式设置持久化（独立文件，与普通统计无关）
     *
     * 存储键 `player_hint_strategy` 表示「玩家点提示时是否使用大师全信息搜索」。
     * 电脑玩家在大师模式下固定走全信息搜索，不再提供开关，故不再存 `master_ai_strategy`。
     */
    private val strategyPrefs by lazy { getSharedPreferences("master_strategy", MODE_PRIVATE) }
    private var playerHintStrategy = Difficulty.NORMAL
    private var masterThinkSeconds = 5

    private fun loadStrategy(key: String, default: Difficulty): Difficulty {
        val s = strategyPrefs.getString(key, default.name) ?: default.name
        return try { Difficulty.valueOf(s) } catch (_: IllegalArgumentException) { default }
    }

    private fun loadMasterStrategy() {
        playerHintStrategy = loadStrategy("player_hint_strategy", Difficulty.NORMAL)
        masterThinkSeconds = strategyPrefs.getInt("master_think_seconds", 5)
    }

    private fun saveMasterStrategy() {
        strategyPrefs.edit().apply {
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
        root.put("schema", BACKUP_SCHEMA)
        root.put("appVersion", BuildConfig.VERSION_NAME)
        root.put("exportedAt", SimpleDateFormat("yyyyMMddHHmm", Locale.getDefault()).format(Date()))
        val settings = JSONObject()
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
        val hint: Difficulty, val sec: Int,
        val stats: Map<Difficulty, GameStats>
    )

    private fun parseBackupJson(text: String): ImportedBackup {
        val root = JSONObject(text)
        // 只接受当前格式；其他版本的存档一律视为无效，不做兼容
        if (root.optInt("schema", 0) != BACKUP_SCHEMA) {
            throw IllegalArgumentException("备份版本不匹配")
        }
        val settings = root.getJSONObject("settings")
        val hint = try {
            Difficulty.valueOf(settings.getString("player_hint_strategy"))
        } catch (_: IllegalArgumentException) {
            throw IllegalArgumentException("提示策略取值非法")
        }
        val sec = settings.getInt("masterThinkSeconds").coerceIn(2, 60)
        val statsObj = root.getJSONObject("stats")
        val imported = mutableMapOf<Difficulty, GameStats>()
        for (mode in listOf(Difficulty.NORMAL, Difficulty.MASTER)) {
            val o = statsObj.getJSONObject(mode.name)
            imported[mode] = GameStats(
                o.getInt("score"), o.getInt("games"), o.getInt("wins")
            )
        }
        return ImportedBackup(hint, sec, imported)
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
            } catch (e: IllegalArgumentException) {
                // 版本不匹配或字段缺失：提示具体原因，不静默兼容
                val reason = e.message ?: "文件无效"
                runOnUiThread { toast("导入失败：$reason") }
            } catch (_: Exception) {
                runOnUiThread { toast("导入失败：文件无效") }
            }
        }
    }

    /** 用户确认导入后应用：覆盖设置与统计，并同步引擎与 UI */
    private fun applyImportedBackup() {
        val data = pendingImport ?: return
        pendingImport = null
        playerHintStrategy = data.hint
        masterThinkSeconds = data.sec
        for ((m, st) in data.stats) stats[m] = st
        saveMasterStrategy()
        saveStats()
        gameEngine.playerHintStrategy = data.hint
        gameEngine.masterDeadlineMs = data.sec * 1000L
        gameSurfaceView.setHintStrategy(data.hint)
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

        // 恢复大师模式设置到引擎与 UI 显示（电脑策略固定大师，无需恢复）
        gameEngine.playerHintStrategy = playerHintStrategy
        gameEngine.masterDeadlineMs = masterThinkSeconds * 1000L
        gameSurfaceView.setHintStrategy(playerHintStrategy)
        gameSurfaceView.setMasterThinkSeconds(masterThinkSeconds)

        // 主界面设置：「玩家提示ai」策略变更（持久化保存并实时生效）
        gameSurfaceView.onHintStrategyChange = { hint ->
            playerHintStrategy = hint
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

        // 性能测试（把本机算力折算成「预算内能搜多少节点」，与沙箱结果对齐）
        gameSurfaceView.onPerfTestRequested = { showPerfTestDialog() }

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
            if (playerIndex == 0) gameSurfaceView.startHandResort()   // 人类出牌后手牌重新聚类：0.6s 重排动画
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

    // ==================== 性能测试 ====================

    /**
     * 性能测试入口：输入单步思考预算（毫秒）→ 后台跑 10 组开局 → 弹出可复制报表。
     *
     * 为什么需要它：仿真是按「节点预算」跑的（跨设备确定），而手机是按「时间预算」跑的。
     * 两者之间只有一个换算系数，就是本机每秒能搜多少节点——只能实测。
     * 手机跑一次本测试，与沙箱 `./sim/run-sim.sh perf 300` 的数字相除，即得算力比。
     */
    private fun showPerfTestDialog() {
        val density = resources.displayMetrics.density
        val input = android.widget.EditText(this).apply {
            setText("300")
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setSelectAllOnFocus(true)
            textSize = 20f
        }
        val wrap = android.widget.FrameLayout(this).apply {
            setPadding((24 * density).toInt(), (8 * density).toInt(), (24 * density).toInt(), 0)
            addView(
                input,
                android.widget.FrameLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }
        AlertDialog.Builder(this)
            .setTitle("性能测试")
            .setMessage(
                "输入单步思考时间预算（毫秒），建议 300（与沙箱基准同档）。\n\n" +
                    "将跑 10 组标准开局，测第一个出牌的 AI 在该预算内能搜多少节点、多少层，" +
                    "结果可一键复制用于机型对齐。\n\n" +
                    "耗时约「预算 × 12」（含 2 组预热）。"
            )
            .setView(wrap)
            .setPositiveButton("开始") { _, _ ->
                startPerfTest(input.text.toString().toLongOrNull() ?: 300L)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 后台线程跑基准；期间显示不可取消的进度框（10×预算最长可达数十秒，主线程会 ANR） */
    private fun startPerfTest(budgetMs: Long) {
        val budget = budgetMs.coerceIn(50L, 20000L)
        val progress = AlertDialog.Builder(this)
            .setTitle("性能测试中")
            .setMessage("预算 ${budget}ms × 10 组，请稍候…")
            .setCancelable(false)
            .create()
        progress.show()
        thread(name = "perf-bench") {
            val report = try {
                PerfBench.run(budget)
            } catch (t: Throwable) {
                null
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                progress.dismiss()
                if (report == null) {
                    toast("性能测试失败")
                } else {
                    showPerfResult(report)
                }
            }
        }
    }

    /** 结果弹窗：等宽字体 + 可选中 + 一键复制（数字列必须对齐，方便逐行比对） */
    private fun showPerfResult(report: PerfBench.Report) {
        val density = resources.displayMetrics.density
        val full = PerfBench.Report(report.budgetMs, report.samples, perfDeviceInfo())
        val text = full.toText()

        val tv = android.widget.TextView(this).apply {
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = 11f
            setTextIsSelectable(true)
            this.text = text
            setPadding((16 * density).toInt(), (8 * density).toInt(), (16 * density).toInt(), 0)
        }
        val scroll = android.widget.ScrollView(this).apply { addView(tv) }

        AlertDialog.Builder(this)
            .setTitle("性能测试结果")
            .setView(scroll)
            .setPositiveButton("复制") { _, _ ->
                val cm = getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                    as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("perf-bench", text))
                toast("已复制到剪贴板")
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    /** 设备信息行：机型/系统/ABI/核数，用于判断「哪台机器跑的这份数据」 */
    private fun perfDeviceInfo(): String {
        val abi = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            Build.SUPPORTED_ABIS.joinToString("/")
        } else {
            Build.CPU_ABI
        }
        val cores = Runtime.getRuntime().availableProcessors()
        val maxMb = Runtime.getRuntime().maxMemory() / 1024L / 1024L
        return "设备: ${Build.MANUFACTURER} ${Build.MODEL} / Android ${Build.VERSION.RELEASE}" +
            "（SDK ${Build.VERSION.SDK_INT}）/ ABI $abi\n" +
            "CPU 核数 $cores / 堆上限 ${maxMb}MB"
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
