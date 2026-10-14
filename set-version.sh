#!/usr/bin/env bash
# Set the plugin version.
#
#   ./set-version.sh 1.2.3
#
# gradle.properties is the single source: Gradle stamps it into plugin.xml and the ZIP name,
# and the Makefile derives the release tag and updatePlugins.xml from it.
set -euo pipefail

cd "$(dirname "$0")"
if [[ ! "${1:-}" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
  echo "usage: ./set-version.sh <major.minor.patch>" >&2
  exit 1
fi

old=$(sed -nE "s/^version[[:space:]]*=[[:space:]]*//p" gradle.properties)
sed -i '' -E "s/^version[[:space:]]*=.*/version = $1/" gradle.properties
echo "Version $old -> $1"
