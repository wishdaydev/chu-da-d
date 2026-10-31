#!/bin/bash
# 大师模式仿真 harness 编译+运行脚本（纯 JVM，不需要 Android SDK / Gradle）
# 用法: ./sim/run-sim.sh [games] [deadlineMs] [nodeLimit] [threads] [baseSeed]
# 示例: ./sim/run-sim.sh 240 50 150000 3 1
set -uo pipefail
cd "$(dirname "$0")/.."

OUT=/tmp/chudad-sim
mkdir -p "$OUT"

# 动态定位 sdkman 安装的 Gradle 内嵌 Kotlin 编译器（避免硬编码路径/版本）
GRADLE_LIB=$(for d in /root/.sdkman/candidates/gradle/*/lib; do
    [ -f "$d"/kotlin-compiler-embeddable-*.jar ] && { echo "$d"; break; }
done)
KOTLIN_STDLIB=$(ls -1 "$GRADLE_LIB"/kotlin-stdlib-*.jar 2>/dev/null | head -1)

build_and_run() {
    if command -v kotlinc >/dev/null 2>&1; then
        kotlinc \
            app/src/main/java/com/chudad/game/model/*.kt \
            app/src/main/java/com/chudad/game/logic/CardRuleEngine.kt \
            app/src/main/java/com/chudad/game/logic/MasterSearch.kt \
            sim/SimMain.kt \
            -d "$OUT/classes" || { echo "❌ 编译失败"; exit 1; }
        RUN_CP="$OUT/classes"
    else
        [ -n "$GRADLE_LIB" ] || { echo "❌ 找不到 kotlinc 或 kotlin-compiler-embeddable"; exit 1; }
        java -cp "$GRADLE_LIB/*" \
            org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
            -cp "$KOTLIN_STDLIB" \
            app/src/main/java/com/chudad/game/model/*.kt \
            app/src/main/java/com/chudad/game/logic/CardRuleEngine.kt \
            app/src/main/java/com/chudad/game/logic/MasterSearch.kt \
            sim/SimMain.kt \
            -d "$OUT/classes" || { echo "❌ 编译失败"; exit 1; }
        RUN_CP="$OUT/classes:$KOTLIN_STDLIB"
    fi

    echo "=== 运行仿真 ==="
    java -cp "$RUN_CP" com.chudad.sim.SimMainKt "$@"
}

build_and_run "$@"
