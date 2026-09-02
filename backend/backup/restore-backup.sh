#!/usr/bin/env bash
set -euo pipefail

if [[ ${2:-} != --confirm || ! -f ${1:-} ]]; then
  echo "usage: BACKUP_ENCRYPTION_SECRET=... $0 BACKUP.afbackup --confirm" >&2
  exit 2
fi
: "${BACKUP_ENCRYPTION_SECRET:?set BACKUP_ENCRYPTION_SECRET}"

backup_file=$(realpath "$1")
work_dir=$(mktemp -d /tmp/antflow-restore.XXXXXX)
trap 'rm -rf -- "$work_dir"' EXIT
tool_dir=$(realpath "$(dirname "$0")")
docker run --rm --network none \
  -e BACKUP_ENCRYPTION_SECRET \
  -v "$tool_dir:/tool:ro" \
  -v "$(dirname "$backup_file"):/backup:ro" \
  -v "$work_dir:/work" \
  maven:3.9-eclipse-temurin-17 \
  java /tool/BackupDecrypt.java "/backup/$(basename "$backup_file")" /work/backup.zip
if unzip -Z1 "$work_dir/backup.zip" | grep -Eq '(^/|(^|/)\.\.(/|$)|\\)'; then
  echo "backup contains an unsafe path" >&2
  exit 1
fi
unzip -q "$work_dir/backup.zip" -d "$work_dir/content"
test -f "$work_dir/content/database.dump"
test -f "$work_dir/content/manifest.json"
command -v jq >/dev/null
jq -r '.entries[] | "\(.sha256)  \(.path)"' "$work_dir/content/manifest.json" > "$work_dir/content/SHA256SUMS"
(cd "$work_dir/content" && sha256sum -c SHA256SUMS)

cd "$(dirname "$0")/../.."
set -a
. ./.env
set +a
docker compose --env-file .env stop web backend
docker cp "$work_dir/content/database.dump" antflow-local-postgres-1:/tmp/antflow-restore.dump
docker exec -e PGPASSWORD="$POSTGRES_PASSWORD" antflow-local-postgres-1 \
  pg_restore --clean --if-exists --no-owner --no-privileges \
  --username antflow --dbname antflow /tmp/antflow-restore.dump

for pair in "mobile:antflow-local-mobile-files" "audit:antflow-local-audit-archives"; do
  source_name=${pair%%:*}; bucket=${pair#*:}
  test -d "$work_dir/content/minio/$source_name" || continue
  docker run --rm --network antflow-local_default \
    -v "$work_dir/content/minio/$source_name:/restore:ro" quay.io/minio/mc \
    sh -c "mc alias set local http://minio:9000 '$MINIO_ROOT_USER' '$MINIO_ROOT_PASSWORD' && mc mb --ignore-existing local/$bucket && mc mirror --overwrite --remove /restore local/$bucket"
done
docker compose --env-file .env up -d backend web
