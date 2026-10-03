# MidnightBeam privacy policy

MidnightBeam does not collect, store or send any personal data.

- **No accounts, no analytics, no ads, no tracking.** The app contains no third-party SDKs.
- **No internet connections.** The network is used only on your local network:
  - on a TV, by the optional *Phone remote* (a small web server on port 8765, protected by a random pairing code
    shown on the TV screen);
  - on a phone, to reach the TVs you paired, and by the app's own screen on `127.0.0.1` (the phone itself).
- **What stays on the device:** the filter settings, the schedule, saved days and scenes, the pairing code and,
  on a phone, the list of paired TVs (name, address, pairing code). Nothing leaves your local network.
- **Permissions:** *Display over other apps* draws the filter; the optional *Accessibility* service only lends its
  window to the filter, it does not read the screen or your input; *Run at startup* restores the filter after a reboot;
  *Network* is for the local remote described above.
- The **Download the Android app** button and the links in the settings open GitHub pages in your browser only when
  you tap them.

Questions: open an issue on https://github.com/MadeinTaly/midnightbeam/issues

Last updated: 2026-10-03
