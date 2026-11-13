package io.github.p4grecomp;

import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.util.Log;
import org.json.JSONObject;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Driver loading is based on P5R Android v0.5.3. Saved packages remain available when switching drivers. */
final class CustomDrivers {
    private static final long MAX_BYTES = 256L * 1024 * 1024;
    private static final String PACKAGE_NAME = "(turnip|panvk|powervr|xclipse)-[0-9a-f-]{36}";

    private CustomDrivers() {}

    static final class Driver {
        final String id, name, version;
        final int minApi;

        Driver(String id, String name, String version, int minApi) {
            this.id = id;
            this.name = name;
            this.version = version;
            this.minApi = minApi;
        }
    }

    private static File directory(Context context) {
        return new File(context.getFilesDir(), "gpu_driver");
    }

    static String lastRun(Context context) {
        return readStatus(context, "last-run.txt");
    }

    private static String readStatus(Context context, String name) {
        File file = new File(directory(context), name);
        try {
            if (!file.isFile() || file.length() > 1024) return "";
            return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8).trim();
        } catch (IOException e) { return ""; }
    }

    static boolean interrupted(Context context) {
        String pending = readStatus(context, "pending.txt");
        return !pending.isEmpty() && pending.equals(active(context));
    }

    static void acknowledge(Context context) throws IOException {
        Files.deleteIfExists(new File(directory(context), "pending.txt").toPath());
    }

    static synchronized String active(Context context) {
        String id = readSelection(context);
        return !id.isEmpty() && new File(directory(context), id).isFile() ? id : "";
    }

    private static String readSelection(Context context) {
        File marker = new File(directory(context), "active.txt");
        if (!marker.isFile() || marker.length() > 256) return "";
        try {
            String id = new String(Files.readAllBytes(marker.toPath()), StandardCharsets.UTF_8).trim();
            return validId(id) ? id : "";
        } catch (IOException e) { return ""; }
    }

    static synchronized List<Driver> list(Context context) throws IOException {
        File root = directory(context);
        List<Driver> result = new ArrayList<>();
        if (!root.exists()) return result;
        File[] entries = root.listFiles();
        if (entries == null) throw new IOException("Could not list saved drivers.");
        String selected = active(context);
        for (File entry : entries) {
            if (entry.getName().matches("\\.(deleting|import)-[0-9a-f-]{36}")) {
                cleanPackage(entry);
                continue;
            }
            if (!entry.getName().matches(PACKAGE_NAME) ||
                    !Files.isDirectory(entry.toPath(), LinkOption.NOFOLLOW_LINKS)) continue;
            Driver driver = readPackage(entry, selected);
            if (driver != null) result.add(driver);
        }
        result.sort(Comparator.comparing((Driver d) -> d.name, String.CASE_INSENSITIVE_ORDER).thenComparing(d -> d.id));
        return result;
    }

    private static Driver readPackage(File folder, String active) {
        try {
            File info = new File(folder, "driver.json");
            if (info.isFile()) {
                if (info.length() > 16384) return null;
                JSONObject metadata = new JSONObject(new String(Files.readAllBytes(info.toPath()), StandardCharsets.UTF_8));
                String id = folder.getName() + "/" + metadata.optString("libraryName");
                if (!validId(id) || !new File(folder.getParentFile(), id).isFile()) return null;
                return new Driver(id, label(metadata.optString("name")), label(metadata.optString("version")),
                        metadata.optInt("minApi", 0));
            }
            // Older builds kept only active.txt and the libraries, without display metadata.
            if (active.startsWith(folder.getName() + "/")) {
                String family = folder.getName().startsWith("panvk-") ? "PanVK"
                        : folder.getName().startsWith("powervr-") ? "PowerVR"
                        : folder.getName().startsWith("xclipse-") ? "Xclipse" : "Turnip";
                int minApi = family.equals("Xclipse") ? 34 : family.equals("PanVK") ? 31 : 29;
                return new Driver(active, family + " (previous import)", "", minApi);
            }
        } catch (Exception e) {
            Log.w("P4G-Launch", "Could not read saved driver metadata", e);
        }
        return null;
    }

    static synchronized void useSystem(Context context) throws IOException {
        preserveActive(context);
        Files.deleteIfExists(new File(directory(context), "active.txt").toPath());
    }

    static synchronized void select(Context context, Driver driver) throws IOException {
        if (!validId(driver.id) || !new File(directory(context), driver.id).isFile())
            throw new IOException("The saved driver is missing. Reopen the driver list.");
        if (driver.minApi > Build.VERSION.SDK_INT) throw new IOException("The driver requires a newer Android version.");
        requireArm64(new File(directory(context), driver.id));
        preserveActive(context);
        writeSelection(directory(context), driver.id);
    }

    static synchronized void delete(Context context, Driver driver) throws IOException {
        if (!validId(driver.id)) throw new IOException("Invalid saved driver.");
        File root = directory(context);
        File folder = new File(root, driver.id.substring(0, driver.id.indexOf('/')));
        if (!folder.exists()) return;
        if (readSelection(context).equals(driver.id)) useSystem(context);
        File removed = new File(root, ".deleting-" + UUID.randomUUID());
        Files.move(folder.toPath(), removed.toPath());
        cleanPackage(removed);
    }

    static synchronized Driver install(Context context, Uri uri, String name) throws Exception {
        if (name == null) throw new IOException("Could not read the driver file name.");
        File root = directory(context);
        if (!root.isDirectory() && !root.mkdirs()) throw new IOException("Could not create driver directory.");
        File staged = new File(root, ".import-" + UUID.randomUUID());
        if (!staged.mkdir()) throw new IOException("Could not stage the driver.");
        File publishedFolder = null;
        boolean published = false;
        try {
            JSONObject metadata;
            InputStream input = context.getContentResolver().openInputStream(uri);
            if (input == null) throw new IOException("Could not open the driver file.");
            try (InputStream in = input) {
                if (name.toLowerCase(Locale.ROOT).endsWith(".zip")) {
                    File archive = new File(staged, "package.zip");
                    try (OutputStream out = new FileOutputStream(archive)) { copy(in, out, MAX_BYTES); }
                    metadata = extract(archive, staged);
                    Files.delete(archive.toPath());
                } else {
                    if (!libraryName(name)) throw new IOException("Select a driver .zip or .so.");
                    try (OutputStream out = new FileOutputStream(new File(staged, name))) { copy(in, out, MAX_BYTES); }
                    requireArm64(new File(staged, name));
                    metadata = new JSONObject().put("libraryName", name);
                }
            }
            String library = metadata.optString("libraryName");
            String family = familyHint(library);
            if (family.isEmpty()) family = familyHint(metadata.optString("name") + " "
                    + metadata.optString("driverVersion") + " " + name);
            if (family.isEmpty()) family = binaryFamily(new File(staged, library));
            if (family.isEmpty())
                throw new IOException("Choose a Turnip, PanVK, PowerVR, or Xclipse driver package.");
            int familyMinApi = family.equals("xclipse") ? 34 : family.equals("panvk") ? 31 : 29;
            int minApi = Math.max(metadata.optInt("minApi", 0), familyMinApi);
            if (minApi > Build.VERSION.SDK_INT) throw new IOException("The driver requires a newer Android version.");
            String display = label(metadata.optString("name"));
            if (display.isEmpty()) display = label(name);
            String version = label(metadata.optString("packageVersion", metadata.optString("driverVersion")));
            writeMetadata(staged, library, display, version, minApi);
            publishedFolder = new File(root, family + "-" + UUID.randomUUID());
            Files.move(staged.toPath(), publishedFolder.toPath());
            Driver driver = new Driver(publishedFolder.getName() + "/" + library, display, version, minApi);
            select(context, driver);
            published = true;
            return driver;
        } finally {
            if (!published && publishedFolder != null) cleanPackage(publishedFolder);
            if (staged.exists()) cleanPackage(staged);
        }
    }

    private static void preserveActive(Context context) throws IOException {
        String id = active(context);
        if (id.isEmpty()) return;
        File folder = new File(directory(context), id.substring(0, id.indexOf('/')));
        if (new File(folder, "driver.json").exists()) return;
        Driver driver = readPackage(folder, id);
        if (driver != null)
            writeMetadata(folder, id.substring(id.indexOf('/') + 1), driver.name, driver.version, driver.minApi);
    }

    private static void writeMetadata(File folder, String library, String name, String version, int minApi)
            throws IOException {
        byte[] data;
        try {
            data = new JSONObject().put("libraryName", library).put("name", name)
                    .put("version", version).put("minApi", minApi).toString().getBytes(StandardCharsets.UTF_8);
        } catch (org.json.JSONException e) { throw new IOException("Could not save driver information.", e); }
        File temporary = File.createTempFile("metadata-", ".tmp", folder);
        try {
            try (FileOutputStream out = new FileOutputStream(temporary)) {
                out.write(data);
                out.getFD().sync();
            }
            Files.move(temporary.toPath(), new File(folder, "driver.json").toPath(),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary.toPath()); }
    }

    private static void writeSelection(File root, String id) throws IOException {
        File marker = File.createTempFile("selection-", ".tmp", root);
        try {
            try (FileOutputStream out = new FileOutputStream(marker)) {
                out.write((id + "\n").getBytes(StandardCharsets.UTF_8));
                out.getFD().sync();
            }
            Files.move(marker.toPath(), new File(root, "active.txt").toPath(),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(marker.toPath()); }
    }

    private static JSONObject extract(File archive, File destination) throws Exception {
        try (ZipFile zip = new ZipFile(archive)) {
            if (zip.size() > 256) throw new IOException("Driver ZIP has too many entries.");
            Map<String, ZipEntry> libraries = new HashMap<>();
            JSONObject metadata = null;
            for (Enumeration<? extends ZipEntry> entries = zip.entries(); entries.hasMoreElements();) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory()) continue;
                String name = entry.getName();
                name = name.substring(name.lastIndexOf('/') + 1);
                if ("meta.json".equals(name)) {
                    if (metadata != null) throw new IOException("Driver ZIP has duplicate metadata.");
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    try (InputStream in = zip.getInputStream(entry)) { copy(in, out, 16 * 1024); }
                    metadata = new JSONObject(out.toString("UTF-8"));
                } else if (packageLibraryName(name) && libraries.put(name, entry) != null) {
                    throw new IOException("Driver ZIP has duplicate library names.");
                }
            }
            if (metadata == null) metadata = new JSONObject();
            String main = metadata.optString("libraryName");
            if (main.isEmpty() && libraries.size() == 1) main = libraries.keySet().iterator().next();
            if (!libraries.containsKey(main)) throw new IOException("Driver ZIP must specify its main .so in meta.json.");
            if (metadata.optInt("minApi", 0) > Build.VERSION.SDK_INT)
                throw new IOException("The driver requires a newer Android version.");
            long remaining = MAX_BYTES;
            for (Map.Entry<String, ZipEntry> library : libraries.entrySet()) {
                File target = new File(destination, library.getKey());
                try (InputStream in = zip.getInputStream(library.getValue()); OutputStream out = new FileOutputStream(target)) {
                    remaining -= copy(in, out, remaining);
                }
                requireArm64(target);
            }
            return metadata.put("libraryName", main);
        }
    }

    private static String familyHint(String value) {
        String text = value.toLowerCase(Locale.ROOT);
        if (text.contains("panvk") || text.contains("panfrost")) return "panvk";
        if (text.contains("turnip") || text.contains("freedreno")) return "turnip";
        if (text.contains("powervr") || text.contains("imagination") || text.contains("pvr_")) return "powervr";
        if (text.contains("xclipse") || text.contains("radv") || text.contains("vulkan.radeon")) return "xclipse";
        return "";
    }

    private static String binaryFamily(File library) throws IOException {
        boolean turnip = false, panvk = false, powervr = false, xclipse = false;
        String tail = "";
        byte[] buffer = new byte[65536];
        try (InputStream in = new FileInputStream(library)) {
            for (int n; (n = in.read(buffer)) != -1;) {
                String text = tail + new String(buffer, 0, n, StandardCharsets.ISO_8859_1);
                turnip |= text.contains("Turnip") || text.contains("tu_GetInstanceProcAddr");
                panvk |= text.contains("PanVK") || text.contains("panvk_GetInstanceProcAddr");
                powervr |= text.contains("PowerVR") || text.contains("Imagination") ||
                        text.contains("pvr_GetInstanceProcAddr");
                xclipse |= text.contains("Xclipse") || text.contains("RADV") ||
                        text.contains("radv_GetInstanceProcAddr");
                tail = text.substring(Math.max(0, text.length() - 32));
            }
        }
        int matches = (turnip ? 1 : 0) + (panvk ? 1 : 0) + (powervr ? 1 : 0) + (xclipse ? 1 : 0);
        if (matches != 1) return "";
        return panvk ? "panvk" : powervr ? "powervr" : xclipse ? "xclipse" : "turnip";
    }

    private static String label(String text) {
        String clean = text.replaceAll("[\\p{Cntrl}]", " ").trim();
        return clean.substring(0, Math.min(clean.length(), 120));
    }

    private static boolean validId(String id) {
        int slash = id.indexOf('/');
        return slash > 0 && id.substring(0, slash).matches(PACKAGE_NAME) && libraryName(id.substring(slash + 1));
    }

    private static boolean libraryName(String name) {
        return name.matches("[A-Za-z0-9_][A-Za-z0-9._-]{0,127}\\.so");
    }

    /** Dependency libraries may use '+' (notably libc++_shared.so), but can never become the selected entrypoint. */
    private static boolean packageLibraryName(String name) {
        return name.matches("[A-Za-z0-9_+][A-Za-z0-9._+-]{0,127}\\.so");
    }

    private static long copy(InputStream in, OutputStream out, long limit) throws IOException {
        long bytes = 0;
        byte[] buffer = new byte[65536];
        for (int n; (n = in.read(buffer)) != -1;) {
            bytes += n;
            if (bytes > limit) throw new IOException("Driver package is too large.");
            out.write(buffer, 0, n);
        }
        return bytes;
    }

    private static void requireArm64(File library) throws IOException {
        byte[] header = new byte[20];
        try (DataInputStream in = new DataInputStream(new FileInputStream(library))) { in.readFully(header); }
        if (header[0] != 0x7f || header[1] != 'E' || header[2] != 'L' || header[3] != 'F' ||
                header[4] != 2 || header[5] != 1 || header[16] != 3 || header[17] != 0 ||
                (header[18] & 255) != 183 || header[19] != 0)
            throw new IOException("Driver libraries must be Android ARM64 shared objects.");
    }

    private static void cleanPackage(File directory) {
        try {
            Files.walkFileTree(directory.toPath(), new SimpleFileVisitor<Path>() {
                @Override public FileVisitResult visitFile(Path path, BasicFileAttributes attributes) throws IOException {
                    Files.delete(path);
                    return FileVisitResult.CONTINUE;
                }
                @Override public FileVisitResult postVisitDirectory(Path path, IOException error) throws IOException {
                    if (error != null) throw error;
                    Files.delete(path);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) { Log.w("P4G-Launch", "Driver cleanup pending", e); }
    }
}
