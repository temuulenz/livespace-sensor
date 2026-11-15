# Verified device profile

Read through authorized USB ADB on 2026-11-15. Values are a snapshot, not constants.

| Property | Verified value |
|---|---|
| Model | Samsung SM-N910U (Galaxy Note 4) |
| Board platform | exynos5 |
| Android | 6.0.1, API 23 |
| CPU ABIs | armeabi-v7a, armeabi (32-bit; no arm64 reported) |
| Kernel-reported total memory | 2,879,120 kB (~2.75 GiB) |
| Battery at inspection | 22%, USB powered, charging, 35.3 °C |
| Cameras | Rear 0, orientation 90°; front 1, orientation 270° |
| Camera HAL | Exynos Camera HAL1; Camera2 uses legacy compatibility shim |
| Rear advertised preview | Includes 640×480 and 320×240 |

Sources: `adb devices -l`, `getprop ro.product.model`, `getprop ro.board.platform`, `getprop ro.product.cpu.abilist`, `getprop ro.build.version.sdk`, `getprop ro.build.version.release`, `/proc/meminfo`, `dumpsys battery`, `dumpsys media.camera`.

The camera dump advertises Camera1 parameters. The app separately enumerates Camera2 YUV sizes in Diagnostics; successful Camera2 capture must be established by runtime testing. CPU core details and specific Exynos SKU have not been established from the board name alone.

Host: Fedora Linux; installed Android SDK platform android-37.0; Android Studio bundled JBR 25.0.4; ADB 37.0.1; project Gradle wrapper 9.6.0 / Android Gradle plugin 9.5.1. This phase retains the existing build toolchain and removes Compose dependencies. No native libraries are packaged in Phase 1.

## App Camera2 verification

Physical capture succeeded at 640×480 using YUV_420_888 on the legacy shim. App characteristics reported hardware level 2 (LEGACY) for both cameras.

Rear Camera2 YUV outputs: 1920×1080, 1440×1080, 1088×1088, 1280×720, 1024×768, 1056×704, 960×720, 720×720, 800×450, 720×480, 640×480, 352×288, 320×240, 256×144, 176×144.

Front Camera2 YUV outputs: 1920×1080, 1440×1080, 1072×1072, 1280×720, 1056×704, 960×720, 720×720, 800×450, 720×480, 640×480, 352×288, 320×240, 176×144. Front capture was not tested; application deliberately selects rear only.

## Phase 2 runtime and benchmark observations

The phone remained API 23 with a 32-bit armeabi-v7a operating system. No arm64 runtime was assumed. TFLite 2.14.0's ARMv7 ELF imported `__aeabi_memcpy4@LIBC_N`; this Samsung libc exports that name under LIBC_PRIVATE, and the runtime failed loading. TFLite 2.8.0's unversioned reference loaded and invoked the unchanged SSD MobileNet V1 model successfully. See MODEL_PROFILE.md and evidence/native-symbols.txt.

Battery before Phase 2 probing: 25%, USB powered/charging, 32.7 °C. Before camera benchmarks: about 30%, USB powered/charging, 31.7–31.8 °C. Values change during use. Battery temperature is not CPU temperature. A USB reconnect interrupted one initial benchmark; its cause was not established. Another incomplete run ended when the screen was off; the foreground-only sensor correctly relinquished the camera. Neither event is evidence of thermal throttling or a model crash. Final benchmark ranges are in PHASE2_REPORT.md / evidence/BENCHMARKS.md.

Debug benchmarks keep their own window awake and stop at the selected conservative 42 °C battery-temperature limit. Normal sensor operation does not claim screen-off capture; power/Doze/service validation remains in later phases.

Complete Phase 2 benchmark sample ranges: process PSS 26.3–45.7 MiB, battery 32.7–35.0 °C, initialization 13.4–27.8 ms across six 30-sample runs (180 successful inferences, zero reported inference failures). A paused/incomplete run's host checks reached 35.2 °C; no 42 °C guard triggered. These do not measure CPU temperature or prove extended memory/thermal stability. The user identifies the chipset as Exynos 5433; this session's ADB board property independently confirms exynos5, not the exact SKU.

Independent final running-app dumpsys snapshot: total PSS 92,060 kB (~89.9 MiB), EGL/graphics 53,148 kB (~51.9 MiB), swap 5,844 kB. Per-sample Debug.getPss() figures omit a full graphics-inclusive view on this device; they are not total/peak memory guarantees. Final battery: 26%, USB powered/charging flag, 35.7 °C. Percentage declined from about 30% despite USB, so supply/battery adequacy under load remains unverified; no controlled energy measurement was performed.

## Phase 3 audio

AudioRecord physically initialized at 16000 Hz, mono PCM16, VOICE_RECOGNITION source, buffer size 16000 bytes. API 23 nonblocking reads and the unchanged ARMv7 TFLite 2.8.0 model/runtime operated simultaneously. Fallback rates/sources exist but were not needed in normal capture. Controlled AppOps rejection left the microphone uninitialized with a clear error while AI inference continued; restoring the original allow mode restored capture.

Initial Phase 3 battery snapshot: 27%, USB charging flag, 34.4 °C. Complete benchmark sample temperatures and memory are in evidence/phase3/summary.json; Debug.getPss is not the full graphics-inclusive footprint, as established in Phase 2. No SPL reference-meter calibration, CPU-temperature measurement or controlled power-consumption test was performed.

## Phase 7 API 23 production TLS compatibility

The Note 4 lacks ISRG system trust anchors. Production WSS now uses app-bundled, fingerprint-verified ISRG Root X1 through platform path validation only on API 23 and the exact production endpoint; default hostname checks remain enabled. Other endpoints/newer Android retain system TLS defaults. Physical trust-anchor failure, corrected TLS 1.2, negative certificate/hostname checks, authenticated ready/accepted ACKs and public real-source updates were verified. See [PHASE7_TLS_COMPATIBILITY.md](PHASE7_TLS_COMPATIBILITY.md) for provenance, implementation, tests, evidence and remaining Phase 7 checks.
