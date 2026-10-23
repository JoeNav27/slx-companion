# SLX Companion — Android app for the SwingLogic SLX Hybrid X3

An unofficial, free, no-account Android companion app for the SwingLogic
SLX Hybrid X3 (SLX100H) golf GPS / Bluetooth speaker.

This project exists for owners who mainly use the X3 as a Bluetooth
speaker and on-course GPS and want a simpler app for those jobs. It does
not require a SwingLogic account or cloud service. The app talks to the
device directly over BLE (Nordic UART Service) using the stock protocol,
reverse-engineered from HCI snoop captures of the official app.

**Not affiliated with or endorsed by SwingLogic or Ulike.** “SLX” and
“SwingLogic” are used only to identify the compatible hardware.

Built by JoeNav27.

## Download

Download `slx-companion-v0.55.apk` from the **Releases** tab and install
it on an Android 8.0 or newer phone. Android may ask you to allow installs
from your browser or file manager because this is distributed directly,
not through Google Play.

SHA-256 for the v0.55 public APK:

`69dbaf791ede14445d1bc1e020802c9f2638221486bb7250f23d04825f80a7fb`

The public APK contains no device-specific Bluetooth address. It connects
to any nearby Bluetooth device whose advertised name starts with “SLX”
and speaks this model's stock protocol.

## What it does

- **GPS screen as home:** live distance to the green from the phone's
  GPS, in yards. Download a course once and it is cached on the phone.
- **Front / Center / Back pin targets:** measured from the mapped green
  shape. The selected target is shown in the app and pushed to the X3,
  including the device's FP / CP / BP label.
- **Device display feed:** while the app is open, a course is loaded,
  and the BLE link is live, yardages are pushed to the X3's own display
  using the stock type-0x01 distance records.
- **Works with the phone locked:** a foreground service keeps GPS and
  the device feed live during a round.
- **Hole preview:** illustrated hole map drawn from OpenStreetMap
  shapes—mapped fairway polygons, bunkers, water, cart paths, tees,
  and greens—fetched once per course and cached.
- **Tap to measure:** tap a point on the hole preview to see its
  distance from your current position.
- **Hazard distances:** shows the nearest mapped water and bunker
  distances when that geometry is available.
- **Measure Shot:** mark where a shot was played and see the live
  distance as you walk to the ball.
- **Home-screen widget:** current course, hole, yardage, and pin target.
- **Speaker Settings:** an equalizer that attaches to YouTube Music's
  audio session. Music plays through the X3's separate system Bluetooth
  pairing (SLX-SPEAKER, A2DP); the BLE connection is data-only.
- **No swing analysis:** that part of the product is intentionally out
  of scope.

Walking within about 25 yards of the green triggers a spoken
announcement through the phone's selected audio output and advances to
the next hole.

## Compatibility and limits

- Developed and field-tested against one SLX Hybrid X3 (SLX100H).
  Other hardware revisions may behave differently; other SwingLogic
  models are not targeted.
- No pairing or authentication is used on the BLE link because the
  device itself does not require it.
- Course data comes from golfclubxy (free and keyless). Hole shapes
  come from OpenStreetMap contributors. Coverage and mapping quality
  vary by course.
- Known gap: under the official app, pressing the X3 power button in
  GPS mode can trigger the device's spoken yardage readout. That
  readout has not been made to work under SLX Companion despite
  extensive protocol captures and testing. The app itself can announce
  yardages through the phone/speaker, but the X3's built-in power-button
  readout remains stock-app-only for now.

## Privacy

- No account.
- No ads.
- No analytics or tracking.
- No network calls except course downloads and OpenStreetMap shape
  fetches initiated by the user.
- Courses and shapes are cached locally on the phone.

## What's in the repo

| Path | What it is |
|---|---|
| `android-app/` | Full Android source (plain Java, zero dependencies) |
| Releases tab | Installable public APKs |
| `docs/protocol-map-v1..v7.md` | Reverse-engineered BLE protocol notes, in the order the work developed; v4 is the earlier master map, while v6–v7 record later device-to-phone, pin-label, and readout findings |
| `docs/ble-recon-guide.md` | How the protocol was captured and decoded |
| `docs/course-data-sources.md` | Course-data source evaluation |

## Build / install

Install the APK from Releases, or build from `android-app/`. The
included `build-apk.sh` documents the direct aapt2/javac/d8 pipeline
used for the shipped builds on the author's machine; the project also
contains basic Gradle files.

## Status

Working and in regular use by the author. Reports from other X3 owners
are welcome—especially whether it connects cleanly to other hardware
revisions and how the course mapping looks in other regions.

## License

MIT. See `LICENSE`.
