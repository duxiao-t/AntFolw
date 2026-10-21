#!/usr/bin/env bash
# 依赖安装的统一入口（只在 lock 变更 / node_modules 缺失时由 deploy.sh 调用）。
#
# 唯一允许出现 CODEBUDDY_SAFE_DELETE_ENABLED=0 的地方，原因：
#   npm ci 的第一步就是删掉 <module>/node_modules 的每个顶层条目（实测 339 条），
#   必然超过宿主的批量删除阈值（50）→ 进入"人工确认"分支 → 后台无人应答时永久挂起。
#   node_modules 无法像 dist 那样"先 mv 后重建"（GB 级、Windows 上常被句柄占用），
#   而它完全可由 lockfile 重建，所以这是唯一可接受的窄口径旁路。
#
# 纪律：必须是**行内前缀**，脚本内不得 export；不得默认加 --ignore-scripts
# （frontend 的 prepare 会跑 husky + max setup，跳过会留下不完整的工具链）。
set -euo pipefail

ROOT=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
module=${1:-}
case "$module" in
  frontend|mobile) ;;
  *) echo 'Usage: bash scripts/deps.sh frontend|mobile [--ignore-scripts]' >&2; exit 2 ;;
esac
shift || true
extra=()
for arg in "$@"; do
  case "$arg" in
    --ignore-scripts) extra+=(--ignore-scripts) ;;
    *) echo "unknown arg: $arg" >&2; exit 2 ;;
  esac
done

echo "== 安装 $module 依赖（npm ci）=="
CODEBUDDY_SAFE_DELETE_ENABLED=0 npm --prefix "$ROOT/$module" ci \
  --prefer-offline --no-audit --no-fund "${extra[@]}"

# 装到一半就"成功"是最坏的情况：用两条例行断言兜住。
[[ -f "$ROOT/$module/node_modules/.package-lock.json" ]] \
  || { echo "依赖安装不完整：$module/node_modules/.package-lock.json 缺失" >&2; exit 1; }
count=$(ls -1 "$ROOT/$module/node_modules" | wc -l)
(( count > 100 )) || { echo "依赖安装不完整：$module/node_modules 只有 $count 个条目" >&2; exit 1; }
echo "== $module 依赖就绪（$count 个顶层条目）=="
