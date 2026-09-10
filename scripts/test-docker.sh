#!/usr/bin/env bash
set -euo pipefail

ROOT=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
PROJECT=antflow-test
SOURCE_PROJECT=antflow-local
COMPOSE_FILE="$ROOT/compose.test.yaml"
TEST_ENV="$ROOT/.env.test"
SOURCE_ENV="$ROOT/.env"
SOURCE_POSTGRES="${SOURCE_PROJECT}-postgres-1"
SOURCE_MINIO="${SOURCE_PROJECT}-minio-1"

compose() {
  docker compose --project-name "$PROJECT" --env-file "$TEST_ENV" -f "$COMPOSE_FILE" "$@"
}

random_secret() {
  openssl rand -hex "$1"
}

source_credentials() {
  if [[ ! -f "$SOURCE_ENV" && -f "$ROOT/.env.docker.local" ]]; then
    SOURCE_ENV="$ROOT/.env.docker.local"
  fi
  [[ -f "$SOURCE_ENV" ]] || { echo "Missing source Docker env file." >&2; return 1; }
  SOURCE_MINIO_USER=$(sed -n 's/^MINIO_ROOT_USER=//p' "$SOURCE_ENV" | tail -n 1 | tr -d '\r')
  SOURCE_MINIO_PASSWORD=$(sed -n 's/^MINIO_ROOT_PASSWORD=//p' "$SOURCE_ENV" | tail -n 1 | tr -d '\r')
  [[ -n "$SOURCE_MINIO_USER" && -n "$SOURCE_MINIO_PASSWORD" ]] || {
    echo 'MINIO_ROOT_USER and MINIO_ROOT_PASSWORD are required in source env.' >&2
    return 1
  }
}

ensure_test_env() {
  [[ -f "$TEST_ENV" ]] && return
  umask 077
  local tmp="$TEST_ENV.tmp"
  printf '%s\n' \
    "POSTGRES_PASSWORD=$(random_secret 24)" \
    "MINIO_ROOT_USER=antflow-test" \
    "MINIO_ROOT_PASSWORD=$(random_secret 24)" \
    "JWT_SECRET=$(random_secret 32)" \
    "AUDIT_ARCHIVE_ENCRYPTION_SECRET=$(random_secret 32)" \
    "ANTFLOW_INTEGRATION_ENCRYPTION_KEY=$(random_secret 32)" \
    "BACKUP_ENCRYPTION_SECRET=$(random_secret 32)" > "$tmp"
  chmod 600 "$tmp"
  mv "$tmp" "$TEST_ENV"
}

assert_source_running() {
  for container in "$SOURCE_POSTGRES" "$SOURCE_MINIO"; do
    [[ "$(docker inspect --format '{{.State.Running}}' "$container" 2>/dev/null || true)" == true ]] || {
      echo "Source container is not running: $container" >&2
      return 1
    }
  done
}

wait_healthy() {
  local service=$1 id state
  id=$(compose ps -q "$service")
  [[ -n "$id" ]] || { echo "No test $service container." >&2; return 1; }
  for _ in $(seq 1 60); do
    state=$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' "$id")
    [[ "$state" == healthy ]] && return
    sleep 1
  done
  echo "Test $service did not become healthy." >&2
  compose logs --tail=100 "$service" >&2 || true
  return 1
}

