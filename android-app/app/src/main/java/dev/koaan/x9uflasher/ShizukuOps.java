package dev.koaan.x9uflasher;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import moe.shizuku.server.IRemoteProcess;
import moe.shizuku.server.IShizukuService;
import rikka.shizuku.Shizuku;
import rikka.shizuku.ShizukuBinderWrapper;

/** CVE-2026-98260 shell backend for the app's existing restricted workflow. */
final class ShizukuOps {
    static final int PERMISSION_REQUEST = 98260;
    private static final String WORK = "/data/local/tmp/x9u-cve98260";
    private static final String LAUNCHER = WORK + "/cve98260";
    private static final String LIBRARY = WORK + "/libcve98260.so";
    private static final String BROKER = WORK + "/x9u-cve-root-broker.sh";
    private static final String ROOT_STAGE = WORK + "/x9u-cve-root-stage.sh";
    private static final String READY = WORK + "/root.ready";
    private static final String REQUEST = WORK + "/root.request";
    private static final String RESPONSE = WORK + "/root.response";
    private static final String PARKED = WORK + "/PARKED";
    private static final String EXPLOIT_LOG = WORK + "/exploit.log";
    private static final String FLASH_STAGE = WORK + "/flash-stage";

    private ShizukuOps() {
    }

    private static final class CommandResult {
        final int exitCode;
        final String output;

        CommandResult(int exitCode, String output) {
            this.exitCode = exitCode;
            this.output = output;
        }
    }

    static String permissionStatus() {
        try {
            boolean running = Shizuku.pingBinder();
            boolean granted = running
                    && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
            return new JSONObject()
                    .put("ok", true)
                    .put("running", running)
                    .put("granted", granted)
                    .put("message", !running ? "Shizuku is not running."
                            : granted ? "Shizuku permission is granted."
                            : "Shizuku permission is required.")
                    .toString();
        } catch (Throwable error) {
            return failure("Could not read Shizuku status", error);
        }
    }

    static String permissionResult(int result) {
        boolean granted = result == PackageManager.PERMISSION_GRANTED;
        try {
            return new JSONObject()
                    .put("ok", granted)
                    .put("running", Shizuku.pingBinder())
                    .put("granted", granted)
                    .put("message", granted ? "Shizuku permission granted."
                            : "Shizuku permission was denied.")
                    .toString();
        } catch (Throwable error) {
            return failure("Could not process the Shizuku permission result", error);
        }
    }

