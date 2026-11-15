# Phase 1 testing — 2026-11-15

## Build evidence

- Existing toolchain retained: Gradle 9.6.0, AGP 9.5.1, Android Studio JBR 25.0.4, compile/target SDK 37, minSdk 23. AGP built-in Kotlin compiled the app without a separate Kotlin/Compose plugin.
- First sandbox build could not write the host Gradle cache. Retried with authorized access.
- First compile/resource build failed because template adaptive icons were in an unversioned folder. Moved them to mipmap-anydpi-v26 and added a platform vector launcher icon. Fixed before installation.
- Final `./gradlew assembleDebug lintDebug`: BUILD SUCCESSFUL, 0 lint errors, 46 warnings. Warnings include English strings/localization, unused template resources, UI suggestions, backup-rule configuration and optional build-tool updates. No unsupported API calls were flagged. Deprecated Camera2 session/connectivity APIs are deliberately used for API 23 compatibility; compiler warnings remain.
- APK: `app/build/outputs/apk/debug/app-debug.apk`, final installed size 956,535 bytes. No native shared libraries or AI dependencies in Phase 1.
- Removed generated Compose sources and unrelated template example tests (including AndroidX tests whose dependencies were removed). No automated unit/instrumentation suite was added or claimed. Verification used real-device ADB smoke testing and Android lint.

## Physical device evidence

Connected authorized Samsung SM-N910U, Android 6.0.1/API 23, armeabi-v7a/armeabi. See DEVICE_PROFILE.md.

- `adb install -r …`: Success, including final APK. Launch via `am start`: succeeded.
- Dashboard hierarchy displayed LS-001, SM-N910U, Android 6.0.1 and live readings: battery 24%, temperature 34.3 °C, charging, available RAM 1231 MiB, network disconnected at one snapshot. Values changed over time; not constants or benchmarks.
- Rear Camera2 session opened at 640×480. Logcat: `Capture running: 640x480`.
- Initial UI snapshot: 53 acquired frames / 2 sampled frames at 2 s.
- Diagnostics snapshot: 900 acquired / 30 sampled, last-frame age 23 ms, sensor error None. This is roughly 30 seconds at the camera's approximately 30 fps; sample interval defaults to 1000 ms. This is not an AI performance benchmark or a soak test.
- Camera2 diagnostics: rear and front hardware level LEGACY (2); rear orientation 90°, front 270°. Rear YUV sizes included 640×480; list saved in DEVICE_PROFILE.md.
- Final APK separately acquired 620 frames / 21 samples before Stop; restart generated a second successful capture log.
- Explicit Stop displayed Stopped; Home/return left Stopped. A subsequent `dumpsys media.camera` snapshot showed no active clients. Closure is asynchronous; a dump taken immediately after Home briefly still showed the client before cleanup completed.
- Inspected `adb logcat -d -s AndroidRuntime:E LiveSpaceCamera:I`: no AndroidRuntime crash entries; successful capture entries present. This does not prove absence of all runtime issues.
- Permission was revoked with `pm revoke`, then Start displayed the Android camera permission prompt. Later capture succeeded with permission granted. Denial was attempted but the UI outcome was not conclusively observed, so denial/permanent denial remain manual checks.
- Diagnostics dialog opened and enumerated both cameras, YUV sizes, frames, last-frame age and hardware. No screenshots or camera pixel data were captured/saved as test artifacts. UI hierarchy XML contains text only.

## Manual checklist

