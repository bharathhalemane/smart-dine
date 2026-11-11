#!/usr/bin/env bash
# Consistent snapshot of the whole Mongo instance (--oplog), compressed, with rotation.
# Usage (from anywhere):  bash ops/backup.sh      Env: BACKUP_DIR (default ./backups), KEEP_DAYS (default 14)
set -euo pipefail
cd "$(dirname "$0")/.."

DIR="${BACKUP_DIR:-./backups}"
KEEP_DAYS="${KEEP_DAYS:-14}"
mkdir -p "$DIR"
FILE="$DIR/qrorder-$(date -u +%Y%m%dT%H%M%SZ).archive.gz"

docker compose -f docker-compose.prod.yml exec -T mongo \
  mongodump --quiet --archive --gzip --oplog > "$FILE.partial"

if [ ! -s "$FILE.partial" ]; then
  echo "backup is empty, aborting" >&2
  rm -f "$FILE.partial"
  exit 1
fi
mv "$FILE.partial" "$FILE"
find "$DIR" -name 'qrorder-*.archive.gz' -mtime +"$KEEP_DAYS" -delete
echo "ok $FILE ($(du -h "$FILE" | cut -f1))"