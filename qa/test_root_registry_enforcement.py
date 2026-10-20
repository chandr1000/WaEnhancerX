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

SU_SITES_EXPECTED_PHASE_5 = {
    'app/src/main/java/com/waenhancer/root/RootEngine.java',
}

# Phase 5 is now active: exactly one su site (the kernel), zero legacy doors.
SU_SITES_EXPECTED_PHASE_1_TO_4 = SU_SITES_EXPECTED_PHASE_5

ALLOWED_COMMAND_IDS = {
    'root.probe.id',
    'restart.target.force_stop',
    'restart.target.launch',
    'diag.process.pidof',
    'diag.selinux.getenforce',
    'update.mktemp',
    'update.copy',
    'update.install',
    'update.cleanup',
    'hma.probe.zygisk',
    'hma.probe.lsposed_db',
    'hma.copy_lsposed_db',
    'hma.discover.packages',
    'hma.discover.find_configs',
    'hma.read_config',
    'hma.backup_config',
    'hma.write_config',
    'hma.restore_config',
    'hma.force_stop',
    # §6.5: debug-build-only entry; release builds exclude it via BuildConfig.DEBUG.
    'debug.echo',
}

GOLDEN_TEMPLATES = {
    'root.probe.id': "id",
    'restart.target.force_stop': "am force-stop '",
    'restart.target.launch': "monkey -p '",
    'diag.selinux.getenforce': "getenforce",
    'diag.process.pidof': "END|WAEX_PROCESS_SNAPSHOT",
    'update.mktemp': "mktemp /data/local/tmp/waex.XXXXXX",
    'update.copy': "wc -c <",
    'update.install': "pm install -r -d --user 0",
    'update.cleanup': "rm -f --",
    'hma.probe.zygisk': "/data/adb/modules/hma_oss_zygisk",
    'hma.probe.lsposed_db': "/data/adb/lspd/config/modules_config.db",
    'hma.copy_lsposed_db': "modules_config.db-wal",
    'hma.discover.packages': "pm list packages",
    'hma.discover.find_configs': "-type f -xdev -maxdepth 8",
    'hma.read_config': "head -c 262144",
    'hma.backup_config': "WAEX_HMA_BACKUP_OK",
    'hma.write_config': "WAEX_HMA_WRITE_OK",
    'hma.restore_config': "WAEX_HMA_RESTORE_OK",
    'hma.force_stop': "am force-stop '",
}

PRIVATE_RUNTIME_KEYS = [
    'rootop_enabled_restart_target_force_stop',
    'rootop_enabled_restart_target_launch',
    'rootop_enabled_diag_process_pidof',
    'rootop_enabled_diag_selinux_getenforce',
    'rootop_enabled_update_mktemp',
    'rootop_enabled_update_copy',
    'rootop_enabled_update_install',
    'rootop_enabled_update_cleanup',
    'rootop_enabled_hma_probe_zygisk',
    'rootop_enabled_hma_probe_lsposed_db',
    'rootop_enabled_hma_copy_lsposed_db',
    'rootop_enabled_hma_discover_packages',
    'rootop_enabled_hma_discover_find_configs',
    'rootop_enabled_hma_read_config',
    'rootop_enabled_hma_backup_config',
    'rootop_enabled_hma_write_config',
    'rootop_enabled_hma_restore_config',
    'rootop_enabled_hma_force_stop',
    'rootop_enabled_debug_echo',
    'root_audit_log_enabled',
    'root_consent_reviewed',
]


def find_su_sites() -> set[str]:
    """Find all files that spawn su. Matches multiple idioms:
    ProcessBuilder("su"..., Runtime.exec("su..., and ProcessBuilder(listOf("su"..."""
    sites = set()
    su_patterns = [
        'ProcessBuilder("su"',
        'Runtime.exec("su',
        '"su", "-c"',  # listOf("su", "-c", ...) form
    ]
    for path in APP.rglob('*'):
        if not path.is_file() or path.suffix not in {'.java', '.kt'}:
            continue
        text = path.read_text(encoding='utf-8', errors='replace')
        for pattern in su_patterns:
            if pattern in text:
                sites.add(str(path.relative_to(ROOT)))
                break
    return sites


