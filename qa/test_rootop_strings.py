#!/usr/bin/env python3
"""String-parity scanner for the root operations resource files (Layer 3).

Pure Python, no SDK. Verifies that:
  - values/root_operations.xml and values-in/root_operations.xml have the same
    key set, in the same relative order
  - Every command id in RootCommandRegistry.kt has all three rootop_*_title /
    _desc / _detail keys present in both locales
  - Chrome keys exist in both locales
  - No empty strings; no unescaped raw quotes breaking ElementTree parsing
  - The two parametrized strings (rootop_consent_body %1$d, rootop_toast_step_blocked
    %1$s) carry identical positional format args in EN and ID
  - No bare '%' outside the allowlisted positional patterns
  - ID length within ±20% of EN length (warning, not failure)
"""
from __future__ import annotations

import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
VALUES = ROOT / 'app/src/main/res/values/root_operations.xml'
VALUES_IN = ROOT / 'app/src/main/res/values-in/root_operations.xml'
REGISTRY = ROOT / 'app/src/main/java/com/waenhancer/root/RootCommandRegistry.kt'

# Keys whose ID length may legitimately diverge by >20% because they contain
# technical terms kept in English in both locales.
LENGTH_TOLERANCE_OVERRIDE = frozenset()


def parse_keys(path: Path) -> list[tuple[str, str]]:
    tree = ET.parse(path)
    root = tree.getroot()
    entries = []
    for string in root.findall('string'):
        name = string.get('name')
        if name is None:
            continue
        text = string.text or ''
        entries.append((name, text))
    # Also parse <plurals> elements — each has a name attribute and <item> children.
    for plurals in root.findall('plurals'):
        name = plurals.get('name')
        if name is None:
            continue
        items = [item.text or '' for item in plurals.findall('item')]
        # Join all quantity variants for format-arg checking.
        entries.append((name, ' '.join(items)))
    return entries


def parse_registry_ids() -> set[str]:
    text = REGISTRY.read_text(encoding='utf-8')
    ids = set()
    # Direct string-literal ids: id = "..."
    for match in re.finditer(r'id\s*=\s*"([^"]+)"', text):
        ids.add(match.group(1))
    # Constant references: const val PROBE_ID = "root.probe.id"
    # Resolve the constant into the registry id set so the scanner enforces
    # parity for the probe entry too.
    const_to_value = {}
    for match in re.finditer(r'const\s+val\s+(\w+)\s*=\s*"([^"]+)"', text):
        const_to_value[match.group(1)] = match.group(2)
    # Find id = CONSTANT_NAME references and resolve them.
    for match in re.finditer(r'id\s*=\s*(\w+)', text):
        name = match.group(1)
        if name in const_to_value:
            ids.add(const_to_value[name])
    return ids


def check_format_args(name: str, en: str, id_text: str) -> list[str]:
    """Verify the full set of %N$X format tokens is identical in EN and ID."""
    failures: list[str] = []
    format_pattern = re.compile(r'%\d+\$[sdf]')
    en_tokens = sorted(set(format_pattern.findall(en)))
    id_tokens = sorted(set(format_pattern.findall(id_text)))
    if en_tokens != id_tokens:
        failures.append(
            f'{name}: format-arg set mismatch (EN={en_tokens}, ID={id_tokens})')
    # No bare '%' either (would need %% + formatted=false)
    bare_en = re.findall(r'(?<!%)%(?!\d)', en)
    bare_id = re.findall(r'(?<!%)%(?!\d)', id_text)
    if bare_en:
        failures.append(f'{name}: bare % in EN')
    if bare_id:
        failures.append(f'{name}: bare % in ID')
    return failures


def check_strings_referenced_from_code(en_keys: list[str]) -> list[str]:
    """Every rootop_* key must be referenced from at least one .kt/.java file."""
    failures: list[str] = []
    java_dir = ROOT / 'app/src/main/java'
    all_source = []
    for path in java_dir.rglob('*'):
        if not path.is_file() or path.suffix not in {'.java', '.kt'}:
            continue
        all_source.append(path.read_text(encoding='utf-8', errors='replace'))
    combined = '\n'.join(all_source)
    for key in en_keys:
        if f'R.string.{key}' not in combined and f'R.plurals.{key}' not in combined:
            failures.append(f'{key}: not referenced from any .kt/.java source file')
    return failures


