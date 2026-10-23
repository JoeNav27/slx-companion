package com.muse.slxcompanion;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.media.AudioAttributes;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.List;
import java.util.Locale;

/**
 * Course GPS screen (v0.2).
 *
 * Search golfclubxy (free, keyless, OpenStreetMap data), download a
 * course once, and it is cached on this phone. Distances are computed
 * here from the phone GPS to the center of each green — never to a
 * server — and can be spoken aloud (the audio routes to the SLX
 * speaker when it is the paired Bluetooth audio device).
 */
public class GpsActivity extends Activity {

    private static final int REQ_LOC = 77;
    private static final int REQ_BT = 43;
    private static final long SCAN_TIMEOUT_MS = 15000;
    private static final long POLL_MS = 5000;
    private static final double AUTO_ADVANCE_YD = 25.0;
    private static final double M_PER_YD = 0.9144;

    private final Handler ui = new Handler(Looper.getMainLooper());

    private TextView courseNameText, holeText, distText, distUnitText, holeInfoText, pickerStatus;
    private LinearLayout pickerBox, resultsBox, savedBox;
    private EditText searchInput;
    private Button connectBtn;
    private HoleView holeView;
    private volatile HoleGeom geom;
    private volatile boolean geomSettled;
    private static final int PIN_FRONT = 0, PIN_CENTER = 1, PIN_BACK = 2;
    private int pinMode = PIN_CENTER;
    private Button pinFrontBtn, pinCenterBtn, pinBackBtn;

    // BLE link (owned here since this screen became the app's home).
    private BluetoothAdapter adapter;
    private BluetoothLeScanner scanner;
    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic rxChar;
    private boolean bleConnected = false;
    private boolean scanning = false;
    private boolean polling = false;

    private boolean pushToDevice = false;
    // Display feed is always on while the app is open (user, 2026-10-22):
    // no on/off button; it starts as soon as a course + link exist.

    private CourseApi.Course course;
    private int holeIdx = 0;
    private boolean autoAdvancedForHole = false;
    // Yards only, per the user (2026-10-22) — no units toggle.

    private LocationManager lm;
    private Location lastLoc;
    private TextToSpeech tts;
    private boolean ttsReady = false;

    // ------------------------------------------------------------------ UI

