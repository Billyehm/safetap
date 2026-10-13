# SafeTap iOS scaffold

Two targets: `SafeTapApp` (the app) and `SafeTapWidgets` (the WidgetKit extension).

## iOS version assumptions

- Deployment target: **iOS 16.0**.
- Interactive widget buttons (`Button(intent:)`) need **iOS 17+**. On iOS 16 the widget falls back to a deep link (`safetap://sos`) into `SosConfirmationView`.
- Lock Screen `accessoryRectangular` widgets need iOS 16+.

## Xcode setup this scaffold assumes (not code, do it once)

1. App Groups capability on both targets: `group.com.safetap.app`.
2. Keychain Sharing on both targets with access group `com.safetap.shared` (see `TokenBridge.accessGroup`), so the widget can read the login token.
3. URL scheme `safetap` registered in the app's Info.plist.
4. `NSLocationWhenInUseUsageDescription` in Info.plist with a plain reason string.
5. `NSFaceIDUsageDescription` in Info.plist for the false-alarm biometric gate.
6. No "location" background mode. Deliberate: the app only uses location during an active SOS.

## How the SOS flows fit together

- **iOS 17+ widget:** tap -> `SosIntent` arms 3s -> 5s countdown -> POST `api/alerts` -> shared state `sent` -> app adopts it on next launch and starts the 15s `LocationStreamer`.
- **iOS 16 widget:** tap -> deep link `safetap://sos` -> `SosConfirmationView` (one confirming tap, 5s cancellable countdown) -> `AlertSession.triggerSOS()` -> streamer starts.
- **Cancel any time before firing:** `CancelSosIntent` flips the shared flag; the intent checks it every 0.1s.
- **False alarm after firing:** `FalseAlarmView` requires Face ID / Touch ID, then POSTs `api/alerts/{id}/false-alarm` and stops the streamer.

## SMS on iOS

iOS cannot send SMS programmatically. The backend sends the texts; `SmsFallback.swift` provides the pre-filled `MFMessageComposeViewController` path for manual sends.

## Backend

Base URL lives in one place per target: `SafeTapApp/Config/AppConfig.swift` and `SafeTapWidgets/Shared/WidgetConfig.swift`. Currently `http://localhost:8000`.
