package com.chudad.sim

import com.chudad.game.logic.PerfBench

/**
 * 性能基准入口（与 App 内「设置 → 性能测试」跑的是同一份 [PerfBench]）。
 *
 * 用法: ./sim/run-sim.sh perf [budgetMs] [rounds]
 * 示例: ./sim/run-sim.sh perf 300          # 默认 10 组
 *
 * 输出为纯文本报表，可直接与手机端弹窗结果逐行比对，得到「手机 / 沙箱」的算力比。
 */
fun main(args: Array<String>) {
    val budget = args.getOrNull(0)?.toLongOrNull() ?: 300L
    val rounds = args.getOrNull(1)?.toIntOrNull() ?: PerfBench.DEFAULT_ROUNDS

    val cores = Runtime.getRuntime().availableProcessors()
    val info = buildString {
        append("运行环境: JVM ").append(System.getProperty("java.vm.version"))
        append(" / ").append(System.getProperty("os.name")).append(' ').append(System.getProperty("os.arch"))
        append(" / 可用核数 ").append(cores)
    }

    println("跑基准中（预算 ${budget}ms × $rounds 组（预热 2 组×400ms，不计入））…")
    val report = PerfBench.run(budget, rounds) { done, total ->
        print("\r  进度 $done/$total")
        if (done == total) println()
        System.out.flush()
    }
    println()
    print(PerfBench.Report(report.budgetMs, report.samples, info).toText())
}
