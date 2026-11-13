package org.p4pc.game;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;

import org.libsdl.app.SDLActivity;

/**
 * The virtual pad: d-pad, the four face buttons, L1/R1/L2/R2 and Start/Select, drawn over the game surface. Each button injects the Android key
 * the port's keyboard binding for that PS2 button uses (src/platform/pad/pad_sdl.c kKeys: arrows, Z X C V, Enter, Backspace, Q E, 1 3), through SDL's
 * key entry points, so the touch pad needs no code of its own on the native side. Bluetooth and USB controllers reach the game through SDL's
 * gamepad support (pad_sdl.c) and hide this overlay.
 *
 * Layout editor: press and hold the small menu icon at the top centre for about 0.7 s. Drag a control to move it, tap it and use - / + to resize it,
 * Alpha cycles the transparency of the whole pad, Reset restores the default layout and Done saves. With nothing changed the pad looks and behaves as
 * it always did; the saved layout is kept as fractions of the screen, so it survives a change of window size.
 */
final class PadOverlay extends View {
    private static final int UP = 0, DOWN = 1, LEFT = 2, RIGHT = 3, CROSS = 4, CIRCLE = 5, TRIANGLE = 6, SQUARE = 7;
    private static final int START = 8, SELECT = 9, L1 = 10, R1 = 11, L2 = 12, R2 = 13, COUNT = 14;
    private static final int[] KEYS = {
        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
        KeyEvent.KEYCODE_Z, KeyEvent.KEYCODE_X, KeyEvent.KEYCODE_C, KeyEvent.KEYCODE_V,
        KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DEL, KeyEvent.KEYCODE_Q, KeyEvent.KEYCODE_E, KeyEvent.KEYCODE_1, KeyEvent.KEYCODE_3,
    };

    // The parts of the pad that can be moved and resized. The d-pad and the face buttons move as one cluster each.
    private static final int E_DPAD = 0, E_FACE = 1, E_L1 = 2, E_L2 = 3, E_R1 = 4, E_R2 = 5, E_SELECT = 6, E_START = 7, ELEMENTS = 8;
    private static final int[] BUTTON_OF = {-1, -1, L1, L2, R1, R2, SELECT, START};
    private static final float[] HALF_HEIGHT = {0f, 0f, 0.55f, 0.55f, 0.55f, 0.55f, 0.5f, 0.5f}; // of the box, in units of the control's size
    private static final float[][] FACE = {{0, 1.5f, CROSS}, {1.5f, 0, CIRCLE}, {0, -1.5f, TRIANGLE}, {-1.5f, 0, SQUARE}};
    private static final float[] OPACITIES = {1f, 0.75f, 0.5f, 0.3f};
    private static final float MIN_SIZE = 0.5f, MAX_SIZE = 2f;
    private static final int P_SMALLER = 0, P_BIGGER = 1, P_ALPHA = 2, P_RESET = 3, P_DONE = 4, PANEL = 5;
    private static final long EDIT_HOLD_MS = 700;
    private static final String PREFS = "p4pc_pad";

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ui = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint accent = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF tmp = new RectF();
    private final RectF[] panel = new RectF[PANEL];
    private boolean[] down = new boolean[COUNT];
    private float u, vw, vh;

    private final float[] baseX = new float[ELEMENTS], baseY = new float[ELEMENTS]; // default centres, in pixels
    private final float[] offX = new float[ELEMENTS], offY = new float[ELEMENTS];   // the user's moves, as a fraction of the width / height
    private final float[] size = new float[ELEMENTS];                               // 1 = default size
    private float opacity = 1f;
    private final SharedPreferences prefs;

    private boolean editing, dragging, gearDown;
    private int selected = -1;
    private float lastX, lastY;
    private final Runnable gearHold = new Runnable() {
        @Override
        public void run() {
            if (gearDown && !editing) startEditing();
        }
    };

    PadOverlay(Context c) {
        super(c);
        prefs = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        line.setStyle(Paint.Style.STROKE);
        fill.setStyle(Paint.Style.FILL);
        ui.setStyle(Paint.Style.STROKE);
        accent.setStyle(Paint.Style.STROKE);
        accent.setColor(0xFFFFD54F);
        text.setColor(0xFFFFFFFF);
        text.setTextAlign(Paint.Align.CENTER);
        text.setShadowLayer(4f, 0f, 0f, 0xFF000000);
        for (int i = 0; i < PANEL; i++) panel[i] = new RectF();
        resetLayout();
        load();
    }

