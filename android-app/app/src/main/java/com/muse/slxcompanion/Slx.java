package com.muse.slxcompanion;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * SLX Hybrid X3 BLE protocol, reverse-engineered from HCI snoop captures
 * (see protocol-map-v3.md in the project docs).
 *
 * Transport: Nordic UART Service on a Nordic nRF chip. No pairing/auth.
 *   RX 6e400002 (phone writes commands here)
 *   TX 6e400003 (device notifies here; CCC descriptor 0x2902)
 * Frame: [type][length][payload][checksum] where checksum = XOR of all
 * preceding bytes and length = payload byte count.
 */
public final class Slx {

    public static final UUID NUS_SERVICE = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e");
    public static final UUID NUS_RX = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e");
    public static final UUID NUS_TX = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e");
    public static final UUID CCC = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    public static final String NAME_PREFIX = "SLX";

    // Phone -> device command types observed in the stock app's session.
    public static final int CMD_GET_CONFIG = 0x62;      // -> device replies 0x56 config block
    public static final int CMD_POLL_HEADLINE = 0x12;   // -> device replies 0x06
    public static final int CMD_POLL_DETAIL = 0x11;     // param 01 -> device replies 0x05
    public static final int CMD_POLL_STATE = 0x0c;      // -> device replies 0x01 / 0x00 reports

    // Device -> phone frame types.
    public static final int FR_STATUS_TEXT = 0x52;      // 'R' ASCII, e.g. STATE=READY,MOTION=NONE
    public static final int FR_CONFIG = 0x56;           // 'V' 14-byte config block
    public static final int FR_HEADLINE = 0x06;         // ASCII digits, headline metric
    public static final int FR_DETAIL = 0x05;           // ASCII digits + detail byte + zeros
    public static final int FR_STATUS_VALUE = 0x00;     // uint16 LE status report
    public static final int FR_STATE_BYTE = 0x01;       // single-byte state / upload ack counter
    public static final int FR_SENSOR = 0x04;           // slow-drifting single byte
    public static final int FR_EVENT = 0x53;            // 'S' event flags (0x80 / 0x82)

    private Slx() {}

    public static byte[] frame(int type, byte[] payload) {
        if (payload == null) payload = new byte[0];
        byte[] out = new byte[payload.length + 3];
        out[0] = (byte) type;
        out[1] = (byte) payload.length;
        System.arraycopy(payload, 0, out, 2, payload.length);
        int x = 0;
        for (int i = 0; i < out.length - 1; i++) x ^= (out[i] & 0xFF);
        out[out.length - 1] = (byte) x;
        return out;
    }

    public static byte[] getConfig() { return frame(CMD_GET_CONFIG, null); }
    public static byte[] pollHeadline() { return frame(CMD_POLL_HEADLINE, null); }
    public static byte[] pollDetail() { return frame(CMD_POLL_DETAIL, new byte[]{0x01}); }
    public static byte[] pollState() { return frame(CMD_POLL_STATE, null); }

    /**
     * Device settings upload, type 0x5f — the stock app sends this
     * 14-byte block at every connect (twice in captures); our app
     * never sent it at all (added v0.44). Byte 5: DO NOT TOUCH.
     * Capture 4's stock upload has 0x00 there; capture 3 and the
     * A/B stock session have 0x03 — but when v0.51 uploaded 0x03,
     * the user's device went visibly wrong (green LED blinking
     * constantly, holes switching 1↔2 at random on pin changes).
     * Whatever byte 5 selects, it is a device mode with teeth, not
     * a volume knob. Reverted to 0x00 in v0.52; characterize it
     * from captures only, never by experiment on the device.
     */
    public static byte[] cmdSettingsUpload() {
        return frame(0x5f, new byte[]{0x01, 0, 0, 0, 0, 0, 0, 0x01, 0, 0, 0x01, 0, 0x04, 0});
    }

    /**
     * Type 0x1c, payload 01 01 — the stock app sends this once at
     * every connect, between its settings uploads. Purpose unknown;
     * until v0.48 it was the one stock session frame this app had
     * never sent at all, which made it the last unsent suspect in
     * the power-button readout hunt.
     */
    public static byte[] cmdConnect1c() {
        return frame(0x1c, new byte[]{0x01, 0x01});
    }

    /**
     * One live distance record, type 0x01 — the pushes the stock app
     * sends during a round (protocol-map v2/v3): payload is
     * [rec][u16 BE = yards x 10][0x02][flag]. 999.0 yd is the
     * out-of-range cap the stock app uses. rec is 1-based; in the
     * captured round, rec 1 tracked the current hole live while the
     * following recs held the upcoming holes' distances. The flag
     * byte is the PIN SELECTOR the device displays: 0x01 = FP,
     * 0x02 = CP, 0x03 = BP (proven on the user's device 2026-10-23:
     * flipping our live records 0x02 → 0x01 flipped the device's
     * label CP → FP). The hole-start zero reset uses 0x02.
     */
    public static final int CMD_DISTANCE_RECORD = 0x01;

    public static byte[] distanceRecord(int rec, double yards) {
        return distanceRecord(rec, yards, 0x01);
    }