    private TextView tv(String text, float sp, boolean bold) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(sp);
        if (bold) t.setTypeface(null, Typeface.BOLD);
        t.setPadding(0, 6, 0, 6);
        return t;
    }

    private Button btn(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        return b;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();

        // Arm the EQ session listener for the life of the process.
        EqHolder.arm(this);

        BluetoothManager bm = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        if (bm != null) adapter = bm.getAdapter();
        if (adapter == null) connectBtn.setEnabled(false);

        tts = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) {
                tts.setLanguage(Locale.US);
                // Media stream: follows the phone's media output, i.e.
                // the SLX speaker when it is the paired audio device.
                tts.setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build());
                ttsReady = true;
            }
        });

        lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);

        refreshSavedList();
        long lastId = getPrefs().getLong("last_course", -1);
        if (lastId >= 0 && lastId != TEST_ID && courseFile(lastId).exists()) {
            loadCourseFromCache(lastId, false);
        } else {
            pickerBox.setVisibility(View.VISIBLE);
        }
        ensureLocation();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(40, 40, 40, 40);

        TextView title = tv("Course GPS", 26, true);
        title.setTextColor(0xFF1B5E20);
        root.addView(title);

        connectBtn = btn("Connect to SLX");
        connectBtn.setOnClickListener(v -> {
            if (bleConnected || gatt != null) disconnect();
            else if (scanning) {
                stopScan();
                connectBtn.setText("Connect to SLX");
            } else startConnect();
        });
        root.addView(connectBtn);

        Button musicBtn = btn("Music EQ");
        musicBtn.setOnClickListener(v -> startActivity(new Intent(this, MusicActivity.class)));
        root.addView(musicBtn);

        Button changeBtn = btn("Change/Download Course");
        changeBtn.setOnClickListener(v ->
                pickerBox.setVisibility(pickerBox.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE));
        root.addView(changeBtn);

        courseNameText = tv("No course loaded", 26, true);
        root.addView(courseNameText);

        // ---- course picker ----
        pickerBox = new LinearLayout(this);
        pickerBox.setOrientation(LinearLayout.VERTICAL);
        pickerBox.setVisibility(View.GONE);
        searchInput = new EditText(this);
        searchInput.setHint("Course name, e.g. Pala Mesa");
        searchInput.setSingleLine(true);
        pickerBox.addView(searchInput);
        Button searchBtn = btn("Search courses");
        searchBtn.setOnClickListener(v -> doSearch());
        pickerBox.addView(searchBtn);
        pickerStatus = tv("", 13, false);
        pickerBox.addView(pickerStatus);
        resultsBox = new LinearLayout(this);
        resultsBox.setOrientation(LinearLayout.VERTICAL);
        pickerBox.addView(resultsBox);
        pickerBox.addView(tv("Downloaded on this phone:", 14, true));
        savedBox = new LinearLayout(this);
        savedBox.setOrientation(LinearLayout.VERTICAL);
        pickerBox.addView(savedBox);
        root.addView(pickerBox);

        // ---- play view ----
        LinearLayout holeRow = new LinearLayout(this);
        holeRow.setOrientation(LinearLayout.HORIZONTAL);
        holeRow.setGravity(Gravity.CENTER_HORIZONTAL);
        Button prev = btn("◀ Prev");
        prev.setOnClickListener(v -> setHole(holeIdx - 1, true));
        holeText = tv("Hole —", 22, true);
        holeText.setGravity(Gravity.CENTER_HORIZONTAL);
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        holeText.setLayoutParams(hp);
        Button next = btn("Next ▶");
        next.setOnClickListener(v -> setHole(holeIdx + 1, true));
        holeRow.addView(prev);
        holeRow.addView(holeText);
        holeRow.addView(next);
        root.addView(holeRow);

        distText = tv("—", 84, true);
        distText.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(distText);
        distUnitText = tv("yards to green", 16, false);
        distUnitText.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(distUnitText);

        holeInfoText = tv("", 15, false);
        holeInfoText.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(holeInfoText);

        LinearLayout pinRow = new LinearLayout(this);
        pinRow.setOrientation(LinearLayout.HORIZONTAL);
        pinFrontBtn = btn("Front Pin");
        pinCenterBtn = btn("Center Pin");
        pinBackBtn = btn("Back Pin");
        for (Button b : new Button[]{pinFrontBtn, pinCenterBtn, pinBackBtn}) {
            b.setLayoutParams(new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            pinRow.addView(b);
        }
        pinFrontBtn.setOnClickListener(v -> setPinMode(PIN_FRONT));
        pinCenterBtn.setOnClickListener(v -> setPinMode(PIN_CENTER));
        pinBackBtn.setOnClickListener(v -> setPinMode(PIN_BACK));
        root.addView(pinRow);
        stylePinButtons();

        holeView = new HoleView(this);
        int previewH = Math.round(540 * getResources().getDisplayMetrics().density);
        holeView.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, previewH));
        holeView.setVisibility(View.GONE);
        root.addView(holeView);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        setContentView(scroll);
    }

    // ------------------------------------------------------------- location

    private void ensureLocation() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, REQ_LOC);
            return;
        }
        startLocationUpdates();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_LOC) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startLocationUpdates();
            } else {
                toast("Location permission denied — distances need it.");
            }
        }
        if (requestCode == REQ_BT) {
            if (hasBtPerms()) startConnect();
            else toast("Bluetooth permission denied — can't reach the SLX without it.");
        }
    }

    private final LocationListener locListener = new LocationListener() {
        @Override public void onLocationChanged(Location loc) {
            lastLoc = loc;
            updateDisplay();
            ensurePush();
            maybeAutoAdvance(loc);
        }
        @Override public void onProviderEnabled(String p) {}
        @Override public void onProviderDisabled(String p) {}
        @Override public void onStatusChanged(String p, int s, Bundle e) {}
    };

    private void startLocationUpdates() {
        if (lm == null) return;
        try {
            if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000, 2f, locListener);
            }
            if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 2000, 5f, locListener);
            }
            Location seed = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            if (seed == null) seed = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
            if (seed != null) { lastLoc = seed; updateDisplay(); }
        } catch (SecurityException ignored) {}
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (lm != null) { try { lm.removeUpdates(locListener); } catch (Exception ignored) {} }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            startLocationUpdates();
        }
        ensurePush();
    }

    @Override
    protected void onDestroy() {
        disconnect();
        stopPush();
        if (tts != null) { tts.stop(); tts.shutdown(); }
        super.onDestroy();
    }

    // ------------------------------------------------------------- distance

    private CourseApi.Hole currentHole() {
        if (course == null || course.holes.isEmpty()) return null;
        return course.holes.get(Math.max(0, Math.min(holeIdx, course.holes.size() - 1)));
    }

    private double distToGreenMeters(Location loc, CourseApi.Hole h) {
        float[] out = new float[1];
        Location.distanceBetween(loc.getLatitude(), loc.getLongitude(), h.greenLat, h.greenLon, out);
        return out[0];
    }

    private String fmt(double meters) {
        return String.valueOf(Math.round(meters / M_PER_YD));
    }

    private void updateDisplay() {
        CourseApi.Hole h = currentHole();
        if (h == null) {
            holeText.setText("Hole —");
            distText.setText("—");
            holeInfoText.setText("");
            holeView.setVisibility(View.GONE);
            return;
        }
        holeText.setText("Hole " + h.number);
        String info = "Par " + (h.par > 0 ? h.par : "—");
        if (h.yards > 0) info += "  ·  " + Math.round(h.yards) + " yd from tee";
        if (h.handicap > 0) info += "  ·  Hcp " + h.handicap;
        holeInfoText.setText(info);
        distUnitText.setText(pinMode == PIN_FRONT ? "yards to front pin"
                : pinMode == PIN_BACK ? "yards to back pin" : "yards to center pin");

        if (lastLoc == null) {
            distText.setText("···");
        } else {
            double d = distToTargetMeters(lastLoc, h);
            distText.setText(fmt(d));
        }
        updateHolePreview(h);
    }

    private void setPinMode(int mode) {
        pinMode = mode;
        stylePinButtons();
        lastSentYd = -1;   // force the device feed to re-send at the new target
        lastValueMs = 0;
        updateDisplay();
    }

    private void stylePinButtons() {
        Button[] bs = {pinFrontBtn, pinCenterBtn, pinBackBtn};
        for (int i = 0; i < bs.length; i++) {
            if (bs[i] == null) continue;
            boolean sel = i == pinMode;
            bs[i].setBackgroundColor(sel ? 0xFF1B5E20 : 0xFFE0E0E0);
            bs[i].setTextColor(sel ? 0xFFFFFFFF : 0xFF212121);
        }
    }

    /**
     * Distance in meters from the player to the selected pin point.
     * Center is the mapped green center. Front/back are the green
     * shape's near/far edges along the line from the player through
     * the center — the way golf GPS computes them — and need the
     * hole's mapped green shape; without one they fall back to the
     * center distance.
     */
    private double distToTargetMeters(Location loc, CourseApi.Hole h) {
        if (pinMode == PIN_CENTER) return distToGreenMeters(loc, h);
        HoleGeom g = geom;
        if (g == null) return distToGreenMeters(loc, h);
        HoleGeom.Shape sh = g.byHole.get(h.number);
        if (sh == null || sh.greens.isEmpty()) return distToGreenMeters(loc, h);
        double kx = 111320.0 * Math.cos(Math.toRadians(loc.getLatitude()));
        double cx = (h.greenLon - loc.getLongitude()) * kx;
        double cy = (h.greenLat - loc.getLatitude()) * 110540.0;
        double cl = Math.hypot(cx, cy);
        if (cl < 1) return cl;
        double ux = cx / cl, uy = cy / cl;
        double best = pinMode == PIN_FRONT ? Double.MAX_VALUE : -Double.MAX_VALUE;
        for (double[] v : sh.greens.get(0)) {
            double px = (v[1] - loc.getLongitude()) * kx;
            double py = (v[0] - loc.getLatitude()) * 110540.0;
            double proj = px * ux + py * uy;
            best = pinMode == PIN_FRONT ? Math.min(best, proj) : Math.max(best, proj);
        }
        return Math.max(0, best);
    }

    /** Shows the current hole's picture (OSM shapes), or a simple
     *  tee -> green schematic when no shapes are mapped. */
    private void updateHolePreview(CourseApi.Hole h) {
        if (!geomSettled) {
            // Shapes still loading: keep the space blank white rather
            // than showing a half-loaded picture.
            holeView.setVisibility(View.INVISIBLE);
            return;
        }
        HoleGeom.Shape s = null;
        HoleGeom g = geom;
        if (g != null) s = g.byHole.get(h.number);
        if (s == null || !s.hasArt()) {
            if (h.teeLat != 0 || h.teeLon != 0) {
                s = new HoleGeom.Shape();
                s.line.add(new double[]{h.teeLat, h.teeLon});
                s.line.add(new double[]{h.greenLat, h.greenLon});
            } else {
                s = null;
            }
        }
        if (s != null && s.hasArt()) {
            holeView.setShape(s);
            holeView.setVisibility(View.VISIBLE);
        } else {
            holeView.setVisibility(View.GONE);
        }
    }

    private void maybeAutoAdvance(Location loc) {
        CourseApi.Hole h = currentHole();
        if (h == null || course == null) return;
        if (autoAdvancedForHole) return;
        if (loc.hasAccuracy() && loc.getAccuracy() > 30) return; // don't jump on a sloppy fix
        if (holeIdx >= course.holes.size() - 1) return;
        double dYd = distToGreenMeters(loc, h) / M_PER_YD;
        if (dYd <= AUTO_ADVANCE_YD) {
            autoAdvancedForHole = true;
            ui.postDelayed(() -> {
                setHole(holeIdx + 1, false);
                speakDistance();
            }, 1200);
        }
    }

    private void setHole(int idx, boolean manual) {
        if (course == null) return;
        int clamped = Math.max(0, Math.min(idx, course.holes.size() - 1));
        if (clamped == holeIdx && !manual) return;
        holeIdx = clamped;
        autoAdvancedForHole = false;
        updateDisplay();
        // Stock behaviour on a hole change: restart the record set with
        // a zero reset for rec 1, then rebuild the window one by one.
        if (pushToDevice) {
            stopPush();
            startPush();
        }
    }

    // ------------------------------------------------- device display push

    /**
     * Feeds the SLX display. Field lessons:
     *  - v0.4/v0.5: the device displays the record it received last,
     *    and the rec number IS the hole number it shows (sending rec 1
     *    forever parked it on "1H" even when the app changed holes).
     *    So: send rec = the current hole's real number.
     *  - v0.5: the yardage restarts its count-up on every incoming
     *    record — at 1 send/sec it reset every second and never
     *    settled. So: send ONLY on real change (>= 3 yd), at most
     *    every 5 s, with a 30 s heartbeat — long quiet stretches let
     *    the number land and stay.
     * Record format is byte-verified against the stock capture
     * (raw 0112200202 = rec 1, 464.0 yd, BE). A hole (re)start begins
     * with that hole's rec as value 0 / flag 0x02, like the stock
     * app's set reset; live values use flag 0x01.
     *
     * v0.6 lesson: holes changed (addressing right) but NO yardage
     * appeared. Two deltas vs the stock stream, fixed in v0.7:
     * (1) v0.6 sent the zero reset and the live value back-to-back in
     *     the same instant; stock leaves ~0.5 s between them.
     * (2) the stock app precedes every round's records with its Start
     *     sequence (0x5a x3 + 0x17 "round active"); we never sent it,
     *     so the device may not have been in a state that renders
     *     values at all.
     * Value cadence now follows stock: ~every 2 s while the value is
     * changing, 30 s heartbeat when static.
     */
    private int lastSentHole = -1;
    private double lastSentYd = -1;
    private long lastValueMs = 0;
    private long resetMs = 0;

    private final Runnable pushTick = new Runnable() {
        @Override public void run() {
            if (!pushToDevice) return;
            maybeSendRecord();
            ui.postDelayed(this, 500);
        }
    };

    private void startPush() {
        if (course == null) {
            pushToDevice = false;
            return;
        }
        lastSentHole = -1;
        lastSentYd = -1;
        lastValueMs = 0;
        resetMs = 0;
        if (SlxLink.isLive()) {
            // Stock round-start sequence, byte-for-byte from the capture.
            SlxLink.send(Slx.cmdRoundStartA());
            SlxLink.send(Slx.cmdRoundStartA());
            SlxLink.send(Slx.cmdRoundActive());
            SlxLink.send(Slx.cmdRoundStartA());
        }
        ui.removeCallbacks(pushTick);
        ui.postDelayed(pushTick, 350);
    }

    private void stopPush() {
        ui.removeCallbacks(pushTick);
    }

    private void maybeSendRecord() {
        if (course == null || lastLoc == null) return;
        if (!SlxLink.isLive()) {
            return;
        }
        CourseApi.Hole h = currentHole();
        if (h == null) return;
        double yd = Math.min(999.0, distToTargetMeters(lastLoc, h) / M_PER_YD);
        long now = System.currentTimeMillis();

        if (h.number != lastSentHole) {
            // Hole (re)start: zero reset now; the value follows after a
            // ~0.5 s gap on a later tick, as in the stock stream.
            SlxLink.send(Slx.distanceRecord(h.number, 0, 0x02));
            lastSentHole = h.number;
            lastSentYd = -1;
            resetMs = now;
            return;
        }
        // v0.9 field result: flag 0x02 on live values changed nothing —
        // the sweep-from-0 is the device's own rendering of any fresh
        // record (it's a swing speedometer at heart; the stock app
        // feeds it the same records and gets the same sweep). The
        // lever we actually have is cadence: only update in >= 5 yd
        // steps so the number finishes its sweep and then sits —
        // standing at the ball (when it's read) it never re-sends.
        boolean changed = lastSentYd < 0 || Math.abs(yd - lastSentYd) >= 5.0;
        boolean due = now - lastValueMs >= 2000;
        boolean heartbeat = now - lastValueMs >= 30000;
        if (!((changed && due) || heartbeat)) return;
        if (now - resetMs < 500) return; // keep the reset -> value gap
        // Live values at flag 0x02 (matches the stock stream's settled
        // values; 0x01 vs 0x02 made no difference to the display sweep).
        SlxLink.send(Slx.distanceRecord(h.number, yd, 0x02));
        lastSentYd = yd;
        lastValueMs = now;
    }

    // ------------------------------------------------------------------ TTS

    private void speakDistance() {
        CourseApi.Hole h = currentHole();
        if (h == null) { toast("Load a course first"); return; }
        if (!ttsReady) { toast("Voice not ready yet"); return; }
        if (lastLoc == null) { say("Waiting for a GPS fix."); return; }
        double d = distToGreenMeters(lastLoc, h);
        long v = Math.round(d / M_PER_YD);
        say("Hole " + h.number + ". " + v + " yards to the green.");
    }

    private void say(String text) {
        if (ttsReady) tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "dist");
    }

    // --------------------------------------------------------------- courses

    private SharedPreferences getPrefs() {
        return getSharedPreferences("slx", Context.MODE_PRIVATE);
    }

    private File courseFile(long id) {
        File dir = new File(getFilesDir(), "courses");
        if (!dir.exists()) dir.mkdirs();
        return new File(dir, id + ".json");
    }

    private void doSearch() {
        String q = searchInput.getText().toString().trim();
        if (q.isEmpty()) { toast("Type a course name first"); return; }
        pickerStatus.setText("Searching…");
        resultsBox.removeAllViews();
        new Thread(() -> {
            try {
                List<CourseApi.CourseRef> refs = CourseApi.search(q);
                ui.post(() -> {
                    pickerStatus.setText(refs.isEmpty()
                            ? "No matches. Try a shorter name."
                            : refs.size() + " match(es) — tap to download:");
                    for (CourseApi.CourseRef ref : refs) {
                        Button b = btn(ref.name + "  ·  " + ref.holes + " holes  ·  data " + ref.tier
                                + ("full".equals(ref.status) ? "" : " (" + ref.status + ")"));
                        b.setOnClickListener(v -> downloadCourse(ref));
                        resultsBox.addView(b);
                    }
                });
            } catch (Exception e) {
                ui.post(() -> pickerStatus.setText("Search failed: " + e.getMessage() + " (needs internet, once per course)"));
            }
        }).start();
    }

    private void downloadCourse(CourseApi.CourseRef ref) {
        pickerStatus.setText("Downloading " + ref.name + "…");
        new Thread(() -> {
            try {
                String json = CourseApi.fetchCourseJson(ref.id);
                CourseApi.Course parsed = CourseApi.parseCourse(json);
                FileWriter w = new FileWriter(courseFile(ref.id));
                w.write(json);
                w.close();
                rememberCourse(ref.id, parsed.name);
                ui.post(() -> {
                    pickerStatus.setText("Saved " + parsed.name + " (" + parsed.holes.size() + " holes) — works offline now.");
                    setCourse(parsed);
                    refreshSavedList();
                });
            } catch (Exception e) {
                ui.post(() -> pickerStatus.setText("Download failed: " + e.getMessage()));
            }
        }).start();
    }

    private void rememberCourse(long id, String name) {
        try {
            JSONArray arr = new JSONArray(getPrefs().getString("saved_courses", "[]"));
            JSONArray next = new JSONArray();
            next.put(new JSONObject().put("id", id).put("name", name));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                if (o.getLong("id") != id) next.put(o);
            }
            getPrefs().edit().putString("saved_courses", next.toString()).putLong("last_course", id).apply();
        } catch (Exception ignored) {}
    }

    private void refreshSavedList() {
        savedBox.removeAllViews();
        try {
            JSONArray arr = new JSONArray(getPrefs().getString("saved_courses", "[]"));
            if (arr.length() == 0) savedBox.addView(tv("None yet — search above.", 13, false));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                long id = o.getLong("id");
                if (id == TEST_ID) continue; // retired test course stays hidden
                LinearLayout row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                Button b = btn(o.getString("name"));
                b.setLayoutParams(new LinearLayout.LayoutParams(
                        0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
                b.setOnClickListener(v -> loadCourseFromCache(id, true));
                row.addView(b);
                Button del = btn("✕");
                del.setOnClickListener(v -> deleteCourse(id));
                row.addView(del);
                savedBox.addView(row);
            }
        } catch (Exception ignored) {}
    }

    /** Removes a downloaded course: list entry, data file, shape cache. */
    private void deleteCourse(long id) {
        try {
            JSONArray arr = new JSONArray(getPrefs().getString("saved_courses", "[]"));
            JSONArray kept = new JSONArray();
            String name = null;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                if (o.getLong("id") == id) name = o.optString("name", "");
                else kept.put(o);
            }
            getPrefs().edit().putString("saved_courses", kept.toString()).apply();
            if (getPrefs().getLong("last_course", -1) == id) {
                getPrefs().edit().remove("last_course").apply();
            }
            courseFile(id).delete();
            geomFile(id).delete();
            refreshSavedList();
            toast("Deleted " + (name != null ? name : "course"));
        } catch (Exception ignored) {}
    }

    private void loadCourseFromCache(long id, boolean fromTap) {
        new Thread(() -> {
            try {
                BufferedReader r = new BufferedReader(new FileReader(courseFile(id)));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = r.readLine()) != null) sb.append(line);
                r.close();
                CourseApi.Course parsed = CourseApi.parseCourse(sb.toString());
                getPrefs().edit().putLong("last_course", id).apply();
                ui.post(() -> setCourse(parsed));
            } catch (Exception e) {
                ui.post(() -> toast("Couldn't load that course: " + e.getMessage()));
            }
        }).start();
    }

    private void setCourse(CourseApi.Course c) {
        course = c;
        holeIdx = 0;
        autoAdvancedForHole = false;
        courseNameText.setText(c.name);
        pickerBox.setVisibility(View.GONE);
        geom = null;
        geomSettled = false;
        updateDisplay();
        ensurePush();
        loadGeometry(c);
    }

    private File geomFile(long id) {
        File dir = new File(getFilesDir(), "courses");
        if (!dir.exists()) dir.mkdirs();
        return new File(dir, id + ".geom.json");
    }

    /**
     * Loads the hole-preview shapes for a course: from its cache file
     * when present, otherwise fetched once from Overpass and cached.
     * Best effort — on any failure there is simply no picture.
     */
    private void loadGeometry(final CourseApi.Course c) {
        geom = null;
        geomSettled = false;
        if (c.id == TEST_ID) {
            geomSettled = true;
            return;
        }
        double s = 90, w = 180, n = -90, e = -180;
        for (CourseApi.Hole h : c.holes) {
            // Only real points: a hole with no mapped tee has tee = 0,0,
            // which would blow the box up to the equator and kill the fetch.
            s = Math.min(s, h.greenLat);
            n = Math.max(n, h.greenLat);
            w = Math.min(w, h.greenLon);
            e = Math.max(e, h.greenLon);
            if (h.teeLat != 0 || h.teeLon != 0) {
                s = Math.min(s, h.teeLat);
                n = Math.max(n, h.teeLat);
                w = Math.min(w, h.teeLon);
                e = Math.max(e, h.teeLon);
            }
        }
        final double fs = s - 0.004, fw = w - 0.004, fn = n + 0.004, fe = e + 0.004;
        final long courseId = c.id;
        new Thread(() -> {
            try {
                File f = geomFile(courseId);
                String source = "download";
                HoleGeom parsed = null;
                if (f.exists()) {
                    StringBuilder sb = new StringBuilder();
                    BufferedReader r = new BufferedReader(new FileReader(f));
                    String line;
                    while ((line = r.readLine()) != null) sb.append(line);
                    r.close();
                    try {
                        parsed = HoleGeom.parse(sb.toString(), c);
                        source = "cache";
                    } catch (Exception cacheBad) {
                        // Unusable cache (e.g. an error page saved by an
                        // older build): drop it and refetch below.
                        f.delete();
                        parsed = null;
                    }
                }
                if (parsed == null) {
                    String json = HoleGeom.fetch(fs, fw, fn, fe);
                    FileWriter wr = new FileWriter(f);
                    wr.write(json);
                    wr.close();
                    parsed = HoleGeom.parse(json, c);
                }
                final HoleGeom result = parsed;
                if (course != null && course.id == courseId) {
                    geom = result;
                    geomSettled = true;
                    ui.post(this::updateDisplay);
                }
            } catch (Exception ex) {
                final String msg = ex.getMessage() != null
                        ? ex.getMessage() : ex.getClass().getSimpleName();
                ui.post(() -> {
                    geomSettled = true;
                    updateDisplay();
                    toast("Hole pictures failed: " + msg);
                });
            }
        }).start();
    }

    /** Always-on display feed: starts whenever a course and the link exist. */
    private void ensurePush() {
        if (course != null && !pushToDevice && SlxLink.isLive()) {
            pushToDevice = true;
            startPush();
        }
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    // The synthetic TEST course (walk-test era) is retired — user,
    // 2026-10-22: it kept coming up. Its id stays defined only so the
    // saved list and startup can filter out copies saved earlier.
    private static final long TEST_ID = 999000341L;

    // ------------------------------------------------------------ BLE link
    // The connection engine lives here since this screen became the
    // app's home (user, 2026-10-22 — the old landing page is gone).
    // Same session pattern as the stock app: enable notifications,
    // fetch config, poll every 5 s; the link is published to SlxLink
    // so the display feed can send its records.

    private boolean hasBtPerms() {
        if (Build.VERSION.SDK_INT >= 31) {
            return checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
                    && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
        }
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private void startConnect() {
        if (adapter == null) return;
        if (!adapter.isEnabled()) {
            toast("Bluetooth is off — turn it on, then tap Connect.");
            return;
        }
        if (!hasBtPerms()) {
            if (Build.VERSION.SDK_INT >= 31) {
                requestPermissions(new String[]{
                        Manifest.permission.BLUETOOTH_SCAN,
                        Manifest.permission.BLUETOOTH_CONNECT}, REQ_BT);
            } else {
                requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, REQ_BT);
            }
            return;
        }
        scanner = adapter.getBluetoothLeScanner();
        if (scanner == null) {
            toast("BLE scanner unavailable.");
            return;
        }
        connectBtn.setText("Cancel");
        scanning = true;
        try {
            scanner.startScan(null,
                    new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),
                    scanCallback);
        } catch (SecurityException e) {
            scanning = false;
            connectBtn.setText("Connect to SLX");
            toast("Bluetooth permission missing.");
            return;
        }
        ui.postDelayed(() -> {
            if (scanning) {
                stopScan();
                connectBtn.setText("Connect to SLX");
                toast("No SLX found — is it on and not connected to another app?");
            }
        }, SCAN_TIMEOUT_MS);
    }

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            BluetoothDevice dev = result.getDevice();
            String name = null;
            try { name = dev.getName(); } catch (SecurityException ignored) {}
            if (name == null && result.getScanRecord() != null) {
                name = result.getScanRecord().getDeviceName();
            }
            boolean macMatch = Slx.KNOWN_MAC.equalsIgnoreCase(dev.getAddress());
            boolean nameMatch = name != null && name.startsWith(Slx.NAME_PREFIX);
            if (!macMatch && !nameMatch) return;
            stopScan();
            connectBtn.setText("Connecting…");
            try {
                if (Build.VERSION.SDK_INT >= 26) {
                    gatt = dev.connectGatt(GpsActivity.this, false, gattCallback, BluetoothDevice.TRANSPORT_LE);
                } else {
                    gatt = dev.connectGatt(GpsActivity.this, false, gattCallback);
                }
            } catch (SecurityException e) {
                connectBtn.setText("Connect to SLX");
                toast("Bluetooth permission missing.");
            }
        }

        @Override
        public void onScanFailed(int errorCode) {
            scanning = false;
            connectBtn.setText("Connect to SLX");
            toast("Scan failed (" + errorCode + ").");
        }
    };

    private void stopScan() {
        if (scanning && scanner != null) {
            try { scanner.stopScan(scanCallback); } catch (Exception ignored) {}
        }
        scanning = false;
    }

    private void disconnect() {
        stopPolling();
        stopScan();
        pushToDevice = false;
        SlxLink.clear();
        if (gatt != null) {
            try { gatt.disconnect(); } catch (Exception ignored) {}
            try { gatt.close(); } catch (Exception ignored) {}
            gatt = null;
        }
        bleConnected = false;
        rxChar = null;
        if (connectBtn != null) ui.post(() -> connectBtn.setText("Connect to SLX"));
    }

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                bleConnected = true;
                ui.post(() -> connectBtn.setText("Disconnect"));
                try { g.discoverServices(); } catch (SecurityException ignored) {}
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                bleConnected = false;
                stopPolling();
                pushToDevice = false;
                SlxLink.clear();
                rxChar = null;
                ui.post(() -> connectBtn.setText("Connect to SLX"));
                try { g.close(); } catch (Exception ignored) {}
                if (gatt == g) gatt = null;
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt g, int status) {
            BluetoothGattService svc = g.getService(Slx.NUS_SERVICE);
            if (svc == null) {
                ui.post(() -> toast("Connected, but this isn't an SLX."));
                return;
            }
            rxChar = svc.getCharacteristic(Slx.NUS_RX);
            BluetoothGattCharacteristic txChar = svc.getCharacteristic(Slx.NUS_TX);
            if (rxChar == null || txChar == null) {
                ui.post(() -> toast("UART characteristics missing."));
                return;
            }
            try { g.setCharacteristicNotification(txChar, true); } catch (SecurityException ignored) {}
            BluetoothGattDescriptor ccc = txChar.getDescriptor(Slx.CCC);
            if (ccc != null) writeCcc(g, ccc);
            else onLinkReady();
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor d, int status) {
            if (Slx.CCC.equals(d.getUuid())) onLinkReady();
        }
    };

    /** Notifications are live: publish the link, fetch config, poll like the stock app. */
    private void onLinkReady() {
        SlxLink.attach(gatt, rxChar);
        sendBle(Slx.getConfig());
        startPolling();
        ensurePush();
    }

    private void startPolling() {
        if (polling) return;
        polling = true;
        ui.post(pollTask);
    }

    private void stopPolling() {
        polling = false;
        ui.removeCallbacks(pollTask);
    }

    private final Runnable pollTask = new Runnable() {
        @Override
        public void run() {
            if (!polling || !bleConnected) return;
            sendBle(Slx.pollHeadline());
            ui.postDelayed(() -> sendBle(Slx.pollDetail()), 300);
            ui.postDelayed(() -> sendBle(Slx.pollState()), 600);
            ui.postDelayed(this, POLL_MS);
        }
    };

    private void sendBle(byte[] data) {
        BluetoothGatt g = gatt;
        BluetoothGattCharacteristic rx = rxChar;
        if (g == null || rx == null || !bleConnected) return;
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                g.writeCharacteristic(rx, data, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
            } else {
                rx.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
                rx.setValue(data);
                g.writeCharacteristic(rx);
            }
        } catch (SecurityException ignored) {}
    }

    private void writeCcc(BluetoothGatt g, BluetoothGattDescriptor ccc) {
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                g.writeDescriptor(ccc, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
            } else {
                ccc.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                g.writeDescriptor(ccc);
            }
        } catch (SecurityException ignored) {}
    }
}
