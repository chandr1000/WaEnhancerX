package com.waenhancer.xposed.utils;

import com.waenhancer.root.RootEngine;

import java.io.IOException;

/**
 * Deprecated delegating shim preserved so {@code qa/TestRootCommandRunner.java} keeps
 * compiling until Phase 5. New code must call {@code RegisteredRootRunner.run(...)}.
 *
 * @deprecated Use {@link com.waenhancer.root.RegisteredRootRunner} for any new call site.
 */
@Deprecated
public final class RootCommandRunner {
    /** Shared bounded root execution for the manager and injected utility callers. */
    public static String run(String command, long timeoutMillis) {
        return RootEngine.exec(command, timeoutMillis);
    }

    @Deprecated
    @FunctionalInterface
    interface Starter { Process start(String command) throws IOException; }

    private RootCommandRunner() {}

    /** A null result means startup, exit, timeout or stream processing failed. Empty output can be success. */
    @Deprecated
    static String run(String command, long timeoutMillis, Starter starter) {
        if (starter == null) return null;
        return RootEngine.exec(command, timeoutMillis, starter::start);
    }
}
