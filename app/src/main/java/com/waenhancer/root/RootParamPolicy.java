package com.waenhancer.root;

import java.util.regex.Pattern;

/**
 * Pure-Java validators and shell-quoting helpers for root command parameters.
 *
 * <p>Compiled directly into the JDK-only QA harness (no Android dependencies),
 * so every call site inherits the same validation and the R1/R3/R7 divergence
 * class cannot re-emerge.</p>
 */
public final class RootParamPolicy {

    /** Well-formed Android package name (Java identifier style). */
    public static final Pattern PACKAGE_NAME = Pattern.compile(
            "[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)+");

    /** Strict /data path: positive allowlist only, rejects every shell metacharacter. */
    public static final Pattern PATH_STRICT = Pattern.compile("^/data/[A-Za-z0-9_./-]+$");

    /** Root-id token on a word boundary; shared by the probe and {@code root.probe.id} output policy. */
    public static final Pattern ROOT_ID = Pattern.compile(
            "(?s).*(?:^|\\s)uid=0(?:\\(|\\s|$).*");

    /** {@code monkey} success line; shared by {@code restart.target.launch} output policy. */
    public static final Pattern MONKEY_LAUNCHED = Pattern.compile(
            "(?s).*\\bEvents injected:\\s*[1-9][0-9]*\\b.*");

    /** {@code getenforce} output values. */
    public static final Pattern SELINUX_MODE = Pattern.compile(
            "^(Enforcing|Permissive|Disabled)$");

    /** {@code mktemp} staging path returned under /data/local/tmp. */
    public static final Pattern MKTEMP_PATH = Pattern.compile(
            "^/data/local/tmp/waex\\.[A-Za-z0-9]+$");

    private RootParamPolicy() {}

    public static boolean isValidPackageName(String value) {
        return value != null && value.length() <= 223
                && PACKAGE_NAME.matcher(value).matches();
    }

    /**
     * HMA-family package: a well-formed package name whose lower-cased form matches
     * one of the known HMA substrings. Replaces the raw substring filter at the
     * {@code am force-stop} call site (R3 fix).
     */
    public static boolean isHmaPackage(String value) {
        if (!isValidPackageName(value)) return false;
        String lower = value.toLowerCase();
        return lower.contains("hma") || lower.contains("hidemyapp") || lower.endsWith(".hmal");
    }

    public static boolean isValidPathStrict(String value) {
        if (value == null || value.length() > 4096) return false;
        if (value.contains("..")) return false;
        return PATH_STRICT.matcher(value).matches();
    }

    public static boolean reportsRootId(String output) {
        return output != null && ROOT_ID.matcher(output).matches();
    }

    public static boolean reportsMonkeyLaunch(String output) {
        return output != null && MONKEY_LAUNCHED.matcher(output).matches();
    }

    public static boolean isSelinuxMode(String output) {
        return output != null && SELINUX_MODE.matcher(output.trim()).matches();
    }

    public static boolean isMktempPath(String output) {
        return output != null && MKTEMP_PATH.matcher(output.trim()).matches();
    }

    /** POSIX single-quote wrapping with embedded single-quotes escaped as {@code '"'"'}. */
    public static String shellQuote(String value) {
        if (value == null) return "''";
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }
}