    static String enableRoot(Context context) {
        try {
            requirePermission();
            RootOps.BrokerResult existing = brokerCommand("PING", 700L);
            if (existing != null && existing.code == 0
                    && existing.log.contains("uid=0")) {
                return response(true, "Temporary root is already available.", existing.log)
                        .put("root", true).put("backend", "cve-2026-98260").toString();
            }

            CommandResult prepare = run(10, null,
                    "umask 077; mkdir -p " + quote(WORK)
                            // The exploit produces uid 0 without DAC override capabilities.
                            // It therefore needs explicit write/traverse access to this
                            // shell-owned directory to launch the restricted broker.
                            + "; chmod 703 " + quote(WORK)
                            + "; B=$(cat /proc/sys/kernel/random/boot_id); "
                            + "if [ -f " + quote(PARKED) + " ] && "
                            + "[ \"$(cat " + quote(PARKED) + ")\" = \"$B\" ]; then "
                            + "echo PARKED_SAME_BOOT; exit 73; fi; "
                            + "rm -f " + quote(PARKED) + " " + quote(READY) + " "
                            + quote(REQUEST) + " " + quote(RESPONSE) + " "
                            + quote(EXPLOIT_LOG) + "; mkdir -p " + quote(FLASH_STAGE)
                            + "; chmod 701 " + quote(FLASH_STAGE));
            if (prepare.exitCode == 73 || prepare.output.contains("PARKED_SAME_BOOT")) {
                return response(false,
                        "A held timer state exists from this boot. Reboot the phone before retrying.",
                        prepare.output).put("root", false).put("rebootRequired", true)
                        .toString();
            }
            if (prepare.exitCode != 0) {
                return response(false, "Could not prepare the CVE workspace.", prepare.output)
                        .put("root", false).toString();
            }

            stageRuntime(context);
            String command = "cd " + quote(WORK)
                    + "; export CVE_SO=" + quote(LIBRARY)
                    + " CVE_ROOT_SCRIPT=" + quote(ROOT_STAGE)
                    + " CVE_PARKED_FILE=" + quote(PARKED)
                    + " CVE_DISABLE_SELINUX=1; "
                    + quote(LAUNCHER) + " >" + quote(EXPLOIT_LOG)
                    + " 2>&1 </dev/null & P=$!; echo $P >launcher.pid; I=0; "
                    + "while [ $I -lt 3000 ]; do "
                    + "if [ -f " + quote(READY) + " ]; then cat " + quote(EXPLOIT_LOG)
                    + "; echo ROOT_BROKER_READY; exit 0; fi; "
                    + "B=$(cat /proc/sys/kernel/random/boot_id); "
                    + "if grep -q '^KSU_CHAIN_HELD$' " + quote(EXPLOIT_LOG)
                    + " 2>/dev/null; then printf '%s' \"$B\" >" + quote(PARKED)
                    + "; cat " + quote(EXPLOIT_LOG)
                    + "; echo PARKED_SAME_BOOT; exit 74; fi; "
                    + "if [ -f " + quote(PARKED) + " ] && [ \"$(cat " + quote(PARKED)
                    + ")\" = \"$B\" ]; then cat " + quote(EXPLOIT_LOG)
                    + "; echo PARKED_SAME_BOOT; exit 74; fi; "
                    + "if ! kill -0 $P 2>/dev/null; then cat " + quote(EXPLOIT_LOG)
                    + "; echo EXPLOIT_EXITED; exit 75; fi; "
                    + "sleep 0.1; I=$((I+1)); done; B=$(cat /proc/sys/kernel/random/boot_id); "
                    + "printf '%s' \"$B\" >" + quote(PARKED) + "; cat "
                    + quote(EXPLOIT_LOG) + "; echo PARKED_SAME_BOOT; "
                    + "echo EXPLOIT_TIMEOUT; exit 76";
            CommandResult exploit = run(310, null, command);
            RootOps.BrokerResult broker = brokerCommand("PING", 15000L);
            boolean ok = exploit.exitCode == 0 && broker != null && broker.code == 0
                    && broker.log.contains("uid=0");
            boolean parked = exploit.exitCode == 74 || exploit.exitCode == 76
                    || exploit.output.contains("PARKED_SAME_BOOT");
            String log = exploit.output;
            if (broker != null && !broker.log.isEmpty()) {
                log = log.isEmpty() ? broker.log : log + "\n" + broker.log;
            }
            return response(ok,
                    ok ? "Temporary root obtained with CVE-2026-98260."
                            : parked ? "The exploit retained a held timer state; reboot is required."
                            : "Root was not obtained; nothing was flashed.",
                    log).put("root", ok).put("backend", "cve-2026-98260")
                    .put("rebootRequired", parked).put("exploitExit", exploit.exitCode)
                    .toString();
        } catch (Throwable error) {
            return failure("CVE-2026-98260 temporary-root attempt failed", error);
        }
    }

    static boolean rootAvailable() {
        try {
            RootOps.BrokerResult result = brokerCommand("PING", 700L);
            return result != null && result.code == 0 && result.log.contains("uid=0");
        } catch (Throwable ignored) {
            return false;
        }
    }

    static boolean installReady() {
        return markerReady(WORK + "/install.ready", "INSTALL_VERIFY_OK");
    }

    static boolean uninstallReady() {
        return markerReady(WORK + "/uninstall.ready", "UNINSTALL_VERIFY_OK");
    }

