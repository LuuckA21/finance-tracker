#!/usr/bin/env bash
#
# Checks that a release tag matches the versions in the code and prints its notes.
#
# Usage: scripts/release-notes.sh v1.2.3 > notes.md
#
# Fails when the tag is not vX.Y.Z, when backend/pom.xml or frontend/package.json carry another
# version, or when CHANGELOG.md has no section "## [X.Y.Z]" (its text is the release notes).
# Run by .github/workflows/release.yml; also handy before pushing a tag.

set -Eeuo pipefail

PROJECT="$(CDPATH='' cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
TAG="${1:-}"

fail() { printf '%s\n' "$1" >&2; exit 1; }

[[ "$TAG" =~ ^v([0-9]+\.[0-9]+\.[0-9]+)$ ]] || fail "Not a release tag (vX.Y.Z): '$TAG'"
VERSION="${BASH_REMATCH[1]}"

# The project's own version: the first <version> after its <artifactId> (the parent's comes before)
POM_VERSION="$(sed -n '/<artifactId>finance-tracker<\/artifactId>/{n;s/.*<version>\(.*\)<\/version>.*/\1/p;q}' \
    "$PROJECT/backend/pom.xml")"
NPM_VERSION="$(sed -n 's/^  "version": "\(.*\)",$/\1/p' "$PROJECT/frontend/package.json" | head -n 1)"
[[ "$POM_VERSION" == "$VERSION" ]] || fail "backend/pom.xml is at '$POM_VERSION', the tag at $VERSION"
[[ "$NPM_VERSION" == "$VERSION" ]] || fail "frontend/package.json is at '$NPM_VERSION', the tag at $VERSION"

# The lines between "## [VERSION]" and the next "## [", without the blank ones around them
NOTES="$(awk -v heading="## [$VERSION]" '
    index($0, heading) == 1 { found = 1; next }
    found && /^## \[/ { exit }
    found { print }
' "$PROJECT/CHANGELOG.md" | sed -e '/./,$!d')"
[[ -n "${NOTES//[[:space:]]/}" ]] || fail "CHANGELOG.md has no notes under '## [$VERSION]'"
printf '%s\n' "$NOTES"