1. Clear/revoke Camera permission, Start, deny: verify a clear denial state and no camera client. Retry, allow and Start again if needed. Test permanent denial and recovery in Android app settings.
2. Confirm rear preview is live, upright and has reasonable aspect ratio in portrait and landscape; test orientation changes. Preview geometry has not been visually verified by the agent.
3. Repeated Start/Stop, rapid Start after Stop, Back/Home, lock/unlock and return. Camera must release, counters stop, and restart work. If closing is still in progress, Start explicitly asks to retry. No screen-off capture is expected in Phase 1.
4. Settings: invalid/empty device ID, invalid location, sampling interval outside 100–60000 ms should fail validation; valid values survive activity/process recreation. Device ID must be 1–64 letters/numbers/_/-. Empty location is permitted before deployment configuration.
5. Disable preview, Start: preview is hidden and YUV counters should still increase. Reenable and retest. Single-output session on this legacy HAL remains unverified.
6. Compare battery and temperature to `dumpsys battery`, memory to Android's available-memory report, and connected/disconnected network state after toggling Wi-Fi. UI network connectivity is not server connectivity.
7. Exercise camera contention with another camera app, permission revocation during use, and device disconnect. Errors must remain visible and explicit Start retry should recover after the cause is removed.
8. Try large font/display settings and TalkBack; scroll to settings/diagnostics. Localized Mongolian UI is not yet provided.
9. Run a supervised 30–60 minute capture test; watch RAM, temperature, UI responsiveness and frame freshness. Battery drain, thermal throttling, long-run reliability, Doze and unattended deployment are not established.
10. Confirm no media files are created and no sensor data is transmitted. This app has no INTERNET or microphone permission in Phase 1.

## Limits

AI/audio/transport/queue/service are intentionally absent. Unavailable values are labeled. Future settings for those phases are explicitly unavailable. No model or labels are bundled yet. Backend was not supplied/inspected. Camera failures require explicit Start retry; automatic service recovery belongs to Phase 5. Runtime permission lifecycle behavior on denial and orientation/preview geometry require further human validation. Latest Android versions have not been physically tested. There is no claim of 24/7 reliability.

Settings smoke test: the final installed APK opened Sensor settings with device ID LS-001, empty Location ID, sampling interval 1000 ms, checked preview toggle, Save/Cancel and the explicit deferred-feature notice. Closed without changing settings. Validation and persistence cases remain on the manual checklist.

# Phase 2 verification

The Phase 1 sections above are historical evidence of the earlier build. Phase 2 results supersede its statements about AI being absent; no audio/network/queue/service was added.

## Runtime/model gate before integration

- Downloaded genuine quantized SSD MobileNet V1 from the TensorFlow-hosted archive. Saved exact model/label hashes and FlatBuffer inspection in MODEL_PROFILE.md and evidence/model-flatbuffer.json.
- Inspected official Maven AARs and ARMv7 ELF imports. TFLite 2.14.0 built but physically failed interpreter loading (`__aeabi_memcpy4`, LIBC_N symbol version). This was a caught probe failure, not hidden or treated as success. Samsung libc exports inspected directly from `/system/lib/libc.so`.
- Selected TFLite 2.8.0 after its actual minimal interpreter init/allocation/invocation/close succeeded on the phone. The probe used neutral synthetic bytes, clearly separated from real observations. Runtime allocated [1,300,300,3] UINT8 input and [1,10,4], [1,10], [1,10], [1] FLOAT32 outputs. No GPU/NNAPI/XNNPACK.
- APK ZIP inspection confirmed only lib/armeabi-v7a/libtensorflowlite_jni.so, matching the selected AAR's library SHA-256; model stored uncompressed. See evidence/apk-native.txt and runtime-aars.json.

## Automated checks

`./gradlew assembleDebug lintDebug testDebugUnitTest` builds the application and runs focused JUnit tests. Tests cover padded row strides, interleaved chroma pixel strides, nonzero plane positions, crop offsets, all quarter-turn rotations, RGB black/white byte values, fit-center geometry, cropped-region-to-preview mapping, monitoring area inclusion, count smoothing, invalid settings and percentile definitions. They use synthetic buffers only and do not claim person accuracy.

Median is the conventional average of the two middle observations for even sample sizes; p95 uses nearest rank. No warm-up samples are silently removed.

## Benchmark method

