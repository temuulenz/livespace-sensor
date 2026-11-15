# LiveSpace Sensor university demonstration

## Before the demonstration

- Use the physical Note 4 registered as `LS-NOTE4-001`, location `I`, real sensor. Confirm the app's endpoint is `wss://livespace.temuulenbayarjargal.workers.dev/ws/sensors`, the telemetry interval is 15000 ms, and the 30000 ms protocol heartbeat is independent of the 3000 ms inference interval.
- The production token is already saved. Keep the existing app data and leave the token field blank when reviewing settings. If reprovisioning is necessary, enter the token from the private mode-0600 file directly in the Android app's Server and credentials settings. Do not send it in chat, display it, or paste it into a terminal command. Android stores it through Android Keystore. A blank field keeps the stored token.
- Connect the phone to reliable Wi-Fi. Confirm its clock is correct, battery is healthy, permissions are granted, camera view is mounted safely, and an appropriate power supply is available. A USB-powered test is not evidence of battery-only endurance.
- Open the Android dashboard, public cafe page `https://livespace.temuulenbayarjargal.workers.dev/#cafe-I`, and the admin page `https://livespace.temuulenbayarjargal.workers.dev/#admin` in separate tabs. Admin diagnostics require the existing private admin account. Do not modify account or device provisioning during the demo.
- Confirm the public real-source history baseline before capture. The website updates applicable live views by automatic polling (currently about every 10 seconds); do not describe it as instantaneous push.
- Obtain informed consent from people who may enter the camera's field of view. Frames and microphone samples are processed on-device only; only structured JSON telemetry is sent. Do not save images/audio. Treat observed people as visible detections, not total cafe occupancy. dBFS is not calibrated dB SPL.

## 3–5 minute sequence

1. **Start (30–45 s):** Open LiveSpace Sensor and press **Start sensor**. Grant camera/microphone permissions if Android asks. Show the local preview, model ready state, model settings (2 threads, confidence 0.5, 3000 ms), microphone status, battery and persistent notification.
2. **Connect (30–45 s):** Press **Connect**. Show `CONNECTED`, the configured `LS-NOTE4-001` / `I` identity, then sent and acknowledged counters increasing. The transport permits sending only after matching `sensor.ready`; the Phase 7 debug evidence records this gate explicitly. Confirm pending queue stays near zero after accepted ACKs. If anything says `AUTH_FAILED`, stop and correct registration/token privately; do not repeatedly retry or switch to simulator credentials.
3. **People (30–45 s):** Have a consenting person enter the rear-camera field, pause, then leave. Show observed count, confidence, inference latency and box alignment on the phone. State clearly that count covers only visible detections within the configured area.
4. **Sound (30–45 s):** Show current RMS/dBFS, then keep quiet briefly, speak near the microphone, and clap once at a safe distance. Show derived levels changing. Call the unit dBFS; show estimated SPL only if a valid calibration is already configured. Never claim the raw uncalibrated value is SPL.
5. **Website and history (45–60 s):** Show the public cafe I page update after its polling cycle and identify the source as real. Open real-source history. In the signed-in admin dashboard, show the device's last-seen/diagnostic state and history. Confirm telemetry timestamps, device ID and real source match the Android view. Public endpoints intentionally omit private device diagnostics.
6. **Background (30–60 s):** Keep the user-started foreground service active, press Home, and turn the display off briefly. Show the persistent notification if available, then unlock and reopen the app to show the same service/connection state. Do not claim a five-minute screen-off soak unless it actually ran. End with **Stop sensor** and confirm camera/audio/service state stops.

At 15-second cadence, a five-minute run generates about 20 fresh observations, before reconnect retries. Use 5000 ms only for a short explicitly bounded demo; restore 15000 ms after the demo to limit Free D1 usage. Keep the 3000 ms inference cadence unchanged.

## Fallback plan

