# Phase 2 — On-device person detection

Implemented on top of Phase 1, preserving Kotlin/XML, Camera2, the existing build toolchain and minSdk 23. No audio, WebSocket, SQLite queue, service or forecasting was added. No commit or push was performed.

## Runtime and verified model

Selected **org.tensorflow:tensorflow-lite:2.8.0**, CPU built-in kernels, configurable 1/2/4 threads, NNAPI and XNNPACK disabled. The physical SM-N910U still runs a 32-bit ARMv7 Android 6.0.1/API 23 OS. Maven AAR inspection, ARMv7 ELF import comparison, APK contents and real interpreter allocation/invocation establish compatibility. TFLite 2.14.0 was rejected after its native library failed loading `__aeabi_memcpy4` with the incompatible LIBC_N version. The unchanged 2.8.0 ARMv7 library is actually packaged. No arm64 assumption or minSdk override was used.

Genuine TensorFlow-hosted COCO SSD MobileNet V1 quantized model, dated 2018-06-29, 4,183,312 bytes. Publisher model listing declares Apache 2.0. Model source, licensing context, exact tensors, labels, operators, runtime/AAR hashes and minimal device probe are documented in [MODEL_PROFILE.md](MODEL_PROFILE.md). The ZIP lacks embedded licensing/metadata, so the publisher's license declaration and bundled Apache/attribution notice are documented explicitly.

Model SHA-256: `e4b118e5e4531945de2e659742c7c590f7536f8d0ed26d135abcfe83b4779d13`.

Input: UINT8 [1,300,300,3], scale 0.0078125, zero point 128; feed RGB bytes directly. This is the canonical unsigned 8-bit quantized V1 model, not signed INT8 I/O. Outputs FLOAT32 [1,10,4] boxes, [1,10] classes, [1,10] scores and [1] valid count, with no output quantization. The allocated shapes were read on the phone. Person is output class 0, label line/index 1 after the leading background label. The model includes detection postprocess/NMS and needs no redundant NMS or Flex library.

## Implementation and changes

- Added `detection/DetectionResult.kt`, `ImagePreprocessor.kt`, `FrameGeometry.kt`, `PersonDetector.kt`, `InferenceScheduler.kt`, `OccupancyTracker.kt`, `PerformanceStats.kt` and `ui/DetectionOverlayView.kt`.
- Updated `camera/CameraController.kt`: local image callback, measured frame metadata, sensor/display rotation, cleanup ordering and deferred TextureView-buffer release.
- Updated `MainActivity.kt` and `res/layout/activity_main.xml`: boxes, real raw/smoothed counts and confidence, model/error status, inference latency/frequency, diagnostic stage timings/percentiles/PSS/counters/UTC time, validated settings and pending runtime-permission handling.
- Added model, original labels, license and source notice in `app/src/main/assets/`; no media test fixtures.
- Updated `app/build.gradle.kts` for pinned runtime, ARMv7 packaging, uncompressed model and JUnit tests; added documented supported AGP 9.x shared-namespace switch in `gradle.properties`. Manifest disables legacy backup explicitly. No unrelated dependency/toolchain upgrades.
- Added debug-only RuntimeProbeActivity/manifest, eight focused tests in `DetectionMathTest.kt`, reproducible host `tools/inspect_model.py`, `benchmark_device.py`, `summarize_benchmarks.py`, `lifecycle_smoke.py` and text/JSON evidence.
- Updated AGENTS.md, README.md, ARCHITECTURE.md, DEVICE_PROFILE.md, TESTING.md and ROADMAP.md; created MODEL_PROFILE.md and this report. Historical Phase 1 report remains intact.

Camera images are acquired with acquireLatestImage and always closed using use. Sampling checks happen before preparation. YUV planes honor crop, row stride, pixel stride and initial buffer positions. Nearest-neighbor sampled conversion prepares only the required model pixels, rotates upright and reuses input/output buffers; no Bitmaps or full-resolution RGB copies. Cropped model boxes map back to the upright full preview. Fit-center preview and overlay share one geometry contract.

An atomic flag bounds preparation/inference to one in-flight input. The interpreter is created/invoked/closed on its worker; Stop queues close behind pending invocation, and activity generations ignore late callbacks. Median smoothing uses up to five raw counts and never accumulates identities or people across frames. Monitoring area uses box-center inclusion. Missing/stopped/stale/differently rotated observations are unavailable; valid zero-person results are actual model outputs. Mean confidence is the current retained-person score summary, null when no person is retained, not a calibrated accuracy measure.

## Build and installation

`./gradlew assembleDebug lintDebug testDebugUnitTest`: **BUILD SUCCESSFUL**. Eight JUnit tests pass, no failures/errors/skips. Lint: **0 errors / 54 warnings**. Relevant API/lifecycle/geometry issues were addressed; remaining warnings include localization/UI/template notices, pinned older versions, excluded ChromeOS ABIs and unbundled x86_64 dependency alignment. The old runtime is not claimed suitable for modern 16 KB-page/store deployment. Shared runtime/API namespace and inability to strip the supplied native binary remain documented build warnings; the native hash is verified.

Final APK: `app/build/outputs/apk/debug/app-debug.apk`, 7,906,731 bytes. SHA-256 `48853d42f13447e566312af082d405db7d54300f155ddb5c09a1e40270989168`. Final `adb install -r`: Success. Physical model/camera/inference startup succeeded. See `evidence/build-summary.txt` and `apk-native.txt`.

## Benchmark interpretation

