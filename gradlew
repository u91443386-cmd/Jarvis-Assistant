#!/usr/bin/env sh
APP_BASE_NAME=${0##*/}
APP_HOME=$(dirname "$0")
[ -n "$CLASSPATH" ] || CLASSPATH=$APP_HOME/gradle/wrapper/gradle-wrapper.jar
export CLASSPATH
exec java org.gradle.wrapper.GradleWrapperMain "$@"
