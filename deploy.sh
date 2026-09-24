#!/usr/bin/env bash
#
# Installs or updates Finance Tracker and rebuilds the containers.
#
# Usage: ./deploy.sh                 # update the current branch
#        ./deploy.sh master            # switch to and update master
#        ./deploy.sh --rollback      # restore the code used before the last deploy
#        FINANCE_REPO=git@github.com:tu/altro.git ./deploy.sh master
#        FINANCE_ROOT=/opt ./deploy.sh master
#
# The script can live next to the project directory or in its repository root.
# Before touching the code it dumps the database into $FINANCE_BACKUP_DIR
# (default: <root>/finance-tracker-backups), keeping the last $FINANCE_BACKUP_KEEP.

set -Eeuo pipefail
umask 077

SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
if [[ -n "${FINANCE_ROOT:-}" ]]; then
    ROOT="$FINANCE_ROOT"
    PROJECT="$ROOT/finance-tracker"
elif [[ -d "$SCRIPT_DIR/.git" && -f "$SCRIPT_DIR/docker-compose.yml" ]]; then
    PROJECT="$SCRIPT_DIR"
    ROOT="$(dirname -- "$PROJECT")"
else
    ROOT="$SCRIPT_DIR"
    PROJECT="$ROOT/finance-tracker"
fi
REPO="${FINANCE_REPO:-https://github.com/LuuckA21/finance-tracker.git}"
STATE_FILE="$ROOT/.finance-tracker-last-deploy"
BACKUP_DIR_ROOT="${FINANCE_BACKUP_DIR:-$ROOT/finance-tracker-backups}"
BACKUP_KEEP="${FINANCE_BACKUP_KEEP:-10}"
HEALTH_TIMEOUT="${FINANCE_HEALTH_TIMEOUT:-300}"
MIGRATION_DIR=backend/src/main/resources/db/migration

MODE=deploy
REQUESTED_BRANCH=""
DEPLOY_IN_PROGRESS=0
ROLLBACK_AVAILABLE=0
PARTIAL_BACKUP=""

red()   { printf '\033[31m%s\033[0m\n' "$1" >&2; }
green() { printf '\033[32m%s\033[0m\n' "$1"; }
info()  { printf '\033[34m→\033[0m %s\n' "$1"; }

usage() {
    printf 'Usage: %s [BRANCH]\n' "${0##*/}"
    printf '       %s --rollback\n' "${0##*/}"
    printf '\nWithout BRANCH, the current branch is updated.\n'
}

cleanup() {
    if [[ -n "$PARTIAL_BACKUP" && -e "$PARTIAL_BACKUP" ]]; then
        rm -rf -- "$PARTIAL_BACKUP"
    fi
}
trap cleanup EXIT

on_error() {
    local exit_code=$?
    set +e
    if (( DEPLOY_IN_PROGRESS == 1 )); then
        echo >&2
        red "Deployment failed. The new containers did not pass verification."
        if (( ROLLBACK_AVAILABLE == 1 )); then
            red "The previous code has not been deleted. To restore it, run:"
            red "  $0 --rollback"
        fi
        docker compose ps >&2 2>/dev/null || true
        docker compose logs --tail=80 backend web >&2 2>/dev/null || true
    fi
    exit "$exit_code"
}
trap on_error ERR

if (( $# > 1 )); then
    usage >&2
    exit 2
fi

case "${1:-}" in
    --help|-h)
        usage
        exit 0
        ;;
    --rollback)
        MODE=rollback
        ;;
    --*)
        red "Unknown option: $1"
        usage >&2
        exit 2
        ;;
    *)
        REQUESTED_BRANCH="${1:-}"
        ;;
esac

if ! [[ "$HEALTH_TIMEOUT" =~ ^[1-9][0-9]*$ ]]; then
    red "FINANCE_HEALTH_TIMEOUT must be a positive number of seconds."
    exit 2
fi
if ! [[ "$BACKUP_KEEP" =~ ^[1-9][0-9]*$ ]]; then
    red "FINANCE_BACKUP_KEEP must be a positive number."
    exit 2
fi

command -v git >/dev/null 2>&1 || {
    red "git is required."
    exit 1
}

# cron and non-interactive shells carry a minimal PATH. Rootless Docker may
# have been installed below the user's home directory, so look there too.
if ! command -v docker >/dev/null 2>&1; then
    for dir in "$HOME/bin" /usr/local/bin /usr/bin /bin; do
        if [[ -x "$dir/docker" ]]; then
            PATH="$dir:$PATH"
            break
        fi
    done
fi

command -v docker >/dev/null 2>&1 || {
    red "docker is not installed, or is not on $(whoami)'s PATH."
    exit 1
}

