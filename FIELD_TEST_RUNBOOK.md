# SafeTap Field Test Runbook

How to take SafeTap from "verified in the sandbox" to "proven on real phones."
Do the steps in order. Each test has a pass mark, write down your result.

## What you need

- Your Linux Mint machine (runs the backend)
- Two phones: phone A (the person in danger, runs the SafeTap app) and
  phone B (the emergency contact, just receives SMS/calls/WhatsApp)
- Both phones on the same WiFi as your machine for the first tests
- A Termii account with a little credit (for the real-SMS tests)

## Step 0: Get your Termii key

1. Sign up at termii.com and fund the wallet with a small amount.
2. Copy your API key from the Termii dashboard.
3. Note the sender ID Termii gives you. If "SafeTap" is rejected, use theirs.

## Step 1: Run the backend on your machine

```bash
git clone https://github.com/Billyehm/safetap.git
cd safetap/backend
python3 -m venv .venv && .venv/bin/pip install -r requirements.txt
```

Find your machine's LAN address:

```bash
hostname -I
```

Say it prints `192.168.1.40`. Start the server so your phone can reach it:

```bash
export SAFETAP_SECRET_KEY=$(openssl rand -hex 32)
export SAFETAP_BASE_URL=http://192.168.1.40:8000
export MOCK_GATEWAYS=0
export TERMII_API_KEY=<your key>
export TERMII_SENDER_ID=<your sender id>
.venv/bin/uvicorn app.main:app --host 0.0.0.0 --port 8000
```

Keep `MOCK_GATEWAYS=1` for the first dry run if you want to test the full
flow without spending a kobo. The mock sends are printed in the server log.

If phone B cannot open `http://192.168.1.40:8000/docs` in its browser, check
your firewall (`sudo ufw allow 8000`) and that both devices are on the same
WiFi.

## Step 2: Point the apps at your machine

- **Android:** in `mobile/android/app/src/main/java/com/safetap/ApiClient.kt`
  line 20, change `BASE_URL` to `"http://192.168.1.40:8000"`
  (your LAN address, not 10.0.2.2, which is emulator-only).
- **iOS:** in `mobile/ios/SafeTapApp/Config/AppConfig.swift` line 7, change
  `baseURL` to `"http://192.168.1.40:8000"`.

`SAFETAP_BASE_URL` (step 1) and these two must agree, otherwise the tracking
links texted to contacts will point at the wrong address.

## Step 3: Compile and install

- **Android:** open `mobile/android` in Android Studio, run a Gradle sync,
  then install on phone A. Put the SOS widget on the home screen.
- **iOS:** open the project in Xcode, set the App Group
  (`group.com.safetap.app`), Keychain Sharing, the `safetap` URL scheme, and
  the location/Face ID usage strings (checklist in `mobile/ios/README.md`),
  then install on phone A.

On phone A: register, add phone B as an emergency contact, fill the medical
profile.

## Test 1: SOS end to end (the big one)

1. On phone A, press and hold the widget SOS button for 3 seconds, then let
   the 5-second countdown run out. Start a stopwatch when it ends.
2. On phone B, note when the first SMS arrives, when the voice call rings,
   and when the WhatsApp message arrives.
3. Tap the tracking link on phone B. It must load in under 3 seconds on
   mobile data and show the name, live location, battery, and medical notes.
4. Walk 50 metres with phone A. The tracking page must update within
   30 seconds.
5. On phone A, open the alert status: every contact and channel must show a
   status (delivered / ringing / failed).

Pass: first message on every channel within 60 seconds, on both WiFi and
mobile data.

## Test 2: False alarm

1. Trigger an SOS, then tap FALSE ALARM, I'M FINE and confirm with
   fingerprint or PIN.
2. Phone B must get the "FALSE ALARM, they are fine" SMS.
3. Any open tracking page must flip to the green all-clear state within
   10 seconds, with no further location updates.
4. Try the wrong PIN first: it must be rejected and the alert must stay live.

Pass: contacts are stood down fast and the flow feels as calm as the alert.

## Test 3: Check-in timer

1. Set a check-in timer for 3 minutes from now.
2. At expiry the phone must buzz loudly with an "I'm fine" prompt.
3. Tap "I'm fine": nothing fires, contacts hear nothing.
4. Repeat, but ignore the buzz. After 10 more minutes the full SOS must fire
   on its own (type: checkin_missed).

Pass: confirming fires nothing, ignoring fires a real alert.

## Test 4: No-data SMS fallback (Android)

1. Turn off mobile data on phone A (keep SMS).
2. Trigger SOS from the widget.
3. Phone B must still get a plain SMS with the last known coordinates and a
   Google Maps link, sent straight from phone A.
4. Turn data back on: the backend must sync and phone B then gets the full
   tracking link.

Pass: contacts get a location by SMS with zero data on phone A.

## Test 5: All three channels, for real

With `MOCK_GATEWAYS=0` and your Termii key set:

1. Trigger SOS and confirm the message arrives by SMS, voice call, and
   WhatsApp.
2. Open the tracking link from each message.
3. Check `GET /api/alerts/{id}` on the backend: each channel must show a
   real receipt (sent / ringing / delivered). Test a deliberately wrong
   number too: it must show failed, not hang.
4. Voice: the call must clearly say "[Name] has triggered an emergency
   alert" over a normal phone speaker.

Pass: three channels, three receipts, no silent failures.

## Results log

| Test | Date | WiFi / data | Pass? | Notes |
| ---- | ---- | ----------- | ----- | ----- |
| 1. SOS end to end | | | | first SMS at __s, call at __s, WA at __s |
| 2. False alarm | | | | |
| 3. Check-in timer | | | | |
| 4. No-data SMS fallback | | | | Android only |
| 5. Three channels live | | | | |

## If something fails

- **Phone cannot reach the backend:** same WiFi? firewall open on 8000?
  `SAFETAP_BASE_URL` set to the LAN address?
- **SMS not sending:** Termii wallet funded? sender ID accepted? Check the
  server log for the provider's error detail.
- **Tracking link does not open:** the link uses `SAFETAP_BASE_URL`. If that
  is localhost, only the server machine can open it.
- **Location not updating:** location permission granted as "always allow"?
  Foreground service notification visible on Android?

When all five pass, SafeTap is field-proven. The next decision is whether to
run the 30-day widget-retention test with real users or build phase-two
features first.
