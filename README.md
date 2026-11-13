# SuperTuxRower

Rowing games driven by the WaterRower S4 monitor's USB sensor, starring Tux.

The goal is an app for the Android tablet that ships with the Ergatta rower, with several
workout modes (2D Tux, a 2.5D Tux game, YouTube) and a shared stats overlay. For now there is
a desktop prototype.

## Development environment

Tools come from the Nix flake. With direnv, add an `.envrc` containing `use flake`; otherwise:

```sh
nix develop
```

Your user needs access to the rower's serial port: membership in the `dialout` group, or
`sudo chmod a+rw /dev/ttyACM0` until the next replug.

## Layout

| Path | What |
|---|---|
| `docs/protocol.md` | The S4 USB protocol as observed on this unit |
| `docs/tablet.md` | The Ergatta tablet: hardware, software, adb access |
| `docs/workout-model.md` | Intensity bands and workout structure |
| `docs/ergatta-notes.md` | Ideas from the Ergatta app: zones, workout structure, display |
| `android/app/` | The SuperTuxRower app (prototype): profiles, workouts, stats overlay |
| `android/sensortest/` | Minimal Android app that reads the sensor over the USB host API |
| `prototype/tuxswim/` | Desktop prototype of the Tux game (pygame) |
| `tools/` | Protocol probes: `probe.py`, `scan.py` (readable memory), `log.py` (capture a session) |
| `captures/` | Recorded sessions, replayable with `--replay` |

## Running the prototype

```sh
python3 prototype/tuxswim/game.py                                # live rower
python3 prototype/tuxswim/game.py --keyboard                     # hold space to pull
python3 prototype/tuxswim/game.py --replay captures/row1.jsonl   # recorded session
```

Keys: Tab toggles the stats panel, Left/Right picks the workout length before starting,
R restarts, Esc quits.

## Android

Build and install the app on the tablet (see `docs/tablet.md` for adb access):

```sh
gradle -p android :app:assembleDebug
adb connect tablet.example:5555
adb install -r android/app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n name.onetrue.supertuxrower/.MainActivity
```

The app uses the rower if it's plugged in, otherwise a simulated rower that rows towards each
piece's target; "Simulate" on the workout picker switches. `:sensortest` is a bare-bones sensor
readout built the same way.

The first launch asks for permission to use the sensor; leave "Always open" unticked unless the
app should take over from Ergatta's when the sensor is plugged in.
