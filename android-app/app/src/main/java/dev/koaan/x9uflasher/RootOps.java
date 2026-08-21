package dev.koaan.x9uflasher;

import android.content.Context;
import android.os.Build;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

final class RootOps {
    private static final String PMA110_PROJECT_ID = "25021";
    private static final String PMA120_PROJECT_ID = "25022";
    private static final String CPH2841_PROJECT_ID = "25211";
    private static final String EXPECTED_KERNEL =
            "6.12.58-android16-6-g7704a1ae279b-ab15213644-4k";

    private static final String BROKER_ASSET = "x9u-root-broker.sh";
    private static final String BROKER_SCRIPT = ".x9u_root_broker.sh";
    private static final String BROKER_READY = ".x9u_root_ready";
    private static final String BROKER_REQUEST = ".x9u_root_request";
    private static final String BROKER_RESPONSE = ".x9u_root_response";
    private static final String BROKER_LOG = ".x9u_root_broker.log";
    private static final String INSTALL_READY = ".x9u_install_ready";
    private static final String UNINSTALL_READY = ".x9u_uninstall_ready";

    private static final String PRELOAD_SHA256 =
            "32ac2f03f56955c41032157aa53f23590c3f6a1595fccc025ad991322e07f7f6";
    private static final String ABL_SHA256 =
            "4ad7f1db0c92f0a358e28bf18170a20533011299827d8d9a25cffd2218f1415d";
    private static final String EFI_SHA256 =
            "35560f8e6fd706a3768f26f692467491c90425881a8d80bf237341215c04cac5";
    private static final String EMPTY_EFISP_SHA256 =
            "bbd05cf6097ac9b1f89ea29d2542c1b7b67ee46848393895f5a9e43fa1f621e5";

    private RootOps() {
    }

    private static final class CommandResult {
        final int exitCode;
        final String output;

        CommandResult(int exitCode, String output) {
            this.exitCode = exitCode;
            this.output = output;
        }
    }

    private static final class BrokerResult {
        final int code;
        final String message;
        final String log;

        BrokerResult(int code, String message, String log) {
            this.code = code;
            this.message = message;
            this.log = log;
        }
    }

