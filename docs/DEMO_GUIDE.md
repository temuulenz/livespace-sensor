# LiveSpace Sensor university demonstration

## Before the demonstration

- Use the physical Note 4 registered as `LS-NOTE4-001`, location `I`, real sensor. Confirm the app's endpoint is `wss://livespace.temuulenbayarjargal.workers.dev/ws/sensors`, the telemetry interval is 15000 ms, and the 30000 ms protocol heartbeat is independent of the 3000 ms inference interval.
- Enter the production token from the private mode-0600 file directly in the Android app's Server and credentials settings. Do not send it in chat, display it, or paste it into a terminal command. Android stores it through Android Keystore. A blank field keeps the stored token.
- Connect the phone to reliable Wi-Fi. Confirm its clock is correct, battery is healthy, permissions are granted, camera view is mounted safely, and an appropriate power supply is available. A USB-powered test is not evidence of battery-only endurance.
- Open the Android dashboard, public cafe page `https://livespace.temuulenbayarjargal.workers.dev/#cafe-I`, and the admin page `https://livespace.temuulenbayarjargal.workers.dev/#admin` in separate tabs. Admin diagnostics require the existing private admin account. Do not modify account or device provisioning during the demo.
- Confirm the public real-source history baseline before capture. The website updates applicable live views by automatic polling (currently about every 10 seconds); do not describe it as instantaneous push.
- Obtain informed consent from people who may enter the camera's field of view. Frames and microphone samples are processed on-device only; only structured JSON telemetry is sent. Do not save images/audio. Treat observed people as visible detections, not total cafe occupancy. dBFS is not calibrated dB SPL.

## 3–5 minute sequence

1. **Start (30–45 s):** Open LiveSpace Sensor and press **Start sensor**. Grant camera/microphone permissions if Android asks. Show the local preview, model ready state, model settings (2 threads, confidence 0.5, 3000 ms), microphone status, battery and persistent notification.
2. **Connect (30–45 s):** Press **Connect**. Show `CONNECTED`, matching `sensor.ready` identity, then sent and acknowledged counters increasing. Confirm pending queue stays near zero after accepted ACKs. If anything says `AUTH_FAILED`, stop and correct registration/token privately; do not repeatedly retry or switch to simulator credentials.
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

- [ ] Production token remains only in Android Keystore; no credential is visible in a terminal, log, screen recording, or presentation.
- [ ] Device says `LS-NOTE4-001`, location `I`, source real, 15000 ms telemetry.
- [ ] Website update is described as polling, not instantaneous SSE if the deployed UI uses polling.
- [ ] Person and audio actions are performed by consenting participants.
- [ ] dBFS is not called dB SPL without calibration.
- [ ] Fallback is visibly marked simulated.
- [ ] Stop action releases sensors and network when the demo ends.
