#!/usr/bin/env bash
set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"

PID_FILE="$ROOT_DIR/logs/agent.pid"
LOG_FILE="$ROOT_DIR/logs/agent.log"

if [ -f "$PID_FILE" ]; then
    PID=$(cat "$PID_FILE")
    if ps -p "$PID" > /dev/null 2>&1; then
        echo "Agent is RUNNING (PID: $PID)"
        echo "Querying health endpoint..."
        curl -k -s --connect-timeout 2 https://localhost:8443/health || echo "Could not reach health endpoint"
        echo ""
        echo "Recent log entries:"
        tail -n 10 "$LOG_FILE" 2>/dev/null || true
        exit 0
    fi
fi

# Fallback: check port 8443
PORT_PID=$(lsof -ti :8443 2>/dev/null || true)
if [ -n "$PORT_PID" ]; then
    echo "Agent is RUNNING on port 8443 (PID: $PORT_PID)"
    curl -k -s --connect-timeout 2 https://localhost:8443/health || echo "Could not reach health endpoint"
    echo ""
    exit 0
fi

echo "Agent is STOPPED."
exit 1