`tools/benchmark_device.py` launches a fresh debuggable Activity/process per configuration, automatically starts the same camera/detection path, and records only structured logcat metrics. 30 successful samples per complete run; one/two/four threads at 3 seconds plus two threads at 2 and 5 seconds. Camera preview stays enabled. The debug run keeps its own window awake without changing system sleep/thermal settings. Debug overrides never persist and release builds ignore them.

Preprocessing timing includes plane setup, crop/rotation mapping and sampled YUV-to-RGB preparation. Invocation measures Interpreter.runForMultipleInputsOutputs, including its built-in postprocess/NMS. App postprocessing measures person/ROI filtering, smoothing and score summary. Total measures preparation start through app postprocessing completion, including inference-handler waiting; it excludes UI rendering and is not end-to-end sensor exposure latency. Init includes model mapping, interpreter allocation and contract/label validation. Actual Hz is acquisition-start spacing over the run.

Process PSS is Android Debug.getPss(), sampled per successful inference, not total system memory or peak allocation. Battery temperature is from ACTION_BATTERY_CHANGED / dumpsys battery, not CPU temperature. Host checks battery temperature about every 10 seconds and aborts at ≥42 °C or if the temperature becomes unavailable. This conservative engineering guard does not replace Android's thermal protections.

A USB transport reconnection interrupted the first one-thread run after 12 samples. Those raw samples remain labeled with their distinct run ID and are excluded from complete-run summaries. The app was seen reinitializing after the reconnection; subsequent runs start cleanly. The cause of the USB interruption was not established. No inference failure is invented for a host transport interruption.

Configurations are sequential, not randomized; CPU frequency, other phone workloads, scene content and USB charging are uncontrolled. Do not infer energy savings, accuracy, or universal thread superiority from these runs. See evidence/BENCHMARKS.md for full stage/temperature/memory measurements and PHASE2_REPORT.md for interpretation.

## Phase 2 manual visual/deployment checklist

- Put consenting people in view: verify 0/1/multiple people, non-person rejection, confidence thresholds, distances, seated/standing people, occlusion and venue lighting. No images may be saved for this task. Positive person accuracy has not been demonstrated merely by successful inference.
- Confirm box edges align with people and remain aligned with portrait/landscape/180°/270° rotation and fit-center letterboxing. Unit-tested geometry does not replace the legacy HAL's visual verification.
- Confirm monitoring-area box-center inclusion with subjects near each boundary. Keep mounting orientation fixed once the area is configured. Check smoothing delay (up to five samples) and that stopped/stale values become unavailable.
- Change threshold, interval, 1/2/4 threads, preview and boxes; validate invalid/NaN/out-of-range inputs and persistence across process recreation. Confirm preview-disabled inference on this legacy HAL.
- Test permission denial/permanent denial, grant after returning, revocation during capture, camera contention, rapid Start/Stop and Home/return. Verify error messages and successful retry when the cause is removed.
- Inspect responsiveness and memory over a supervised extended session. The short benchmarks do not establish leak-free 24/7 operation, battery safety or power consumption. Use a safe power supply and assess the old battery physically before deployment.
- Consent, visible venue notices, camera placement, physical security and isolated IoT networking remain operator responsibilities. No facial recognition, identities, raw recordings, audio processing or transport are implemented.

Additional interruption: the first 5-second run stopped after 18 samples. Power diagnostics showed mWakefulness=Asleep / display OFF, no active camera client and no AndroidRuntime or detection error. This is an incomplete foreground-only run, not an inference failure or thermal-stop claim. It is retained in raw evidence and excluded from comparisons. A fresh final-build run is used for that configuration.

Final code checks: `assembleDebug lintDebug testDebugUnitTest` succeeded, eight tests passed, lint 0 errors / 54 warnings. Relevant lifecycle improvements include pending permission-grant handling, inference-generation filtering, worker-thread interpreter close, camera cleanup thread-identity guard and deferred TextureView buffer release. The known shared-TFLite-namespace build issue is addressed with the documented supported AGP 9.x switch rather than modifying vendor AARs. Remaining lint warnings include localization/template/UI notices, optional version upgrades, intentionally excluded ChromeOS ABIs and dependency x86_64 16 KB alignment; the inspected APK contains only ARMv7.

