"""SafeTap backend: FastAPI + SQLite (Postgres-ready), alert state machine,
WebSocket live-location streaming, check-in timers, and the no-install
recipient tracking page.

Alert state machine:
    TRIGGERED -> CONTACTS_NOTIFIED -> ACKNOWLEDGED -> RESOLVED | FALSE_ALARM
Every transition is timestamped and visible to the user.
"""

import asyncio
import datetime
import logging
import os

from fastapi import (BackgroundTasks, Depends, FastAPI, Header, HTTPException,
                     Request, WebSocket, WebSocketDisconnect)
from fastapi.responses import HTMLResponse, JSONResponse
from fastapi.templating import Jinja2Templates
from pydantic import BaseModel

from . import config
from .hub import hub
from .models import (Acknowledgement, Alert, CheckIn, Contact, LocationPing,
                     Notification, SessionLocal, Token, User, init_db)
from .notifier import broadcast_false_alarm, fan_out
from .security import (decrypt_medical, encrypt_medical, google_maps_link,
                       hash_pin, make_token, make_tracking_token,
                       normalize_ng_phone, verify_pin)

logging.basicConfig(level=logging.INFO)
log = logging.getLogger("safetap")

app = FastAPI(title="SafeTap API", version="0.1.0")
templates = Jinja2Templates(directory=os.path.join(os.path.dirname(os.path.dirname(__file__)), "templates"))


@app.exception_handler(ValueError)
async def value_error_handler(request: Request, exc: ValueError):
    # e.g. invalid phone number from security.normalize_ng_phone
    return JSONResponse(status_code=400, content={"detail": str(exc)})


# ---------- helpers ----------

def db_session():
    db = SessionLocal()
    try:
        yield db
    finally:
        db.close()


def current_user(authorization: str = Header(default=""),
                 db=Depends(db_session)) -> User:
    token = authorization.removeprefix("Bearer ").strip()
    if not token:
        raise HTTPException(401, "Missing bearer token")
    row = db.query(Token).filter(Token.token == token).first()
    if not row:
        raise HTTPException(401, "Invalid token")
    user = db.get(User, row.user_id)
    if not user:
        raise HTTPException(401, "Invalid token")
    return user


def alert_or_404(db, alert_id: str, user: User) -> Alert:
    alert = db.query(Alert).filter(Alert.id == alert_id, Alert.user_id == user.id).first()
    if not alert:
        raise HTTPException(404, "Alert not found")
    return alert


def alert_public(db, alert_id: str) -> Alert:
    alert = db.get(Alert, alert_id)
    if not alert:
        raise HTTPException(404, "Alert not found")
    return alert


def alert_summary(db, alert: Alert) -> dict:
    notifs = db.query(Notification).filter(Notification.alert_id == alert.id).all()
    acks = db.query(Acknowledgement).filter(Acknowledgement.alert_id == alert.id).all()
    contacts = {c.id: c for c in db.query(Contact).filter(Contact.user_id == alert.user_id).all()}
    return {
        "id": alert.id, "type": alert.type, "status": alert.status,
        "started_at": alert.started_at.isoformat() if alert.started_at else None,
        "tracking_url": f"{config.BASE_URL}/t/{alert.tracking_token}",
        "expires_at": alert.expires_at.isoformat() if alert.expires_at else None,
        "last_location": {"lat": alert.last_lat, "lng": alert.last_lng,
                          "battery": alert.last_battery},
        "timestamps": {
            "notified_at": alert.notified_at.isoformat() if alert.notified_at else None,
            "acknowledged_at": alert.acknowledged_at.isoformat() if alert.acknowledged_at else None,
            "resolved_at": alert.resolved_at.isoformat() if alert.resolved_at else None,
            "false_alarm_at": alert.false_alarm_at.isoformat() if alert.false_alarm_at else None,
        },
        "notifications": [
            {"contact": contacts[n.contact_id].name if n.contact_id in contacts else "?",
             "channel": n.channel, "status": n.status, "detail": n.detail,
             "sent_at": n.sent_at.isoformat() if n.sent_at else None}
            for n in notifs
        ],
        "acknowledgements": [
            {"by": a.actor_name, "action": a.action,
             "at": a.at.isoformat() if a.at else None} for a in acks
        ],
    }


