# SLX Hybrid X3 — Bluetooth Recon Guide

Goal: capture exactly what the SLX Connect app sends to the X3 and what the
X3 sends back, so we can rebuild those commands in our own app.
Two passes: (1) map the device's BLE services, (2) record a full session.

You need: your Android phone, the X3 charged, ~30 minutes.
No accounts anywhere in this process.

## Pass 0 — Turn on developer options (once)

1. Settings → About phone → tap **Build number** 7 times.
2. Settings → System → **Developer options** → turn on
   **Enable Bluetooth HCI snoop log**.
3. Toggle Bluetooth off, then on (this starts the capture).
   From now on the phone records ALL Bluetooth traffic to a log file,
   in plain readable form — including the SLX app's session.

## Pass 1 — Enumerate the device (nRF Connect)

1. Install **nRF Connect for Mobile** (Nordic Semiconductor, free).
2. Open SLX Connect first and make sure the X3 is *disconnected* there
   (the X3 only allows one data connection at a time).
3. In nRF Connect: Scan → find the X3 (the data device, **not**
   "SLX-SPEAKER" — that's the audio side) → **Connect**.
4. Screenshot every expanded service and characteristic: we need each
   service UUID and each characteristic UUID + its properties
   (READ / WRITE / NOTIFY).
5. Tap the ⤓/log icon if offered — a saved nRF log is a bonus.
6. Disconnect when done.

Send me: the screenshots (or the log). This gives the "map" of what exists.

## Pass 2 — Capture a real session (HCI snoop log)

Do this with everything else Bluetooth OFF if you can (watch, earbuds) —
less noise to dig through. Work slowly: **one action, pause ~5 seconds,
note the clock time**, next action. The timestamps are how we line packets
up with actions.

1. Open SLX Connect, connect to the X3. Note the time.
2. Do each of these, one at a time, noting the time of each:
   - Just connect and sit idle 30 seconds (baseline chatter)
   - Check battery level in the app
   - Change a setting (e.g. units yards ↔ meters)
   - Start a GPS round / enter GPS mode
   - Trigger a distance announcement (the button press you use on course)
   - Anything else you actually use — nothing swing-related needed
3. Disconnect from the X3 in the app.
4. Developer options → **Take bug report** → wait for it to finish →
   save/share the .zip to yourself.
   The Bluetooth log (`btsnoop_hci.log`) is inside that zip.
   (On some phones it also sits at
   `Internal storage/Android/data/btsnoop_hci.log` — either source works.)
5. Turn the HCI snoop log OFF again (it records all BT traffic and the
   file grows; no reason to leave it on).

Send me: the `btsnoop_hci.log` file (or the whole bug-report zip) plus
your timestamped action list. I'll open it in Wireshark, filter to the
X3's traffic, and turn it into a command map: "this byte string = ask
distance, this notification = battery 82%", etc.

## Optional accelerator — the stock app itself

If you want, also grab the SLX Connect app's APK with any free
"APK Extractor" app and send it to me. The command formats are usually
sitting in the app's code in readable form, which confirms what the
log shows and can save a full round of guessing.

## What happens next

1. I turn the logs into a protocol map (services, commands, responses).
2. We agree the app's screens: speaker + EQ, GPS distance, nothing else.
3. I build the Android app; you install the APK directly — no accounts.
