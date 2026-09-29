#!/bin/sh
# Prints, for $GITHUB_OUTPUT, the exact JDK of the backend runtime image
# (FROM eclipse-temurin:<version>_<build>-jre), so CI tests on the Java that runs production:
#   version=...  for actions/setup-java (Temurin semver: a fourth number, as in the out-of-band
#                release 25.0.4.1_1, moves into the build as 100 x fourth + build: 25.0.4+101)
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
if [ "$parts" -eq 3 ]; then
    echo "version=${version%.*}+$((${version##*.} * 100 + build))"
else
    echo "version=$version+$build"
fi
echo "runtime=$version+$build"
