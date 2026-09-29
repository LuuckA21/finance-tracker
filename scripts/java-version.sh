#!/bin/sh
# Prints, for $GITHUB_OUTPUT, the exact JDK of the backend runtime image
# (FROM eclipse-temurin:<version>_<build>-jre), so CI tests on the Java that runs production:
#   version=...  for actions/setup-java, the Temurin semver matched exactly: a fourth number, as in
#                the out-of-band release 25.0.4.1_1, moves into the build as 100 x fourth + build,
#                and LTS releases (17, 21, 25, then every fourth) end with .0.LTS: 25.0.4+101.0.LTS
#   runtime=...  what `java -version` reports as its build (25.0.4.1+1), to check the JDK installed
set -eu
dockerfile=${1:?usage: java-version.sh path/to/Dockerfile}
tag=$(sed -n 's/^FROM eclipse-temurin:\([0-9][0-9.]*_[0-9][0-9]*\)-jre$/\1/p' "$dockerfile")
version=${tag%_*}
build=${tag#*_}
case $version in
    *[!0-9.]* | *..* | .* | *.) parts=0 ;;
    *) parts=$(echo "$version" | tr -cd . | wc -c) ;;
esac
if [ -z "$tag" ] || [ "$parts" -lt 2 ] || [ "$parts" -gt 3 ]; then
    echo "No exact JDK version in $dockerfile: expected 'FROM eclipse-temurin:X.Y.Z_B-jre'" >&2
    exit 1
fi
build_tag=$(sed -n 's/^FROM eclipse-temurin:\(.*\)-jdk AS build$/\1/p' "$dockerfile")
if [ "$build_tag" != "$tag" ]; then
    echo "The build stage of $dockerfile must use the runtime's JDK: 'FROM eclipse-temurin:${tag}-jdk AS build'" >&2
    exit 1
fi
major=${version%%.*}
lts=
if [ "$major" -ge 17 ] && [ $(((major - 17) % 4)) -eq 0 ]; then
    lts=.0.LTS
fi
if [ "$parts" -eq 3 ]; then
    echo "version=${version%.*}+$((${version##*.} * 100 + build))$lts"
else
    echo "version=$version+$build$lts"
fi
echo "runtime=$version+$build"
