"""In-memory WebSocket hub for live location/status streaming to tracking pages.

One hub per backend process. For the MVP (single process) this is all we need;
a Redis pub/sub adapter can replace it when we scale horizontally.
"""

import asyncio
import json
import logging

log = logging.getLogger("safetap.hub")


class Hub:
    def __init__(self):
        self._rooms: dict[str, set] = {}
        self._lock = asyncio.Lock()

    async def join(self, token: str, ws):
        async with self._lock:
            self._rooms.setdefault(token, set()).add(ws)

    async def leave(self, token: str, ws):
        async with self._lock:
            room = self._rooms.get(token)
            if room:
                room.discard(ws)
                if not room:
                    del self._rooms[token]

    async def broadcast(self, token: str, payload: dict):
        message = json.dumps(payload)
        async with self._lock:
            targets = list(self._rooms.get(token, ()))
        for ws in targets:
            try:
                await ws.send_text(message)
            except Exception:
                await self.leave(token, ws)


hub = Hub()
