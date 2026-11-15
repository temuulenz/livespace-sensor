# Phase 7 checkpoint

Updated 2026-11-16, Asia/Ulaanbaatar. Phase 7 remains in progress.

## Verified

- Secure API 23 ISRG Root X1 compatibility; authenticated production ready for `LS-NOTE4-001 / I / real`; accepted ACKs and queue drain.
- Existing token/settings preserved across `adb install -r`.
- Read-only remote D1 measurement/receipt join matches phone ACK UUIDs; queries wrote zero rows.
- Actual public Chromium page renders REAL/online and changes timestamp/dBFS/history through polling.
- Positive detector outputs are stored; correctness against actual people is not yet human-verified. AudioRecord/RMS/dBFS capture and delivery are verified; quiet/speech/clap observations pending.
- Final demo APK prepared in `artifacts/phase7`, checksum `c36e39d9fe0c23eb508276e2f925ff4d3e85593e434da3eb908eacbc37bc8015`; 47 tests pass, lint 0 errors / 90 warnings.

## Current coordination

ADB briefly became unauthorized, then access was restored. First screen-off run was interrupted when the phone woke at about two minutes; this is incomplete evidence, not a five-minute pass. Asked the user to finish manual checks and leave the phone untouched for five uninterrupted minutes before restarting the run. The phone subsequently stopped sensing; camera/microphone/wake-lock cleanup is observed. It remains stopped pending the user’s readiness response. No permission to clear data or change credentials is needed or sought.

## Resume

1. Obtain manual person entry/exit and quiet/speech/clap observations, including portrait/landscape preview/box alignment. Do not equate stored positive outputs with verified accuracy.
2. Once the user is ready to leave the phone untouched, repeat five minutes screen-off, monitoring display interactivity, service/capture/ACKs, queue, PSS and temperature. Stop at 42 °C or instability.
3. Run 60-second Wi-Fi-only outage with automatic restoration. Read-only queue audit must match pre-reconnect UUID/payload SHA-256 to SEND checksum and accepted/duplicate ACK, then row removal and D1 storage.
4. Perform supervised microphone isolation and notification Stop cleanup as practical.
5. Update the report/guide with exact results, keep incomplete runs separate, and stop at Phase 7. Do not perform Git operations or begin Phase 8–10.

Full current results and evidence: [PHASE7_REPORT.md](PHASE7_REPORT.md). Root provenance/fingerprint and physical TLS negative tests: [PHASE7_TLS_COMPATIBILITY.md](PHASE7_TLS_COMPATIBILITY.md). University guide: [DEMO_GUIDE.md](DEMO_GUIDE.md).

Prepared host test commands (run from repository root only after user readiness): `python3 -u tools/phase7_screenoff.py`, then `python3 -u tools/phase7_offline.py`. Both retain separate timestamped run evidence. The screen-off tool fails an awake/stopped sample rather than calling it a five-minute pass. The outage tool requires an already-running connected sensor, restores Wi-Fi in `finally`, audits real queued JSON by checksum without logging payloads, and requires identical send checksums plus ACKs. A subsequent read-only D1 lookup is still needed to verify those replay UUIDs. Neither tool synthesizes telemetry.
