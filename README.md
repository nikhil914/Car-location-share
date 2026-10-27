# Send to Car

Share a place on your phone, and Waze on the car's head unit starts navigating to it.
Built for an FYT UIS8141E unit (Android 8.1) whose notification shade shows no notifications.

One APK runs on both devices. On first launch, pick **car** on the head unit. On the phone, tap
**Scan the car's QR code to pair** (or scan the car's QR with the camera app). The phone saves the
car's code, IP address and Bluetooth identity, then tests every channel. Typing the 8-character
code by hand also works.

When a place arrives, the car shows a notification and opens a full-screen "Navigate to" card
directly. On the FYT unit the card is what you see, since its shade shows no notifications. On
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

On the car, a full-screen "Navigate to" card opens. It either starts Waze after a countdown or
waits for you to tap Go, depending on the setting.

## 0.1.x is a probe

These versions include on-screen tests to confirm each channel on the real unit. Run them, then
send the car report as QR codes or over the internet.

## Releasing

Bump `versionCode` and `versionName` in `app/build.gradle.kts` and push to `main`. CI tests,
builds, signs and publishes release `v<versionName>` if it doesn't exist yet.

Signing needs two repository secrets (Settings → Secrets and variables → Actions):
`SIGNING_KEYSTORE_BASE64` and `SIGNING_PASSWORD`. Without them, CI still builds a debug-signed
APK as an artifact but publishes no release.
