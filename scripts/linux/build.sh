#!/usr/bin/env bash
set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"

OUTPUT_DIR="$ROOT_DIR/build/classes"
LIBS="$ROOT_DIR/libs/UhfRfidAPI.jar:$ROOT_DIR/libs/RXTXcomm.jar"

if [ -x "$ROOT_DIR/jdk/bin/javac" ]; then
    JAVAC="$ROOT_DIR/jdk/bin/javac"
elif [ -n "$JAVA_HOME" ] && [ -x "$JAVA_HOME/bin/javac" ]; then
    JAVAC="$JAVA_HOME/bin/javac"
else
    JAVAC="javac"
fi

echo "javac   : $JAVAC"
echo "SDK jars: $LIBS"

SOURCES=$(find "$ROOT_DIR/src" -name "*.java")
"$JAVAC" -encoding UTF-8 -d "$OUTPUT_DIR" -cp "$LIBS" $SOURCES

echo "Built successfully into $OUTPUT_DIR"