# Rebuild the rootless Docker socket variables that interactive shell startup
# files normally provide.
ROOTLESS=0
if [[ -z "${DOCKER_HOST:-}" ]]; then
    RUNTIME_DIR="${XDG_RUNTIME_DIR:-/run/user/$(id -u)}"
    if [[ -S "$RUNTIME_DIR/docker.sock" ]]; then
        export DOCKER_HOST="unix://$RUNTIME_DIR/docker.sock"
        export XDG_RUNTIME_DIR="$RUNTIME_DIR"
        ROOTLESS=1
    fi
fi

if ! docker info >/dev/null 2>&1; then
    red "Cannot reach the Docker daemon as $(whoami)."
    if [[ -n "${DOCKER_HOST:-}" ]]; then
        red "DOCKER_HOST points at $DOCKER_HOST but the daemon is not answering."
    fi
    red "Check that Docker is running and that $(whoami) may use it."
    exit 1
fi

if ! docker compose version >/dev/null 2>&1; then
    red "Docker Compose v2 is not available for user $(whoami)."
    exit 1
fi

if (( ROOTLESS == 1 )) && command -v loginctl >/dev/null 2>&1; then
    if [[ "$(loginctl show-user "$(whoami)" -p Linger --value 2>/dev/null)" != yes ]]; then
        red "Linger is off for $(whoami): rootless containers may stop at logout."
        red "Enable it with: sudo loginctl enable-linger $(whoami)"
        echo
    fi
fi

compose() {
    docker compose "$@"
}

require_clean_worktree() {
    if ! git diff --quiet || ! git diff --cached --quiet; then
        red "There are uncommitted tracked changes in $PROJECT:"
        git status --short | grep -v '^??' >&2 || true
        red "Commit or restore them before deploying."
        exit 1
    fi
}

validate_branch() {
    local branch=$1
    if [[ -z "$branch" || "$branch" == -* ]] ||
       ! git check-ref-format --branch "$branch" >/dev/null 2>&1; then
        red "Invalid branch name: $branch"
        exit 2
    fi
}

state_value() {
    local key=$1
    awk -F= -v key="$key" '
        $1 == key {
            sub(/^[^=]*=/, "")
            print
            exit
        }
    ' "$STATE_FILE"
}

write_state() {
    local status=$1
    local previous_branch=$2
    local previous_commit=$3
    local deployed_branch=$4
    local deployed_commit=$5
    local backup_dir=$6
    local migration_changed=$7
    local state_tmp

    state_tmp="$(mktemp "$ROOT/.finance-tracker-last-deploy.XXXXXX")"
    {
        printf 'status=%s\n' "$status"
        printf 'previous_branch=%s\n' "$previous_branch"
        printf 'previous_commit=%s\n' "$previous_commit"
        printf 'deployed_branch=%s\n' "$deployed_branch"
        printf 'deployed_commit=%s\n' "$deployed_commit"
        printf 'backup_dir=%s\n' "$backup_dir"
        printf 'migration_changed=%s\n' "$migration_changed"
    } > "$state_tmp"
    mv -- "$state_tmp" "$STATE_FILE"
}

# Dumps the database (custom format, restorable with pg_restore) together with
# .env, which holds APP_ENCRYPTION_KEY. Sets BACKUP_DIR. Not meant for $(...):
# bash drops errexit inside command substitutions.
backup_database() {
    local final

    mkdir -p -- "$BACKUP_DIR_ROOT"
    compose up -d --wait --wait-timeout "$HEALTH_TIMEOUT" db

    PARTIAL_BACKUP="$(mktemp -d "$BACKUP_DIR_ROOT/.partial-XXXXXXXX")"
    compose exec -T db pg_dump -U finance -d finance --format=custom \
        > "$PARTIAL_BACKUP/finance.dump"
    # An unreadable dump is no backup: make pg_restore parse its table of contents.
    compose exec -T db pg_restore --list < "$PARTIAL_BACKUP/finance.dump" >/dev/null
    install -m 600 -- .env "$PARTIAL_BACKUP/.env"
    git rev-parse HEAD > "$PARTIAL_BACKUP/commit"
    (cd -- "$PARTIAL_BACKUP" && sha256sum finance.dump .env commit > SHA256SUMS)

    final="$BACKUP_DIR_ROOT/predeploy-$(date +%Y%m%d-%H%M%S-%N)"
    mv -T -- "$PARTIAL_BACKUP" "$final"
    PARTIAL_BACKUP=""

    # Keep only the newest backups made by this script.
    find "$BACKUP_DIR_ROOT" -mindepth 1 -maxdepth 1 -type d -name 'predeploy-*' -print0 |
        sort -rz | tail -zn +"$((BACKUP_KEEP + 1))" | xargs -0r rm -rf --

    BACKUP_DIR="$final"
}

