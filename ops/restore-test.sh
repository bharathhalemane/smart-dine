#!/usr/bin/env bash
# Proves a backup is usable: restores it into a throwaway Mongo container and prints document counts.
# It never touches the real database.   Usage: bash ops/restore-test.sh backups/qrorder-<stamp>.archive.gz
set -euo pipefail
FILE="${1:?usage: restore-test.sh <backup-file>}"
NAME="qr-restore-test-$$"
trap 'docker rm -f "$NAME" >/dev/null 2>&1 || true' EXIT

docker run -d --name "$NAME" mongo:7.0 >/dev/null
for _ in $(seq 1 30); do
  docker exec "$NAME" mongosh --quiet --eval 'db.runCommand({ping:1}).ok' >/dev/null 2>&1 && break
  sleep 1
done

docker exec -i "$NAME" mongorestore --quiet --archive --gzip --oplogReplay < "$FILE"
echo "--- documents per collection in the restored 'qrorder' database ---"
docker exec "$NAME" mongosh --quiet qrorder --eval \
  'db.getCollectionNames().sort().forEach(n => print(n.padEnd(28), db[n].countDocuments()))'
echo "--- done: compare these counts with the live database ---"