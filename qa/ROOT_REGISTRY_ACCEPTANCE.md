# ROOT_REGISTRY_ACCEPTANCE.md

Manual on-device acceptance matrix for the Root Command Registry & Transparency Layer.
Follows the `RUNTIME_DIAGNOSTIC_ACCEPTANCE.md` convention.

## 1. Fresh install: consent onboarding

- [ ] Fresh install launches `PermissionsActivity` → `RootConsentReviewScreen` shows
      the 18 registered operations grouped by category (no su prompt yet).
- [ ] Tapping "Continue" sets `root_consent_reviewed` in `private_config` and
      triggers the `root.probe.id` → the root manager prompt appears.
- [ ] Granting root → app proceeds to the main dashboard.
- [ ] Denying root → `RootAccessRequirementScreen` with "Root access is required"
      and a "Check again" retry button.
- [ ] Re-launching the app after consent+grant → no consent flash; dashboard loads
      directly (consent flag is persisted).
- [ ] Re-launching after consent but before grant → consent screen is skipped,
      probe fires, `RootAccessRequirementScreen` shows "Waiting for superuser approval…"
      (20s timeout, not 4s).

## 2. Bahasa Indonesia sweep

- [ ] Switch in-app language to Bahasa Indonesia.
- [ ] System Health → "Privileged Operations" card shows localized title and summary.
- [ ] Privileged Operations list: all 18 commands show localized title, desc, risk chip,
      toggle label ("Diaktifkan" / "Dinonaktifkan oleh Anda").
- [ ] Tap a command → detail screen shows localized description, "Kapan dijalankan",
      "Yang terjadi saat dinonaktifkan", "Parameter & validasi", "Perintah sebenarnya".
- [ ] Audit log screen: localized outcome chips, timestamp, "Pratinjau keluaran" label.
- [ ] Consent review: localized title, body, continue button, boundary statement.
- [ ] Toast fallbacks localized: "Mulai ulang lewat root dinonaktifkan oleh Anda…",
      "Instalasi lewat root dinonaktifkan oleh Anda…".

## 3. Toggle-off degradation

- [ ] Disable `restart.target.force_stop` in Privileged Operations.
- [ ] Trigger a restart from the dashboard → toast "Root restart disabled by you —
      using broadcast fallback" appears; WhatsApp reopens but restart is not verified.
- [ ] Disable `update.install` → root install falls back to the session installer.
- [ ] Disable `hma.write_config` → HMA repair chain refuses to start (pre-flight
      blocks; no partial write).

## 4. Audit log

- [ ] After any root operation, entries appear in the audit log (System Health →
      Privileged Operations → audit-log icon).
- [ ] Entries show: timestamp, command id, outcome (OK / Blocked / Timed out / Failed),
      caller tag, duration, output preview (≤200 chars).
- [ ] Record 200+ entries → buffer never exceeds 200; oldest is evicted.
- [ ] Toggle the audit-log header switch OFF → new operations are not recorded.
- [ ] Toggle it back ON → recording resumes.
- [ ] Kill and relaunch the app → audit log is empty (RAM-only, cleared on process death).

## 5. Release build

- [ ] `assembleRelease` is green.
- [ ] No "Developer tools" section appears in Privileged Operations.
- [ ] Resource shrinking does not strip `rootop_*` strings.
- [ ] `debug.echo` spec is not registered (no audit entry, no toggle, no UI).

## 6. Root-manager revoke mid-session

- [ ] Launch app with root granted.
- [ ] Revoke root for WAEX in the root manager.
- [ ] Trigger any root operation → `RootResult.Failed(NONZERO_EXIT)` or
      `Failed(TIMEOUT)` appears in the audit log; UI degrades to non-root fallback.
- [ ] No hang, no crash.
