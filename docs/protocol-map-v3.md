# SLX Hybrid X3 — Protocol Map v3 (session correlation added)

Supersedes v2 with the action-by-action correlation from the user's
narrated session (bug report btsnoop, 2026-10-22, 11:30:00–11:34:14 PDT).
The user: opened a **Demo round at Pebble Beach Golf Links** → clicked
Start → changed pin location → flipped yards→meters→yards → changed
hole → searched and picked another course.

## What each action looked like on the wire

**Connect / round setup (11:30:05–11:30:54)**
- `62-00-62` get-config, `0x5f` settings upload, `0x1c` toggle — app init.
- Four `0x0a` commands cycling two selectors through all combos
  ([1|2] × [3|4], constant 04) — the pin/green position fields syncing.
- `0x5f` block re-uploaded per change; diffs show single bytes moving:
  - frame byte 8: 00→01→00 within 3 s — a there-and-back flip matching
    the **yards→meters→yards** change (probable)
  - frame byte 7: steps 00→02→03→01 — a multi-value picker being scrolled
  - frame byte 6: 00→01, later back — another boolean setting
- `0x1c` payload flips `01-01` → `00-01` → `01-01` — a boolean (Demo or
  Battery Save; exact identity open).

**Start (11:31:03)**
- `0x5a` (`01-01`) ×3 + `0x17` flag set → then the course table upload:
  **type `0x18`, records seq 02–0f (~2 s apart)**, and the device's
  type `0x01` state byte **counts 2→14 in lockstep** — it is the
  upload-progress ack (last record received), not a club index.
  Burst 2 at the course switch counts down 4→1 with seq 04–01.
- 15 records total (seq 01–0f). Record bodies group as
  `[ff][u16][u8][u16][00][08-00]`; field semantics still open.

**During the round (11:31:43–11:33:13)**
- **Type `0x01` pushes = distance records**: `[rec][u16 BE = value × 10][02][flag]`.
  rec=01 counts 464 → 412 → 424 → 399 → 352 → 353 (live distance updating);
  other recs settle at fixed values; **999.0 (0x2706) is a sentinel/cap**
  hit by recs 05–07. Pattern fits "distance from current position to each
  hole's green, capped at 999". After the hole change (11:32:57) the whole
  set resets and recomputes (324 / 247 / 248 / 100 / 523 / 416 / 959…).
- `0x17` flag toggles around round activity; `0x5a` flurry (incl. a
  `01-08` variant) brackets the course switch at 11:34:05.

**Course switch (11:33:30–11:34:09)**
- Partial `0x18` re-upload (seq 04–01, ack counting down), then `0x5a`
  init flurry — capture ends (bug report taken) mid-switch.

## Field note (2026-10-22, from the user's on-device test)

The `MOTION=` field in the `R` status frame is **not** (only) physical
motion: the user observed it switch to `TRUE` when they selected
Bluetooth/speaker mode on the X3 itself. Working interpretation: it
tracks the device's mode/active state — usable in the app as a
"speaker mode on/off" indicator. Label it that way in the UI, not
"motion".

## Confidence

- Confirmed: framing, handles, connect sequence, poll pairs, `0x18`
  table upload + ack counter, `0x01` distance-record format, `0x5f`
  per-setting bytes, 999.0 sentinel.
- Probable: byte 8 of `0x5f` = unit (yd/m); `0x17` = round/GPS active;
  `0x0a` selectors = pin/green position.
- Open: `0x18` record field meanings; `0x1c` identity; `0x01` tail flags.

## Build relevance

Our app computes GPS distances itself and speaks them via TTS through
the speaker, so v1 does not depend on the open items: they only matter
if we later want the X3 itself to announce/display distances (which
would use the `0x01`/`0x18` pushes, whose structure is already known).
