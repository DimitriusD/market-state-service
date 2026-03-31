#!/bin/sh
DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
JAVA_CMD="java"
if [ -n "$JAVA_HOME" ]; then
  JAVA_CMD="$JAVA_HOME/bin/java"
fi
exec "$JAVA_CMD" -Dorg.gradle.appname=gradlew -jar "$DIR/gradle/wrapper/gradle-wrapper.jar" "$@"
