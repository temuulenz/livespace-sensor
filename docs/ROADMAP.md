# Roadmap

1. Android foundation: XML dashboard, Camera2 frame acquisition, permission handling, real device monitoring and diagnostics. Build and physical smoke testing; see TESTING.md.
2. Edge AI: obtain genuine SSD MobileNet V1 quantized UINT8; record source, license, hash, labels, tensors and quantization. Verify a TFLite release with API 23/armeabi-v7a support. Implement stride-aware YUV conversion, rotation, person filtering, monitoring area, count smoothing, debug boxes and measured CPU latency on this device. Visible people remain distinct from total occupancy.
3. Audio: AudioRecord, supported mono PCM16 sample rate, RMS/dBFS windows, optional calibration; test simultaneous camera use.
4. Transport: inspect backend requirements, agree authentication/ACK contract, choose API 23-compatible OkHttp, validate WSS/local configuration, heartbeat/backoff and JSON IDs.
5. Foreground service: implemented. Service ownership, explicit controls, notification, wake-lock lifecycle, Home/screen-off check on the Note 4. See PHASE5_REPORT.md.
6. Reliability: implemented. Bounded app-private SQLite JSON outbox, ACK deletion, identity scoping, durable counters and force-stop/replay mock test. See PHASE6_REPORT.md.
7. Backend integration: physical Note 4 production WSS to Cloudflare Worker/D1 and the public polling UI. Secure API 23 ISRG-root compatibility, ready/accepted ACKs, direct D1 persistence and browser updates verified; supervised manual and uninterrupted reliability checks remain open. See PHASE7_REPORT.md.
8. Server forecasting: real historical aggregates, baseline models, chronological evaluation; no fabricated observations or Android training.
9. Deployment: CPU/RAM/temperature/power optimization and extended physical soak tests, privacy and installation documentation.

Each phase ends with a build and evidence report before proceeding. Phase 7 production integration is not complete; Phases 8–9 remain future work.

Phase 2 implementation includes the canonical quantized UINT8 SSD MobileNet V1 model, physically verified TFLite 2.8.0 ARMv7 CPU runtime, bounded inference, local boxes/ROI/count smoothing and measured diagnostics. See PHASE2_REPORT.md for benchmark and manual-validation limits.

Phase 3 adds local AudioRecord RMS/dBFS, rolling energy average/sample peak, optional manual calibration, a bounded graph and a synchronized finite/stale-aware telemetry snapshot. Phase 4 transport and Phases 5–6 service/durable queue are implemented; see their reports. Phase 7 production authentication, D1 persistence and browser updates are verified. Manual scene/audio validation, uninterrupted five-minute screen-off and controlled outage/replay still require completion. Phases 8–10 are not authorized.
