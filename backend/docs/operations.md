# Operations guide

## Indexes
- Production creates indexes at startup (`IndexInitializer`, enabled by `app.indexes.ensure-on-startup`). Look for the log line
  `Indexes ensured: N declared, M created [...]`.
- **Adding an index:** add the annotation, deploy. It is created at startup.
- **Changing or removing an index (Changing an index):** MongoDB cannot alter an index in place, and the initializer never drops anything.
    1. Add the new index under a NEW name and deploy.
    2. Check the new index exists, then drop the old one by hand:
       `docker compose -f docker-compose.prod.yml exec mongo mongosh qrorder --eval 'db.<collection>.dropIndex("<old name>")'`
- If startup stops with "Cannot create index ...": an index with that name already exists with different options, or existing data
  breaks a new unique index (duplicates). Fix the data or the name, never disable the check.
- List stale indexes: `db.<collection>.getIndexes()` and compare with the code.

## Backups
- Take one: `bash ops/backup.sh` (whole instance, consistent via oplog, gzip, files older than 14 days are deleted).
- Schedule (every 6 hours, on the server): `0 */6 * * * cd /opt/qr-order && bash ops/backup.sh >> backups/backup.log 2>&1`
- Your maximum data loss equals the time since the last backup. Daily = up to 24 h of orders and payments.
- **Copy backups off the server** (object storage, another machine). A backup on the same disk dies with the server.
  Backups contain customer emails and password hashes: encrypt them before they leave the server (for example with `age` or `gpg`).
- **Test a restore monthly:** `bash ops/restore-test.sh backups/<file>`; compare the counts with the live database.
  A backup you have never restored is a hope, not a backup.

## Restoring after a disaster
1. `docker compose -f docker-compose.prod.yml stop app caddy`
2. `docker compose -f docker-compose.prod.yml exec -T mongo mongorestore --archive --gzip --oplogReplay --drop < backups/<file>`
3. `docker compose -f docker-compose.prod.yml start app caddy`
4. Run the reconciliation report (10.7) for the period after the backup: payments received by Razorpay in that window
   may be missing from the database and must be re-entered or replayed from Razorpay's dashboard.

## MongoDB access control
Mongo is not published to the host and has no password; only containers on the compose network can reach it.
Before real customer data, pick one:
- **MongoDB Atlas** (managed): authentication, TLS, backups and replica set included. Set `MONGODB_URI` to the `mongodb+srv://` string;
  the startup guard already accepts it. A free tier exists for staging; check its backup features before using it for production.
- **Self-hosted with authentication:** needs a keyfile and an admin user. Ask for this as its own step; do not improvise it.

## Image storage
- Create a bucket in an S3-compatible store (Cloudflare R2, AWS S3, ...) and an API token that can read, write and delete objects in that bucket only.
- Serve images from a public address (custom domain or CDN) and put it in `APP_MEDIA_PUBLICBASEURL`. Use a different domain from the API, so image content can never share cookies with it.
- Browsers upload directly to the bucket, so it needs CORS for your frontend origin:
  `[{"AllowedOrigins":["https://app.yourdomain.com"],"AllowedMethods":["PUT"],"AllowedHeaders":["Content-Type"],"MaxAgeSeconds":3600}]`
- Add a bucket lifecycle rule that deletes objects older than a few days under no prefix you care about, only if you accept that images
  that were uploaded but never attached are removed; this platform already removes unfinished uploads itself.
- The frontend's Content-Security-Policy needs `img-src` to include the public address.