    private static String shellQuote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    private static CommandResult command(
            long timeoutSeconds, String[] environment, String... arguments) throws Exception {
        ProcessBuilder builder = new ProcessBuilder(arguments).redirectErrorStream(true);
        if (environment != null) {
            for (int i = 0; i + 1 < environment.length; i += 2) {
                builder.environment().put(environment[i], environment[i + 1]);
            }
        }
        Process process = builder.start();
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        Thread reader = new Thread(() -> {
            try {
                byte[] buffer = new byte[8192];
                int count;
                InputStream input = process.getInputStream();
                while ((count = input.read(buffer)) != -1) {
                    captured.write(buffer, 0, count);
                }
            } catch (IOException ignored) {
            }
        }, "x9u-command-reader");
        reader.start();
        if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            process.destroy();
            if (!process.waitFor(2, TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
            reader.join(2000L);
            throw new IOException("Command timed out after " + timeoutSeconds + " seconds.");
        }
        reader.join(3000L);
        return new CommandResult(process.exitValue(),
                captured.toString(StandardCharsets.UTF_8.name()).trim());
    }

    private static CommandResult command(long timeoutSeconds, String... arguments)
            throws Exception {
        return command(timeoutSeconds, null, arguments);
    }

    private static String property(String name) {
        try {
            CommandResult result = command(5, "/system/bin/getprop", name);
            return result.exitCode == 0 ? result.output.trim() : "";
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static String kernel() {
        try {
            CommandResult result = command(5, "/system/bin/uname", "-r");
            return result.exitCode == 0 ? result.output.trim() : "";
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static String firmware() {
        for (String key : new String[]{
                "ro.build.version.ota", "ro.build.display.id",
                "ro.product.build.version.incremental", "ro.build.version.incremental"
        }) {
            String value = property(key);
            if (!value.isEmpty()) {
                return value;
            }
        }
        return "unknown";
    }

    private static String projectId() {
        for (String name : new String[]{
                "ro.boot.prjname", "ro.boot.project_name", "ro.boot.prjid",
                "ro.boot.project_id", "ro.product.prjname"
        }) {
            String value = property(name);
            if (!value.isEmpty()) {
                return value;
            }
        }
        return "";
    }

    private static boolean supportedProject(String project) {
        String normalizedProject = project == null ? "" : project.trim();
        return PMA110_PROJECT_ID.equals(normalizedProject)
                || PMA120_PROJECT_ID.equals(normalizedProject)
                || CPH2841_PROJECT_ID.equals(normalizedProject);
    }

    private static String compatibilityProblem() {
        String model = Build.MODEL == null ? "" : Build.MODEL.trim();
        String project = projectId();
        if (!supportedProject(project)) {
            return "Unsupported project ID: "
                    + (project.isEmpty() ? "unknown" : project)
                    + " (reported model: " + model + ")";
        }
        String release = kernel();
        if (!EXPECTED_KERNEL.equals(release)) {
            return "Kernel mismatch: " + release;
        }
        return null;
    }

    static String deviceInfo(Context context) {
        try {
            String model = Build.MODEL == null ? "" : Build.MODEL.trim();
            String project = projectId();
            String release = kernel();
            File preload = preloadFile(context);
            return new JSONObject()
                    .put("device", "OPPO Find X9 Ultra")
                    .put("model", model)
                    .put("projectId", project)
                    .put("firmware", firmware())
                    .put("kernel", release)
                    .put("supported", supportedProject(project))
                    .put("kernelCompatible", EXPECTED_KERNEL.equals(release))
                    .put("preloadPresent", preload.isFile())
                    .toString();
        } catch (Throwable error) {
            return failure("Could not read device information", error);
        }
    }

    private static File preloadFile(Context context) {
        return new File(context.getApplicationInfo().nativeLibraryDir, "libx9upreload.so");
    }

    private static String readText(File file) throws IOException {
        try (FileInputStream input = new FileInputStream(file)) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                output.write(buffer, 0, count);
            }
            return output.toString(StandardCharsets.UTF_8.name());
        }
    }

    private static void writeSynced(File file, String value) throws Exception {
        try (FileOutputStream output = new FileOutputStream(file, false)) {
            output.write(value.getBytes(StandardCharsets.UTF_8));
            output.getFD().sync();
        }
    }

    private static void prepareBrokerScript(Context context) throws Exception {
        File target = new File(context.getFilesDir(), BROKER_SCRIPT);
        try (InputStream input = context.getAssets().open(BROKER_ASSET);
             FileOutputStream output = new FileOutputStream(target, false)) {
            byte[] buffer = new byte[16 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) {
                output.write(buffer, 0, count);
            }
            output.getFD().sync();
        }
        if (!target.setReadable(true, true) || !target.setExecutable(true, true)) {
            throw new IOException("Could not prepare the temporary-root service.");
        }
    }

    private static synchronized BrokerResult brokerCommand(
            Context context, String operation, long timeoutMs) throws Exception {
        File home = context.getFilesDir();
        File ready = new File(home, BROKER_READY);
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (!ready.isFile() && System.currentTimeMillis() < deadline) {
            Thread.sleep(100L);
        }
        if (!ready.isFile()) {
            return null;
        }

        File request = new File(home, BROKER_REQUEST);
        File response = new File(home, BROKER_RESPONSE);
        File pending = new File(home, BROKER_REQUEST + ".pending");
        response.delete();
        request.delete();
        pending.delete();
        String token = Long.toHexString(System.nanoTime())
                + Long.toHexString(Thread.currentThread().getId());
        writeSynced(pending, token + "\n" + operation + "\n");
        if (!pending.renameTo(request)) {
            throw new IOException("Could not contact the temporary-root service.");
        }

        while (System.currentTimeMillis() < deadline) {
            if (response.isFile()) {
                String[] lines = readText(response).split("\n", -1);
                if (lines.length >= 3 && token.equals(lines[0])) {
                    int code = Integer.parseInt(lines[1].trim());
                    StringBuilder log = new StringBuilder();
                    for (int i = 3; i < lines.length; i++) {
                        if (i > 3) {
                            log.append('\n');
                        }
                        log.append(lines[i]);
                    }
                    response.delete();
                    return new BrokerResult(code, lines[2], log.toString().trim());
                }
            }
            Thread.sleep(100L);
        }
        request.delete();
        return null;
    }

    private static boolean rootAvailable(Context context) {
        try {
            BrokerResult result = brokerCommand(context, "PING", 500L);
            return result != null && result.code == 0 && result.log.contains("uid=0");
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean uninstallReady(Context context) {
        File marker = new File(context.getFilesDir(), UNINSTALL_READY);
        try {
            return marker.isFile() && "UNINSTALL_VERIFY_OK".equals(readText(marker).trim());
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean installReady(Context context) {
        File marker = new File(context.getFilesDir(), INSTALL_READY);
        try {
            return marker.isFile() && "INSTALL_VERIFY_OK".equals(readText(marker).trim());
        } catch (Throwable ignored) {
            return false;
        }
    }

    static String status(Context context) {
        try {
            File preload = preloadFile(context);
            String preloadHash = preload.isFile() ? sha256(preload) : "missing";
            return new JSONObject()
                    .put("ok", true)
                    .put("root", rootAvailable(context))
                    .put("installReady", installReady(context))
                    .put("uninstallReady", uninstallReady(context))
                    .put("preloadOk", PRELOAD_SHA256.equals(preloadHash))
                    .put("preloadHash", preloadHash)
                    .put("ablHash", ABL_SHA256)
                    .put("efiHash", EFI_SHA256)
                    .put("emptyEfispHash", EMPTY_EFISP_SHA256)
                    .toString();
        } catch (Throwable error) {
            return failure("Status check failed", error);
        }
    }

    static String enableRoot(Context context) {
        try {
            String problem = compatibilityProblem();
            if (problem != null) {
                return response(false, problem + "; nothing was run.", "")
                        .put("root", false).toString();
            }

            BrokerResult existing = brokerCommand(context, "PING", 500L);
            if (existing != null && existing.code == 0 && existing.log.contains("uid=0")) {
                return response(true, "Temporary root is already available.", existing.log)
                        .put("root", true).toString();
            }

            File preload = preloadFile(context);
            if (!preload.isFile() || !PRELOAD_SHA256.equals(sha256(preload))) {
                return response(false, "Embedded preload.so failed verification.",
                        preload.getAbsolutePath()).put("root", false).toString();
            }

            prepareBrokerScript(context);
            File home = context.getFilesDir();
            new File(home, BROKER_READY).delete();
            new File(home, BROKER_REQUEST).delete();
            new File(home, BROKER_REQUEST + ".pending").delete();
            new File(home, BROKER_RESPONSE).delete();
            File brokerLog = new File(home, BROKER_LOG);
            brokerLog.delete();

            File brokerScript = new File(home, BROKER_SCRIPT);
            String launch = "/system/bin/sh " + shellQuote(brokerScript.getAbsolutePath())
                    + " </dev/null >" + shellQuote(brokerLog.getAbsolutePath()) + " 2>&1 &";
            CommandResult exploit = command(300,
                    new String[]{
                            "LD_PRELOAD", preload.getAbsolutePath(),
                            "X9U_HOME", home.getAbsolutePath(),
                            "HOME", home.getAbsolutePath(),
                            "TMPDIR", home.getAbsolutePath()
                    },
                    "/system/bin/sh", "-c", launch);

            BrokerResult broker = brokerCommand(context, "PING", 15000L);
            boolean ok = broker != null && broker.code == 0 && broker.log.contains("uid=0");
            StringBuilder log = new StringBuilder(exploit.output);
            if (brokerLog.isFile()) {
                String value = readText(brokerLog).trim();
                if (!value.isEmpty()) {
                    if (log.length() > 0) log.append("\n\n--- Root service ---\n");
                    log.append(value);
                }
            }
            if (broker != null && !broker.log.isEmpty()) {
                if (log.length() > 0) log.append('\n');
                log.append(broker.log);
            }
            return response(ok,
                    ok ? "Temporary root obtained."
                            : "Root was not obtained; nothing was flashed.",
                    log.toString())
                    .put("root", ok)
                    .put("exploitExit", exploit.exitCode)
                    .toString();
        } catch (Throwable error) {
            return failure("Temporary-root attempt failed", error);
        }
    }

    private static File extractAsset(Context context, String assetName, String expectedHash)
            throws Exception {
        File stage = new File(context.getFilesDir(), "flash-stage");
        if (!stage.isDirectory() && !stage.mkdirs()) {
            throw new IOException("Could not create private staging directory.");
        }
        File output = new File(stage, assetName);
        try (InputStream input = context.getAssets().open(assetName);
             FileOutputStream file = new FileOutputStream(output, false)) {
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) {
                file.write(buffer, 0, count);
            }
            file.getFD().sync();
        }
        String actual = sha256(output);
        if (!expectedHash.equals(actual)) {
            throw new IOException(assetName + " checksum mismatch: " + actual);
        }
        return output;
    }

    static String flash(Context context, String confirmation) {
        if (!"FLASH".equals(confirmation)) {
            return failure("Flash confirmation was not accepted", null);
        }

        BrokerResult flashResult = null;
        boolean brokerConfirmed = false;
        boolean operationTimedOut = false;
        boolean success = false;
        try {
            String problem = compatibilityProblem();
            if (problem != null) {
                return response(false, problem + "; nothing was flashed.", "")
                        .put("flashed", false).toString();
            }
            if (!rootAvailable(context)) {
                return response(false, "Temporary root is not available; nothing was flashed.", "")
                        .put("flashed", false).toString();
            }
            brokerConfirmed = true;

            extractAsset(context, "abl.img", ABL_SHA256);
            extractAsset(context, "installed-mode2.efi", EFI_SHA256);
            flashResult = brokerCommand(context, "FLASH", 300000L);
            if (flashResult == null) {
                operationTimedOut = true;
                return response(false,
                        "The flash operation timed out. Do not reboot until its state is checked.",
                        "No completion response was received from the root service.")
                        .put("flashed", false).toString();
            }

            success = flashResult.code == 0
                    && flashResult.log.contains("FLASH_VERIFY_OK")
                    && installReady(context);
            return response(success,
                    success ? "GBL chainload payloads installed and read-back verified."
                            : "A write or verification failed. Do not reboot until the log is reviewed.",
                    flashResult.log)
                    .put("flashed", success)
                    .put("root", success)
                    .put("installReady", success)
                    .put("uninstallReady", false)
                    .put("exitCode", flashResult.code)
                    .toString();
        } catch (Throwable error) {
            return failure("Flashing failed", error);
        } finally {
            if (brokerConfirmed && !operationTimedOut && !success) {
                try {
                    brokerCommand(context, "STOP", 5000L);
                } catch (Throwable ignored) {
                }
            }
        }
    }

    static String uninstall(Context context, String confirmation) {
        if (!"ERASE".equals(confirmation)) {
            return failure("Uninstall confirmation was not accepted", null);
        }

        BrokerResult uninstallResult = null;
        boolean brokerConfirmed = false;
        boolean operationTimedOut = false;
        boolean success = false;
        try {
            String problem = compatibilityProblem();
            if (problem != null) {
                return response(false, problem + "; EFISP was not changed.", "")
                        .put("uninstalled", false).toString();
            }
            if (!rootAvailable(context)) {
                return response(false, "Temporary root is not available; EFISP was not changed.", "")
                        .put("uninstalled", false).toString();
            }
            brokerConfirmed = true;

            extractAsset(context, "efisp.img", EMPTY_EFISP_SHA256);
            uninstallResult = brokerCommand(context, "UNINSTALL", 300000L);
            if (uninstallResult == null) {
                operationTimedOut = true;
                return response(false,
                        "The EFISP erase timed out. Do not reboot until its state is checked.",
                        "No completion response was received from the root service.")
                        .put("uninstalled", false).toString();
            }

            success = uninstallResult.code == 0
                    && uninstallResult.log.contains("UNINSTALL_VERIFY_OK")
                    && uninstallReady(context);
            return response(success,
                    success ? "GBL chainload removed from EFISP and read-back verified."
                            : "The EFISP erase or verification failed. Do not reboot until the log is reviewed.",
                    uninstallResult.log)
                    .put("uninstalled", success)
                    .put("root", success)
                    .put("uninstallReady", success)
                    .put("exitCode", uninstallResult.code)
                    .toString();
        } catch (Throwable error) {
            return failure("GBL chainload removal failed", error);
        } finally {
            if (brokerConfirmed && !operationTimedOut && !success) {
                try {
                    brokerCommand(context, "STOP", 5000L);
                } catch (Throwable ignored) {
                }
            }
        }
    }

    static String rebootFastboot(Context context) {
        try {
            String problem = compatibilityProblem();
            if (problem != null) {
                return response(false, problem + "; reboot was not requested.", "").toString();
            }
            if (!installReady(context) && !uninstallReady(context)) {
                return response(false,
                        "No verified install or uninstall operation authorizes this reboot.", "")
                        .toString();
            }
            if (!rootAvailable(context)) {
                return response(false,
                        "Temporary root expired. Enable it again or use adb reboot fastboot.", "")
                        .toString();
            }

            BrokerResult reboot = brokerCommand(context, "REBOOT_FASTBOOT", 25000L);
            if (reboot == null) {
                return response(false,
                        "No response was received. If the phone is not rebooting, use adb reboot fastboot.",
                        "The root service disconnected before returning a status.").toString();
            }
            return response(reboot.code == 0, reboot.message, reboot.log)
                    .put("exitCode", reboot.code).toString();
        } catch (Throwable error) {
            return failure("Recovery / fastbootd reboot request failed", error);
        }
    }

    private static JSONObject response(boolean ok, String message, String log) throws Exception {
        JSONObject result = new JSONObject().put("ok", ok).put("message", message);
        if (log != null && !log.isEmpty()) {
            result.put("log", log);
        }
        return result;
    }

    static String failure(String message, Throwable error) {
        try {
            String detail = error == null ? "" : String.valueOf(error.getMessage());
            return response(false, message, detail).toString();
        } catch (Exception ignored) {
            return "{\"ok\":false,\"message\":\"Operation failed.\"}";
        }
    }

    private static String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (FileInputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) {
                digest.update(buffer, 0, count);
            }
        }
        StringBuilder result = new StringBuilder();
        for (byte value : digest.digest()) {
            result.append(String.format(Locale.ROOT, "%02x", value & 0xff));
        }
        return result.toString();
    }
}
