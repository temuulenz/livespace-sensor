# LiveSpace Sensor

Native Kotlin/XML Android edge sensor for LiveSpace, Ulaanbaatar. Phases 1–6 provide local Camera2 person detection, AudioRecord noise monitoring, protocol-v1 WebSocket telemetry, an explicit foreground sensor service, and a bounded SQLite offline outbox. Physically verified on Samsung SM-N910U, Android 6.0.1/API 23, ARMv7. Read the phase reports for device limits; this is not an unattended 24/7 reliability claim.

```sh
./gradlew assembleDebug lintDebug testDebugUnitTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.livespace.sensor/.MainActivity
```

Use the existing Android Studio JBR/SDK and Gradle 9.6.0 / AGP 9.5.1 toolchain. Minimum SDK remains 23. TFLite **2.8.0**, the genuine quantized UINT8 SSD MobileNet V1 and ARMv7-only packaging remain unchanged. Default inference is two CPU threads, 3000 ms, confidence 0.5. See [model/runtime compatibility](docs/MODEL_PROFILE.md) before changing them.

Press Start sensor and allow Camera/Microphone. Microphone is optional; denying it leaves camera inference available. The persistent foreground service continues after Home and screen-off on the tested Note 4. Stop using either the dashboard control or notification action. Configure the registered endpoint/token under Server and credentials, then Connect / test WebSocket. A fresh install defaults to the configured workers.dev WSS URL, device `LS-NOTE4-001`, location `I`, and 15-second continuous telemetry. Set 5000 ms for short demonstrations. The app waits for backend `sensor.ready` before sending and uses 30-second WebSocket control pings independently of telemetry and 3000 ms inference. WSS uses normal certificate validation; private-LAN `ws://` requires explicit opt-in.

Dashboard: local preview/boxes, smoothed observed people, current raw count/confidence, inference timing/frequency, microphone status, normalized RMS, current dBFS, rolling energy average, rolling sample peak, calibration state, optional estimated SPL, a bounded noise history graph, battery and device diagnostics. Stopped/stale/unavailable sensors show unavailable rather than artificial zeroes.

Settings preserve device/location, inference, monitoring area and preview controls. They add microphone enable/disable and optional manual SPL calibration. Calibration is **off by default**. Set offset = reference meter SPL − simultaneous dBFS, then enable only after a reference measurement under fixed gain/placement. Estimated SPL is not A-weighted or certified; zero/floor-limited digital input has no SPL estimate. Uncalibrated microphone levels are **dBFS, not dB SPL**.

Audio uses mono PCM16, prefers 16000 Hz and has compatible fallback rates/sources. One-second RMS windows feed a 30-window energy average and maximum absolute sample peak. A −120 dBFS numeric floor avoids infinities; it does not imply physical microphone precision. Noise history holds aggregate values only, never PCM. Neither camera pixels nor microphone recordings are saved, logged or transmitted. Debug verification logs contain derived metrics only.

Observed people are visible detections, not total cafe occupancy or available seats. The model has at most ten detections across all classes. Positive-person accuracy, box alignment, and quiet/talking/clapping response still require confirmed human testing; see [manual checklist](docs/TESTING.md).

`TelemetryRepository.store.snapshot(utcMs, elapsedMs)` exposes a synchronized immutable snapshot used by the v1 serializer: observed count/confidence, inference latency, RMS/dBFS, nullable estimated SPL/calibration, battery percent/temperature/charging, UTC snapshot time, producer timestamps and uptime. Missing/stale values are null. The transport uses OkHttp 4.12.0, AndroidKeyStore token encryption and an app-private SQLite outbox capped at 10,000 messages / 24 hours by default. Queue cap and age are configurable in Server and credentials. Only accepted/duplicate ACKs delete a row. See [Phase 4 report](docs/PHASE4_REPORT.md), [Phase 5 report](docs/PHASE5_REPORT.md), [Phase 6 report](docs/PHASE6_REPORT.md), [connection setup](docs/PHASE4_CONNECTIONS.md) and authoritative [sensor protocol](docs/SENSOR_PROTOCOL.md).

Source: `MainActivity.kt`, `camera/`, `detection/`, `audio/`, `device/`, `model/`, `ui/`, XML layouts and verified model assets. Debug-only interpreter/benchmark tools are excluded or gated in release builds. No credentials belong in the repository.

Reproducible supervised physical checks:

```sh
python3 tools/benchmark_device.py --configs 2:3000 --microphone on --output docs/evidence/phase3/ai-audio-final.jsonl
python3 tools/phase3_capture_checks.py
python3 tools/phase3_lifecycle_checks.py
python3 tools/summarize_phase3.py
```

Capture-rejection checks temporarily deny RECORD_AUDIO through AppOps and restore its original mode in finally. Lifecycle tap coordinates target the verified Note 4 portrait layout. Keep the app foreground and device orientation fixed during benchmarks. Scripts stop at 42 °C battery temperature; battery temperature is not CPU temperature. These checks save only text/aggregate metrics, never media.

Read the [Phase 3 report](docs/PHASE3_REPORT.md), [Phase 4 report](docs/PHASE4_REPORT.md), [architecture](docs/ARCHITECTURE.md), [testing evidence](docs/TESTING.md), [device profile](docs/DEVICE_PROFILE.md), [frozen protocol](docs/SENSOR_PROTOCOL.md), [roadmap](docs/ROADMAP.md) and [permanent requirements](AGENTS.md). No unattended/24-hour reliability or calibrated acoustic accuracy is claimed. No commits/pushes are automatic.