The benchmark table below contains complete 30-sample configurations only. Median uses the average of two middle values; p95 uses nearest rank, includes first invocation and does not silently remove warm-up. Stage definitions and reproducible commands are in TESTING.md. Process PSS is sampled memory, not guaranteed peak allocation; battery temperature is not CPU temperature. These are supervised short runs with preview enabled and USB charging, not power or accuracy benchmarks.

The first four complete configurations used the integrated pipeline before final texture/crop/percentile refinements; later 5-second and repeated 3-second runs used the final APK. Runtime/model/preprocessing method are unchanged; the final default was reverified separately. All complete runs and their IDs are retained in evidence/benchmark-summary.json. Interrupted runs remain in raw evidence but are excluded: 12 samples after a USB reconnect and 18 samples before display sleep. Neither is counted as a successful 30-sample configuration.

Recommended provisional default remains **2 CPU threads, 3000 ms, threshold 0.5**. Four threads improved latency modestly in the earlier run, with no measured battery/energy justification for changing the default. CPU scheduling/frequency, thermal state, scene and phone background work were uncontrolled; the runs were sequential, not randomized. A 5-second interval is available when slower updates are acceptable, but power savings were not measured.

| Threads | Interval | n | Init ms | Pre median/p95 ms | Invoke median/p95 ms | Post median/p95 ms | Total median/p95 ms | PSS MiB | Battery °C | Hz | Failures |
|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| 1 | 3 s | 30 | 17.9 | 148.1/155.8 | 242.2/251.3 | 0.1/0.6 | 389.6/402.5 | 26.4–44.7 | 32.7–32.9 | 0.331 | 0 |
| 2 | 3 s | 30 | 24.4 | 150.4/155.4 | 143.4/193.6 | 0.1/0.6 | 291.1/349.2 | 26.3–44.8 | 33.0–33.3 | 0.331 | 0 |
| 4 | 3 s | 30 | 15.9 | 151.0/160.2 | 135.6/163.5 | 0.1/0.6 | 281.0/306.2 | 26.3–44.6 | 33.3–33.7 | 0.331 | 0 |
| 2 | 2 s | 30 | 16.3 | 148.2/158.4 | 165.4/198.3 | 0.1/0.6 | 316.4/349.0 | 26.3–45.7 | 33.7–34.3 | 0.494 | 0 |
| 2 | 5 s | 30 | 27.8 | 147.7/157.3 | 177.5/195.0 | 0.1/0.6 | 328.1/348.3 | 26.6–44.4 | 34.7–35.0 | 0.199 | 0 |
| 2 | 3 s | 30 | 13.4 | 148.1/156.4 | 146.3/197.0 | 0.1/0.6 | 295.4/351.5 | 26.3–44.7 | 34.8–35.0 | 0.331 | 0 |

Complete runs: **180 successful camera inferences, zero reported inference failures**. Process PSS **26.3–45.7 MiB**, battery temperature **32.7–35.0 °C** across complete samples; no thermal guard was triggered. Initialization was **13.4–27.8 ms**. Final default actual frequency **0.331 Hz**. Interrupted runs are additional partial samples, not included in these totals. All complete-run person counts were zero in the current view; no accuracy conclusion follows.

## Limitations and Phase 3 readiness

All benchmark outputs in the current view were zero retained people; successful invocation alone does **not** establish positive-person accuracy or box alignment. Visual tests with consenting subjects, varied seating/lighting/occlusion, monitoring-area boundaries and real rotations remain mandatory. Geometry and stride math are unit tested; legacy-HAL preview appearance must still be confirmed by a human. The physical stream was 640×480; synthetic tests cover non-full crops, which were not separately measured on the phone.

The model has a maximum of 10 detections across all classes and can undercount crowded, distant or occluded people. Five-sample smoothing delays changes. Observed people are not total cafe occupancy or available seats. Long-running memory stability, energy use, CPU temperature and 24/7 reliability are not established. Battery was USB powered/charging; the old battery and supply need deployment review. Normal screen-off operation is deliberately absent.

Phase 3 audio work can proceed from this verified CPU inference foundation after authorization. First complete the visual/privacy/deployment checks in TESTING.md, then implement AudioRecord RMS/dBFS and test simultaneous camera/microphone load and thermal behavior. Do not add transport/service/forecasting work as part of Phase 2.

## Additional lifecycle evidence

On the final APK, three explicit Start/Stop cycles each reached a real inference and then released the camera; Home/return left the model closed and camera released. Temporary landscape orientation reinitialized camera/model and completed three extra camera inferences; original rotation settings were restored. Text-only evidence: `evidence/lifecycle-smoke.txt`, `evidence/rotation-smoke.jsonl`. Permission-denial validation is reported separately: the first combined attempt did not establish denial because OS permission was subsequently granted. Preview/box alignment remains a human visual check.

Final permission result: denial and permanent denial remain **unverified manual checks**. The combined denial assertion did not pass; an isolated retry could not find a stable Deny control, and OS permission was granted at follow-up. Grant state/camera use are verified, but a denial-pass claim is not made. Final filtered application error log was empty; installed app was relaunched normally with sensing stopped and no active camera client.

Memory qualification: an independent running-app `dumpsys meminfo` snapshot reported **89.9 MiB total PSS, including 51.9 MiB EGL/graphics, plus 5.7 MiB swap**. The table's Debug.getPss() samples do not describe a full graphics-inclusive footprint or peak. Final battery snapshot was **26%, USB powered/charging flag, 35.7 °C**; decline from about 30% despite USB means supply/battery adequacy needs review. No controlled power/battery-health result or CPU-temperature measurement is claimed. See evidence/final-device-snapshot.txt.
