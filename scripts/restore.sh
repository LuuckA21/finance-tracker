#!/usr/bin/env bash
#
# Restores the database from a backup. A "prerestore" backup of the current data is taken first.
#
# Usage: scripts/restore.sh BACKUP_DIR --yes
#        scripts/restore.sh --from-cloud [SNAPSHOT] --yes   # default: latest cloud snapshot
#        scripts/restore.sh --list-cloud
#
# .env is not overwritten: the backup's copy is kept next to the dump. If APP_ENCRYPTION_KEY
# differs, the script says so (2FA secrets were encrypted with the backup's key).
source "$(dirname -- "${BASH_SOURCE[0]}")/backup-common.sh"

usage() { sed -n '3,11p' "$0" | sed 's/^# \{0,1\}//'; }
source_dir=''
cloud_snapshot=''
confirmed=no
while (( $# > 0 )); do
  case $1 in
    --yes) confirmed=yes ;;
    --list-cloud) load_restic_config; restic snapshots --tag finance-tracker; exit 0 ;;
    --from-cloud)
      cloud_snapshot=latest
      if [[ ${2:-} =~ ^[0-9a-f]{8,64}$ ]]; then cloud_snapshot=$2; shift; fi ;;
    -h|--help) usage; exit 0 ;;
    -*) fail "unknown option: $1" ;;
    *) source_dir=$1 ;;
  esac
  shift
done
[[ -n "$source_dir" || -n "$cloud_snapshot" ]] || { usage >&2; exit 2; }
[[ "$confirmed" == yes ]] || fail 'restoring replaces the current data: add --yes to confirm'

take_lock
work=''
cleanup() { if [[ -n "$work" ]]; then rm -rf -- "$work"; fi; }
trap cleanup EXIT

if [[ -n "$cloud_snapshot" ]]; then
  load_restic_config
  work=$(mktemp -d "$BACKUP_ROOT/.restore-XXXXXXXX")
  log "Downloading cloud snapshot $cloud_snapshot"
  restic restore "$cloud_snapshot" --host "$BACKUP_HOST" --tag finance-tracker --target "$work"
  source_dir=$(dirname -- "$(find "$work" -name "SHA256SUMS" -print -quit)")
  [[ "$source_dir" != . ]] || fail 'the snapshot does not contain a backup'
fi
source_dir=$(cd -- "$source_dir" && pwd -P)
verify_backup_dir "$source_dir"

# Older data is fine (Flyway migrates it on startup); newer data would stop this code from starting.
backup_version=$(cat "$source_dir/schema-version")
code_version=$(code_schema_version)
if (( backup_version > code_version )); then
  fail "the backup has schema V$backup_version but this code only knows V$code_version: deploy the newer code first"
fi

cd -- "$PROJECT_DIR"
db_running || fail 'the db container is not running (docker compose up -d db)'
safety=$(create_local_backup prerestore)
log "Current data saved in $safety"

key() { sed -n 's/^APP_ENCRYPTION_KEY=//p' "$1" | tail -n 1; }
if [[ "$(key "$source_dir/.env")" != "$(key .env)" ]]; then
  log "WARNING: APP_ENCRYPTION_KEY differs from the backup's. Users with 2FA will need it reset,"
  log "         unless you put the backup's key back in .env: $source_dir/.env"
fi

sql() { compose exec -T db psql -U finance -d postgres -v ON_ERROR_STOP=1 -qAt -c "$1"; }

# Restore into a fresh database and swap it in only once complete: a failed restore leaves the
# current data untouched, and no table from a newer schema survives next to the old one.
log "Restoring $(basename -- "$source_dir") into a new database"
sql 'DROP DATABASE IF EXISTS finance_restore'
sql 'CREATE DATABASE finance_restore OWNER finance'
if ! compose exec -T db pg_restore -U finance -d finance_restore --no-owner --exit-on-error \
    < "$source_dir/database.dump"; then
  sql 'DROP DATABASE IF EXISTS finance_restore'
  fail 'pg_restore failed: the current database was not touched'
fi

log "Stopping backend and web"
compose stop backend web
trap 'cleanup; compose start backend web >/dev/null 2>&1 || true' EXIT
sql "SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname = 'finance' AND pid <> pg_backend_pid()" >/dev/null
sql 'ALTER DATABASE finance RENAME TO finance_before_restore'
sql 'ALTER DATABASE finance_restore RENAME TO finance'
sql 'DROP DATABASE finance_before_restore'

log "Starting backend and web"
compose start backend web
trap cleanup EXIT
log "Restore completed from $source_dir (previous data: $safety)"
