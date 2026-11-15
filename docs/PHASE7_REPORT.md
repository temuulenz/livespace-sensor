# Phase 7 production integration report

Updated 2026-11-16. **Phase 7 is not complete.** This checkpoint records verified preflight work and the current production blocker without inventing phone readings or backend ACKs.

## Preflight results

- The supplied token path exists and has mode `0600`, owner-only permissions. Its value was not read into output, logged, committed or copied into source. The Android settings screen writes it using Android Keystore AES/GCM. The user was asked to enter it directly in the app and confirm before any authenticated production traffic.
- Read-only host HTTPS: public Workers site HTTP 200; `/api/v1/health` HTTP 200, reporting Cloudflare deployment, D1, `sensorGateway: true`, `transport: polling`, schema 4. A standard CA- and hostname-validated host TLS handshake negotiated TLS 1.3. This does not verify the Android 6 trust path.
- Public real-source history baseline for cafe `I` returned `readings: []`, `aggregates: []`. No production telemetry has been inserted by this Android session.
- ADB reports the connected device `SM_N910U`. The dashboard reports `LS-NOTE4-001`, SM-N910U, Android 6.0.1, sensor stopped, detector interval 3000 ms, two CPU threads, 100% battery / 33.8 °C, USB-powered, and Wi-Fi. With sensors stopped, model, observed count, noise, inference and uptime are unavailable. This is a point-in-time USB-powered observation after the isolated DB probe, not a battery endurance result.
- App-private settings could not be inspected with `adb run-as` (`Could not set capabilities`), so current saved endpoint, telemetry interval and Keystore token presence remain unverified. Do not infer fresh-install defaults override saved settings.

## Implementation and automated checks

- Existing WebSocket implementation remains protocol v1: Bearer authorization header, `sensor.ready` identity gate, 30-second protocol ping, accepted/duplicate ACK deletion, unchanged-message replay and SQLite outbox. No backend protocol/schema change was made.
- Added a **debug-only** SQLite outbox probe with a unique isolated database, maximum two rows and 60-second retention. It checked expiry counter, overflow/drop counter, close/reopen persistence, immutable payload re-read and idempotent ACK accounting on the Note 4. It did not touch the production `sensor_outbox.db`; the isolated database was deleted after the passing run.
- `./gradlew testDebugUnitTest assembleDebug lintDebug`: **BUILD SUCCESSFUL**, 44 tests passed, 0 failures/errors; lint 0 errors / 88 warnings.
- Current APK: `app/build/outputs/apk/debug/app-debug.apk`, 8,169,226 bytes, SHA-256 `5d5f7e92136f56083567c63dd255f94967039a27e08f25e4267adb0afc657206`. It is installed and the normal dashboard launched. `adb install -r` preserved app data. No app fatal exception appeared in the inspected log slice.

## Production verification still pending

The phone has not yet completed an authenticated WSS session. There is no observed Android-originated `sensor.ready`, real camera/microphone payload, accepted/duplicate ACK, SQLite queue decrement, Cloudflare dashboard update or admin diagnostics observation. Therefore this report does not claim production integration success.

Pending user-dependent tests: token entry confirmation; actual phone TLS/WebSocket connection; five-minute screen-off run; manual positive person and box alignment; quiet/talk/clap checks; microphone-failure isolation; public real-source update and historical/admin inspection; controlled 60-second outage/replay; notification Stop cleanup. Follow [PHASE7_PROGRESS.md](PHASE7_PROGRESS.md) and [DEMO_GUIDE.md](DEMO_GUIDE.md) to resume.

No production telemetry was fabricated. No simulator was started. No backend data, accounts, resources, TLS settings or credentials were modified. No Git operation was performed. Continue with safe in-app provisioning, then perform P0 connection/ACK checks before optional verification.

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
