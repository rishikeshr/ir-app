# Fan IR Scanner

Android app that finds the infrared codes of a ceiling fan whose remote is lost,
using the phone's built-in IR blaster.

- **Scan** sends every standard NEC code (256 addresses x 256 commands, about 2 hours at most).
- **Pinpoint** steps one code at a time around the spot where the fan reacted.
- **Remote** keeps the codes you saved as buttons.

## Install

Download the latest build on the phone and open it:
https://github.com/rishikeshr/ir-app/releases/latest/download/fan-ir-scanner.apk

Every push to `main` rebuilds the APK and replaces that release. Builds are signed with the
debug key in `app/debug.keystore`, so a new build installs over the old one and keeps saved buttons.

## Limits

Only standard NEC infrared codes are tried. A fan that uses radio (RF), extended NEC
addresses or another infrared format will not react.
