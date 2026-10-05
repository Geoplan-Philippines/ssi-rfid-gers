#!/usr/bin/env bash
set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"

LOG_DIR="$ROOT_DIR/logs"
PID_FILE="$LOG_DIR/agent.pid"
LOG_FILE="$LOG_DIR/agent.log"

CLASSES="$ROOT_DIR/build/classes"
LIBS="$ROOT_DIR/libs/UhfRfidAPI.jar:$ROOT_DIR/libs/RXTXcomm.jar"
CLASSPATH="$CLASSES:$LIBS"

mkdir -p "$LOG_DIR"

# Check if already running via PID file
if [ -f "$PID_FILE" ]; then
    PID=$(cat "$PID_FILE")
    if ps -p "$PID" > /dev/null 2>&1; then
        echo "Agent is already running with PID $PID."
        exit 0
    else
        rm -f "$PID_FILE"
    fi
fi

# Check if port 8443 is already in use
PORT_PID=$(lsof -ti :8443 2>/dev/null || true)
if [ -n "$PORT_PID" ]; then
    echo "Warning: Port 8443 is already in use by PID $PORT_PID."
    echo "Please stop it before starting: kill -9 $PORT_PID"
    exit 1
fi

# Build if build/classes missing
if [ ! -f "$CLASSES/com/geoplan/rfid/agent/AgentMain.class" ]; then
    echo "build/classes missing. Compiling first..."
    "$SCRIPT_DIR/build.sh"
fi

if [ -x "$ROOT_DIR/jdk/bin/java" ]; then
    JAVA="$ROOT_DIR/jdk/bin/java"
elif [ -n "$JAVA_HOME" ] && [ -x "$JAVA_HOME/bin/java" ]; then
    JAVA="$JAVA_HOME/bin/java"
else
    JAVA="java"
fi

echo "Starting SSI RMK reader agent in background..."
cd "$ROOT_DIR"
nohup "$JAVA" -Dfile.encoding=UTF-8 -cp "$CLASSPATH" com.geoplan.rfid.agent.AgentMain > "$LOG_FILE" 2>&1 &
AGENT_PID=$!
echo "$AGENT_PID" > "$PID_FILE"

sleep 2

if ps -p "$AGENT_PID" > /dev/null 2>&1; then
    echo "Agent started successfully in background (PID: $AGENT_PID)."
    echo "Logs: $LOG_FILE"
else
    echo "Agent failed to start. Last log lines:"
    tail -n 25 "$LOG_FILE"
    rm -f "$PID_FILE"
    exit 1
fi
