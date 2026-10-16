#!/bin/bash
# Publicly expose the SafeTap backend via a Cloudflare quick tunnel.
# Usage: ./host-public.sh
# Prints the public URL at the end. The backend runs with MOCK_GATEWAYS=1,
# so no real SMS/voice/WhatsApp goes out.
set -e
cd "$(dirname "$0")/backend"

# Kill any previous instances
pkill -f "uvicorn app.main:app" 2>/dev/null || true
pkill -f "cloudflared tunnel" 2>/dev/null || true
sleep 2

# Start the backend locally first
MOCK_GATEWAYS=1 .venv/bin/uvicorn app.main:app --host 127.0.0.1 --port 8000 --log-level warning &
echo $! > /tmp/safetap-backend.pid
sleep 4

# Start the tunnel and capture the public URL
cloudflared tunnel --url http://127.0.0.1:8000 > /tmp/safetap-tunnel.log 2>&1 &
echo $! > /tmp/safetap-tunnel.pid

URL=""
for i in $(seq 1 30); do
  URL=$(grep -o "https://[a-z0-9-]*\.trycloudflare\.com" /tmp/safetap-tunnel.log | head -1)
  if [ -n "$URL" ]; then break; fi
  sleep 2
done

if [ -z "$URL" ]; then
  echo "Tunnel failed to start. Log:"
  cat /tmp/safetap-tunnel.log
  exit 1
fi

# Restart backend with the public base URL so tracking links are correct
kill $(cat /tmp/safetap-backend.pid) 2>/dev/null || true
sleep 2
MOCK_GATEWAYS=1 SAFETAP_BASE_URL="$URL" .venv/bin/uvicorn app.main:app --host 127.0.0.1 --port 8000 --log-level warning &
echo $! > /tmp/safetap-backend.pid
sleep 3

echo "PUBLIC_URL=$URL"
curl -s "$URL/api/health"; echo
