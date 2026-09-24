#!/usr/bin/env bash
#
# Updates the code on the server and rebuilds/restarts the containers.
#
# Usage: ./deploy.sh                 # update the current branch
#        ./deploy.sh master          # switch to and update master
#        FINANCE_HEALTH_TIMEOUT=600 ./deploy.sh master

set -Eeuo pipefail

PROJECT="$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
HEALTH_TIMEOUT="${FINANCE_HEALTH_TIMEOUT:-300}"

red()   { printf '\033[31m%s\033[0m\n' "$1" >&2; }
green() { printf '\033[32m%s\033[0m\n' "$1"; }
info()  { printf '\033[34m→\033[0m %s\n' "$1"; }

usage() {
    printf 'Usage: %s [BRANCH]\n' "${0##*/}"
    printf '\nWithout BRANCH, the current branch is updated.\n'
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

BRANCH="${1:-$(git symbolic-ref --quiet --short HEAD || true)}"
if [[ -z "$BRANCH" ]]; then
    red "The repository is detached. Specify the branch explicitly, for example: $0 master"
    exit 1
fi
if ! git check-ref-format --branch "$BRANCH" >/dev/null 2>&1; then
    red "Invalid branch name: $BRANCH"
    exit 2
fi

info "Fetching origin"
git fetch --prune origin
if ! git show-ref --verify --quiet "refs/remotes/origin/$BRANCH"; then
    red "Remote branch not found: origin/$BRANCH"
    exit 1
fi

PREVIOUS_COMMIT="$(git rev-parse HEAD)"
if git show-ref --verify --quiet "refs/heads/$BRANCH"; then
    git switch "$BRANCH"
else
    git switch --track -c "$BRANCH" "origin/$BRANCH"
fi
git merge --ff-only "origin/$BRANCH"
DEPLOYED_COMMIT="$(git rev-parse HEAD)"

if [[ "$PREVIOUS_COMMIT" == "$DEPLOYED_COMMIT" ]]; then
    info "Code already at ${DEPLOYED_COMMIT:0:7}"
else
    info "Updated from ${PREVIOUS_COMMIT:0:7} to ${DEPLOYED_COMMIT:0:7}"
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

green "Deployed $BRANCH at ${DEPLOYED_COMMIT:0:7}"
docker compose ps