    /**
     * The stock app's round-start sequence: three type 0x5a (payload
     * 01 01) with a type 0x17 between them. The 0x17 payload came
     * from the first capture (00 00 01 01 …), but captures 2–4 —
     * including the readout-confirmed session — all send
     * 00 00 01 00 …: byte 3 is 0 there. v0.45 flips ours to match
     * (the settings upload in v0.44 changed nothing, so this flags
     * byte is the next single-variable readout-arm suspect).
     */
    public static byte[] cmdRoundStartA() { return frame(0x5a, new byte[]{0x01, 0x01}); }
    public static byte[] cmdRoundActive() { return frame(0x17, new byte[]{0, 0, 1, 0, 0, 0, 0, 0, 0}); }

    /**
     * The stock app's START action, decoded from the 2026-10-23 A/B
     * snoop: when the user taps Start, the app sends the 0x17 with
     * byte 3 SET (00 00 01 01 …) and re-uploads the settings block;
     * the device answers with its "Start" state report — and only in
     * a started round does the power-button readout speak (the
     * user's single A/B press came after starting, and spoke; every
     * session of ours, which never sent this, stayed silent). The
     * idx3=00 variant above is the "round loaded" form sent when a
     * course is picked.
     */
    public static byte[] cmdRoundStarted() { return frame(0x17, new byte[]{0, 0, 1, 1, 0, 0, 0, 0, 0}); }

    /**
     * Flag byte as seen in the stock capture: 0x01 on live values during
     * play; 0x02 on the zero-value resets the app sends when a set
     * (re)starts — round start and hole changes.
     */
    public static byte[] distanceRecord(int rec, double yards, int flag) {
        return distanceRecord(rec, yards, flag, 0x02);
    }

    /**
     * The payload's fourth byte: 0x02 in every stock record. The
     * v0.32 experiment (sending 1/3 for front/back pin) REFUTED it
     * as the device pin label: the CP label never moved and the
     * yardage feed misbehaved — the byte belongs to the record's
     * value semantics (likely which green target the value is for),
     * so it stays 0x02 exactly as the stock app sends. The FP/CP/BP
     * label command is still unidentified; see protocol-map-v5.md.
     */
    public static byte[] distanceRecord(int rec, double yards, int flag, int fourthByte) {
        double clamped = Math.max(0, Math.min(999.0, yards));
        int v = (int) Math.round(clamped * 10.0);
        byte[] payload = new byte[]{
                (byte) rec,
                (byte) (v >> 8), (byte) v,
                (byte) fourthByte, (byte) flag};
        return frame(CMD_DISTANCE_RECORD, payload);
    }

    /** One decoded frame. */
    public static final class Frame {
        public final int type;
        public final byte[] payload;

        Frame(int type, byte[] payload) {
            this.type = type;
            this.payload = payload;
        }

        public String ascii() {
            return new String(payload, StandardCharsets.US_ASCII);
        }

        public String hex() {
            StringBuilder sb = new StringBuilder();
            for (byte b : payload) sb.append(String.format("%02x", b));
            return sb.toString();
        }

        /** Short human description for the log and UI. */
        public String describe() {
            switch (type) {
                case FR_STATUS_TEXT:
                    return ascii();
                case FR_CONFIG:
                    return "CONFIG " + hex();
                case FR_HEADLINE:
                    return "HEADLINE " + ascii();
                case FR_DETAIL: {
                    String digits = payload.length >= 3
                            ? new String(payload, 0, 3, StandardCharsets.US_ASCII) : ascii();
                    int detail = payload.length > 3 ? (payload[3] & 0xFF) : -1;
                    return "DETAIL " + digits + " +" + detail;
                }
                case FR_STATUS_VALUE: {
                    int v = payload.length >= 2
                            ? (payload[0] & 0xFF) | ((payload[1] & 0xFF) << 8) : -1;
                    return "STATUS " + v;
                }
                case FR_STATE_BYTE:
                    return "STATE#" + (payload.length > 0 ? (payload[0] & 0xFF) : -1);
                case FR_SENSOR:
                    return "SENSOR " + (payload.length > 0 ? (payload[0] & 0xFF) : -1);
                case FR_EVENT:
                    return "EVENT " + hex();
                default:
                    return String.format("TYPE %02x ", type) + hex();
            }
        }
    }

    /**
     * Stream parser: BLE notifications split and merge frames arbitrarily
     * (a 26-byte frame spans two 20-byte notifications), so bytes are
     * accumulated and complete, checksum-valid frames are emitted.
     * On a bad length/checksum it drops one byte and resyncs.
     */
    public static final class Parser {
        private final ArrayList<Byte> buf = new ArrayList<>();

        public List<Frame> feed(byte[] chunk) {
            for (byte b : chunk) buf.add(b);
            List<Frame> out = new ArrayList<>();
            while (buf.size() >= 3) {
                int len = buf.get(1) & 0xFF;
                int total = len + 3;
                if (total > 64) { // implausible: drop a byte and resync
                    buf.remove(0);
                    continue;
                }
                if (buf.size() < total) break; // wait for the rest
                int x = 0;
                for (int i = 0; i < total - 1; i++) x ^= (buf.get(i) & 0xFF);
                if (x != (buf.get(total - 1) & 0xFF)) {
                    buf.remove(0); // bad checksum: resync
                    continue;
                }
                byte[] payload = new byte[len];
                for (int i = 0; i < len; i++) payload[i] = buf.get(2 + i);
                out.add(new Frame(buf.get(0) & 0xFF, payload));
                buf.subList(0, total).clear();
            }
            return out;
        }
    }
}
