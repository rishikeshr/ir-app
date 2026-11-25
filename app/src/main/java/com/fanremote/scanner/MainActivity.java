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
    private static final String[] PRESETS = {
        "Fan on/off", "Light", "Speed 1", "Speed 2", "Speed 3", "Speed 4", "Speed 5",
        "Speed +", "Speed -", "Boost", "Timer", "Sleep", "Custom name..."
    };

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
        new AlertDialog.Builder(this)
            .setTitle("What did the fan do?")
            .setItems(PRESETS, (dialog, which) -> {
                if (which == PRESETS.length - 1) {
                    askName("Name this button", "", name -> saveCode(name));
                } else {
                    saveCode(PRESETS[which]);
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
        saved.add(new String[] {label, String.valueOf(pinPos)});
        storeSaved();
        refreshRemote();
        new AlertDialog.Builder(this)
            .setTitle("Saved \"" + label + "\"")
            .setMessage(first
                ? "The fan's other buttons are in the same group of 256 codes. "
                    + "Keep looking starts again from the beginning of that group."
                : "Keep looking carries on from this code.")
            .setPositiveButton("Keep looking", (dialog, which) -> {
                if (first) {
                    pinPos = pinPos / Nec.COMMANDS * Nec.COMMANDS;
                    pinStart = -1;
                    pinFresh = true;
                    refreshPin();
                }
            })
            .setNegativeButton("Open remote", (dialog, which) -> show(SCREEN_REMOTE))
            .show();
    }

    /** Moves to the first code of the neighbouring group without sending anything. */
    private void jumpGroup(int delta) {
        setAutoStep(false);
        int group = Math.max(0, Math.min(Nec.ADDRESSES - 1, pinPos / Nec.COMMANDS + delta));
        pinPos = group * Nec.COMMANDS;
        pinStart = -1;
        pinFresh = true;
        refreshPin();
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
        if (saved.isEmpty()) {
            LinearLayout empty = card();
            empty.addView(label("No buttons yet", 20, TEXT, true));
            empty.addView(gap(6));
            empty.addView(label("Scan for your fan's codes first. Each code you save shows up here as a button.",
                15, MUTED, false));
            empty.addView(gap(16));
            empty.addView(pill("Find codes", ACCENT, ON_ACCENT, 56, 17, () -> show(SCREEN_SCAN)));
            remoteGrid.addView(empty);
            return;
        }
        LinearLayout row = null;
        for (int i = 0; i < saved.size(); i++) {
            final String[] entry = saved.get(i);
            final int pos = Integer.parseInt(entry[1]);
            if (i % 2 == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                remoteGrid.addView(row, matchWidth(0));
            }
            boolean main = i == 0;
            TextView key = pill(entry[0], main ? ACCENT : CARD_HIGH, main ? ON_ACCENT : TEXT, 96, 18, null);
            key.setOnClickListener(v -> {
                v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                sendAsync(pos);
            });
            key.setOnLongClickListener(v -> {
                editEntry(entry);
                return true;
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(96), 1f);
            lp.setMargins(dp(5), dp(5), dp(5), dp(5));
            row.addView(key, lp);
        }
        if (saved.size() % 2 == 1) {
            View filler = new View(this);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(96), 1f);
            lp.setMargins(dp(5), dp(5), dp(5), dp(5));
            row.addView(filler, lp);
        }
        remoteGrid.addView(gap(12));
        remoteGrid.addView(link("Add another button", () -> show(SCREEN_PIN)));
        remoteGrid.addView(label("Press and hold a button to rename or delete it.", 13, MUTED, false));
    }

    private void editEntry(final String[] entry) {
        new AlertDialog.Builder(this)
            .setTitle(entry[0])
            .setItems(new String[] {"Rename", "Delete"}, (dialog, which) -> {
                if (which == 0) {
                    askName("Rename button", entry[0], name -> {
                        entry[0] = name;
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
        remotePanel.addView(label("Point the top edge of the phone at the fan.", 15, MUTED, false));
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
