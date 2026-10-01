# SweepVR

Native Cardboard VR environment for Android. A proof-of-concept app exploring
the practicality of using head sweep actions instead of reticle dwell actions.
It implements a basic web browser, keyboard, file manager and video player, with
support for SMB browsing and streaming.

Sweep actions are determined by where you enter and leave a control, rather than
by dwelling on it. Entry points on sweep controls are marked with indentations.
In most cases, the sweep exits perpendicular to the entry point, although this
varies slightly between control types.

More details on using sweep are available in the web browser,
or [here](https://scratchingmyhead.github.io/SweepVR/).

## Download

Current release APK
[SweepVR-0.6.32.apk](https://github.com/ScratchingMyHead/SweepVR/releases/download/v0.6.32/SweepVR-0.6.32.apk).
All releases: [releases page](https://github.com/ScratchingMyHead/SweepVR/releases).

## Sensors

Requires a gyroscope (same as Cardboard itself).

## Build

```sh
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Requires Android SDK 34, Kotlin 1.9.x.

## License

GPL-3.0-only — see [LICENSE](LICENSE). Anyone distributing a product
built from this code must provide its full source under the same terms.
