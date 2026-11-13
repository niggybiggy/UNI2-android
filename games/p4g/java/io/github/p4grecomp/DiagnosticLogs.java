package io.github.p4grecomp;

import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Process;
import android.provider.MediaStore;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/** Builds a bounded snapshot on demand. The game process does no work for the exporter. */
final class DiagnosticLogs {
    private static final int MAX_LINES = 1500;
    private static final long MAX_RUNTIME_BYTES = 256L * 1024;

    private DiagnosticLogs() {}

    static final class Exported {
        final String name;
        final Uri uri;

        Exported(String name, Uri uri) {
            this.name = name;
            this.uri = uri;
        }
    }

    static Exported export(Context context) throws IOException {
        String name = "P4G-logs-" + new SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US)
                .format(new Date()) + ".txt";
        ContentResolver resolver = context.getContentResolver();
        ContentValues values = new ContentValues();
        values.put(MediaStore.Downloads.DISPLAY_NAME, name);
        values.put(MediaStore.Downloads.MIME_TYPE, "text/plain");
        values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
        values.put(MediaStore.Downloads.IS_PENDING, 1);
        Uri uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
        if (uri == null) throw new IOException("Could not create a file in Downloads.");
        boolean published = false;
        try {
            OutputStream stream = resolver.openOutputStream(uri);
            if (stream == null) throw new IOException("Could not open the log file.");
            try (BufferedWriter out = new BufferedWriter(new OutputStreamWriter(stream, StandardCharsets.UTF_8))) {
                out.write("Under Night In-Birth II diagnostics\n");
                out.write("Captured: " + new Date() + "\n");
                out.write("Device: " + Build.MANUFACTURER + " " + Build.MODEL
                        + " | Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")\n");
                if (Build.VERSION.SDK_INT >= 30) Api30.writeExitHistory(context, out);
                out.write("\n--- Recent Android messages ---\n");
                File root = Launcher.dataDir();
                if (!writeLogcat(context, out)) {
                    writeRuntimeTail(out, new File(root, "nx.log"), "Runtime log fallback");
                }
                File previous = new File(root, "nx.log.prev");
                if (previous.isFile()) writeRuntimeTail(out, previous, "Previous runtime session");
            }
            values.clear();
            values.put(MediaStore.Downloads.IS_PENDING, 0);
            if (resolver.update(uri, values, null, null) != 1)
                throw new IOException("Could not publish the log file.");
            published = true;
            return new Exported(name, uri);
        } finally {
            if (!published) resolver.delete(uri, null, null);
        }
    }

    private static boolean writeLogcat(Context context, BufferedWriter out) throws IOException {
        File snapshot = File.createTempFile("p4g-logcat-", ".txt", context.getCacheDir());
        java.lang.Process logcat = null;
        try {
            try {
                logcat = new ProcessBuilder("/system/bin/logcat", "-b", "main", "-b", "crash",
                        "-v", "threadtime", "-t", Integer.toString(MAX_LINES),
                        "--uid=" + Process.myUid(), "p4g:V", "P4G-Launch:V", "P4G-Saves:V",
                        "AndroidRuntime:E", "libc:F", "*:S")
                        .redirectErrorStream(true).redirectOutput(snapshot).start();
            } catch (IOException e) {
                out.write("Could not start Android log snapshot.\n");
                return false;
            }
            if (!logcat.waitFor(5, TimeUnit.SECONDS)) {
                out.write("Android log snapshot timed out.\n");
                return false;
            }
            if (logcat.exitValue() != 0) {
                out.write("Android log snapshot unavailable (exit " + logcat.exitValue() + ").\n");
                return false;
            }
            boolean gameMessages = false;
            try (BufferedReader in = new BufferedReader(new InputStreamReader(
                    new FileInputStream(snapshot), StandardCharsets.UTF_8))) {
                for (int i = 0; i < MAX_LINES; ++i) {
                    String line = in.readLine();
                    if (line == null) break;
                    if (line.contains(" p4g ")) gameMessages = true;
                    out.write(line);
                    out.newLine();
                }
            }
            return gameMessages;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Log export interrupted.", e);
        } finally {
            if (logcat != null && logcat.isAlive()) logcat.destroyForcibly();
            snapshot.delete();
        }
    }

    private static void writeRuntimeTail(BufferedWriter out, File log, String heading) throws IOException {
        out.write("\n--- " + heading + " (" + log.getName() + ") ---\n");
        byte[] tail;
        boolean truncated;
        try (RandomAccessFile in = new RandomAccessFile(log, "r")) {
            long end = in.length();
            long start = Math.max(0, end - MAX_RUNTIME_BYTES);
            in.seek(start);
            truncated = start != 0;
            if (truncated) {
                // Skip the partial first line of a tail snapshot.
                while (in.getFilePointer() < end && in.read() != '\n') { }
            }
            tail = new byte[(int) (end - in.getFilePointer())];
            in.readFully(tail);
        } catch (IOException e) {
            out.write("No runtime log could be read.\n");
            return;
        }
        if (truncated) out.write("[Showing the last 256 KiB]\n");
        out.write(new String(tail, StandardCharsets.UTF_8));
        out.newLine();
    }

    private static final class Api30 {
        static void writeExitHistory(Context context, BufferedWriter out) throws IOException {
            out.write("\n--- Recent process exits ---\n");
            ActivityManager manager = context.getSystemService(ActivityManager.class);
            if (manager == null) return;
            for (ApplicationExitInfo exit : manager.getHistoricalProcessExitReasons(context.getPackageName(), 0, 8)) {
                out.write(new Date(exit.getTimestamp()) + " | " + exit.getProcessName()
                        + " | reason=" + exit.getReason() + " status=" + exit.getStatus()
                        + " | " + exit.getDescription() + "\n");
            }
        }
    }
}
