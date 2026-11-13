package io.github.p4grecomp;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public class ModFilesTest {
    private static void check(boolean condition) { if (!condition) throw new AssertionError(); }

    private static void write(File root, String path) throws IOException {
        Path file = new File(root, path).toPath();
        Files.createDirectories(file.getParent());
        Files.write(file, path.getBytes("UTF-8"));
    }

    private static ModFiles.Mod find(ModFiles.Snapshot snapshot, String name, boolean enabled) {
        for (ModFiles.Mod mod : snapshot.mods)
            if (mod.name.equals(name) && mod.enabled == enabled) return mod;
        throw new AssertionError("Missing mod " + name + " enabled=" + enabled);
    }

    public static void main(String[] args) throws Exception {
        File root = Files.createTempDirectory("p4g-mod-test").toFile();
        write(root, "mods/translation/romfs/data_e.cpk");
        write(root, "mods/tradução_p4g/romfs/DATA_E.CPK");
        write(root, "mods/combined/romfs/data.cpk");
        write(root, "mods/combined/exefs/main");
        write(root, "mods/_disabled/romfs/data.cpk");
        write(root, "mods/romfs/data.cpk");
        write(root, "mods/exefs/main");
        write(root, "mods/legacy/CPK/PATCH1.CPK");
        write(root, "mods/.hidden/romfs/data_e.cpk");
        write(root, "mods/unknown/data_e.cpk");
        write(root, "mods/translation/romfs/.import-pending.tmp");
        write(root, "atmosphere/contents/010062b01525c000/romfs/data.cpk");
        write(root, "atmosphere/contents/other/_romfs/other.bin");
        Path outside = Files.createTempDirectory("p4g-other-mod");
        Files.write(outside.resolve("external.bin"), new byte[] {1});
        Files.createSymbolicLink(new File(root, "mods/translation/romfs/external").toPath(), outside);

        ModFiles.Snapshot state = ModFiles.scan(root);
        check(state.mods.size() == 9);
        check(state.conflicts.size() == 3);
        ModFiles.Mod translation = find(state, "translation", true);
        check(translation.files.size() == 1);
        List<ModFiles.Mod> textOwners = state.conflicts.get("romfs/data_e.cpk");
        check(textOwners.size() == 2);
        check(textOwners.get(1).name.equals("translation"));
        check(state.conflictCount(translation) == 1);
        check(state.conflicts.get("exefs/main").get(1).name.equals("exefs"));

        byte[] before = Files.readAllBytes(new File(root, "mods/translation/romfs/data_e.cpk").toPath());
        ModFiles.setEnabled(translation, false);
        state = ModFiles.scan(root);
        translation = find(state, "translation", false);
        check(!state.conflicts.containsKey("romfs/data_e.cpk"));
        check(state.overlapsWhenEnabled(translation) == 1);
        File disabledRomfs = ModFiles.translationRomfs(root);
        check(disabledRomfs.equals(new File(root, "mods/_translation/romfs")));
        check(java.util.Arrays.equals(before, Files.readAllBytes(new File(disabledRomfs, "data_e.cpk").toPath())));
        ModFiles.setEnabled(translation, true);
        check(ModFiles.translationRomfs(root).equals(new File(root, "mods/translation/romfs")));

        ModFiles.Mod combined = find(state, "combined", true);
        check(combined.files.size() == 2);
        ModFiles.setEnabled(combined, false);
        state = ModFiles.scan(root);
        check(find(state, "combined", false).files.size() == 2);
        check(new File(root, "mods/_combined/exefs/main").isFile());
        check(!state.conflicts.containsKey("exefs/main"));

        ModFiles.setEnabled(find(state, "romfs", true), false);
        ModFiles.setEnabled(find(state, "exefs", true), false);
        ModFiles.setEnabled(find(state, "Atmosphere / 010062b01525c000", true), false);
        state = ModFiles.scan(root);
        check(!state.conflicts.containsKey("romfs/data.cpk"));
        check(new File(root, "mods/_romfs/data.cpk").isFile());
        check(new File(root, "mods/_exefs/main").isFile());
        check(new File(root, "atmosphere/contents/010062b01525c000/_romfs/data.cpk").isFile());
        ModFiles.setEnabled(find(state, "Atmosphere / other", false), true);
        check(new File(root, "atmosphere/contents/other/romfs/other.bin").isFile());

        write(root, "mods/_translation/romfs/old.cpk");
        state = ModFiles.scan(root);
        try {
            ModFiles.setEnabled(find(state, "translation", false), true);
            throw new AssertionError("An existing destination must be preserved");
        } catch (IOException expected) {
            check(new File(root, "mods/translation/romfs/data_e.cpk").isFile());
            check(new File(root, "mods/_translation/romfs/old.cpk").isFile());
        }
        check(ModFiles.scan(new File(root, "missing")).mods.isEmpty());
        System.out.println("PASS mod discovery, files, conflicts, priority, toggles, disabled imports and existing-folder protection");
    }
}
