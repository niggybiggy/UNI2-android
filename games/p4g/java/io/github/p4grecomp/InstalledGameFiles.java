package io.github.p4grecomp;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/** One complete, replaceable installation. Saves, mods and settings stay outside this directory. */
final class InstalledGameFiles {
    static final String FORMAT = "format\tp4g-installed-v1";
    static final String TITLE = "title\t0100bd401d18a000";
    private static final Object LOCK = new Object();
    private static boolean preparing;

    static File game(File root) { return new File(root, "game"); }
    static File shaders(File root) { return new File(game(root), "spirv"); }

    /** One-time move from the shared data folder to Android/data/<package>/files. Never moves saves or mods. */
    static void migrate(File legacyRoot, File destinationRoot) throws IOException {
        synchronized (LOCK) {
            if (legacyRoot.getCanonicalFile().equals(destinationRoot.getCanonicalFile())) return;
            recover(destinationRoot);
            File source = game(legacyRoot), destination = game(destinationRoot);
            if (!source.exists() && !new File(legacyRoot, ".game-previous").exists() &&
                    !new File(destination, ".migrated-from").exists()) return;
            if (valid(destination)) { cleanupMigration(source, destination); return; }
            recover(legacyRoot);
            if (!valid(source)) return;
            if (preparing) throw new IOException("Game preparation is already running");
            if (!destinationRoot.isDirectory() && !destinationRoot.mkdirs()) throw new IOException("Could not create app storage");
            // Both paths usually share the emulated-storage volume: a rename avoids copying several gigabytes.
            if (!destination.exists() && source.renameTo(destination)) {
                boolean verified;
                try { verified = valid(destination); } catch (IOException e) { verified = false; }
                if (verified) return;
                if (!destination.renameTo(source)) throw new IOException("Migration verification failed; game data kept at " + destination);
                throw new IOException("Migration verification failed. The previous installation was restored");
            }
            // FUSE/storage providers may reject a rename across the Android/data boundary. Copy into staging,
            // publish the verified payload, then remove the old copy. A failed copy keeps the source intact.
            File stage = begin(destinationRoot);
            try {
                long bytes = treeSize(source);
                long available = destinationRoot.getUsableSpace();
                if (available < bytes || available - bytes < 64L * 1024 * 1024)
                    throw new IOException("Not enough free app storage to move the installed game. The old installation is unchanged");
                copyTree(source, stage, new byte[128 * 1024]);
                if (!valid(stage)) throw new IOException("Copied game data is incomplete. The old installation is unchanged");
                try (FileOutputStream out = new FileOutputStream(new File(stage, ".migrated-from"))) {
                    out.write(source.getCanonicalPath().getBytes("UTF-8")); out.getFD().sync();
                }
                publish(destinationRoot, stage);
                stage = null;
                cleanupMigration(source, destination);
            } finally {
                if (stage != null) discard(destinationRoot, stage);
            }
        }
    }

    private static void cleanupMigration(File source, File destination) throws IOException {
        File receipt = new File(destination, ".migrated-from");
        if (!receipt.isFile() || receipt.length() > 4096 || !source.getCanonicalPath().equals(
                new String(Files.readAllBytes(receipt.toPath()), "UTF-8"))) return;
        File oldManifest = new File(source, "installed.tsv"), newManifest = new File(destination, "installed.tsv");
        if (oldManifest.isFile() && (oldManifest.length() != newManifest.length() || oldManifest.length() > 16L * 1024 * 1024 ||
                !Arrays.equals(Files.readAllBytes(oldManifest.toPath()), Files.readAllBytes(newManifest.toPath())))) return;
        // If cleanup is interrupted, the receipt permits retrying only this exact old installation folder.
        try { removeTree(source); Files.deleteIfExists(receipt.toPath()); }
        catch (IOException ignored) { /* The valid destination is already playable; retry cleanup on reopening. */ }
    }

    private static long treeSize(File file) throws IOException {
        if (Files.isSymbolicLink(file.toPath())) throw new IOException("Symbolic links are not supported in an installed game");
        if (!file.isDirectory()) {
            if (!file.isFile()) throw new IOException("Could not read installed game data");
            return file.length();
        }
        File[] children = file.listFiles();
        if (children == null) throw new IOException("Could not read installed game folder");
        long total = 0;
        for (File child : children) {
            long size = treeSize(child);
            if (Long.MAX_VALUE - total < size) throw new IOException("Installed game size overflow");
            total += size;
        }
        return total;
    }

    private static void copyTree(File from, File to, byte[] buffer) throws IOException {
        if (Files.isSymbolicLink(from.toPath())) throw new IOException("Symbolic links are not supported in an installed game");
        if (from.isDirectory()) {
            if (!to.isDirectory() && !to.mkdirs()) throw new IOException("Could not create migrated game folder");
            File[] children = from.listFiles();
            if (children == null) throw new IOException("Could not read installed game folder");
            for (File child : children) copyTree(child, new File(to, child.getName()), buffer);
        } else {
            long copied = 0;
            try (InputStream in = new FileInputStream(from); FileOutputStream out = new FileOutputStream(to)) {
                for (int n; (n = in.read(buffer)) != -1;) { out.write(buffer, 0, n); copied += n; }
                out.getFD().sync();
            }
            if (copied != from.length() || to.length() != copied) throw new IOException("Incomplete game-data copy");
        }
    }

