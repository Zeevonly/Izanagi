package org.p4pc.game;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.Binder;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.hardware.input.InputManager;

import org.libsdl.app.SDLActivity;

import java.io.File;

/**
 * The game: SDL's activity with the port's environment set before libmain.so starts and the touch pad on top of the surface.
 *
 * Launch extras (P4Launcher forwards them; tests pass them with `am start`):
 *   iso, elf   a content:// or file:// URI, or an absolute path. A URI is opened here and handed over as the descriptor "fd:N"
 *              (src/platform/os p4_os_fopen_rb); a path is passed as is. The elf is optional: without it the game reads
 *              SLUS_217.82 from the disc image.
 *   env, env0, env1, ...   one NAME=VALUE each, set as environment variables (the P4PC_* test switches: P4PC_AUTOPRESS, P4PC_CAPTURE_FRAMES, ...).
 *   pad        "0" hides the touch pad.
 *   perf       "0" leaves out the 60 Hz display and sustained-performance requests.
 */
public class P4Activity extends SDLActivity implements InputManager.InputDeviceListener {
    private static final String TAG = "p4pc";

    private static native void nativeSetEnv(String name, String value);
    private static native void nativeHardwareKeyboardChanged(boolean connected);

    private PadOverlay pad;
    private InputManager keyboardDevices;

    @Override
    public void loadLibraries() {
        super.loadLibraries();
        try {
            setupEnvironment(getIntent());
        } catch (Exception e) {
            Log.e(TAG, "cannot set up the game's environment", e);
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // The game paces itself on a 60 Hz tick and updates 30 times a second. On a 90 or 120 Hz screen that tick and the display's refreshes
        // drift against each other (an occasional repeat or skip), so ask for a 60 Hz mode; and ask phones that support it to hold their clocks
        // steady instead of throttling under a long session. Both are requests the system may ignore. Launch extra perf=0 leaves them out.
        if (!"0".equals(getIntent().getStringExtra("perf"))) {
            try {
                final WindowManager.LayoutParams attributes = getWindow().getAttributes();
                attributes.preferredRefreshRate = 60f;
                getWindow().setAttributes(attributes);
                getWindow().setSustainedPerformanceMode(true);
            } catch (RuntimeException e) {
                Log.w(TAG, "cannot set the display and performance hints", e);
            }
        }
        if (!"0".equals(getIntent().getStringExtra("pad"))) {
            pad = new PadOverlay(this);
            SDLActivity.mLayout.addView(pad);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (pad != null) {
            // a connected game controller replaces the on-screen pad
            pad.setVisibility(PadOverlay.hasGamepad() ? View.GONE : View.VISIBLE);
        }
    }
    /** Called synchronously from the native error UI before its drained exit. */
    public boolean prepareRestart() {
        Intent game = new Intent(getIntent());
        // Keep URI strings and grants, not native descriptors opened by this process.
        Bundle original = game.getExtras();
        if (original != null) {
            for (String key : original.keySet()) {
                Object value = original.get(key);
                if (!(value instanceof String)) continue;
                String text = (String) value;
                if ((key.equals("iso") || key.equals("elf")) && text.startsWith("fd:") ||
                    key.startsWith("env") && (text.startsWith("P4PC_ISO=fd:") || text.startsWith("P4PC_ELF=fd:"))) {
                    Log.e(TAG, "restart cannot reopen a process-local file descriptor");
                    return false;
                }
            }
        }
        game.setClass(this, P4Activity.class);
        Bundle rendezvous = new Bundle();
        rendezvous.putBinder("gameLifetime", new Binder());
        rendezvous.putParcelable("gameIntent", game);
        Intent restart = new Intent(this, P4RestartActivity.class);
        restart.putExtras(rendezvous);
        restart.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        try {
            startActivity(restart);
            Log.i(TAG, "restart prepared");
            return true;
        } catch (RuntimeException failure) {
            Log.e(TAG, "cannot prepare fresh-process restart", failure);
            return false;
        }
    }

    /** Called by the C settings model only while native Config is open. */
    public void trackHardwareKeyboard(final boolean enabled) {
        runOnUiThread(() -> {
            if (!enabled) {
                if (keyboardDevices != null) keyboardDevices.unregisterInputDeviceListener(this);
                keyboardDevices = null;
                nativeHardwareKeyboardChanged(false);
                return;
            }
            if (keyboardDevices == null) {
                keyboardDevices = (InputManager) getSystemService(INPUT_SERVICE);
                keyboardDevices.registerInputDeviceListener(this, null);
            }
            nativeHardwareKeyboardChanged(PadOverlay.hasKeyboard());
        });
    }

    @Override public void onInputDeviceAdded(int id) { updateHardwareKeyboard(); }
    @Override public void onInputDeviceRemoved(int id) { updateHardwareKeyboard(); }
    @Override public void onInputDeviceChanged(int id) { updateHardwareKeyboard(); }
    private void updateHardwareKeyboard() {
        if (keyboardDevices != null) nativeHardwareKeyboardChanged(PadOverlay.hasKeyboard());
    }

    @Override
    protected void onDestroy() {
        if (keyboardDevices != null) {
            keyboardDevices.unregisterInputDeviceListener(this);
            keyboardDevices = null;
            nativeHardwareKeyboardChanged(false);
        }
        super.onDestroy();
    }

    private void setupEnvironment(Intent in) throws Exception {
        final File files = getFilesDir();
        final File saves = new File(files, "saves");
        final String iso = in.getStringExtra("iso");
        final String elf = in.getStringExtra("elf");

        saves.mkdirs();
        nativeSetEnv("P4PC_SAVE_DIR", saves.getPath());
        nativeSetEnv("P4PC_LOG_FILE", new File(files, "boot.log").getPath());
        nativeSetEnv("P4PC_LOGCAT", "1"); // the log goes to logcat as well, unless a test launch says env0=P4PC_LOGCAT=0
        if (iso != null) nativeSetEnv("P4PC_ISO", open(iso));
        if (elf != null && !elf.isEmpty()) nativeSetEnv("P4PC_ELF", open(elf));
        final Bundle extras = in.getExtras();
        if (extras != null) {
            for (String key : extras.keySet()) {
                // env, env0, env1, ...: one NAME=VALUE each (an adb shell line cannot carry a newline-separated list safely)
                final String line = key.startsWith("env") ? String.valueOf(extras.get(key)) : null;
                final int eq = line == null ? -1 : line.indexOf('=');
                if (eq > 0) nativeSetEnv(line.substring(0, eq).trim(), line.substring(eq + 1));
            }
        }
    }

    /** A file the game reads: a path stays a path, a URI becomes an open descriptor ("fd:N") that the native side duplicates. */
    private String open(String name) throws Exception {
        if (name.startsWith("/")) {
            return name;
        }
        final ParcelFileDescriptor fd = getContentResolver().openFileDescriptor(Uri.parse(name), "r");
        if (fd == null) {
            throw new java.io.FileNotFoundException(name);
        }
        return "fd:" + fd.detachFd();
    }
            }