# ---------- schemas ----------

class RegisterIn(BaseModel):
    phone: str
    name: str
    pin: str  # 4+ digits, never logged


class LoginIn(BaseModel):
    phone: str
    pin: str


class MedicalIn(BaseModel):
    blood_type: str = ""
    allergies: str = ""
    conditions: str = ""
    medications: str = ""
    doctor_phone: str = ""
    note: str = ""


class ContactIn(BaseModel):
    name: str
    phone: str
    relationship: str = ""
    priority: int = 0
    sms_enabled: bool = True
    voice_enabled: bool = True
    whatsapp_enabled: bool = True


class TriggerIn(BaseModel):
    type: str = "panic"  # panic | checkin_missed
    lat: float | None = None
    lng: float | None = None
    battery: int | None = None


class LocationIn(BaseModel):
    lat: float
    lng: float
    battery: int | None = None


class FalseAlarmIn(BaseModel):
    pin: str


class CheckInIn(BaseModel):
    checkin_at: str  # ISO datetime, UTC: "2026-10-13T22:30:00"
    note: str = ""


class AckIn(BaseModel):
    contact_name: str


# ---------- auth ----------

@app.post("/api/auth/register")
def register(body: RegisterIn, db=Depends(db_session)):
    phone = normalize_ng_phone(body.phone)
    if db.query(User).filter(User.phone == phone).first():
        raise HTTPException(409, "This phone number is already registered")
    if len(body.pin) < 4:
        raise HTTPException(400, "PIN must be at least 4 digits")
    user = User(phone=phone, name=body.name.strip(), pin_hash=hash_pin(body.pin))
    db.add(user)
    db.commit()
    token = make_token()
    db.add(Token(token=token, user_id=user.id))
    db.commit()
    return {"token": token, "user": {"id": user.id, "phone": user.phone, "name": user.name}}


@app.post("/api/auth/login")
def login(body: LoginIn, db=Depends(db_session)):
    phone = normalize_ng_phone(body.phone)
    user = db.query(User).filter(User.phone == phone).first()
    if not user or not verify_pin(body.pin, user.pin_hash):
        raise HTTPException(401, "Wrong phone number or PIN")
    token = make_token()
    db.add(Token(token=token, user_id=user.id))
    db.commit()
    return {"token": token, "user": {"id": user.id, "phone": user.phone, "name": user.name}}


# ---------- medical profile ----------

@app.put("/api/me/medical")
def set_medical(body: MedicalIn, user: User = Depends(current_user), db=Depends(db_session)):
    user.medical_enc = encrypt_medical(body.model_dump())
    db.commit()
    return {"ok": True}


@app.get("/api/me/medical")
def get_medical(user: User = Depends(current_user), db=Depends(db_session)):
    return decrypt_medical(user.medical_enc)


# ---------- contacts ----------

@app.get("/api/contacts")
def list_contacts(user: User = Depends(current_user), db=Depends(db_session)):
    rows = (db.query(Contact).filter(Contact.user_id == user.id)
              .order_by(Contact.priority).all())
    return [{"id": c.id, "name": c.name, "phone": c.phone, "relationship": c.relationship,
             "priority": c.priority, "sms_enabled": c.sms_enabled,
             "voice_enabled": c.voice_enabled, "whatsapp_enabled": c.whatsapp_enabled}
            for c in rows]


