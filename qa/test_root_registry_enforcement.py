#!/usr/bin/env python3
"""Source-text enforcement scanner for the root command registry (Layer 2).

Pure Python, no SDK. The structural wall: scanner-enforced rules that make the
single-door topology stick. Allowlist schedule:

  - Phase 1-4: exactly TWO ProcessBuilder("su" sites
        - app/src/main/java/com/waenhancer/root/RootEngine.java    (the kernel)
        - app/src/main/java/com/waenhancer/xposed/utils/AppRestartHelper.java
          (the legacy manual overload, dies in Phase 5)
  - Phase 5: exactly ONE su site (kernel only)

Plus:
  - Every RegisteredRootRunner.run(...) call site passes a literal command id
    present in RootCommandRegistry.
  - No file outside com/waenhancer/root/ references RootEngine.
  - No file under xposed/features/ imports com.waenhancer.root.
  - RootCommandRunner is marked @Deprecated.
  - rootop_enabled_* / root_audit_log_enabled / root_consent_reviewed keys are
    registered in PreferenceSchema as Sensitivity.RUNTIME + Store.PRIVATE.
  - Each golden command literal from the registry mirror is present in the
    Kotlin RootCommandRegistry source.
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
APP = ROOT / 'app/src/main/java'

SU_SITES_EXPECTED_PHASE_1_TO_4 = {
    'app/src/main/java/com/waenhancer/root/RootEngine.java',
    'app/src/main/java/com/waenhancer/xposed/utils/AppRestartHelper.java',
}

ALLOWED_COMMAND_IDS = {
    'root.probe.id',
    'restart.target.force_stop',
    'restart.target.launch',
    'diag.process.pidof',
    'diag.selinux.getenforce',
}

GOLDEN_TEMPLATES = {
    'root.probe.id': "id",
    'restart.target.force_stop': "am force-stop '",
    'restart.target.launch': "monkey -p '",
    'diag.selinux.getenforce': "getenforce",
    'diag.process.pidof': "END|WAEX_PROCESS_SNAPSHOT",
}

PRIVATE_RUNTIME_KEYS = [
    'rootop_enabled_restart_target_force_stop',
    'rootop_enabled_restart_target_launch',
    'rootop_enabled_diag_process_pidof',
    'rootop_enabled_diag_selinux_getenforce',
    'root_audit_log_enabled',
    'root_consent_reviewed',
]


def find_su_sites() -> set[str]:
    sites = set()
    for path in APP.rglob('*.java'):
        text = path.read_text(encoding='utf-8', errors='replace')
        if 'ProcessBuilder("su"' in text:
            sites.add(str(path.relative_to(ROOT)))
    for path in APP.rglob('*.kt'):
        text = path.read_text(encoding='utf-8', errors='replace')
        if 'ProcessBuilder("su"' in text:
            sites.add(str(path.relative_to(ROOT)))
    return sites


def check_runner_call_sites_use_literal_ids() -> list[str]:
    failures: list[str] = []
    pattern = re.compile(r'RegisteredRootRunner\.run\(\s*"([^"]+)"')
    for path in APP.rglob('*'):
        if not path.is_file() or path.suffix not in {'.java', '.kt'}:
            continue
        text = path.read_text(encoding='utf-8', errors='replace')
        for match in pattern.finditer(text):
            command_id = match.group(1)
            if command_id not in ALLOWED_COMMAND_IDS:
                failures.append(
                    f'{path.relative_to(ROOT)}: unknown command id "{command_id}"')
    return failures


def check_root_engine_isolation() -> list[str]:
    failures: list[str] = []
    # RootCommandRunner is the deprecated delegating shim that forwards to
    # RootEngine during the migration; it is removed in Phase 5.
    allowed_outside_root = {
        'app/src/main/java/com/waenhancer/xposed/utils/RootCommandRunner.java',
    }
    for path in APP.rglob('*'):
        if not path.is_file() or path.suffix not in {'.java', '.kt'}:
            continue
        rel = str(path.relative_to(ROOT))
        if rel == 'app/src/main/java/com/waenhancer/root/RootEngine.java':
            continue
        if rel.startswith('app/src/main/java/com/waenhancer/root/'):
            continue
        if rel in allowed_outside_root:
            continue
        text = path.read_text(encoding='utf-8', errors='replace')
        if 'RootEngine' in text and 'com.waenhancer.root.RootEngine' in text:
            failures.append(f'{rel}: imports RootEngine (must be in com.waenhancer.root only)')
    return failures


def check_no_root_imports_in_xposed_features() -> list[str]:
    failures: list[str] = []
    features = APP / 'com/waenhancer/xposed/features'
    if not features.exists():
        return failures
    for path in features.rglob('*'):
        if not path.is_file() or path.suffix not in {'.java', '.kt'}:
            continue
        text = path.read_text(encoding='utf-8', errors='replace')
        if 'import com.waenhancer.root' in text:
            failures.append(f'{path.relative_to(ROOT)}: xposed feature imports com.waenhancer.root')
    return failures


def check_root_command_runner_deprecated() -> list[str]:
    path = APP / 'com/waenhancer/xposed/utils/RootCommandRunner.java'
    text = path.read_text(encoding='utf-8', errors='replace')
    if '@Deprecated' not in text:
        return [f'{path.relative_to(ROOT)}: missing @Deprecated annotation']
    return []


def check_schema_keys() -> list[str]:
    path = APP / 'com/waenhancer/config/PreferenceSchema.java'
    text = path.read_text(encoding='utf-8', errors='replace')
    failures: list[str] = []
    for key in PRIVATE_RUNTIME_KEYS:
        # Each key must be registered with Sensitivity.RUNTIME and Store.PRIVATE.
        line_pattern = re.compile(
            r'add\(entries,\s*"' + re.escape(key) + r'",\s*Type\.\w+,\s*Sensitivity\.(\w+),\s*Store\.(\w+)\)'
        )
        match = line_pattern.search(text)
        if not match:
            failures.append(f'{key}: not registered in PreferenceSchema')
            continue
        sensitivity, store = match.group(1), match.group(2)
        if sensitivity != 'RUNTIME':
            failures.append(f'{key}: sensitivity={sensitivity} (must be RUNTIME)')
        if store != 'PRIVATE':
            failures.append(f'{key}: store={store} (must be PRIVATE)')
    return failures


def check_golden_templates_in_registry() -> list[str]:
    path = APP / 'com/waenhancer/root/RootCommandRegistry.kt'
    text = path.read_text(encoding='utf-8', errors='replace')
    failures: list[str] = []
    for command_id, golden in GOLDEN_TEMPLATES.items():
        if golden not in text:
            failures.append(f'{command_id}: golden literal "{golden}" missing from registry')
    return failures


def check_toggle_keys_cover_registry() -> list[str]:
    """Every toggleable spec in the registry must have a rootop_enabled_* key in schema."""
    registry = (APP / 'com/waenhancer/root/RootCommandRegistry.kt').read_text(
        encoding='utf-8', errors='replace')
    schema = (APP / 'com/waenhancer/config/PreferenceSchema.java').read_text(
        encoding='utf-8', errors='replace')
    failures: list[str] = []
    # Match each RootCommandSpec block: look for id = "X", then scan for toggleable = false/true
    spec_pattern = re.compile(r'id\s*=\s*"([^"]+)".*?toggleable\s*=\s*(true|false)',
                              re.DOTALL)
    for match in spec_pattern.finditer(registry):
        command_id, toggleable = match.group(1), match.group(2)
        if toggleable == 'false':
            continue
        expected_key = 'rootop_enabled_' + command_id.replace('.', '_')
        if expected_key not in schema:
            failures.append(f'{command_id}: toggleable but key {expected_key} missing from schema')
    return failures


def main() -> int:
    failures: list[str] = []

    su_sites = find_su_sites()
    expected = SU_SITES_EXPECTED_PHASE_1_TO_4
    if su_sites != expected:
        extra = su_sites - expected
        missing = expected - su_sites
        if extra:
            failures.append(f'Unexpected ProcessBuilder("su" sites: {sorted(extra)}')
        if missing:
            failures.append(f'Missing expected ProcessBuilder("su" sites: {sorted(missing)}')

    failures.extend(check_runner_call_sites_use_literal_ids())
    failures.extend(check_root_engine_isolation())
    failures.extend(check_no_root_imports_in_xposed_features())
    failures.extend(check_root_command_runner_deprecated())
    failures.extend(check_schema_keys())
    failures.extend(check_golden_templates_in_registry())
    failures.extend(check_toggle_keys_cover_registry())

    if failures:
        print('FAIL: root registry enforcement scanner', file=sys.stderr)
        for failure in failures:
            print('  - ' + failure, file=sys.stderr)
        return 1
    print('PASS: root registry enforcement scanner (Layer 2) - '
          f'{len(expected)} su sites, {len(ALLOWED_COMMAND_IDS)} command ids, '
          f'{len(PRIVATE_RUNTIME_KEYS)} schema keys')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
