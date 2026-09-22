#!/bin/sh
#
# Gradle wrapper launcher (POSIX).
#
set -e

APP_HOME=$(cd "$(dirname "$0")" >/dev/null && pwd)
WRAPPER_JAR="$APP_HOME/gradle/wrapper/gradle-wrapper.jar"

if [ ! -f "$WRAPPER_JAR" ]; then
    echo "gradle-wrapper.jar is missing from gradle/wrapper/." >&2
    echo "" >&2
    echo "Fetch it once with either:" >&2
    echo "  gradle wrapper --gradle-version 8.12" >&2
    echo "  curl -Lo gradle/wrapper/gradle-wrapper.jar \\" >&2
    echo "    https://raw.githubusercontent.com/gradle/gradle/v8.12.0/gradle/wrapper/gradle-wrapper.jar" >&2
    exit 1
fi

if [ -n "$JAVA_HOME" ] && [ -x "$JAVA_HOME/bin/java" ]; then
    JAVACMD="$JAVA_HOME/bin/java"
else
    JAVACMD="java"
fi

exec "$JAVACMD" $JAVA_OPTS -classpath "$WRAPPER_JAR" org.gradle.wrapper.GradleWrapperMain "$@"
