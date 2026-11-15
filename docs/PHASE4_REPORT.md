# Phase 4 — WebSocket telemetry and preview orientation

## Implementation

Camera2/TFLite/audio and their defaults were preserved. `FrameGeometry` now models TextureView's existing sensor-orientation correction separately from display rotation; the TextureView transform applies display rotation once, calculates a fit-center rectangle from the upright stream size, and the detection overlay shares that rectangle. YUV/model coordinates continue using sensor orientation minus display rotation. This fixes the double-sensor-rotation path without changing inference preprocessing.

OkHttp **4.12.0** is pinned. Its published Android support starts at API 21; the app minSdk remains 23. The final Note 4 ran the library successfully on Android 6.0.1/ARMv7. The packaged TFLite library remains `lib/armeabi-v7a/libtensorflowlite_jni.so`; the existing model SHA-256 is unchanged: `e4b118e5e4531945de2e659742c7c590f7536f8d0ed26d135abcfe83b4779d13`. No GPU/NNAPI/TFLite change.

`SensorProtocolV1` implements [the frozen v1 protocol](SENSOR_PROTOCOL.md): numeric version 1; exact required envelope and nested field names; UTC millisecond timestamps; UUID message IDs; nullable unavailable values; calibration-aware SPL; and no media. Device ID/location are checked against `sensor.ready` before telemetry is sent. HTTP Bearer authentication, `accepted`/`duplicate` acknowledgements, documented error-code handling and retry of the exact same JSON/message ID are implemented. OkHttp handles server control pings/pongs; no unsupported application-level heartbeat is invented.

Connection actor owns network and retry state. It uses exponential jittered reconnect (capped at 30 s), a 16-entry volatile queue, four in-flight messages, 15 s acknowledgement timeouts and at most three attempts per entry. Auth failures stop reconnect; invalid/validation messages are quarantined; storage failures retry unchanged; rate limits pause with backoff. New messages are refused when the bounded queue is full. Queue contents are lost on stop/process death by design; durable SQLite belongs to Phase 6.

Settings use the demo defaults `ws://192.168.1.240:8080/ws/sensors`, `LS-NOTE4-001`, location `I`, and 5000 ms. Private-LAN ws requires its explicit setting. Production accepts configurable WSS hosts, including the documented workers.dev endpoint, with normal certificate and hostname verification. Device tokens are stored using AndroidKeyStore AES/GCM; UI never populates or displays the secret, and logs/errors omit URL credentials, headers, response bodies and close reasons. No token is included in this repository.

## Verification results

- `./gradlew testDebugUnitTest assembleDebug lintDebug`: **BUILD SUCCESSFUL**; **40 tests, 0 failures/errors**; lint **0 errors / 76 warnings**. Tests include exact JSON keys/types/nulls, UTC formatting, ID uniqueness, ready/ACK/error parsing, matching duplicate ACK behavior, same-ID/body retry after `storage_failed`, HTTP auth failure, reconnect and bounded queue policy.
- Installed successfully on Samsung SM-N910U through USB ADB. Final debug APK: `app/build/outputs/apk/debug/app-debug.apk`, **8,109,971 bytes**, SHA-256 `14fd0f0654516250beac2bbde1211b4e386bc90ca0bbace4accac91c8974135b`.
- Physical local WebSocket mock verified request path and Bearer header, AndroidKeyStore roundtrip, exact root/nested field sets, numeric schema version, configured IDs, UUID shape, UTC timestamp, absence of raw-media fields, JSON telemetry receipt and ACK processing. The random test token was not present in logs. Evidence: `evidence/phase4/device-network-probe.json`.
- 0°, 90°, 180° and 270° each completed three camera/inference/audio samples with zero reported inference failures on the camera-fix build; the final heartbeat-only network rebuild retained identical camera code. Aggregate evidence: `evidence/phase4/rotations.json` and `rotation-*.jsonl`.
- Final-build smoke recorded two successful simultaneous camera/TFLite and 16 kHz microphone samples before Activity recreation caused the benchmark harness to stop. This incomplete run is retained as `evidence/phase4/final-ai-audio-smoke.jsonl`, not summarized as a benchmark. Those samples showed 29.8 °C battery temperature, USB powered, 85% charge, and ~29–31 MiB sampled app PSS (does not include full EGL graphics PSS).
- After the final install, the app launched and the filtered `AndroidRuntime`, camera, detection and audio error logs were empty. Final idle-process snapshot: 59,334 kB total PSS, including 43,236 kB EGL; this is not the full active-sensor peak. Rotation settings were restored to auto-rotate enabled / user rotation 0.
- No AndroidRuntime, camera, detection or audio error was present in the post-install launch log filter. The app was installed/launched; the mock exercised network lifecycle in a debug-only probe activity.

## Manual confirmation and limits

The code path now corrects rotation mathematically, but the phone display was not visually inspected by a person during this run. An operator must confirm upright portrait/landscape framing and that boxes align with a consenting person in the intended mounting orientation. Automated captures had zero detected people; no positive-person accuracy claim is made. Quiet/talk/clap acoustic changes still need human-stimulus confirmation.

The actual registered local Node backend was not used because no one-time registered device token was supplied. The compatible protocol mock verified the Android serializer/ACK path, but it does not replace a real backend registration/authentication check. Cloudflare WSS endpoint availability and TLS connection were not tested. The final combined network+camera+AI+microphone workload has not been profiled for power/thermal endurance. Battery temperature is not CPU temperature; USB was powered but reported max charging current 0 in an earlier sample, so charging adequacy is unknown. Final post-install battery was 30.4 °C/88% and USB-powered.

## Handoff

Phase 4 code and the v1 client contract are ready for the next phase. Phase 5 may add the foreground service while retaining explicit user Start/Stop and all media privacy rules. Phase 7 should first register this device in the real backend, supply its token through the app settings, verify the assigned cafe ID is `I` (or configure the actual registered ID), and confirm accepted telemetry reaches backend history/SSE. Do not enable a 24/7 deployment claim until service, reconnect, thermal and old-battery stability tests pass.
