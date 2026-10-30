# Send to Car

Share a place on your phone, and Waze on the car's head unit starts navigating to it.
Built for an FYT UIS8141E unit (Android 8.1) whose notification shade shows no notifications.

One APK runs on both devices. On first launch, pick **car** on the head unit. On the phone, tap
**Scan the car's QR code to pair** (or scan the car's QR with the camera app). The phone saves the
car's code, IP address and Bluetooth identity, then tests every channel. Typing the 8-character
code by hand also works.

When a place arrives, the car shows a notification and opens a full-screen "Navigate to" card
directly. The map app is Waze unless you pick another in the car's settings. On the FYT unit the card is what you see, since its shade shows no notifications. On
newer Android, which blocks pop-ups from the background, the notification is the way in. Both
offer **Navigate (Waze)** and **Other app** (pick any installed map app).

## Install and update with Obtainium

1. In [Obtainium](https://github.com/ImranR98/Obtainium), tap **Add App**.
2. Paste `https://github.com/nikhil914/Car-location-share` and add it.
3. Install it. Obtainium updates it whenever a new release appears here.

Do this on both the phone and the car unit. Every release is signed with the same key, so
updates install over the previous version.

## How a place reaches the car

The phone sends each place over three channels at once. The car uses whichever copy arrives
first and ignores the rest.

| Channel | How |
| --- | --- |
| Wi-Fi | The car runs an HTTP server on port 47800. The phone tries the last known IP, then sweeps its local /24 networks (the hotspot). |
| Bluetooth | The car listens on an insecure RFCOMM socket. The phone tries each paired device. |
| Internet | An ntfy.sh topic named from the pairing code. The car acknowledges on `<topic>-ack` and catches up on places from the last 12 h. |

A place sent as a text file by Bluetooth file transfer can also be read on the car (car test 3).

On the car, a full-screen "Navigate to" card opens. It either starts navigation after a countdown
or waits for you to tap, depending on the setting.

## The car screens

The car side is Jetpack Compose (it runs on the Android 8.1 unit) with three surfaces:

- **Dashboard**, the driving screen: a status pill (receiver state, the car's Wi-Fi IP, which
  channels are up, when the phone was last heard; tap it for details), the place that just arrived
  with a large **Start navigation** button plus Copy coords / Save / Dismiss, and the last places as
  one-tap chips (a bottom row, or a side rail on screens wider than 2:1).
- **Destinations**: received places with time and coordinates, saved places, and Clear.
- **Settings**: Display (theme, accent, keep screen on), Receiver (turn Wi-Fi, Bluetooth or the
  internet relay on or off, Display over other apps), Navigation (countdown or wait, the map app:
  Waze, Google Maps, OsmAnd, Sygic or ask every time) and Pairing & debug (pairing QR, status,
  the probe tests, log, role switch).

Touch targets are at least 56 dp (64 dp for most buttons, 76 dp for Start navigation) with 16 dp
gaps, and nothing on the dashboard scrolls.

### Themes

| Theme | Look |
| --- | --- |
| Clean (default) | Charcoal `#121316`, borders `#26282E`, text `#EDEDED`, cyan or orange accent on Start navigation only |
| True black | `#000000`, dim slate text, dark amber button: keeps night vision |
| Daylight | `#F4F5F7`, text `#111113`, cobalt `#0047B3` |
| Auto | True black at night, Clean or Daylight by day |

Auto decides night by the best signal the unit has: the headlights (FYT `com.syu.ms` illumination
wire, as in CarRadio), then the system night mode, then the light sensor, then sunset to sunrise
at the car's GPS position (Abu Dhabi until it has a fix).

To add a theme, add a `Palette` in `Palettes.kt` and a `ThemeOption` that returns it from
`Palettes.of`. `PaletteTest` checks every palette: primary text and the Start navigation label
must reach 7:1 contrast (WCAG AAA) and muted text 4.5:1. The icons are Material icons inlined in
`Icons.kt`.

## 0.1.x is a probe

These versions include on-screen tests to confirm each channel on the real unit. Run them, then
send the car report as QR codes or over the internet.

## Releasing

Bump `versionCode` and `versionName` in `app/build.gradle.kts` and push to `main`. CI tests,
builds, signs and publishes release `v<versionName>` if it doesn't exist yet.

Signing needs two repository secrets (Settings → Secrets and variables → Actions):
`SIGNING_KEYSTORE_BASE64` and `SIGNING_PASSWORD`. Without them, CI still builds a debug-signed
APK as an artifact but publishes no release.