@app.post("/api/contacts")
def add_contact(body: ContactIn, user: User = Depends(current_user), db=Depends(db_session)):
    count = db.query(Contact).filter(Contact.user_id == user.id).count()
    if count >= config.FREE_CONTACT_LIMIT:
        raise HTTPException(402, f"Free plan covers {config.FREE_CONTACT_LIMIT} contacts. "
                                "Family plan raises this to 10.")
    contact = Contact(user_id=user.id, name=body.name.strip(),
                      phone=normalize_ng_phone(body.phone),
                      relationship=body.relationship, priority=body.priority,
                      sms_enabled=body.sms_enabled, voice_enabled=body.voice_enabled,
                      whatsapp_enabled=body.whatsapp_enabled)
    db.add(contact)
    db.commit()
    return {"id": contact.id}


@app.delete("/api/contacts/{contact_id}")
def delete_contact(contact_id: str, user: User = Depends(current_user), db=Depends(db_session)):
    contact = db.query(Contact).filter(Contact.id == contact_id,
                                      Contact.user_id == user.id).first()
    if not contact:
        raise HTTPException(404, "Contact not found")
    db.delete(contact)
    db.commit()
    return {"ok": True}


# ---------- alerts ----------

def _check_rate_limit(db, user: User):
    since = datetime.datetime.utcnow() - datetime.timedelta(hours=24)
    count = db.query(Alert).filter(Alert.user_id == user.id,
                                  Alert.started_at >= since).count()
    if count >= config.MAX_ALERTS_PER_DAY:
        raise HTTPException(429, "Too many alerts today. Please try again tomorrow. "
                                 "If you are in danger, call 112.")


def create_alert(db, user: User, alert_type: str,
                 lat: float | None, lng: float | None, battery: int | None) -> Alert:
    _check_rate_limit(db, user)
    alert = Alert(
        user_id=user.id, type=alert_type,
        tracking_token=make_tracking_token(),
        expires_at=datetime.datetime.utcnow() + datetime.timedelta(hours=config.TRACKING_LINK_HOURS),
        last_lat=lat, last_lng=lng, last_battery=battery,
    )
    db.add(alert)
    db.commit()
    if lat is not None and lng is not None:
        db.add(LocationPing(alert_id=alert.id, lat=lat, lng=lng, battery=battery))
        db.commit()
    return alert


@app.post("/api/alerts")
def trigger_alert(body: TriggerIn, background: BackgroundTasks,
                  user: User = Depends(current_user), db=Depends(db_session)):
    """Fire an SOS. The phone already ran its 5-second cancel countdown, so this
    goes straight out to every contact on SMS, voice and WhatsApp in parallel."""
    if body.type not in ("panic", "checkin_missed"):
        raise HTTPException(400, "type must be panic or checkin_missed")
    alert = create_alert(db, user, body.type, body.lat, body.lng, body.battery)
    background.add_task(fan_out, alert.id)
    return alert_summary(db, alert)


@app.get("/api/alerts/{alert_id}")
def get_alert(alert_id: str, user: User = Depends(current_user), db=Depends(db_session)):
    return alert_summary(db, alert_or_404(db, alert_id, user))


@app.post("/api/alerts/{alert_id}/location")
async def ping_location(alert_id: str, body: LocationIn,
                        user: User = Depends(current_user), db=Depends(db_session)):
    """Phone streams GPS every 15 seconds during an active alert."""
    alert = alert_or_404(db, alert_id, user)
    if alert.status not in Alert.ACTIVE:
        raise HTTPException(409, "Alert is no longer active")
    alert.last_lat, alert.last_lng, alert.last_battery = body.lat, body.lng, body.battery
    db.add(LocationPing(alert_id=alert.id, lat=body.lat, lng=body.lng, battery=body.battery))
    db.commit()
    await hub.broadcast(alert.tracking_token, {
        "type": "location", "lat": body.lat, "lng": body.lng,
        "battery": body.battery,
        "at": datetime.datetime.utcnow().isoformat(),
    })
    return {"ok": True}