- If the phone, network, permission, or production connection fails, explain the limitation and use only previously persisted readings whose page labels identify them as **simulated**. The separate `LS-SIM-DEMO-001` registration is simulated and must never share the phone token or be presented as real.
- Do not start an unnecessary production simulator or create more Cloudflare resources. If a simulator is deliberately used for a separate rehearsal, disclose its simulated source on screen and stop it immediately after that short test.
- If no valid simulated rows are available, show the dashboard and clearly labeled static/empty history, then describe the intended data flow. Never manufacture readings or portray mock telemetry as observations from the physical sensor.
- If the phone overheats, the battery swells, inference stalls, queue grows unexpectedly, or authentication fails, stop the sensor and switch to the fallback. Do not disable Android thermal protections or TLS validation.

## Presenter checklist

- [ ] Production token stays in protected provisioning storage and Android Keystore; no credential is visible in a terminal, log, screen recording, or presentation.
- [ ] Device says `LS-NOTE4-001`, location `I`, source real, 15000 ms telemetry.
- [ ] Website update is described as polling, not instantaneous SSE if the deployed UI uses polling.
- [ ] Person and audio actions are performed by consenting participants.
- [ ] dBFS is not called dB SPL without calibration.
- [ ] Fallback is visibly marked simulated.
- [ ] Stop action releases sensors and network when the demo ends.

## Prepared university APK and secure transport

Use [livespace-sensor-phase7-api23-debug.apk](../artifacts/phase7/livespace-sensor-phase7-api23-debug.apk), the debug-signed ARMv7/API 23 demonstration build. It is installed on the Note 4 by an in-place `adb install -r`; do not uninstall or clear data. SHA-256 is in [SHA256SUMS](../artifacts/phase7/SHA256SUMS). This is a demonstration build, not a release-signed store package.

On Android 6 the app adds official ISRG Root X1 trust only for the exact production WSS endpoint. Certificate-path and hostname validation remain enabled. Other endpoints and newer Android versions retain system trust. Never bypass a certificate warning or change to insecure WS for the demonstration. See [TLS implementation and evidence](PHASE7_TLS_COMPATIBILITY.md).

## Manual validation worksheet

Record actual observations; leave unfinished items pending. Do not photograph or record camera/audio output for evidence.

| Check | Action | Record |
| --- | --- | --- |
| Positive person | Empty view 10 s, consenting person enters and pauses 10 s, then exits 10 s | Actual people and displayed counts before/during/after; lighting/distance; missed or extra boxes |
| Orientation | Repeat while holding portrait and landscape | Upright preview, full-field fit-center view and aligned boxes in each orientation |
| Quiet | Remain quiet for 10 s | Current dBFS range and rolling average/peak |
| Speech | Normal speech for 10 s | dBFS range and change from quiet |
| Clap | One clap at a comfortable distance | Rolling peak response; an instantaneous event may fall between 15-second website samples |
| Website | Keep cafe I open for at least two polling cycles | REAL / online, timestamp changes, observed people and dBFS; null SPL when uncalibrated |

The detector emits at most ten outputs. Positive counts, confidence values and successful data delivery do not establish calibrated accuracy or total venue occupancy. Inspect actual people in view before claiming a count is correct.

For the five-minute screen-off and controlled outage evidence, use the verified results in [PHASE7_REPORT.md](PHASE7_REPORT.md), rather than assuming a short demo proves endurance. Stop sensing if battery temperature reaches 42 °C or instability appears. A brief USB-powered test does not establish unrestricted 24/7 or battery-only reliability.

## Current verification boundary

Production authentication, accepted ACKs, direct D1 measurement/receipt persistence and actual browser polling/DOM updates are verified. Positive detector outputs and live dBFS are stored, but human scene counts and named quiet/speech/clap responses have not been confirmed. The first screen-off attempt was interrupted at approximately two minutes; five uninterrupted minutes and controlled production offline replay remain pending. Present these boundaries honestly until the report records completed supervised results.