Complete benchmarks: six runs ×30 = 180 successful camera inferences, zero reported inference failures in those runs; extra partial 12- and 18-sample runs excluded. The final APK was independently installed and completed both the 5-second run and a repeated default 3-second run. Final default stage median/p95: preprocessing 148.1/156.4 ms, invocation 146.3/197.0 ms, app postprocessing 0.1/0.6 ms, total 295.4/351.5 ms; actual 0.331 Hz. Complete sample PSS 26.3–45.7 MiB; battery 32.7–35.0 °C. All complete samples had zero retained people; positive-person accuracy and visual boxes are not established. Full ordered samples/run IDs and initialization/counter values are retained in evidence/benchmark-samples.jsonl and benchmark-summary.json.

Final-build lifecycle smoke evidence is in evidence/lifecycle-smoke.txt. Three Start→valid camera inference→Stop cycles passed within one normal Activity/process, each with no active camera client after asynchronous cleanup. Home/return left the model closed and camera released. A temporary landscape rotation triggered fresh initialization and three additional real camera inferences (evidence/rotation-smoke.jsonl); original rotation settings were restored. These tests do not visually confirm preview/box alignment. The first combined permission-denial assertion failed; follow-up OS inspection showed permission granted and capture running, so that attempt is not counted as a verified denial. An isolated test checks OS permission state directly.

Permission-test result: both automated denial attempts were inconclusive. The combined test did not observe the expected denial UI, and the isolated retry could not find a stable Deny button. OS package state was granted=true with capture running at follow-up. This does not prove an app denial-handling failure or a successful denial test; denial/permanent denial remain manual requirements. Grant state and successful camera use were verified. No automatic approval/prompt outcome is assumed.

Final independent `adb dumpsys meminfo` snapshot while the app was running: **92,060 kB total PSS (~89.9 MiB), including 53,148 kB EGL/graphics (~51.9 MiB), plus 5,844 kB swap**. The per-sample Debug.getPss() range is **not a full graphics-inclusive footprint or peak-memory claim** on this legacy device; use both reports when sizing deployment memory. Native heap PSS is not inferred from the snapshot's separate heap-allocation columns.

Final battery snapshot: USB powered, charging flag set, 26%, 35.7 °C. Battery had been about 30% before camera benchmarks; the decline despite USB connection means charging flag alone is insufficient evidence of adequate supply under load. No controlled energy/battery-health test was performed. Review cable, supply and battery before deployment. No thermal protections were altered and no 42 °C benchmark guard was triggered.

Final current logcat filter AndroidRuntime:E / LiveSpaceDetection:E / LiveSpaceCamera:E contained no error entries. After final normal launch, active camera clients were empty and sensing was left stopped. See evidence/final-device-snapshot.txt.

# Phase 4 protocol and device verification

Final Phase 4 command: `./gradlew testDebugUnitTest assembleDebug lintDebug`. Result: **40 tests passed**, zero failures/errors; **0 lint errors / 76 warnings**. The added six protocol tests assert exact root/nested JSON keys, numeric schema version, UTC timestamp, available/null data, calibrated SPL constraints, unique UUID-form IDs, ID validation, ready/ACK/error parsing, same-body/message-ID storage retry and physical/mock socket ACK handling. Eleven network foundation tests cover endpoint security, token redaction, bounded queue/in-flight slots, timeout/retry, jittered backoff, HTTP authentication failure and reconnection.

Final APK installed on the authorized `SM_N910U` USB device. The debug-only `tools/phase4_device_probe.py` used a random runtime-only mock token and separate AndroidKeyStore alias. The compatible mock verified the Authorization Bearer header, `/ws/sensors` path, `sensor.ready` gate, exact field sets and types, `schemaVersion` as numeric 1, UUID-shaped ID, UTC timestamp, JSON-only content, telemetry receipt and accepted ACK. It recorded only aggregate true/false checks and test tags. It did not contact a registered Node/Cloudflare backend or write to the app's production token store. Evidence: `evidence/phase4/device-network-probe.json` and `.txt`.

