# SweepVR

Native Cardboard VR web browser and video player for Android using sweep controls

- In-headset SMB (SMB2/3) direct streaming.
- Phone + Cardboard stereo rendering
- Browse servers, folders, and files without leaving VR
- Projections: Flat 2D/imax, SBS/TB, 180/220/270/360 domes, fisheye

This is a work in progress. The web browser is not fully functional.

Sweep controls are faster and more natural than reticle dwell-to-trigger type
controls. Sweep control activation and actions are determined by where on the
control you enter and leave and do not require dwelling on the control. Entry
locations on a control are marked with indentations, and usually the exit
direction is perpendicular to that, but this varies slightly with different
control types.

## Download

Current release APK
[SweepVR-0.6.32.apk](https://github.com/ScratchingMyHead/SweepVR/releases/download/v0.6.32/SweepVR-0.6.32.apk).
All releases: [releases page](https://github.com/ScratchingMyHead/SweepVR/releases).

## Sensors

Head tracking needs a gyroscope (same as Cardboard itself).

## Build

```sh
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Requires Android SDK 34, Kotlin 1.9.x.

## License

GPL-3.0-only — see [LICENSE](LICENSE). Anyone distributing a product
built from this code must provide its full source under the same terms.
