#!/usr/bin/env bash
set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

OUTPUT_DIR="$ROOT_DIR/build/classes"
LIBS="$ROOT_DIR/libs/UhfRfidAPI.jar:$ROOT_DIR/libs/RXTXcomm.jar"

mkdir -p "$OUTPUT_DIR"

echo "javac   : $(which javac)"
echo "SDK jars: $LIBS"

SOURCES=$(find "$ROOT_DIR/src" -name "*.java")
javac -encoding UTF-8 -d "$OUTPUT_DIR" -cp "$LIBS" $SOURCES

echo "Built successfully into $OUTPUT_DIR"
