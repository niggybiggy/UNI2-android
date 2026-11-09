package io.github.p4grecomp;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.system.Os;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileReader;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Routes to preparation or the main menu after checking the actual files. */
public class Launcher extends Activity {
    static final String DATA = "Persona 4 Golden";

    static final class Option {
        final String key, es, en;
        final String[] values, labelsEs, labelsEn;
        int pick;

        Option(String key, String es, String en, String[] values, String[] labelsEs, String[] labelsEn, int pick) {
            this.key = key;
            this.es = es;
            this.en = en;
            this.values = values;
            this.labelsEs = labelsEs;
            this.labelsEn = labelsEn;
            this.pick = pick;
        }
    }

    final List<Option> options = new ArrayList<>();
    final Map<String, String> config = new LinkedHashMap<>();
    TextView status;
    Button play;
    static final int PICK_ZIP = 1, PICK_FOLDER = 2, PICK_GAME = 3, PICK_KEYS = 4, PREPARE = 6, PICK_CPK = 7,
            PICK_DRIVER = 8, PICK_UPDATE = 9;

    String t(String spanish, String english) { return Lang.t(spanish, english); }

    String label(Option o) { return t(o.labelsEs[o.pick], o.labelsEn[o.pick]); }

    static File dataDir() { return new File(Environment.getExternalStorageDirectory(), DATA); }

    static File installationDir(Context context) {
        File dir = context.getExternalFilesDir(null);
        if (dir == null) throw new IllegalStateException("App storage is unavailable. Try again after storage is mounted.");
        return dir;
    }

    static final class Api30Helper {
        static boolean isExternalStorageManager() {
            return Environment.isExternalStorageManager();
        }
    }

    boolean hasAccess() {
        if (Build.VERSION.SDK_INT < 30)
            return checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    == android.content.pm.PackageManager.PERMISSION_GRANTED;
        return Api30Helper.isExternalStorageManager();
    }

    // ---- source game file (needed only while installing the local game data) ----

    SharedPreferences prefs() { return getSharedPreferences("game", MODE_PRIVATE); }

    String gameUri() { return prefs().getString("uri", null); }

    String gameName() { return prefs().getString("name", ""); }

    String updateUri() { return prefs().getString("update_uri", null); }

    String updateName() { return prefs().getString("update_name", ""); }

    static File keysFile(Context c, String name) { return new File(new File(c.getFilesDir(), "keys"), name); }

    boolean hasKeys() { return keysFile(this, "prod.keys").length() > 0; }

    boolean gameReadable() {
        try (ParcelFileDescriptor f = getContentResolver().openFileDescriptor(Uri.parse(gameUri()), "r")) {
            return f != null;
        } catch (Exception e) {
            return false;
        }
    }

    boolean updateReadable() {
        if (updateUri() == null) return true;
        try (ParcelFileDescriptor f = getContentResolver().openFileDescriptor(Uri.parse(updateUri()), "r")) {
            return f != null;
        } catch (Exception e) {
            return false;
        }
    }

    boolean prepared() throws IOException {
        File installed = installationDir(this);
        InstalledGameFiles.migrate(dataDir(), installed);
        return InstalledGameFiles.ready(installed);
    }

    String displayName(Uri uri) {
        try (Cursor c = getContentResolver().query(uri, new String[] {OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) return c.getString(0);
        } catch (Exception ignored) {
        }
        String last = uri.getLastPathSegment();
        return last == null ? "" : last.substring(last.lastIndexOf('/') + 1);
    }

    void pick(int request, String type, boolean multiple) {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType(type);
        if (multiple) i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        startActivityForResult(i, request);
    }

    void onGamePicked(Uri uri) {
        String name = displayName(uri);
        String lower = name.toLowerCase(Locale.ROOT);
        if (!lower.endsWith(".nsp") && !lower.endsWith(".xci")) {
            tell(t("Archivo no válido", "Not a game file"),
                    t("Elige el archivo .nsp o .xci del juego base (no el de la actualización).",
                            "Choose the base game's .nsp or .xci file (not the update's)."));
            return;
        }
        try {
            getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Exception ignored) {  // a provider without persistable grants: works while the app runs
        }
        prefs().edit().putString("uri", uri.toString()).putString("name", name)
                .remove("p4g_prepared_uri").apply();
    }

    void onUpdatePicked(Uri uri) {
        String name = displayName(uri);
        String lower = name.toLowerCase(Locale.ROOT);
        if (!lower.endsWith(".nsp")) {
            tell(t("Archivo no válido", "Not an update file"),
                    t("Elige la actualización de P4G en formato .nsp.",
                            "Choose the P4G update in .nsp format."));
            return;
        }
        try {
            getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Exception ignored) {
        }
        prefs().edit().putString("update_uri", uri.toString()).putString("update_name", name).apply();
    }

    // prod.keys (and title.keys, when the dump needs it): copied into the app's own storage, never shared or logged.
    void onKeysPicked(Intent data) {
        List<Uri> uris = new ArrayList<>();
        ClipData clip = data.getClipData();
        if (clip != null)
            for (int k = 0; k < clip.getItemCount(); k++) uris.add(clip.getItemAt(k).getUri());
        else if (data.getData() != null) uris.add(data.getData());
        boolean prod = false;
        for (Uri uri : uris) {
            String name = displayName(uri).toLowerCase(Locale.ROOT);
            String target = name.contains("title") ? "title.keys" : "prod.keys";
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                byte[] buf = new byte[1 << 20];
                int n = 0, r;
                while (in != null && n < buf.length && (r = in.read(buf, n, buf.length - n)) > 0) n += r;
                String text = new String(buf, 0, n, "UTF-8");
                if (target.equals("prod.keys") && !text.contains("header_key") && !text.contains("master_key_00")) continue;
                File f = keysFile(this, target);
                f.getParentFile().mkdirs();
                try (OutputStream out = new FileOutputStream(f)) {
                    out.write(buf, 0, n);
                }
                prod |= target.equals("prod.keys");
            } catch (Exception ignored) {
            }
        }
        if (!prod && !hasKeys())
            tell(t("Claves no válidas", "Not a keys file"),
                    t("Ese archivo no tiene las claves (header_key). Elige el prod.keys de tu consola.",
                            "That file has no keys (header_key). Choose your console's prod.keys."));
    }

