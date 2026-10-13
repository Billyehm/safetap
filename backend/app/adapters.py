"""Gateway adapters: SMS (Termii), voice calls, WhatsApp.

Each adapter has a real implementation and a MOCK implementation. With
MOCK_GATEWAYS=1 (the default) every send is logged and reported as delivered,
so the full alert flow can be tested without credentials or spend. Set
MOCK_GATEWAYS=0 and provide the env keys to go live against Termii / your
voice and WhatsApp providers.

All adapters expose the same shape:
    await adapter.send(to, message, *, tracking_url="") -> dict(status, provider_id, detail)
status is one of: sent | ringing | delivered | failed
"""

import logging

import httpx

from . import config

log = logging.getLogger("safetap.adapters")


class MockAdapter:
    kind = "mock"

    async def send(self, to: str, message: str, *, tracking_url: str = "") -> dict:
        body = message + (f"\nTrack: {tracking_url}" if tracking_url else "")
        log.info("[MOCK %s] to=%s body=%r", self.kind, to, body[:160])
        return {"status": "delivered", "provider_id": f"mock-{self.kind}",
                "detail": "mock gateway, no real message sent"}


class TermiiSmsAdapter:
    """Termii SMS: https://v3.api.termii.com/api/sms/send"""
    kind = "sms"

    async def send(self, to: str, message: str, *, tracking_url: str = "") -> dict:
        body = message + (f"\nTrack: {tracking_url}" if tracking_url else "")
        payload = {
            "to": to.lstrip("+"),  # Termii expects the number without +
            "from": config.TERMII_SENDER_ID,
            "sms": body,
            "type": "plain",
            "channel": "generic",
            "api_key": config.TERMII_API_KEY,
        }
        try:
            async with httpx.AsyncClient(timeout=20) as client:
                r = await client.post("https://v3.api.termii.com/api/sms/send", json=payload)
                data = r.json() if r.headers.get("content-type", "").startswith("application/json") else {}
                ok = r.status_code == 200 and data.get("message_id")
                return {
                    "status": "sent" if ok else "failed",
                    "provider_id": str(data.get("message_id", "")),
                    "detail": f"termii http={r.status_code}",
                }
        except Exception as exc:  # network down, bad key, etc.
            return {"status": "failed", "provider_id": "", "detail": f"sms error: {exc}"}


class VoiceAdapter:
    """Automated voice call with text-to-speech.

    Configure VOICE_API_URL / VOICE_API_KEY for your provider (Termii voice or
    equivalent with Nigerian termination). The provider is expected to accept
    JSON {to, text, api_key} and return a call id.
    """
    kind = "voice"

    async def send(self, to: str, message: str, *, tracking_url: str = "") -> dict:
        if not config.VOICE_API_URL:
            return {"status": "failed", "provider_id": "",
                    "detail": "VOICE_API_URL not configured"}
        try:
            async with httpx.AsyncClient(timeout=20) as client:
                r = await client.post(config.VOICE_API_URL, json={
                    "to": to, "text": message, "api_key": config.VOICE_API_KEY,
                })
                return {"status": "ringing" if r.status_code == 200 else "failed",
                        "provider_id": "", "detail": f"voice http={r.status_code}"}
        except Exception as exc:
            return {"status": "failed", "provider_id": "", "detail": f"voice error: {exc}"}


class WhatsAppAdapter:
    """WhatsApp template/message send. Configure WHATSAPP_API_URL / WHATSAPP_API_KEY."""
    kind = "whatsapp"

    async def send(self, to: str, message: str, *, tracking_url: str = "") -> dict:
        if not config.WHATSAPP_API_URL:
            return {"status": "failed", "provider_id": "",
                    "detail": "WHATSAPP_API_URL not configured"}
        body = message + (f"\n{tracking_url}" if tracking_url else "")
        try:
            async with httpx.AsyncClient(timeout=20) as client:
                r = await client.post(config.WHATSAPP_API_URL, json={
                    "to": to, "text": body, "api_key": config.WHATSAPP_API_KEY,
                })
                return {"status": "sent" if r.status_code == 200 else "failed",
                        "provider_id": "", "detail": f"whatsapp http={r.status_code}"}
        except Exception as exc:
            return {"status": "failed", "provider_id": "", "detail": f"whatsapp error: {exc}"}


def get_adapters() -> dict:
    if config.MOCK_GATEWAYS:
        sms = MockAdapter(); sms.kind = "sms"
        voice = MockAdapter(); voice.kind = "voice"
        wa = MockAdapter(); wa.kind = "whatsapp"
        return {"sms": sms, "voice": voice, "whatsapp": wa}
    return {"sms": TermiiSmsAdapter(), "voice": VoiceAdapter(), "whatsapp": WhatsAppAdapter()}
