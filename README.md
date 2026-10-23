# SLX Companion — Android app for the SwingLogic SLX Hybrid X3

A no-account Android companion app for the SwingLogic SLX Hybrid X3
(SLX100H) golf GPS / Bluetooth speaker. Built because I only use the
X3 as a Bluetooth speaker and an on-course GPS — swing analysis not
wanted, and no SwingLogic account or cloud service anywhere in the
loop. The app talks to the device directly over BLE (Nordic UART
Service) using the stock protocol, reverse-engineered from HCI
snoop captures of the official app.

Built by JoeNav27.

## What it does

- **GPS page (home screen):** live distance to the green from the
  phone's GPS, in yards. Download a course once and it's cached on
  the phone; Front / Center / Back pin targets are measured off the
  mapped green shape. Walking within ~25 yd of the green triggers a
  spoken announcement and auto-advance to the next hole.
- **Device display feed:** while the app is open, a course is
  loaded, and the BLE link is live, the yardage is pushed to the
  X3's own display using the stock type-0x01 distance records —
  byte-identical to what the official app sends.
- **Hole preview:** illustrated hole map (fairway strip, rough,
  green, bunkers, water, cart paths) drawn from OpenStreetMap
  shapes, fetched once per course and cached.
- **Music EQ:** an equalizer + bass boost that latches onto the
  YouTube Music audio session. All audio (music and spoken
  distances) plays through the X3's separate system Bluetooth
  pairing (SLX-SPEAKER, A2DP) — the BLE link is data-only.

## Compatibility

- Connects to any Bluetooth device whose advertised name starts
  with "SLX" and speaks this model's stock protocol. Developed and
  tested against one SLX Hybrid X3 unit; other SwingLogic models
  are not targeted.
- No pairing or authentication on the BLE link.
- Course data comes from golfclubxy (free, keyless). Coverage
  varies by course — some courses have incomplete source data.

## What's in the repo

| Path | What it is |
|---|---|
| `android-app/` | Full Android source (plain Java, zero dependencies) |
| Releases tab | The installable APK (v0.33) is attached to the v0.33 release as a download |
| `docs/protocol-map-v1..v5.md` | The reverse-engineered BLE protocol, in the order it was figured out (v4 is the master; v5 covers the pin-label investigation) |
| `docs/ble-recon-guide.md` | How the protocol was captured and decoded |
| `docs/course-data-sources.md` | Course data source evaluation |

## Build / install

Sideload the APK from the Releases tab, or build from source: `android-app/` is a plain
Android project (Gradle), and `android-app/build-apk.sh` documents
the manual aapt2/javac/d8 pipeline used to produce the shipped
APKs. The app holds no accounts and makes no network calls except
course downloads and OpenStreetMap shape fetches.

## Status

Working and in regular use. One known gap: the X3's own FP/CP/BP
pin label does not follow the app's pin selection (the yardage
itself does). See `docs/protocol-map-v5.md` for what's been ruled
out and what would settle it.
