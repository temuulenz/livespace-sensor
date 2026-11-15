# Phase 4 checkpoint — complete

## Completed

- Kept Camera2 YUV capture, SSD MobileNet V1 UINT8/TFLite 2.8.0, ARMv7 packaging, audio monitoring, and Phase 1–3 defaults intact.
- Corrected TextureView geometry: rely on TextureView's sensor-orientation compensation, apply display rotation once, fit the upright stream without stretching, and use the same fit rectangle for the detection overlay. Model/YUV rotation remains sensor-minus-display.
- Added OkHttp 4.12.0 transport (Android 21+), explicit ws-private-LAN opt-in (off by default), WSS with standard TLS verification, API 23 AndroidKeyStore AES-GCM token storage, readiness gate, v1 telemetry serializer, RFC control ping handling, accepted/duplicate ACKs, sanitized protocol errors, jittered exponential reconnect and bounded volatile retry queue.
- Added 40 unit tests. `testDebugUnitTest assembleDebug lintDebug` passes: 40 tests, zero failures; lint 0 errors / 76 warnings.
- Installed final 0.4.0 debug APK on the SM-N910U (SHA-256: `14fd0f0654516250beac2bbde1211b4e386bc90ca0bbace4accac91c8974135b`). Local mock verified the Note 4 sent exact v1 JSON after `sensor.ready` and handled `sensor.ack`.
- Camera/audio capture and inference initialized in all four display rotations. The 270° run was repeated after a screen-off interruption. Geometry/unit coverage spans all quarter turns.

## Verification limits

- No production token was provided. The local protocol-compatible test server was used; actual registered local Node backend and Cloudflare endpoint were not authenticated/tested.
- The short final-build camera/audio smoke run yielded two real successful samples, then the benchmark harness observed Activity recreation and stopped. Do not treat it as a benchmark. Four rotation checks each have three successful samples in `evidence/phase4/rotations.json`.
- Preview uprightness, aspect appearance, person-positive detection and visible box alignment require human visual confirmation on the phone; no camera image was captured or saved.
- Four-sensor concurrent operation with an acknowledged WebSocket was not measured on device. WebSocket does not block camera/audio/inference worker threads by design.
- No persistent outbox, foreground service or background reliability is implemented (later phases).

## Resume / hand-off

Build and checks: `./gradlew testDebugUnitTest assembleDebug lintDebug`.

Install: `adb install -r app/build/outputs/apk/debug/app-debug.apk`.

Physical mock: `python3 tools/phase4_device_probe.py` (requires connected authorized ADB; uses a one-time random test token and separate disposable Keystore alias).

For the demo, register `LS-NOTE4-001`, enter the one-time device token in Server and credentials, and keep the phone/server on the same non-isolated private Wi-Fi. See `PHASE4_CONNECTIONS.md`. No Phase 5 code was started.
