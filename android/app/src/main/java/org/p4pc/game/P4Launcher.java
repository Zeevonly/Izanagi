package org.p4pc.game;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import android.app.AlertDialog;
import java.io.File;
/**
 * Setup screen: the user picks the disc image (and, optionally, the game executable) through the Storage Access Framework document
 * picker. The grants are persisted, so the next launch starts without asking. Nothing is copied; the game reads the picked documents
 * through descriptors (P4Activity). A launch with an `iso` extra (tests) skips this screen and forwards its extras to the game.
 */
public class P4Launcher extends Activity {
    private static final int REQ_ISO = 1;
    private static final int REQ_ELF = 2;
    private static final String PREFS = "p4pc";
    // Aspect ratio picked on this screen. It reaches the game as P4PC_ASPECT, which beats p4pc.ini (and so the in-game Config row);
    // index 0 sets nothing and leaves the game's own setting in charge.
    private static final String[] ASPECT_VALUES = {null, "4:3", "16:9", "auto"};
    private static final String[] ASPECT_LABELS = {"Game setting", "4:3 (original)", "16:9 widescreen", "Auto (fit screen)"};
    // Audio buffer, passed as SDL's own settings (SDL reads them from the environment when the audio device opens). The first entry is the
    // default here: a bigger device buffer and Android's normal, not low-latency, AAudio mode make crackling less likely, at the cost of some
    // delay (about 85 ms for 4096 frames, 170 ms for 8192, at 48 kHz). The last entry sets nothing, which is how the game ran before.
    private static final String[][] AUDIO_ENV = {
        {"SDL_AUDIO_DEVICE_SAMPLE_FRAMES=4096", "SDL_ANDROID_LOW_LATENCY_AUDIO=0"},
        {"SDL_AUDIO_DEVICE_SAMPLE_FRAMES=8192", "SDL_ANDROID_LOW_LATENCY_AUDIO=0"},
        {},
    };
    private static final String[] AUDIO_LABELS = {"Smooth", "Extra smooth", "Low latency (original)"};

    private SharedPreferences prefs;
    private TextView status;
    private Button start;
    private Button aspectButton;
    private Button audioButton;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        final File crash = new File(getFilesDir(), "p4pc-crash.log");
        if (crash.length() > 0 && prefs.getLong("crashShown", 0) != crash.lastModified()) {
            new AlertDialog.Builder(this).setTitle("Previous native crash")
                    .setMessage("The previous run stopped unexpectedly. Crash details are saved at:\n"
                            + crash.getAbsolutePath() + "\n\nInclude this file and boot.log in a bug report.")
                    .setPositiveButton("OK", (dialog, which) ->
                            prefs.edit().putLong("crashShown", crash.lastModified()).apply()).show();
        }

        final Intent in = getIntent();
        if (in.hasExtra("iso")) {
            launch(in.getStringExtra("iso"), in.getStringExtra("elf"), in, false);
            return;
        }

        final LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER);
        col.setPadding(48, 48, 48, 48);
        final TextView title = new TextView(this);
        title.setText("Persona 4");
        title.setTextSize(28);
        col.addView(title);
        status = new TextView(this);
        status.setPadding(0, 32, 0, 32);
        col.addView(status);
        col.addView(button("Choose disc image (.iso)", REQ_ISO));
        col.addView(button("Choose SLUS_217.82 (optional)", REQ_ELF));
        aspectButton = new Button(this);
        aspectButton.setOnClickListener(v -> {
            prefs.edit().putInt("aspect", (aspectIndex() + 1) % ASPECT_VALUES.length).apply();
            refreshAspect();
        });
        col.addView(aspectButton);
        refreshAspect();
        audioButton = new Button(this);
        audioButton.setOnClickListener(v -> {
            prefs.edit().putInt("audio", (audioIndex() + 1) % AUDIO_ENV.length).apply();
            refreshAudio();
        });
        col.addView(audioButton);
        refreshAudio();
        start = new Button(this);
        start.setText("Start");
        start.setOnClickListener(v -> launch(prefs.getString("iso", null), prefs.getString("elf", null), getIntent(), true));
        col.addView(start);
        setContentView(col);
        refresh();
    }

    private int aspectIndex() {
        final int i = prefs.getInt("aspect", 0);
        return i >= 0 && i < ASPECT_VALUES.length ? i : 0;
    }

    private void refreshAspect() {
        aspectButton.setText("Aspect ratio: " + ASPECT_LABELS[aspectIndex()]);
    }

    private int audioIndex() {
        final int i = prefs.getInt("audio", 0);
        return i >= 0 && i < AUDIO_ENV.length ? i : 0;
    }

    private void refreshAudio() {
        audioButton.setText("Audio: " + AUDIO_LABELS[audioIndex()]);
    }

    private Button button(String label, int req) {
        final Button btn = new Button(this);
        btn.setText(label);
        btn.setOnClickListener(v -> {
            final Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            pick.addCategory(Intent.CATEGORY_OPENABLE);
            pick.setType("*/*");
            pick.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            startActivityForResult(pick, req);
        });
        return btn;
    }

    @Override
    protected void onActivityResult(int req, int result, Intent data) {
        super.onActivityResult(req, result, data);
        if (result != RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        final Uri uri = data.getData();
        getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        prefs.edit().putString(req == REQ_ISO ? "iso" : "elf", uri.toString()).apply();
        refresh();
    }

    private boolean readable(String uri) {
        if (uri == null) return false;
        try (ParcelFileDescriptor fd = getContentResolver().openFileDescriptor(Uri.parse(uri), "r")) {
            return fd != null;
        } catch (Exception e) {
            return false;
        }
    }

    private void refresh() {
        final boolean iso = readable(prefs.getString("iso", null));
        final boolean elf = readable(prefs.getString("elf", null));
        status.setText("Disc image: " + (iso ? "ready" : "not chosen") + "\nExecutable: " + (elf ? "ready" : "from the disc image")
                + "\nSaves: " + getFilesDir() + "/saves");
        start.setEnabled(iso);
    }

    private void launch(String iso, String elf, Intent from, boolean fromScreen) {
        final Intent g = new Intent(this, P4Activity.class);
        if (from != null && from.getExtras() != null) {
            g.putExtras(from.getExtras());
        }
        g.putExtra("iso", iso);
        if (elf != null) g.putExtra("elf", elf);
        // P4Activity sets every "env*" extra as NAME=VALUE before the game starts. Test launches (an `iso` extra) are left as they are.
        final String aspect = fromScreen ? ASPECT_VALUES[aspectIndex()] : null;
        if (aspect != null) g.putExtra("env_aspect", "P4PC_ASPECT=" + aspect);
        if (fromScreen) {
            final String[] audio = AUDIO_ENV[audioIndex()];
            for (int i = 0; i < audio.length; i++) g.putExtra("env_audio" + i, audio[i]);
        }
        startActivity(g);
        finish();
    }
    }
            
