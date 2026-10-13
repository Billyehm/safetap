# SafeTap Android: build notes

Native Kotlin scaffold for the SafeTap emergency SOS app. This covers the
Android half of the MVP: the widgets, the SOS flow, the check-in timer,
contacts, the medical profile, and the false-alarm gate.

## Opening it

1. Open Android Studio (Koala / 2024.1 or newer, for AGP 8.5.2).
2. File > Open, pick the `android/` folder (the one with `settings.gradle`).
3. Let Gradle sync. First sync downloads the Android Gradle Plugin,
   Kotlin 2.0.21, Compose BOM and the Play Services location library,
   so it needs internet once.
4. Run on an emulator (API 34 image) or a physical phone. The widget
   appears in the widget picker as "SafeTap SOS" and "SafeTap Check-in".

SDK levels: `minSdk 26`, `targetSdk 34`, `compileSdk 34`, Java/Kotlin
target 17. JDK 17 must be the Gradle JDK (Android Studio ships one).

## Where the backend URL lives

`app/src/main/java/com/safetap/ApiClient.kt`, top of the object:

```kotlin
const val BASE_URL = "http://10.0.2.2:8000"
```

`10.0.2.2` is the emulator's alias for the host machine's localhost,
so it works out of the box against a FastAPI backend running on your
dev machine. On a real phone, change it to your machine's LAN IP,
e.g. `http://192.168.1.40:8000`, and rebuild.

## Permissions and why each exists

Declared in `app/src/main/AndroidManifest.xml`. The dangerous ones are
requested at runtime from the Safety tab.

| Permission | Why |
|---|---|
| `INTERNET`, `ACCESS_NETWORK_STATE` | Talk to the backend; detect offline mode |
| `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION` | GPS fix at trigger time, 15-second stream during an alert |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_LOCATION` | Keep the location stream alive with the screen off |
| `SEND_SMS` | No-data fallback: SMS straight to contacts with coordinates + maps link |
| `POST_NOTIFICATIONS` | Foreground-service notification and check-in prompts (Android 13+) |
| `VIBRATE` | Hold-to-arm haptics, countdown ticks, check-in buzz |
| `USE_BIOMETRIC` | Fingerprint/face gate on the false-alarm button |
| `SCHEDULE_EXACT_ALARM` | Check-in expiry buzzes on time, even in Doze |

Privacy posture, as the spec demands: location is collected only during
an active alert (or while a check-in timer the user started is armed),
never passively. Say so on first launch and in the store listing.

## Auth

Login screen (phone + PIN) calls `POST /api/auth/login`. The Bearer
token is kept in `EncryptedSharedPreferences` (`Prefs.kt`), along with
the PIN hash, contacts, and medical profile. Every API call sends
`Authorization: Bearer <token>`.

## File map

- `SosWidgetProvider.kt` : 1x1 SOS widget (resizable to 2x1, see
  `res/xml/sos_widget_info.xml`). Tap opens `SosActivity`.
- `SosActivity.kt` : full-screen SOS. Press-and-hold 3 s on the ring
  (haptics + filling arc) to arm, 5 s countdown with big CANCEL, then
  fire. Shows delivery status and the FALSE ALARM button.
- `AlertFirer.kt` : shared fire logic. Online: `POST /api/alerts/trigger`
  then starts the streaming service. Offline: `SmsManager` text to each
  contact with last known coordinates and a Google Maps link, service
  starts anyway and syncs later. Rate limit: 7 alerts/day.
- `SosForegroundService.kt` : foreground service, `location` type.
  POSTs lat/lng/battery to `/api/alerts/{id}/location` every 15 s.
  Polls alert status each round and stops on RESOLVED or FALSE_ALARM.
- `CheckInWidgetProvider.kt` + `CheckInReceiver.kt` : second widget.
  One tap starts a 60-minute check-in (`POST /api/checkins`) via an
  exact alarm. At expiry, `CheckInActivity` buzzes loudly with an
  I'M FINE button; ignore it for 10 minutes and the full SOS fires
  as a `checkin_missed` alert.
- `CheckInActivity.kt` : full-screen expiry prompt with countdown to
  auto-escalation.
- `MainActivity.kt` + `ui/` : login gate, bottom-nav tabs.
  `ContactsScreen` (max 2 on the free tier, Nigerian number check),
  `MedicalScreen` (blood type, allergies, conditions, medications,
  doctor number, free-text note), `FalseAlarmScreen` (PIN or
  BiometricPrompt, then `POST /api/alerts/{id}/resolve`).

## Platform assumptions worth knowing

- Location uses `FusedLocationProviderClient` (Play Services). A pure
  AOSP device without Play Services would need `LocationManager`; the
  call sites are isolated in `AlertFirer` and `SosForegroundService`.
- `VibrationEffect` needs API 26+ (== minSdk, fine). `VibratorManager`
  is API 31+; the code falls back to the old `VIBRATOR_SERVICE` below.
- `targetCellWidth/Height` in the widget XMLs is honored on Android 12+.
  Below that, `minWidth/minHeight` (40dp = 1x1) applies.
- Exact alarms: if the user denies `SCHEDULE_EXACT_ALARM` on Android 12+,
  the check-in falls back to `setAndAllowWhileIdle`. The 10-minute grace
  makes a few minutes of drift acceptable.
- The widget cannot do press-and-hold itself (RemoteViews only fires a
  tap), so the hold-to-arm lives in `SosActivity`. This matches the spec.

## Deliberately not here

Voice calls, WhatsApp messages, the no-install tracking web page, and
delivery receipts are backend/gateway work (Termii etc.), not phone work.
The phone's job is: trigger fast, stream location, survive offline.
