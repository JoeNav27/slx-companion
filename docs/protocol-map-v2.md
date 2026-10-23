# SLX Hybrid X3 — BLE Protocol Map v2 (both directions)

Sources: nRF Connect log 2026-10-22 (receive side) + btsnoop_hci.log from
bug report 2026-10-22 11:34 (full SLX Connect session, 11:30:00–11:34:14).
425 SLX-framed messages decoded in the snoop session alone.

Device: **SLX-HYBRID (PRO-1038)**, Nordic nRF chip (unit MAC redacted).
Frame format (unchanged, re-verified): `[type][len][payload][XOR checksum]`.

## Handles (Nordic UART Service)

| Handle | Role |
|---|---|
| `0x000d` | NUS RX value — phone writes all commands here (WriteCmd) |
| `0x000f` | NUS TX value — device notifications |
| `0x0010` | TX CCC descriptor — write `01-00` to enable notifications |

## Connect sequence (what SLX Connect does)

1. Connect, service discovery.
2. Write `01-00` to `0x0010` → device immediately pushes `R` STATE frame.
3. Send **`62-00-62`** (type 0x62, no payload) → device replies with the
   `0x56` 'V' config block.
4. App uploads a settings block (type `0x5f`, see below).
5. Poll loop, roughly every 5–6 s, three requests (see pairs below).

## Request → response pairs (the poll loop)

| Phone sends | Device replies | Meaning |
|---|---|---|
| `12-00-12` (type 0x12) | type `0x06` | Headline metric ("172") |
| `11-01-01-11` (type 0x11, param 01) | type `0x05` | Metric detail ("172" + byte 0x3a–0x41 + zeros) |
| `0c-00-0c` (type 0x0c) | type `0x01` + type `0x00` reports | State byte; numeric status reports |

Device also pushes unprompted: `R` STATE frames on motion change,
`0x53` event flags (0x80/0x82), `R` "Start", type `0x04` slow value (0x3d–0x3e).

## Phone → device commands observed

| Type | Count | Payload pattern | Working meaning |
|---|---|---|---|
| `0x11` | 42 | `01` | Poll: metric detail |
| `0x0c` | 42 | — | Poll: state/status |
| `0x12` | 41 | — | Poll: headline metric |
| `0x01` | ~45 | `[rec][u16][02][01\|02]`, rec 0–7+, values change over time | Live data records pushed to device — GPS-distance-shaped (updates as things change) |
| `0x5f` | 13 | 14-byte block, small byte variations between sends | Settings upload (whole block re-sent on change) |
| `0x18` | 15 | `[seq 01–0f][ff][…][03][00][08][00]` | Sequenced table upload, 15 records, re-sent when data changes (GPS table?) |
| `0x17` | 7 | 9 bytes, one byte toggles 0/1 | A mode/setting toggle |
| `0x5a` | 8 | `01-01` (×7), `01-08` (×1) | Small set-command, two params |
| `0x1c` | 4 | `01-01` / `00-01` | On/off toggle |
| `0x0a` | 4 | `[01\|02]-[03\|04]-04` | Two-field setting |
| `0x51` | 1 | `07-01-82` | One-off command |
| `0x62` | 2 | — | Get config → `0x56` reply |

## Device → phone frames (from v1, confirmed again)

- `0x52` 'R' ASCII status: `STATE=READY,MOTION=TRUE/NONE`, `Start`
- `0x56` 'V' 14-byte config block, in reply to `0x62`
- `0x06` headline metric (ASCII), `0x05` metric detail
- `0x00` uint16 status reports — recurring values {1, 23, 24, 28, 95};
  exact register semantics still TBD
- `0x01` state byte (values 1–14 seen across captures)
- `0x04` slow-drifting byte (0x3a–0x40 range), `0x53` event flags

## Status

The conversation is fully mapped at the frame level: connect sequence,
poll pairs, settings upload, and the data-record uploads are all captured
byte-for-byte. Remaining unknowns are field-level semantics inside the
`0x01` / `0x18` GPS-shaped records and the `0x5f` settings block — those
get pinned by correlating with what was done in the app during a session,
or by nRF Connect write tests (send a known frame, watch what changes).

**This is enough to build the app's BLE core now**: connect, enable
notifications, fetch config, run the poll loop, display the live stream.