def check_runner_call_sites_use_literal_ids() -> list[str]:
    """Every RegisteredRootRunner.run(...) call site must pass a literal command id
    that is in ALLOWED_COMMAND_IDS. Also detects non-literal call sites (variable
    arg) which the old regex silently approved."""
    failures: list[str] = []
    literal_pattern = re.compile(r'RegisteredRootRunner\.run\(\s*"([^"]+)"')
    any_call_pattern = re.compile(r'RegisteredRootRunner\.run\(')
    literal_ids_seen: set[str] = set()
    for path in APP.rglob('*'):
        if not path.is_file() or path.suffix not in {'.java', '.kt'}:
            continue
        text = path.read_text(encoding='utf-8', errors='replace')
        # Find all call sites
        all_calls = list(any_call_pattern.finditer(text))
        literal_calls = list(literal_pattern.finditer(text))
        # If there are more calls than literal matches, some use non-literal args
        if len(all_calls) > len(literal_calls):
            failures.append(
                f'{path.relative_to(ROOT)}: {len(all_calls) - len(literal_calls)} '
                f'call site(s) use non-literal command id (must be a string literal)')
        for match in literal_calls:
            command_id = match.group(1)
            literal_ids_seen.add(command_id)
            if command_id not in ALLOWED_COMMAND_IDS:
                failures.append(
                    f'{path.relative_to(ROOT)}: unknown command id "{command_id}"')
    return failures


def check_root_gate_uses_private_store() -> list[str]:
    """RootGate.load/setEnabled/setAuditLogEnabled/markConsentReviewed/isConsentReviewed
    must all call PreferenceStores.privateStore (not publicStore)."""
    failures: list[str] = []
    gate = (APP / 'com/waenhancer/root/RootGate.kt').read_text(encoding='utf-8', errors='replace')
    expected_call = 'PreferenceStores.privateStore'
    # Count occurrences — should appear in load, setEnabled, setAuditLogEnabled,
    # markConsentReviewed, isConsentReviewed (5 call sites).
    count = gate.count(expected_call)
    if count < 5:
        failures.append(f'RootGate.kt: only {count} privateStore calls (expected >= 5)')
    if 'publicStore' in gate:
        failures.append('RootGate.kt: must never call publicStore (world-readable)')
    return failures


def check_root_engine_isolation() -> list[str]:
    failures: list[str] = []
    # Phase 5: RootCommandRunner shim is deleted, so no production code outside
    # com.waenhancer.root may reference RootEngine.
    for path in APP.rglob('*'):
        if not path.is_file() or path.suffix not in {'.java', '.kt'}:
            continue
        rel = str(path.relative_to(ROOT))
        if rel == 'app/src/main/java/com/waenhancer/root/RootEngine.java':
            continue
        if rel.startswith('app/src/main/java/com/waenhancer/root/'):
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
    # Phase 5: the RootCommandRunner shim and AppRestartHelper.runRootCommand
    # raw door must both be gone. Exactly one su site remains (RootEngine).
    failures: list[str] = []
    runner = APP / 'com/waenhancer/xposed/utils/RootCommandRunner.java'
    if runner.exists():
        failures.append(f'{runner.relative_to(ROOT)}: shim must be deleted in Phase 5')
    helper = (APP / 'com/waenhancer/xposed/utils/AppRestartHelper.java').read_text(
        encoding='utf-8', errors='replace')
    if 'runRootCommand' in helper:
        failures.append('AppRestartHelper.runRootCommand: raw door must be deleted in Phase 5')
    return failures


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
    """Every toggleable spec in the registry must have a rootop_enabled_* key in schema.
    Resolves const-ref ids (e.g. id = PROBE_ID) so the probe spec is not silently skipped."""
    registry = (APP / 'com/waenhancer/root/RootCommandRegistry.kt').read_text(
        encoding='utf-8', errors='replace')
    schema = (APP / 'com/waenhancer/config/PreferenceSchema.java').read_text(
        encoding='utf-8', errors='replace')
    # Build a map of const val name -> value so we can resolve id = PROBE_ID references.
    const_to_value: dict[str, str] = {}
    for m in re.finditer(r'const\s+val\s+(\w+)\s*=\s*"([^"]+)"', registry):
        const_to_value[m.group(1)] = m.group(2)
    # Match each RootCommandSpec block: look for id = "X" or id = CONST, then scan
    # for toggleable = false/true within the same block.
    spec_pattern = re.compile(r'id\s*=\s*("[^"]+"|\w+).*?toggleable\s*=\s*(true|false)',
                              re.DOTALL)
    failures: list[str] = []
    for match in spec_pattern.finditer(registry):
        id_expr = match.group(1)
        toggleable = match.group(2)
        # Resolve const references
        if id_expr.startswith('"'):
            command_id = id_expr.strip('"')
        elif id_expr in const_to_value:
            command_id = const_to_value[id_expr]
        else:
            failures.append(f'unresolvable id expression: {id_expr}')
            continue
        if toggleable == 'false':
            continue
        expected_key = 'rootop_enabled_' + command_id.replace('.', '_')
        if expected_key not in schema:
            failures.append(f'{command_id}: toggleable but key {expected_key} missing from schema')
    return failures


