# Phase 3 — Real-time acoustic monitoring

Implemented and physically verified on Samsung SM-N910U, Android 6.0.1/API 23, ARMv7, on 2026-11-15/16. The original Camera2/detector implementation, TFLite 2.8.0, UINT8 SSD MobileNet V1 asset, two-thread/3000 ms/0.5 defaults and XML dashboard/settings were preserved. No dependency upgrade, networking, service, offline queue, forecasting, commit or push.

## Delivered functionality and files

- Added `audio/AudioMonitor.kt`: dedicated AudioRecord owner thread; mono PCM16; prefer 16000 Hz with 44100/48000/8000 fallback and VOICE_RECOGNITION/MIC source fallback; validated minimum/actual initialization; even-aligned native buffer at least twice getMinBufferSize and 500 ms; reusable 100 ms read array, API 23 nonblocking reads, bounded polling, explicit error reporting and finally stop/release.
- Added `audio/NoiseMath.kt`, `NoiseReading.kt`: normalized RMS, finite dBFS, one-second sample windows, bounded 30-window energy average and absolute PCM sample peak, optional reference-based SPL offset. No raw PCM leaves the capture class or goes to disk/logs/network.
- Added `ui/NoiseHistoryView.kt`: bounded sixty-window scalar dBFS graph. Updated MainActivity/XML with microphone state, RMS/dBFS, average/peak, calibration/nullable estimated SPL and microphone/calibration settings. Audio uses the existing one-second dashboard cadence rather than a second refresh timer.
- Added `model/SensorSnapshot.kt`: synchronized TelemetryStore/TelemetryRepository and immutable snapshot with required acoustic/AI/battery fields and producer timestamps. Monotonic freshness: audio 3 s, detection max(2×interval,5 s), battery 30 s. Stop/error clears data; generation checks ignore late callbacks. Phase 4 can read the repository from a worker; no transport or serializer contract is implemented.
- Extended DeviceMonitor with typed nullable battery readings. Added RECORD_AUDIO/optional microphone hardware to manifest; app version 0.3.0 (code 3), minSdk 23. Updated AGENTS/README/architecture/testing/device/protocol/roadmap documentation.
- Added nine acoustic and four snapshot unit tests, plus reproducible aggregate-only benchmark/capture/lifecycle tools and evidence under `docs/evidence/phase3/`. Existing eight detector tests remain.

Microphone runtime denial leaves camera available; disabled microphone is not requested/opened. Capture initialization/read errors clear noise and show an error while AI continues. Stop/Home closes both sensor pipelines, with microphone stop/release on its owner after a short nonblocking poll, not a UI-thread join. Stale/stopped measurements are unavailable, not fabricated zeroes.

## Acoustic definitions and limits

RMS = sqrt(mean((PCM16/32768)^2)); dBFS = 20 log10(RMS), with a −120 dBFS numeric floor at RMS ≤1e−6. RMS=0 remains zero; no NaN/Infinity is emitted. The floor is a representation convention, not physical sensitivity. Average is linear mean-square energy across up to thirty completed one-second windows; peak is the maximum absolute PCM sample across the same windows. Startup displays its actual window count. These are neither A-weighted SPL nor peak acoustic sound pressure.

Calibration is disabled by default. With an operator-supplied finite offset from −200 to 200 dB, estimatedDbSpl = dBFS + offset; silence/floor-limited input remains null. Offset should be reference SPL − simultaneous dBFS under stable gain/source/placement. No reference sound meter or certified acoustic calibration was available. Android/vendor gain and processing can affect levels even with VOICE_RECOGNITION; hardware processing is not claimed disabled or characterized.

The snapshot's timestampUtcMs is UTC epoch milliseconds; individual producer timestamps disclose sample age. Confidence is the current retained-person mean score, while observed people is smoothed. The snapshot is thread-safe and finite by construction/testing, but is not yet a backend wire schema or occupancy estimate for the whole cafe.

## Build, APK and compatibility

Final command: `./gradlew assembleDebug lintDebug testDebugUnitTest` — **BUILD SUCCESSFUL; 21 tests passed, zero failed/skipped; lint 0 errors / 56 warnings**. A temporary SuspiciousIndentation lint error during instrumentation was corrected without suppression. Remaining warnings include prior localized-string/template/UI notices, pinned version/native alignment/ABI suggestions and deliberate API 23 compatibility choices.

APK: `app/build/outputs/apk/debug/app-debug.apk`, **7,552,753 bytes**. SHA-256 `ef5470122461195e1e2cf2292efc77d55db251bf7e5f087277cc31f18819c3ea`. Final install: **Success**. Only ARMv7 JNI is packaged. Original model SHA-256 remains `e4b118e5e4531945de2e659742c7c590f7536f8d0ed26d135abcfe83b4779d13`; native runtime SHA-256 remains `b34b0fe7d20ef29adacf68886ef6bb402cd0aff905feefe56fe1083ec0c7aefe`. Evidence: build-artifact.json.

