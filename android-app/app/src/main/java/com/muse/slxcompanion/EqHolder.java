package com.muse.slxcompanion;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.media.audiofx.AudioEffect;
import android.media.audiofx.BassBoost;
import android.media.audiofx.Equalizer;
import android.os.Build;

/**
 * An EQ that shapes YouTube Music's sound.
 *
 * The user always plays music from the YouTube Music app, and Android
 * gives no app a way to EQ another app's audio directly. The one open
 * door is the legacy audio-effect control session: when a player
 * starts, it broadcasts ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION with
 * its audio session id, and any app holding MODIFY_AUDIO_SETTINGS may
 * attach effects to that session (this is how EQ apps like Wavelet
 * work). YouTube Music still announces its session on current
 * Android, so we arm a receiver for the life of this process, attach
 * an Equalizer + BassBoost when YouTube Music shows up, and re-apply
 * the saved settings every time.
 *
 * Settings persist in SharedPreferences, so the user's curve survives
 * restarts; the attach itself lasts while this app's process lives.
 */
public final class EqHolder {

    public interface Listener {
        void onEqStateChanged();
    }

    public static volatile Listener listener;

    public static final String YTM_PACKAGE = "com.google.android.apps.youtube.music";
    private static final String PREFS = "musiceq";

    private static Equalizer eq;
    private static BassBoost bass;
    private static int attachedSession = -1;
    private static boolean armed = false;

    private EqHolder() {}

    // ---------------------------------------------------------- arming

    /** Registers the session listener once per process. Safe to call often. */
    public static synchronized void arm(Context ctx) {
        if (armed) return;
        armed = true;
        Context app = ctx.getApplicationContext();
        IntentFilter filter = new IntentFilter();
        filter.addAction(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION);
        filter.addAction(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION);
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String pkg = intent.getStringExtra(AudioEffect.EXTRA_PACKAGE_NAME);
                int session = intent.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, -1);
                if (session <= 0 || !YTM_PACKAGE.equals(pkg)) return;
                if (AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION.equals(intent.getAction())) {
                    attach(app, session);
                } else {
                    detachIf(session);
                }
            }
        };
        if (Build.VERSION.SDK_INT >= 33) {
            app.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            app.registerReceiver(receiver, filter);
        }
    }

    private static synchronized boolean attach(Context ctx, int sessionId) {
        if (eq != null && attachedSession == sessionId) return true;
        releaseFx();
        try {
            eq = new Equalizer(0, sessionId);
            bass = new BassBoost(0, sessionId);
            attachedSession = sessionId;
            applySavedFx(ctx);
            notifyListener();
            return true;
        } catch (Exception e) {
            releaseFx();
            return false;
        }
    }

    private static synchronized void detachIf(int sessionId) {
        if (attachedSession == sessionId) {
            releaseFx();
            attachedSession = -1;
            notifyListener();
        }
    }

    private static void releaseFx() {
        if (eq != null) { try { eq.release(); } catch (Exception ignored) {} eq = null; }
        if (bass != null) { try { bass.release(); } catch (Exception ignored) {} bass = null; }
    }

    public static synchronized boolean isAttached() { return eq != null; }
    public static synchronized int attachedSession() { return attachedSession; }

    // ---------------------------------------------------------- settings

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static void applySavedFx(Context ctx) {
        if (eq == null) return;
        SharedPreferences p = prefs(ctx);
        boolean on = p.getBoolean("eq_on", true);
        try {
            eq.setEnabled(on);
            int bands = eq.getNumberOfBands();
            for (int b = 0; b < bands; b++) {
                int saved = p.getInt("band_" + b, Integer.MIN_VALUE);
                if (saved != Integer.MIN_VALUE) {
                    eq.setBandLevel((short) b, (short) saved);
                }
            }
        } catch (Exception ignored) {}
        if (bass != null) {
            try {
                int strength = p.getInt("bass", 0);
                bass.setStrength((short) strength);
                bass.setEnabled(on && strength > 0);
            } catch (Exception ignored) {}
        }
    }

    public static synchronized boolean fxAvailable() { return eq != null; }

    public static synchronized int bandCount() {
        try { return eq != null ? eq.getNumberOfBands() : 0; }
        catch (Exception e) { return 0; }
    }

    /** [low, high] band level range in millibels. */
    public static synchronized short[] bandRange() {
        try { return eq != null ? eq.getBandLevelRange() : new short[]{-1500, 1500}; }
        catch (Exception e) { return new short[]{-1500, 1500}; }
    }

    public static synchronized int centerFreqHz(int band) {
        try { return eq != null ? eq.getCenterFreq((short) band) / 1000 : 0; }
        catch (Exception e) { return 0; }
    }

    public static synchronized int bandLevel(int band) {
        try { return eq != null ? eq.getBandLevel((short) band) : 0; }
        catch (Exception e) { return 0; }
    }

    public static synchronized void setBandLevel(Context ctx, int band, int levelMb) {
        prefs(ctx).edit().putInt("band_" + band, levelMb).apply();
        if (eq == null) return;
        try { eq.setBandLevel((short) band, (short) levelMb); } catch (Exception ignored) {}
    }

    public static synchronized int presetCount() {
        try { return eq != null ? eq.getNumberOfPresets() : 0; } catch (Exception e) { return 0; }
    }

    public static synchronized String presetName(int i) {
        try { return eq != null ? eq.getPresetName((short) i) : ""; } catch (Exception e) { return ""; }
    }

    public static synchronized int currentPreset() {
        try { return eq != null ? eq.getCurrentPreset() : -1; } catch (Exception e) { return -1; }
    }

    public static synchronized void usePreset(Context ctx, int i) {
        if (eq == null) return;
        try {
            eq.usePreset((short) i);
            SharedPreferences.Editor ed = prefs(ctx).edit();
            for (int b = 0; b < eq.getNumberOfBands(); b++) {
                ed.putInt("band_" + b, eq.getBandLevel((short) b));
            }
            ed.apply();
        } catch (Exception ignored) {}
        notifyListener();
    }

    public static synchronized boolean eqEnabled(Context ctx) {
        return prefs(ctx).getBoolean("eq_on", true);
    }

    public static synchronized void setEqEnabled(Context ctx, boolean on) {
        prefs(ctx).edit().putBoolean("eq_on", on).apply();
        if (eq != null) {
            try { eq.setEnabled(on); } catch (Exception ignored) {}
        }
        if (bass != null) {
            try { bass.setEnabled(on && bassStrength(ctx) > 0); } catch (Exception ignored) {}
        }
        notifyListener();
    }

    public static synchronized int bassStrength(Context ctx) {
        return prefs(ctx).getInt("bass", 0);
    }

    public static synchronized void setBassStrength(Context ctx, int strength) {
        prefs(ctx).edit().putInt("bass", strength).apply();
        if (bass != null) {
            try {
                bass.setStrength((short) strength);
                bass.setEnabled(eqEnabled(ctx) && strength > 0);
            } catch (Exception ignored) {}
        }
    }

    private static void notifyListener() {
        Listener l = listener;
        if (l != null) l.onEqStateChanged();
    }
}
