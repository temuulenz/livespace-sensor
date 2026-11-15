# Phase 7 production integration checkpoint

Updated 2026-11-16. Phase 7 is in progress; authenticated production WSS and accepted ACKs are now verified. See the latest TLS compatibility results below.

## Confirmed

- Read the Android protocol, Phase 5/6 reports, current implementation, and backend deployment/protocol documentation in the sibling LiveSpace web repository. The deployed backend is Cloudflare Worker + D1; the physical registration is `LS-NOTE4-001`, cafe/location `I`, kind `sensor`, real source. No backend files were changed.
- The production token file exists at the authorized path, is mode `0600`, owner-only, and 48 bytes. Its contents have not been read into output, logs, or source.
- Host-side requests to the public page and `/api/v1/health` returned HTTP 200. Health identified Cloudflare, D1, sensor gateway, schema 4. A standard certificate-verified host TLS handshake negotiated TLS 1.3.
- Read-only public history for cafe `I`, source `real`, currently returns zero readings. No production sensor data has been fabricated or sent from another client.
- The Note 4 is connected over ADB. Main dashboard shows `LS-NOTE4-001`, SM-N910U, Android 6.0.1; sensor stopped; 3000 ms inference, 2 CPU threads; 100% battery / 33.8 °C, USB powered; network type Wi-Fi. No person-count/audio/inference readings are available while stopped.
- `adb run-as` cannot access app-private settings on this device (`Could not set capabilities`), so pre-existing endpoint/interval/token-presence settings have not yet been independently read. The user has been asked to set/verify the production URL, IDs and 15000 ms interval and enter the token directly into the app's Keystore-backed settings.

## Waiting on user action

The production WebSocket, five-minute screen-off run, public/owner/admin verification, person-in-view and audio-event checks depend on the user saving the token directly on the phone and being present for camera/audio validation. Do not proceed with authenticated production traffic until the user confirms the app saved the token. Do not ask for or echo the token in chat.

## Resume after confirmation

1. On the phone, verify Server and credentials settings; start sensors, connect, and require matching `sensor.ready` for `LS-NOTE4-001` / `I` / `real` before sending. Confirm accepted ACK and SQLite queue decrement.
2. Observe the real-source public cafe page/history and signed-in admin diagnostics. Use only read-only admin routes and do not expose the private account credentials.
3. With the user present, test positive person entry/exit, orientation/box fit, quiet/talking/clapping levels and microphone-failure isolation. Never save raw media.
4. Run the requested five-minute screen-off session while recording actual service state, ACK/queue counters, latency, memory, charging state and battery temperature. Stop safely if the battery is hot or unstable.
5. Conduct the supervised 60-second Wi-Fi outage and replay; verify same message IDs/payloads, ACK removal and latest-state timestamp behavior. Use isolated reduced-limit tests for overflow/expiry.
6. Run final Gradle verification, install the final APK, collect its SHA-256, and complete [PHASE7_REPORT.md](PHASE7_REPORT.md) with verified vs unverified checks.

## Build/test state

`./gradlew testDebugUnitTest assembleDebug lintDebug`: **BUILD SUCCESSFUL**, 44 unit tests, 0 failures/errors; lint 0 errors / 88 warnings. Installed the final APK with `adb install -r` and launched the normal dashboard. Filtered logs showed no app `FATAL EXCEPTION`. The debug-only SQLite probe passed on-device: two-row cap, 60-second expiry, one expired/one dropped counter, close/reopen persistence, payload equality and ACK idempotence. Its unique test DB was deleted; production `sensor_outbox.db` was untouched. APK SHA-256 `5d5f7e92136f56083567c63dd255f94967039a27e08f25e4267adb0afc657206`, size 8,169,226 bytes. No commit, push, merge, branch change, production write or paid service action was performed.

## Resumed session — 2026-11-16

- User confirmed production token and connection settings were saved on the Note 4, authorizing authenticated production integration. Credential value was not read or displayed.
- Started sensor through the dashboard. Android reports `SensorService` foreground with persistent Stop action. Device logs confirm camera capture at 640×480 with preview and AudioRecord at 16000 Hz; these are capture-start checks, not manual accuracy validation.
- Battery observed at 100%, USB powered, 34.3–34.4 °C.
- UI Automator fails with `could not get idle state` during sensing. Earlier dump files were stale and were excluded as evidence of current sensor/network state. Requested the user tap Connect and report current connection/ACK/queue counters plus manual person and audio observations. No successful authenticated connection or ACK is claimed yet.
- Read-only public real-source history still returned empty readings/aggregates. Its response window ended `2026-11-15T21:09:43.378Z`; this is recorded as returned and does not independently establish current server/device clock correctness.
- Repeated `./gradlew testDebugUnitTest assembleDebug lintDebug`: BUILD SUCCESSFUL (unit test/build tasks up-to-date). APK SHA-256 unchanged: `5d5f7e92136f56083567c63dd255f94967039a27e08f25e4267adb0afc657206`. No reinstall needed; no implementation change in this resumed session.
- Sensor remains running for supervised manual tests. Five-minute screen-off, outage/replay, signed-in diagnostics, and Stop cleanup remain pending. No raw media captured or saved by test tooling. Phase 7 remains incomplete.

## Secure production TLS fix and successful integration — 2026-11-16

The saved token confirmation was received. API 23 system trust was missing ISRG roots. Bundled official, fingerprint-verified ISRG Root X1 and added endpoint-scoped platform path validation; normal hostname validation and system trust for other endpoints/newer Android remain intact. Physical default-trust failure, corrected TLS 1.2, invalid-signature rejection and wrong-host rejection verified. Installed with `adb install -r`, preserving token/settings. Matching production ready, accepted ACKs, queue drain to zero, public live real-source updates and real-source history are verified. One backlog rejection was counted and remains unexplained; fresh samples were accepted.

Full implementation, CA provenance/fingerprint, APK hash, 47 passing tests, lint results and evidence: [PHASE7_TLS_COMPATIBILITY.md](PHASE7_TLS_COMPATIBILITY.md). Earlier pending-authentication statements above describe previous checkpoints, not the current status. Manual camera/audio observations, five-minute screen-off, outage/replay, microphone isolation and Stop cleanup are still pending. No later phase or Git publication was performed.
