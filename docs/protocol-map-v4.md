# SLX Hybrid X3 — Protocol Map v4 (distance-record cadence decoded)

Addendum to v3, from re-decoding the user's bug-report btsnoop
(2026-10-22 session) after the v0.3 field test. Decoder + full output
kept in the goal's hidden_files (decode.py / full.txt). All 54
phone→device type `0x01` writes in the session extracted.

## How the stock app actually sends distance records

- **One record at a time, never a burst.** Inter-send gaps ~0.5–2.5 s.
  (v0.3's 8-record bursts every 2 s made the device misbehave —
  display counted to the 999 cap and restarted.)
- **Window semantics confirmed**: rec 1 = current hole, rec 2 = next
  hole, … up to rec 7–8. Values are live distances from the player's
  position to each hole's green, capped at **999.0** (0x2706).
- **Refresh pattern**: rec 1 updates most often (~1 s while it is
  changing: 464→412→424→399→352→353), then the app walks the window
  (rec 2: 724→694, rec 3: 522→485, rec 4: 856, recs 5–7: 999 caps,
  re-sent occasionally).
- **Set (re)start = zero reset**: at round start and at each hole
  change the app first sends the rec(s) as **value 0, flag 0x02**,
  then the real values follow one by one (hole change at t≈172:
  rec1 0 → 324, rec2 247, rec3 248; second reset t≈178 at the course
  switch: rec1 0 → 100, rec2 523, rec3 416, rec5 959, rec6 999).
- **Flag byte**: `0x01` on live values during play; `0x02` on zero
  resets and during the course-switch rebuild. One `0x03` seen.
  Byte 3 is `0x02` in all but one frame (a single `0x01` at rec 1 =
  352.0 — treated as noise).
- **rec 0 exists**: sent as value 0 / flag 0x02 a few times around
  round setup, immediately after a type `0x17` command with payload
  `000001000000000000` (9 bytes, byte 2 = 01). Meaning still open —
  possibly a "window base / clear all" marker. Not replicated yet.
- Duplicate sends of the same record ~10–500 ms apart occur in the
  stock stream too — harmless.

## Field results that fed this (user's v0.3 test, 2026-10-22)

- Bare `0x01` records DO light the device display — no `0x18` table
  needed: the X3 showed its VOICE GPS page, hole "1H", yardage, "F.P".
- At home (miles from the loaded course) every value caps at 999 —
  the photo's "999 Y" is the cap working, not bad data.
- The device's unit (Y/M) is the device's own setting; the app-side
  units toggle changes only the phone. (Stock changes device units
  via the `0x5f` settings block, byte 8.)
- TTS speaks on the phone unless the phone's media audio is routed
  to the SLX — our BLE link is data-only; the speaker path is the
  separate system Bluetooth audio pairing ("SLX-SPEAKER").

## v0.4 replication

startPush sends rec 1 = 0 / flag 0x02 (reset), then a 900 ms ticker
alternating rec 1 with window recs 2–8 (live values, flag 0x01).
Hole change restarts the set the same way. `0x17` / rec 0 held back
pending another field test.

## v0.4 field test + raw-byte verification (2026-10-22, later)

- **The device displays whichever record arrived last.** With the
  alternating ticker the hole indicator followed the sends:
  1H, 1H, 1H, 2H, 1H, 3H… So records act as direct display commands,
  at least without a table upload. (Whether the stock app's window
  walk produces the same flicker on the device in real rounds is
  unknown — the demo session can't tell us.)
- Yardage on the device "counted up" (~40 in v0.4, to the 999 cap in
  v0.3) and kept resetting — mechanism unresolved; the v0.5
  single-record test isolates whether it comes from multi-rec sends.
- **Display latches**: turning the app feed off leaves the device
  showing its last number (999) — the screen persists until the
  device gets new data or is mode-switched/power-cycled.
- **Record byte order verified from raw payloads** (not the parsed
  view): `0112200202` = rec 1, 0x1220 = 4640 → 464.0 yd BE;
  `0527060201` = rec 5, 0x2706 = 9990 → the 999.0 cap. Our encoder
  matches byte-for-byte.
- **The `0x18` upload is demo data, not a course table.** All 14
  record bodies (seq 02–0f) fall into identical groups — 3×
  `…6a0064a0…`, 6× `…6a006450…`, 3× `…d8001928…`, then `d800190c`,
  `f4000a08` — real per-hole data would differ on every record.
  This session was a demo round; don't chase `0x18` for real rounds.
- Start context, for the record: `0x5a` `0101` ×3 with a `0x17`
  (`000001010000000000` — bytes 2 AND 3 set) interleaved, ~5 s
  before the first `0x18`. The later `0x17` at records-start was
  `000001000000000000` (byte 3 clear).

## v0.5 experiment

Send ONLY rec 1 (current hole), 1 record/sec, zero-reset on start
and hole change. Prediction: hole indicator parks on the current
hole and the yardage sits steady (999 at home). If counting persists
with a single repeated record, the counting is device-internal and
the next step is a narrated stock-app snoop at a real course.

## v0.5 field test (2026-10-22) — two facts nailed

- **Hole indicator parked on 1H** ✓ (single-rec works), and it did
  NOT change when the app switched holes — because **the rec number
  is the hole number the device displays**. Re-reading the capture
  with this lens: recs are ABSOLUTE hole numbers (the stock app
  refreshes a window of holes around the current one, current most
  often; on hole change it re-sends the neighbours because every
  distance changed with the new position). v0.3/v0.4 "window"
  framing was the same data, wrong addressing conclusion.
- **Yardage still reset every second** — in step with the 1/sec
  sends. Combined with v0.4 (0.9 s sends → counted to ~40 then
  reset), the pattern is: **each incoming record restarts the
  device's count-up toward the value**; frequent sends never let it
  land. Rate ≈ 40 counts in ~0.9 s.

## RESOLVED (2026-10-22): the sweep is the device, not us

v0.9 tested live values at flag 0x02 — no change. The user then
checked the official SLX Connect app directly: **it also counts up
from 0 on yardage updates.** The sweep is the X3's built-in way of
drawing a fresh number (a swing speedometer's renderer), identical
under the stock feed. Our display behaviour is at parity with
stock; the only app-side lever is update cadence (v0.10: >= 5 yd
steps, so the number finishes and sits longer, and GPS jitter while
standing still doesn't re-trigger sweeps). Display protocol work is
done: records addressed by absolute hole number, Start sequence +
~0.5 s reset gap required for values to render.

## v0.6 behaviour

Send rec = current hole's absolute number, only when the distance
changed ≥ 3 yd, at most every 5 s (30 s heartbeat), zero-reset on
hole change/start. Device then gets long quiet stretches to finish
its count-up and hold the number, and its hole indicator tracks the
app's hole.
