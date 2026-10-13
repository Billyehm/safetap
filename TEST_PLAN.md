# SafeTap MVP Test Plan

How to prove the system works before any real user depends on it. Run the
automated suite first, then the manual field tests on real phones.

## 0. Automated backend suite

```bash
cd backend
.venv/bin/uvicorn app.main:app --host 127.0.0.1 --port 8000 &
.venv/bin/python /tmp/safetap_e2e.py
```

Covers: health, registration with Nigerian number formats, login, wrong-PIN
rejection, bad-number rejection, medical profile encryption round-trip,
contact limits (2 free, 3rd blocked), SOS trigger, tracking-link issuance,
fan-out to all contacts on all channels, delivery receipts, state machine
(TRIGGERED -> CONTACTS_NOTIFIED), location pings, WebSocket live streaming,
public contact acknowledgement (-> ACKNOWLEDGED), false-alarm PIN gate and
resolution, check-in arm/confirm, the watchdog auto-firing an overdue timer,
and the 7-alerts-per-day rate limit. 26 of 27 checks currently pass; the one
outstanding check (tracking page render) passes once `templates/tracking.html`
lands.

## 1. SOS end to end

Goal: a simulated SOS reaches all contacts within 60 seconds on WiFi and on
mobile data.

1. On a test phone, install the app, register, add 2 contacts (real numbers
   you control), fill the medical profile.
2. Put the widget on the home screen. Press and hold the SOS button for 3
   seconds (feel the haptic, watch the ring fill), let the 5-second countdown
   run out.
3. Start a stopwatch at the moment the countdown ends.
4. Check each contact's phone: an SMS, a voice call, and a WhatsApp message
   should all arrive. Note the arrival time of the first message per channel.
5. Tap the tracking link on a contact's phone: the page must load in under 3
   seconds on mobile data, show the name, live location, battery, and medical
   notes.
6. Walk 50 metres with the alert phone. The map on the tracking page must
   update within 30 seconds.
7. On the alert phone, open the confirmation screen: every contact and channel
   must show a status (delivered / ringing / failed).

Pass: first message on every channel arrives within 60 seconds, on both WiFi
and mobile data, location updates flow.

## 2. False alarm

1. Trigger an SOS as above, then immediately tap FALSE ALARM, I'M FINE and
   enter the PIN (or biometric).
2. Every contact must receive the "FALSE ALARM, they are fine" SMS.
3. Any open tracking page must flip to the calm green "false alarm" state
   within 10 seconds, with no further location updates.
4. Try the wrong PIN first: it must be rejected and the alert must stay live.

Pass: contacts are stood down fast, and the flow feels as polished as the
alert itself. A product people are afraid to touch gets deleted.

## 3. Check-in timer expiry

1. Set a check-in timer for 3 minutes from now ("walking home").
2. At expiry the phone must buzz loudly with an "I'm fine" prompt.
3. Tap "I'm fine": the timer disarms, no alert fires, contacts hear nothing.
4. Repeat, but this time ignore the buzz. After 10 more minutes the full SOS
   must fire automatically (type: checkin_missed), with the same triple-channel
   fan-out as a panic SOS.
5. Confirm that only one timer can be armed at a time.

Pass: the grace period works, ignoring it fires a real alert, confirming it
fires nothing.

## 4. No-data SMS fallback

1. Put the alert phone in airplane mode, then re-enable only SMS (or use a
   phone with no data plan / remove the SIM data).
2. Trigger SOS from the widget. The app must detect there is no data
   connection and send a plain SMS directly from the phone to each contact
   with the last known GPS coordinates and a Google Maps link.
3. Reconnect to data: the backend must sync the alert and the contacts must
   then receive the full tracking link.

Pass: contacts still get a location by SMS with zero data on the alert phone.

## 5. All three recipient channels

For each channel (SMS, voice call, WhatsApp), with a real gateway configured
(MOCK_GATEWAYS=0, Termii keys set):

1. Trigger SOS and confirm the message arrives on that channel.
2. Confirm the tracking link inside each message opens the page.
3. Confirm the delivery receipt shows the right status in
   GET /api/alerts/{id} (sent / ringing / delivered; a deliberately wrong
   number must show failed, not hang).
4. Voice: the call must play "[Name] has triggered an emergency alert" via
   text-to-speech and be understandable over a normal phone speaker.

Pass: three channels, three receipts, no silent failures.

## 6. Edge cases worth a run

- Pocket dial: tap (not hold) the widget button; nothing must happen.
- Cancel during countdown: alert never fires, contacts hear nothing.
- Tracking link after 12 hours: must show "link has expired", and no location
  history is viewable.
- 8th SOS in one day: blocked with a clear message pointing at 112.
- Location permission denied: the alert still fires (contacts get the alert
  without live location) instead of crashing.

## 7. Success criteria (from the spec)

- 100 test users keep the widget on their home screen after 30 days.
- At least 20 check-in timers used per week across the cohort.
- Simulated SOS reaches all contacts within 60 seconds on WiFi and mobile data.
- Measured by the widget staying installed, not by downloads.
