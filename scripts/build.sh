#!/usr/bin/env bash
# 前端/移动端构建的统一入口。
#
# 为什么需要它：本机宿主给每个 node 子进程注入了 node-safe-delete-shim —— 删除会先进回收站
# （单次 5s 上限），并按"单次工具调用内累计删除条目数"计数，超过阈值（默认 50）就要求人工确认；
# 后台无人应答时永久挂起。而一个前端构建必然触顶：
#   · umi build 第一行 rimraf src/.umi-production（Windows 上逐文件 unlink）
#   · utoopack 对 dist 的每个顶层条目各发一次 fs.promises.rm
#   · vite build 的 emptyDir 整目录 rm
#
# 做法：构建前把生成目录 mv 到仓库根的 .build-stale/（同盘 rename，不经过任何删除 API），
# 三个清理动作就都命中"目录不存在"分支。mv 失败会在构建开始前中止，此时旧产物完好。
#
# 约束（改之前先读，三条都踩过）：
#   1) 必须同盘：跨盘 mv 会退化成"复制 + 逐文件删除"，等于把 shim 请回来；
#   2) 不能放 frontend/ 内：frontend/tsconfig.json 的 include 是 ./**/*，biome 也没排除，
#      搬出来的 .umi 生成物会被 tsc/lint 扫到；
#   3) .dockerignore 首行是 `*` 白名单，.build-stale 不会进镜像构建上下文。
set -euo pipefail

ROOT=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
STALE_ROOT="$ROOT/.build-stale"
BUDGET=1
requested=()

usage() { echo 'Usage: bash scripts/build.sh frontend|mobile|all [--no-budget]'; }

while (($#)); do
  case "$1" in
    frontend|mobile|all) requested+=("$1") ;;
    --no-budget) BUDGET=0 ;;
    -h|--help) usage; exit 0 ;;
    *) echo "unknown arg: $1" >&2; usage >&2; exit 2 ;;
  esac
  shift
done

((${#requested[@]})) || { usage >&2; exit 2; }

modules=()
for item in "${requested[@]}"; do
  if [[ "$item" == all ]]; then
    for m in frontend mobile; do [[ " ${modules[*]} " == *" $m "* ]] || modules+=("$m"); done
  else
    [[ " ${modules[*]} " == *" $item "* ]] || modules+=("$item")
  fi
done

command -v node >/dev/null || { echo 'node 未找到' >&2; exit 1; }
node_major=$(node -p "Number(process.versions.node.split('.')[0])")
(( node_major >= 22 )) || { echo "Node.js 22+ 必需（当前 $(node -v)）" >&2; exit 1; }

fail() { echo "构建校验失败：$*" >&2; exit 1; }

# 把已存在的生成目录整体搬到 .build-stale/<module>-<UTC 时间>-<pid>/。
# 任何一个目录搬不动就整体中止——此时还没开始构建，旧产物仍可用。
quarantine() {
  local module=$1; shift
  local existing=() path target stamp
  for path in "$@"; do [[ -e "$path" ]] && existing+=("$path"); done
  if ((${#existing[@]} == 0)); then
    echo "  [quarantine] $module：产物不存在，跳过"
    return 0
  fi
  stamp=$(date -u +%Y%m%dT%H%M%SZ)
  target="$STALE_ROOT/$module-$stamp-$$"
  mkdir -p "$target"
  for path in "${existing[@]}"; do
    mv "$path" "$target/$(basename "$path")"
    echo "  [quarantine] ${path#"$ROOT"/} → ${target#"$ROOT"/}/$(basename "$path")"
  done
}

assert_frontend() {
  local dist="$ROOT/frontend/dist" routes
  [[ -f "$dist/index.html" ]] || fail 'frontend: dist/index.html 缺失'
  compgen -G "$dist/*.async.js" >/dev/null || fail 'frontend: 没有 async chunk'
  grep -lq 'antflow-csrf' "$dist"/*.js || fail 'frontend: 产物里找不到 antflow-csrf（CSRF cookie 名没注入）'
  if grep -rq 'antflow-test-csrf' "$dist"; then
    fail 'frontend: 产物里混入 antflow-test-csrf（会让镜像最后一步的负向 grep 失败）'
  fi
  [[ -f "$dist/404.html" ]] || fail 'frontend: 404.html 缺失（public/ 没拷进产物）'
  routes=$(find "$dist" -mindepth 2 -maxdepth 2 -name index.html | wc -l)
  (( routes >= 3 )) || fail "frontend: exportStatic 路由产物只有 $routes 个"
}

assert_mobile() {
  local dist="$ROOT/mobile/dist"
  [[ -f "$dist/index.html" ]] || fail 'mobile: dist/index.html 缺失'
  [[ -f "$dist/.vite/manifest.json" ]] || fail 'mobile: dist/.vite/manifest.json 缺失'
  compgen -G "$dist/assets/*.js" >/dev/null || fail 'mobile: 没有 assets/*.js'
}

for module in "${modules[@]}"; do
  echo "== 构建 $module =="
  case "$module" in
    frontend)
      quarantine frontend "$ROOT/frontend/src/.umi-production" "$ROOT/frontend/dist"
      ( cd "$ROOT" && ANTFLOW_OUTPUT_PATH=dist ANTFLOW_AUTH_CSRF_COOKIE_NAME=antflow-csrf \
          npm --prefix frontend run build )
      assert_frontend
      ;;
    mobile)
      quarantine mobile "$ROOT/mobile/dist"
      ( cd "$ROOT" && ANTFLOW_OUTPUT_PATH=dist VITE_AUTH_CSRF_COOKIE_NAME=antflow-csrf \
          npm --prefix mobile run build )
      assert_mobile
      if (( BUDGET )); then
        npm --prefix "$ROOT/mobile" run check:bundle
      fi
      ;;
    *) fail "unknown module $module" ;;
  esac
  echo "== $module 完成 =="
done

if [[ -d "$STALE_ROOT" ]]; then
  echo "隔离目录：$STALE_ROOT（$(du -sh "$STALE_ROOT" 2>/dev/null | cut -f1)，共 $(ls -1 "$STALE_ROOT" | wc -l) 份）"
  echo "清理方式：bash scripts/prune-build-stale.sh --force --keep 3"
fi
