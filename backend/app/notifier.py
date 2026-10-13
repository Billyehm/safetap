"""Alert fan-out: contact every emergency contact through SMS, voice and
WhatsApp in parallel, record delivery receipts, and move the alert state
machine forward: TRIGGERED -> CONTACTS_NOTIFIED."""

import asyncio
import datetime
import logging

from . import config
from .adapters import get_adapters
from .hub import hub
from .models import Alert, Contact, Notification, SessionLocal, User

log = logging.getLogger("safetap.notifier")


def _sos_text(user_name: str) -> str:
    return (f"{user_name} has triggered an emergency alert. They may need help right now. "
            f"Tap the link in this message for their live location and medical details.")


def _voice_text(user_name: str) -> str:
    return (f"{user_name} has triggered an emergency alert. "
            f"Please check the text message we sent you for their live location.")


async def _send_channel(alert: Alert, contact: Contact, channel: str,
                        adapters: dict, tracking_url: str, user_name: str):
    adapter = adapters[channel]
    message = _voice_text(user_name) if channel == "voice" else _sos_text(user_name)
    try:
        result = await adapter.send(contact.phone, message, tracking_url=tracking_url)
    except Exception as exc:
        result = {"status": "failed", "provider_id": "", "detail": f"adapter crash: {exc}"}
    return contact, channel, result


async def fan_out(alert_id: str):
    """Alert every contact on every enabled channel. Runs as a background task."""
    db = SessionLocal()
    try:
        alert = db.get(Alert, alert_id)
        if not alert:
            return
        user = db.get(User, alert.user_id)
        contacts = (db.query(Contact)
                      .filter(Contact.user_id == alert.user_id)
                      .order_by(Contact.priority).all())
        tracking_url = f"{config.BASE_URL}/t/{alert.tracking_token}"
        adapters = get_adapters()

        jobs = []
        for contact in contacts:
            if contact.sms_enabled:
                jobs.append(_send_channel(alert, contact, "sms", adapters, tracking_url, user.name))
            if contact.voice_enabled:
                jobs.append(_send_channel(alert, contact, "voice", adapters, tracking_url, user.name))
            if contact.whatsapp_enabled:
                jobs.append(_send_channel(alert, contact, "whatsapp", adapters, tracking_url, user.name))

        results = await asyncio.gather(*jobs)
        for contact, channel, result in results:
            detail = f"{result.get('detail', '')[:160]} pid={result.get('provider_id', '')}"
            db.add(Notification(alert_id=alert.id, contact_id=contact.id,
                                channel=channel, status=result["status"],
                                detail=detail))
        alert.status = "CONTACTS_NOTIFIED"
        alert.notified_at = datetime.datetime.utcnow()
        db.commit()
        token = alert.tracking_token  # capture before the session closes
        log.info("alert %s: notified %d contacts over %d channels",
                 alert.id, len(contacts), len(results))
    finally:
        db.close()
    await hub.broadcast(token, {"type": "status", "status": "CONTACTS_NOTIFIED"})


async def broadcast_false_alarm(alert_id: str):
    """Tell every contact it was a mistake, flip the tracking page to calm green."""
    db = SessionLocal()
    try:
        alert = db.get(Alert, alert_id)
        user = db.get(User, alert.user_id)
        contacts = db.query(Contact).filter(Contact.user_id == alert.user_id).all()
        adapters = get_adapters()
        text = f"FALSE ALARM from SafeTap: {user.name} is fine. It was a mistake. No need to worry."
        jobs = [adapters["sms"].send(c.phone, text) for c in contacts]
        results = await asyncio.gather(*jobs)
        for contact, result in zip(contacts, results):
            db.add(Notification(alert_id=alert.id, contact_id=contact.id, channel="sms",
                                status=result["status"], detail="false alarm notice"))
        db.commit()
        token = alert.tracking_token  # capture before the session closes
    finally:
        db.close()
    await hub.broadcast(token, {"type": "status", "status": "FALSE_ALARM"})
