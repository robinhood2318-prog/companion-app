#!/bin/sh
# Standard Gradle Wrapper Script
set -e
APP_HOME="$(cd -P -- "$(dirname -- "$0")" && pwd -P)"
CLASSPATH="$APP_HOME/gradle/wrapper/gradle-wrapper.jar"

# Determine Java command
if [ -n "$JAVA_HOME" ] ; then
    JAVACMD="$JAVA_HOME/bin/java"
else
    JAVACMD="java"
fi

exec "$JAVACMD" -jar "$CLASSPATH" "$@"
