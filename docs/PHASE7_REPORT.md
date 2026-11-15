# Phase 7 — physical production integration

Updated 2026-11-16, Asia/Ulaanbaatar. **Phase 7 is in progress.** Authenticated production delivery, D1 persistence and browser updates are verified. Supervised manual accuracy and uninterrupted reliability checks remain open as listed below.

## Verification status

| Requirement | Result | Evidence / limit |
| --- | --- | --- |
| Production WSS authentication | Verified on physical SM-N910U / API 23 | Saved Keystore token used; no credential printed or reprovisioned |
| `sensor.ready` identity gate | Verified | `READY_MATCHED device=LS-NOTE4-001 location=I source=real` |
| Accepted telemetry ACK / outbox removal | Verified | Explicit `ACK_ACCEPTED` and pending queue reaches zero |
| D1 durable storage | Verified by read-only remote SQL | Measurement and receipt rows match phone ACK UUIDs; query metadata reports zero rows written |
| Public API updates | Verified | REAL / online, observed people, dBFS, source-separated history |
| Actual public browser updates | Verified | Headless Chromium cafe I DOM changes timestamp/dBFS across polling; no JavaScript exceptions |
| Positive detector output | Verified as output, accuracy unverified | Three positive rows at one D1 snapshot; one count 6 / confidence 0.6692708; actual scene count not provided |
| Audio capture and telemetry | Verified | AudioRecord 16000 Hz, changing finite RMS/dBFS in phone, D1 and browser; SPL null / uncalibrated |
| Quiet / speech / clap discrimination | Pending supervised observations | Ambient variation does not prove named-event response |
| Portrait/landscape preview and boxes | Pending human visual check | No screenshots/raw media saved; numeric/rotation tests do not replace visual confirmation |
| Five-minute screen-off operation | **Not yet verified** | First attempt interrupted when phone woke at ~120 s; kept separate as incomplete |
| Controlled 60-second production outage/replay | Pending coordination | Queue checksum audit and comparison tools prepared; prior production backlog drain and earlier mock immutable replay are not this test |
| Resource cleanup after observed stop | Verified state; notification interaction not supervised | Camera clients empty, microphone RELEASED, no sensing wake lock, service not started |
| Microphone denial isolation / notification Stop interaction | Pending supervised interaction | Do not infer microphone-denial behavior or the exact Stop UI path from cleanup alone |

## TLS compatibility actually implemented

The Note 4's AndroidCAStore has 158 anchors and no ISRG root. Default in-app OkHttp WSS reproduced `CertPathValidatorException: Trust anchor for certification path not found`. The server supplied leaf → YE2 → Root YE → ISRG Root X2 (cross-signed by X1), so X1 was the missing trust anchor.

Bundled the official self-signed ISRG Root X1 from https://letsencrypt.org/certs/isrgrootx1.pem. Its DER SHA-256 matches the official [CP/CPS v6.1 fingerprint table](https://letsencrypt.org/documents/isrg-cp-cps-v6.1/):

`96:BC:EC:06:26:49:76:F3:74:60:77:9A:CF:28:C5:A7:CF:E8:A3:C0:AA:E1:1A:8F:FC:EE:05:C0:BD:DF:08:C6`

`ProductionTls` supplies an app-local platform `X509TrustManager` and `SSLContext` only on API 23 and the exact configured production endpoint `wss://livespace.temuulenbayarjargal.workers.dev/ws/sensors`. Both the system validator and the additional X1 validator enforce normal certificate paths. OkHttp's default hostname verifier remains enabled; other endpoints and newer Android use system defaults. No leaf/YE2 pinning, TLS bypass, root installation or insecure-WS workaround exists.

Physical checks passed: corrected TLS 1.2, correct hostname accepted, wrong hostname rejected, damaged leaf signature rejected, corrected OkHttp health HTTP 200. Unit TLS checks also reject expired and untrusted leaf certificates. Details and original diagnostic evidence: [PHASE7_TLS_COMPATIBILITY.md](PHASE7_TLS_COMPATIBILITY.md).

## Production persistence and browser evidence

Read-only Wrangler SQL queried the existing production D1 database, joining `measurements` and `sensor_receipts` by `(device_id,message_id)`. Phone-logged accepted UUIDs `1750532d-74b2-4b98-8e25-5827a38c890e`, `b062b711-142d-4db8-bf97-28cd1a0fc967`, and `5fc9e45d-4721-491e-b752-7449cb09fb89` all had matching real-source cafe I measurements and receipts. This independently verifies persistence rather than inferring it only from ACKs. The same query confirmed positive-output UUID `3cbd65ff-5b90-4778-bf75-701e492e5a7a`. A point-in-time aggregate found 73 measurements / 73 distinct message IDs, three positive samples, dBFS from −77.42064 to −58.30186. All query metadata reported `rows_written: 0`, `changed_db: false`. This is an observed snapshot, not a lifetime accuracy or quota result.

