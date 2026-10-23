# SLX Hybrid X3 — Protocol Map v6 (device → phone direction)

Addendum to v4/v5, 2026-10-23. The earlier maps cover what the
phone sends; this one decodes what the DEVICE sends back, from a
re-scan of the original btsnoop (274 device frames). Our app
currently uses only the poll replies; the rest is decoded here for
the record.

## Device frame types (notifications on handle 0x000f)

- `0x00` — **ACK**: payload [command-type][0x00] echoes whatever
  the phone just sent (seen: 01, 17, 18, 1c, 51, 5f). 97 frames.
- `0x52` ('R') — ASCII state report: `STATE=READY,MOTION=NONE` /
  `STATE=READY,MOTION=TRUE`, plus a bare `Start` at round start.
  (MOTION=TRUE = the device itself is in Bluetooth/speaker mode —
  the user's own correction, see v4.)
- `0x06` — poll reply, payload ASCII `172`, constant.
- `0x05` — poll reply, payload ASCII `172` + one varying byte
  (0x3b–0x40 across the session) + zeros. The varying byte wanders
  slowly (59–64); identity unknown — a live device metric, not an
  event. Worth watching in future captures.
- `0x01` — poll reply, 1-byte values (01, 0e, 03, 04, 02, 06…).
- `0x53` ('S') — payload `0x80`, seen in identical pairs ~0.2 s
  apart. Unidentified.
- `0x04` — 1-byte payloads 0x3e / 0x3d. Unidentified.
- `0x56` — config reply to the 0x62 fetch (known since v1).

## Buttons (volume +/−, play/pause): NOT in this capture

The user asked (2026-10-23) whether the device's volume and
play/pause buttons send decodable messages. Full scan of the
capture's non-ATT channels: **zero AVRCP passthrough commands**
and no unsolicited BLE frame that looks like a button event —
nobody pressed those buttons during the demo-round capture, so
this log can neither confirm nor deny a BLE-side button message.

Prior knowledge: for a Bluetooth speaker, volume and play/pause
normally travel as AVRCP over the SLX-SPEAKER audio connection
(the phone OS routes them to the media app / system volume) —
that path needs no app at all. Whether the X3 ALSO reports
presses on the BLE data channel is the open question.

## To settle it (one capture)

HCI snoop on → connect in SLX Connect → music playing through
SLX-SPEAKER → press on the DEVICE: Vol+ ×2, Vol− ×2, Play/Pause
×2, a few seconds apart, noting the order → bug report. Diff
AVRCP frames, BLE notifications, and the 0x05 wandering byte
around each press.

## SETTLED 2026-10-23 by the third capture (device in speaker mode)

- **Play/Pause: standard AVRCP passthrough.** Frames
  `[AVCTP][0x110e][0x0048][0x7c][op][0x00]`, op 0x44 = PLAY,
  0x46 = PAUSE, each as a press frame + a release frame
  (op | 0x80). Five clean presses logged (PAUSE, PLAY, PAUSE,
  PLAY, PAUSE) with the phone accepting and toggling playback.
  NOTE: passthrough frames carry NO 001958 company id — the
  first scans keyed on that byte pattern and missed them;
  captures 1–2 were re-hunted with the corrected pattern
  (`48 7c <op>`) and genuinely contain no presses (their links
  were down by press time).
- **Volume: NO commands cross.** The X3 changes its own volume
  and reports the result via AVRCP VOLUME_CHANGED notifications
  (event 0x0D), absolute level 0–127 in steps of 8. The phone
  only mirrors it (and can SetAbsoluteVolume, PDU 0x50, the
  other way). The narrated down/up sweeps are all in the log.
- Both mechanisms are OS-level Bluetooth behavior: they work
  with YouTube Music regardless of any app. Nothing proprietary
  to clone, and nothing our app needs to do.

## The 0x54 frame: power-button readout event (fourth capture)

2026-10-23, exact narration. In a stock demo round, each
power-button press for the yardage readout produced exactly one
device frame: **type 0x54, payload `0001`**, on the normal notify
handle — four presses, four frames, nothing else in the session
matches them. The stock app sends NOTHING in response (the next
phone frame is the routine cadence record ~1 s later). Our app
receives these frames too and has never acted on one — which is
exactly why the readout is silent under our feed IF the voice is
the phone's (stock-app TTS answering the event). If the voice is
the X3's own speaker, some session element still differs and the
hunt continues. Asked the user which speaker the voice uses;
that answer routes the fix. Values the device held at the four
presses: 432 yd (pin Front), 489 (Center), 67 (Back), 147 (after
the speaker-mode episode). (Also: the session's 0x05 byte read
0x63–0x64 again — consistent with the battery-gauge reading.)

**Routing answer (user, 2026-10-23): the voice comes from the
X3 itself.** So the device speaks autonomously on the press and
the 0x54 is merely its report; some session state arms the
readout under the stock feed and not under ours. Eliminated by
capture 4 itself: the 0x18 table (absent that session, readouts
worked) and any app response to the press (none is sent).
Confound to clear first: every successful readout was a stock
DEMO round, every silent one a LIVE round in our app — app and
mode differ together. Decisive no-build test: stock app, LIVE
(non-demo) round, press power. If silent there too, the readout
is demo-only and nothing is missing from our feed. If it speaks,
remaining feed differences are the 0x17 payload byte idx3
(stock now sends 00, we send 01; capture-1 stock also sent 01)
and our extra 0x5a trio at feed start (captures 2–4 stock sent
none).

Byte-hunt results (user-tested at home via the v0.43 Fake
Course, realistic ~400 yd in the device): the range theory died
first (still silent at 400 yd). v0.44 added the stock 0x5f
settings upload at connect — **no change**. v0.45 flipped the
0x17 byte idx3 to 00 — **no change**.

**The our-app snoop (capture 5, 2026-10-23 09:58) cracked it.**
The device DOES emit 0x54 under our feed — three presses, three
events, payload 0001, exactly as under stock. The press
registers fine; the device simply never speaks. Diffing our
record stream against stock's exposed why: **every live record
our app has ever sent carries flag 0x02** — a v0.9-era choice,
annotated "0x01 vs 0x02 made no difference" (true of the
DISPLAY sweep, the only thing tested then). 0x02 is the
hole-start/reset flag; stock's ordinary live updates are flag
0x01, and every speaking stock session had flag-0x01 records
flowing. A value the device only ever received as a "reset" is
apparently not one it will say out loud. **v0.46**: live values
at flag 0x01 (zero reset stays 0x02, as stock does); the 0x5f
upload and the 0x17 byte from v0.44/v0.45 stay — both are
stock-faithful regardless.

Readout content (user, 2026-10-23): under the stock app the
spoken readout STARTS WITH THE HOLE NUMBER ("Hole N, …yards") —
the device composes the announcement from its round state
(current hole + distance), not from the last value alone. The
hole reaches it via the record rec field, which our feed sends;
what our sessions may still lack is whatever latches its
"round in progress" state. (v0.47 added the spaced write queue;
still silent. v0.48 added the 0x1c and wired the record flag to
the selected pin. v0.49: lone once-per-round 0x17, no 0x5a —
silent. v0.50: the A/B "Start pair" — silent.)

**Reframe (user observation, 2026-10-23 11:18):** the X3's
green light blinks on a power press under BOTH apps. The device
attempts the readout in our sessions too — the trigger was
never the problem; the AUDIO is what's missing. The hunt moves
from "arming" to per-session audio state. Prime suspect:
settings byte 5 — our upload carried capture 4's 0x00, while
capture 3 and the A/B stock app upload 0x03. v0.51 uploads
byte 5 = 0x03 (verified byte-for-byte in the delivered APK);
device-side volume checks (VOL+ in GPS mode) run in parallel.

**v0.51 result — byte 5 has teeth (2026-10-23 11:23):** with
byte 5 = 0x03 uploaded, the device went visibly wrong: green
LED blinking constantly, and the displayed hole switching
1↔2 at random when the user tapped the pin button. Byte 5 is
a device mode selector, not a volume knob. REVERTED in v0.52
(byte 5 back to 0x00, byte-verified in the APK). Rule: never
experiment with settings bytes on the user's device again —
characterize from captures only. The readout hunt is paused
at the LED reframe: the device attempts the readout under our
app; the missing audio remains unexplained, but the settings
block is off-limits as a probe.

**LED correction (user, 2026-10-23 11:33):** the green light
tracks the SPEAKER (A2DP) connection — it blinks the same way
under both apps and has nothing to do with GPS or the readout.
So the "device tries to speak" inference from the press blink
was wrong: the blink is speaker-link activity. v0.51's
constant blinking was the byte-5 mode scramble disturbing
that link; v0.52 + a power cycle restored identical behavior
in both apps. Recovery confirmed by the user. The readout
itself remains unsolved and is PARKED: pins, feed, EQ and
controls all work; the device-side voice readout under our
feed is the one known gap, with no remaining probe that is
both safe and evidence-led.
