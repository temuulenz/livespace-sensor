# Phase 6 — Durable telemetry outbox

## Implementation

`SqliteTelemetryOutbox` uses `sensor_outbox.db` in app-private internal storage with SQLite transactions and WAL. Each pending row contains only the frozen protocol-v1 JSON text plus its message ID, device/location scope, original UTC-created time, send/attempt state and replay priority. No image, frame, audio sample or raw media field is stored. IDs and JSON bytes remain unchanged across reconnect and process death.

Messages are inserted before any WebSocket send. Only `accepted` or `duplicate` ACKs parsed by `SensorProtocolV1` delete a matching row; repeated ACKs do nothing. ACK deletion checks the configured device/location scope. `sensor.ready` must match that same identity before the outbox can drain. Messages from old device/location settings stay queued but are never sent under the new credentials.

Default retention is at most 10,000 rows and 24 hours; the Server and credentials settings allow a lower row cap and 1–24 hour retention. Expired rows, overflow drops and permanently rejected messages update durable counters and are shown in the dashboard. Queue-full rejects the newest observation and increments `dropped`; it does not silently evict older entries. Expiry is applied transactionally when queue state is read or updated. Server validation/authentication/conflict errors retain the protocol's existing semantics; permanent validation rejects are removed and counted so one malformed record cannot block later rows. Transient rate/storage/timeout/reconnect failures preserve the original JSON and ID.

At most four messages are in flight, and transmission is paced at one message per second (below the backend's 120/minute/device limit). After `sensor.ready`, stored backlog is demoted and fresh live samples receive priority; newest timestamps are chosen first so replay cannot hold current readings behind a long history. Server consumers still compare observation timestamps as required by the protocol. The 2026-11-16 compatibility update enables 30-second client-originated WebSocket control pings; OkHttp continues to answer server control pings automatically. No app-level JSON heartbeat is sent.

The dashboard and Diagnostics show queue depth, oldest pending age, durable delivered/retry/expired/drop/reject counters, last ACK, connection state, reconnect attempts and sanitized error. SQLite metric refresh is kept off the UI path; the service observes a cached immutable counter snapshot.

## Verification

- `./gradlew testDebugUnitTest assembleDebug lintDebug`: **BUILD SUCCESSFUL**, **43 unit tests passed**, 0 failures/errors. Lint: **0 errors / 88 warnings**. The added tests cover scope-safe ACKs/fresh-message priority, one-message pacing claims, and offline collection before `sensor.ready`, including unchanged payload across disconnect. Existing frozen-protocol tests continue to pass.
- Debug-only Note 4 replay probe seeded a protocol JSON row, force-stopped the app process, restarted a fresh process, connected to a local protocol-compatible WebSocket mock, and replayed the seeded UUID. The server-side SHA-256 matched the pre-force-stop payload checksum; no raw-media keys were present. The final paced run observed two messages (one live sample and the target); target replay and scoped cleanup both succeeded. This is mock traffic only, not a LiveSpace backend submission. Exact probe results: `targetReplayed=true`, `payloadChecksumMatches=true`, `rawMediaAbsent=true`, `testRowsCleaned=true`, `serverErrors=[]`.
- The Note 4 accepted the final debug APK. Camera and sensor lifecycle were physically checked separately as documented in [Phase 5](PHASE5_REPORT.md). No real registered device token/backend was available, so production authentication and Cloudflare were not exercised.

## Remaining validation

The physical force-stop/restart replay confirms durable storage and immutable payload replay. This run did not conduct a controlled 60-second sensor outage with the production settings/token, did not fill 10,000 rows, and did not wait 24 hours for retention expiry. Those need a supervised protocol mock/backend session. Existing queue entries for a different configured identity are intentionally retained until their age limit, but excluded from transmission. The debug probe's `PHASE6-PROBE` rows were removed after the final run; cleanup is also exposed through its debug-only activity.
