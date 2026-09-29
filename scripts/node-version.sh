#!/bin/sh
# Prints "version=X.Y.Z" for $GITHUB_OUTPUT: the exact Node version of the frontend build stage
# (FROM node:X.Y.Z-alpine AS build), so CI tests on the Node that builds production.
set -eu
dockerfile=${1:?usage: node-version.sh path/to/Dockerfile}
version=$(sed -n 's/^FROM node:\([0-9][0-9]*\.[0-9][0-9]*\.[0-9][0-9]*\)-alpine AS build$/\1/p' "$dockerfile")
if [ -z "$version" ]; then
    echo "No exact Node version in $dockerfile: expected 'FROM node:X.Y.Z-alpine AS build'" >&2
    exit 1
fi
echo "version=$version"