    static File child(File root, String relative) throws IOException {
        if (relative.isEmpty() || relative.startsWith("/") || relative.indexOf('\\') >= 0 ||
                relative.indexOf('\t') >= 0 || relative.indexOf('\n') >= 0 || relative.indexOf('\r') >= 0)
            throw new IOException("Unsafe installed file path");
        for (String part : relative.split("/", -1))
            if (part.isEmpty() || part.equals(".") || part.equals("..")) throw new IOException("Unsafe installed file path");
        File file = new File(root, relative);
        // Every part of `relative` was checked above (no empty, "." or ".." part, no backslash), so a plain lexical
        // check is enough to stay inside the folder. The canonical path is not compared: on Android's FUSE storage it
        // intermittently came back different for the same file ("escapes the game folder" with identical paths).
        java.nio.file.Path base = root.toPath().toAbsolutePath().normalize();
        java.nio.file.Path target = file.toPath().toAbsolutePath().normalize();
        if (!target.startsWith(base) || java.nio.file.Files.isSymbolicLink(file.toPath()))
            throw new IOException("Installed file escapes the game folder: " + relative);
        return file;
    }

    static boolean nso(File file) throws IOException {
        if (!file.isFile() || file.length() < 0x100) return false;
        try (DataInputStream in = new DataInputStream(new FileInputStream(file))) { return in.readInt() == 0x4e534f30; }
    }

    static String buildId(File file) throws IOException {
        if (!nso(file)) throw new IOException("The installed main executable is not a valid NSO");
        byte[] id = new byte[16];
        try (RandomAccessFile in = new RandomAccessFile(file, "r")) {
            in.seek(0x40);
            in.readFully(id);
        }
        StringBuilder out = new StringBuilder(32);
        for (byte value : id) out.append(String.format(Locale.ROOT, "%02X", value & 0xff));
        return out.toString();
    }

    static String revision(File current) throws IOException {
        // UNI2: any build is accepted for now; the Build ID is only reported (it is written to the logs).
        return buildId(new File(current, "exefs/main"));
    }

