package com.muse.slxcompanion;

import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.os.Build;

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
    }

    public static boolean isLive() {
        return connected && gatt != null && rxChar != null;
    }

    /** Writes one framed command. Returns false if the link is down. */
    public static boolean send(byte[] data) {
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