The final installed camera implementation recorded three successful inference samples at each 0/90/180/270 degree orientation with audio capture enabled and zero reported inference failures. See `evidence/phase4/rotations.json`; 270° needed one retry after the display slept. These are camera/AI/audio startup and inference checks, **not visual orientation or positive-person/box-alignment evidence**. Do not save camera captures. The separate smoke run yielded two real AI/audio samples, then the benchmark harness detected Activity recreation and stopped; see `evidence/phase4/final-ai-audio-smoke.jsonl`. It is incomplete and not included in Phase 2 performance summaries.

The final idle post-install snapshot measured 59,334 kB total PSS, including 43,236 kB EGL; the two active smoke samples reported app PSS 29,498 and 31,873 KiB, excluding full graphics and not representing peak memory. Rotation samples measured 29.8–30.0 °C; final launch measured 30.4 °C and 88% charge, USB powered. A prior system snapshot reported max charging current 0, so charging sufficiency is unknown. No thermal guard was changed. No long-duration or four-pipeline network-load benchmark was run.

For a real demo, follow `PHASE4_CONNECTIONS.md`: register the sensor and cafe, use the exact assigned location ID, enter the one-time token, and verify admin/history/SSE receipt. Required manual checks remain: visually confirm upright portrait/landscape view and boxes with a consenting person; positive detection; quiet/talking/clap response; actual token/backend ACK; connection loss/recovery under real Wi-Fi; Start/Stop/Home; and camera+AI+microphone+network simultaneous battery/memory behavior. The Phase 4 protocol mock validates the client contract, but is not evidence those production/manual checks occurred.

# Phase 3 — 2026-11-15/16

## Build and numeric checks

`./gradlew assembleDebug lintDebug testDebugUnitTest`: final build successful, **21 tests passed** (8 detection, 9 acoustic, 4 snapshot), **0 lint errors / 56 warnings**. A telemetry-instrumentation edit temporarily triggered lint SuspiciousIndentation; the indentation and unused local were corrected, not suppressed. Existing pinned-runtime/shared-namespace and API 23-compatible deprecated-API warnings remain documented. No library/model upgrade or minSdk increase.

Acoustic tests cover digital silence and finite −120 dBFS floor; normalized signed full scale and ±half scale; known sine-wave RMS; long sums without integer overflow; actual short-read prefixes; invalid/nonfinite inputs; optional/invalid calibration; correct energy-domain averaging; peak eviction; exact one-second windows over partial/multiple reads; rejection of invalid reading/SPL combinations. Snapshot tests cover valid zero vs unavailable, independent monotonic expiry, microphone failure preserving occupancy, optional calibration, and concurrent reads without torn fields.

## Device checks and measured data

Authorized Samsung SM-N910U/API 23/ARMv7 remained connected. AudioRecord successfully opened **16000 Hz mono PCM16**, source **VOICE_RECOGNITION**, native buffer **16000 bytes**; no sample-rate fallback was needed or claimed tested. The same original TFLite 2.8.0 ARMv7 library and model hashes were verified inside the Phase 3 APK (evidence/phase3/build-artifact.json).

Two initial complete 30-inference runs compare microphone on/off on one Phase 3 build. A later final-build 30-inference run verifies the one-second UI refresh and coherent snapshot additions. All runs use two CPU threads, 3000 ms and the existing 0.5 threshold. They are sequential/uncontrolled, not an isolated power or CPU-overhead experiment. Structured JSONL contains timings/counts/RMS/dBFS/battery/PSS, never waveform data. See evidence/phase3/BENCHMARKS.md / summary.json for results and stage timings.

