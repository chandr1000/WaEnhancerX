package com.waenhancer.root;

import java.util.HashMap;
import java.util.Map;

/**
 * JDK-only regression for {@link RootParamPolicy} and the registry's parameter
 * validation layer. Runs without Android; compiled by {@code qa/test_root_registry_contract.py}.
 */
public final class TestRootParamPolicy {
    private static int checks;
    private static void check(boolean result, String message) {
        checks++;
        if (!result) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        // --- PACKAGE_NAME ---
        check(RootParamPolicy.isValidPackageName("com.whatsapp"), "valid package: com.whatsapp");
        check(RootParamPolicy.isValidPackageName("com.whatsapp.w4b"), "valid package: com.whatsapp.w4b");
        check(RootParamPolicy.isValidPackageName("com.gbwhatsapp"), "valid clone package");
        check(!RootParamPolicy.isValidPackageName(null), "null package rejected");
        check(!RootParamPolicy.isValidPackageName(""), "empty package rejected");
        check(!RootParamPolicy.isValidPackageName("com.whatsapp;reboot"), "semicolon rejected");
        check(!RootParamPolicy.isValidPackageName("com.whatsapp$(touch /tmp/p)"), "command substitution rejected");
        check(!RootParamPolicy.isValidPackageName("com.whatsapp `id`"), "backtick rejected");
        check(!RootParamPolicy.isValidPackageName("com.whatsapp | cat"), "pipe rejected");
        check(!RootParamPolicy.isValidPackageName("com.whatsapp'"), "trailing quote rejected");
        check(!RootParamPolicy.isValidPackageName("com.whatsapp w4b"), "whitespace rejected");

        // --- HMA_PACKAGE ---
        check(RootParamPolicy.isHmaPackage("com.hma.app"), "hma substring matches");
        check(RootParamPolicy.isHmaPackage("com.hidemyapp"), "hidemyapp substring matches");
        check(RootParamPolicy.isHmaPackage("com.example.hmal"), ".hmal suffix matches");
        check(!RootParamPolicy.isHmaPackage("com.whatsapp"), "non-HMA package rejected");
        check(!RootParamPolicy.isHmaPackage("hma"), "not a package name");
        check(!RootParamPolicy.isHmaPackage("com.hma$(touch /tmp/p)"), "metacharacter in HMA name rejected");

        // --- PATH_STRICT (the R1 fix target, exercised in P5 but tested here) ---
        check(RootParamPolicy.isValidPathStrict("/data/data/com.whma/config.json"), "valid /data path");
        check(RootParamPolicy.isValidPathStrict("/data/local/tmp/waex.Abc123"), "valid staging path");
        check(!RootParamPolicy.isValidPathStrict(null), "null path rejected");
        check(!RootParamPolicy.isValidPathStrict(""), "empty path rejected");
        check(!RootParamPolicy.isValidPathStrict("/system/bin/su"), "non-/data path rejected");
        // R1 literal PoC payload — the named regression case
        check(!RootParamPolicy.isValidPathStrict("/data/user/0/com.attacker/hma$(touch /data/local/tmp/pwned)/config.json"),
                "R1 PoC payload rejected: hma$(touch /data/local/tmp/pwned)/config.json");
        check(!RootParamPolicy.isValidPathStrict("/data/hma`id`/config.json"), "backtick rejected");
        check(!RootParamPolicy.isValidPathStrict("/data/hma;reboot/config.json"), "semicolon rejected");
        check(!RootParamPolicy.isValidPathStrict("/data/hma|cat/config.json"), "pipe rejected");
        check(!RootParamPolicy.isValidPathStrict("/data/hma config/config.json"), "whitespace rejected");
        check(!RootParamPolicy.isValidPathStrict("/data/hma\"x\"/config.json"), "double quote rejected");
        check(!RootParamPolicy.isValidPathStrict("/data/hma$(echo p)/config.json"), "$(...) rejected");
        check(!RootParamPolicy.isValidPathStrict("/data/../etc/passwd"), "directory traversal rejected");

        // --- ROOT_ID output policy (R7 unification) ---
        check(RootParamPolicy.reportsRootId("uid=0(root) gid=0(root)"), "uid=0 with parens");
        check(RootParamPolicy.reportsRootId("uid=0"), "bare uid=0");
        check(RootParamPolicy.reportsRootId("uid=0 root"), "uid=0 with trailing space");
        check(!RootParamPolicy.reportsRootId("uid=1000"), "uid=1000 not root");
        check(!RootParamPolicy.reportsRootId("garbage uid=0xyz"), "uid=0xyz not a boundary match");
        check(!RootParamPolicy.reportsRootId(null), "null output rejected");
        check(!RootParamPolicy.reportsRootId(""), "empty output rejected");

        // --- MONKEY_LAUNCHED output policy ---
        check(RootParamPolicy.reportsMonkeyLaunch("Events injected: 1"), "single event");
        check(RootParamPolicy.reportsMonkeyLaunch("Events injected: 42"), "multiple events");
        check(!RootParamPolicy.reportsMonkeyLaunch("Events injected: 0"), "zero events not a launch");
        check(!RootParamPolicy.reportsMonkeyLaunch(""), "empty monkey output rejected");

        // --- SELINUX_MODE output policy ---
        check(RootParamPolicy.isSelinuxMode("Enforcing"), "Enforcing");
        check(RootParamPolicy.isSelinuxMode("Permissive"), "Permissive");
        check(RootParamPolicy.isSelinuxMode("Disabled"), "Disabled");
        check(!RootParamPolicy.isSelinuxMode("enforcing"), "lowercase not normalized");
        check(!RootParamPolicy.isSelinuxMode(""), "empty selinux output rejected");
        check(!RootParamPolicy.isSelinuxMode("garbage"), "garbage selinux output rejected");

        // --- MKTEMP_PATH ---
        check(RootParamPolicy.isMktempPath("/data/local/tmp/waex.Abc123"), "valid mktemp path");
        check(!RootParamPolicy.isMktempPath("/data/local/tmp/waex.Abc123; rm -rf /"), "metacharacter in mktemp path rejected");
        check(!RootParamPolicy.isMktempPath("/tmp/waex.X"), "non-/data/local/tmp path rejected");

        // --- shellQuote (POSIX single-quote wrapping) ---
        check(RootParamPolicy.shellQuote("hello").equals("'hello'"), "simple quoting");
        check(RootParamPolicy.shellQuote("").equals("''"), "empty quoting");
        check(RootParamPolicy.shellQuote("a'b").equals("'a'\"'\"'b'"), "embedded single-quote escaped");
        check(RootParamPolicy.shellQuote("/data/local/tmp/waex.abc").equals("'/data/local/tmp/waex.abc'"), "path quoting");
        check(RootParamPolicy.shellQuote(null).equals("''"), "null quoting");

        // --- Registry mirror: every spec template with valid params -> exact expected command line ---
        Map<String, String> pkg = new HashMap<>();
        pkg.put("package", "com.whatsapp");
        check(RootCommandRegistryMirror.templateFor("root.probe.id", new HashMap<>()).equals("id"),
                "probe template");
        check(RootCommandRegistryMirror.templateFor("restart.target.force_stop", pkg).equals("am force-stop 'com.whatsapp'"),
                "force_stop template");
        check(RootCommandRegistryMirror.templateFor("restart.target.launch", pkg).equals(
                "monkey -p 'com.whatsapp' -c android.intent.category.LAUNCHER 1"), "launch template");
        check(RootCommandRegistryMirror.templateFor("diag.selinux.getenforce", new HashMap<>()).equals("getenforce"),
                "getenforce template");
        Map<String, String> pidofParams = new HashMap<>();
        pidofParams.put("packages", "com.whatsapp\ncom.whatsapp.w4b");
        pidofParams.put("script", "command -v pidof >/dev/null 2>&1 || exit 127\npids=...");
        String pidofTemplate = RootCommandRegistryMirror.templateFor("diag.process.pidof", pidofParams);
        check(pidofTemplate.endsWith("\nprintf 'END|WAEX_PROCESS_SNAPSHOT\\n'"),
                "pidof template appends sentinel");

        // --- Output-policy matrix ---
        check(RootCommandRegistryMirror.outputPolicyCheck("root.probe.id", "uid=0(root)"), "probe policy pass");
        check(!RootCommandRegistryMirror.outputPolicyCheck("root.probe.id", "uid=1000"), "probe policy fail");
        check(RootCommandRegistryMirror.outputPolicyCheck("restart.target.force_stop", ""), "force_stop empty ok");
        check(RootCommandRegistryMirror.outputPolicyCheck("restart.target.force_stop", "anything"), "force_stop non-empty ok");
        check(RootCommandRegistryMirror.outputPolicyCheck("restart.target.launch", "Events injected: 1"), "launch policy pass");
        check(!RootCommandRegistryMirror.outputPolicyCheck("restart.target.launch", "no events"), "launch policy fail");
        check(RootCommandRegistryMirror.outputPolicyCheck("diag.selinux.getenforce", "Enforcing"), "getenforce pass");
        check(!RootCommandRegistryMirror.outputPolicyCheck("diag.selinux.getenforce", "garbage"), "getenforce fail");
        check(RootCommandRegistryMirror.outputPolicyCheck("diag.process.pidof", "PID|com.whatsapp|123\nEND|WAEX_PROCESS_SNAPSHOT"),
                "pidof sentinel present");
        check(!RootCommandRegistryMirror.outputPolicyCheck("diag.process.pidof", "PID|com.whatsapp|123"),
                "pidof missing sentinel fails");

        System.out.println("PASS: " + checks + " RootParamPolicy + registry template + output-policy checks");
    }
}