    static boolean hasGamepad() {
        for (int id : InputDevice.getDeviceIds()) {
            final InputDevice d = InputDevice.getDevice(id);
            if (d != null && (d.getSources() & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD && !d.isVirtual()) {
                return true;
            }
        }
        return false;
    }
    static boolean hasKeyboard() {
        for (int id : InputDevice.getDeviceIds()) {
            final InputDevice d = InputDevice.getDevice(id);
            if (d == null || d.isVirtual()) continue;
            final int sources = d.getSources();
            final int type = d.getKeyboardType();
            final boolean controller =
                    (sources & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
                    (sources & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK;
            if ((sources & InputDevice.SOURCE_KEYBOARD) == InputDevice.SOURCE_KEYBOARD &&
                    (type == InputDevice.KEYBOARD_TYPE_ALPHABETIC ||
                     (type == InputDevice.KEYBOARD_TYPE_NON_ALPHABETIC && !controller))) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ layout

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        // The Android picture is fitted to 4:3. On wide phones keep the pad in its
        // side bars rather than sizing it from the whole screen and covering play.
        vw = w;
        vh = h;
        final float gutter = Math.max(0f, (w - h * 4f / 3f) / 2f);
        final boolean sideBars = gutter >= h * 0.32f;
        u = sideBars ? Math.min(h / 10f, gutter / 6.4f) : Math.min(w, h * 1.7f) / 17f;
        final float dpadX = sideBars ? gutter / 2f : 3.2f * u;
        final float dpadY = h - (sideBars ? 4.4f : 3.4f) * u;
        final float faceX = w - dpadX;
        final float faceY = dpadY;
        line.setStrokeWidth(Math.max(2f, u * 0.08f));
        ui.setStrokeWidth(Math.max(2f, u * 0.08f));
        accent.setStrokeWidth(Math.max(3f, u * 0.12f));
        text.setTextSize(0.42f * u);
        baseX[E_DPAD] = dpadX; baseY[E_DPAD] = dpadY;
        baseX[E_FACE] = faceX; baseY[E_FACE] = faceY;
        // shoulder buttons: two rows at the top corners, clear of the cutout
        baseX[E_L1] = dpadX; baseY[E_L1] = 0.95f * u;
        baseX[E_L2] = dpadX; baseY[E_L2] = 2.25f * u;
        baseX[E_R1] = faceX; baseY[E_R1] = 0.95f * u;
        baseX[E_R2] = faceX; baseY[E_R2] = 2.25f * u;
        baseX[E_SELECT] = sideBars ? dpadX : w / 2f - 1.8f * u; baseY[E_SELECT] = h - u;
        baseX[E_START] = sideBars ? faceX : w / 2f + 1.8f * u; baseY[E_START] = h - u;
        // the editor's buttons, in a row in the middle of the screen
        final float bw = 2.4f * u, bh = 1.1f * u, gap = 0.2f * u;
        final float left = w / 2f - (PANEL * bw + (PANEL - 1) * gap) / 2f;
        final float top = h / 2f - bh / 2f;
        for (int i = 0; i < PANEL; i++) panel[i].set(left + i * (bw + gap), top, left + i * (bw + gap) + bw, top + bh);
    }

    private float cx(int e) { return Math.max(0f, Math.min(vw, baseX[e] + offX[e] * vw)); }
    private float cy(int e) { return Math.max(0f, Math.min(vh, baseY[e] + offY[e] * vh)); }
    private float ue(int e) { return u * size[e]; }

    /** The box of a shoulder, Start or Select button. */
    private void rectOf(int e, RectF out) {
        final float s = ue(e);
        out.set(cx(e) - 1.4f * s, cy(e) - HALF_HEIGHT[e] * s, cx(e) + 1.4f * s, cy(e) + HALF_HEIGHT[e] * s);
    }

    private void clampOffset(int e) {
        if (vw <= 0f || vh <= 0f) return;
        offX[e] = (cx(e) - baseX[e]) / vw;
        offY[e] = (cy(e) - baseY[e]) / vh;
    }

    private void resetLayout() {
        for (int e = 0; e < ELEMENTS; e++) {
            offX[e] = 0f;
            offY[e] = 0f;
            size[e] = 1f;
        }
        opacity = 1f;
    }

    private void load() {
        for (int e = 0; e < ELEMENTS; e++) {
            offX[e] = Math.max(-1f, Math.min(1f, prefs.getFloat("ox" + e, 0f)));
            offY[e] = Math.max(-1f, Math.min(1f, prefs.getFloat("oy" + e, 0f)));
            size[e] = Math.max(MIN_SIZE, Math.min(MAX_SIZE, prefs.getFloat("sz" + e, 1f)));
        }
        opacity = Math.max(0.1f, Math.min(1f, prefs.getFloat("opacity", 1f)));
    }

    private void save() {
        final SharedPreferences.Editor ed = prefs.edit();
        for (int e = 0; e < ELEMENTS; e++) {
            ed.putFloat("ox" + e, offX[e]);
            ed.putFloat("oy" + e, offY[e]);
            ed.putFloat("sz" + e, size[e]);
        }
        ed.putFloat("opacity", opacity);
        ed.apply();
    }

    /** The colour with its alpha scaled by the pad's transparency setting. */
    private int a(int argb) {
        final int alpha = Math.round(((argb >>> 24) & 0xFF) * opacity);
        return (alpha << 24) | (argb & 0xFFFFFF);
    }

    // ------------------------------------------------------------------ play

    /** Buttons under one finger. */
    private int hit(float x, float y) {
        int mask = 0;
        float s = ue(E_DPAD);
        final float dx = x - cx(E_DPAD), dy = y - cy(E_DPAD);
        final float d = (float) Math.hypot(dx, dy);
        if (d < 2.6f * s && d > 0.35f * s) {
            if (dx < -0.4f * d) mask |= 1 << LEFT;
            if (dx > 0.4f * d) mask |= 1 << RIGHT;
            if (dy < -0.4f * d) mask |= 1 << UP;
            if (dy > 0.4f * d) mask |= 1 << DOWN;
        }
        s = ue(E_FACE);
        for (float[] f : FACE) {
            if (Math.hypot(x - (cx(E_FACE) + f[0] * s), y - (cy(E_FACE) + f[1] * s)) < 1.0f * s) mask |= 1 << (int) f[2];
        }
        for (int e = E_L1; e < ELEMENTS; e++) {
            rectOf(e, tmp);
            if (tmp.contains(x, y)) mask |= 1 << BUTTON_OF[e];
        }
        return mask;
    }

    private boolean gearHit(float x, float y) {
        return Math.hypot(x - vw / 2f, y - 0.9f * u) < 0.9f * u;
    }

    private void releaseAll() {
        for (int i = 0; i < COUNT; i++) {
            if (down[i]) SDLActivity.onNativeKeyUp(KEYS[i]);
        }
        down = new boolean[COUNT];
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (editing) return onEditTouch(e);
        final int action = e.getActionMasked();
        // the menu icon: a press that stays on it opens the layout editor; a short tap does nothing
        if (action == MotionEvent.ACTION_DOWN && e.getPointerCount() == 1 && gearHit(e.getX(), e.getY())) {
            gearDown = true;
            postDelayed(gearHold, EDIT_HOLD_MS);
            return true;
        }
        if (gearDown) {
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                gearDown = false;
                removeCallbacks(gearHold);
            }
            return true;
        }
        final int lifted = (action == MotionEvent.ACTION_POINTER_UP || action == MotionEvent.ACTION_UP) ? e.getActionIndex() : -1;
        int mask = 0;
        if (action != MotionEvent.ACTION_CANCEL) {
            for (int i = 0; i < e.getPointerCount(); i++) {
                if (i != lifted) mask |= hit(e.getX(i), e.getY(i));
            }
        }
        final boolean[] now = new boolean[COUNT];
        for (int i = 0; i < COUNT; i++) {
            now[i] = (mask & (1 << i)) != 0;
            if (now[i] != down[i]) {
                if (now[i]) SDLActivity.onNativeKeyDown(KEYS[i]);
                else SDLActivity.onNativeKeyUp(KEYS[i]);
            }
        }
        down = now;
        invalidate();
        return mask != 0 || action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP;
    }

    // ------------------------------------------------------------------ editor

    private void startEditing() {
        releaseAll();
        editing = true;
        dragging = false;
        selected = -1;
        invalidate();
    }

    /** The control under a finger in the editor, with a little more room than when playing. */
    private int elementAt(float x, float y) {
        for (int e = ELEMENTS - 1; e >= 0; e--) {
            final float s = ue(e);
            if (e == E_DPAD || e == E_FACE) {
                if (Math.hypot(x - cx(e), y - cy(e)) < 2.6f * s) return e;
            } else {
                rectOf(e, tmp);
                tmp.inset(-0.3f * s, -0.3f * s);
                if (tmp.contains(x, y)) return e;
            }
        }
        return -1;
    }

    private void panelAction(int p) {
        switch (p) {
            case P_SMALLER:
                if (selected >= 0) size[selected] = Math.max(MIN_SIZE, Math.round((size[selected] - 0.1f) * 10f) / 10f);
                break;
            case P_BIGGER:
                if (selected >= 0) size[selected] = Math.min(MAX_SIZE, Math.round((size[selected] + 0.1f) * 10f) / 10f);
                break;
            case P_ALPHA: {
                int i = 0;
                for (int k = 0; k < OPACITIES.length; k++) {
                    if (Math.abs(OPACITIES[k] - opacity) < 0.01f) i = k;
                }
                opacity = OPACITIES[(i + 1) % OPACITIES.length];
                break;
            }
            case P_RESET:
                resetLayout();
                selected = -1;
                break;
            case P_DONE:
                save();
                editing = false;
                dragging = false;
                selected = -1;
                break;
            default:
                break;
        }
    }

    private boolean onEditTouch(MotionEvent e) {
        final int action = e.getActionMasked();
        final float x = e.getX(), y = e.getY();
        if (action == MotionEvent.ACTION_DOWN) {
            dragging = false;
            for (int p = 0; p < PANEL; p++) {
                if (panel[p].contains(x, y)) {
                    panelAction(p);
                    invalidate();
                    return true;
                }
            }
            final int el = elementAt(x, y);
            if (el >= 0) {
                selected = el;
                dragging = true;
                lastX = x;
                lastY = y;
            }
            invalidate();
        } else if (action == MotionEvent.ACTION_MOVE) {
            if (dragging && selected >= 0 && vw > 0f && vh > 0f) {
                offX[selected] += (x - lastX) / vw;
                offY[selected] += (y - lastY) / vh;
                clampOffset(selected);
                lastX = x;
                lastY = y;
                invalidate();
            }
        } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            dragging = false;
            gearDown = false;
        }
        return true;
    }

    // ------------------------------------------------------------------ drawing

    private void button(Canvas c, float cx, float cy, float r, boolean on) {
        fill.setColor(a(on ? 0x99FFFFFF : 0x33000000));
        c.drawCircle(cx, cy, r, fill);
        c.drawCircle(cx, cy, r, line);
    }

    @Override
    protected void onDraw(Canvas c) {
        line.setColor(a(0xCCFFFFFF));
        // d-pad: a ring and four arrow wedges
        float s = ue(E_DPAD);
        final float dpadX = cx(E_DPAD), dpadY = cy(E_DPAD);
        fill.setColor(a(0x22000000));
        c.drawCircle(dpadX, dpadY, 2.4f * s, fill);
        c.drawCircle(dpadX, dpadY, 2.4f * s, line);
        final int[] dir = {UP, DOWN, LEFT, RIGHT};
        final float[][] off = {{0, -1.5f}, {0, 1.5f}, {-1.5f, 0}, {1.5f, 0}};
        for (int i = 0; i < 4; i++) {
            final float cx = dpadX + off[i][0] * s, cy = dpadY + off[i][1] * s;
            fill.setColor(a(down[dir[i]] ? 0x99FFFFFF : 0x33000000));
            final Path p = new Path();
            final float t = 0.55f * s;
            if (i == 0) { p.moveTo(cx, cy - t); p.lineTo(cx - t, cy + t); p.lineTo(cx + t, cy + t); }
            else if (i == 1) { p.moveTo(cx, cy + t); p.lineTo(cx - t, cy - t); p.lineTo(cx + t, cy - t); }
            else if (i == 2) { p.moveTo(cx - t, cy); p.lineTo(cx + t, cy - t); p.lineTo(cx + t, cy + t); }
            else { p.moveTo(cx + t, cy); p.lineTo(cx - t, cy - t); p.lineTo(cx - t, cy + t); }
            p.close();
            c.drawPath(p, fill);
            c.drawPath(p, line);
        }
        // face buttons: cross (bottom), circle (right), triangle (top), square (left)
        s = ue(E_FACE);
        final float faceX = cx(E_FACE), faceY = cy(E_FACE);
        final float r = 0.9f * s, k = 0.4f * s;
        button(c, faceX, faceY + 1.5f * s, r, down[CROSS]);
        c.drawLine(faceX - k, faceY + 1.5f * s - k, faceX + k, faceY + 1.5f * s + k, line);
        c.drawLine(faceX - k, faceY + 1.5f * s + k, faceX + k, faceY + 1.5f * s - k, line);
        button(c, faceX + 1.5f * s, faceY, r, down[CIRCLE]);
        c.drawCircle(faceX + 1.5f * s, faceY, k, line);
        button(c, faceX, faceY - 1.5f * s, r, down[TRIANGLE]);
        final Path tri = new Path();
        tri.moveTo(faceX, faceY - 1.5f * s - k); tri.lineTo(faceX - k, faceY - 1.5f * s + k * 0.8f); tri.lineTo(faceX + k, faceY - 1.5f * s + k * 0.8f); tri.close();
        c.drawPath(tri, line);
        button(c, faceX - 1.5f * s, faceY, r, down[SQUARE]);
        c.drawRect(faceX - 1.5f * s - k, faceY - k, faceX - 1.5f * s + k, faceY + k, line);
        // shoulders and start/select
        for (int e = E_L1; e < ELEMENTS; e++) {
            s = ue(e);
            rectOf(e, tmp);
            fill.setColor(a(down[BUTTON_OF[e]] ? 0x99FFFFFF : 0x33000000));
            c.drawRoundRect(tmp, 0.4f * s, 0.4f * s, fill);
            c.drawRoundRect(tmp, 0.4f * s, 0.4f * s, line);
        }
        if (editing) drawEditor(c);
        else drawMenuIcon(c);
    }

    /** The hold-to-edit icon: three bars in a faint circle. */
    private void drawMenuIcon(Canvas c) {
        final float x = vw / 2f, y = 0.9f * u;
        ui.setColor(0x55FFFFFF);
        c.drawCircle(x, y, 0.6f * u, ui);
        for (int i = -1; i <= 1; i++) c.drawLine(x - 0.3f * u, y + i * 0.2f * u, x + 0.3f * u, y + i * 0.2f * u, ui);
    }

    private void drawEditor(Canvas c) {
        if (selected >= 0) {
            final float s = ue(selected);
            if (selected == E_DPAD || selected == E_FACE) {
                c.drawCircle(cx(selected), cy(selected), 2.6f * s, accent);
            } else {
                rectOf(selected, tmp);
                tmp.inset(-0.2f * s, -0.2f * s);
                c.drawRoundRect(tmp, 0.5f * s, 0.5f * s, accent);
            }
        }
        final String[] label = {"-", "+", "Alpha " + Math.round(opacity * 100f) + "%", "Reset", "Done"};
        for (int p = 0; p < PANEL; p++) {
            fill.setColor(0xDD202020);
            c.drawRoundRect(panel[p], 0.3f * u, 0.3f * u, fill);
            ui.setColor(0xFFFFFFFF);
            c.drawRoundRect(panel[p], 0.3f * u, 0.3f * u, ui);
            c.drawText(label[p], panel[p].centerX(), panel[p].centerY() + 0.15f * u, text);
        }
        final float top = panel[0].top;
        c.drawText("Drag a control to move it", vw / 2f, top - 1.0f * u, text);
        c.drawText(selected >= 0 ? "Size " + Math.round(size[selected] *
