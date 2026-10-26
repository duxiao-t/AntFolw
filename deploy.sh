#!/usr/bin/env bash
set -euo pipefail

ROOT=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
cd "$ROOT"

ENV_FILE=${ANTFLOW_ENV_FILE:-.env.docker.local}
HEALTH_URL=${ANTFLOW_HEALTH_URL:-http://127.0.0.1:7070/actuator/health}
# 注意这是**软**超时：每轮巡检本身要花几秒（compose ps + docker inspect），
# 实际等待 ≈ 设定值 + 一轮（实测一轮约 1~10s）。别把它当硬上界写进文档。
HEALTH_TIMEOUT_SECONDS=${ANTFLOW_HEALTH_TIMEOUT_SECONDS:-300}
# `compose up` 的硬超时。**必须有**：本机实测过引擎"假活"——容器能创建、但 start 永久卡住，
# 此时 compose up 会无限等待（不报错、不退出），部署脚本跟着挂死，而旧容器已经被删掉了
# （compose recreate 是先删后建），整栈就静默下线了。
COMPOSE_UP_TIMEOUT_SECONDS=${ANTFLOW_COMPOSE_UP_TIMEOUT_SECONDS:-900}
# 探活用的镜像：必须是**本地已有**的，断网时不能指望 pull。
PROBE_IMAGE=${ANTFLOW_PROBE_IMAGE:-nginx:1.28-alpine}

# 部署前记录的服务镜像（服务名 → 镜像 ID / 镜像名），健康检查失败或 up 失败时用来回滚。
# 镜像名也要记：容器若在 up 阶段被删/创建失败，回滚时就没地方读这个名字了（见 rollback_services）。
declare -A PREV_IMAGE=()
declare -A PREV_REF=()

compose() {
  docker compose --env-file "$ENV_FILE" "$@"
}

# 带硬超时的 compose 调用。超时返回 124（GNU timeout 的约定），
# 让调用方能用 `||` 走回滚分支，而不是永久阻塞。
compose_to() {
  local secs=$1
  shift
  timeout --foreground "$secs" docker compose --env-file "$ENV_FILE" "$@"
}

# 部署前的引擎可用性预检。
#
# 为什么需要它：本机反复遇到 Docker 引擎"假活"——`docker ps`/`exec` 都正常，
# 但**新容器的 start 会永久卡住**。此时 `compose up` 既不报错也不推进，
# 表现就是"部署没反应"，最后容器停在 Created、站点 502。
# 用一次性容器把 create→start 走一遍，几秒内就能判定，给出可执行的处置建议。
#
# 返回 0 表示引擎能正常创建并启动容器；非 0 表示不建议继续部署。
preflight_engine() {
  local probe="antflow-preflight-$$" rc=0
  docker rm -f "$probe" >/dev/null 2>&1 || true
  if ! timeout 60 docker create --name "$probe" "$PROBE_IMAGE" true >/dev/null 2>&1; then
    echo "[预检] 失败：无法创建容器。引擎没起来，或本机缺少镜像 $PROBE_IMAGE。" >&2
    return 1
  fi
  timeout 30 docker start "$probe" >/dev/null 2>&1 || rc=$?
  docker rm -f "$probe" >/dev/null 2>&1 || true
  if (( rc == 124 )); then
    cat >&2 <<'EOF'
[预检] 失败：容器能创建，但 start 超过 30 秒没有返回 —— 引擎处于"假活"状态。
       继续部署的话，compose up 会卡在启动容器，旧容器已被删掉，整栈会静默下线。
       处置：docker desktop restart（等 status 变为 running 后重跑本脚本）。
       已实测：重启引擎即可恢复，与镜像、compose 配置无关。
EOF
    return 1
  fi
  if (( rc != 0 )); then
    echo "[预检] 失败：探测容器启动异常（退出码 $rc）。建议 docker desktop restart 后重试。" >&2
    return 1
  fi
  echo "[预检] 引擎正常：容器可创建、可启动"
  return 0
}

reset_plan() {
  build_backend=0
  build_frontend=0
  build_mobile=0
  image_backend=0
  image_web=0
  compose_all=0
  deps_frontend=0
  deps_mobile=0
}

enable_all() {
  build_backend=1
  build_frontend=1
  build_mobile=1
  image_backend=1
  image_web=1
  compose_all=1
}

classify_changes() {
  local path
  while IFS= read -r path; do
    case "$path" in
      frontend/config/page-capabilities.json) build_backend=1; build_frontend=1; image_backend=1; image_web=1 ;;
      backend/*) build_backend=1; image_backend=1 ;;
      frontend/*) build_frontend=1; image_web=1 ;;
      mobile/*) build_mobile=1; image_web=1 ;;
      Dockerfile.local|.dockerignore) image_backend=1; image_web=1 ;;
      compose.yaml) image_backend=1; image_web=1; compose_all=1 ;;
      infra/docker/*) image_web=1 ;;
    esac
    case "$path" in
      frontend/package.json|frontend/package-lock.json) deps_frontend=1 ;;
      mobile/package.json|mobile/package-lock.json) deps_mobile=1 ;;
    esac
  done <<< "$1"
}

force_module() {
  case "$1" in
    backend) build_backend=1; image_backend=1 ;;
    frontend) build_frontend=1; image_web=1 ;;
    mobile) build_mobile=1; image_web=1 ;;
  esac
}

plan_key() {
  printf '%s:%s:%s:%s' \
    "$build_backend$build_frontend$build_mobile" \
    "$image_backend$image_web" \
    "$deps_frontend$deps_mobile" \
    "$compose_all"
}

record_revision() {
  printf '%s\n' "$2" > "$1.tmp"
  mv "$1.tmp" "$1"
}

# 记录本次要重建的服务当前用的是哪个镜像，供回滚使用。首次部署时为空（无可回滚版本）。
capture_previous_images() {
  local svc cid
  for svc in "$@"; do
    cid=$(compose ps -q "$svc" 2>/dev/null || true)
    if [[ -n "$cid" ]]; then
      PREV_IMAGE[$svc]=$(docker inspect -f '{{.Image}}' "$cid" 2>/dev/null || true)
      # 名字一并记下：compose recreate 是"先删旧容器再建新的"，up 失败后容器可能已不存在，
      # 回滚时再想从容器上读 {{.Config.Image}} 就晚了（演练实测到这条）。
      PREV_REF[$svc]=$(docker inspect -f '{{.Config.Image}}' "$cid" 2>/dev/null || true)
    fi
  done
}

# 等所有本次部署的服务就绪：有 healthcheck 的必须 healthy，没有的必须是 running。
# 比原来"只 curl 一次外部健康检查"更早发现问题，也是回滚的触发点。
wait_healthy() {
  local deadline=$((SECONDS + HEALTH_TIMEOUT_SECONDS))
  local svc cid status all_ok
  while (( SECONDS < deadline )); do
    all_ok=1
    for svc in "$@"; do
      cid=$(compose ps -q "$svc" 2>/dev/null || true)
      if [[ -z "$cid" ]]; then all_ok=0; break; fi
      status=$(docker inspect \
        -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' \
        "$cid" 2>/dev/null || echo unknown)
      if [[ "$status" != healthy && "$status" != running ]]; then all_ok=0; break; fi
    done
    (( all_ok )) && return 0
    sleep 3
  done
  return 1
}

# 回滚：把服务镜像指回上一版并强制重建。
#
# 演练（_rollback-drill）暴露过两个坑，这里都兜住：
#   1. 容器可能已经不存在（up 创建失败被删、或停在 created/exited）——`ps -q` 只看 running，
#      所以用 `ps -aq`；容器不在就用部署前记下的 PREV_REF 里的镜像名。
#   2. 只要 PREV_IMAGE 有值就应当尽力回滚，不能因为"读不到当前容器"而放弃（那等于没有回滚）。
rollback_services() {
  local svc old_image new_cid image_ref restored=0
  for svc in "$@"; do
    old_image=${PREV_IMAGE[$svc]:-}
    if [[ -z "$old_image" ]]; then
      echo "  - $svc：没有上一版镜像，跳过" >&2
      continue
    fi
    new_cid=$(compose ps -aq "$svc" 2>/dev/null | head -1 || true)
    image_ref=$([[ -n "$new_cid" ]] && docker inspect -f '{{.Config.Image}}' "$new_cid" 2>/dev/null || true)
    if [[ -z "$image_ref" ]]; then
      image_ref=${PREV_REF[$svc]:-}
    fi
    if [[ -z "$image_ref" ]]; then
      echo "  - $svc：拿不到镜像名（部署前也没记录），跳过" >&2
      continue
    fi
    docker tag "$old_image" "$image_ref"
    restored=1
    echo "  - $svc：$image_ref 已指回上一版镜像" >&2
  done
  (( restored )) || return 1
  # 回滚本身也要有上界：引擎假死时 recreate 会卡在这里，把"挂死"从一个阶段挪到另一个阶段。
  compose_to "$COMPOSE_UP_TIMEOUT_SECONDS" up -d --force-recreate --no-build "$@" >&2
}

# 备份目录的就绪检查（每次部署都做）。
#
# 为什么不能只靠 compose 里的 backup-init：那个服务只在**重建**项目时跑一次。栈仅重启、
# 或宿主目录被删了重建之后，目录属主回落到 root，后端（10001）写不进去，
# 表现为"备份失败：AccessDeniedException /backups/xxx.afbackup"（实测踩过）。
# 这里每次部署都用 root 容器把属主与权限兜一遍，并把可用空间不足提前喊出来。
ensure_backup_directory() {
  local dir available
  dir=$(grep -E '^BACKUP_DIRECTORY=' "$ENV_FILE" 2>/dev/null | tail -1 | cut -d= -f2-)
  dir=${dir:-/www/antflow/backups}
  mkdir -p "$dir" 2>/dev/null || true
  available=$(docker run --rm -v "$dir:/b" alpine sh -c \
    'chown 10001:10001 /b 2>/dev/null; chmod 700 /b 2>/dev/null; df -Pk /b 2>/dev/null | tail -1' \
    2>/dev/null | awk '{print $4}')
  if [[ -n ${available:-} && ${available:-0} -lt 2097152 ]]; then
    # 2GiB 以下就告警：Docker Desktop 上把 BACKUP_DIRECTORY 留在默认的 /www 下，
    # 会落到 VM 的小根盘（约 200MB），备份必然半途 ENOSPC。
    echo "警告：备份目录 $dir 可用空间仅 $((available / 1024))MB（建议≥2GB）。" \
      "Docker Desktop 上请把 BACKUP_DIRECTORY 指到宿主机盘，例如 D:/antflow-backups" >&2
  fi
}

self_test() {
  local actual expected
  while IFS='|' read -r changes expected; do
    reset_plan
    classify_changes "${changes//\\n/$'\n'}"
    actual=$(plan_key)
    [[ "$actual" == "$expected" ]] || {
      echo "self-test failed: $changes => $actual, expected $expected" >&2
      return 1
    }
  done <<'CASES'
backend/src/App.java|100:10:00:0
frontend/src/App.tsx\nmobile/package-lock.json|011:01:01:0
frontend/config/page-capabilities.json|110:11:00:0
Dockerfile.local|000:11:00:0
infra/docker/nginx.conf|000:01:00:0
compose.yaml|000:11:00:1
README.md|000:00:00:0
CASES

  reset_plan
  force_module frontend
  [[ "$(plan_key)" == '010:01:00:0' ]] || return 1
  reset_plan
  enable_all
  [[ "$(plan_key)" == '111:11:00:1' ]] || return 1
  local script
  for script in scripts/build.sh scripts/deps.sh scripts/prune-build-stale.sh; do
    [[ -f "$ROOT/$script" ]] || { echo "self-test failed: missing $script" >&2; return 1; }
    bash -n "$ROOT/$script" || { echo "self-test failed: syntax error in $script" >&2; return 1; }
  done
  echo 'deploy.sh self-test passed'
}

usage() {
  echo 'Usage: bash deploy.sh [--all] [backend] [frontend] [mobile]'
}

main() {
  if [[ "${1:-}" == '--self-test' ]]; then
    [[ $# -eq 1 ]] || { usage >&2; return 2; }
    self_test
    return
  fi

  local force_all=0 arg previous='' current changes state_file
  local -a forced=() services=() labels=()
  for arg in "$@"; do
    case "$arg" in
      --all) force_all=1 ;;
      backend|frontend|mobile) forced+=("$arg") ;;
      -h|--help) usage; return ;;
      *) usage >&2; return 2 ;;
    esac
  done

  if ! git diff --quiet || ! git diff --cached --quiet; then
    echo 'Tracked files have local changes; commit or stash them before deploying.' >&2
    return 1
  fi

  state_file=$(git rev-parse --git-path antflow-deployed-revision)
  if [[ -s "$state_file" ]]; then
    previous=$(< "$state_file")
  fi
  git pull --ff-only
  current=$(git rev-parse HEAD)

  # 先确认引擎能正常起容器，再花几分钟构建：引擎假死时构建出来的东西也用不上，
  # 更糟的是会让人以为"部署正在跑"，直到很久之后才发现根本没动。
  preflight_engine || return 1

  reset_plan
  if [[ -z "$previous" ]] || ! git cat-file -e "$previous^{commit}" 2>/dev/null || ! git merge-base --is-ancestor "$previous" "$current"; then
    enable_all
    deps_frontend=1
    deps_mobile=1
  elif (( force_all )); then
    enable_all
  else
    changes=$(git diff --name-only "$previous" "$current")
    classify_changes "$changes"
  fi
  for arg in "${forced[@]}"; do force_module "$arg"; done

  [[ -f backend/target/antflow-backend-0.1.0-SNAPSHOT.jar ]] || { build_backend=1; image_backend=1; }
  [[ -f frontend/dist/index.html ]] || { build_frontend=1; image_web=1; }
  [[ -f mobile/dist/index.html ]] || { build_mobile=1; image_web=1; }

  (( build_backend )) && labels+=(backend)
  (( build_frontend )) && labels+=(frontend)
  (( build_mobile )) && labels+=(mobile)
  (( image_backend && ! build_backend )) && labels+=(backend-image)
  (( image_web && ! build_frontend && ! build_mobile )) && labels+=(web-image)

  if (( ${#labels[@]} == 0 )); then
    record_revision "$state_file" "$current"
    echo 'No deployable changes.'
    return
  fi
  echo "Deploying: ${labels[*]}"

  if (( build_frontend || build_mobile )); then
    command -v node >/dev/null && [[ $(node -p "Number(process.versions.node.split('.')[0])") -ge 22 ]] || {
      echo 'Node.js 22 or newer is required.' >&2
      return 1
    }
  fi
  # 构建/装依赖都走 scripts/ 下的统一入口：build.sh 先把生成目录 mv 到 .build-stale
  # （同盘 rename，不触发宿主的删除守卫），deps.sh 是唯一带行内旁路的脚本（npm ci 必须清 node_modules）。
  if (( build_frontend )); then
    if (( deps_frontend )) || [[ ! -d frontend/node_modules ]]; then
      bash "$ROOT/scripts/deps.sh" frontend
    fi
    bash "$ROOT/scripts/build.sh" frontend --no-budget
  fi
  if (( build_mobile )); then
    if (( deps_mobile )) || [[ ! -d mobile/node_modules ]]; then
      bash "$ROOT/scripts/deps.sh" mobile
    fi
    bash "$ROOT/scripts/build.sh" mobile --no-budget
  fi
  if (( build_backend )); then
    bash backend/build.sh -Dmaven.test.skip=true package
  fi

  local -a deploy_services=()
  if (( compose_all )); then
    deploy_services=(backend web)
  else
    (( image_backend )) && services+=(backend)
    (( image_web )) && services+=(web)
    deploy_services=("${services[@]}")
  fi

  capture_previous_images "${deploy_services[@]}"
  ensure_backup_directory
  # up 本身也必须纳入判定：compose recreate 是"先删旧容器再建新的"，一旦创建失败
  # （坏镜像、坏配置、设备映射等），set -e 会让脚本直接退出，而旧容器已经没了 → 整栈下线。
  # 演练（_rollback-drill/drill3）实测过这条路径。
  if ! compose_to "$COMPOSE_UP_TIMEOUT_SECONDS" up -d --build "${services[@]}" \
      || ! wait_healthy "${deploy_services[@]}" \
      || ! curl --fail --silent --show-error --retry 5 --retry-delay 2 --retry-connrefused \
           "$HEALTH_URL"; then
    echo '健康检查未通过，开始回滚到上一版镜像…' >&2
    compose logs --tail 50 "${deploy_services[@]}" >&2 || true
    # 回滚同样要 recreate + start，引擎假死时它一样会挂住。先探一次：
    # 引擎不可用就直说"先重启引擎"，别让人再等一轮注定失败的长时间等待。
    if ! preflight_engine; then
      echo '引擎当前不可用，跳过自动回滚。' >&2
      echo '旧镜像仍在本机：执行 docker desktop restart，等引擎恢复后重跑本脚本即可。' >&2
      return 1
    fi
    if rollback_services "${deploy_services[@]}" && wait_healthy "${deploy_services[@]}"; then
      echo '已回滚到上一版镜像（revision 未更新，修复后可重跑）' >&2
    else
      echo '回滚失败或回滚后仍不健康，需要人工介入' >&2
    fi
    return 1
  fi
  echo
  record_revision "$state_file" "$current"
  echo "Deployed $current"
}

main "$@"
