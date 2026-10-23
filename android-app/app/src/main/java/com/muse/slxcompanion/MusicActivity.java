package com.muse.slxcompanion;

import android.app.Activity;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import java.util.Locale;

/**
 * Music EQ screen.
 *
 * The user's music always comes from the YouTube Music app, so there
 * is deliberately no player here. This screen runs the EQ that
 * EqHolder attaches to YouTube Music's announced audio session:
 * band sliders and presets, all persisted. While attached,
 * the EQ shapes YouTube Music's sound on its way to the SLX speaker.
 *
 * (Bass boost and an on-screen volume bar were removed 2026-10-23
 * at the user's word: the boost had no audible effect on this
 * setup, and the bar didn't track the phone/Bluetooth volume.)
 */
public class MusicActivity extends Activity {

    private final Handler ui = new Handler(Looper.getMainLooper());

    private TextView attachText, presetText;
    private Button eqBtn;
    private LinearLayout bandsBox;
    private int shownPreset = -1;

    private final EqHolder.Listener eqListener = () -> ui.post(this::refresh);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EqHolder.arm(this);
        buildUi();
        EqHolder.listener = eqListener;
        EqHolder.ensureFx(this);
        refresh();
    }

    @Override
    protected void onResume() {
        super.onResume();
        EqHolder.ensureFx(this);
        refresh();
    }

    @Override
    protected void onDestroy() {
        if (EqHolder.listener == eqListener) EqHolder.listener = null;
        super.onDestroy();
    }

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

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(40, 40, 40, 40);

        TextView title = tv("Speaker Settings", 26, true);
        title.setTextColor(0xFF1B5E20);
        root.addView(title);


        attachText = tv("Waiting for YouTube Music…", 16, true);
        root.addView(attachText);

        eqBtn = btn("Equalizer: ON");
        eqBtn.setOnClickListener(v -> {
            EqHolder.setEqEnabled(this, !EqHolder.eqEnabled(this));
            refresh();
        });
        root.addView(eqBtn);

        root.addView(tv("PRESET", 15, true));
        LinearLayout presetRow = new LinearLayout(this);
        presetRow.setOrientation(LinearLayout.HORIZONTAL);
        Button prevPreset = btn("◀");
        prevPreset.setOnClickListener(v -> stepPreset(-1));
        presetRow.addView(prevPreset);
        presetText = tv("—", 16, false);
        presetText.setGravity(Gravity.CENTER_HORIZONTAL);
        presetRow.addView(presetText, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        Button nextPreset = btn("▶");
        nextPreset.setOnClickListener(v -> stepPreset(1));
        presetRow.addView(nextPreset);
        root.addView(presetRow);

        root.addView(tv("BANDS", 15, true));
        bandsBox = new LinearLayout(this);
        bandsBox.setOrientation(LinearLayout.VERTICAL);
        root.addView(bandsBox);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        setContentView(scroll);
    }

    private void stepPreset(int dir) {
        int count = EqHolder.presetCount();
        if (count == 0) return;
        int cur = EqHolder.currentPreset();
        if (cur >= 0) shownPreset = cur;
        if (shownPreset < 0) shownPreset = 0;
        else shownPreset = ((shownPreset + dir) % count + count) % count;
        EqHolder.usePreset(this, shownPreset);
        refresh();
    }

    private void refresh() {
        boolean attached = EqHolder.isAttached();
        attachText.setText(attached
                ? (EqHolder.onMixFallback()
                        ? "EQ connected — YouTube Music session pending"
                        : "EQ connected to YouTube Music ✓")
                : "Waiting for YouTube Music — press play there.");
        eqBtn.setText(EqHolder.eqEnabled(this) ? "Equalizer: ON" : "Equalizer: OFF");

        if (attached) {
            int cur = EqHolder.currentPreset();
            if (cur >= 0) shownPreset = cur;
            presetText.setText(cur >= 0 ? EqHolder.presetName(cur) : "Custom");
        } else {
            presetText.setText("—");
        }

        buildBandSliders();
    }

    private void buildBandSliders() {
        bandsBox.removeAllViews();
        if (!EqHolder.fxAvailable()) {
            bandsBox.addView(tv("Band sliders appear once YouTube Music connects.", 13, false));
            return;
        }
        short[] range = EqHolder.bandRange();
        final int low = range[0];
        final int high = range[1];
        int count = EqHolder.bandCount();
        for (int b = 0; b < count; b++) {
            final int band = b;
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            TextView freq = tv(fmtFreq(EqHolder.centerFreqHz(band)), 14, false);
            row.addView(freq);
            final TextView val = tv(fmtDb(EqHolder.bandLevel(band)), 14, false);
            val.setGravity(Gravity.END);
            row.addView(val, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            bandsBox.addView(row);

            SeekBar s = new SeekBar(this);
            s.setMax(high - low);
            s.setProgress(EqHolder.bandLevel(band) - low);
            s.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(SeekBar sk, int p, boolean fromUser) {
                    if (fromUser) {
                        int level = low + p;
                        EqHolder.setBandLevel(MusicActivity.this, band, level);
                        val.setText(fmtDb(level));
                        presetText.setText("Custom");
                    }
                }
                @Override public void onStartTrackingTouch(SeekBar sk) {}
                @Override public void onStopTrackingTouch(SeekBar sk) {}
            });
            bandsBox.addView(s);
        }
    }

    private static String fmtFreq(int hz) {
        if (hz >= 1000) {
            String s = String.format(Locale.US, "%.1f", hz / 1000.0);
            if (s.endsWith(".0")) s = s.substring(0, s.length() - 2);
            return s + " kHz";
        }
        return hz + " Hz";
    }

    private static String fmtDb(int millibels) {
        return String.format(Locale.US, "%+.1f dB", millibels / 100.0);
    }
}
