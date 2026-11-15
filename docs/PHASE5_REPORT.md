# Phase 5 — Foreground sensor service

## Implementation

`SensorService` now owns Camera2 capture, SSD MobileNet/TFLite inference, AudioRecord, the synchronized telemetry snapshot and the optional WebSocket actor. `MainActivity` binds only to observe state and attach an optional TextureView; Activity pause/stop no longer owns or stops the sensors. Start and Stop are explicit user actions. The service returns `START_NOT_STICKY`, has no boot receiver, and does not start camera/microphone on reboot.

The persistent notification reports running and connection state, latest observed people when available, and a Stop sensor action. A partial wake lock is acquired only during a user-started capture run and released after the camera, audio and interpreter owners finish cleanup. On Android 6 the service uses the API 23 `startForeground` path; newer versions use the appropriate foreground-service type API when present and declare camera/microphone foreground permissions. Normal permission checks remain in force.

TextureView is optional and is no longer the analysis owner. On the Note 4's Exynos Camera HAL1, an in-place output-session reconfiguration on TextureView destruction produced `Cannot call configure while in state: 0` and disconnected Camera2. `CameraController` now closes and reopens its CameraDevice around preview surface changes while retaining the ImageReader and inference scheduler. No frames are persisted.

Camera and microphone errors are reported separately. A camera failure closes the detector and leaves audio/network owners available; a microphone failure clears noise and leaves camera inference available. The service does not run an unbounded automatic camera restart loop. A camera failure is surfaced for the operator to retry with Start.

## Physical Note 4 verification

- Foreground status observed in `dumpsys activity services`: `isForeground=true`, notification ID 5101, `startRequested=true`.
- Rear Camera2 active client remained `com.livespace.sensor` after Home and again with display OFF / `mWakefulness=Asleep`. Returning to the Activity restored the dashboard without restarting the service.
- AudioRecord is service-owned and initialized in the service run, but an active-track/sample trace after Home/screen-off was not captured separately. Background microphone continuation remains a manual verification item.
- The dashboard showed model Ready, two CPU threads, 3000 ms interval, and a real inference measurement of 100.8 ms in one live sample. The displayed count was zero in the empty test view; no positive-person accuracy is inferred.
- An active-run `dumpsys meminfo` sample reported Dalvik Heap PSS 19,497 KiB and EGL mtrack PSS 53,148 KiB. A reliable whole-process peak PSS was not captured; these are component snapshots, not a memory benchmark.
- Battery readings during the short run were about 30.5–30.7 °C and later 32.3 °C, USB-powered; after additional test work the device read 33.1 °C / 98%. No thermal threshold was approached. Battery temperature is not CPU temperature.
- Tapping the dashboard Stop control removed the foreground service and `dumpsys media.camera` showed no active client. The independent notification Stop action was present but was not separately tapped in this run.
- No `AndroidRuntime` crash was observed in the filtered checks. The legacy HAL emitted session errors on the pre-fix build; after reopening the CameraDevice around TextureView teardown, Home and screen-off checks retained an active Camera2 client.

## Limits

The physical run was short and USB-powered. It does not establish all-night reliability, battery life, thermal endurance, or behavior under every Samsung power-management state. Home/screen-off camera operation was verified on this Android 6 device; newer Android releases enforce additional foreground-service and notification rules and need device testing. OS permissions are not bypassed. Notification Stop, background microphone trace, explicit activity recreation under memory pressure, and a positive person/bounding-box visual check remain manual checks. A service-owned WebSocket was not exercised in the background with a registered backend token. No 24/7 guarantee is made.