A fresh isolated headless Chromium profile opened the real public cafe I page, without logging in. It rendered REAL / online, observed count 0 and dBFS; its displayed timestamp advanced from 05:30:05 to 05:30:20 local time and dBFS from −73.7 to −69.8. History grew from one to two samples in the current five-minute bucket. Repeated `/api/v1/live` and real-source history requests returned 200 at the normal approximately ten-second polling cadence. No runtime exceptions occurred. `/auth/me` returned expected anonymous 401; no private credentials were used. No screenshot, recording or sensor media was captured.

Evidence:

- [D1 measurement/receipt join and counts](evidence/phase7/d1-persistence.json)
- [Actual public browser DOM/polling observations](evidence/phase7/public-browser.json)
- [Current public live JSON](evidence/phase7/public-live-current.json)
- [Current public real-source history](evidence/phase7/public-history-current.json)
- [Structured phone production logs](evidence/phase7/production-current.txt)

## Screen-off attempt and test coordination

The first controlled attempt pressed Home and Sleep and sampled device/service state. At 0.718 seconds the phone was Dozing / `interactive=false`; at ~31, 61 and 91 seconds it was Asleep, with camera/inference counts advancing, microphone running and production ACKs continuing. At ~120 seconds it was Awake / `interactive=true`; a subsequent service restart was observed. Its cause was not independently established. The attempt was stopped and retained in [screen-off-interrupted.jsonl](evidence/phase7/screen-off-interrupted.jsonl), and **does not qualify as five uninterrupted minutes**. Battery stayed around 34.4–34.8 °C in that attempt, USB powered. No 24/7 or battery-only claim follows.

The phone subsequently entered a stopped state. Read-only checks found no active camera clients, microphone RELEASED, no `LiveSpaceSensor:Capture` wake lock and `startRequested=false`; latest samples show stopped/null sensor metrics. See [stopped-resource-state.json](evidence/phase7/stopped-resource-state.json). The exact UI action was not supervised; no automatic restart was performed. Requested user coordination for restarting sensing and five uninterrupted minutes without touching the phone, followed by the controlled Wi-Fi outage. Replay verification will compare read-only SQLite row SHA-256 values and message IDs to actual send checksums/ACK IDs, then confirm removal and D1 rows; no telemetry will be synthesized or edited.

## Final demonstration build

- `./gradlew testDebugUnitTest assembleDebug lintDebug`: **BUILD SUCCESSFUL**, **47 tests**, zero failures/errors; lint **zero errors / 90 warnings**.
- Prepared [livespace-sensor-phase7-api23-debug.apk](../artifacts/phase7/livespace-sensor-phase7-api23-debug.apk), 8,230,015 bytes, debug-signed for the existing installed app. Package `com.livespace.sensor`, minSdk 23, ARMv7; TFLite 2.8.0 and OkHttp 4.12.0 remain pinned.
- SHA-256: `c36e39d9fe0c23eb508276e2f925ff4d3e85593e434da3eb908eacbc37bc8015`. [Checksum file](../artifacts/phase7/SHA256SUMS).
- `adb install -r` succeeded on the Note 4, with no uninstall or data clearing. Saved token and 15000 ms production settings remained usable; matching ready and accepted ACKs were observed after installation.
- Fixed obsolete dashboard wording that denied transmissions and claimed sensors stopped on leaving the Activity. It now describes structured telemetry and user-started service/Stop behavior correctly.
- Added bounded debug observation of display interactivity/PSS and read-only queue row checksums. Send diagnostics contain UUID/checksum only, never raw JSON/media or credentials. Wire schema, ACK policy, root scope and runtime dependencies are unchanged.
- [University demonstration guide](DEMO_GUIDE.md) includes setup, a timed sequence, manual worksheet, secure-TLS explanation, APK installation, limitations and fallback.

Existing durable counters included retries 7 / drops 8 / rejected 1 before this continuation. The earlier unexplained backlog rejection remains surfaced; it is not a successful delivery and its cause is not claimed. Do not silently reset these counters. Separate fresh-test deltas from lifetime values.

No backend functionality, account, device registration, provisioning token or paid service was modified. No raw images/audio were saved or transmitted. No Git command, commit, push, merge or Phase 8–10 implementation was performed in this continuation.