@app.post("/api/alerts/{alert_id}/false-alarm")
def false_alarm(alert_id: str, body: FalseAlarmIn, background: BackgroundTasks,
                user: User = Depends(current_user), db=Depends(db_session)):
    """FALSE ALARM, I'M FINE. Requires the user's PIN (the app may accept
    biometric instead and just call this endpoint). Notifies all contacts."""
    alert = alert_or_404(db, alert_id, user)
    if alert.status not in Alert.ACTIVE:
        raise HTTPException(409, "Alert is no longer active")
    if not verify_pin(body.pin, user.pin_hash):
        raise HTTPException(401, "Wrong PIN")
    alert.status = "FALSE_ALARM"
    alert.false_alarm_at = datetime.datetime.utcnow()
    db.commit()
    background.add_task(broadcast_false_alarm, alert.id)
    return {"ok": True, "status": "FALSE_ALARM"}


@app.post("/api/alerts/{alert_id}/resolve")
async def resolve_alert(alert_id: str, user: User = Depends(current_user),
                        db=Depends(db_session)):
    alert = alert_or_404(db, alert_id, user)
    if alert.status not in Alert.ACTIVE:
        raise HTTPException(409, "Alert is no longer active")
    alert.status = "RESOLVED"
    alert.resolved_at = datetime.datetime.utcnow()
    db.commit()
    await hub.broadcast(alert.tracking_token, {"type": "status", "status": "RESOLVED"})
    return {"ok": True}


# ---------- public recipient endpoints (no login) ----------

def _public_alert(db, token: str) -> Alert:
    alert = db.query(Alert).filter(Alert.tracking_token == token).first()
    if not alert:
        raise HTTPException(404, "This link is not valid.")
    if datetime.datetime.utcnow() > alert.expires_at:
        raise HTTPException(410, "This link has expired.")
    return alert


@app.post("/api/public/alerts/{token}/ack")
async def contact_ack(token: str, body: AckIn, db=Depends(db_session)):
    """A contact tapped 'I'm on my way' on the tracking page."""
    alert = _public_alert(db, token)
    if alert.status not in Alert.ACTIVE:
        raise HTTPException(409, "This alert is no longer active")
    db.add(Acknowledgement(alert_id=alert.id, actor_name=body.contact_name.strip()[:80]))
    if alert.status in ("TRIGGERED", "CONTACTS_NOTIFIED"):
        alert.status = "ACKNOWLEDGED"
        alert.acknowledged_at = datetime.datetime.utcnow()
    db.commit()
    await hub.broadcast(alert.tracking_token, {
        "type": "status", "status": alert.status, "by": body.contact_name.strip()[:80]})
    return {"ok": True, "status": alert.status}


@app.get("/t/{token}", response_class=HTMLResponse)
def tracking_page(token: str, request: Request, db=Depends(db_session)):
    """The no-install recipient page. Loads fast, works on 2G."""
    alert = _public_alert(db, token)
    user = db.get(User, alert.user_id)
    medical = decrypt_medical(user.medical_enc)
    scheme = "wss" if request.url.scheme == "https" else "ws"
    return templates.TemplateResponse(request, "tracking.html", {
        "person_name": user.name,
        "photo_url": user.photo_url or "",
        "phone": user.phone,
        "alert_started": alert.started_at.isoformat() if alert.started_at else "",
        "token": token,
        "medical": medical,
        "ws_url": f"{scheme}://{request.url.netloc}/ws/t/{token}",
        "initial_lat": alert.last_lat, "initial_lng": alert.last_lng,
        "battery": alert.last_battery,
        "status": alert.status,
        "expires_at": alert.expires_at.strftime("%H:%M, %d %b %Y") if alert.expires_at else "",
    })


@app.websocket("/ws/t/{token}")
async def tracking_ws(websocket: WebSocket, token: str):
    db = SessionLocal()
    try:
        alert = db.query(Alert).filter(Alert.tracking_token == token).first()
        if not alert or datetime.datetime.utcnow() > alert.expires_at:
            await websocket.close(code=4404)
            return
    finally:
        db.close()
    await websocket.accept()
    await hub.join(token, websocket)
    try:
        # Push current state immediately so the page never shows stale info.
        await websocket.send_json({"type": "status", "status": alert.status})
        while True:
            await websocket.receive_text()  # keep-alive; client may ping
    except WebSocketDisconnect:
        pass
    finally:
        await hub.leave(token, websocket)


