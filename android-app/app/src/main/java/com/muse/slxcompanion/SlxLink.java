package com.muse.slxcompanion;

import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import java.util.ArrayDeque;

/**
 * The live BLE link to the SLX, shared between screens.
 *
 * GpsActivity (the app's home screen) owns the connection lifecycle
 * (scan, connect, poll loop); it publishes the connected GATT here
 * once notifications are live, so the display feed can send frames —
 * e.g. the distance records that feed the device's own display —
 * without owning a second connection (the X3 accepts only one).
 */
public final class SlxLink {

    public static volatile BluetoothGatt gatt;
    public static volatile BluetoothGattCharacteristic rxChar;
    public static volatile boolean connected = false;

    private SlxLink() {}

    public static void attach(BluetoothGatt g, BluetoothGattCharacteristic rx) {
        gatt = g;
        rxChar = rx;
        connected = true;
    }

    public static void clear() {
        gatt = null;
        rxChar = null;
        connected = false;
        synchronized (QUEUE) {
            QUEUE.clear();
            draining = false;
        }
        WRITER.removeCallbacksAndMessages(null);
    }

    public static boolean isLive() {
        return connected && gatt != null && rxChar != null;
    }

    // ------------------------------------------------------------ writes
    //
    // Every frame goes through ONE queue that writes a frame every
    // 75 ms. Android's GATT stack silently drops WRITE_TYPE_NO_RESPONSE
    // writes issued while another is outstanding, and this app used to
    // fire bursts back-to-back (the 4-frame round-start sequence, the
    // connect-time config fetch + settings upload). The 2026-10-23
    // our-app snoop showed the cost: of the round-start burst only one
    // 0x5a arrived and the 0x17 never did; a v0.44 session's settings
    // upload never arrived either — two "failed" experiments that
    // mostly tested frames the device never received. The stock app
    // spaces its own bursts ~200 ms apart; 75 ms is safely inside the
    // stack's comfort and still far faster than the frame cadence.

    private static final Handler WRITER = new Handler(Looper.getMainLooper());
    private static final ArrayDeque<byte[]> QUEUE = new ArrayDeque<>();
    private static boolean draining = false;

    private static final Runnable DRAIN = new Runnable() {
        @Override public void run() {
            byte[] data;
            synchronized (QUEUE) {
                data = QUEUE.poll();
                if (data == null) {
                    draining = false;
                    return;
                }
            }
            writeNow(data);
            WRITER.postDelayed(this, 75);
        }
    };

    /** Queues one framed command for spaced delivery. */
    public static boolean send(byte[] data) {
        if (!isLive()) return false;
        synchronized (QUEUE) {
            QUEUE.add(data);
            if (!draining) {
                draining = true;
                WRITER.post(DRAIN);
            }
        }
        return true;
    }

    private static boolean writeNow(byte[] data) {
        BluetoothGatt g = gatt;
        BluetoothGattCharacteristic rx = rxChar;
        if (g == null || rx == null || !connected) return false;
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                g.writeCharacteristic(rx, data, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
            } else {
                rx.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
                rx.setValue(data);
                g.writeCharacteristic(rx);
            }
            return true;
        } catch (SecurityException e) {
            return false;
        }
    }
}