    static void stagePayload(Context context, String assetName, String expectedHash)
            throws Exception {
        requirePermission();
        String destination = FLASH_STAGE + "/" + assetName;
        try (InputStream input = context.getAssets().open(assetName)) {
            stage(input, destination, "644");
        }
        CommandResult verify = run(20, null,
                "test \"$(sha256sum " + quote(destination)
                        + " | awk '{print $1}')\" = " + quote(expectedHash));
        if (verify.exitCode != 0) {
            throw new IOException(assetName + " failed CVE workspace verification: "
                    + verify.output);
        }
    }

    static synchronized RootOps.BrokerResult brokerCommand(String operation, long timeoutMs)
            throws Exception {
        if (!("PING".equals(operation) || "FLASH".equals(operation)
                || "UNINSTALL".equals(operation) || "REBOOT_FASTBOOT".equals(operation)
                || "STOP".equals(operation))) {
            throw new IllegalArgumentException("Unsupported broker operation.");
        }
        requirePermission();
        String token = Long.toHexString(System.nanoTime())
                + Long.toHexString(Thread.currentThread().getId());
        long loops = Math.max(1L, timeoutMs / 100L);
        String command = "test -f " + quote(READY) + " || exit 90; "
                + "rm -f " + quote(RESPONSE) + " " + quote(REQUEST) + "; "
                + "printf '%s\\n%s\\n' " + quote(token) + " " + quote(operation)
                // The broker is uid 0 without DAC override capabilities, while the
                // request is shell-owned. It needs read permission but never write
                // permission; the containing directory supplies broker-side removal.
                + " >" + quote(REQUEST + ".pending") + "; chmod 604 "
                + quote(REQUEST + ".pending") + "; mv -f " + quote(REQUEST + ".pending")
                + " " + quote(REQUEST) + "; I=0; while [ $I -lt " + loops + " ]; do "
                + "if [ -f " + quote(RESPONSE) + " ] && [ \"$(sed -n '1p' "
                + quote(RESPONSE) + ")\" = " + quote(token) + " ]; then cat "
                + quote(RESPONSE) + "; exit 0; fi; sleep 0.1; I=$((I+1)); done; exit 91";
        CommandResult result = run(Math.max(2L, timeoutMs / 1000L + 5L), null, command);
        if (result.exitCode != 0 || result.output.isEmpty()) return null;
        String[] lines = result.output.split("\\n", -1);
        if (lines.length < 3 || !token.equals(lines[0])) return null;
        int code = Integer.parseInt(lines[1].trim());
        StringBuilder log = new StringBuilder();
        for (int i = 3; i < lines.length; i++) {
            if (i > 3) log.append('\n');
            log.append(lines[i]);
        }
        return new RootOps.BrokerResult(code, lines[2], log.toString().trim());
    }

