package com.waenhancer.root;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * Bounded root execution kernel. Ported 1:1 from the original `RootCommandRunner`:
 * poll-based wait (minSdk 24 has no reliably-linked `waitFor(timeout)`), always-draining
 * reader thread, 8 KiB capture cap, `destroy()` + reflective `destroyForcibly()`,
 * fail-closed `null` contract.
 *
 * <p>The `Starter` seam is `public` (rather than package-private
 * so the deprecated `RootCommandRunner` shim could delegate
 * cross-package during the Phase 1-4 migration window. With the shim deleted
 * in Phase 5, the seam is kept public so the JDK-only QA harness
 * (`qa/TestRootEngine.java`) can drive real unprivileged `sh -c` subprocesses
 * against the production kernel without a real device or `su` grant.</p>
 */
public final class RootEngine {

    private static final int MAX_CAPTURE_CHARS = 8192;
    private static final int READ_CHUNK = 1024;

    @FunctionalInterface
    public interface Starter { Process start(String command) throws IOException; }

    /** Default root shell starter: {@code su -c <command>} with stderr merged into stdout. */
    static final Starter SU_STARTER =
            value -> new ProcessBuilder("su", "-c", value).redirectErrorStream(true).start();

    private RootEngine() {}

    /** A null result means startup, exit, timeout or stream processing failed. Empty output can be success. */
    public static String exec(String command, long timeoutMillis) {
        return exec(command, timeoutMillis, SU_STARTER);
    }

    public static String exec(String command, long timeoutMillis, Starter starter) {
        Result r = execResult(command, timeoutMillis, starter);
        return r.ok ? r.output : null;
    }

    public static Result execResult(String command, long timeoutMillis) {
        return execResult(command, timeoutMillis, SU_STARTER);
    }

    /** Tri-state result so the runner can distinguish timeout from nonzero-exit. */
    public static final class Result {
        public final boolean ok;
        public final boolean timedOut;
        public final String output;
        private Result(boolean ok, boolean timedOut, String output) {
            this.ok = ok; this.timedOut = timedOut; this.output = output;
        }
        static final Result TIMEOUT = new Result(false, true, null);
        static final Result NONZERO = new Result(false, false, null);
        static Result success(String output) { return new Result(true, false, output); }
    }

    public static Result execResult(String command, long timeoutMillis, Starter starter) {
        if (command == null || command.trim().isEmpty() || timeoutMillis <= 0 || starter == null) {
            return Result.NONZERO;
        }
        Process process = null;
        Thread drain = null;
        StringBuilder output = new StringBuilder(MAX_CAPTURE_CHARS);
        try {
            process = starter.start(command);
            final Process currentProcess = process;
            drain = new Thread(() -> {
                try (Reader reader = new InputStreamReader(
                        currentProcess.getInputStream(), StandardCharsets.UTF_8)) {
                    char[] chunk = new char[READ_CHUNK];
                    int count;
                    while ((count = reader.read(chunk)) != -1) {
                        synchronized (output) {
                            int room = MAX_CAPTURE_CHARS - output.length();
                            if (room > 0) output.append(chunk, 0, Math.min(room, count));
                        }
                        // Keep draining even after the capture is full so the process
                        // never blocks on a full OS pipe.
                    }
                } catch (IOException ignored) {
                    // Process termination can close the stream while it is being read.
                }
            }, "WAEX-root-stdout");
            drain.setDaemon(true);
            drain.start();
            // Poll exitValue rather than using Process.waitFor(timeout, unit),
            // which is not reliably available on the application's minSdk 24.
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
            boolean completed = false;
            while (System.nanoTime() < deadline) {
                try {
                    process.exitValue();
                    completed = true;
                    break;
                } catch (IllegalThreadStateException stillRunning) {
                    long remainingNanos = deadline - System.nanoTime();
                    if (remainingNanos <= 0) break;
                    long sleepMillis = Math.min(25L,
                            Math.max(1L, TimeUnit.NANOSECONDS.toMillis(remainingNanos)));
                    Thread.sleep(sleepMillis);
                }
            }
            if (!completed) {
                terminate(process);
                return Result.TIMEOUT;
            }
            if (drain.isAlive()) drain.join(250L);
            if (process.exitValue() != 0) return Result.NONZERO;
            synchronized (output) { return Result.success(output.toString().trim()); }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return Result.NONZERO;
        } catch (IOException | RuntimeException failure) {
            return Result.NONZERO;
        } finally {
            if (process != null) terminate(process);
            if (drain != null && drain.isAlive()) drain.interrupt();
        }
    }

    private static void terminate(Process process) {
        try { process.destroy(); } catch (RuntimeException ignored) {}
        // Reflection preserves Android API 24 support where destroyForcibly()
        // may not be linked, while using it on runtimes that provide it.
        try {
            Process.class.getMethod("destroyForcibly").invoke(process);
        } catch (ReflectiveOperationException | SecurityException ignored) {}
    }
}
