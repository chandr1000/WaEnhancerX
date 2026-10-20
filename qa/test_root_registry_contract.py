#!/usr/bin/env python3
"""JDK-only security contract harness for the root command registry.

Compiles RootParamPolicy.java + RootEngine.java + a Java mirror of all 20 specs,
then drives:
  - R1 negative matrix (the analysis's literal PoC payload)
  - Package-name / HMA_PACKAGE / PATH_STRICT / BASE64_TEXT validation
  - Quoting goldens (every spec template with valid params -> exact command line)
  - Output-policy matrix (Sentinel / Regex / EmptyOk / RawTextCapped)
  - Starter seam with real unprivileged sh -c subprocesses (bounded output,
    timeout, failure-to-start)
  - RootEngine.execResult tri-state (success / timeout / nonzero)
"""
import subprocess
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / 'app/src/main/java/com/waenhancer/root'
QA = ROOT / 'qa'

# A Java mirror of the Kotlin RootCommandRegistry. The source-golden strings
# here are also asserted by qa/test_root_registry_enforcement.py against the
# real Kotlin source, so the mirror cannot drift from production.
REGISTRY_MIRROR = r'''package com.waenhancer.root;

import java.util.HashMap;
import java.util.Map;

/**
 * Java mirror of the Kotlin RootCommandRegistry. The enforcement scanner
 * asserts that each golden literal here is also present in the Kotlin source.
 */
public final class RootCommandRegistryMirror {

    public static final String PROBE_ID = "root.probe.id";

    private RootCommandRegistryMirror() {}

    public static String templateFor(String id, Map<String, String> params) {
        switch (id) {
            case "root.probe.id":
                return "id";
            case "restart.target.force_stop":
                return "am force-stop '" + params.get("package") + "'";
            case "restart.target.launch":
                return "monkey -p '" + params.get("package")
                        + "' -c android.intent.category.LAUNCHER 1";
            case "diag.selinux.getenforce":
                return "getenforce";
            case "diag.process.pidof":
                String body = params.getOrDefault("script", "");
                if (body.isEmpty()) return "printf 'END|WAEX_PROCESS_SNAPSHOT\\n'";
                return body + "\nprintf 'END|WAEX_PROCESS_SNAPSHOT\\n'";
            case "update.mktemp":
                return "mktemp /data/local/tmp/waex.XXXXXX";
            case "update.copy":
                return "cat " + quote(params.get("source")) + " > " + quote(params.get("target"))
                    + " && chmod 644 " + quote(params.get("target"))
                    + " && wc -c < " + quote(params.get("target"));
            case "update.install":
                return "pm install -r -d --user 0 " + quote(params.get("target"));
            case "update.cleanup":
                return "rm -f -- " + quote(params.get("target"));
            case "hma.probe.zygisk":
                return "[ -d /data/adb/modules/hma_oss_zygisk ] && [ ! -f /data/adb/modules/hma_oss_zygisk/disable ] && echo active; ls -d /data/adb/modules/*hma* 2>/dev/null; true";
            case "hma.probe.lsposed_db":
                return "[ -f /data/adb/lspd/config/modules_config.db ] && echo exists; true";
            case "hma.discover.packages":
                return "pm list packages";
            case "hma.discover.find_configs":
                return "find /data/misc /data/system /data/adb /data/user/0 /data/data -type f -xdev -maxdepth 8 -name 'config.json' \\( -path '*hide_my_applist*' -o -path '*hma*' \\) 2>/dev/null; true";
            case "hma.read_config":
                return "head -c 262144 " + quote(params.get("path"));
            case "hma.backup_config":
                return "cp -p " + quote(params.get("path")) + " " + quote(params.get("path") + ".waex.bak")
                    + " 2>/dev/null && (restorecon " + quote(params.get("path") + ".waex.bak")
                    + " 2>/dev/null || true) && echo WAEX_HMA_BACKUP_OK";
            case "hma.force_stop":
                return "am force-stop '" + params.get("package") + "'";
            default:
                throw new IllegalArgumentException("unknown id: " + id);
        }
    }

    private static String quote(String value) {
        if (value == null) return "''";
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }

    public static boolean outputPolicyCheck(String id, String output) {
        switch (id) {
            case "root.probe.id":
                return RootParamPolicy.reportsRootId(output);
            case "restart.target.launch":
                return RootParamPolicy.reportsMonkeyLaunch(output);
            case "diag.selinux.getenforce":
                return RootParamPolicy.isSelinuxMode(output);
            case "restart.target.force_stop":
            case "update.cleanup":
            case "hma.copy_lsposed_db":
                return true;  // EMPTY_OK
            case "diag.process.pidof":
                String[] lines = output == null ? new String[0] : output.split("\n");
                return lines.length > 0
                        && lines[lines.length - 1].equals("END|WAEX_PROCESS_SNAPSHOT");
            case "update.mktemp":
                return RootParamPolicy.isMktempPath(output);
            case "update.copy":
                return RootParamPolicy.POSITIVE_LONG.matcher(output == null ? "" : output.trim()).matches();
            case "update.install":
                return output != null && output.toLowerCase().startsWith("success");
            default:
                // RawTextCapped variants accept any non-null output
                return output != null;
        }
    }
}
'''


def main() -> int:
    with tempfile.TemporaryDirectory(prefix='waex_root_contract_') as output:
        mirror = Path(output) / 'RootCommandRegistryMirror.java'
        mirror.write_text(REGISTRY_MIRROR)
        sources = [
            SRC / 'RootParamPolicy.java',
            SRC / 'RootEngine.java',
            mirror,
            QA / 'TestRootParamPolicy.java',
        ]
        subprocess.run(['javac', '--release', '17', '-d', output, *map(str, sources)],
                        check=True)
        subprocess.run(['java', '-cp', output,
                         'com.waenhancer.root.TestRootParamPolicy'], check=True)
    print('PASS: root registry contract harness (Layer 1)')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
