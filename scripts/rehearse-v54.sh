#!/usr/bin/env bash
# V54（公司归属快照）上线预演的入口。真正的逻辑在 scripts/rehearse/V54Rehearsal.java。
#
#   bash scripts/rehearse-v54.sh --jdbc jdbc:postgresql://<host>/<副本库> \
#     --user <u> --password <p> --confirm [--old-image-check] [--force]
#
# 它在一个**副本库**上走真实 Flyway 路径跑一遍 V54 并计时（见 docs/p4a-rollout-runbook.md）。
# 目标库的三道闸由 Java 那边执行：库名不能是 antflow、必须带 rehearsal_marker 表、必须停在 V53。
#
# 为什么绕一层：工具要用项目自己那份 Flyway（10.10.0，来自 spring-boot BOM），
# 所以先从 backend 的依赖里取 classpath。显式 `javac -encoding UTF-8` 而不是单文件源码模式——
# 后者按平台默认字符集读源码，Windows 上会把中文读成乱码、甚至让字符串字面量解析失败。
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
root="$(cd "$here/.." && pwd)"

if [[ ! -f "$root/backend/pom.xml" ]]; then
  echo "找不到 $root/backend/pom.xml —— 请在仓库内运行本脚本。" >&2
  exit 2
fi
if ! command -v mvn >/dev/null && [[ ! -x "$root/backend/mvnw" ]]; then
  echo "需要 maven（或用 backend/mvnw）。" >&2
  exit 2
fi

# 类路径分隔符按平台。本工具主要在操作人的宿主机（Windows/git-bash）上跑，但别写死。
case "$(uname -s)" in
  MINGW* | MSYS* | CYGWIN*) path_sep=';' ;;
  *) path_sep=':' ;;
esac

# 产物落到 target/ 下（已被 gitignore）。
work_dir="$root/backend/target/rehearse"
classes_dir="$work_dir/classes"
cp_file="$work_dir/classpath.txt"
mkdir -p "$classes_dir"

# javac/java 是 Windows JVM（走 mvn 那套），不认 MSYS 的 /e/code/... 路径，必须转成 C:\...。
win() { if command -v cygpath >/dev/null 2>&1; then cygpath -w "$1"; else printf '%s' "$1"; fi; }
classes_win="$(win "$classes_dir")"

echo "[rehearse] 解析 backend 依赖 classpath ..."
mvn -B -q -f "$root/backend/pom.xml" dependency:build-classpath -Dmdep.outputFile="$cp_file" >/dev/null
if [[ ! -s "$cp_file" ]]; then
  echo "classpath 解析失败（$cp_file 为空）。" >&2
  exit 2
fi

echo "[rehearse] 编译工具（-encoding UTF-8）..."
javac -encoding UTF-8 -d "$classes_win" -cp "$(cat "$cp_file")" \
  "$(win "$root/scripts/rehearse/V54Rehearsal.java")"

# 迁移目录由包装脚本按仓库位置给出（不依赖调用者的 CWD）；用户显式传的会覆盖它（解析取后者）。
migrations_win="$(win "$root/backend/src/main/resources/db/migration")"

exec java -cp "$classes_win$path_sep$(cat "$cp_file")" V54Rehearsal \
  --migrations "filesystem:$migrations_win" "$@"