def check_unescaped_apostrophes(entries: list[tuple[str, str]]) -> list[str]:
    """Reject bare ' not preceded by backslash and not wrapped in double quotes."""
    failures: list[str] = []
    apostrophe_pattern = re.compile(r"(?<!\\)'(?![^\"]*\")")
    for name, text in entries:
        if apostrophe_pattern.search(text):
            failures.append(f'{name}: unescaped apostrophe')
    return failures



def main() -> int:
    failures: list[str] = []

    if not VALUES.exists():
        failures.append(f'missing EN file: {VALUES}')
    if not VALUES_IN.exists():
        failures.append(f'missing ID file: {VALUES_IN}')
    if failures:
        for f in failures:
            print('  - ' + f, file=sys.stderr)
        return 1

    en_entries = parse_keys(VALUES)
    id_entries = parse_keys(VALUES_IN)
    en_keys = [name for name, _ in en_entries]
    id_keys = [name for name, _ in id_entries]
    en_map = dict(en_entries)
    id_map = dict(id_entries)

    if en_keys != id_keys:
        en_set = set(en_keys)
        id_set = set(id_keys)
        only_en = en_set - id_set
        only_id = id_set - en_set
        for key in sorted(only_en):
            failures.append(f'key only in EN: {key}')
        for key in sorted(only_id):
            failures.append(f'key only in ID: {key}')
        # Also check order
        common_en = [k for k in en_keys if k in id_set]
        common_id = [k for k in id_keys if k in en_set]
        if common_en != common_id:
            failures.append('relative key order differs between EN and ID')

    # Empty strings
    for name, text in en_entries:
        if not text.strip():
            failures.append(f'EN: empty string {name}')
    for name, text in id_entries:
        if not text.strip():
            failures.append(f'ID: empty string {name}')

    # Registry spec <-> string parity
    registry_ids = parse_registry_ids()
    for command_id in sorted(registry_ids):
        underscored = command_id.replace('.', '_')
        for suffix in ('title', 'desc', 'detail'):
            key = f'rootop_{underscored}_{suffix}'
            if key not in en_map:
                failures.append(f'{command_id}: missing EN key {key}')
            if key not in id_map:
                failures.append(f'{command_id}: missing ID key {key}')

    # Format-arg signature parity (full set equality, not just one allowlisted token)
    for name, en_text in en_entries:
        if name not in id_map:
            continue
        id_text = id_map[name]
        failures.extend(check_format_args(name, en_text, id_text))

    # Verify every rootop_* key is referenced from at least one source file
    failures.extend(check_strings_referenced_from_code(en_keys))

    # Reject unescaped apostrophes (aapt would reject these at build time)
    failures.extend(check_unescaped_apostrophes(en_entries))
    failures.extend(check_unescaped_apostrophes(id_entries))

    # ID length within ±20% (warning)
    warnings = []
    for name, en_text in en_entries:
        if name not in id_map or name in LENGTH_TOLERANCE_OVERRIDE:
            continue
        id_text = id_map[name]
        if not en_text:
            continue
        ratio = len(id_text) / len(en_text)
        if ratio < 0.80 or ratio > 1.20:
            warnings.append(
                f'{name}: ID/EN length ratio {ratio:.2f} (EN={len(en_text)}, ID={len(id_text)})')

    if failures:
        print('FAIL: rootop string-parity scanner', file=sys.stderr)
        for failure in failures:
            print('  - ' + failure, file=sys.stderr)
        return 1
    print(f'PASS: rootop string-parity scanner (Layer 3) - '
          f'{len(en_keys)} keys, {len(registry_ids)} registry commands, '
          f'format-arg set parity enforced')
    for warning in warnings:
        print(f'  WARN: {warning}', file=sys.stderr)
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
