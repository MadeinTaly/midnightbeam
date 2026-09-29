# Overlay Dimmer

A tiny screen dimmer and warm (red) filter for **Android TV boxes, projectors and TVs** that can be controlled
**live from ADB or Home Assistant**, as well as with the TV remote.

It draws a click-through overlay above everything, video included, like the usual blue-light filter apps.
The difference is that changes apply instantly, with no flash to full brightness. Other filter apps usually have
to be restarted to pick up new values from automation tools.

- **Live control** from `adb shell` / Home Assistant: warm filter, brightness, colour temperature
- **Phone remote**: scan a QR code on the TV and control it from any phone browser (Android or iPhone),
  no app to install; the page works offline, on the local network only
- **Daily schedule**: a timeline of time slots (movie night, early to bed…) that the TV applies by itself,
  even with the phone off; shown as a bar at the bottom of the TV screen
- **Remote-friendly settings screen**: only D-pad and OK are needed, with a visible focus highlight
  for the many TV-box themes that show none
- **Restores the last setting at boot**, early: the boot receiver has a high priority, because on some boxes
  `BOOT_COMPLETED` reaches apps one at a time and can take over a minute
- **Tiny**: ~55 KB APK, plain Java, no tracking; the network is used only by the optional phone remote,
  inside your home network (no outgoing connections)
- Android 8.0+ (API 26), phones work too

![Settings screen, focused row highlighted](docs/settings-screen.png)
<sub>The settings screen as rendered by the app on a 1080p Android TV projector (the focused row is highlighted).</sub>

![Filter effect on a test pattern](docs/filter-simulation.png)
<sub>Effect on a test pattern, simulated with the same colour and blending formula the app uses
(real screen captures do not include overlays on many TV boxes).</sub>

## Install

1. Download `overlay-dimmer.apk` from the [latest release](../../releases/latest).
2. Install and allow drawing over other apps:

   ```sh
   adb install overlay-dimmer.apk
   adb shell appops set dev.overlaydimmer SYSTEM_ALERT_WINDOW allow
   ```

   Without ADB: install the APK with a file manager, then enable
   *Settings → Apps → Special app access → Display over other apps → Overlay Dimmer*.
3. Open **Overlay Dimmer** from the app list once and set it up.

## Remote control

Open the app from the launcher:

| Key | Action |
|---|---|
| Up / Down | move between the switch and the sliders |
| Left / Right | change the focused value (applied immediately) |
| OK | switch the filter on / off |

## Phone remote and schedule

1. In the app on the TV, turn on **Phone remote**: a QR code appears.
2. Scan it with the phone camera. The page opens in the browser; *Add to Home screen* makes it an app icon.
3. Move the sliders or pick a preset: the TV follows instantly.
4. **Schedule**: pick a day preset, tap the icon of a time slot to choose its setting (a profile or a custom
   one), rename or remove it, add slots, drag the edges between slots to set the times, then
   **Apply to TV**. The TV switches slot by itself; a manual change lasts until the next slot.
   **Save as day** keeps a named day on the TV, available to every paired phone.

The page is in English, or Italian on Italian phones (switchable with EN | IT); the TV app follows the
device language.

The QR code carries a random pairing key: requests without it are refused. **New pairing code** on the TV
disconnects every paired phone. The remote works only while the phone is on the same network as the TV.

HTTP API (port 8765, header `X-Key: <pairing key>`), handy for scripts:

```sh
curl -H "X-Key: $KEY" http://TV-IP:8765/api/state
curl -H "X-Key: $KEY" -H "Content-Type: application/json" -d '{"red":60,"bright":40}' http://TV-IP:8765/api/set
curl -H "X-Key: $KEY" http://TV-IP:8765/api/schedule
curl -H "X-Key: $KEY" http://TV-IP:8765/api/days
```

## ADB commands

```sh
# warm filter 0-100, brightness 5-100 (100 = no dimming), colour temperature 1000-6500 K
adb shell am start-foreground-service -n dev.overlaydimmer/.DimService --ei red 60 --ei bright 40 --ei temp 1100

# change a single value, the others are kept
adb shell am start-foreground-service -n dev.overlaydimmer/.DimService --ei bright 70

# off
adb shell am start-foreground-service -n dev.overlaydimmer/.DimService --ez off true

# phone remote on / off
adb shell am start-foreground-service -n dev.overlaydimmer/.DimService --ez remote true

# current state
adb shell dumpsys activity service dev.overlaydimmer/.DimService
```

Lower colour temperature means redder: 1000-1200 K is a deep red, 1800 K orange, 3000 K+ yellowish.

The service can only be controlled by the app itself and from `adb shell` / root. It is protected by the
`DUMP` permission, so other apps cannot change it.

## Home Assistant

The [`homeassistant/`](homeassistant) folder has a ready-made example:

- [`overlay_dimmer.yaml`](homeassistant/overlay_dimmer.yaml): package with sliders, presets and state
  sensors, using the built-in **Android Debug Bridge** integration (`androidtv.adb_command`)
- [`dashboard.yaml`](homeassistant/dashboard.yaml): example dashboard
- [`status.sh`](homeassistant/status.sh): optional script that prints the state as JSON for the sensors

Replace `media_player.my_tv` with your own entity and copy `status.sh` to the device:

```sh
adb push homeassistant/status.sh /data/local/tmp/overlay-dimmer-status.sh
```

## Build

No Gradle and no Android SDK manager. On Debian / Ubuntu:

```sh
sudo apt install openjdk-17-jdk-headless dalvik-exchange aapt zipalign apksigner android-sdk-platform-23
./build.sh
```

With the Android SDK instead of the Debian packages:

```sh
BUILD_TOOLS=$ANDROID_HOME/build-tools/34.0.0 ANDROID_JAR=$ANDROID_HOME/platforms/android-28/android.jar ./build.sh
```

The first build creates a local `debug.keystore` to sign the APK.

The phone remote page is `web/remote.html`, styled with Tailwind CSS compiled at development time
(`web/build-css.sh`, needs Node). The generated `app/assets/remote.css` is committed, so building the APK
does not need Node and the page has no CDN dependency. APKs signed with different keys cannot
update each other, so uninstall the release build before installing your own.

## Notes

- It is an overlay: it darkens and tints the picture but cannot lower the real backlight or LED power.
- The colour comes from Tanner Helland's black-body approximation
  ([source](https://tannerhelland.com/2012/09/18/convert-temperature-rgb-algorithm-code.html)).

## Third-party

- [QR Code generator library](https://www.nayuki.io/page/qr-code-generator-library) by Project Nayuki,
  MIT License (`app/src/io/nayuki/qrcodegen`, unmodified)
- [Tailwind CSS](https://tailwindcss.com), MIT License (compiled into `app/assets/remote.css`)

## Support

If Overlay Dimmer is useful to you, you can support its development here: https://gt.1471995.xyz

## License

[MIT](LICENSE)
