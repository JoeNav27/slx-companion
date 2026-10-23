# SLX Hybrid X3 — Protocol Map v7 (pin label + buttons, second capture)

2026-10-23. Second user capture (bug report 08:06): connect →
Pebble Beach demo round → pin cycled center/front/back repeatedly
mid-round → Vol+ ×3, Vol− ×3, Play/Pause ×3. Full-traffic
inventory, both directions, all channels.

## Pin label: CLOSED — no phone command carries it

(UI note, user 2026-10-23: in SLX Connect the pin control is a
SINGLE button that filters/cycles Center → Front → Back on
repeated taps — not three separate buttons. Our app uses three
direct buttons; user hasn't asked to change that.)

During the entire round window (~26–76 s), the phone sent ONLY
polls (0x0c/0x11/0x12) and distance records (0x01). Zero 0x0a,
zero 0x5f re-uploads, record byte 3 constant 0x02, record values
following the demo's simulated walk (412→85 yd) straight through
the pin cycling — the plateaus at 210.0 (×6 sends) and 85.0 don't
step when the pin changes. **In the stock app, changing the pin
mid-round emits nothing on the BLE link.** Combined with the
v0.32 field refutation (byte 3 is not the label), there is no
stock-repertoire command for our app to send. The FP/CP/BP label
is most likely cycled on the device itself (its own buttons /
marker remote) — a 30-second on-device test the user can run
against our feed. Our app already matches stock behavior: it
sends the selected pin's yardage; the label is not ours to move.

## The 0x5f settings block, byte anatomy (two captures compared)

14-byte payload. Old capture: 13 uploads (setup-heavy session);
new capture: 2, identical, at connect.

- idx0: 01 constant. idx12: 03 in the old session, 04 in the new
  (session-level difference, unidentified).
- idx4: flipped 0→1→0 during old-capture setup only.
- idx6: stepped 0→2→3→1 during old-capture setup (~2–4 s apart,
  then settled at 1 for the rest of the session); 0 in the new
  capture. A 0–3 selector of *something* set at setup time —
  NOT the pin (it never moved during the new capture's round).
- idx7/idx8: a paired field that shifted 01/00 → 00/01 once in
  the old session, at the same moment idx6 first stepped.
- The old session's four 0x0a selector pairs ([1|2]×[3|4] + 04)
  also fell inside that same setup window. Whatever the setup
  screens were configuring, it was configured before the round.

## Buttons: still uncaptured (third time unlucky)

Definitive AVRCP passthrough hunt (loose offsets, both captures):
**zero candidates.** In the new log the AVRCP channel goes quiet
after ~81 s and the SLX BLE session ends ~76 s; the narrated
button phase appears to have happened after both links were
down, so the presses never reached the log. The only AVRCP life
is at 9–17 s (playback status notifications playing→stopped→
playing, before the BLE session) — consistent with starting
music from the phone. If this is retried: press the buttons
WHILE the round and music are live, and take the bug report
before disconnecting anything.

User's own hypothesis for the retry (2026-10-23): the device may
need to be in **speaker mode** (the STATE=READY,MOTION=TRUE state)
for its media buttons to act at all — during GPS-mode rounds the
reports read MOTION=NONE, and the buttons may simply be inert
there. Fits all evidence so far. Retry plan: snoop on → X3 in
speaker mode → music playing through SLX-SPEAKER → press Vol+ ×3,
Vol− ×3, Play/Pause ×3 on the device → bug report BEFORE
disconnecting or switching modes.

**Hardware fact (user, 2026-10-23): the X3's power button,
single-pressed, toggles the device between GPS/launch-monitor
mode and speaker mode** — that is the physical control behind
the MOTION=TRUE/NONE state reports.

Third capture narrated 2026-10-23 (awaiting analysis): connect →
YouTube Music playing → volume presses + play/pause (speaker
mode) → power-press to GPS mode → connect GPS → pin cycled
again.

## Fourth capture — exact narration (user, 2026-10-23 09:22)

Stock app. Fresh Bluetooth pairing → open app → select GPS →
Demo → Start → change hole → pin button ×1 (Center → **Back**) →
pin button ×1 (Back → **Front**) → **power press (readout, pin=
Front)** → pin button ×1 (Front → Center) → **power press
(readout, pin=Center)** → pin button (Center → Back) → **power
press (readout, pin=Back)** → volume up/down pressed (nothing
happened, GPS mode) → device Bluetooth button (speaker mode,
fell back to yardage after ~a second) → **power press (readout)**
→ power off.

Two facts banked from this narration alone:
- **Stock pin cycle order is Center → Back → Front** (not the
  C → F → B our v0.39 implemented from an earlier loose
  description). Ours must be re-ordered to match.
- The three readouts at three different pin selections make the
  log decisive for what the readout speaks: compare the record
  values the device held at each press.

## Side observation

The 0x05 reply's wandering byte ("172" + byte): 59–64 in the old
session, 99–100 in the new one. Session-scale difference plus
slow wander is consistent with a battery gauge. Unproven.

---

## Correction (2026-10-23, later the same day)

This document's headline verdict — "there is no label command;
the FP/CP/BP label cannot be driven by the app" — is **wrong**.
It is true that pin changes emit no SEPARATE command. But the
label is driven by the distance records themselves: the record
FLAG byte is the pin selector (0x01 = FP, 0x02 = CP, 0x03 = BP),
proven when flipping our live records from flag 0x02 to 0x01
moved the device's displayed label from CP to FP, and confirmed
for all three pins in v0.48, which sends flag = selected pin.
The mid-round pin taps in capture 2 changed nothing on the wire
because the stock app doesn't need a command: the selection
rides in the records it is already streaming.
