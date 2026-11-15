# Phase 4 connection setup

## Local university demo

1. Start the Node backend on the server computer using its published quick-start in [SENSOR_PROTOCOL.md](SENSOR_PROTOCOL.md). Confirm `http://192.168.1.240:8080/api/v1/health` from the phone and connect both devices to the same private Wi-Fi. Replace that IP if the host address changed; disable guest Wi-Fi client isolation.
2. Register `LS-NOTE4-001` as a real `sensor` in the web admin, assign it to the intended cafe and copy the one-time token. Configure the app with `ws://192.168.1.240:8080/ws/sensors`, `LS-NOTE4-001`, the assigned cafe/location ID, and 5000 ms for a short demo. The default example location `I` is valid only if that is the ID assigned by the backend.
3. Enable “Allow private-IP LAN ws development” only for this isolated private-network test. The Bearer token travels without TLS over local ws; do not use this mode on public/guest networks. Save the token through the masked password field. It is encrypted with AndroidKeyStore and is never shown again.
4. Start sensors, then choose Connect / test WebSocket. Wait for `CONNECTED`, sent count, then acknowledged count. A connected TCP/WebSocket alone is not evidence of accepted telemetry. Check the admin Devices/diagnostics and cafe history/SSE as documented by the backend team.

## Cloudflare deployment

The app's fresh-install default is `wss://livespace.temuulenbayarjargal.workers.dev/ws/sensors`. Use it when the Workers host is deployed and has a valid certificate. The endpoint remains editable. Keep standard TLS verification enabled. Register/assign the real device and use its rotated production token. Do not reuse a local demo token. Continuous telemetry defaults to 15000 ms; the setting accepts 5000 ms for a short demonstration. The heartbeat remains 30 seconds and independent of telemetry and 3000 ms AI inference.

## Test without a real backend credential

Run `python3 tools/phase4_device_probe.py` while the authorized Note 4 is attached. The debug-only probe generates a random short-lived token, uses ADB reverse to a local mock, checks the v1 upgrade, waits for `sensor.ready`, validates the complete JSON envelope, sends `sensor.ack`, and checks the Android Keystore roundtrip. It stores only aggregate assertions/log tags. It does not touch the production token or send image/audio content.

## Operational constraints

- Stop disconnects networking and clears the Phase 4 in-memory queue. Home/background stops the socket and sensor capture. Persistent offline recovery and screen-off operation are later phases.
- A server assignment mismatch or authentication rejection stops automatic sending. Correct registration/token settings and explicitly reconnect.
- Retry keeps the original message ID and exact payload. `accepted` and `duplicate` ACKs remove a message. Raw media is never sent or stored.
