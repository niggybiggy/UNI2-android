package io.github.p4grecomp;

import android.app.AlertDialog;
import java.io.IOException;
import java.util.List;
import java.util.Map;

/** Scans and changes mod folders off the UI thread. Changes take effect on the next game start. */
final class ModManager {
    private final Launcher activity;
    private AlertDialog dialog;
    private boolean busy;

    ModManager(Launcher activity) { this.activity = activity; }

    boolean busy() { return busy; }

    void dismiss() { if (dialog != null) dialog.dismiss(); }

    private boolean alive() { return !activity.isFinishing() && !activity.isDestroyed(); }

    void show() {
        if (busy || (dialog != null && dialog.isShowing())) return;
        reload(null, false);
    }

    private void reload(ModFiles.Mod change, boolean enabled) {
        busy = true;
        AlertDialog progress = new AlertDialog.Builder(activity)
                .setTitle("Mods and translations")
                .setMessage(change == null ? "Reading installed mods..." : "Updating mod...")
                .setCancelable(false).create();
        dialog = progress;
        progress.show();
        new Thread(() -> {
            ModFiles.Snapshot snapshot = null;
            String error = null;
            try {
                if (change != null) ModFiles.setEnabled(change, enabled);
                snapshot = ModFiles.scan(Launcher.dataDir());
            } catch (IOException | RuntimeException e) {
                error = e.getMessage();
                if (error == null) error = e.toString();
            }
            final ModFiles.Snapshot result = snapshot;
            final String failure = error;
            activity.runOnUiThread(() -> {
                busy = false;
                if (!alive()) return;
                progress.dismiss();
                if (failure == null) showMods(result);
                else dialog = new AlertDialog.Builder(activity).setTitle("Could not read or update mods")
                        .setMessage(failure).setPositiveButton("Retry", (d, w) -> reload(null, false))
                        .setNegativeButton("Close", null).show();
            });
        }, "P4G-mod-manager").start();
    }

    private void showMods(ModFiles.Snapshot snapshot) {
        AlertDialog.Builder builder = new AlertDialog.Builder(activity).setTitle("Mods and translations")
                .setNegativeButton("Close", null);
        if (snapshot.mods.isEmpty()) {
            builder.setMessage("No mods found. Import a translation with Import CPK, or place a mod in "
                    + "mods/<name>/romfs/. Changes apply the next time you open the game.");
        } else {
            String[] rows = new String[snapshot.mods.size()];
            for (int i = 0; i < rows.length; ++i) {
                ModFiles.Mod mod = snapshot.mods.get(i);
                int conflicts = snapshot.conflictCount(mod);
                rows[i] = mod.name + "\n" + (mod.enabled ? "Enabled" : "Disabled")
                        + " · " + mod.files.size() + " files"
                        + (conflicts == 0 ? "" : " · " + conflicts + " conflicting files");
            }
            builder.setItems(rows, (d, index) -> showMod(snapshot, snapshot.mods.get(index)));
        }
        if (!snapshot.conflicts.isEmpty())
            builder.setNeutralButton("Conflicts (" + snapshot.conflicts.size() + ")", (d, w) -> showConflicts(snapshot));
        dialog = builder.show();
    }

    private void showMod(ModFiles.Snapshot snapshot, ModFiles.Mod mod) {
        String path = Launcher.dataDir().toPath().relativize(mod.path.toPath()).toString();
        dialog = new AlertDialog.Builder(activity).setTitle(mod.name)
                .setMessage((mod.enabled ? "Enabled" : "Disabled") + " · " + mod.files.size() + " files\n"
                        + path + "\n\nChanges apply the next time you open the game.")
                .setPositiveButton(mod.enabled ? "Disable" : "Enable", (d, w) -> toggle(snapshot, mod))
                .setNeutralButton("View files", (d, w) -> showFiles(snapshot, mod))
                .setNegativeButton("Back", (d, w) -> showMods(snapshot)).show();
    }

    private void toggle(ModFiles.Snapshot snapshot, ModFiles.Mod mod) {
        int overlaps = mod.enabled ? 0 : snapshot.overlapsWhenEnabled(mod);
        if (overlaps == 0) {
            reload(mod, !mod.enabled);
            return;
        }
        dialog = new AlertDialog.Builder(activity).setTitle("Enable overlapping mod?")
                .setMessage("This mod replaces " + overlaps + " files also supplied by enabled mods. "
                        + "For each file, the last folder in alphabetical order takes priority.")
                .setPositiveButton("Enable", (d, w) -> reload(mod, true))
                .setNegativeButton("Back", (d, w) -> showMod(snapshot, mod)).show();
    }

    private void showFiles(ModFiles.Snapshot snapshot, ModFiles.Mod mod) {
        AlertDialog.Builder builder = new AlertDialog.Builder(activity).setTitle(mod.name + " · Files")
                .setNegativeButton("Back", (d, w) -> showMod(snapshot, mod));
        if (mod.files.isEmpty()) builder.setMessage("This mod folder is empty.");
        else {
            String[] rows = new String[mod.files.size()];
            for (int i = 0; i < rows.length; ++i) {
                String file = mod.files.get(i);
                List<ModFiles.Mod> owners = snapshot.conflicts.get(ModFiles.key(file));
                rows[i] = file;
                if (mod.enabled && owners != null)
                    rows[i] += "\nConflict · Used from " + owners.get(owners.size() - 1).name;
            }
            builder.setItems(rows, null);
        }
        dialog = builder.show();
    }

    private void showConflicts(ModFiles.Snapshot snapshot) {
        String[] rows = new String[snapshot.conflicts.size()];
        int index = 0;
        for (Map.Entry<String, List<ModFiles.Mod>> entry : snapshot.conflicts.entrySet()) {
            StringBuilder line = new StringBuilder(entry.getKey());
            for (ModFiles.Mod mod : entry.getValue()) line.append("\n").append(mod.name);
            List<ModFiles.Mod> owners = entry.getValue();
            line.append("\nUsed from ").append(owners.get(owners.size() - 1).name);
            rows[index++] = line.toString();
        }
        dialog = new AlertDialog.Builder(activity).setTitle("Conflicting files")
                .setItems(rows, null).setNegativeButton("Back", (d, w) -> showMods(snapshot)).show();
    }
}