    void prepare() {
        Intent i = new Intent(this, ImportActivity.class);
        i.putExtra(ImportActivity.EXTRA_URI, gameUri());
        i.putExtra(ImportActivity.EXTRA_NAME, gameName());
        i.putExtra(ImportActivity.EXTRA_UPDATE_URI, updateUri());
        i.putExtra(ImportActivity.EXTRA_UPDATE_NAME, updateName());
        startActivityForResult(i, PREPARE);
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        P4gTheme.apply(this);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        options.add(new Option("RESOLUTION", "Resolución de salida", "Output resolution",
                new String[] {"360p", "480p", "720p", "1080p"},
                new String[] {"360p", "480p", "720p", "1080p"},
                new String[] {"360p", "480p", "720p", "1080p"}, 2));
        options.add(new Option("NX_STRETCH", "Formato de pantalla", "Screen format",
                new String[] {"0", "16:10", "1"},
                new String[] {"16:9", "16:10", "Estirar"},
                new String[] {"16:9", "16:10", "Stretch"}, 0));
        options.add(new Option("NX_FPS_CAP", "Límite de FPS", "FPS limit", new String[] {"30", "45", "60"},
                new String[] {"30 FPS", "45 FPS", "60 FPS"},
                new String[] {"30 FPS", "45 FPS", "60 FPS"}, 0));
        options.add(new Option("NX_SHOW_FPS", "Contador de FPS", "FPS counter", new String[] {"0", "1"},
                new String[] {"Desactivado", "Activado"}, new String[] {"Off", "On"}, 0));
        for (String metric : new String[] {"CPU", "GPU", "RAM"}) {
            options.add(new Option("NX_OVERLAY_" + metric, metric, metric, new String[] {"0", "1"},
                    new String[] {"Desactivado", "Activado"}, new String[] {"Off", "On"}, 0));
        }
        options.add(new Option("NX_TRACE_PERF", "Estadísticas detalladas de rendimiento", "Detailed performance logging",
                new String[] {"0", "1"}, new String[] {"Desactivado", "Activado"},
                new String[] {"Off", "On"}, 0));
        options.add(new Option("NX_PIPE_PREWARM", "Precalentamiento de pipelines (experimental)",
                "Pipeline prewarming (experimental)", new String[] {"1", "2", "0"},
                new String[] {"En segundo plano (recomendado)", "Agresivo (carga más larga)", "Desactivado"},
                new String[] {"Background (recommended)", "Aggressive (longer loading)", "Off"}, 0));
        options.add(new Option("NX_AFFINITY", "Afinidad de CPU (experimental)", "CPU affinity (experimental)",
                new String[] {"0", "2", "1"},
                new String[] {"Sistema Android", "Optimizada (recomendada)", "Separar hilos de cuadro"},
                new String[] {"Android scheduler", "Optimized (recommended)", "Split frame threads"}, 1));
        options.add(new Option("NX_P4G_MOTION_BLUR", "Desactivar desenfoque de movimiento (experimental)",
                "Disable motion blur (experimental)", new String[] {"1", "0"},
                new String[] {"Desactivado", "Activado"}, new String[] {"Off", "On"}, 0));
        options.add(new Option("NX_LOD_BIAS", "Nitidez de texturas 3D (experimental)",
                "3D texture sharpness (experimental)", new String[] {"0", "-0.5", "-1"},
                new String[] {"Original", "Más nítidas", "Más nítidas (-1, prueba)"},
                new String[] {"Original", "Sharper", "Sharper (-1, test)"}, 0));
        options.add(new Option("NX_TOUCH_SHOW", "Controles táctiles", "On-screen controls", new String[] {"1", "0"},
                new String[] {"Activados", "Desactivados"}, new String[] {"On", "Off"}, 0));
        options.add(new Option("NX_TOUCH_LEFT_STICK_MODE", "Modo del analógico izquierdo", "Left analog mode",
                new String[] {"fixed", "floating"}, new String[] {"Fijo", "Flotante"},
                new String[] {"Fixed", "Floating"}, 0));
        options.add(new Option("NX_TOUCH_SIZE", "Tamaño de los botones", "Button size",
                new String[] {"80", "100", "125"}, new String[] {"Pequeño", "Normal", "Grande"},
                new String[] {"Small", "Normal", "Large"}, 1));
        options.add(new Option("NX_TOUCH_OPACITY", "Opacidad de los botones", "Button opacity",
                new String[] {"50", "85", "100"}, new String[] {"Baja", "Normal", "Alta"},
                new String[] {"Low", "Normal", "High"}, 1));
        options.add(new Option("NX_STICK_SENSITIVITY", "Sensibilidad palanca táctil", "Touch stick sensitivity",
                new String[] {"50", "75", "100", "125", "150", "200"},
                new String[] {"50%", "75%", "100%", "125%", "150%", "200%"},
                new String[] {"50%", "75%", "100%", "125%", "150%", "200%"}, 2));
        options.add(new Option("NX_CAMERA_SENSITIVITY", "Sensibilidad cámara táctil", "Touch camera sensitivity",
                new String[] {"50", "75", "100", "125", "150", "200", "250"},
                new String[] {"50%", "75%", "100%", "125%", "150%", "200%", "250%"},
                new String[] {"50%", "75%", "100%", "125%", "150%", "200%", "250%"}, 2));
        build(false);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
        showGameError(getIntent());
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
    }

