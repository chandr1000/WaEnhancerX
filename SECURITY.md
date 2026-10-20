# Security Policy

## Supported Versions

Security updates and patches are actively applied to the following versions of WAEX:

| Version | Supported          |
| ------- | ------------------ |
| 2.x.x   | :white_check_mark: |
| 1.x.x   | :white_check_mark: |
| < 1.0   | :x:                |

---

## Reporting a Vulnerability

The WAEX team takes security seriously. If you discover a security vulnerability in this project, please report it responsibly so we can investigate and address it promptly.

### How to Report

1. **Do not create a public issue or discussion.**
2. Send an email to **[hello@mubashar.dev](mailto:hello@mubashar.dev)** with the subject line `[SECURITY] WAEX Vulnerability Report`.
3. In your report, please include:
   - A clear description of the vulnerability.
   - Step-by-step instructions or Proof of Concept (PoC) to reproduce the issue.
   - The affected component, class, or module.
   - Potential impact and severity assessment.
   - Any suggested mitigations or fixes, if known.

### Response Timeline

- **Initial Acknowledgment**: Within 48 hours of receiving your report.
- **Assessment & Validation**: We will investigate and provide confirmation within 5 business days.
- **Patch & Release**: A fix will be developed, tested, and released as quickly as feasible.
- **Public Disclosure**: Coordinated public advisory will be issued once the fix is released.

---

## Security Considerations

WAEX operates as an Android application with hook extensions (such as Xposed/LSPosed). As such:
- We prioritize user data integrity, privacy, and prevention of unintended privilege escalation.
- We do not store or transmit sensitive communication data externally without explicit user authorization.
- All dependencies are audited regularly for known CVEs.

## Root Command Registry

All root operations flow through a single registered door: `RegisteredRootRunner.run`.
There is exactly one `ProcessBuilder("su"` site in the codebase (`RootEngine.SU_STARTER`),
enforced by the `qa/test_root_registry_enforcement.py` scanner.

- **Parameter validation is positive (allowlist), not escaping-based.** `PATH_STRICT`
  rejects `$`, backticks, quotes, `;`, `&`, `|`, `( )`, whitespace; `PACKAGE_NAME`
  uses a strict regex; `BASE64_TEXT` validates the Base64 alphabet.
- **Per-command kill-switch** toggles live in `private_config` (MODE_PRIVATE, module
  UID only), never in the world-readable `waex_prefs`. They are `Sensitivity.RUNTIME`
  so a settings backup cannot silently re-enable a root capability the user disabled.
- **The audit log is RAM-only** — 200-entry ring buffer, 200-char output preview,
  cleared on process death. A persisted root log on a rooted device would be readable
  by anything with root, which would invert the feature's purpose.
- **Logging is a privacy control for the user, not a security control over WAEX.**
  Disabling the audit log means the *user* loses visibility; it does not (and cannot)
  reduce what the app may execute.

### Honest limitations

A compromised manager process (code execution inside `com.waenhancer` via another
Xposed module, Frida, or a malicious dependency) can still invoke any *registered*
command with any *valid* parameters. The registry shrinks the blast radius from
"arbitrary shell" to 20 vetted operations — a massive improvement, but not zero.
The su manager remains the real security boundary; the registry governs *what the
app will ask for*, not *what the device will allow*.
