#!/bin/bash
# 大师模式仿真 / 性能基准 harness 编译+运行脚本（纯 JVM，不需要 Android SDK / Gradle）
#
# ── 用法 ────────────────────────────────────────────────────────────────
# 仿真:  ./sim/run-sim.sh [games] [deadlineMs] [nodeLimit] [threads] [baseSeed]
# 基准:  ./sim/run-sim.sh perf [budgetMs] [rounds]      # 与 App 内「性能测试」同一份代码
# 示例:  SIM_NODES=120000 ./sim/run-sim.sh 100 0 0 4 7
#        ./sim/run-sim.sh perf 300
#
# ── 两种预算模式 ────────────────────────────────────────────────────────
# 1) 时间预算（默认）：deadlineMs 为准；nodeLimit 不传＝不限（与线上同档 600 万安全阀）。
#    贴近真机；但节点数随局型/负载浮动（实测约 1.6 倍），且同一 seed 两次跑可能走到不同
#    深度 → 结果不可复现。
# 2) 固定节点预算：设 SIM_NODES=<n>，脚本自动把 deadline 放宽到 600s 并固定节点上限。
#    完全确定性（同 seed 同配置 = 同一结果）、跨设备/跨负载可比；代价是每步耗时浮动。
#    A/B 对比与调参请用这个。标定见 sim/README.md。
set -uo pipefail
cd "$(dirname "$0")/.."

MODE=sim
if [ "${1:-}" = "perf" ]; then
    MODE=perf
    shift
fi

# 固定节点预算模式：把 SIM_NODES 摊到位置参数上，其余参数（games/threads/seed）保持用户所给
if [ "$MODE" = sim ] && [ -n "${SIM_NODES:-}" ]; then
    set -- "${1:-200}" 600000 "$SIM_NODES" "${4:-1}" "${5:-1}"
fi

# JVM 参数：默认堆要显式限制。容器里 JVM 常按宿主机物理内存算 1/4 作默认堆（实测 31GB），
# 一旦超过 cgroup 上限就被内核 OOM kill（表现为无堆栈的 "Killed"）。栈也要放大：4 人搜索递归深。
JAVA_OPTS="${JAVA_OPTS:--Xss32m -Xmx4g}"

OUT=/tmp/chudad-sim
mkdir -p "$OUT"

# 动态定位 sdkman 安装的 Gradle 内嵌 Kotlin 编译器（避免硬编码路径/版本）
GRADLE_LIB=$(for d in /root/.sdkman/candidates/gradle/*/lib; do
    [ -f "$d"/kotlin-compiler-embeddable-*.jar ] && { echo "$d"; break; }
done)
KOTLIN_STDLIB=$(ls -1 "$GRADLE_LIB"/kotlin-stdlib-*.jar 2>/dev/null | head -1)

# 与 App 同源编译：业务代码 + 两个入口（SimMain / PerfMain）。PerfBench 属于业务代码，
# 这样「App 按钮」与「沙箱脚本」跑的是完全相同的实现，数字才可比。
SRC_COMMON=(
    app/src/main/java/com/chudad/game/model/*.kt
    app/src/main/java/com/chudad/game/logic/CardRuleEngine.kt
    app/src/main/java/com/chudad/game/logic/AIDecision.kt
    app/src/main/java/com/chudad/game/logic/MasterSearch.kt
    app/src/main/java/com/chudad/game/logic/PerfBench.kt
    sim/AIDecisionV0.kt
    sim/SimMain.kt
    sim/PerfMain.kt
)

build() {
    # 每次清空输出目录：避免删掉的源文件残留旧 .class，出现「改了没生效」的假象
    rm -rf "$OUT/classes"
    if command -v kotlinc >/dev/null 2>&1; then
        kotlinc "${SRC_COMMON[@]}" -d "$OUT/classes" || { echo "❌ 编译失败"; exit 1; }
        RUN_CP="$OUT/classes"
    else
        [ -n "$GRADLE_LIB" ] || { echo "❌ 找不到 kotlinc 或 kotlin-compiler-embeddable"; exit 1; }
        java -cp "$GRADLE_LIB/*" \
            org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
            -cp "$KOTLIN_STDLIB" \
            "${SRC_COMMON[@]}" \
            -d "$OUT/classes" || { echo "❌ 编译失败"; exit 1; }
        RUN_CP="$OUT/classes:$KOTLIN_STDLIB"
    fi
}

build

MAIN=com.chudad.sim.SimMainKt
if [ "$MODE" = perf ]; then
    MAIN=com.chudad.sim.PerfMainKt
    echo "=== 运行性能基准 ==="
else
    echo "=== 运行仿真 ==="
fi

# shellcheck disable=SC2086
java $JAVA_OPTS -cp "$RUN_CP" "$MAIN" "$@"