def check_debug_echo_debug_guarded() -> list[str]:
    """The debug.echo spec must be registered inside an `if (BuildConfig.DEBUG)` guard."""
    registry = (APP / 'com/waenhancer/root/RootCommandRegistry.kt').read_text(
        encoding='utf-8', errors='replace')
    failures: list[str] = []
    if 'id = "debug.echo"' not in registry:
        failures.append('debug.echo: spec not registered in RootCommandRegistry')
        return failures
    # Find the spec block and verify it sits inside a BuildConfig.DEBUG guard.
    spec_index = registry.index('id = "debug.echo"')
    # Look backwards from the spec for the nearest if (BuildConfig.DEBUG)
    preceding = registry[:spec_index]
    last_buildconfig_if = preceding.rfind('if (BuildConfig.DEBUG)')
    last_close = preceding.rfind('}')
    if last_buildconfig_if == -1 or last_close > last_buildconfig_if:
        failures.append('debug.echo: spec must be registered inside `if (BuildConfig.DEBUG)`')
    # Verify the literal "debug.echo" appears only at debug call sites or the registry.
    allowed_files_for_debug_echo = {
        'app/src/main/java/com/waenhancer/root/RootCommandRegistry.kt',
        'app/src/main/java/com/waenhancer/ui/screens/root/PrivilegedOperationsScreen.kt',
        'app/src/main/java/com/waenhancer/app/RootConsentReviewScreen.kt',
    }
    for path in APP.rglob('*'):
        if not path.is_file() or path.suffix not in {'.java', '.kt'}:
            continue
        rel = str(path.relative_to(ROOT))
        if rel in allowed_files_for_debug_echo:
            continue
        if rel.startswith('qa/'):
            continue
        text = path.read_text(encoding='utf-8', errors='replace')
        if '"debug.echo"' in text or 'id = "debug.echo"' in text:
            failures.append(f'{rel}: debug.echo literal only allowed in registry + UI')
    return failures


def check_backup_excludes_rootop_keys() -> list[str]:
    """No rootop_enabled_* / root_audit_log_enabled / root_consent_reviewed key may be
    registered with Sensitivity.PUBLIC_SETTING or PRIVATE_SETTING (which are
    exportable). They must be Sensitivity.RUNTIME (never exported)."""
    failures: list[str] = []
    schema = (APP / 'com/waenhancer/config/PreferenceSchema.java').read_text(
        encoding='utf-8', errors='replace')
    # Find every rootop_* or root_* key and check its sensitivity.
    pattern = re.compile(
        r'add\(entries,\s*"(rootop_enabled_[^"]+|root_audit_log_enabled|root_consent_reviewed)",'
        r'\s*Type\.\w+,\s*Sensitivity\.(\w+),\s*Store\.(\w+)\)'
    )
    for match in pattern.finditer(schema):
        key, sensitivity, store = match.group(1), match.group(2), match.group(3)
        if sensitivity in ('PUBLIC_SETTING', 'PRIVATE_SETTING'):
            failures.append(
                f'{key}: sensitivity={sensitivity} (must be RUNTIME so backups cannot '
                f'silently re-enable a root capability)')
        if store != 'PRIVATE':
            failures.append(f'{key}: store={store} (must be PRIVATE)')
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
    failures.extend(check_root_gate_uses_private_store())
    failures.extend(check_root_engine_isolation())
    failures.extend(check_no_root_imports_in_xposed_features())
    failures.extend(check_root_command_runner_deprecated())
    failures.extend(check_schema_keys())
    failures.extend(check_backup_excludes_rootop_keys())
    failures.extend(check_golden_templates_in_registry())
    failures.extend(check_toggle_keys_cover_registry())
    failures.extend(check_debug_echo_debug_guarded())

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
