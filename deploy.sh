#!/usr/bin/env bash
set -euo pipefail

ROOT=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
cd "$ROOT"

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
  if (( build_frontend )); then
    if (( deps_frontend )) || [[ ! -d frontend/node_modules ]]; then
      npm --prefix frontend ci --prefer-offline --no-audit --no-fund
    fi
    npm --prefix frontend run build
  fi
  if (( build_mobile )); then
    if (( deps_mobile )) || [[ ! -d mobile/node_modules ]]; then
      npm --prefix mobile ci --prefer-offline --no-audit --no-fund
    fi
    npm --prefix mobile run build
  fi
  if (( build_backend )); then
    bash backend/build.sh -Dmaven.test.skip=true package
  fi

  if (( compose_all )); then
    docker compose --env-file .env.docker.local up -d --build
  else
    (( image_backend )) && services+=(backend)
    (( image_web )) && services+=(web)
    docker compose --env-file .env.docker.local up -d --build "${services[@]}"
  fi

  curl --fail --silent --show-error --retry 30 --retry-delay 2 --retry-connrefused \
    http://127.0.0.1:7070/actuator/health
  echo
  record_revision "$state_file" "$current"
  echo "Deployed $current"
}

main "$@"
