package io.github.p4grecomp;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Uses the runtime's folder layouts and leading-underscore convention for disabled mods. */
final class ModFiles {
    private ModFiles() {}

    static final class Mod {
        final String name;
        final File path, enabledPath, disabledPath, romfs, exefs;
        final boolean enabled;
        final List<String> files = new ArrayList<>();

        Mod(String name, File path, File enabledPath, File disabledPath, File romfs, File exefs) throws IOException {
            this.name = name;
            this.path = path;
            this.enabledPath = enabledPath;
            this.disabledPath = disabledPath;
            this.enabled = path.equals(enabledPath);
            this.romfs = romfs;
            this.exefs = exefs;
            collect(romfs, "romfs/", files);
            collect(exefs, "exefs/", files);
            Collections.sort(files);
        }

        File priorityRoot(String file) {
            File root = file.startsWith("exefs/") ? exefs : romfs;
            return new File(enabledPath, path.toPath().relativize(root.toPath()).toString());
        }
    }

    static final class Snapshot {
        final List<Mod> mods;
        final Map<String, List<Mod>> conflicts = new TreeMap<>();

        Snapshot(List<Mod> mods) {
            this.mods = mods;
            Map<String, List<Mod>> owners = new TreeMap<>();
            for (Mod mod : mods) {
                if (!mod.enabled) continue;
                for (String key : keys(mod)) owners.computeIfAbsent(key, k -> new ArrayList<>()).add(mod);
            }
            for (Map.Entry<String, List<Mod>> entry : owners.entrySet()) {
                if (entry.getValue().size() < 2) continue;
                String file = entry.getKey();
                entry.getValue().sort((a, b) -> comparePaths(a.priorityRoot(file), b.priorityRoot(file)));
                conflicts.put(file, entry.getValue());
            }
        }

        int conflictCount(Mod mod) {
            int count = 0;
            for (List<Mod> owners : conflicts.values()) if (owners.contains(mod)) ++count;
            return count;
        }

        int overlapsWhenEnabled(Mod mod) {
            Set<String> active = new HashSet<>();
            for (Mod other : mods) if (other.enabled && other != mod) active.addAll(keys(other));
            active.retainAll(keys(mod));
            return active.size();
        }
    }

    static synchronized Snapshot scan(File data) throws IOException {
        List<Mod> mods = new ArrayList<>();
        File root = new File(data, "mods");
        for (File folder : children(root)) {
            if (!directory(folder)) continue;
            String name = folder.getName();
            boolean enabled = !name.startsWith("_");
            String plain = enabled ? name : name.substring(1);
            if (plain.isEmpty() || plain.startsWith(".") || plain.startsWith("_")) continue;
            File romfs = null, exefs = null;
            if (plain.equals("romfs")) romfs = folder;
            else if (plain.equals("exefs")) exefs = folder;
            else {
                File rom = new File(folder, "romfs"), exe = new File(folder, "exefs");
                if (directory(rom)) romfs = rom;
                else if (legacyRomfs(folder)) romfs = folder;
                if (directory(exe)) exefs = exe;
            }
            if (romfs == null && exefs == null) continue;
            mods.add(new Mod(plain, folder, new File(root, plain), new File(root, "_" + plain), romfs, exefs));
        }
        File atmosphere = new File(data, "atmosphere/contents");
        for (File title : children(atmosphere)) {
            if (!directory(title)) continue;
            File active = new File(title, "romfs"), inactive = new File(title, "_romfs");
            for (File folder : new File[] {active, inactive}) {
                if (directory(folder))
                    mods.add(new Mod("Atmosphere / " + title.getName(), folder, active, inactive, folder, null));
            }
        }
        mods.sort((a, b) -> comparePaths(a.enabledPath, b.enabledPath));
        return new Snapshot(mods);
    }

    static synchronized void setEnabled(Mod mod, boolean enabled) throws IOException {
        if (enabled == mod.enabled) return;
        File destination = enabled ? mod.enabledPath : mod.disabledPath;
        if (!directory(mod.path)) throw new IOException("The mod folder changed. Reopen the manager.");
        if (Files.exists(destination.toPath(), LinkOption.NOFOLLOW_LINKS))
            throw new IOException("A folder named " + destination.getName() + " already exists. Both folders were kept.");
        Files.move(mod.path.toPath(), destination.toPath());
    }

    static synchronized File translationRomfs(File data) {
        File active = new File(data, "mods/translation");
        File inactive = new File(data, "mods/_translation");
        return new File(!active.exists() && directory(inactive) ? inactive : active, "romfs");
    }

    private static boolean directory(File file) {
        return Files.isDirectory(file.toPath(), LinkOption.NOFOLLOW_LINKS);
    }

    private static File[] children(File folder) throws IOException {
        if (!folder.exists()) return new File[0];
        File[] files = folder.listFiles();
        if (files == null) throw new IOException("Could not list " + folder.getPath());
        return files;
    }

    private static boolean legacyRomfs(File folder) {
        for (String name : new String[] {"CPK", "cpk", "sound", "movie", "field", "app_config"})
            if (directory(new File(folder, name))) return true;
        return false;
    }

    private static void collect(File folder, String prefix, List<String> files) throws IOException {
        if (folder == null) return;
        Path root = folder.toPath();
        Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
            @Override public FileVisitResult visitFile(Path path, BasicFileAttributes attributes) {
                if (attributes.isRegularFile()) {
                    String relative = root.relativize(path).toString().replace(File.separatorChar, '/');
                    if (!path.getFileName().toString().startsWith(".import-")) files.add(prefix + relative);
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static Set<String> keys(Mod mod) {
        Set<String> result = new HashSet<>();
        for (String file : mod.files) result.add(key(file));
        return result;
    }

    static String key(String file) {
        // Match the native filesystem's ASCII case folding while retaining non-ASCII filenames.
        char[] chars = file.toCharArray();
        for (int i = 0; i < chars.length; ++i)
            if (chars[i] >= 'A' && chars[i] <= 'Z') chars[i] += 'a' - 'A';
        return new String(chars);
    }

    private static int comparePaths(File a, File b) {
        byte[] left = a.getPath().getBytes(StandardCharsets.UTF_8);
        byte[] right = b.getPath().getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i < Math.min(left.length, right.length); ++i) {
            int difference = (left[i] & 255) - (right[i] & 255);
            if (difference != 0) return difference;
        }
        return Integer.compare(left.length, right.length);
    }
}
