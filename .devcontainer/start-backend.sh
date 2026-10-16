#!/bin/bash
# Start the SafeTap backend detached from the devcontainer lifecycle command,
# so it keeps running after postStartCommand finishes.
# Logs to /tmp/uvicorn.log inside the codespace.
set -u
cd "$(dirname "$0")/../backend"

export MOCK_GATEWAYS=1

# Stop any previous instance from an earlier start/stop cycle.
pkill -f "uvicorn app.main:app" 2>/dev/null || true
sleep 1

# python3 -m uvicorn avoids PATH issues with the uvicorn console script.
# </dev/null + nohup + & detaches it fully.
nohup python3 -m uvicorn app.main:app --host 0.0.0.0 --port 8000 \
  > /tmp/uvicorn.log 2>&1 < /dev/null &
echo "SafeTap backend start requested (log: /tmp/uvicorn.log)"
