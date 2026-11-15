# Phase 5/6 completion checkpoint

Updated 2026-11-16. Phases 5 and 6 are implemented, built, linted, installed on the Samsung SM-N910U, and the physical queue replay probe completed. The final verification command was:

```sh
./gradlew testDebugUnitTest assembleDebug lintDebug
```

Result: BUILD SUCCESSFUL, 43 tests, 0 failures/errors; lint 0 errors / 88 warnings. APK: `app/build/outputs/apk/debug/app-debug.apk`, 8,168,867 bytes (SHA-256 `1a3caafbb2df7d2aa9ba54628279001a267c20aa9df4bf49a07c7458c8eb3376`).

## Phase 5

- Service owns camera, AI, microphone, snapshot and WebSocket runtime; explicit controls and persistent notification.
- Home and display-off left the foreground service and Camera2 client active on this Android 6 device. TextureView teardown now closes/reopens the legacy CameraDevice while retaining analysis owners.
- One active-run sample reported 100.8 ms inference, Dalvik Heap PSS 19,497 KiB and EGL mtrack PSS 53,148 KiB; no process peak PSS benchmark was captured.
- Dashboard Stop released the service/camera. Notification action was implemented but not separately tapped.
- AudioRecord starts with the service, but its post-screen-off active-track/sample trace and a service-owned WebSocket transmission while backgrounded remain unverified.
- Battery was below 34 °C in short USB-powered checks.

## Phase 6

- SQLite JSON-only app-private outbox, transaction/WAL, 10,000-message / 24-hour defaults, configurable lower bounds, counters, ACK deletion, identity scoping and bounded replay are implemented.
- Debug-only Note 4 probe verified a seeded row replayed after `am force-stop` with the same SHA-256 payload; final paced mock observed two messages and test rows were cleaned.
- A full 60-second production-config offline interval, 10,000-row overflow and 24-hour age expiry were not physically simulated. The local backend/token is unavailable.

## Approved heartbeat compatibility update — 2026-11-16

- OkHttp now sends an RFC 6455 control-frame ping every 30 seconds. It retains automatic pong handling for peer-originated pings. A missing pong causes OkHttp `onFailure`, which follows the existing exponential reconnect/backoff path; no JSON heartbeat was added and protocol-v1 `sensor.ready`, telemetry, ACK, IDs and replay are unchanged.
- Fresh-install endpoint defaults to `wss://livespace.temuulenbayarjargal.workers.dev/ws/sensors`; device/location defaults remain `LS-NOTE4-001` / `I`. Continuous telemetry defaults to 15000 ms; configure 5000 ms for a short demonstration. Existing saved configuration is preserved. Inference stays at 3000 ms.
- Automated raw local WebSocket peer test withheld pong, observed reconnect, confirmed automatic pong to a peer-originated ping, preserved/replayed the same unacknowledged JSON and message ID from the bounded queue, then maintained the new session. The Phase 6 Note 4 force-stop test remains the separate SQLite process-restart durability evidence.
- Final compatibility build: see the current heartbeat addendum in `docs/TESTING.md` for test/lint/APK hash and physical launch results. Cloudflare authentication and real backend telemetry remain unverified because no device token was supplied. Screen-off control-ping exchange with an authenticated backend has not been physically observed; service wake-lock behavior is documented in `PHASE5_REPORT.md`.

## Next commands / handoff

No Phase 7 backend integration has been attempted. When the registered LiveSpace device token is provided and the backend is reachable, configure its assigned device/location and WSS/approved private-LAN endpoint in the app, start the foreground service, connect, then confirm accepted history/SSE with the backend team. Do not use Phase6 probe credentials or identity for the real device.
