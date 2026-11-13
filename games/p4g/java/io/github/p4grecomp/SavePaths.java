package io.github.p4grecomp;

import java.io.IOException;
import java.util.List;
import java.util.Locale;

/** P4G stores dataXXXX.bin + .binslot directly inside user/save. */
final class SavePaths {
    static boolean safe(String path) {
        if (path.isEmpty() || path.startsWith("/") || path.contains("\\") || path.contains(":")) return false;
        for (String part : path.split("/", -1))
            if (part.isEmpty() || part.equals(".") || part.equals("..") || part.equals("__MACOSX")) return false;
        return true;
    }
    static boolean file(String name) {
        return name.toLowerCase(Locale.ROOT).matches("(?:data[0-9]{4}|system)\\.(?:bin|binslot)");
    }
    static String prefix(List<String> paths) throws IOException {
        String found = null;
        for (String path : paths) {
            if (!safe(path)) continue;
            int slash = path.lastIndexOf('/');
            if (!file(path.substring(slash + 1))) continue;
            String parent = path.substring(0, slash + 1);
            if (found != null && !found.equals(parent))
                throw new IOException("The package contains more than one save folder. Select only the backup you want to import.");
            found = parent;
        }
        return found;
    }
    static String target(String path, String prefix) {
        if (prefix == null || !safe(path) || !path.startsWith(prefix)) return null;
        String name = path.substring(prefix.length());
        return file(name) ? name.toLowerCase(Locale.ROOT) : null;
    }
}