# ---------- check-in timers ----------

@app.post("/api/checkins")
def arm_checkin(body: CheckInIn, user: User = Depends(current_user), db=Depends(db_session)):
    """'Alert my contacts if I don't check in by 22:30.' The server fires the
    full SOS automatically at checkin_at + 10 minutes if not confirmed."""
    try:
        checkin_at = datetime.datetime.fromisoformat(body.checkin_at)
    except ValueError:
        raise HTTPException(400, "checkin_at must be ISO datetime")
    if checkin_at <= datetime.datetime.utcnow():
        raise HTTPException(400, "checkin_at must be in the future")
    # One armed timer at a time per user.
    existing = db.query(CheckIn).filter(CheckIn.user_id == user.id,
                                       CheckIn.confirmed == False,
                                       CheckIn.fired == False).first()
    if existing:
        raise HTTPException(409, "You already have an armed check-in timer")
    ci = CheckIn(user_id=user.id, checkin_at=checkin_at, note=body.note[:200])
    db.add(ci)
    db.commit()
    return {"id": ci.id, "checkin_at": ci.checkin_at.isoformat(),
            "sos_fires_at": (ci.checkin_at + datetime.timedelta(
                seconds=config.CHECKIN_GRACE_SECONDS)).isoformat()}


@app.get("/api/checkins/active")
def active_checkin(user: User = Depends(current_user), db=Depends(db_session)):
    ci = db.query(CheckIn).filter(CheckIn.user_id == user.id,
                                 CheckIn.confirmed == False,
                                 CheckIn.fired == False).first()
    if not ci:
        return {"active": False}
    return {"active": True, "id": ci.id, "checkin_at": ci.checkin_at.isoformat(),
            "note": ci.note}


@app.post("/api/checkins/{checkin_id}/confirm")
def confirm_checkin(checkin_id: str, user: User = Depends(current_user),
                    db=Depends(db_session)):
    """'I'm fine.' Disarms the timer."""
    ci = db.query(CheckIn).filter(CheckIn.id == checkin_id,
                                 CheckIn.user_id == user.id).first()
    if not ci or ci.fired:
        raise HTTPException(404, "No such armed timer")
    ci.confirmed = True
    db.commit()
    return {"ok": True}


async def checkin_watchdog():
    """Background loop: fire SOS for expired, unconfirmed check-in timers."""
    while True:
        await asyncio.sleep(10)
        db = SessionLocal()
        try:
            deadline = datetime.datetime.utcnow() - datetime.timedelta(
                seconds=config.CHECKIN_GRACE_SECONDS)
            due = db.query(CheckIn).filter(CheckIn.confirmed == False,
                                          CheckIn.fired == False,
                                          CheckIn.checkin_at <= deadline).all()
            for ci in due:
                user = db.get(User, ci.user_id)
                try:
                    alert = create_alert(db, user, "checkin_missed", None, None, None)
                    ci.fired = True
                    db.commit()
                    log.info("check-in timer expired for %s, fired alert %s",
                             user.phone, alert.id)
                    asyncio.create_task(fan_out(alert.id))
                except HTTPException as exc:
                    # e.g. rate-limited: mark fired anyway so we do not retry forever,
                    # the 429 message tells them to call 112.
                    log.warning("check-in auto-fire blocked for %s: %s", user.phone, exc.detail)
                    ci.fired = True
                    db.commit()
                except Exception as exc:
                    log.exception("check-in auto-fire failed: %s", exc)
                    db.rollback()
        finally:
            db.close()


@app.on_event("startup")
async def startup():
    init_db()
    asyncio.create_task(checkin_watchdog())
    log.info("SafeTap backend up. Mock gateways: %s", config.MOCK_GATEWAYS)


@app.get("/api/health")
def health():
    return {"ok": True, "mock_gateways": config.MOCK_GATEWAYS}
