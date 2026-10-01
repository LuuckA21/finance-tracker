#!/usr/bin/env bash
#
# Updates the code on the server and rebuilds/restarts the containers.
#
# Usage: ./deploy.sh                 # update the current branch
#        ./deploy.sh master          # switch to and update master
#        ./deploy.sh v1.0.0          # install exactly that release (also to go back to it)
#        FINANCE_HEALTH_TIMEOUT=600 ./deploy.sh master
#        FINANCE_SKIP_BACKUP=1 ./deploy.sh master   # skip the pre-deploy database backup
#
# Before touching the code it takes a local backup (scripts/backup.sh --local).

set -Eeuo pipefail

PROJECT="$(CDPATH='' cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
HEALTH_TIMEOUT="${FINANCE_HEALTH_TIMEOUT:-300}"

red()   { printf '\033[31m%s\033[0m\n' "$1" >&2; }
green() { printf '\033[32m%s\033[0m\n' "$1"; }
info()  { printf '\033[34m→\033[0m %s\n' "$1"; }

usage() {
    printf 'Usage: %s [BRANCH | vX.Y.Z]\n' "${0##*/}"
    printf '\nWithout an argument, the current branch is updated. A version (vX.Y.Z) installs that release.\n'
}

if (( $# > 1 )); then
    usage >&2
    exit 2
fi
case "${1:-}" in
    --help|-h) usage; exit 0 ;;
    -*)        red "Unknown option: $1"; usage >&2; exit 2 ;;
esac

if ! [[ "$HEALTH_TIMEOUT" =~ ^[1-9][0-9]*$ ]]; then
    red "FINANCE_HEALTH_TIMEOUT must be a positive number of seconds."
    exit 2
fi

# Rootless Docker: rebuild the socket variable that non-interactive shells
# (cron, ssh host command) do not get from the user's startup files.
if [[ -z "${DOCKER_HOST:-}" ]]; then
    RUNTIME_DIR="${XDG_RUNTIME_DIR:-/run/user/$(id -u)}"
    if [[ -S "$RUNTIME_DIR/docker.sock" ]]; then
        export DOCKER_HOST="unix://$RUNTIME_DIR/docker.sock"
    fi
fi

if ! docker compose version >/dev/null 2>&1; then
    red "Docker Compose v2 is not available for user $(whoami)."
    exit 1
fi

cd -- "$PROJECT"

if [[ ! -f .env ]]; then
    red "Missing $PROJECT/.env: copy .env.example and fill in DB_PASSWORD and APP_ENCRYPTION_KEY."
    exit 1
fi

if ! git diff --quiet || ! git diff --cached --quiet; then
    red "There are uncommitted tracked changes in $PROJECT:"
    git status --short | grep -v '^??' >&2 || true
    red "Commit or restore them before deploying."
    exit 1
fi

TARGET="${1:-$(git symbolic-ref --quiet --short HEAD || true)}"
if [[ -z "$TARGET" ]]; then
    red "A release is installed (no current branch). Name a branch or a version, for example: $0 master or $0 v1.0.0"
    exit 1
fi
# A release tag (vX.Y.Z) is installed as it is; anything else is a branch to update
RELEASE=0
if [[ "$TARGET" =~ ^v[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
    RELEASE=1
elif ! git check-ref-format --branch "$TARGET" >/dev/null 2>&1; then
    red "Invalid branch name: $TARGET"
    exit 2
fi

info "Fetching origin"
git fetch --prune --tags origin
if (( RELEASE )); then
    if ! git show-ref --verify --quiet "refs/tags/$TARGET"; then
        red "Release not found: $TARGET (git tag --list 'v*' shows the ones available)"
        exit 1
    fi
elif ! git show-ref --verify --quiet "refs/remotes/origin/$TARGET"; then
    red "Remote branch not found: origin/$TARGET"
    exit 1
fi

# A local copy is enough to go back after a bad migration; the nightly timer handles the cloud
if [[ "${FINANCE_SKIP_BACKUP:-}" == 1 ]]; then
    info "Pre-deploy backup skipped (FINANCE_SKIP_BACKUP=1)"
elif [[ ! -x scripts/backup.sh ]]; then
    info "No scripts/backup.sh in this checkout: pre-deploy backup skipped"
elif ! docker compose ps --status running --services 2>/dev/null | grep -qx db; then
    info "Database not running (first install?): pre-deploy backup skipped"
else
    info "Backing up the database"
    scripts/backup.sh --local --reason predeploy
fi

PREVIOUS_COMMIT="$(git rev-parse HEAD)"
if (( RELEASE )); then
    git switch --detach "refs/tags/$TARGET"
elif git show-ref --verify --quiet "refs/heads/$TARGET"; then
    git switch "$TARGET"
    git merge --ff-only "origin/$TARGET"
else
    git switch --track -c "$TARGET" "origin/$TARGET"
fi
DEPLOYED_COMMIT="$(git rev-parse HEAD)"

if [[ "$PREVIOUS_COMMIT" == "$DEPLOYED_COMMIT" ]]; then
    info "Code already at ${DEPLOYED_COMMIT:0:7}"
else
    info "Updated from ${PREVIOUS_COMMIT:0:7} to ${DEPLOYED_COMMIT:0:7}"
    # Empty when going back to an older release
    git --no-pager log --max-count=10 --oneline "$PREVIOUS_COMMIT..$DEPLOYED_COMMIT"
fi

info "Rebuilding and restarting containers"
docker compose up -d --build --remove-orphans --wait --wait-timeout "$HEALTH_TIMEOUT"

# The backend is not published and its image has no HTTP client: ask it from
# the web container (busybox wget) over the compose network.
info "Waiting for the backend"
deadline=$(( SECONDS + HEALTH_TIMEOUT ))
until docker compose exec -T web \
        wget -qO- http://backend:8080/actuator/health/readiness 2>/dev/null |
        grep -q '"status"[[:space:]]*:[[:space:]]*"UP"'; do
    if (( SECONDS >= deadline )); then
        red "The backend is not ready after ${HEALTH_TIMEOUT}s."
        docker compose ps >&2
        docker compose logs --tail=80 backend >&2
        exit 1
    fi
    sleep 3
done

docker image prune -f >/dev/null

green "Deployed $TARGET at ${DEPLOYED_COMMIT:0:7}"
docker compose ps