retry_check() {
    local description=$1
    shift
    local attempt

    for (( attempt = 1; attempt <= 15; attempt++ )); do
        if "$@"; then
            return 0
        fi
        if (( attempt < 15 )); then
            sleep 2
        fi
    done

    red "$description did not respond after 15 attempts."
    return 1
}

# The backend is not published and its image has no HTTP client, so both
# checks run from the web container (busybox wget) over the compose network.
backend_is_ready() {
    local response
    response="$(
        compose exec -T web \
            wget -qO- http://backend:8080/actuator/health/readiness 2>/dev/null
    )" || return 1
    grep -q '"status"[[:space:]]*:[[:space:]]*"UP"' <<< "$response"
}

web_is_ready() {
    compose exec -T web wget -qO- http://127.0.0.1:8080/ >/dev/null 2>&1
}

verify_services() {
    info "Checking backend health"
    retry_check "Backend" backend_is_ready

    info "Checking web response"
    retry_check "Web" web_is_ready
}

rebuild_and_verify() {
    info "Rebuilding containers (timeout: ${HEALTH_TIMEOUT}s)"
    compose up -d --build --remove-orphans --wait --wait-timeout "$HEALTH_TIMEOUT"
    verify_services
}

rollback() {
    local status previous_branch previous_commit deployed_branch deployed_commit
    local backup_dir migration_changed current_commit

    if [[ ! -d "$PROJECT/.git" ]]; then
        red "No Finance Tracker git repository was found at $PROJECT."
        exit 1
    fi
    if [[ ! -f "$STATE_FILE" ]]; then
        red "No previous deployment state was found at $STATE_FILE."
        exit 1
    fi

    cd -- "$PROJECT"
    require_clean_worktree

    status="$(state_value status)"
    previous_branch="$(state_value previous_branch)"
    previous_commit="$(state_value previous_commit)"
    deployed_branch="$(state_value deployed_branch)"
    deployed_commit="$(state_value deployed_commit)"
    backup_dir="$(state_value backup_dir)"
    migration_changed="$(state_value migration_changed)"

    if [[ "$status" == rolled_back ]]; then
        red "The last deployment has already been rolled back."
        exit 1
    fi
    if [[ "$previous_commit" == "$deployed_commit" ]]; then
        red "The last deployment did not change the code (${deployed_commit:0:7}): nothing to roll back."
        exit 1
    fi
    if ! git cat-file -e "${previous_commit}^{commit}" 2>/dev/null; then
        red "The previous commit is no longer available locally: $previous_commit"
        exit 1
    fi

    current_commit="$(git rev-parse HEAD)"
    if [[ "$current_commit" != "$deployed_commit" ]]; then
        red "Rollback refused because the working copy is no longer at the deployed commit."
        red "Expected: $deployed_commit"
        red "Current:  $current_commit"
        exit 1
    fi

    if [[ "$migration_changed" == 1 ]]; then
        red "Automatic code rollback is disabled because this deploy changed Flyway migrations:"
        red "the old code may not work with the migrated database."
        red "The pre-deploy database dump is: $backup_dir/finance.dump"
        red "Restore it only after checking it on a disposable copy, for example:"
        red "  docker compose exec -T db pg_restore -U finance -d finance --clean --if-exists < $backup_dir/finance.dump"
        exit 1
    fi

    info "Rolling back from ${deployed_commit:0:7} to ${previous_commit:0:7}"
    if [[ -n "$previous_branch" ]] &&
       [[ "$(git rev-parse "refs/heads/$previous_branch" 2>/dev/null || true)" == "$previous_commit" ]]; then
        git switch "$previous_branch"
    else
        git switch --detach "$previous_commit"
        info "The repository is detached at the previous commit."
        info "A later explicit deploy, for example '$0 master', will return to a branch."
    fi

    DEPLOY_IN_PROGRESS=1
    rebuild_and_verify
    DEPLOY_IN_PROGRESS=0

    write_state rolled_back "$previous_branch" "$previous_commit" \
        "$deployed_branch" "$deployed_commit" "$backup_dir" "$migration_changed"

    info "Pruning dangling images"
    docker image prune -f >/dev/null
    green "Rollback completed: Finance Tracker is running ${previous_commit:0:7}."
    compose ps
}

if [[ "$MODE" == rollback ]]; then
    rollback
    exit 0
fi

FIRST_RUN=0

if [[ ! -d "$PROJECT" ]]; then
    info "No project at $PROJECT"
    info "Cloning $REPO"
    clone_args=()
    if [[ -n "$REQUESTED_BRANCH" ]]; then
        validate_branch "$REQUESTED_BRANCH"
        clone_args+=(--branch "$REQUESTED_BRANCH")
    fi
    git clone "${clone_args[@]}" "$REPO" "$PROJECT"
    FIRST_RUN=1
elif [[ ! -d "$PROJECT/.git" ]]; then
    red "$PROJECT exists but is not a git repository."
    red "Move it aside after preserving .env, then run this script again."
    exit 1
