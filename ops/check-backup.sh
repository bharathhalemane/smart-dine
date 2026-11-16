#!/usr/bin/env bash
# Exits non-zero if the newest backup is missing, tiny or older than MAX_AGE_HOURS (default 8).
# Usage: bash ops/check-backup.sh        Env: BACKUP_DIR (default ./backups), MAX_AGE_HOURS
set -euo pipefail
cd "$(dirname "$0")/.."

DIR="${BACKUP_DIR:-./backups}"
MAX="${MAX_AGE_HOURS:-8}"
latest="$(ls -1t "$DIR"/qrorder-*.archive.gz 2>/dev/null | head -n 1 || true)"

if [ -z "$latest" ]; then
  echo "NO BACKUP FOUND in $DIR"
  exit 1
fi
size="$(stat -c %s "$latest")"
age_h=$(( ( $(date +%s) - $(stat -c %Y "$latest") ) / 3600 ))

if [ "$size" -le 100 ]; then
  echo "LATEST BACKUP IS TOO SMALL ($size bytes): $latest"
  exit 1
fi
if [ "$age_h" -gt "$MAX" ]; then
  echo "LATEST BACKUP IS ${age_h}h OLD (limit ${MAX}h): $latest"
  exit 1
fi
echo "ok: $latest (${age_h}h old, ${size} bytes)"