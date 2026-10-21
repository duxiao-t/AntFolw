#!/usr/bin/env bash
# 停机恢复：解密 → 校验 → 定位部署 → 恢复前快照 → 停机 → 事务性恢复 → 重建对象 → 起来 → 体检。
#
# 用法：
#   BACKUP_ENCRYPTION_SECRET=... backend/backup/restore-backup.sh BACKUP.afbackup --confirm
# 可选环境变量：
#   ANTFLOW_ENV_FILE      指定 compose 的 env 文件（默认自动探测 .env.docker.local → .env）
#   SKIP_SAFETY_DUMP=1    跳过"恢复前给当前库打快照"（默认会打；恢复失败时它是唯一退路）
#   PRE_RESTORE_DIR       快照落盘目录（默认与备份文件同目录）
#   HEALTH_URL            恢复后体检地址（默认 http://127.0.0.1:7070/actuator/health）
set -euo pipefail

if [[ ${2:-} != --confirm || ! -f ${1:-} ]]; then
  echo "usage: BACKUP_ENCRYPTION_SECRET=... $0 BACKUP.afbackup --confirm" >&2
  echo "  可选：ANTFLOW_ENV_FILE=... / SKIP_SAFETY_DUMP=1 / PRE_RESTORE_DIR=... / HEALTH_URL=..." >&2
  exit 2
fi
: "${BACKUP_ENCRYPTION_SECRET:?set BACKUP_ENCRYPTION_SECRET}"

backup_file=$(realpath "$1")
work_dir=$(mktemp -d /tmp/antflow-restore.XXXXXX)
trap 'rm -rf -- "$work_dir"' EXIT
tool_dir=$(realpath "$(dirname "$0")")

# ① 解密 + 解包 + 逐文件 sha256 校验（都在断网容器里做；校验通过才动线上）
#
# 堆必须按归档体积给：AFBK1 是**单条 AES-GCM 流**，而 JDK 的解密只在 doFinal() 才吐明文
# （update() 恒返回 null，已用 1MB 密文实测），所以峰值内存 ≈ 归档大小 × 2.6。
# 用默认堆去解几个 GB 的归档，表现是"跑几十分钟、目标文件一直 0 字节"，很容易被误判成卡死
# （排查时踩过）。这里按体积算堆并显式设上限，Docker 内存不够时会立刻报错而不是假死。
archive_mb=$(( $(stat -c%s "$backup_file") / 1048576 ))
heap_mb=$(( archive_mb * 3 + 512 ))
echo "备份归档 ${archive_mb}MB"
# 解密工具按环境择优：
#   - 有 node → 用 BackupDecrypt.mjs（OpenSSL 流式，内存恒定，同盘 1.3GB 约 3s）
#   - 否则 → 用 BackupDecrypt.java（JDK 的 GCM 解密会把整包缓冲，峰值 ≈2.6×归档，需要按体积给 -Xmx）
if command -v node >/dev/null 2>&1; then
  BACKUP_ENCRYPTION_SECRET="$BACKUP_ENCRYPTION_SECRET" \
    node "$tool_dir/BackupDecrypt.mjs" "$backup_file" "$work_dir/backup.zip"
elif command -v java >/dev/null 2>&1; then
  BACKUP_ENCRYPTION_SECRET="$BACKUP_ENCRYPTION_SECRET" \
    java -Xms$(( heap_mb / 2 ))m -Xmx"${heap_mb}"m \
    "$tool_dir/BackupDecrypt.java" "$backup_file" "$work_dir/backup.zip"
else
  docker run --rm --network none \
    -e BACKUP_ENCRYPTION_SECRET \
    -e JAVA_TOOL_OPTIONS="-Xms$(( heap_mb / 2 ))m -Xmx${heap_mb}m" \
    -v "$tool_dir:/tool:ro" \
    -v "$(dirname "$backup_file"):/backup:ro" \
    -v "$work_dir:/work" \
    maven:3.9-eclipse-temurin-17 \
    java /tool/BackupDecrypt.java "/backup/$(basename "$backup_file")" /work/backup.zip
fi
if command -v java >/dev/null 2>&1; then
  java "$tool_dir/BackupExtract.java" "$work_dir/backup.zip" "$work_dir/content"
else
  docker run --rm --network none \
    -v "$tool_dir:/tool:ro" \
    -v "$work_dir:/work" \
    maven:3.9-eclipse-temurin-17 \
    java /tool/BackupExtract.java /work/backup.zip /work/content
fi
test -f "$work_dir/content/database.dump"
test -f "$work_dir/content/manifest.json"
command -v jq >/dev/null
jq -r '.entries[] | "\(.sha256)  \(.path)"' "$work_dir/content/manifest.json" > "$work_dir/content/SHA256SUMS"
(cd "$work_dir/content" && sha256sum -c SHA256SUMS)

cd "$(dirname "$0")/../.."

# ② 定位部署：env 文件、容器名、网络、桶名全部从部署里解析，不写死（换目录名/compose 项目名依然可用）
if [[ -n ${ANTFLOW_ENV_FILE:-} ]]; then
  env_file=$ANTFLOW_ENV_FILE
