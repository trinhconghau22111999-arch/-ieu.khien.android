#!/bin/sh
GRADLE_WRAPPER_JAR="gradle/wrapper/gradle-wrapper.jar"
GRADLE_WRAPPER_PROPERTIES="gradle/wrapper/gradle-wrapper.properties"
exec java -jar "$GRADLE_WRAPPER_JAR" "$@"
