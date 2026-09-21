#!/usr/bin/env bash
set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"

PID_FILE="$ROOT_DIR/logs/agent.pid"

find_pids() {
    local pids=""
    if [ -f "$PID_FILE" ]; then
        local stored_pid
        stored_pid="$(cat "$PID_FILE")"
        if [ -n "$stored_pid" ] && ps -p "$stored_pid" > /dev/null 2>&1; then
            pids="$stored_pid"
        fi
    fi
    if [ -z "$pids" ]; then
        pids="$(lsof -ti :8443 2>/dev/null || true)"
    fi
    echo "$pids"
}

PIDS=$(find_pids)

if [ -z "$PIDS" ]; then
    echo "No running agent found."
    rm -f "$PID_FILE"
    exit 0
fi

for PID in $PIDS; do
    echo "Stopping agent process (PID: $PID)..."
    kill -15 "$PID" 2>/dev/null || true

    # Wait up to 5 seconds for graceful shutdown
    COUNT=0
    while ps -p "$PID" > /dev/null 2>&1 && [ $COUNT -lt 5 ]; do
        sleep 1
        COUNT=$((COUNT + 1))
    done

    if ps -p "$PID" > /dev/null 2>&1; then
        echo "Process $PID did not stop gracefully, sending SIGKILL..."
        kill -9 "$PID" 2>/dev/null || true
    fi
done

rm -f "$PID_FILE"
echo "Agent stopped successfully."