The actual RECORD_AUDIO runtime prompt was displayed and inspected. A Deny interaction was attempted, but the subsequent OS state and permission callback were granted; this is not counted as a verified runtime-button denial. A separate **controlled AppOps RECORD_AUDIO=deny** test established real capture rejection: all supported rate/source attempts failed initialization gracefully, noise stayed null, and three AI inferences still succeeded. Original AppOps mode was restored in finally, then actual 16 kHz noise readings and AI both resumed. This validates rejection/error handling, not the unconfirmed prompt-button outcome. See capture-checks.txt, capture-denied.json and capture-restored.json.

Home stopped camera and microphone in both rejected and restored sessions. The audio worker's RELEASED marker was observed after resource release. Eight real one-second aggregate windows from the restored session were retained in noise-windows.jsonl, including rolling energy average and sample peaks; no PCM was retained. Ambient measurements have no confirmed quiet/talking/clapping event labels. Those conditions must not be inferred from level changes alone.

## Remaining human/manual checks

1. Start with microphone enabled, grant Camera and Microphone, remain quiet for 10–15 s, talk near the phone for 10 s, clap once, then leave another quiet interval. Confirm RMS/current dBFS and graph react; peak retains a clap for up to 30 completed windows. Do not save audio. Record only event times and derived levels if comparing conditions.
2. Deny RECORD_AUDIO in the actual Android prompt, including permanent denial; confirm camera inference continues, microphone says permission denied, noise/SPL are unavailable and no automatic re-prompt loop occurs. Retry Start / Android settings to grant and recover. Automated prompt timing did not establish this result.
3. Settings: disable microphone and restart; no microphone resource should open and AI should continue. Reenable, restart and verify. Test calibration checkbox and finite offset bounds; estimates must be absent when disabled, unavailable/stale, or at the numeric silence floor. A reference meter is required before calling the offset calibrated; no physical SPL accuracy was tested.
4. Repeated Start/Stop and Home/return: verify no stale noise values/boxes and that resources reopen; observe diagnostic errors after actual contention with another microphone app. Automatic screen-off capture remains out of scope.
5. Have consenting people stand/sit in the approved camera field: confirm positive-person results and visual box alignment in the actual mounting orientation. Automated runs with zero outputs do not prove positive detection or true empty-room accuracy. Phase 2 visual checks remain open until a suitable person confirms them.
6. Supervise longer simultaneous camera/audio runs, watch RAM/battery temperature, power supply and old battery behavior. Stop if hot/unstable; scripts guard at 42 °C battery temperature. Battery is not CPU temperature. No energy or 24/7 stability result is claimed.

# Phase 5/6 verification — 2026-11-16

## Automated checks

- Command: `./gradlew testDebugUnitTest assembleDebug lintDebug`.
- Result: **BUILD SUCCESSFUL**, **43 tests passed**, 0 failures/errors; lint **0 errors / 88 warnings**. Existing warnings include Android API/version advisories, deprecated API 23-compatible calls, and dependency update recommendations. No dependency was upgraded.
- Final debug APK: `app/build/outputs/apk/debug/app-debug.apk` (8,168,867 bytes), SHA-256 `1a3caafbb2df7d2aa9ba54628279001a267c20aa9df4bf49a07c7458c8eb3376`.

## Physical device

Final APK installed on Samsung SM-N910U / Android 6.0.1 / API 23 / ARMv7. User-started foreground service was visible as `isForeground=true`. Camera2 remained active after Home and after screen-off (`mWakefulness=Asleep`, display OFF); returning to the Activity preserved service state. Model reported Ready with 2 threads and a measured 100.8 ms inference in one sample. An active `dumpsys meminfo` snapshot reported Dalvik Heap PSS 19,497 KiB and EGL mtrack PSS 53,148 KiB; no full process peak was captured. Dashboard Stop removed the service and active Camera2 client. Short USB-powered run observed battery temperature 30.5–33.1 °C. Battery temperature is not CPU temperature. See [PHASE5_REPORT.md](PHASE5_REPORT.md).

