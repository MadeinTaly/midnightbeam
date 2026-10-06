# MidnightBeam privacy policy

MidnightBeam does not collect, store or send any personal data.

- **No accounts, no analytics, no ads, no tracking.** The app contains no third-party SDKs.
- **No internet connections.** The network is used only on your local network:
  - on a TV, by the optional *Phone remote* (a small web server on port 8765, protected by a random pairing code
    shown on the TV screen);
  - on a phone, to reach the TVs you paired, and by the app's own screen on `127.0.0.1` (the phone itself).
- **Local network announcement:** while *Phone remote* is on, a TV announces itself on the local network (mDNS,
  `_midnightbeam._tcp`) with its device name, its type (TV, projector…) and a random id, so phones running the app can
  find it. The pairing code is never announced.
- **Audio (optional):** the audio controls change the device's own volume and sound effects. If you allow it with adb
  (`DUMP`), the app reads which audio sessions are playing on the device, to apply the effects to them; this
  stays on the device.
- **What stays on the device:** the filter settings, the schedule, saved days and scenes, the pairing code and,
  on a phone, the list of paired TVs (name, address, pairing code). Nothing leaves your local network.
- **Permissions:** *Display over other apps* draws the filter; the optional *Accessibility* service only lends its
  window to the filter, it does not read the screen or your input; *Run at startup* restores the filter after a reboot;
  *Network* is for the local remote described above.
- The **Download the Android app** button and the links in the settings open GitHub pages in your browser only when
  you tap them.

Questions: open an issue on https://github.com/MadeinTaly/midnightbeam/issues

Last updated: 2026-10-06