fi

cd -- "$PROJECT"

if [[ ! -f .env ]]; then
    cp -- .env.example .env
    echo
    red "Created .env from .env.example, but its secrets are empty."
    red "Set DB_PASSWORD, APP_ENCRYPTION_KEY (openssl rand -base64 32), WEB_BIND and WEB_PORT, then rerun."
    red "Configuration file: $PROJECT/.env"
    exit 1
fi

PREVIOUS_BRANCH=""
PREVIOUS_COMMIT=""
TARGET_BRANCH=""
DEPLOYED_COMMIT=""
BACKUP_DIR=""
MIGRATION_CHANGED=0
MIGRATION_FILES=""

if (( FIRST_RUN == 1 )); then
    TARGET_BRANCH="$(git symbolic-ref --quiet --short HEAD)"
    DEPLOYED_COMMIT="$(git rev-parse HEAD)"
    info "Cloned $TARGET_BRANCH at ${DEPLOYED_COMMIT:0:7}"
else
    require_clean_worktree
    PREVIOUS_BRANCH="$(git symbolic-ref --quiet --short HEAD || true)"
    PREVIOUS_COMMIT="$(git rev-parse HEAD)"

    if [[ -n "$REQUESTED_BRANCH" ]]; then
        TARGET_BRANCH="$REQUESTED_BRANCH"
    else
        TARGET_BRANCH="$PREVIOUS_BRANCH"
    fi
    if [[ -z "$TARGET_BRANCH" ]]; then
        red "The repository is detached. Specify the branch explicitly, for example: $0 master"
        exit 1
    fi
    validate_branch "$TARGET_BRANCH"

    info "Fetching origin and validating branch $TARGET_BRANCH"
    git fetch --prune origin
    if ! git show-ref --verify --quiet "refs/remotes/origin/$TARGET_BRANCH"; then
        red "Remote branch not found: origin/$TARGET_BRANCH"
        exit 1
    fi

    info "Creating the mandatory pre-deploy database backup"
    backup_database
    info "Backup completed: $BACKUP_DIR"

    if git show-ref --verify --quiet "refs/heads/$TARGET_BRANCH"; then
        git switch "$TARGET_BRANCH"
    else
        git switch --track -c "$TARGET_BRANCH" "origin/$TARGET_BRANCH"
    fi

    info "Fast-forwarding $TARGET_BRANCH"
    git merge --ff-only "origin/$TARGET_BRANCH"
    DEPLOYED_COMMIT="$(git rev-parse HEAD)"

    if [[ "$PREVIOUS_COMMIT" == "$DEPLOYED_COMMIT" ]]; then
        info "Source is already current (${DEPLOYED_COMMIT:0:7}); rebuilding for configuration changes"
    else
        info "Updated from ${PREVIOUS_COMMIT:0:7} to ${DEPLOYED_COMMIT:0:7}"
        git --no-pager log --max-count=10 --oneline \
            "$PREVIOUS_COMMIT..$DEPLOYED_COMMIT"

        MIGRATION_FILES="$(
            git diff --name-only "$PREVIOUS_COMMIT" "$DEPLOYED_COMMIT" -- \
                "$MIGRATION_DIR" | sed -n '/\.sql$/p'
        )"
        if [[ -n "$MIGRATION_FILES" ]]; then
            MIGRATION_CHANGED=1
            echo
            red "Database migrations changed in this deployment:"
            printf '%s\n' "$MIGRATION_FILES" | sed 's/^/    /' >&2
            red "The automatic code rollback will remain disabled for this deployment."
            echo
        fi
    fi

    write_state pending "$PREVIOUS_BRANCH" "$PREVIOUS_COMMIT" \
        "$TARGET_BRANCH" "$DEPLOYED_COMMIT" "$BACKUP_DIR" "$MIGRATION_CHANGED"
    ROLLBACK_AVAILABLE=1
fi

DEPLOY_IN_PROGRESS=1
rebuild_and_verify
DEPLOY_IN_PROGRESS=0

if (( FIRST_RUN == 0 )); then
    write_state successful "$PREVIOUS_BRANCH" "$PREVIOUS_COMMIT" \
        "$TARGET_BRANCH" "$DEPLOYED_COMMIT" "$BACKUP_DIR" "$MIGRATION_CHANGED"
fi

info "Pruning dangling images"
docker image prune -f >/dev/null

green "Deployment completed in $PROJECT (${DEPLOYED_COMMIT:0:7})"
echo
compose ps

if (( FIRST_RUN == 1 )); then
    echo
    info "First run: if APP_ADMIN_PASSWORD was empty, read the temporary admin password with:"
    info "  docker compose logs backend | grep -A3 'temporary password'"
fi

echo
info "Backend logs: docker compose logs -f backend"