The debug-only outbox probe seeded a JSON record and logged its SHA-256, force-stopped the app, then reconnected a new process to a local WebSocket mock. The target UUID replayed and its server-side JSON SHA-256 matched; raw media keys were absent. The final paced run observed two messages and cleaned its `PHASE6-PROBE` rows. This verifies process-restart persistence and payload immutability against a mock, not a real LiveSpace registration.

## Still requires a supervised/manual check

1. Tap **Stop sensor** in the foreground notification itself and verify camera, microphone, interpreter and wake lock release. The dashboard Stop path was tested; notification action was not separately tapped.
2. While backgrounded/screen-off, confirm AudioRecord continues producing readings and a service-owned WebSocket transmits/receives ACKs. No active-track trace or real registered service connection was captured in this run.
3. Recreate the Activity with an OS-driven configuration/process recreation while the service runs; Home/return and screen-off were tested.
4. With a protocol-compatible server unavailable for at least 60 seconds, watch queue depth increase while camera, AI and microphone continue, then restore the server and confirm limited replay. The debug mock verified durable restart replay, but no 60-second sensor outage was conducted.
5. Exercise actual 10,000-row overflow and 1–24 hour retention expiry using the configured queue settings. Verify visible dropped/expired counters.
6. Provide a consenting person for positive person detection and bounding-box visual alignment. No positive-person accuracy result is claimed.
7. Run a longer battery-powered screen-off soak and record charge/thermal behavior; this run was short and USB-powered.
8. Real-backend authentication/history/SSE requires the registered device token and reachable backend. No token was supplied; the local mock was used.

The app never starts capture at boot. User permission and Android background limits remain active. A long-running deployment claim requires the checks above and testing on newer Android versions.

## Approved WebSocket heartbeat compatibility update — 2026-11-16

- `OkHttpClient.pingInterval` is configured to 30 seconds. This sends a WebSocket control frame; OkHttp also automatically responds to peer ping frames. An unanswered ping makes OkHttp fail the socket and uses the existing reconnect/backoff behavior. The test injects a short interval only to avoid a 30-second unit test delay; the production constant remains 30000 ms.
- `WebSocketHeartbeatTest` uses a raw local protocol peer which intentionally ignores the first ping, verifies reconnect, confirms OkHttp's automatic pong to a server-originated ping, and observes the same message ID and byte-for-byte JSON replay from the test queue. No JSON heartbeat was sent. The existing Phase 6 physical force-stop test separately covers SQLite process-restart replay; this JVM heartbeat test injects a bounded in-memory queue.
- Fresh-install defaults: Cloudflare workers.dev WSS endpoint, `LS-NOTE4-001`, location `I`, 15000 ms telemetry. 5000 ms remains configurable for demonstrations. The 3000 ms detector interval remains independent. Existing stored settings are not overwritten. No credential was supplied, so no real Cloudflare or Node backend transaction was made.
- Verification: `./gradlew testDebugUnitTest assembleDebug lintDebug` **BUILD SUCCESSFUL**, 44 tests passed, 0 failed/errors; lint 0 errors / 88 warnings. APK `app/build/outputs/apk/debug/app-debug.apk`, 8,206,398 bytes, SHA-256 `5059509a4d3326b313acef4bcc629bf7982c35f5eae1fbcba29e4d9120ecd54c`. It contains `lib/armeabi-v7a/libtensorflowlite_jni.so` (no arm64 library expected for the target).
- Installed on the connected SM-N910U, and MainActivity launched. ADB reported ARM ABIs `armeabi-v7a,armeabi`; battery was USB-powered at 100%, 30.2 °C. The inspected log slice contained no app `FATAL EXCEPTION`; unrelated Samsung protected sensor-permission messages were present. This launch does not verify live server heartbeat or the screen-off ping exchange.
- Screen-off control heartbeat requires a supervised run with a valid registered token and reachable backend/mock; do not treat the earlier Phase 5 camera/screen-off test as proof of WebSocket pongs. Keep TLS validation enabled and do not log credential/header/payload secrets.
