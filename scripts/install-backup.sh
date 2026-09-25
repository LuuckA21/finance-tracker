#!/usr/bin/env bash
#
# Sets up the daily backup: Restic configuration (optional) and the systemd user timer.
# Runs one backup at the end to prove the chain works. Safe to run again.
#
# Usage: scripts/install-backup.sh --cloud-from ~/.config/restic/mangashelf/env.sh
#            reuse the S3 credentials of an existing Restic setup (e.g. Swiss Backup for
#            MangaShelf); the repository becomes <same bucket>/finance-tracker
#        RESTIC_REPOSITORY=s3:https://.../finance-tracker AWS_ACCESS_KEY_ID=... \
#        AWS_SECRET_ACCESS_KEY=... scripts/install-backup.sh --cloud
#        scripts/install-backup.sh --local      # no cloud copy
source "$(dirname -- "${BASH_SOURCE[0]}")/backup-common.sh"

usage() { sed -n '3,13p' "$0" | sed 's/^# \{0,1\}//'; }
mode=${1:-}
case $mode in
  --local) (( $# == 1 )) || { usage >&2; exit 2; } ;;
  --cloud) (( $# == 1 )) || { usage >&2; exit 2; } ;;
  --cloud-from) (( $# == 2 )) || { usage >&2; exit 2; }; from=$2 ;;
  -h|--help) usage; exit 0 ;;
  *) usage >&2; exit 2 ;;
esac
for cmd in docker flock sha256sum systemctl; do command -v "$cmd" >/dev/null || fail "$cmd is required"; done
[[ -f "$PROJECT_DIR/.env" ]] || fail "missing $PROJECT_DIR/.env"
[[ "$PROJECT_DIR" =~ ^/[A-Za-z0-9_./-]+$ && "$BACKUP_ROOT" =~ ^/[A-Za-z0-9_./-]+$ ]] ||
  fail 'project and backup paths may only contain letters, digits and ._/-'
export DBUS_SESSION_BUS_ADDRESS=${DBUS_SESSION_BUS_ADDRESS:-unix:path=$XDG_RUNTIME_DIR/bus}

write_cloud_config() {
  local repository=$1 key_id=$2 secret=$3 region=$4 tmp
  [[ "$repository" == s3:*/finance-tracker ]] || fail "repository must be s3:.../finance-tracker, got: $repository"
  [[ -n "$key_id" && -n "$secret" ]] || fail 'S3 access key and secret are required'
  install -d -m 700 "$CONFIG_DIR"
  if [[ -f "$CONFIG_DIR/env.sh" ]]; then
    # shellcheck source=/dev/null
    (source "$CONFIG_DIR/env.sh"; [[ ${RESTIC_REPOSITORY:-} == "$repository" ]]) ||
      fail "$CONFIG_DIR/env.sh points to another repository: left unchanged"
  fi
  if [[ ! -s "$CONFIG_DIR/password" ]]; then
    (set -o noclobber; openssl rand -base64 48 > "$CONFIG_DIR/password")
    log "New Restic password: $CONFIG_DIR/password"
  fi
  tmp=$(mktemp "$CONFIG_DIR/.env-XXXXXXXX")
  {
    printf 'export RESTIC_REPOSITORY=%q\n' "$repository"
    printf 'export RESTIC_PASSWORD_FILE=%q\n' "$CONFIG_DIR/password"
    printf 'export AWS_ACCESS_KEY_ID=%q\n' "$key_id"
    printf 'export AWS_SECRET_ACCESS_KEY=%q\n' "$secret"
    printf 'export AWS_DEFAULT_REGION=%q\n' "$region"
  } > "$tmp"
  mv -f -- "$tmp" "$CONFIG_DIR/env.sh"
  load_restic_config
  # init refuses an existing repository, so an existing key is never replaced
  if restic cat config >/dev/null 2>&1; then
    log "Restic repository already initialised: $RESTIC_REPOSITORY"
  else
    restic init
  fi
}

case $mode in
  --cloud-from)
    [[ -f "$from" ]] || fail "not found: $from"
    (
      # shellcheck source=/dev/null
      source "$from"
      [[ ${RESTIC_REPOSITORY:-} == s3:*/* ]] || fail "$from has no S3 RESTIC_REPOSITORY"
      write_cloud_config "${RESTIC_REPOSITORY%/*}/finance-tracker" "${AWS_ACCESS_KEY_ID:-}" \
        "${AWS_SECRET_ACCESS_KEY:-}" "${AWS_DEFAULT_REGION:-us-east-1}"
    ) ;;
  --cloud)
    write_cloud_config "${RESTIC_REPOSITORY:-}" "${AWS_ACCESS_KEY_ID:-}" "${AWS_SECRET_ACCESS_KEY:-}" \
      "${AWS_DEFAULT_REGION:-us-east-1}" ;;
  --local)
    if cloud_configured; then
      log "Note: $CONFIG_DIR/env.sh exists, so the timer will still upload to the cloud."
    fi ;;
esac

unit_dir=${FT_SYSTEMD_USER_DIR:-$HOME/.config/systemd/user}
install -d -m 700 "$unit_dir"
for name in finance-tracker-backup.service finance-tracker-backup.timer; do
  sed -e "s|/srv/apps/finance-tracker|$PROJECT_DIR|g" -e "s|/srv/backups/finance-tracker|$BACKUP_ROOT|g" \
    "$PROJECT_DIR/ops/systemd/$name" > "$unit_dir/$name"
done
systemctl --user daemon-reload

log "Running a first backup through systemd"
if ! systemctl --user start finance-tracker-backup.service; then
  journalctl --user -u finance-tracker-backup.service -n 60 --no-pager || true
  fail 'the first backup failed; the timer was not enabled'
fi
systemctl --user enable --now finance-tracker-backup.timer
systemctl --user list-timers finance-tracker-backup.timer --no-pager

if command -v loginctl >/dev/null &&
   [[ "$(loginctl show-user "$(id -un)" -p Linger --value 2>/dev/null)" != yes ]]; then
  log "WARNING: linger is off, user timers stop at logout: sudo loginctl enable-linger $(id -un)"
fi
log "Latest backup: $(cat "$BACKUP_ROOT/last-success")"
if cloud_configured; then
  cat "$BACKUP_ROOT/last-cloud-success"
  log "Save the Restic password ($CONFIG_DIR/password) and the S3 credentials in your password"
  log "manager: without them the cloud copy cannot be read."
fi
