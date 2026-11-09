package io.github.p4grecomp;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

public class AppFlowTest {
    static void check(boolean value) { if (!value) throw new AssertionError(); }
    static void bytes(File file, byte[] data) throws IOException {
        file.getParentFile().mkdirs();
        Files.write(file.toPath(), data);
    }
    static void text(File file, String text) throws IOException { bytes(file, text.getBytes("UTF-8")); }
    static byte[] spv() {
        byte[] b = new byte[20]; b[0] = 3; b[1] = 2; b[2] = 35; b[3] = 7; b[12] = 1; return b;
    }
    static File zip(File dir, String name, String... paths) throws IOException {
        File out = new File(dir, name);
        try (ZipOutputStream z = new ZipOutputStream(new FileOutputStream(out))) {
            for (String path : paths) {
                z.putNextEntry(new ZipEntry(path));
                z.write(("imported:" + path).getBytes("UTF-8"));
                z.closeEntry();
            }
        }
        return out;
    }
    static void installationFixture(File stage, String content) throws IOException {
        byte[] nso = new byte[0x100]; nso[0] = 'N'; nso[1] = 'S'; nso[2] = 'O'; nso[3] = '0';
        byte[] build = hex("C433D980688380A642C086072F620928");
        System.arraycopy(build, 0, nso, 0x40, build.length);
        bytes(new File(stage, "exefs/main"), nso); bytes(new File(stage, "exefs/sdk"), nso);
        text(new File(stage, "extract/romfs/CPK/data.cpk"), content);
        text(new File(stage, "analysis/main.bin"), "flat");
        text(new File(stage, "spirv/prepared.tsv"), PreparationFiles.FORMAT + "\n");
        text(new File(stage, ".import/complete"), "checked import\n");
        text(new File(stage, ".import/index.tsv"), "exefs\t4\t00000000\tanalysis/main.bin\nromfs\t" +
                content.getBytes("UTF-8").length + "\t00000000\textract/romfs/CPK/data.cpk\n");
    }
    static byte[] hex(String value) {
        byte[] out = new byte[value.length() / 2];
        for (int i = 0; i < out.length; i++) out[i] = (byte)Integer.parseInt(value.substring(i * 2, i * 2 + 2), 16);
        return out;
    }
    static void installedGameTests(File parent) throws Exception {
        File root = new File(parent, "installed-game-test"); root.mkdirs();
        text(new File(root, "user/save/data0001.bin"), "keep save");
        text(new File(root, "mods/translation/romfs/CPK/data.cpk"), "keep mod");
        text(new File(root, "config.txt"), "NX_TOUCH_HIDDEN=L3\n");
        text(new File(root, "spirv/prepared.tsv"), PreparationFiles.FORMAT + "\n");
        check(!InstalledGameFiles.ready(root)); // Old shader-only preparation cannot skip installation.
        File stage = InstalledGameFiles.begin(root);
        try { InstalledGameFiles.begin(root); throw new AssertionError("Concurrent preparation accepted"); }
        catch (IOException expected) { }
        installationFixture(stage, "installed data");
        InstalledGameFiles.finish(stage); check(!new File(stage, "analysis").exists());
        InstalledGameFiles.publish(root, stage);
        check(InstalledGameFiles.ready(root) && InstalledGameFiles.present(root));
        // No source NSP, URI, prod.keys or title.keys exists in this fixture.
        check("installed data".equals(new String(Files.readAllBytes(new File(root, "game/extract/romfs/CPK/data.cpk").toPath()), "UTF-8")));
        File original = InstalledGameFiles.game(root);
        stage = InstalledGameFiles.begin(root); installationFixture(stage, "bad");
        Files.delete(new File(stage, "exefs/sdk").toPath());
        try { InstalledGameFiles.finish(stage); throw new AssertionError("Missing sdk accepted"); }
        catch (IOException expected) { }
        InstalledGameFiles.discard(root, stage); check(InstalledGameFiles.ready(root));
        stage = InstalledGameFiles.begin(root); installationFixture(stage, "cannot publish"); InstalledGameFiles.finish(stage);
        File cannotRename = new File(stage.getPath()) { @Override public boolean renameTo(File destination) { return false; } };
        try { InstalledGameFiles.publish(root, cannotRename); throw new AssertionError("Failed publication accepted"); }
        catch (IOException expected) { }
        InstalledGameFiles.discard(root, stage); check(InstalledGameFiles.ready(root));
        stage = InstalledGameFiles.begin(root); installationFixture(stage, "replacement");
        text(new File(stage, ".import/index.tsv"), "romfs\t1\t00000000\t../outside\n");
        try { InstalledGameFiles.finish(stage); throw new AssertionError("Unsafe index accepted"); }
        catch (IOException expected) { }
        InstalledGameFiles.discard(root, stage);
        // Interrupted swap restores the previous complete payload.
        File previous = new File(root, ".game-previous"); Files.move(original.toPath(), previous.toPath());
        check(InstalledGameFiles.ready(root) && original.exists());
        Files.move(original.toPath(), previous.toPath());
        text(new File(original, "installed.tsv"), "incomplete\n");
        check(InstalledGameFiles.ready(root)); // Invalid new payload is quarantined and the valid backup restored.
        stage = InstalledGameFiles.begin(root); installationFixture(stage, "new data");
        InstalledGameFiles.finish(stage); InstalledGameFiles.publish(root, stage);
        check(InstalledGameFiles.ready(root) && !previous.exists());
        // A truncated extracted asset blocks Play; duplicate/unsafe manifests do too.
        File asset = new File(root, "game/extract/romfs/CPK/data.cpk");
        text(asset, "x"); check(!InstalledGameFiles.ready(root)); text(asset, "new data");
        File manifest = new File(root, "game/installed.tsv");
        String valid = new String(Files.readAllBytes(manifest.toPath()), "UTF-8");
        text(manifest, valid + "file\t8\textract/romfs/CPK/data.cpk\n"); check(!InstalledGameFiles.ready(root));
        text(manifest, valid); check(InstalledGameFiles.ready(root));
        // 64-bit sizes: files above 2 GiB must not overflow readiness or require reading all their bytes.
        stage = InstalledGameFiles.begin(root); installationFixture(stage, "large");
        long size = 3L * 1024 * 1024 * 1024 + 1;
        try (RandomAccessFile large = new RandomAccessFile(new File(stage, "extract/romfs/CPK/data.cpk"), "rw")) { large.setLength(size); }
        text(new File(stage, ".import/index.tsv"), "romfs\t" + size + "\t00000000\textract/romfs/CPK/data.cpk\n");
        InstalledGameFiles.finish(stage); InstalledGameFiles.publish(root, stage); check(InstalledGameFiles.ready(root));
        check("keep save".equals(new String(Files.readAllBytes(new File(root, "user/save/data0001.bin").toPath()), "UTF-8")));
        check("keep mod".equals(new String(Files.readAllBytes(new File(root, "mods/translation/romfs/CPK/data.cpk").toPath()), "UTF-8")));
        check(new String(Files.readAllBytes(new File(root, "config.txt").toPath()), "UTF-8").equals("NX_TOUCH_HIDDEN=L3\n"));
        System.out.println("PASS: installed data without NSP/keys, atomic replacement/recovery, incomplete imports, paths, 64-bit sizes and saves/mods/settings isolation");
    }
    static void smallInstallation(File root, String content) throws Exception {
        File stage = InstalledGameFiles.begin(root); installationFixture(stage, content);
        text(new File(stage, "spirv/extra.resources"), "keep generated resource metadata");
        InstalledGameFiles.finish(stage); InstalledGameFiles.publish(root, stage);
    }
    static SortedMap<String, String> snapshot(File root) throws Exception {
        SortedMap<String, String> result = new TreeMap<>();
        try (java.util.stream.Stream<Path> stream = Files.walk(root.toPath())) {
            for (Path path : (Iterable<Path>)stream::iterator) if (Files.isRegularFile(path))
                result.put(root.toPath().relativize(path).toString(), Base64.getEncoder().encodeToString(Files.readAllBytes(path)));
        }
        return result;
    }
    static void migrationTests(File parent) throws Exception {
        File old = new File(parent, "migration-rename/shared"), app = new File(parent, "migration-rename/Android/data/io.github.p4grecomp/files");
        smallInstallation(old, "rename game");
        text(new File(old, "user/save/data0001.bin"), "save"); text(new File(old, "mods/translation/romfs/data.cpk"), "mod");
        text(new File(old, "config.txt"), "config");
        SortedMap<String, String> source = snapshot(InstalledGameFiles.game(old));
        InstalledGameFiles.migrate(old, app);
        check(!InstalledGameFiles.game(old).exists() && InstalledGameFiles.ready(app));
        check(snapshot(InstalledGameFiles.game(app)).equals(source));
        InstalledGameFiles.migrate(old, app); check(InstalledGameFiles.ready(app)); // Reopening is idempotent.
        check(new File(old, "user/save/data0001.bin").isFile() && new File(old, "mods/translation/romfs/data.cpk").isFile());
        check(new String(Files.readAllBytes(new File(old, "config.txt").toPath()), "UTF-8").equals("config"));

        old = new File(parent, "migration-copy/shared"); app = new File(parent, "migration-copy/app-files");
        smallInstallation(old, "copy game"); source = snapshot(InstalledGameFiles.game(old));
        text(new File(app, "game/installed.tsv"), "incomplete target"); // Forces staged-copy path, independent of mount layout.
        InstalledGameFiles.migrate(old, app);
        check(InstalledGameFiles.ready(app) && !InstalledGameFiles.game(old).exists());
        check(snapshot(InstalledGameFiles.game(app)).equals(source));

        old = new File(parent, "migration-full/shared"); app = new File(parent, "migration-full/app-files");
        smallInstallation(old, "keep on disk-full"); source = snapshot(InstalledGameFiles.game(old));
        text(new File(app, "game/installed.tsv"), "incomplete target");
        File noSpace = new File(app.getPath()) { @Override public long getUsableSpace() { return 0; } };
        try { InstalledGameFiles.migrate(old, noSpace); throw new AssertionError("Disk-full migration accepted"); }
        catch (IOException expected) { }
        check(InstalledGameFiles.ready(old) && snapshot(InstalledGameFiles.game(old)).equals(source));
        check(app.listFiles((dir, name) -> name.startsWith(".game-preparing-")).length == 0);
        InstalledGameFiles.migrate(old, app); check(InstalledGameFiles.ready(app));

        // Termination after destination publication but before old-copy cleanup: receipt finishes only that move.
        old = new File(parent, "migration-receipt/shared"); app = new File(parent, "migration-receipt/app-files");
        smallInstallation(old, "same game"); smallInstallation(app, "same game");
        text(new File(app, "game/.migrated-from"), InstalledGameFiles.game(old).getCanonicalPath());
        InstalledGameFiles.migrate(old, app); check(!InstalledGameFiles.game(old).exists() && InstalledGameFiles.ready(app));
        // A valid unrelated destination is not replaced and does not authorize deleting the old installation.
        old = new File(parent, "migration-conflict/shared"); app = new File(parent, "migration-conflict/app-files");
        smallInstallation(old, "old game"); smallInstallation(app, "different game");
        InstalledGameFiles.migrate(old, app); check(InstalledGameFiles.ready(old) && InstalledGameFiles.ready(app));
        System.out.println("PASS: game-only migration, byte-for-byte rename/copy, low-space failure/retry, interrupted cleanup and existing-destination protection");
    }
    public static void main(String[] args) throws Exception {
        File temp = Files.createTempDirectory("p4g-flow-test").toFile();
        System.setProperty("p4g.test.root", temp.getPath());
        check("backup/save/".equals(SavePaths.prefix(Arrays.asList("backup/save/data0001.bin", "backup/save/data0001.binslot"))));
        check("".equals(SavePaths.prefix(Arrays.asList("system.bin", "data0099.bin"))));
        check("data0001.bin".equals(SavePaths.target("save/DATA0001.BIN", "save/")));
        check(SavePaths.target("save/../data0001.bin", "save/") == null);
        check(SavePaths.target("/data0001.bin", "") == null);
        check(SavePaths.target("save/notes.txt", "save/") == null);
        check(SavePaths.prefix(Arrays.asList("DATA01/DATA.DAT")) == null); // P5R is not P4G.
        try {
            SavePaths.prefix(Arrays.asList("first/data0001.bin", "second/data0002.bin"));
            throw new AssertionError("Ambiguous saves accepted");
        } catch (IOException expected) { }

        android.content.ContentResolver resolver = new android.content.ContentResolver();
        File existing = new File(SaveFiles.saveDir(), "data0001.bin");
        text(existing, "original");
        File archive = zip(temp, "save.zip", "backup/user/save/data0001.bin", "backup/user/save/data0001.binslot", "notes.txt");
        SaveFiles.Result result = SaveFiles.importZip(resolver, android.net.Uri.parse(archive.getPath()));
        check(result.files == 2 && result.slots.contains("0001") && result.backup != null);
        check(new String(Files.readAllBytes(existing.toPath()), "UTF-8").startsWith("imported:"));
        check(new String(Files.readAllBytes(new File(temp, "user/" + result.backup + "/data0001.bin").toPath()), "UTF-8").equals("original"));
        check(!new File(SaveFiles.saveDir(), "notes.txt").exists());
        File exported = SaveFiles.export();
        check(exported.getName().startsWith("P4G-saves-"));
        check(SaveFiles.importZip(resolver, android.net.Uri.parse(exported.getPath())).files == 2);
        byte[] before = Files.readAllBytes(existing.toPath());
        try {
            SaveFiles.importZip(resolver, android.net.Uri.parse(zip(temp, "missing-pair.zip", "data0001.bin").getPath()));
            throw new AssertionError("Missing binslot accepted");
        } catch (IOException expected) { }
        check(Arrays.equals(before, Files.readAllBytes(existing.toPath())));
        check(SaveFiles.importZip(resolver, android.net.Uri.parse(zip(temp, "unsafe.zip", "../data0002.bin", "../data0002.binslot").getPath())) == null);

        File folder = new File(temp, "folder-backup/save");
        text(new File(folder, "data0002.bin"), "folder save");
        text(new File(folder, "data0002.binslot"), "folder metadata");
        text(new File(folder, "notes.txt"), "not a save");
        result = SaveFiles.importTree(resolver, android.net.Uri.parse(folder.getParentFile().getPath()));
        check(result.files == 2 && result.slots.contains("0002"));
        check(new String(Files.readAllBytes(new File(SaveFiles.saveDir(), "data0002.bin").toPath()), "UTF-8").equals("folder save"));
        check(Arrays.equals(before, Files.readAllBytes(existing.toPath()))); // Slots not imported stay intact.

        File slot = new File(SaveFiles.saveDir(), "data0001.binslot");
        Files.delete(slot.toPath());
        Files.createDirectory(slot.toPath()); // Cannot replace a directory with a regular save file.
        try {
            SaveFiles.importZip(resolver, android.net.Uri.parse(archive.getPath()));
            throw new AssertionError("Simulated commit failure was ignored");
        } catch (IOException expected) {
            check(Arrays.equals(before, Files.readAllBytes(existing.toPath())));
        }
        Files.delete(slot.toPath());
        text(slot, "original slot metadata");

        // Simulate termination between the two renames, then reopen.
        File previous = new File(temp, "user/.save-import-previous");
        File next = new File(temp, "user/.save-import-next");
        text(new File(next, "data0001.bin"), "staged");
        Files.move(SaveFiles.saveDir().toPath(), previous.toPath());
        SaveFiles.recover();
        check(Arrays.equals(before, Files.readAllBytes(existing.toPath())));
        check("original slot metadata".equals(new String(Files.readAllBytes(slot.toPath()), "UTF-8")));
        check(!previous.exists() && !next.exists());

        File runtime = new File(temp, "spirv");
        File marker = new File(runtime, "prepared.tsv");
        File packed = new File(runtime, "0123456789abcdef.spv");
        File metadata = new File(runtime, "0123456789abcdef.resources");
        // Old copied packs are never considered locally prepared.
        bytes(packed, spv()); text(metadata, "cb0=s0b1");
        check(!PreparationFiles.ready(runtime));
        text(marker, PreparationFiles.FORMAT + "\nseed\t0123456789abcdef\n");
        check(PreparationFiles.ready(runtime));
        Files.delete(packed.toPath()); check(!PreparationFiles.ready(runtime));
        bytes(packed, spv()); Files.delete(metadata.toPath()); check(!PreparationFiles.ready(runtime));
        text(metadata, ""); check(PreparationFiles.ready(runtime)); // no resources is legitimate
        bytes(packed, new byte[20]); check(!PreparationFiles.ready(runtime));
        bytes(packed, spv()); text(new File(runtime, "failed.txt"), "translation error");
        check("translation error".equals(PreparationFiles.failure(runtime)));
        check(PreparationFiles.ready(runtime)); Files.delete(new File(runtime, "failed.txt").toPath());
        text(marker, "format\told-cache\n"); check(!PreparationFiles.ready(runtime));
        text(marker, PreparationFiles.FORMAT + "\n"); check(PreparationFiles.ready(runtime));
        // A damaged runtime module is retried on use, independently of preparation.
        bytes(new File(runtime, "fedcba9876543210.spv"), new byte[20]);
        check(PreparationFiles.ready(runtime));
        installedGameTests(temp);
        migrationTests(temp);
        System.out.println("PASS: actual ZIP/folder import, backup, round trip, rollback, save paths and preparation readiness");
    }
}
