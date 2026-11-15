# Phase 7 — Note 4 production TLS compatibility

Verified 2026-11-16 local time (2026-11-15 UTC). Phase 7 remains in progress.

## Root cause and chain

The physical SM-N910U runs Android 6.0.1 / API 23. Both a read-only pull of `/system/etc/security/cacerts` and the in-app `AndroidCAStore` enumeration found 158 anchors, **zero ISRG anchors**. The old DST Root CA X3 exists, but it does not anchor the current production chain.

The debug-only in-app OkHttp WebSocket baseline, using default system trust against the exact production WSS endpoint, reproduced:

```
javax.net.ssl.SSLHandshakeException
  java.security.cert.CertificateException
    java.security.cert.CertPathValidatorException: Trust anchor for certification path not found.
```

The baseline sent no authorization or telemetry. The physical-device TLS probe and the host's SNI-aware `openssl s_client -showcerts` independently observed:

1. `CN=temuulenbayarjargal.workers.dev`, issued by YE2.
2. YE2, issued by Root YE.
3. Root YE, cross-signed by ISRG Root X2.
4. ISRG Root X2, cross-signed by ISRG Root X1.

X1 is the missing terminal trust anchor, not a missing YE2 intermediate. The host validated this chain; host trust does not establish Android trust. The phone negotiated TLS 1.2 after correction. TLS 1.3 is not required for this API 23 client.

## Official CA provenance

- Hierarchy: https://letsencrypt.org/certificates/
- Downloaded self-signed ISRG Root X1: https://letsencrypt.org/certs/isrgrootx1.pem
- Independently compared certificate DER SHA-256 against the **Cert SHA-256 Fingerprint** table in official CP/CPS v6.1: https://letsencrypt.org/documents/isrg-cp-cps-v6.1/
- Verified fingerprint:
  `96:BC:EC:06:26:49:76:F3:74:60:77:9A:CF:28:C5:A7:CF:E8:A3:C0:AA:E1:1A:8F:FC:EE:05:C0:BD:DF:08:C6`
- Subject: `C=US, O=Internet Security Research Group, CN=ISRG Root X1`.
- Self-signed certificate validity: 2015-06-04 through 2035-06-04. Review CA/root-program policy as it evolves; a certificate's notAfter is not a promise of continued trust.
- Bundled resource: `app/src/main/res/raw/isrg_root_x1.pem`. No leaf, YE2, Root YE, or cross-signed intermediate is bundled or pinned.

Public chain subjects, fingerprints and dates are recorded in [certificate-chain.txt](evidence/phase7/certificate-chain.txt).

## Implementation scope and validation

`ProductionTls` applies only when SDK equals **23** and the configured URL equals exactly:

`wss://livespace.temuulenbayarjargal.workers.dev/ws/sensors`

It checks the bundled root's fingerprint, CA basic constraint and self-signature. A platform `TrustManagerFactory` initialized with a private in-memory KeyStore containing X1 supplies the additional path validator. `AdditionalRootTrustManager` tries normal system path validation, then full platform validation against X1. Both failures propagate as certificate failures. Client-certificate checks retain system behavior.

An app-local `SSLContext("TLS")` and its `X509TrustManager` are passed together to OkHttp. Default hostname verification, TLS/cipher configuration, disabled redirects, 30-second control pings, authentication, ready gating and immutable replay remain in effect. Other endpoints and every other Android API use the unmodified base client's system trust. There is no global SSL default change, system CA installation, trust-all path, permissive hostname verifier or HTTP/WS fallback.

The sensor service supplies this endpoint-scoped client factory. Debug builds additionally log matched readiness and accepted/duplicate ACK IDs and queue size; no credentials or raw media are logged. The parsed ACK retains its existing status for diagnostic differentiation; wire JSON and ACK eligibility are unchanged.

## Verification results