    private static boolean markerReady(String path, String expected) {
        try {
            requirePermission();
            CommandResult result = run(5, null,
                    "test \"$(cat " + quote(path) + " 2>/dev/null)\" = "
                            + quote(expected));
            return result.exitCode == 0;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void stageRuntime(Context context) throws Exception {
        File library = new File(context.getApplicationInfo().nativeLibraryDir,
                "libcve98260.so");
        if (!library.isFile()) throw new IOException("Packaged CVE library is missing.");
        try (InputStream input = context.getAssets().open("cve98260")) {
            stage(input, LAUNCHER, "755");
        }
        try (InputStream input = new FileInputStream(library)) {
            stage(input, LIBRARY, "644");
        }
        try (InputStream input = context.getAssets().open("x9u-cve-root-broker.sh")) {
            stage(input, BROKER, "755");
        }
        try (InputStream input = context.getAssets().open("x9u-cve-root-stage.sh")) {
            stage(input, ROOT_STAGE, "755");
        }
    }

    private static void requirePermission() {
        if (!Shizuku.pingBinder()) throw new IllegalStateException("Shizuku is not running.");
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException("Shizuku permission is required.");
        }
    }

    static String checkCve98260(Context context) {
        try {
            if (!Shizuku.pingBinder()) {
                return response(false, "Shizuku is not running.", "").toString();
            }
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                return response(false, "Grant Shizuku permission first.", "").toString();
            }

            File library = new File(context.getApplicationInfo().nativeLibraryDir,
                    "libcve98260.so");
            if (!library.isFile()) {
                return response(false, "The packaged CVE diagnostic library is missing.",
                        library.getAbsolutePath()).toString();
            }

            CommandResult mkdir = run(10, null,
                    "umask 077; mkdir -p " + quote(WORK) + "; chmod 700 " + quote(WORK));
            if (mkdir.exitCode != 0) {
                return response(false, "Could not create the Shizuku staging directory.",
                        mkdir.output).toString();
            }

            try (InputStream input = context.getAssets().open("cve98260")) {
                stage(input, LAUNCHER, "700");
            }
            try (InputStream input = new FileInputStream(library)) {
                stage(input, LIBRARY, "600");
            }

            String command = "cd " + quote(WORK)
                    + " && CVE_SO=" + quote(LIBRARY)
                    + " " + quote(LAUNCHER) + " --check";
            CommandResult check = run(45, null, command);
            boolean matched = check.output.contains("profile match:")
                    && check.output.contains("--check: would proceed");
            return response(matched,
                    matched ? "CVE-2026-98260 profile and runtime prerequisites matched."
                            : "CVE-2026-98260 precheck did not approve this runtime.",
                    check.output)
                    .put("profileMatched", matched)
                    .put("exitCode", check.exitCode)
                    .toString();
        } catch (Throwable error) {
            return failure("CVE-2026-98260 precheck failed", error);
        }
    }

    private static void stage(InputStream input, String destination, String mode)
            throws Exception {
        String temporary = destination + ".new";
        String command = "umask 077; cat > " + quote(temporary)
                + " && chmod " + mode + " " + quote(temporary)
                + " && mv -f " + quote(temporary) + " " + quote(destination);
        CommandResult result = run(30, input, command);
        if (result.exitCode != 0) {
            throw new IOException("Could not stage " + destination + ": " + result.output);
        }
    }