    /** Small launch check; the full file list is checked off the UI thread by ready(). */
    static boolean present(File root) {
        File current = game(root);
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                new FileInputStream(new File(current, "installed.tsv")), "UTF-8"))) {
            return FORMAT.equals(r.readLine()) && TITLE.equals(r.readLine()) &&
                    nso(new File(current, "exefs/main")) && nso(new File(current, "exefs/sdk")) &&
                    new File(current, "extract/romfs").isDirectory() &&
                    new File(current, "spirv/prepared.tsv").isFile();
        } catch (IOException e) { return false; }
    }

    static boolean valid(File current) throws IOException {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                new FileInputStream(new File(current, "installed.tsv")), "UTF-8"))) {
            if (!FORMAT.equals(r.readLine()) || !TITLE.equals(r.readLine())) return false;
            Set<String> paths = new HashSet<>();
            int romfs = 0;
            for (String line; (line = r.readLine()) != null;) {
                String[] f = line.split("\t", -1);
                if (f.length != 3 || !f[0].equals("file") || !paths.add(f[2]) ||
                        !(f[2].startsWith("exefs/") || f[2].startsWith("extract/romfs/"))) return false;
                long size;
                try { size = Long.parseLong(f[1]); } catch (NumberFormatException e) { return false; }
                File file = child(current, f[2]);
                if (size < 0 || !file.isFile() || file.length() != size) return false;
                if (f[2].startsWith("extract/romfs/")) ++romfs;
            }
            return romfs > 0 && paths.contains("exefs/main") && paths.contains("exefs/sdk") &&
                    nso(new File(current, "exefs/main")) && nso(new File(current, "exefs/sdk")) &&
                    PreparationFiles.ready(new File(current, "spirv"));
        } catch (FileNotFoundException e) { return false; }
    }

    static boolean ready(File root) throws IOException {
        synchronized (LOCK) { recover(root); return valid(game(root)); }
    }

    static void recover(File root) throws IOException {
        synchronized (LOCK) {
            File current = game(root), backup = new File(root, ".game-previous");
            if (!backup.exists()) return;
            if (!current.exists()) {
                if (!backup.renameTo(current)) throw new IOException("Could not restore the previous game installation");
            } else if (!valid(current)) {
                File damaged = new File(root, ".game-incomplete-" + UUID.randomUUID());
                if (!valid(backup) || !current.renameTo(damaged) || !backup.renameTo(current))
                    throw new IOException("Installation recovery failed. Previous data kept at " + backup);
            }
            if (backup.exists()) { try { removeTree(backup); } catch (IOException ignored) { } }
        }
    }

    static File begin(File root) throws IOException {
        synchronized (LOCK) {
            if (preparing) throw new IOException("Game preparation is already running");
            if (!root.isDirectory() && !root.mkdirs()) throw new IOException("Could not create the game data folder");
            recover(root);
            File[] oldStages = root.listFiles((dir, name) -> name.matches("\\.game-preparing-[0-9a-f-]{36}"));
            if (oldStages != null) for (File old : oldStages) removeTree(old);
            File stage = new File(root, ".game-preparing-" + UUID.randomUUID());
            if (!stage.mkdir()) throw new IOException("Could not create the installation folder");
            preparing = true;
            return stage;
        }
    }

    /** Validate the importer's completion/index before publishing our own file-size manifest. */
    static void finish(File stage) throws IOException {
        if (new File(stage, ".import/complete").length() == 0 ||
                !nso(new File(stage, "exefs/main")) || !nso(new File(stage, "exefs/sdk")) ||
                !PreparationFiles.ready(new File(stage, "spirv"))) throw new IOException("Game extraction or shader preparation is incomplete");
        revision(stage);
        SortedMap<String, Long> files = new TreeMap<>();
        int romfs = 0;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                new FileInputStream(new File(stage, ".import/index.tsv")), "UTF-8"))) {
            Set<String> seen = new HashSet<>();
            for (String line; (line = r.readLine()) != null;) {
                String[] f = line.split("\t", -1);
                if (f.length != 4 || !f[2].matches("[0-9a-fA-F]{8}") || !seen.add(f[3]) ||
                        !(f[3].startsWith("analysis/") || f[3].startsWith("extract/romfs/")))
                    throw new IOException("Invalid extraction index");
                long size;
                try { size = Long.parseLong(f[1]); } catch (NumberFormatException e) { throw new IOException("Invalid extraction size", e); }
                File file = child(stage, f[3]);
                if (size < 0 || !file.isFile() || file.length() != size) throw new IOException("Incomplete extracted file: " + f[3]);
                if (f[3].startsWith("extract/romfs/")) { files.put(f[3], size); ++romfs; }
            }
        }
        if (romfs == 0) throw new IOException("The extracted game has no RomFS files");
        File[] exefs = new File(stage, "exefs").listFiles();
        if (exefs == null) throw new IOException("Missing extracted executables");
        for (File file : exefs) {
            File checked = child(stage, "exefs/" + file.getName());
            if (!checked.isFile()) throw new IOException("Invalid executable entry");
            files.put("exefs/" + file.getName(), checked.length());
        }
        try (FileOutputStream stream = new FileOutputStream(new File(stage, "installed.tsv"));
                Writer writer = new OutputStreamWriter(stream, "UTF-8")) {
            writer.write(FORMAT + "\n" + TITLE + "\n");
            for (Map.Entry<String, Long> f : files.entrySet()) writer.write("file\t" + f.getValue() + "\t" + f.getKey() + "\n");
            writer.flush(); stream.getFD().sync();
        }
        if (!valid(stage)) throw new IOException("Installed game verification failed");
        // Flat analysis images are only needed during extraction. NCE uses the raw ExeFS files.
        removeTree(new File(stage, "analysis"));
    }

    static void publish(File root, File stage) throws IOException {
        synchronized (LOCK) {
            if (!root.getCanonicalFile().equals(stage.getCanonicalFile().getParentFile()) ||
                    !stage.getName().startsWith(".game-preparing-") || !valid(stage))
                throw new IOException("Invalid installation staging directory");
            recover(root);
            File current = game(root), backup = new File(root, ".game-previous");
            if (backup.exists()) removeTree(backup); // Only a backup superseded by a valid current installation.
            boolean hadGame = current.exists();
            if (hadGame && !current.renameTo(backup)) throw new IOException("Could not preserve the previous installation");
            if (!stage.renameTo(current)) {
                if (hadGame && !backup.renameTo(current)) throw new IOException("Could not install game; previous data kept at " + backup);
                throw new IOException("Could not install the extracted game");
            }
            preparing = false;
            if (backup.exists()) { try { removeTree(backup); } catch (IOException ignored) { } }
        }
    }

    static void discard(File root, File stage) throws IOException {
        synchronized (LOCK) {
            if (stage != null && root.getCanonicalFile().equals(stage.getCanonicalFile().getParentFile()) &&
                    stage.getName().startsWith(".game-preparing-")) {
                try { removeTree(stage); } finally { preparing = false; }
            }
        }
    }

    private static void removeTree(File file) throws IOException {
        if (Files.isSymbolicLink(file.toPath())) { Files.delete(file.toPath()); return; }
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children == null) throw new IOException("Could not read " + file);
            for (File child : children) removeTree(child);
        }
        Files.deleteIfExists(file.toPath());
    }

    private InstalledGameFiles() {}
}
