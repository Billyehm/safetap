# SafeTap (working title)

One press on a home-screen widget alerts your emergency contacts by SMS,
voice call, and WhatsApp. Contacts need no app: they get a secure web link
with your live location, medical notes, and an "I'm on my way" button.

## Repo layout

```
safetap/
  backend/            FastAPI + SQLite (Postgres-ready), alert state machine,
                      WebSocket location streaming, check-in watchdog
    app/
      main.py         routes, auth, alerts, check-ins, tracking page, WS
      models.py       users, contacts, alerts, pings, acks, notifications, checkins
      notifier.py     triple-channel fan-out + delivery receipts
      adapters.py     Termii SMS / voice / WhatsApp adapters (+ mock mode)
      security.py     PIN hashing, tokens, medical encryption, NG phone numbers
      hub.py          in-memory WS hub (swap for Redis pub/sub at scale)
    templates/
      tracking.html   no-install recipient page (2G-friendly)
    data/             SQLite file lives here
  mobile/
    android/          Kotlin: widget, hold-to-arm SOS, countdown, SMS fallback
    ios/              Swift: iOS 17 AppIntent widget, iOS 16 deep-link fallback
  TEST_PLAN.md        the 5 required tests + edge cases + success criteria
```

## Quick start (backend)

```bash
cd backend
python3 -m venv .venv && .venv/bin/pip install -r requirements.txt
.venv/bin/uvicorn app.main:app --host 127.0.0.1 --port 8000
```

The API is at `http://localhost:8000`, docs at `/docs`. By default
`MOCK_GATEWAYS=1`, so SMS/voice/WhatsApp are logged, not sent.

## API sketch

- `POST /api/auth/register` {phone, name, pin} -> {token}
- `POST /api/auth/login` {phone, pin} -> {token}
- `GET/PUT /api/me/medical` (encrypted at rest)
- `GET/POST /api/contacts`, `DELETE /api/contacts/{id}` (2 free)
- `POST /api/alerts` {type, lat, lng, battery} -> alert + fan-out
- `POST /api/alerts/{id}/location` (every 15s during alert)
- `GET /api/alerts/{id}` (state + per-channel delivery receipts)
- `POST /api/alerts/{id}/false-alarm` {pin}
- `POST /api/alerts/{id}/resolve`
- `POST /api/checkins` {checkin_at, note} / `GET /api/checkins/active` / `POST /api/checkins/{id}/confirm`
- `GET /t/{token}` (recipient page, no login) / `POST /api/public/alerts/{token}/ack`
- `WS /ws/t/{token}` (live location + status pushes)

Alert state machine: `TRIGGERED -> CONTACTS_NOTIFIED -> ACKNOWLEDGED -> RESOLVED | FALSE_ALARM`.

## Going live with real gateways

```bash
export MOCK_GATEWAYS=0
export TERMII_API_KEY=... TERMII_SENDER_ID=SafeTap
export VOICE_API_URL=... VOICE_API_KEY=...
export WHATSAPP_API_URL=... WHATSAPP_API_KEY=...
export SAFETAP_BASE_URL=https://your-domain.com
export SAFETAP_SECRET_KEY=$(openssl rand -hex 32)
export SAFETAP_DATABASE_URL=postgresql://...   # SQLite is the dev default
```

Adapter code lives in `backend/app/adapters.py`; each provider is one class
with one `send()` method, so swapping Termii for another Nigerian gateway is
a contained change.

## Platform differences (also documented in-app)

- **Android:** real home-screen widget (1x1, 2x1), press-and-hold 3s to arm,
  lock-screen shortcut where the OS allows, and true no-data SMS fallback via
  SmsManager straight from the phone.
- **iOS:** widgets cannot run code on tap like Android. iOS 17+ uses an
  interactive widget with AppIntent so SOS fires from the widget; iOS 16 taps
  deep-link into the app's full-screen SOS confirmation (one more tap). Lock
  Screen widget slot supported. No programmatic SMS on iOS, so the no-data
  path leans on the backend SMS gateway instead of the phone.

## Nigerian specifics

- Emergency info section defaults to **112** and **767 (Lagos)**, labelled as
  informational, never auto-dialled.
- Phone validation accepts +234 / 234 / 0-prefixed numbers, stored as +234.
- Pricing in naira; SMS via Termii (or equivalent) with delivery receipts.
- Location streams every 15 seconds **only during an active alert**, never in
  the background otherwise. Stated on first launch and in the store listing.
- Tracking page works on 2G: text-first, map behind a "Load map" button.

## Safety rails

- Max 7 SOS triggers per user per day, then a cooldown pointing at 112.
- Tracking links expire after 12 hours; no location history after expiry.
- Location is collected only during an active alert or an armed check-in
  timer, never passively.
- False alarm needs PIN or biometric, and notifies every contact.