build_artifacts() {
  echo 'Building isolated test artifacts with Java 17 and Node 22 containers...'
  docker run --rm \
    -v "$ROOT/backend:/workspace" -v "$ROOT/.m2:/root/.m2" \
    -w /workspace maven:3.9-eclipse-temurin-17 \
    mvn -B -Dmaven.test.skip=true package
  if [[ ! -x "$ROOT/frontend/node_modules/.bin/max" ]]; then
    docker run --rm -v "$ROOT/frontend:/workspace" -w /workspace node:22-bookworm \
      npm ci --no-audit --no-fund
  fi
  docker run --rm \
    -e ANTFLOW_OUTPUT_PATH=dist-test \
    -e ANTFLOW_AUTH_CSRF_COOKIE_NAME=antflow-test-csrf \
    -v "$ROOT/frontend:/workspace" -w /workspace node:22-bookworm \
    npm run build
  if [[ ! -x "$ROOT/mobile/node_modules/.bin/vite" ]]; then
    docker run --rm -v "$ROOT/mobile:/workspace" -w /workspace node:22-bookworm \
      npm ci --no-audit --no-fund
  fi
  docker run --rm \
    -e ANTFLOW_OUTPUT_PATH=dist-test \
    -e VITE_AUTH_CSRF_COOKIE_NAME=antflow-test-csrf \
    -v "$ROOT/mobile:/workspace" -w /workspace node:22-bookworm \
    npm run build
  if ! grep -Rqs 'antflow-test-csrf' "$ROOT/frontend/dist-test" \
      || ! grep -Rqs 'antflow-test-csrf' "$ROOT/mobile/dist-test"; then
    echo 'Test frontend artifacts use the wrong CSRF cookie name.' >&2
    return 1
  fi
}

clone_database() {
  local target
  target=$(compose ps -q postgres)
  echo 'Cloning PostgreSQL with a consistent read-only dump...'
  docker exec "$SOURCE_POSTGRES" pg_dump -U antflow -d antflow --format=custom --no-owner --no-privileges \
    | docker exec -i "$target" pg_restore -U antflow -d antflow --clean --if-exists --no-owner --no-privileges --exit-on-error
}

sanitize_clone() {
  local target
  target=$(compose ps -q postgres)
  docker exec -i "$target" psql -v ON_ERROR_STOP=1 -U antflow -d antflow <<'SQL'
UPDATE t_wecom_config
SET oauth_enabled = false, js_sdk_enabled = false, message_enabled = false,
    schedule_enabled = false, schedule_last_run_date = NULL;
UPDATE t_oidc_provider SET enabled = false;
UPDATE t_system_backup_setting SET enabled = false WHERE id = 1;
UPDATE t_workflow_job
SET status = 'CANCELLED', completed_at = COALESCE(completed_at, now()),
    last_error = 'Cancelled in isolated test clone'
WHERE status IN ('SCHEDULED', 'RUNNING', 'FAILED');
UPDATE t_workflow_outbox
SET status = 'DEAD', delivered_at = COALESCE(delivered_at, now()),
    last_error = 'Suppressed in isolated test clone'
WHERE status IN ('PENDING', 'RUNNING');
UPDATE t_wecom_message_delivery
SET status = 'DEAD', delivered_at = COALESCE(delivered_at, now()),
    last_error = 'Suppressed in isolated test clone'
WHERE status IN ('PENDING', 'RUNNING');
UPDATE t_wecom_sync_job
SET status = 'FAILED', phase = 'COMPLETED', finished_at = COALESCE(finished_at, now()),
    message = 'Suppressed in isolated test clone'
WHERE status IN ('PENDING', 'RUNNING');
DELETE FROM t_external_auth_flow;
SQL
}

