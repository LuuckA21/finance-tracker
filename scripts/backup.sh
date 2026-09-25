#!/usr/bin/env bash
#
# Backs up the database, .env and docker-compose.yml into a local, checksummed directory
# and, when Restic is configured (scripts/install-backup.sh), uploads it to the cloud.
#
# Usage: scripts/backup.sh                     # local + cloud if configured
#        scripts/backup.sh --local             # local only
#        scripts/backup.sh --reason predeploy  # tag the directory name (default: manual)
source "$(dirname -- "${BASH_SOURCE[0]}")/backup-common.sh"

reason=manual
cloud=auto
while (( $# > 0 )); do
  case $1 in
    --local) cloud=no ;;
    --reason) reason=${2:-}; shift ;;
    -h|--help) sed -n '3,9p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) fail "unknown argument: $1" ;;
  esac
  shift
done
[[ "$reason" =~ ^(scheduled|manual|predeploy|prerestore)$ ]] || fail "invalid reason: $reason"

take_lock
dir=$(create_local_backup "$reason")
log "Backup completed: $dir"

if [[ "$cloud" == auto ]]; then
  if cloud_configured; then
    upload_to_cloud "$dir"
  else
    log "Cloud not configured ($CONFIG_DIR/env.sh): local backup only."
  fi
fi
