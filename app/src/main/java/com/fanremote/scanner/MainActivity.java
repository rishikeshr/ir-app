package com.fanremote.scanner;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.hardware.ConsumerIrManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Finds the infrared codes of a fan whose remote is lost.
 * Scan sweeps every standard NEC code. Pinpoint steps one code at a time
 * around the spot where the fan reacted. Remote shows the saved codes as buttons.
 */
public class MainActivity extends Activity {
    private static final int CODE_PERIOD_MS = 110;
    private static final int BACKTRACK_CODES = 20;
    private static final int AUTO_STEP_MS = 1500;
    private static final int TOTAL_CODES = Nec.ADDRESSES * Nec.COMMANDS;
    /** The six buttons on the Luminous remote, in the order they are offered when saving. */
    private static final String POWER = "Power";
    private static final String[] SLOTS = {POWER, "Speed 1", "Speed 2", "Speed 3", "Speed 4", "Speed 5"};
    /** Where each speed key sits on the ring, in degrees anticlockwise from the right. */
    private static final int[] RING_ANGLES = {234, 162, 90, 18, 306};

    private static final int BG = 0xFF0E1320;
    private static final int CARD = 0xFF1A2133;
    private static final int CARD_HIGH = 0xFF26304A;
    private static final int TEXT = 0xFFF2F5FA;
    private static final int MUTED = 0xFF9AA5BD;
    private static final int ACCENT = 0xFF5EEAD4;
    private static final int ON_ACCENT = 0xFF06231F;
    private static final int AMBER = 0xFFFFC857;
    private static final int ON_AMBER = 0xFF2B1D00;

    private static final int SCREEN_SCAN = 0;
    private static final int SCREEN_PIN = 1;
    private static final int SCREEN_REMOTE = 2;

    private final int[] order = Nec.addressOrder();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final List<String[]> saved = new ArrayList<String[]>();

    private ConsumerIrManager ir;
    private SharedPreferences prefs;
    private HandlerThread worker;
    private Handler bg;

    private volatile boolean scanning;
    private volatile int scanPos;
    private int pinPos;
    private int pinStart = -1;
    private boolean pinFresh = true;
    private boolean autoStep;
    private String activeSpeed;