- `./gradlew testDebugUnitTest assembleDebug lintDebug`: **BUILD SUCCESSFUL**, 47 unit tests, zero failures/errors; lint zero errors, 90 warnings. First runs exposed a TLS test fixture error, an ACK-status expectation needing update, and pre-existing queue-order/WebSocket-close timing assumptions. Fixed the fixture/assertion and test cleanup, then reran successfully; no production queue scheduling behavior was changed for these tests.
- TLS tests: exact API/endpoint scope, official bundled-root fingerprint, unrelated self-signed rejection, accepted locally signed valid certificate, wrong-host rejection, expired-leaf rejection, and untrusted-leaf rejection. Only test dependency added: `okhttp-tls:4.12.0`; runtime dependencies remain pinned.
- Physical API 23 probe: 158 anchors / zero ISRG; exact default OkHttp trust-anchor failure; corrected TLS 1.2 handshake; correct hostname accepted; wrong hostname rejected by the unchanged OkHttp verifier; corrupted leaf signature rejected by the compatibility trust manager; corrected OkHttp production health HTTP 200. See [tls-probe.txt](evidence/phase7/tls-probe.txt).
- Installed with **`adb install -r`**, success. No uninstall, data clearing, token rotation or preference write. The real-service probe verified saved production URL, `LS-NOTE4-001`, location `I`, 15000 ms cadence and token presence. Successful authentication confirms the saved Keystore credential remained usable.
- APK SHA-256: `adb4aeaf0c29f3a2c9b3e34c277a7fdf760a4e8c534ea8d99a9dae2e00527ec7`; size 8,180,277 bytes.
- Physical production service: matching `sensor.ready` for `LS-NOTE4-001 / I / real`, explicit `ACK_ACCEPTED`, and queue drain to zero. An observed sample showed sent 53 / ACK 52 / pending 0, delivered lifetime counter 71. One rejected message was counted during pre-existing backlog replay; its precise server rejection reason was not captured. Do not count it as delivered. Existing lifetime retry 7 / dropped 8 counters predated this connection; expired remained zero. Subsequent fresh messages received accepted ACKs. Later observation included a stopped state followed by a new matched ready and sent 2 / ACK 2 / pending 0 (lifetime delivered 75); the cause of that transition was not independently established and is not claimed as a supervised Stop-cleanup test.
- Real local inference and 16 kHz AudioRecord ran. Snapshot example: observed people 0, inference 147.85 ms, RMS 0.0003653, −68.75 dBFS; these are measurements, not positive-person/audio-event validation. SPL remained null, uncalibrated.
- Public `/api/v1/live` changed to real-source `online`, with observed people and RMS/dBFS. Public real-source history changed from empty to readings and aggregates. Saved read-only responses: [public-live.json](evidence/phase7/public-live.json), [public-history.json](evidence/phase7/public-history.json). Browser rendering and signed-in owner/admin diagnostics are not independently verified.
- USB-powered battery observations 100%, 34.3–34.5 °C; no endurance claim. Some replayed timestamps were ahead of receipt timestamps; the exact historical cause is unverified. Current phone/host epoch checks agreed to the second; no device clock was modified.

## Phase 7 continuation

The normal dashboard has been reopened; the explicit user-started sensor service remains active for manual validation. Requested user reports: consenting person entry/pause/exit counts, portrait/landscape upright preview and box fit, quiet/speech/clap dBFS changes. Human observations remain pending. Do not substitute zero-count samples for a positive-person test.

The five-minute screen-off test, supervised 60-second Wi-Fi outage/replay, microphone-denial isolation, signed-in diagnostics and notification Stop cleanup remain pending. Do not interrupt the manual tests with a screen-off/outage action before the user finishes. A debug-only integration observation activity samples structured metrics every five seconds for up to ten minutes and stops sensing at 42 °C; it neither records media nor changes settings. Its observation timeout does not stop the foreground service.

No backend files, credentials, protocol envelopes or later phases were changed. No commit, push, or Phase 8–10 work was performed.
