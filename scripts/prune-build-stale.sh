#!/usr/bin/env bash
# 回收 .build-stale/ —— 手动、显式、不在流水线上的动作。
#
# 默认 dry-run 只列目录与体积；真删必须 --force，并且只允许删 $ROOT/.build-stale 下的直接子目录。
#
# 这里同样需要行内旁路（CODEBUDDY_SAFE_DELETE_ENABLED=0）：被删的是成百上千个构建产物，
# 走回收站要么逐文件撞 5s 上限、要么撞批量删除阈值。与 deps.sh 一样，旁路只针对"可再生的
# 构建产物"，且必须由人显式加 --force 触发；构建/部署路径里不会调用本脚本。
set -euo pipefail

ROOT=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
STALE_ROOT="$ROOT/.build-stale"
KEEP=3
FORCE=0

while (($#)); do
  case "$1" in
    --dry-run) FORCE=0 ;;
    --force) FORCE=1 ;;
    --keep) shift; KEEP=${1:-3} ;;
    -h|--help) echo 'Usage: bash scripts/prune-build-stale.sh [--dry-run|--force] [--keep N]'; exit 0 ;;
    *) echo "unknown arg: $1" >&2; exit 2 ;;
  esac
  shift
done
[[ "$KEEP" =~ ^[0-9]+$ ]] || { echo "--keep 需要非负整数" >&2; exit 2; }

[[ -d "$STALE_ROOT" ]] || { echo '没有 .build-stale，无需清理'; exit 0; }

# 按 mtime 从新到旧排序；前 KEEP 个保留，其余待删。
mapfile -t all < <(find "$STALE_ROOT" -mindepth 1 -maxdepth 1 -type d -printf '%T@ %p\n' 2>/dev/null \
  | sort -rn | cut -d' ' -f2-)
total=${#all[@]}
if (( total <= KEEP )); then
  echo "共 $total 份，全部保留（--keep $KEEP）"
  exit 0
fi

doomed=("${all[@]:KEEP}")
echo "共 $total 份，保留最新 $KEEP 份，待清理 ${#doomed[@]} 份："
for dir in "${doomed[@]}"; do
  printf '  %s  %s\n' "$(du -sh "$dir" 2>/dev/null | cut -f1)" "${dir#"$ROOT"/}"
done

if (( ! FORCE )); then
  echo '（dry-run，未删除；确认后执行 --force）'
  exit 0
fi

for dir in "${doomed[@]}"; do
  # 双保险：目标必须严格位于 .build-stale 之下
  case "$(realpath "$dir")" in
    "$STALE_ROOT"/*) ;;
    *) echo "拒绝删除越界路径：$dir" >&2; exit 1 ;;
  esac
  CODEBUDDY_SAFE_DELETE_ENABLED=0 rm -rf -- "$dir"
  echo "  已删除 ${dir#"$ROOT"/}"
done
echo '清理完成'
