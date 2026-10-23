# SLX Hybrid X3 — BLE Protocol Map v1

Source: nRF Connect log, 2026-10-22 (336 notifications, listen-only).
Device: **SLX-HYBRID (PRO-1038)**, MAC redacted (author’s unit).

## The big findings

- The BLE chip is a **Nordic nRF** part: it exposes the standard
  **Nordic UART Service** plus Nordic's **Secure DFU Service (0xFE59)**.
- Everything travels over the Nordic UART Service:
  - RX `6e400002-b5a3-f393-e0a9-e50e24dcca9e` — phone → device (write)
  - TX `6e400003-b5a3-f393-e0a9-e50e24dcca9e` — device → phone (notify)
- **No pairing, bonding, or login was needed** — nRF Connect connected,
  enabled notifications, and the device immediately started talking.
- The Secure DFU service is how official firmware updates are delivered
  (signed packages; custom firmware stays blocked without the signing key).

## Frame format (verified against all 336 frames)

```
[ type ][ length ][ payload (length bytes) ][ checksum ]
checksum = XOR of every preceding byte in the frame
```

## Device → phone frames seen

| Type | Name (guess) | Payload | Notes |
|---|---|---|---|
| `0x52` 'R' | Status text | ASCII string | `STATE=READY,MOTION=TRUE` / `MOTION=NONE` (motion = IMU); also `Start` ×2 |
| `0x56` 'V' | Config block | 14 bytes of small values | Sent once per connection; one byte differed between the two sessions (a setting changed) |
| `0x06` | Headline metric | ASCII digits | `172` all of session 2, `161` in session 1 — same number as the 0x05 frame each session (swing metric from the test session, most likely) |
| `0x05` | Metric detail | Same ASCII digits + 1 byte + zeros | Extra byte ranged 0x3D–0x41 (61–65) |
| `0x00` | Status value | uint16 LE | Values seen: 1 (×65), 81, 95, 23, 24, 28. The 95 → 81 drift across sessions is consistent with battery % |
| `0x01` | State index | 1 byte | Values 1–14 seen (1,2,3,4,7,8,9,10,11,13,14) — club or menu index? |
| `0x04` | Live value | 1 byte | 62–64, drifting slowly |
| `0x53` 'S' | Event flag | 0x80 (×9), 0x82 (×3) | Appeared during the active part of session 2 |

## Still unknown

- **Phone → device commands.** This capture only listened; nRF Connect
  sent no writes. The write side (what SLX Connect sends to ask for data,
  change settings, trigger announcements) comes from the HCI snoop log
  of a real SLX Connect session (Pass 2 of the recon guide).
- Exact meaning of the 0x00 / 0x01 / 0x04 values — needs the actions
  noted against them (what was the user doing at each change?).

## What this means for the app

The pipe is as friendly as it gets: standard Nordic UART, plain frames,
XOR checksum, no auth. Once the write commands are captured, the app can
reproduce the whole conversation on its own.