else
  env_file=''
  for candidate in .env.docker.local .env; do
    if [[ -f $candidate ]]; then env_file=$candidate; break; fi
  done
fi
[[ -n $env_file ]] || { echo '找不到 compose 的 env 文件（.env.docker.local / .env），可用 ANTFLOW_ENV_FILE 指定' >&2; exit 1; }
compose() { docker compose --env-file "$env_file" "$@"; }

pg_cid=$(compose ps -q postgres || true)
[[ -n $pg_cid ]] || { echo 'postgres 容器不存在或未运行' >&2; exit 1; }
backend_cid=$(compose ps -q backend || true)
network=$(docker inspect -f '{{range $k, $v := .NetworkSettings.Networks}}{{$k}} {{end}}' "$pg_cid" | awk '{print $1}')
[[ -n $network ]] || { echo '拿不到 postgres 所在网络' >&2; exit 1; }

# 桶名优先读后端容器的真实配置（compose 里是 MINIO_BUCKET / AUDIT_ARCHIVE_BUCKET），拿不到再回落默认值
read_env() { docker inspect -f '{{range .Config.Env}}{{println .}}{{end}}' "$1" 2>/dev/null | sed -n "s/^$2=//p" | head -1; }
mobile_bucket=''
audit_bucket=''
if [[ -n $backend_cid ]]; then
  mobile_bucket=$(read_env "$backend_cid" MINIO_BUCKET)
  audit_bucket=$(read_env "$backend_cid" AUDIT_ARCHIVE_BUCKET)
fi
mobile_bucket=${mobile_bucket:-antflow-mobile-files}
audit_bucket=${audit_bucket:-antflow-audit-archives}

set -a
. "$env_file"
set +a
: "${POSTGRES_PASSWORD:?env 文件里缺少 POSTGRES_PASSWORD}"

# ③ 恢复前给当前库留一份快照：pg_restore --clean 会先删对象，失败时这份快照是唯一退路
safety_dump=''
if [[ ${SKIP_SAFETY_DUMP:-} != 1 ]]; then
  safety_dir=${PRE_RESTORE_DIR:-$(dirname "$backup_file")}
  mkdir -p "$safety_dir"
  safety_dump="$safety_dir/prerestore-$(date +%Y%m%d-%H%M%S).dump"
  echo "恢复前快照：$safety_dump（确定不需要可加 SKIP_SAFETY_DUMP=1 跳过）"
  docker exec -e PGPASSWORD="$POSTGRES_PASSWORD" "$pg_cid" \
    pg_dump --username antflow --dbname antflow --format=custom > "$safety_dump"
  [[ -s $safety_dump ]] || { echo '恢复前快照为空，已中止（未动线上）' >&2; exit 1; }
fi

# ④ 停机 → 事务性恢复（--single-transaction：全成或全败，不留半恢复的库）
compose stop web backend
docker cp "$work_dir/content/database.dump" "$pg_cid:/tmp/antflow-restore.dump"
docker exec -e PGPASSWORD="$POSTGRES_PASSWORD" "$pg_cid" \
  pg_restore --clean --if-exists --no-owner --no-privileges --single-transaction \
  --username antflow --dbname antflow /tmp/antflow-restore.dump
docker exec "$pg_cid" rm -f /tmp/antflow-restore.dump

# ⑤ 重建对象存储
for pair in "mobile:$mobile_bucket" "audit:$audit_bucket"; do
  source_name=${pair%%:*}; bucket=${pair#*:}
  test -d "$work_dir/content/minio/$source_name" || continue
  docker run --rm --network "$network" --entrypoint /bin/sh \
    -e MINIO_ROOT_USER -e MINIO_ROOT_PASSWORD -e RESTORE_BUCKET="$bucket" \
    -v "$work_dir/content/minio/$source_name:/restore:ro" quay.io/minio/mc \
    -c 'mc alias set local http://minio:9000 "$MINIO_ROOT_USER" "$MINIO_ROOT_PASSWORD" && mc mb --ignore-existing "local/$RESTORE_BUCKET" && mc mirror --overwrite --remove /restore "local/$RESTORE_BUCKET"'
done

# ⑥ 起服务 + 体检：健康检查通过并打印迁移末条，恢复才算完成
compose up -d backend web
health_url=${HEALTH_URL:-http://127.0.0.1:7070/actuator/health}
ready=0
for _ in $(seq 1 60); do
  if curl -fsS "$health_url" >/dev/null 2>&1; then ready=1; break; fi
  sleep 2
done
if (( ! ready )); then
  echo "恢复后健康检查未通过：$health_url（库已恢复，服务未就绪，请查 compose logs）" >&2
  exit 1
fi

echo 'Flyway 末条迁移：'
docker exec -e PGPASSWORD="$POSTGRES_PASSWORD" "$pg_cid" \
  psql -U antflow -d antflow -tAc \
  "SELECT version || ' | ' || description || ' | success=' || success FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 1"
if [[ -n $safety_dump ]]; then
  echo "恢复成功。恢复前快照仍在：$safety_dump（确认无误后可自行删除）"
else
  echo '恢复成功（本次跳过了恢复前快照）。'
fi
