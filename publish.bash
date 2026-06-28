#!/bin/bash

set -e

if [ $(git status --porcelain=1 | wc -l) -ne 0 ]; then
  echo "There are local modifications. Resolve before publishing."
  exit 1
fi

if grep SNAPSHOT version.gradle.kts; then
  echo "Set a non-snapshot version, then run this script."
  exit 2
fi

VERSION=$(grep -oP '(?<=")[^"]+(?=")' version.gradle.kts)

./gradlew clean build publishAllPublicationsToMavenCentralRepository

git tag -a "v$VERSION" -m "Release v$VERSION"
git push --follow-tags

echo "Publish complete. Change version back to snapshot."
