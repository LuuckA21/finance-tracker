#!/usr/bin/env bash
# Shared settings and functions for the backup scripts. Sourced, not executed.
set -Eeuo pipefail
# Errors inside $(...) must stop the script too (bash drops errexit there by default)
shopt -s inherit_errexit
umask 077

PROJECT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
default_root="$PROJECT_DIR/backups"
if [[ "$PROJECT_DIR" == /srv/apps/finance-tracker ]]; then default_root=/srv/backups/finance-tracker; fi
BACKUP_ROOT=${FT_BACKUP_ROOT:-$default_root}
mkdir -p -- "$BACKUP_ROOT"
BACKUP_ROOT=$(cd -- "$BACKUP_ROOT" && pwd -P)
LOCAL_RETENTION_DAYS=${FT_LOCAL_RETENTION_DAYS:-14}
CONFIG_DIR=${FT_RESTIC_CONFIG_DIR:-$HOME/.config/restic/finance-tracker}
BACKUP_HOST=${FT_BACKUP_HOST:-$(hostname)}
MARKER=finance-tracker-backup-v1
# 20260925-043000-scheduled
NAME_RE='^[0-9]{8}-[0-9]{6}-(scheduled|manual|predeploy|prerestore)$'
export TZ=${TZ:-Europe/Zurich}

# systemd and cron do not get the rootless Docker socket from the login shell
export XDG_RUNTIME_DIR=${XDG_RUNTIME_DIR:-/run/user/$(id -u)}
if [[ -z ${DOCKER_HOST:-} && -S "$XDG_RUNTIME_DIR/docker.sock" ]]; then
  export DOCKER_HOST="unix://$XDG_RUNTIME_DIR/docker.sock"
fi

log() { printf '%s\n' "$*"; }
fail() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }

compose() { docker compose --project-directory "$PROJECT_DIR" "$@"; }

# One backup, cloud upload or restore at a time. Scripts that call each other share the lock.
take_lock() {
  [[ -n ${FT_BACKUP_LOCKED:-} ]] && return 0
  exec 9>"$BACKUP_ROOT/.lock"
  flock -w 900 9 || fail 'another backup or restore is still running'
  export FT_BACKUP_LOCKED=1
}

db_running() { compose ps --status running --services 2>/dev/null | grep -qx db; }

# Highest Flyway version shipped with the checked-out code.
code_schema_version() {
  find "$PROJECT_DIR/backend/src/main/resources/db/migration" -name 'V*__*.sql' -printf '%f\n' \
    | sed -E 's/^V([0-9]+)__.*/\1/' | sort -n | tail -n 1
}

verify_backup_dir() {
  local dir=$1
  [[ -d "$dir" && ! -L "$dir" ]] || fail "not a backup directory: $dir"
  [[ -f "$dir/.$MARKER" && $(cat "$dir/.$MARKER") == "$MARKER" ]] || fail "not a finance-tracker backup: $dir"
  (cd -- "$dir" && sha256sum --quiet --strict -c SHA256SUMS) || fail "checksum mismatch in $dir"
}

latest_backup() {
  [[ -f "$BACKUP_ROOT/last-success" ]] || fail "no successful backup recorded in $BACKUP_ROOT"
  local path
  path=$(cat "$BACKUP_ROOT/last-success")
  [[ "${path%/*}" == "$BACKUP_ROOT" && "${path##*/}" =~ $NAME_RE ]] || fail "unexpected last-success: $path"
  printf '%s\n' "$path"
}

