package com.waenhancer.root;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * Bounded root execution kernel. Ported 1:1 from {@code RootCommandRunner}: poll-based
 * wait (minSdk 24 has no reliably-linked {@code waitFor(timeout)}), always-draining
 * reader thread, 8 KiB capture cap, {@code destroy()} + reflective {@code destroyForcibly()},
 * fail-closed {@code null} contract.
 *
 * <p>The package-private {@link Starter} overload preserves the QA harness testability
 * seam exercised by {@code qa/TestRootCommandRunner.java}.</p>
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
        if (command == null || command.trim().isEmpty() || timeoutMillis <= 0 || starter == null) return null;
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
                return null;
            }
            if (drain.isAlive()) drain.join(250L);
            if (process.exitValue() != 0) return null;
            synchronized (output) { return output.toString().trim(); }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return null;
        } catch (IOException | RuntimeException failure) {
            return null;
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
