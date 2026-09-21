#!/usr/bin/env bash
set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"

CLASSES="$ROOT_DIR/build/classes"
LIBS="$ROOT_DIR/libs/UhfRfidAPI.jar:$ROOT_DIR/libs/RXTXcomm.jar"
CLASSPATH="$CLASSES:$LIBS"

if [ ! -f "$CLASSES/com/geoplan/rfid/agent/AgentMain.class" ]; then
    echo "build/classes missing. Building first..."
    "$SCRIPT_DIR/build.sh"
fi

if [ -x "$ROOT_DIR/jdk/bin/java" ]; then
    JAVA="$ROOT_DIR/jdk/bin/java"
elif [ -n "$JAVA_HOME" ] && [ -x "$JAVA_HOME/bin/java" ]; then
    JAVA="$JAVA_HOME/bin/java"
else
    JAVA="java"
fi

cd "$ROOT_DIR"
exec "$JAVA" -Dfile.encoding=UTF-8 -cp "$CLASSPATH" com.geoplan.rfid.agent.AgentMain
