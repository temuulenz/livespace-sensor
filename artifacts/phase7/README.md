# LiveSpace Sensor Phase 7 demonstration APK

`livespace-sensor-phase7-api23-debug.apk` is the debug-signed university demonstration APK, package `com.livespace.sensor`, minSdk 23, ARMv7. It is not a release-signed store distribution.

Size: 8,230,015 bytes. SHA-256: `c36e39d9fe0c23eb508276e2f925ff4d3e85593e434da3eb908eacbc37bc8015`.

Install/update with `adb install -r livespace-sensor-phase7-api23-debug.apk`. Keep the existing app installed to preserve its Keystore token and sensor settings. Never uninstall or clear application data as part of this update.

TLS compatibility is scoped to API 23 and the exact production WSS endpoint. See `docs/PHASE7_TLS_COMPATIBILITY.md` and `docs/DEMO_GUIDE.md`. No credentials are bundled. Verification scope and remaining manual tests are in `docs/PHASE7_REPORT.md`.