# Dumps the database with .env and Compose file into BACKUP_ROOT/<timestamp>-<reason>.
# Prints the new directory on stdout. Needs the db container running.
create_local_backup() {
  local reason=$1 stage final file
  cd -- "$PROJECT_DIR"
  [[ -f .env ]] || fail "missing $PROJECT_DIR/.env"
  db_running || fail 'the db container is not running (docker compose up -d db)'

  stage=$(mktemp -d "$BACKUP_ROOT/.partial-XXXXXXXX")
  # shellcheck disable=SC2064 # expand now: the variable is local
  trap "rm -rf -- '$stage'" RETURN
  compose exec -T db pg_dump -U finance -d finance --format=custom > "$stage/database.dump"
  # A dump that pg_restore cannot list is no backup
  compose exec -T db pg_restore --list < "$stage/database.dump" > /dev/null
  compose exec -T db psql -U finance -d finance -Atc \
    'SELECT max(version::int) FROM flyway_schema_history WHERE success' > "$stage/schema-version"
  install -m 600 .env docker-compose.yml "$stage/"
  git -C "$PROJECT_DIR" rev-parse HEAD > "$stage/commit" 2>/dev/null || echo unknown > "$stage/commit"
  printf '%s\n' "$MARKER" > "$stage/.$MARKER"
  (cd -- "$stage" && sha256sum database.dump schema-version .env docker-compose.yml commit ".$MARKER" > SHA256SUMS)
  verify_backup_dir "$stage"

  final="$BACKUP_ROOT/$(date +%Y%m%d-%H%M%S)-$reason"
  [[ ! -e "$final" ]] || fail "$final already exists"
  mv -T -- "$stage" "$final"
  file=$(mktemp "$BACKUP_ROOT/.last-success-XXXXXXXX")
  printf '%s\n' "$final" > "$file"
  mv -f -- "$file" "$BACKUP_ROOT/last-success"

  # Local retention: only directories this script created and marked
  find "$BACKUP_ROOT" -mindepth 1 -maxdepth 1 -type d -mtime +"$((LOCAL_RETENTION_DAYS - 1))" -print0 |
    while IFS= read -r -d '' old; do
      [[ "${old##*/}" =~ $NAME_RE && "$old" != "$final" && -f "$old/.$MARKER" ]] || continue
      rm -rf -- "$old"
      log "Expired local backup removed: ${old##*/}" >&2
    done
  printf '%s\n' "$final"
}

cloud_configured() { [[ -f "$CONFIG_DIR/env.sh" ]]; }

load_restic_config() {
  command -v restic >/dev/null || fail 'restic is not installed'
  [[ -f "$CONFIG_DIR/env.sh" && ! -L "$CONFIG_DIR/env.sh" ]] || fail "missing $CONFIG_DIR/env.sh"
  # shellcheck source=/dev/null
  source "$CONFIG_DIR/env.sh"
  [[ ${RESTIC_REPOSITORY:-} == */finance-tracker ]] ||
    fail 'RESTIC_REPOSITORY must be a dedicated repository ending in /finance-tracker'
  [[ -s ${RESTIC_PASSWORD_FILE:-} ]] || fail 'RESTIC_PASSWORD_FILE is missing or empty'
  export RESTIC_REPOSITORY RESTIC_PASSWORD_FILE
  export AWS_ACCESS_KEY_ID=${AWS_ACCESS_KEY_ID:-} AWS_SECRET_ACCESS_KEY=${AWS_SECRET_ACCESS_KEY:-}
  export AWS_DEFAULT_REGION=${AWS_DEFAULT_REGION:-us-east-1}
}

# Uploads one local backup to the Restic repository, applies retention and checks the repository.
upload_to_cloud() {
  local dir=$1 out snapshot file
  load_restic_config
  verify_backup_dir "$dir"
  out=$(restic backup "$dir" --host "$BACKUP_HOST" --tag finance-tracker --json)
  snapshot=$(grep -o '"snapshot_id":"[0-9a-f]*"' <<< "$out" | tail -n 1 | cut -d'"' -f4)
  [[ -n "$snapshot" ]] || fail 'restic did not report a snapshot id'
  restic forget --host "$BACKUP_HOST" --tag finance-tracker --group-by host,tags \
    --keep-daily 14 --keep-weekly 8 --keep-monthly 12 --prune
  restic check
  file=$(mktemp "$BACKUP_ROOT/.last-cloud-XXXXXXXX")
  printf 'completed_at=%s\nsnapshot=%s\nsource=%s\nrepository=%s\n' \
    "$(date --iso-8601=seconds)" "$snapshot" "$dir" "$RESTIC_REPOSITORY" > "$file"
  mv -f -- "$file" "$BACKUP_ROOT/last-cloud-success"
  log "Cloud backup completed: snapshot $snapshot"
}