    void showGameError(Intent intent) {
        String error = intent.getStringExtra("game_error");
        if (error != null) {
            intent.removeExtra("game_error");
            tell("Could not open the game", error);
        }
    }

    boolean readyForPlay;
    int refreshGeneration;

    @Override protected void onDestroy() {
        if (modManager != null) modManager.dismiss();
        if (driverSelector != null) driverSelector.dismiss();
        super.onDestroy();
    }

    private LinearLayout settingsGroup(LinearLayout root, String key, String title, String subtitle) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        android.graphics.drawable.GradientDrawable background = new android.graphics.drawable.GradientDrawable();
        background.setColor(P4gTheme.PANEL);
        background.setCornerRadius(dp(12));
        card.setBackground(background);
        LinearLayout.LayoutParams spacing = new LinearLayout.LayoutParams(-1, -2);
        spacing.bottomMargin = dp(10);
        root.addView(card, spacing);
        Button header = new Button(this);
        P4gTheme.button(header, false);
        header.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        header.setTextSize(16);
        card.addView(header, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(12), dp(8), dp(12), dp(12));
        TextView hint = new TextView(this);
        hint.setText(subtitle);
        hint.setTextColor(P4gTheme.MUTED);
        hint.setTextSize(13);
        hint.setPadding(0, 0, 0, dp(8));
        body.addView(hint);
        card.addView(body);
        // A fresh visit to the launcher always starts compact. Keeping this state in
        // SharedPreferences made every drawer come back open after the user expanded it once.
        body.setVisibility(View.GONE);
        header.setText("+  " + title);
        header.setContentDescription(title + ", expand");
        header.setOnClickListener(v -> {
            boolean open = body.getVisibility() != View.VISIBLE;
            body.setVisibility(open ? View.VISIBLE : View.GONE);
            header.setText((open ? "−  " : "+  ") + title);
            header.setContentDescription(title + (open ? ", collapse" : ", expand"));
        });
        return body;
    }

    void build(boolean ready) {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(P4gTheme.BACKGROUND);
        int pad = dp(16);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("PERSONA 4 GOLDEN");
        title.setTextColor(P4gTheme.INK);
        title.setBackgroundColor(P4gTheme.YELLOW);
        title.setPadding(dp(12), dp(8), dp(12), dp(8));
        title.setTextSize(24);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);
        TextView version = new TextView(this);
        version.setText("BETA  ·  0.4.5");
        version.setTextColor(P4gTheme.YELLOW);
        version.setTextSize(13);
        version.setTypeface(Typeface.DEFAULT_BOLD);
        version.setPadding(0, dp(10), 0, 0);
        root.addView(version);

        status = new TextView(this);
        status.setTextColor(P4gTheme.MUTED);
        status.setTextSize(14);
        status.setPadding(0, dp(6), 0, dp(14));
        root.addView(status);

        if (!ready) {
        P4gTheme.section(root, t("Preparación", "Game setup"));
        addRow(root, t("Archivo del juego (.nsp / .xci)", "Game file (.nsp / .xci)"), t("Elegir", "Choose"),
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        pick(PICK_GAME, "*/*", false);
                    }
                });
        addRow(root, t("Actualización de P4G .nsp (opcional)", "P4G update .nsp (optional)"),
                updateUri() == null ? t("Elegir", "Choose") : updateName(), v -> pick(PICK_UPDATE, "*/*", false));
        addRow(root, t("Claves (prod.keys)", "Keys (prod.keys)"), t("Elegir", "Choose"), new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pick(PICK_KEYS, "*/*", true);
            }
        });


        play = new Button(this);
        P4gTheme.button(play, true);
        play.setAllCaps(false);
        play.setText(t("Preparar", "Prepare"));
        play.setTextSize(20);
        root.addView(play, new LinearLayout.LayoutParams(-1, dp(64)));
        play.setOnClickListener(v -> {
            if (!hasAccess()) askAccess();
            else if (gameUri() == null || !gameReadable()) pick(PICK_GAME, "*/*", false);
            else if (!hasKeys()) pick(PICK_KEYS, "*/*", true);
            else { prefs().edit().remove("p4g_prepared_uri").apply(); prepare(); }
        });
        ScrollView setup = new ScrollView(this);
        setup.addView(root);
        setContentView(setup);
        return;
        }

        LinearLayout graphics = settingsGroup(root, "graphics", t("Imagen y rendimiento", "Graphics & performance"),
                t("Resolución, FPS y driver", "Resolution, FPS and GPU driver"));
        LinearLayout controls = settingsGroup(root, "controls", t("Controles", "Controls"),
                t("Visibilidad y posición de botones", "Button visibility and layout"));
        LinearLayout gameUpdate = settingsGroup(root, "game_update", t("Actualización del juego", "Game update"),
                t("Aplicar un update oficial .nsp", "Apply an official update .nsp"));
        TextView updateHelp = new TextView(this);
        updateHelp.setText(t(
                "Para aplicar el update, selecciona otra vez el NSP/XCI original y el update NSP. El update de Switch depende de los bloques del juego base y no puede usar solamente los archivos ya extraídos. Después de preparar, ninguno de los NSP será necesario para jugar.",
                "To apply an update, select the original NSP/XCI again and the update NSP. A Switch update depends on blocks from the base game and cannot use only the already extracted files. Neither NSP is needed to play after preparation finishes."));
        updateHelp.setTextColor(Color.LTGRAY);
        updateHelp.setTextSize(14);
        updateHelp.setPadding(0, dp(4), 0, dp(8));
        gameUpdate.addView(updateHelp);
        addRow(gameUpdate, t("Juego base original (NSP/XCI)", "Original base game (NSP/XCI)"),
                gameUri() == null ? t("Elegir", "Choose") : gameName(), v -> pick(PICK_GAME, "*/*", false));
        addRow(gameUpdate, t("Archivo del update (.nsp)", "Update file (.nsp)"),
                updateUri() == null ? t("Elegir", "Choose") : updateName(), v -> pick(PICK_UPDATE, "*/*", false));
        addRow(gameUpdate, t("Aplicar update", "Apply update"), t("Preparar", "Prepare"), v -> {
            if (!hasAccess()) askAccess();
            else if (gameUri() == null || !gameReadable()) pick(PICK_GAME, "*/*", false);
            else if (updateUri() == null || !updateReadable()) pick(PICK_UPDATE, "*/*", false);
            else if (!hasKeys()) pick(PICK_KEYS, "*/*", true);
            else prepare();
        });
        LinearLayout files = settingsGroup(root, "files", t("Partidas y mods", "Saves & mods"),
                t("Copias de seguridad y traducciones", "Backups, translations and mod manager"));
        LinearLayout diagnostics = settingsGroup(root, "diagnostics", t("Diagnóstico", "Diagnostics"),
                t("Exportar registros para reportar errores", "Export logs for bug reports"));
        TextView diagnosticHelp = new TextView(this);
        diagnosticHelp.setText(t(
                "Activa las estadísticas para registrar tiempos de renderizado, esperas y compilación de pipelines. Puede añadir sobrecarga. Los cambios se aplican al iniciar el juego de nuevo. Juega el mismo tramo y exporta los registros; desactiva las estadísticas al terminar. La afinidad no garantiza más FPS.",
                "Enable detailed logging to record rendering times, waits and pipeline compilation. Logging can add overhead. Changes apply when you start the game again. Play the same section and export logs; turn logging off after testing. CPU affinity does not guarantee higher FPS."));
        diagnosticHelp.setTextColor(Color.LTGRAY);
        diagnosticHelp.setTextSize(14);
        diagnosticHelp.setPadding(0, dp(4), 0, dp(8));
        diagnostics.addView(diagnosticHelp);
        LinearLayout overlay = settingsGroup(root, "overlay", "Overlay",
                t("Durante el juego. CPU: 100% = un núcleo; RAM: memoria residente del juego. GPU no disponible en todos los teléfonos.",
                  "In-game stats. CPU: 100% = one core; RAM: game resident memory. GPU usage is unavailable on some phones."));
        LinearLayout community = settingsGroup(root, "community", t("Comunidad", "Community"),
                t("Proyecto, soporte y reportes", "Project, support and bug reports"));
        addRow(community, "GitHub", t("Abrir", "Open"),
                v -> openExternal("https://github.com/nxdevxz/p4g-android"));
        addRow(community, "Discord", t("Unirse", "Join"),
                v -> openExternal("https://discord.gg/yF6FtNK6rM"));
        addRow(community, t("Reportar un problema", "Report an issue"), t("Abrir", "Open"),
                v -> openExternal("https://github.com/nxdevxz/p4g-android/issues/new/choose"));
        boolean controlOptions = false;
        for (final Option o : options) {
            if ("NX_TOUCH_SHOW".equals(o.key)) controlOptions = true;
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(4), 0, dp(4));
            TextView name = new TextView(this);
            name.setText(t(o.es, o.en));
            name.setTextColor(Color.WHITE);
            name.setTextSize(17);
            row.addView(name, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
            final Button value = new Button(this);
            P4gTheme.button(value, false);
            value.setAllCaps(false);
            value.setMinWidth(dp(120));
            value.setMaxWidth(dp(170));
            row.addView(value);
            value.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    o.pick = (o.pick + 1) % o.values.length;
                    value.setText(label(o));
                    save();
                }
            });
            value.setTag(o);
            (o.key.equals("NX_TRACE_PERF") ? diagnostics :
                    o.key.equals("NX_SHOW_FPS") || o.key.startsWith("NX_OVERLAY_") ? overlay :
                    controlOptions ? controls : graphics).addView(row);
        }
        addDriverRow(graphics);
        addLogsRow(diagnostics);

        LinearLayout edit = new LinearLayout(this);
        edit.setOrientation(LinearLayout.HORIZONTAL);
        edit.setGravity(Gravity.CENTER_VERTICAL);
        edit.setPadding(0, dp(4), 0, dp(4));
        TextView editName = new TextView(this);
        editName.setText(t("Posición de los controles", "Controls position"));
        editName.setTextColor(Color.WHITE);
        editName.setTextSize(17);
        edit.addView(editName, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        Button editButton = new Button(this);
        P4gTheme.button(editButton, false);
        editButton.setAllCaps(false);
        editButton.setMinWidth(dp(120));
        editButton.setText(t("Editar", "Edit layout"));
        edit.addView(editButton);
        editButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!hasAccess()) {
                    askAccess();
                    return;
                }
                save();
                startActivity(new Intent(Launcher.this, ControlsEditor.class));
            }
        });
        controls.addView(edit);

        // Saves: in from a ZIP or a folder (a Switch backup or another player's), out to a ZIP in Download.
        LinearLayout saves = new LinearLayout(this);
        saves.setOrientation(LinearLayout.HORIZONTAL);
        saves.setGravity(Gravity.CENTER_VERTICAL);
        saves.setPadding(0, dp(4), 0, dp(4));
        TextView savesName = new TextView(this);
        savesName.setText(t("Partidas guardadas", "Saves"));
        savesName.setTextColor(Color.WHITE);
        savesName.setTextSize(17);
        saves.addView(savesName, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        Button importButton = new Button(this);
        P4gTheme.button(importButton, false);
        importButton.setAllCaps(false);
        importButton.setMinWidth(dp(88));
        importButton.setText(t("Importar", "Import"));
        saves.addView(importButton);
        Button exportButton = new Button(this);
        P4gTheme.button(exportButton, false);
        exportButton.setAllCaps(false);
        exportButton.setMinWidth(dp(88));
        exportButton.setText(t("Exportar", "Export"));
        saves.addView(exportButton);
        importButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!hasAccess()) {
                    askAccess();
                    return;
                }
                askImport();
            }
        });
        exportButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!hasAccess()) {
                    askAccess();
                    return;
                }
                exportSaves();
            }
        });
        files.addView(saves);

        // Translation CPK import
        LinearLayout transRow = new LinearLayout(this);
        transRow.setOrientation(LinearLayout.HORIZONTAL);
        transRow.setGravity(Gravity.CENTER_VERTICAL);
        transRow.setPadding(0, dp(4), 0, dp(4));
        TextView transName = new TextView(this);
        transName.setText(t("Traducción / Paquete (.cpk)", "Translation / CPK mod"));
        transName.setTextColor(Color.WHITE);
        transName.setTextSize(17);
        transRow.addView(transName, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        Button transButton = new Button(this);
        P4gTheme.button(transButton, false);
        transButton.setAllCaps(false);
        transButton.setMinWidth(dp(120));
        transButton.setText(t("Importar CPK", "Import CPK"));
        transRow.addView(transButton);
        transButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!hasAccess()) {
                    askAccess();
                    return;
                }
                pick(PICK_CPK, "*/*", false);
            }
        });
        files.addView(transRow);
        addModsRow(files);

        play = new Button(this);
        play.setTextSize(20);
        play.setAllCaps(false);
        P4gTheme.button(play, true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(64));
        lp.topMargin = dp(4);
        lp.bottomMargin = dp(16);
        // Keep Play after every settings drawer so it remains the final launcher action.
        root.addView(play, lp);
        play.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { installShadersAndPlay(); }
        });

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        setContentView(scroll);
    }

    boolean installingShaders;

    void installShadersAndPlay() {
        if (!hasAccess()) { askAccess(); return; }
        // Cache readiness was checked off the UI thread by refresh(). Missing stages
        // are compiled by the renderer on first use, never installed from APK assets.
        launchAfterShaderInstall();
    }

    void launchAfterShaderInstall() {
        if (importingSaves || importingCpk || importingDriver || (modManager != null && modManager.busy())
                || (driverSelector != null && driverSelector.busy())) return;
        if (!readyForPlay) { refresh(); return; }
        try { SaveFiles.recover(); }
        catch (IOException e) { tell("Save recovery failed", String.valueOf(e.getMessage())); return; }
        save();
        Intent i = new Intent(this, GameActivity.class);
        i.putExtra(ImportActivity.EXTRA_NAME, gameName());
        startActivity(i);
    }

    void refresh() {
        if (installingShaders || importingSaves || isFinishing() || isDestroyed()) return;
        final int generation = ++refreshGeneration;
        play.setEnabled(false);
        status.setText("Checking preparation...");
        new Thread(() -> {
            final boolean access = hasAccess();
            if (access && new File(dataDir(), "game/installed.tsv").isFile()) runOnUiThread(() -> {
                if (generation == refreshGeneration && !isFinishing() && !isDestroyed()) {
                    getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                    status.setText("Checking or moving installed game data...");
                }
            });
            boolean installedReady = false;
            String storageProblem = "";
            try { installedReady = access && prepared(); }
            catch (Exception e) {
                storageProblem = String.valueOf(e.getMessage());
                Log.w("P4G-Launch", "Could not prepare or move installed data", e);
            }
            final boolean ready = installedReady;
            final String storageError = storageProblem;
            final boolean readable = access && !ready && gameUri() != null && gameReadable();
            final boolean keys = hasKeys();
            String preparationError;
            try { preparationError = access ? PreparationFiles.failure(InstalledGameFiles.shaders(installationDir(this))) : ""; }
            catch (Exception e) { preparationError = String.valueOf(e.getMessage()); }
            final String shaderError = preparationError;
            runOnUiThread(() -> {
                if (generation != refreshGeneration || isFinishing() || isDestroyed()) return;
                getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                readyForPlay = ready;
                load();
                for (Option o : options) {
                    String value = config.get(o.key);
                    for (int k = 0; k < o.values.length; ++k)
                        if (o.values[k].equals(value)) o.pick = k;
                }
                build(ready);
                updateButtons(getWindow().getDecorView());
                play.setEnabled(true);
                if (ready) {
                    String revision;
                    try { revision = InstalledGameFiles.revision(InstalledGameFiles.game(installationDir(this))); }
                    catch (Exception e) { revision = "unknown"; }
                    status.setText("Game " + revision + " installed — NSP/XCI not required.\nNew effects may briefly pause while their shaders are prepared.");
                    play.setText(t("Jugar", "Play"));
                } else {
                    String game = gameUri() == null ? "No game selected" : gameName();
                    status.setText(game + "\nprod.keys: " + (keys ? "selected" : "not selected") +
                        (!access ? "\nAllow file access to prepare the game." :
                         !readable && gameUri() != null ? "\nSelect the game file again." :
                         readable && keys ? "\nInstall the game data to continue. Keep the NSP/XCI until installation finishes." : "") +
                        (storageError.isEmpty() ? "" : "\n" + storageError) +
                        (shaderError.isEmpty() ? "" : "\nShader generation failed:\n" + shaderError));
                    play.setText(t("Preparar", "Prepare"));
                }
            });
        }, "P4G-setup-check").start();
    }

    void updateButtons(View v) {
        if (v instanceof Button && v.getTag() instanceof Option) {
            Option o = (Option) v.getTag();
            ((Button) v).setText(label((Option) v.getTag()));
        } else if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int k = 0; k < g.getChildCount(); k++) updateButtons(g.getChildAt(k));
        }
    }

    void tell(String title, String message) {
        new AlertDialog.Builder(this).setTitle(title).setMessage(message).setPositiveButton("OK", null).show();
    }

    void openExternal(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception e) {
            tell(t("No se pudo abrir el enlace", "Could not open link"), url);
        }
    }

    void askImport() {
        new AlertDialog.Builder(this)
                .setTitle(t("Importar partidas", "Import saves"))
                .setMessage(t("Elige un archivo ZIP o una carpeta con partidas de Persona 4 Golden (por ejemplo, un ZIP exportado anteriormente). "
                                + "Antes se guarda una copia de tus partidas actuales.",
                        "Pick a ZIP file or a folder with Persona 4 Golden saves (for example, a ZIP exported earlier). Your current saves "
                                + "are copied aside first."))
                .setPositiveButton(t("Archivo ZIP", "ZIP file"), new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                        i.addCategory(Intent.CATEGORY_OPENABLE);
                        i.setType("*/*");
                        i.putExtra(Intent.EXTRA_MIME_TYPES, new String[] {"application/zip",
                                "application/x-zip-compressed", "application/octet-stream"});
                        startActivityForResult(i, PICK_ZIP);
                    }
                })
                .setNeutralButton(t("Carpeta", "Folder"), new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), PICK_FOLDER);
                    }
                })
                .setNegativeButton(t("Cancelar", "Cancel"), null)
                .show();
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == PREPARE) {
            if (result == RESULT_OK && gameUri() != null) {
                prefs().edit().putString("p4g_prepared_uri", gameUri()).apply();
                Log.i("P4G-Launch", "Local game installation complete");
            }
            refresh();
            return;
        }
        if (request == PICK_DRIVER) {
            if (result == RESULT_OK && data != null && data.getData() != null) importDriver(data.getData());
            else showDrivers();
            return;
        }
        if (result != RESULT_OK || data == null) return;
        if (request == PICK_KEYS) {
            onKeysPicked(data);
            refresh();
            return;
        }
        if (data.getData() == null) return;
        if (request == PICK_GAME) {
            onGamePicked(data.getData());
            refresh();
            return;
        }
        if (request == PICK_UPDATE) {
            onUpdatePicked(data.getData());
            refresh();
            return;
        }
        if (request == PICK_CPK) {
            importCpk(data.getData());
            return;
        }
        if (request == PICK_ZIP || request == PICK_FOLDER) importSaves(request, data.getData());
    }

    boolean importingSaves;
    void importSaves(int request, Uri uri) {
        if (importingSaves) return;
        importingSaves = true;
        play.setEnabled(false);
        status.setText("Importing saves...");
        new Thread(() -> {
            SaveFiles.Result imported = null;
            String failure = null;
            try {
                imported = request == PICK_ZIP ? SaveFiles.importZip(getContentResolver(), uri)
                    : SaveFiles.importTree(getContentResolver(), uri);
            } catch (Exception e) { failure = String.valueOf(e.getMessage()); }
            final SaveFiles.Result result = imported;
            final String error = failure;
            runOnUiThread(() -> {
                importingSaves = false;
                if (isFinishing() || isDestroyed()) return;
                refresh();
                if (error != null) tell("Import failed", error);
                else if (result == null) tell("No saves found",
                    "Choose the folder or ZIP containing the P4G data0001.bin and data0001.binslot files. Include system.bin and system.binslot if they are in your backup.");
                else tell("Saves imported", result.files + " files imported. Select Load Game in P4G." +
                    (result.backup == null ? "" : "\n\nPrevious saves backed up to: user/" + result.backup));
            });
        }, "P4G-save-import").start();
    }

    private boolean importingDriver;
    private DriverSelector driverSelector;

    private void addDriverRow(LinearLayout root) {
        addRow(root, t("Driver gráfico", "GPU driver"), "Select", v -> showDrivers());
    }

    private void showDrivers() {
        if (importingDriver) return;
        if (driverSelector == null) driverSelector = new DriverSelector(this);
        driverSelector.show();
    }

    private void importDriver(Uri uri) {
        if (importingDriver) return;
        importingDriver = true;
        String name = displayName(uri);
        Context app = getApplicationContext();
        Toast.makeText(this, t("Importando driver…", "Importing driver…"), Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            try {
                CustomDrivers.install(app, uri, name);
                runOnUiThread(() -> finishDriverImport(null));
            } catch (Exception e) {
                String message = e.getMessage() == null ? e.toString() : e.getMessage();
                runOnUiThread(() -> finishDriverImport(message));
            }
        }, "P4G-driver-import").start();
    }

    private void finishDriverImport(String error) {
        importingDriver = false;
        if (isFinishing() || isDestroyed()) return;
        if (error != null) {
            new AlertDialog.Builder(this).setTitle("Driver import failed").setMessage(error)
                    .setPositiveButton("Back", (d, w) -> showDrivers()).show();
        } else {
            Toast.makeText(this, "Driver imported and selected.", Toast.LENGTH_SHORT).show();
            showDrivers();
        }
    }

    private boolean exportingLogs;

    private void addLogsRow(LinearLayout root) {
        addRow(root, "Logs", t("Exportar", "Export"), v -> exportLogs());
    }

    private void exportLogs() {
        if (exportingLogs) return;
        exportingLogs = true;
        Context app = getApplicationContext();
        Toast.makeText(this, t("Exportando logs…", "Exporting logs…"), Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            DiagnosticLogs.Exported exported = null;
            String error = null;
            try {
                exported = DiagnosticLogs.export(app);
            } catch (Exception e) {
                error = String.valueOf(e.getMessage());
            }
            final DiagnosticLogs.Exported file = exported;
            final String failure = error;
            runOnUiThread(() -> {
                exportingLogs = false;
                if (isFinishing() || isDestroyed()) return;
                if (failure != null) tell(t("No se pudieron exportar los logs", "Could not export logs"), failure);
                else new AlertDialog.Builder(this)
                        .setTitle(t("Logs exportados", "Logs exported"))
                        .setMessage(Lang.f("Guardados en Descargas/%s.", "Saved to Downloads/%s.", file.name))
                        .setPositiveButton(t("Compartir", "Share"), (dialog, which) -> shareLogs(file))
                        .setNegativeButton(t("Cerrar", "Close"), null)
                        .show();
            });
        }, "P4G-log-export").start();
    }

    private void shareLogs(DiagnosticLogs.Exported file) {
        Intent share = new Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_STREAM, file.uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        share.setClipData(ClipData.newRawUri(file.name, file.uri));
        startActivity(Intent.createChooser(share, t("Compartir logs", "Share logs")));
    }

    void exportSaves() {
        try {
            java.io.File out = SaveFiles.export();
            // Listed at once by the file pickers and the Files app (written straight to disk, it would wait for a scan).
            if (out != null) android.media.MediaScannerConnection.scanFile(this, new String[] {out.getPath()}, null, null);
            if (out == null) tell(t("No hay partidas", "No saves"), t("Todavía no hay partidas guardadas.", "There are no saves yet."));
            else tell(t("Partidas exportadas", "Saves exported"),
                    Lang.f("Guardadas en Descargas/%s. Se importan tal cual con el botón Importar.",
                            "Saved to Download/%s. The Import button takes it back as is.", out.getName()));
        } catch (Exception e) {
            tell(t("No se pudo exportar", "Export failed"), String.valueOf(e.getMessage()));
        }
    }

    @Override
    public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        if (request == 100) refresh();
    }

    void askAccess() {
        if (Build.VERSION.SDK_INT < 30) {
            requestPermissions(new String[] {android.Manifest.permission.READ_EXTERNAL_STORAGE,
                    android.Manifest.permission.WRITE_EXTERNAL_STORAGE}, 100);
            return;
        }
        try {
            startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:" + getPackageName())));
        } catch (Exception e) {
            startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
        }
    }

    // config.txt: KEY=value lines, in order.
    static Map<String, String> readConfig(File f) {
        Map<String, String> out = new LinkedHashMap<>();
        if (!f.canRead()) return out;
        try (BufferedReader r = new BufferedReader(new FileReader(f))) {
            for (String line; (line = r.readLine()) != null; ) {
                line = line.trim();
                int eq = line.indexOf('=');
                if (eq > 0) out.put(line.substring(0, eq), line.substring(eq + 1));
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    static boolean writeConfig(File f, Map<String, String> config) {
        try (FileWriter w = new FileWriter(f)) {
            for (Map.Entry<String, String> e : config.entrySet()) w.write(e.getKey() + "=" + e.getValue() + "\n");
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    void load() {
        config.clear();
        config.putAll(readConfig(new File(dataDir(), "config.txt")));
    }

    void save() {
        if (!hasAccess()) return;
        dataDir().mkdirs();
        if (!dataDir().isDirectory()) return;
        load();
        for (Option o : options) config.put(o.key, o.values[o.pick]);
        final String res = config.get("RESOLUTION");
        if ("360p".equals(res)) {
            config.put("NX_HANDHELD", "1");
            config.put("resolution", "640x360");
            config.put("NX_RENDER_RES", "640x360");
            config.put("NX_RES", "360p");
        } else if ("480p".equals(res)) {
            config.put("NX_HANDHELD", "1");
            config.put("resolution", "854x480");
            config.put("NX_RENDER_RES", "854x480");
            config.put("NX_RES", "480p");
        } else if ("1080p".equals(res)) {
            config.put("NX_HANDHELD", "0");
            config.put("resolution", "1920x1080");
            config.put("NX_RENDER_RES", "1920x1080");
            config.put("NX_RES", "1080p");
        } else {
            config.put("NX_HANDHELD", "1");
            config.put("resolution", "1280x720");
            config.put("NX_RENDER_RES", "1280x720");
            config.put("NX_RES", "720p");
        }
        for (String k : new String[] {"RENDER_SCALE", "NX_ANISO_MAX"}) config.remove(k);
        for (String key : new String[] {"language", "GRAPHICS", "NX_GAMEPAD_LAYOUT"}) config.remove(key);
        writeConfig(new File(dataDir(), "config.txt"), config);
    }

    int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private boolean importingCpk;
    private ModManager modManager;

    private void addModsRow(LinearLayout root) {
        addRow(root, "Mods and translations", "Manage", v -> {
            if (!hasAccess()) { askAccess(); return; }
            if (importingCpk) return;
            if (modManager == null) modManager = new ModManager(this);
            modManager.show();
        });
    }

    void importCpk(Uri uri) {
        if (importingCpk) return;
        String name = displayName(uri);
        if (name == null || name.length() <= 4 || !name.toLowerCase(Locale.ROOT).endsWith(".cpk")
                || name.indexOf('/') >= 0 || name.indexOf('\\') >= 0 || name.indexOf('\0') >= 0) {
            tell(t("Error al importar CPK", "Could not import CPK"),
                    t("Selecciona un archivo .cpk con su nombre original.",
                            "Select a .cpk file with its original filename."));
            return;
        }
        installCpk(uri, name);
    }

    private void installCpk(Uri uri, String target) {
        if (importingCpk) return;
        importingCpk = true;
        Toast.makeText(this, t("Importando CPK…", "Importing CPK…"), Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            String error = null;
            String installed = null;
            try {
                installed = copyCpk(uri, target);
            } catch (Exception e) {
                error = String.valueOf(e.getMessage());
            }
            final String failure = error;
            final String installedPath = installed;
            runOnUiThread(() -> {
                importingCpk = false;
                if (isFinishing() || isDestroyed()) return;
                if (failure == null) {
                    tell(t("CPK instalado", "CPK installed"), installedPath
                            + (installedPath.startsWith("mods/_translation/")
                                ? "\nEnable translation in Mods and translations to apply it."
                                : t("\nSe aplica al volver a abrir el juego.", "\nApplied the next time you open the game.")));
                } else {
                    tell(t("Error al importar CPK", "Could not import CPK"), failure);
                }
            });
        }, "P4G-cpk-import").start();
    }

    private String copyCpk(Uri uri, String target) throws IOException {
        synchronized (ModFiles.class) {
            // Preserve the original RomFS filename, including language archives such as data_e.cpk.
            File romfs = ModFiles.translationRomfs(dataDir());
            if (!romfs.isDirectory() && !romfs.mkdirs()) throw new IOException("Could not create the mod folder.");
            File staged = File.createTempFile(".import-", ".tmp", romfs);
            try {
                InputStream stream = getContentResolver().openInputStream(uri);
                if (stream == null) throw new IOException("Could not open the selected file.");
                try (DataInputStream in = new DataInputStream(stream);
                     FileOutputStream out = new FileOutputStream(staged)) {
                    int magic = in.readInt();
                    if (magic != 0x43504B20) throw new IOException("The selected file is not a CRI CPK archive.");
                    out.write(new byte[] {'C', 'P', 'K', ' '});
                    SaveFiles.copy(in, out);
                    out.getFD().sync();
                }
                Files.move(staged.toPath(), new File(romfs, target).toPath(),
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                return dataDir().toPath().relativize(new File(romfs, target).toPath()).toString().replace(File.separatorChar, '/');
            } finally {
                if (staged.exists() && !staged.delete()) Log.w("P4G-Launch", "Could not remove staged CPK");
            }
        }
    }

    void addRow(LinearLayout root, String label, String button, View.OnClickListener click) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(4), 0, dp(4));
        TextView name = new TextView(this);
        name.setText(label);
        name.setTextColor(Color.WHITE);
        name.setTextSize(17);
        row.addView(name, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        Button b = new Button(this);
        P4gTheme.button(b, false);
        b.setAllCaps(false);
        b.setMinWidth(dp(120));
        b.setMaxWidth(dp(170));
        b.setText(button);
        b.setOnClickListener(click);
        row.addView(b);
        root.addView(row);
    }
}