## Physical results

- AudioRecord opened at **16000 Hz mono PCM16**, **VOICE_RECOGNITION**, native buffer **16000 bytes**. Fallbacks were implemented but not needed/claimed physically tested.
- Camera and microphone worked simultaneously. Three complete 30-inference runs produced **90 successful AI inferences with zero reported inference failures**: sixty with audio enabled and thirty disabled. All sixty enabled-run samples included real RMS/dBFS; disabled-run noise values were null.
- Final-build audio-enabled RMS range **0.000220–0.001055**, current dBFS **−73.14 to −59.54**. These are unlabelled ambient measurements, not verified quiet/talking/clapping conditions. Eight separate real one-second aggregate windows retained average/peak/uncalibrated-null-SPL values; median timestamp spacing **996 ms**.
- The real RECORD_AUDIO prompt appeared. An attempted Deny interaction ended with OS permission/callback granted; **runtime-button denial is not claimed verified**. A separate controlled **AppOps capture denial** was verified: microphone initialization failed with a clear error and null noise, while three AI inferences succeeded. The original allow mode was restored in finally and three more AI inferences plus real microphone readings succeeded. This tests native rejection/recovery, not the unresolved prompt timing.
- Three explicit Start/Stop cycles passed: AI and 16 kHz noise became valid, Stop released camera/microphone, no further inference was published. Home/return left both released. Release markers occur after AudioRecord.release. Evidence: lifecycle-checks.txt, capture-checks.txt and associated aggregate logs.
- Final error-log inspection contained no current AndroidRuntime/camera/detector/audio error entries. The deliberately induced capture-denied error was observed earlier and is documented, not counted as a spontaneous failure. Original AppOps allow state was confirmed. App was launched normally with sensing stopped and camera client list empty.

## Performance measurements

Each row includes all thirty samples, including startup; median averages middle values and p95 uses nearest rank. Two initial comparison runs used the first Phase 3 build; final audio-on repeats after one-second UI/coherent-snapshot refinements. Same model, two CPU threads, 3000 ms and 0.5 threshold throughout. Sequential scene/CPU scheduling/frequency/charging conditions were uncontrolled; do not interpret differences as isolated audio overhead or power savings.

| Run | Audio | n | AI median/p95 ms | Total median/p95 ms | Valid audio samples | Battery °C | Debug PSS MiB |
|---|---|---:|---:|---:|---:|---:|---:|
| ai-audio-final.jsonl | on | 30 | 169.2/194.5 | 317.5/341.1 | 30 | 32.7–33.0 | 26.4–44.9 |
| ai-audio-off.jsonl | off | 30 | 145.6/212.0 | 297.7/354.8 | 0 | 32.0–32.4 | 26.0–44.3 |
| ai-audio-on.jsonl | on | 30 | 153.8/197.3 | 304.9/352.4 | 30 | 31.6–32.0 | 26.5–45.1 |

Actual inference frequency was about **0.331 Hz** in all three runs. Final preprocessing median/p95 and full timing details are in summary.json. Debug.getPss ranged about **26.0–45.1 MiB**; this is not a full graphics-inclusive/peak footprint on this device, as Phase 2 established. No extended memory-leak or power test is claimed.

Complete benchmark battery samples were **31.6–33.0 °C**. Initial session battery was **27%, 34.4 °C**; final check **36%, 33.4 °C**, USB powered with charging flag throughout those snapshots. Battery percentage rose this session; no controlled charge-rate/power or battery-health conclusion follows. The 42 °C benchmark guard never triggered. Battery temperature is not CPU temperature, and thermal protections were not altered.

## Remaining manual checks and next phase

No confirmed human participation was available for a labelled quiet interval, talking, clapping, positive-person accuracy or visual box alignment. Those tests remain explicitly open in TESTING.md; no sound event, true empty-room state or person accuracy is inferred from scalar changes/zero outputs. Runtime prompt denial/permanent denial, settings persistence/calibration against a real reference, external microphone contention and extended simultaneous stability also need manual validation. Synthetic waveform unit tests are math tests, not physical acoustic evidence.

Recommended defaults: retain two AI threads / 3000 ms / threshold 0.5; microphone enabled when consent/permission allows; 16000 Hz mono PCM16 preferred; one-second windows, thirty-window energy average/peak; calibration off until a reference offset is established. No SPL accuracy, available seats, total cafe occupancy, 24/7 or screen-off reliability claim.

The local data producers and synchronized snapshot are ready for a separately authorized Phase 4. First inspect the actual backend and agree authentication/schema/acknowledgement before implementing WebSocket transport. **Stop here: Phase 4 has not been implemented.**
