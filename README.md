# Fan IR Scanner

Android app that finds the infrared codes of a ceiling fan whose remote is lost,
using the phone's built-in IR blaster.

- **Scan** sends every standard NEC code (256 addresses x 256 commands, about 2 hours at most).
- **Pinpoint** steps one code at a time around the spot where the fan reacted.
- **Remote** keeps the codes you saved as buttons.

## Build

- **GitHub:** push this folder to a repository. The included workflow builds the APK;
  download `fan-ir-scanner-apk` from the run's Artifacts.
- **Android Studio:** open the folder and press Run with the phone connected.

## Limits

Only standard NEC infrared codes are tried. A fan that uses radio (RF), extended NEC
addresses or another infrared format will not react.
