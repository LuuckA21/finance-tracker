#!/usr/bin/env bash
# Uploads the latest local backup to the Restic repository again (e.g. after a cloud outage).
source "$(dirname -- "${BASH_SOURCE[0]}")/backup-common.sh"
[[ $# == 0 ]] || fail 'usage: scripts/cloud-backup.sh'
take_lock
upload_to_cloud "$(latest_backup)"
