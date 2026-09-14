#!/bin/sh
set -eu

MAVEN_VERSION=3.9.9
BASE_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
MAVEN_HOME="$BASE_DIR/.codex/maven/apache-maven-$MAVEN_VERSION"

if [ ! -x "$MAVEN_HOME/bin/mvn" ]; then
  mkdir -p "$BASE_DIR/.codex/maven"
  ARCHIVE="$BASE_DIR/.codex/maven/apache-maven-$MAVEN_VERSION-bin.tar.gz"
  if [ ! -f "$ARCHIVE" ]; then
    curl -fL "https://archive.apache.org/dist/maven/maven-3/$MAVEN_VERSION/binaries/apache-maven-$MAVEN_VERSION-bin.tar.gz" -o "$ARCHIVE"
  fi
  tar -xzf "$ARCHIVE" -C "$BASE_DIR/.codex/maven"
fi

exec "$MAVEN_HOME/bin/mvn" "$@"