    private LinearLayout scanPanel;
    private LinearLayout pinPanel;
    private LinearLayout remotePanel;
    private LinearLayout remoteGrid;
    private TextView navFind;
    private TextView navRemote;
    private TextView scanPercent;
    private TextView scanLine;
    private TextView scanCode;
    private TextView scanToggle;
    private TextView reactedButton;
    private ProgressBar scanProgress;
    private TextView pinStep;
    private TextView pinCode;
    private TextView pinNext;
    private TextView autoToggle;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);

        ir = (ConsumerIrManager) getSystemService(Context.CONSUMER_IR_SERVICE);
        prefs = getSharedPreferences("fan", MODE_PRIVATE);
        scanPos = clampPos(prefs.getInt("scanPos", 0));
        pinPos = clampPos(prefs.getInt("pinPos", 0));
        loadSaved();
        activeSpeed = prefs.getString("activeSpeed", null);

        worker = new HandlerThread("ir-sender");
        worker.start();
        bg = new Handler(worker.getLooper());

        buildUi();
        refreshScan();
        refreshPin();
        refreshRemote();
        show(saved.isEmpty() ? SCREEN_SCAN : SCREEN_REMOTE);

        if (!hasEmitter()) {
            new AlertDialog.Builder(this)
                .setTitle("No IR blaster found")
                .setMessage("This phone does not report an infrared emitter, so the app cannot send codes.")
                .setPositiveButton("OK", null)
                .show();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        scanning = false;
        setAutoStep(false);
        prefs.edit().putInt("scanPos", scanPos).putInt("pinPos", pinPos).apply();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        scanning = false;
        worker.quitSafely();
    }

    private boolean hasEmitter() {
        return ir != null && ir.hasIrEmitter();
    }

    // ---------- sending ----------

    /** Sends one code and waits out the rest of the period so codes never overlap. */
    private void send(int address, int command) {
        long start = SystemClock.elapsedRealtime();
        try {
            ir.transmit(Nec.CARRIER_HZ, Nec.frame(address, command));
        } catch (final RuntimeException e) {
            scanning = false;
            ui.post(() -> toast("Could not send: " + e.getMessage()));
        }
        long spent = SystemClock.elapsedRealtime() - start;
        if (spent < CODE_PERIOD_MS) {
            SystemClock.sleep(CODE_PERIOD_MS - spent);
        }
    }

    private void sendAsync(final int pos) {
        if (!hasEmitter()) {
            return;
        }
        bg.post(() -> send(order[pos / Nec.COMMANDS], pos % Nec.COMMANDS));
    }

    private static int clampPos(int pos) {
        return Math.max(0, Math.min(TOTAL_CODES - 1, pos));
    }

    private String hex(int pos) {
        return String.format(Locale.US, "Address 0x%02X, command 0x%02X",
            order[pos / Nec.COMMANDS], pos % Nec.COMMANDS);
    }

    // ---------- scan ----------

    private final Runnable scanLoop = new Runnable() {
        @Override
        public void run() {
            boolean finished = false;
            while (scanning) {
                int pos = scanPos;
                send(order[pos / Nec.COMMANDS], pos % Nec.COMMANDS);
                if (pos + 1 >= TOTAL_CODES) {
                    finished = true;
                    scanning = false;
                } else {
                    scanPos = pos + 1;
                }
                if (pos % 4 == 0) {
                    ui.post(() -> refreshScan());
                }
            }
            final boolean done = finished;
            ui.post(() -> onScanStopped(done));
        }
    };

    private void startScan() {
        if (scanning || !hasEmitter()) {
            return;
        }
        scanning = true;
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        bg.post(scanLoop);
        refreshScan();
    }

    private void onScanStopped(boolean finished) {
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        prefs.edit().putInt("scanPos", scanPos).apply();
        refreshScan();
        if (finished) {
            new AlertDialog.Builder(this)
                .setTitle("Scan finished")
                .setMessage("Every standard code was sent. If the fan never reacted, it uses radio (RF) "
                    + "or a different infrared format, and this phone cannot control it.")
                .setPositiveButton("OK", null)
                .show();
        }
    }

    private void onReacted() {
        scanning = false;
        pinStart = clampPos(scanPos - BACKTRACK_CODES);
        pinPos = pinStart;
        pinFresh = true;
        refreshPin();
        show(SCREEN_PIN);
    }

    private void refreshScan() {
        int pos = scanPos;
        int remainingSec = (TOTAL_CODES - pos) * CODE_PERIOD_MS / 1000;
        String left = remainingSec >= 3600
            ? (remainingSec / 3600) + " h " + (remainingSec % 3600 / 60) + " min"
            : Math.max(1, remainingSec / 60) + " min";
        scanPercent.setText(String.format(Locale.US, "%.1f%%", pos * 100f / TOTAL_CODES));
        scanLine.setText((scanning ? "Scanning" : (pos == 0 ? "Ready" : "Paused"))
            + "  ·  at most " + left + " left");
        scanCode.setText(hex(pos));
        scanProgress.setProgress(pos);
        scanToggle.setText(scanning ? "Pause" : (pos == 0 ? "Start scanning" : "Resume scanning"));
        setEnabled(reactedButton, pos > 0);
    }

    // ---------- pinpoint ----------

    private final Runnable autoStepTask = new Runnable() {
        @Override
        public void run() {
            if (!autoStep) {
                return;
            }
            if (pinPos + 1 >= TOTAL_CODES) {
                setAutoStep(false);
                return;
            }
            nextPin();
            ui.postDelayed(this, AUTO_STEP_MS);
        }
    };

    /** Sends the current code the first time, then advances one code per tap. */
    private void nextPin() {
        if (pinFresh) {
            pinFresh = false;
            stepPin(0);
        } else {
            stepPin(1);
        }
    }

    private void stepPin(int delta) {
        pinFresh = false;
        pinPos = clampPos(pinPos + delta);
        sendAsync(pinPos);
        refreshPin();
    }

    private void setAutoStep(boolean on) {
        autoStep = on;
        ui.removeCallbacks(autoStepTask);
        if (on) {
            ui.post(autoStepTask);
        }
        if (autoToggle != null) {
            autoToggle.setText(on ? "Stop stepping" : "Step automatically, one every 1.5 s");
        }
    }

    private void refreshPin() {
        int n = pinPos - pinStart + 1;
        boolean inWindow = pinStart >= 0 && n >= 1 && n <= BACKTRACK_CODES;
        if (pinFresh) {
            pinStep.setText("Ready");
        } else if (inWindow) {
            pinStep.setText("Code " + n + " of about " + BACKTRACK_CODES);
        } else {
            pinStep.setText("Code " + (pinPos % Nec.COMMANDS + 1) + " of " + Nec.COMMANDS + " in this group");
        }
        pinCode.setText(hex(pinPos));
        pinNext.setText(pinFresh ? "Send the first code" : "Next code");
    }

    private void askLabel() {
        setAutoStep(false);
        final String[] items = new String[SLOTS.length + 1];
        for (int i = 0; i < SLOTS.length; i++) {
            items[i] = find(SLOTS[i]) == null ? SLOTS[i] : SLOTS[i] + "  (replace)";
        }
        items[SLOTS.length] = "Something else...";
        new AlertDialog.Builder(this)
            .setTitle("Which button does the same thing?")
            .setItems(items, (dialog, which) -> {
                if (which == SLOTS.length) {
                    askName("Name this button", "", name -> saveCode(name));
                } else {
                    saveCode(SLOTS[which]);
                }
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    private interface NameCallback {
        void onName(String name);
    }

    private void askName(String title, String current, final NameCallback callback) {
        final EditText input = new EditText(this);
        input.setHint("Button name");
        input.setText(current);
        new AlertDialog.Builder(this)
            .setTitle(title)
            .setView(input)
            .setPositiveButton("Save", (dialog, which) -> {
                String name = input.getText().toString().replace('\t', ' ').replace('\n', ' ').trim();
                callback.onName(name.isEmpty() ? "Button" : name);
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void saveCode(String label) {
        final boolean first = saved.isEmpty();
        String[] existing = isSlot(label) ? find(label) : null;
        if (existing != null) {
            existing[1] = String.valueOf(pinPos);
        } else {
            saved.add(new String[] {label, String.valueOf(pinPos)});
        }
        storeSaved();
        refreshRemote();
        int missing = missingSlots();
        String message;
        if (missing == 0) {
            message = "All six buttons are set up. The remote is ready.";
        } else if (first) {
            message = "The other buttons are in the same group of 256 codes. "
                + "Keep looking starts again from the beginning of that group.";
        } else {
            message = missing + " of 6 buttons still to find. Keep looking carries on from this code.";
        }
        AlertDialog.Builder dialog = new AlertDialog.Builder(this)
            .setTitle("Saved \"" + label + "\"")
            .setMessage(message)
            .setNegativeButton("Open remote", (d, which) -> show(SCREEN_REMOTE));
        if (missing > 0) {
            dialog.setPositiveButton("Keep looking", (d, which) -> {
                if (first) {
                    startOfGroup(pinPos);
                }
            });
        }
        dialog.show();
    }

    private static boolean isSlot(String label) {
        for (String slot : SLOTS) {
            if (slot.equals(label)) {
                return true;
            }
        }
        return false;
    }

    private String[] find(String label) {
        for (String[] entry : saved) {
            if (entry[0].equals(label)) {
                return entry;
            }
        }
        return null;
    }

    private int missingSlots() {
        int missing = 0;
        for (String slot : SLOTS) {
            if (find(slot) == null) {
                missing++;
            }
        }
        return missing;
    }

    /** Points Pinpoint at the first code of the group that contains pos, without sending. */
    private void startOfGroup(int pos) {
        pinPos = pos / Nec.COMMANDS * Nec.COMMANDS;
        pinStart = -1;
        pinFresh = true;
        refreshPin();
    }

    /** Opens the quickest way to find a missing button: the known group if there is one, else a scan. */
    private void findMore() {
        if (saved.isEmpty()) {
            show(SCREEN_SCAN);
        } else {
            startOfGroup(Integer.parseInt(saved.get(0)[1]));
            show(SCREEN_PIN);
        }
    }

    /** Moves to the first code of the neighbouring group without sending anything. */
    private void jumpGroup(int delta) {
        setAutoStep(false);
        int group = Math.max(0, Math.min(Nec.ADDRESSES - 1, pinPos / Nec.COMMANDS + delta));
        startOfGroup(group * Nec.COMMANDS);
    }

    // ---------- saved remote ----------

    private void loadSaved() {
        saved.clear();
        String raw = prefs.getString("saved", "");
        for (String line : raw.split("\n")) {
            String[] parts = line.split("\t");
            if (parts.length != 2) {
                continue;
            }
            try {
                int pos = Integer.parseInt(parts[1]);
                if (pos >= 0 && pos < TOTAL_CODES) {
                    if ("Fan on/off".equals(parts[0]) && find(POWER) == null) {
                        parts[0] = POWER;
                    }
                    saved.add(parts);
                }
            } catch (NumberFormatException ignored) {
                // Skip a corrupt entry and keep the rest.
            }
        }
    }

    private void storeSaved() {
        StringBuilder sb = new StringBuilder();
        for (String[] entry : saved) {
            sb.append(entry[0]).append('\t').append(entry[1]).append('\n');
        }
        prefs.edit().putString("saved", sb.toString()).apply();
    }

    private void refreshRemote() {
        remoteGrid.removeAllViews();
        int missing = missingSlots();

        LinearLayout body = card();
        body.setBackground(rounded(CARD, 40));
        body.setGravity(Gravity.CENTER_HORIZONTAL);
        TextView power = remoteKey(POWER, "Power", 20, 28);
        body.addView(power, new LinearLayout.LayoutParams(dp(148), dp(64)));
        body.addView(gap(20));

        int ringSize = dp(264);
        int keySize = dp(76);
        FrameLayout ring = new FrameLayout(this);
        View hub = new View(this);
        hub.setBackground(outline(CARD_HIGH, 30, false));
        FrameLayout.LayoutParams hubLp = new FrameLayout.LayoutParams(dp(60), dp(60));
        hubLp.gravity = Gravity.CENTER;
        ring.addView(hub, hubLp);
        for (int i = 0; i < RING_ANGLES.length; i++) {
            double angle = Math.toRadians(RING_ANGLES[i]);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(keySize, keySize);
            lp.leftMargin = (int) Math.round(ringSize / 2.0 + dp(92) * Math.cos(angle) - keySize / 2.0);
            lp.topMargin = (int) Math.round(ringSize / 2.0 - dp(92) * Math.sin(angle) - keySize / 2.0);
            ring.addView(remoteKey(SLOTS[i + 1], String.valueOf(i + 1), 28, 38), lp);
        }
        body.addView(ring, new LinearLayout.LayoutParams(ringSize, ringSize));
        body.addView(gap(8));
        body.addView(centered(label(missing == 0
            ? "Point the top edge of the phone at the fan."
            : "Dashed buttons are not set up yet.", 13, MUTED, false)));
        remoteGrid.addView(body);
        remoteGrid.addView(gap(16));

        if (missing > 0) {
            remoteGrid.addView(pill(missing == SLOTS.length ? "Set up the remote" : "Find the other " + missing
                + (missing == 1 ? " button" : " buttons"), ACCENT, ON_ACCENT, 56, 17, () -> findMore()));
            remoteGrid.addView(gap(12));
        }

        LinearLayout row = null;
        int extras = 0;
        for (final String[] entry : saved) {
            if (isSlot(entry[0])) {
                continue;
            }
            if (extras % 2 == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                remoteGrid.addView(row, matchWidth(0));
            }
            final int pos = Integer.parseInt(entry[1]);
            TextView key = pill(entry[0], CARD_HIGH, TEXT, 72, 17, null);
            key.setOnClickListener(v -> {
                v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                sendAsync(pos);
            });
            key.setOnLongClickListener(v -> {
                editEntry(entry);
                return true;
            });
            row.addView(key, weighted(72, 5, 5));
            extras++;
        }
        if (extras % 2 == 1) {
            row.addView(new View(this), weighted(72, 5, 5));
        }
        if (!saved.isEmpty()) {
            remoteGrid.addView(gap(8));
            remoteGrid.addView(centered(label("Press and hold a button to change it.", 13, MUTED, false)));
        }
    }

    /** One key of the on-screen remote: sends its code when set up, offers to find it when not. */
    private TextView remoteKey(final String slot, String caption, int sp, int radiusDp) {
        final String[] entry = find(slot);
        final boolean isPower = POWER.equals(slot);
        boolean active = entry != null && slot.equals(activeSpeed);
        int fill = isPower ? AMBER : (active ? ACCENT : CARD_HIGH);
        int textColor = entry == null ? MUTED : (isPower ? ON_AMBER : (active ? ON_ACCENT : TEXT));

        TextView key = new TextView(this);
        key.setText(caption);
        key.setTextSize(sp);
        key.setTextColor(textColor);
        key.setTypeface(Typeface.DEFAULT_BOLD);
        key.setGravity(Gravity.CENTER);
        Drawable shape = entry == null ? outline(MUTED, radiusDp, true) : rounded(fill, radiusDp);
        key.setBackground(new RippleDrawable(ColorStateList.valueOf(0x40FFFFFF), shape, rounded(0xFFFFFFFF, radiusDp)));
        key.setClickable(true);
        key.setFocusable(true);
        key.setContentDescription(slot);
        if (entry == null) {
            key.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle(slot + " is not set up")
                .setMessage("Find its code by sending codes one at a time while you watch the fan.")
                .setPositiveButton("Find it", (d, which) -> findMore())
                .setNegativeButton("Cancel", null)
                .show());
            return key;
        }
        final int pos = Integer.parseInt(entry[1]);
        key.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            sendAsync(pos);
            activeSpeed = isPower ? null : slot;
            prefs.edit().putString("activeSpeed", activeSpeed).apply();
            refreshRemote();
        });
        key.setOnLongClickListener(v -> {
            editEntry(entry);
            return true;
        });
        return key;
    }

    private void editEntry(final String[] entry) {
        final boolean slot = isSlot(entry[0]);
        String[] items = slot
            ? new String[] {"Find a different code", "Clear this button"}
            : new String[] {"Rename", "Delete"};
        new AlertDialog.Builder(this)
            .setTitle(entry[0])
            .setItems(items, (dialog, which) -> {
                if (which == 0 && slot) {
                    startOfGroup(Integer.parseInt(entry[1]));
                    show(SCREEN_PIN);
                } else if (which == 0) {
                    askName("Rename button", entry[0], name -> {
                        entry[0] = isSlot(name) ? name + " (2)" : name;
                        storeSaved();
                        refreshRemote();
                    });
                } else {
                    saved.remove(entry);
                    storeSaved();
                    refreshRemote();
                }
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    // ---------- layout ----------

    private void buildUi() {
        LinearLayout root = column();
        root.setBackgroundColor(BG);
        root.setFitsSystemWindows(true);

        buildScanPanel();
        buildPinPanel();
        buildRemotePanel();

        LinearLayout panels = column();
        int pad = dp(20);
        panels.setPadding(pad, pad, pad, pad);
        panels.addView(scanPanel);
        panels.addView(pinPanel);
        panels.addView(remotePanel);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(panels);
        root.addView(scroll, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout nav = new LinearLayout(this);
        nav.setOrientation(LinearLayout.HORIZONTAL);
        nav.setBackgroundColor(CARD);
        nav.setPadding(dp(12), dp(8), dp(12), dp(8));
        navFind = navItem("Find codes", () -> show(SCREEN_SCAN));
        navRemote = navItem("Remote", () -> show(SCREEN_REMOTE));
        nav.addView(navFind);
        nav.addView(navRemote);
        root.addView(nav, matchWidth(0));

        setContentView(root);
    }

    private void buildScanPanel() {
        scanPanel = column();
        scanPanel.addView(label("STEP 1 OF 2", 12, ACCENT, true));
        scanPanel.addView(label("Find your fan's codes", 28, TEXT, true));
        scanPanel.addView(label("The phone tries every code. Tell it the moment the fan reacts.", 15, MUTED, false));
        scanPanel.addView(gap(20));

        LinearLayout progress = card();
        scanPercent = label("0.0%", 44, TEXT, true);
        progress.addView(scanPercent);
        scanLine = label("", 15, MUTED, false);
        progress.addView(scanLine);
        progress.addView(gap(14));
        scanProgress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        scanProgress.setMax(TOTAL_CODES);
        scanProgress.setProgressTintList(ColorStateList.valueOf(ACCENT));
        scanProgress.setProgressBackgroundTintList(ColorStateList.valueOf(MUTED));
        progress.addView(scanProgress, matchWidth(0));
        progress.addView(gap(10));
        scanCode = label("", 12, MUTED, false);
        progress.addView(scanCode);
        scanPanel.addView(progress);
        scanPanel.addView(gap(16));

        reactedButton = pill("It reacted!", AMBER, ON_AMBER, 120, 26, () -> onReacted());
        scanPanel.addView(reactedButton);
        scanPanel.addView(gap(12));
        scanToggle = pill("Start scanning", ACCENT, ON_ACCENT, 60, 18, () -> {
            if (scanning) {
                scanning = false;
            } else {
                startScan();
            }
        });
        scanPanel.addView(scanToggle);
        scanPanel.addView(gap(20));

        LinearLayout tips = card();
        tips.addView(label("Before you start", 16, TEXT, true));
        tips.addView(gap(8));
        tips.addView(tip("1", "Switch the fan on at the wall."));
        tips.addView(tip("2", "Point the top edge of the phone at the fan."));
        tips.addView(tip("3", "Watch the fan and its light, and listen for a beep."));
        scanPanel.addView(tips);
        scanPanel.addView(gap(8));
        scanPanel.addView(link("Start over from the first code", () -> new AlertDialog.Builder(this)
            .setTitle("Start over?")
            .setMessage("The scan will begin again from the first code. Saved buttons are kept.")
            .setPositiveButton("Start over", (dialog, which) -> {
                scanning = false;
                scanPos = 0;
                refreshScan();
            })
            .setNegativeButton("Cancel", null)
            .show()));
    }

    private void buildPinPanel() {
        pinPanel = column();
        pinPanel.addView(label("STEP 2 OF 2", 12, ACCENT, true));
        pinPanel.addView(label("Find the exact button", 28, TEXT, true));
        pinPanel.addView(label("Send one code at a time and watch the fan. Save each code that does something.",
            15, MUTED, false));
        pinPanel.addView(gap(20));

        LinearLayout stepper = card();
        pinStep = label("", 26, TEXT, true);
        stepper.addView(pinStep);
        pinCode = label("", 12, MUTED, false);
        stepper.addView(pinCode);
        stepper.addView(gap(16));
        pinNext = pill("Next code", ACCENT, ON_ACCENT, 72, 20, () -> {
            setAutoStep(false);
            nextPin();
        });
        stepper.addView(pinNext);
        stepper.addView(gap(10));
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.addView(pill("Back one", CARD_HIGH, TEXT, 56, 16, () -> {
            setAutoStep(false);
            stepPin(-1);
        }), weighted(56, 0, 5));
        row.addView(pill("Send again", CARD_HIGH, TEXT, 56, 16, () -> {
            setAutoStep(false);
            stepPin(0);
        }), weighted(56, 5, 0));
        stepper.addView(row, matchWidth(0));
        stepper.addView(gap(10));
        autoToggle = pill("", CARD_HIGH, TEXT, 56, 15, () -> setAutoStep(!autoStep));
        stepper.addView(autoToggle);
        pinPanel.addView(stepper);
        pinPanel.addView(gap(16));
        setAutoStep(false);

        pinPanel.addView(pill("That one worked. Save it", AMBER, ON_AMBER, 72, 19, () -> askLabel()));
        pinPanel.addView(gap(8));
        pinPanel.addView(link("Nothing reacted. Keep scanning", () -> {
            show(SCREEN_SCAN);
            startScan();
        }));
        LinearLayout groups = new LinearLayout(this);
        groups.setOrientation(LinearLayout.HORIZONTAL);
        groups.addView(link("Previous group", () -> jumpGroup(-1)), weighted(48, 0, 0));
        groups.addView(link("Next group", () -> jumpGroup(1)), weighted(48, 0, 0));
        pinPanel.addView(groups, matchWidth(0));
    }

    private void buildRemotePanel() {
        remotePanel = column();
        remotePanel.addView(label("Fan remote", 28, TEXT, true));
        remotePanel.addView(label("The same buttons as your Luminous remote.", 15, MUTED, false));
        remotePanel.addView(gap(16));
        remoteGrid = column();
        remotePanel.addView(remoteGrid);
    }

    private void show(int screen) {
        if (screen != SCREEN_SCAN) {
            scanning = false;
        }
        if (screen != SCREEN_PIN) {
            setAutoStep(false);
        }
        scanPanel.setVisibility(screen == SCREEN_SCAN ? View.VISIBLE : View.GONE);
        pinPanel.setVisibility(screen == SCREEN_PIN ? View.VISIBLE : View.GONE);
        remotePanel.setVisibility(screen == SCREEN_REMOTE ? View.VISIBLE : View.GONE);
        styleNav(navFind, screen != SCREEN_REMOTE);
        styleNav(navRemote, screen == SCREEN_REMOTE);
    }

    // ---------- view helpers ----------

    private LinearLayout column() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    private LinearLayout card() {
        LinearLayout l = column();
        l.setBackground(rounded(CARD, 24));
        int pad = dp(20);
        l.setPadding(pad, pad, pad, pad);
        return l;
    }

    private TextView label(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) {
            t.setTypeface(Typeface.DEFAULT_BOLD);
        }
        t.setPadding(0, dp(2), 0, dp(2));
        return t;
    }

    private View tip(String number, String text) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(6), 0, dp(6));
        TextView badge = label(number, 14, ON_ACCENT, true);
        badge.setGravity(Gravity.CENTER);
        badge.setBackground(rounded(ACCENT, 14));
        badge.setPadding(0, 0, 0, 0);
        row.addView(badge, new LinearLayout.LayoutParams(dp(28), dp(28)));
        TextView body = label(text, 15, TEXT, false);
        body.setPadding(dp(12), 0, 0, 0);
        row.addView(body, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return row;
    }

    /** A filled, rounded button. */
    private TextView pill(String text, int fill, int textColor, int heightDp, int sp, final Runnable action) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(sp);
        t.setTextColor(textColor);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(12), 0, dp(12), 0);
        t.setMinHeight(dp(heightDp));
        t.setBackground(new RippleDrawable(ColorStateList.valueOf(0x40FFFFFF), rounded(fill, 20), null));
        t.setClickable(true);
        t.setFocusable(true);
        if (action != null) {
            t.setOnClickListener(v -> action.run());
        }
        return t;
    }

    /** A plain text button for secondary actions. */
    private TextView link(String text, final Runnable action) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(15);
        t.setTextColor(ACCENT);
        t.setGravity(Gravity.CENTER);
        t.setMinHeight(dp(48));
        t.setBackground(new RippleDrawable(ColorStateList.valueOf(0x30FFFFFF), null, rounded(0xFFFFFFFF, 16)));
        t.setClickable(true);
        t.setFocusable(true);
        t.setOnClickListener(v -> action.run());
        return t;
    }

    private TextView navItem(String text, final Runnable action) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(15);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setGravity(Gravity.CENTER);
        t.setMinHeight(dp(48));
        t.setClickable(true);
        t.setFocusable(true);
        t.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(48), 1f);
        lp.setMargins(dp(4), 0, dp(4), 0);
        t.setLayoutParams(lp);
        return t;
    }

    private void styleNav(TextView item, boolean selected) {
        item.setTextColor(selected ? ACCENT : MUTED);
        item.setBackground(selected ? rounded(CARD_HIGH, 16) : null);
    }

    private void setEnabled(View v, boolean enabled) {
        v.setEnabled(enabled);
        v.setAlpha(enabled ? 1f : 0.35f);
    }

    private Drawable rounded(int color, int radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radiusDp));
        return d;
    }

    private Drawable outline(int color, int radiusDp, boolean dashed) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(0x00000000);
        d.setCornerRadius(dp(radiusDp));
        if (dashed) {
            d.setStroke(dp(2), color, dp(6), dp(4));
        } else {
            d.setStroke(dp(2), color);
        }
        return d;
    }

    private TextView centered(TextView t) {
        t.setGravity(Gravity.CENTER);
        return t;
    }

    private View gap(int heightDp) {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(heightDp)));
        return v;
    }

    private LinearLayout.LayoutParams matchWidth(int topDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(topDp), 0, 0);
        return lp;
    }

    private LinearLayout.LayoutParams weighted(int heightDp, int leftDp, int rightDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(heightDp), 1f);
        lp.setMargins(dp(leftDp), 0, dp(rightDp), 0);
        return lp;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }
}
