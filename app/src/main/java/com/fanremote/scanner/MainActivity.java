package com.fanremote.scanner;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.hardware.ConsumerIrManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
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
 * Scan: sweeps every standard NEC code. Pinpoint: steps one code at a time
 * around the spot where the fan reacted. Remote: the codes saved as buttons.
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
    private boolean autoStep;

    private LinearLayout scanPanel;
    private LinearLayout pinPanel;
    private LinearLayout remotePanel;
    private LinearLayout remoteButtons;
    private TextView scanStatus;
    private TextView pinStatus;
    private ProgressBar scanProgress;
    private Button scanToggle;
    private Button autoToggle;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ir = (ConsumerIrManager) getSystemService(Context.CONSUMER_IR_SERVICE);
        prefs = getSharedPreferences("fan", MODE_PRIVATE);
        scanPos = clampPos(prefs.getInt("scanPos", 0));
        pinPos = clampPos(prefs.getInt("pinPos", 0));
        loadSaved();

        worker = new HandlerThread("ir-sender");
        worker.start();
        bg = new Handler(worker.getLooper());

        buildUi();
        showPanel(saved.isEmpty() ? scanPanel : remotePanel);
        refreshScan();
        refreshPin();
        refreshRemote();

        if (ir == null || !ir.hasIrEmitter()) {
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
        stopScan();
        setAutoStep(false);
        prefs.edit().putInt("scanPos", scanPos).putInt("pinPos", pinPos).apply();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        scanning = false;
        worker.quitSafely();
    }

    // ---------- sending ----------

    /** Sends one code and waits out the rest of the period so codes never overlap. */
    private void send(int address, int command) {
        long start = SystemClock.elapsedRealtime();
        try {
            ir.transmit(Nec.CARRIER_HZ, Nec.frame(address, command));
        } catch (final RuntimeException e) {
            scanning = false;
            ui.post(new Runnable() {
                @Override
                public void run() {
                    toast("Could not send: " + e.getMessage());
                }
            });
        }
        long spent = SystemClock.elapsedRealtime() - start;
        if (spent < CODE_PERIOD_MS) {
            SystemClock.sleep(CODE_PERIOD_MS - spent);
        }
    }

    private void sendAsync(final int pos) {
        if (ir == null) {
            return;
        }
        bg.post(new Runnable() {
            @Override
            public void run() {
                send(order[pos / Nec.COMMANDS], pos % Nec.COMMANDS);
            }
        });
    }

    private static int clampPos(int pos) {
        return Math.max(0, Math.min(TOTAL_CODES - 1, pos));
    }

    private String describe(int pos) {
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
                    ui.post(refreshScanTask);
                }
            }
            final boolean done = finished;
            ui.post(new Runnable() {
                @Override
                public void run() {
                    onScanStopped(done);
                }
            });
        }
    };

    private final Runnable refreshScanTask = new Runnable() {
        @Override
        public void run() {
            refreshScan();
        }
    };

    private void startScan() {
        if (scanning || ir == null || !ir.hasIrEmitter()) {
            return;
        }
        scanning = true;
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        bg.post(scanLoop);
        refreshScan();
    }

    private void stopScan() {
        scanning = false;
    }

    private void onScanStopped(boolean finished) {
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        prefs.edit().putInt("scanPos", scanPos).apply();
        refreshScan();
        if (finished) {
            new AlertDialog.Builder(this)
                .setTitle("Sweep finished")
                .setMessage("Every standard NEC code was sent. If the fan never reacted, it uses "
                    + "radio (RF) or a different infrared format, and this phone cannot control it.")
                .setPositiveButton("OK", null)
                .show();
        }
    }

    private void onReacted() {
        stopScan();
        pinPos = clampPos(scanPos - BACKTRACK_CODES);
        showPanel(pinPanel);
        refreshPin();
        toast("Stepping back " + BACKTRACK_CODES + " codes. Use Next to find the exact one.");
    }

    private void refreshScan() {
        int pos = scanPos;
        int remainingSec = (TOTAL_CODES - pos) * CODE_PERIOD_MS / 1000;
        String left = remainingSec >= 3600
            ? (remainingSec / 3600) + " h " + (remainingSec % 3600 / 60) + " min"
            : (remainingSec / 60) + " min";
        scanStatus.setText((scanning ? "Scanning\n" : "Paused\n")
            + "Address " + (pos / Nec.COMMANDS + 1) + " of " + Nec.ADDRESSES + "\n"
            + describe(pos) + "\n"
            + "At most " + left + " left");
        scanProgress.setProgress(pos);
        scanToggle.setText(scanning ? "Pause" : (pos == 0 ? "Start scan" : "Resume scan"));
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
            stepPin(1);
            ui.postDelayed(this, AUTO_STEP_MS);
        }
    };

    private void stepPin(int delta) {
        pinPos = clampPos(pinPos + delta);
        sendAsync(pinPos);
        refreshPin();
    }

    private void setAutoStep(boolean on) {
        autoStep = on;
        ui.removeCallbacks(autoStepTask);
        if (on) {
            ui.postDelayed(autoStepTask, AUTO_STEP_MS);
        }
        if (autoToggle != null) {
            autoToggle.setText(on ? "Stop auto-step" : "Auto-step forward (one every 1.5 s)");
        }
    }

    private void refreshPin() {
        pinStatus.setText(describe(pinPos));
    }

    private void askLabel() {
        setAutoStep(false);
        new AlertDialog.Builder(this)
            .setTitle("What did this code do?")
            .setItems(PRESETS, new android.content.DialogInterface.OnClickListener() {
                @Override
                public void onClick(android.content.DialogInterface dialog, int which) {
                    if (which == PRESETS.length - 1) {
                        askCustomLabel();
                    } else {
                        saveCode(PRESETS[which]);
                    }
                }
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void askCustomLabel() {
        final EditText input = new EditText(this);
        input.setHint("Button name");
        new AlertDialog.Builder(this)
            .setTitle("Name this button")
            .setView(input)
            .setPositiveButton("Save", new android.content.DialogInterface.OnClickListener() {
                @Override
                public void onClick(android.content.DialogInterface dialog, int which) {
                    String name = input.getText().toString().replace('\t', ' ').replace('\n', ' ').trim();
                    saveCode(name.isEmpty() ? "Button" : name);
                }
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void saveCode(String label) {
        saved.add(new String[] {label, String.valueOf(pinPos)});
        storeSaved();
        refreshRemote();
        toast("Saved \"" + label + "\". It is now on the Remote tab.");
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
        remoteButtons.removeAllViews();
        if (saved.isEmpty()) {
            remoteButtons.addView(text("No buttons yet. Find a code on the Scan tab, then save it from Pinpoint."));
            return;
        }
        for (final String[] entry : saved) {
            final int pos = Integer.parseInt(entry[1]);
            Button b = button(entry[0], new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    sendAsync(pos);
                }
            });
            b.setOnLongClickListener(new View.OnLongClickListener() {
                @Override
                public boolean onLongClick(View v) {
                    confirmDelete(entry);
                    return true;
                }
            });
            remoteButtons.addView(b);
        }
        remoteButtons.addView(text("Press and hold a button to delete it."));
    }

    private void confirmDelete(final String[] entry) {
        new AlertDialog.Builder(this)
            .setTitle("Delete \"" + entry[0] + "\"?")
            .setPositiveButton("Delete", new android.content.DialogInterface.OnClickListener() {
                @Override
                public void onClick(android.content.DialogInterface dialog, int which) {
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
        root.setFitsSystemWindows(true);
        int pad = dp(16);
        root.setPadding(pad, pad, pad, pad);

        LinearLayout tabs = new LinearLayout(this);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        tabs.addView(tab("Scan", new Runnable() {
            @Override
            public void run() {
                showPanel(scanPanel);
            }
        }));
        tabs.addView(tab("Pinpoint", new Runnable() {
            @Override
            public void run() {
                stopScan();
                showPanel(pinPanel);
            }
        }));
        tabs.addView(tab("Remote", new Runnable() {
            @Override
            public void run() {
                stopScan();
                showPanel(remotePanel);
            }
        }));
        root.addView(tabs);

        scanPanel = column();
        scanPanel.addView(text("Switch the fan on at the wall, point the top edge of the phone at it and "
            + "start. Watch the fan and its light, and listen for a beep. The moment anything "
            + "changes, tap \"It reacted\"."));
        scanStatus = text("");
        scanStatus.setTextSize(18);
        scanPanel.addView(scanStatus);
        scanProgress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        scanProgress.setMax(TOTAL_CODES);
        scanPanel.addView(scanProgress);
        scanToggle = button("Start scan", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (scanning) {
                    stopScan();
                } else {
                    startScan();
                }
            }
        });
        scanPanel.addView(scanToggle);
        Button reacted = button("It reacted", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onReacted();
            }
        });
        reacted.setMinHeight(dp(96));
        reacted.setTextSize(22);
        scanPanel.addView(reacted);
        scanPanel.addView(button("Start over from the first code", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                stopScan();
                scanPos = 0;
                refreshScan();
            }
        }));

        pinPanel = column();
        pinPanel.addView(text("Step through the codes one at a time. When one does something, save it "
            + "with a name. The fan's other buttons are usually nearby on the same address."));
        pinStatus = text("");
        pinStatus.setTextSize(20);
        pinPanel.addView(pinStatus);
        LinearLayout stepRow = new LinearLayout(this);
        stepRow.setOrientation(LinearLayout.HORIZONTAL);
        stepRow.addView(weighted(button("Previous", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setAutoStep(false);
                stepPin(-1);
            }
        })));
        stepRow.addView(weighted(button("Send again", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setAutoStep(false);
                stepPin(0);
            }
        })));
        stepRow.addView(weighted(button("Next", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setAutoStep(false);
                stepPin(1);
            }
        })));
        pinPanel.addView(stepRow);
        autoToggle = button("", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setAutoStep(!autoStep);
            }
        });
        pinPanel.addView(autoToggle);
        setAutoStep(false);
        pinPanel.addView(button("Save this code as a button", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                askLabel();
            }
        }));
        LinearLayout addrRow = new LinearLayout(this);
        addrRow.setOrientation(LinearLayout.HORIZONTAL);
        addrRow.addView(weighted(button("Previous address", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                jumpAddress(-1);
            }
        })));
        addrRow.addView(weighted(button("Next address", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                jumpAddress(1);
            }
        })));
        pinPanel.addView(addrRow);

        remotePanel = column();
        remoteButtons = column();
        remotePanel.addView(remoteButtons);

        LinearLayout panels = column();
        panels.addView(scanPanel);
        panels.addView(pinPanel);
        panels.addView(remotePanel);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(panels);
        root.addView(scroll);
        setContentView(root);
    }

    /** Moves to the first command of the neighbouring address without sending anything. */
    private void jumpAddress(int delta) {
        setAutoStep(false);
        int addressIndex = pinPos / Nec.COMMANDS + delta;
        addressIndex = Math.max(0, Math.min(Nec.ADDRESSES - 1, addressIndex));
        pinPos = addressIndex * Nec.COMMANDS;
        refreshPin();
    }

    private void showPanel(View panel) {
        if (panel != pinPanel) {
            setAutoStep(false);
        }
        scanPanel.setVisibility(panel == scanPanel ? View.VISIBLE : View.GONE);
        pinPanel.setVisibility(panel == pinPanel ? View.VISIBLE : View.GONE);
        remotePanel.setVisibility(panel == remotePanel ? View.VISIBLE : View.GONE);
    }

    private LinearLayout column() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    private TextView text(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(16);
        int pad = dp(8);
        t.setPadding(0, pad, 0, pad);
        return t;
    }

    private Button button(String label, View.OnClickListener onClick) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setMinHeight(dp(56));
        b.setOnClickListener(onClick);
        return b;
    }

    private View tab(String label, final Runnable action) {
        return weighted(button(label, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                action.run();
            }
        }));
    }

    private View weighted(View v) {
        v.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        return v;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }
}
