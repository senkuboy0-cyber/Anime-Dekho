#!/bin/sh
APP_HOME=$(cd "$(dirname "$0")" && pwd)
JAVACMD="${JAVA_HOME:+$JAVA_HOME/bin/}java"
exec "$JAVACMD" -Xmx512m -Xms64m "-Dorg.gradle.appname=gradlew" -classpath "$APP_HOME/gradle/wrapper/gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain "$@"
