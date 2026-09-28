# Overlay Dimmer

A tiny screen dimmer and warm (red) filter for **Android TV boxes, projectors and TVs** that can be controlled
**live from ADB or Home Assistant**, as well as with the TV remote.

It draws a click-through overlay above everything, video included, like the usual blue-light filter apps.
The difference is that changes apply instantly, with no flash to full brightness. Other filter apps usually have
to be restarted to pick up new values from automation tools.

- **Live control** from `adb shell` / Home Assistant: warm filter, brightness, colour temperature
- **Remote-friendly settings screen**: only D-pad and OK are needed, with a visible focus highlight
  for the many TV-box themes that show none
- **Restores the last setting at boot**, early: the boot receiver has a high priority, because on some boxes
  `BOOT_COMPLETED` reaches apps one at a time and can take over a minute
- **Tiny**: ~20 KB APK, plain Java, no libraries, no internet permission, no tracking
- Android 8.0+ (API 26), phones work too

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

## ADB commands

```sh
# warm filter 0-100, brightness 5-100 (100 = no dimming), colour temperature 1000-6500 K
adb shell am start-foreground-service -n dev.overlaydimmer/.DimService --ei red 60 --ei bright 40 --ei temp 1100

# change a single value, the others are kept
adb shell am start-foreground-service -n dev.overlaydimmer/.DimService --ei bright 70

# off
adb shell am start-foreground-service -n dev.overlaydimmer/.DimService --ez off true

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

The first build creates a local `debug.keystore` to sign the APK. APKs signed with different keys cannot
update each other, so uninstall the release build before installing your own.

## Notes

- It is an overlay: it darkens and tints the picture but cannot lower the real backlight or LED power.
- The colour comes from Tanner Helland's black-body approximation
  ([source](https://tannerhelland.com/2012/09/18/convert-temperature-rgb-algorithm-code.html)).

## License

[MIT](LICENSE)