clone_minio_safe() {
  local target source_ip target_ip helper test_user test_password
  target=$(compose ps -q minio)
  source_ip=$(docker inspect --format "{{with index .NetworkSettings.Networks \"${SOURCE_PROJECT}_default\"}}{{.IPAddress}}{{end}}" "$SOURCE_MINIO")
  target_ip=$(docker inspect --format "{{with index .NetworkSettings.Networks \"${PROJECT}_default\"}}{{.IPAddress}}{{end}}" "$target")
  test_user=$(sed -n 's/^MINIO_ROOT_USER=//p' "$TEST_ENV")
  test_password=$(sed -n 's/^MINIO_ROOT_PASSWORD=//p' "$TEST_ENV")
  [[ -n "$source_ip" && -n "$target_ip" && -n "$test_user" && -n "$test_password" ]] || {
    echo 'Unable to resolve MinIO clone endpoints.' >&2
    return 1
  }
  helper="${PROJECT}-minio-copy-$$"
  echo 'Cloning MinIO objects into the isolated test volume...'
  docker create --name "$helper" --network "${SOURCE_PROJECT}_default" minio/mc sleep 600 >/dev/null
  if ! docker network connect "${PROJECT}_default" "$helper" \
    || ! docker start "$helper" >/dev/null \
    || ! docker exec \
      -e "SOURCE_MINIO_USER=$SOURCE_MINIO_USER" \
      -e "SOURCE_MINIO_PASSWORD=$SOURCE_MINIO_PASSWORD" \
      -e "TEST_MINIO_USER=$test_user" \
      -e "TEST_MINIO_PASSWORD=$test_password" \
      -e "SOURCE_MINIO_URL=http://${source_ip}:9000" \
      -e "TEST_MINIO_URL=http://${target_ip}:9000" \
      "$helper" sh -ec '
        mc alias set source "$SOURCE_MINIO_URL" "$SOURCE_MINIO_USER" "$SOURCE_MINIO_PASSWORD" >/dev/null
        mc alias set target "$TEST_MINIO_URL" "$TEST_MINIO_USER" "$TEST_MINIO_PASSWORD" >/dev/null
        mc ls source > /tmp/source-buckets
        while IFS= read -r row; do
          bucket=${row##* }
          bucket=${bucket%/}
          [ -n "$bucket" ] || continue
          mc mb --ignore-existing "target/$bucket" >/dev/null
          mc mirror --quiet --overwrite "source/$bucket" "target/$bucket"
        done < /tmp/source-buckets
      '; then
    docker rm -f "$helper" >/dev/null 2>&1 || true
    return 1
  fi
  docker rm -f "$helper" >/dev/null
}

start() {
  source_credentials
  ensure_test_env
  assert_source_running
  local pg_exists minio_exists
  pg_exists=$(docker volume inspect "${PROJECT}_postgres_data" >/dev/null 2>&1 && echo yes || true)
  minio_exists=$(docker volume inspect "${PROJECT}_minio_data" >/dev/null 2>&1 && echo yes || true)
  if [[ -z "$pg_exists" && -z "$minio_exists" ]]; then
    compose up -d postgres minio
    wait_healthy postgres
    wait_healthy minio
    clone_database
    clone_minio_safe
    sanitize_clone
  elif [[ -z "$pg_exists" || -z "$minio_exists" ]]; then
    echo 'Incomplete test volumes found; run scripts/test-docker.sh refresh.' >&2
    return 1
  fi
  build_artifacts
  compose up -d --build
  for _ in $(seq 1 90); do
    if curl --fail --silent --show-error http://10.0.0.250:17070/actuator/health >/dev/null; then
      echo 'Isolated test environment: http://10.0.0.250:17070'
      return
    fi
    sleep 2
  done
  compose logs --tail=160 backend web >&2 || true
  return 1
}

destroy() {
  [[ -f "$TEST_ENV" ]] || { echo 'No isolated test environment exists.'; return; }
  compose down --volumes --remove-orphans
  rm -f "$TEST_ENV"
  echo 'Removed antflow-test containers, network, volumes, and local test secrets.'
}

sync_minio() {
  source_credentials
  [[ -f "$TEST_ENV" ]] || { echo 'Start the isolated test environment first.' >&2; return 1; }
  assert_source_running
  [[ -n "$(compose ps -q minio)" ]] || { echo 'Test MinIO is not running.' >&2; return 1; }
  clone_minio_safe
  echo 'Test MinIO objects synchronized from antflow-local.'
}

case "${1:-up}" in
  up) start ;;
  refresh) destroy; start ;;
  status) [[ -f "$TEST_ENV" ]] && compose ps || true ;;
  sync-minio) sync_minio ;;
  destroy) destroy ;;
  *) echo 'Usage: scripts/test-docker.sh [up|refresh|status|sync-minio|destroy]' >&2; exit 2 ;;
esac