    private static CommandResult run(long timeoutSeconds, InputStream upload, String command)
            throws Exception {
        RemoteProcess process = start(command);
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        Thread outReader = copyThread(process.getInputStream(), stdout, "cve98260-stdout");
        Thread errReader = copyThread(process.getErrorStream(), stderr, "cve98260-stderr");
        outReader.start();
        errReader.start();

        try (OutputStream stdin = process.getOutputStream()) {
            if (upload != null) {
                byte[] buffer = new byte[64 * 1024];
                int count;
                while ((count = upload.read(buffer)) != -1) {
                    stdin.write(buffer, 0, count);
                }
            }
        }

        if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            process.destroy();
            throw new IOException("Shizuku command timed out after " + timeoutSeconds
                    + " seconds.");
        }
        outReader.join(2000L);
        errReader.join(2000L);
        String output = stdout.toString(StandardCharsets.UTF_8.name()).trim();
        String error = stderr.toString(StandardCharsets.UTF_8.name()).trim();
        if (!error.isEmpty()) {
            output = output.isEmpty() ? error : output + "\n" + error;
        }
        return new CommandResult(process.exitValue(), output);
    }

    private static Thread copyThread(InputStream input, OutputStream output, String name) {
        Thread thread = new Thread(() -> {
            try (InputStream source = input) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = source.read(buffer)) != -1) {
                    output.write(buffer, 0, count);
                }
            } catch (IOException ignored) {
            }
        }, name);
        thread.setDaemon(true);
        return thread;
    }

    private static RemoteProcess start(String command) throws Exception {
        IBinder binder = Shizuku.getBinder();
        if (binder == null || !binder.pingBinder()) {
            throw new IllegalStateException("Shizuku is unavailable.");
        }
        IShizukuService service = IShizukuService.Stub.asInterface(
                new ShizukuBinderWrapper(binder));
        IRemoteProcess remote = service.newProcess(
                new String[]{"/system/bin/sh", "-c", command},
                new String[]{"PATH=/system/bin:/system/xbin", "HOME=/data/local/tmp",
                        "TMPDIR=/data/local/tmp"},
                "/data/local/tmp");
        if (remote == null) {
            throw new IllegalStateException("Shizuku refused to start a shell process.");
        }
        return new RemoteProcess(remote);
    }

    private static String quote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    private static JSONObject response(boolean ok, String message, String log)
            throws Exception {
        return new JSONObject().put("ok", ok).put("message", message).put("log", log);
    }

    private static String failure(String message, Throwable error) {
        try {
            return response(false, message,
                    error == null ? "" : error.getClass().getSimpleName() + ": "
                            + String.valueOf(error.getMessage())).toString();
        } catch (Throwable ignored) {
            return "{\"ok\":false,\"message\":\"Operation failed\",\"log\":\"\"}";
        }
    }

    private static final class RemoteProcess extends Process {
        private final IRemoteProcess remote;
        private final CountDownLatch exitLatch = new CountDownLatch(1);
        private final AtomicBoolean waiterStarted = new AtomicBoolean(false);
        private OutputStream stdin;
        private InputStream stdout;
        private InputStream stderr;
        private volatile int exitCode;
        private volatile boolean hasExit;
        private volatile Throwable waitError;

        RemoteProcess(IRemoteProcess remote) {
            this.remote = remote;
        }

        private void ensureWaiter() {
            if (!waiterStarted.compareAndSet(false, true)) return;
            Thread waiter = new Thread(() -> {
                try {
                    exitCode = remote.waitFor();
                    hasExit = true;
                } catch (Throwable error) {
                    waitError = error;
                } finally {
                    exitLatch.countDown();
                }
            }, "cve98260-remote-wait");
            waiter.setDaemon(true);
            waiter.start();
        }

        @Override
        public synchronized OutputStream getOutputStream() {
            if (stdin == null) {
                try {
                    stdin = new ParcelFileDescriptor.AutoCloseOutputStream(
                            remote.getOutputStream());
                } catch (Throwable error) {
                    throw new RuntimeException("stdin", error);
                }
            }
            return stdin;
        }

        @Override
        public synchronized InputStream getInputStream() {
            if (stdout == null) {
                try {
                    stdout = new ParcelFileDescriptor.AutoCloseInputStream(
                            remote.getInputStream());
                } catch (Throwable error) {
                    throw new RuntimeException("stdout", error);
                }
            }
            return stdout;
        }

        @Override
        public synchronized InputStream getErrorStream() {
            if (stderr == null) {
                try {
                    stderr = new ParcelFileDescriptor.AutoCloseInputStream(
                            remote.getErrorStream());
                } catch (Throwable error) {
                    throw new RuntimeException("stderr", error);
                }
            }
            return stderr;
        }

        @Override
        public int waitFor() throws InterruptedException {
            ensureWaiter();
            exitLatch.await();
            if (waitError != null) throw new RuntimeException("waitFor", waitError);
            return exitCode;
        }

        @Override
        public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
            ensureWaiter();
            boolean finished = exitLatch.await(timeout, unit);
            if (finished && waitError != null) {
                throw new RuntimeException("waitFor", waitError);
            }
            return finished;
        }

        @Override
        public int exitValue() {
            if (hasExit) return exitCode;
            try {
                exitCode = remote.exitValue();
                hasExit = true;
                return exitCode;
            } catch (Throwable error) {
                throw new IllegalThreadStateException("process has not exited");
            }
        }

        @Override
        public void destroy() {
            try {
                remote.destroy();
            } catch (Throwable ignored) {
            }
        }
    }
}
