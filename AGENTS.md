# Agent guide

Notes for AI coding agents working on SuperTuxRow. Read [README.md](README.md) first for what
the project is; the docs in `docs/` hold the domain knowledge.

## Commands

All tools come from the Nix flake (`nix develop`, or direnv with `use flake`). Don't install
tools with mise or globally. Tasks live in the `justfile`:

| Task | What it does |
|---|---|
| `just check` | Unit tests, then a debug build. Run before declaring work done. |
| `just test` | Unit tests only (`gradle -p android :app:testDebugUnitTest`) |
| `just run` | Install on the tablet and start, on the real rower if plugged in |
| `just sim` | Install and start on the simulated rower |
| `just screenshot FILE` | Save the tablet's screen, to check UI changes by looking at them |
| `just logs` | Follow the app's logcat |
| `just set-home` / `just restore-home` | Make the app the tablet's home app, or give home back to Android's launcher |

The tablet's address is `TABLET=host:port` in `.env`, which isn't checked in (see
`example.env`). Never commit a real hostname or IP address. If `adb` can't reach the tablet, adb
over Wi-Fi was probably reset by a reboot; ask the user to re-enable it (see `docs/tablet.md`).
Driving the UI from adb (`adb shell input tap X Y`) works; the screen is 1920×1080.

## Architecture

`android/app/src/main/java/name/onetrue/supertuxrower/`:

| Package | Responsibility |
|---|---|
| `sensor` | `S4Link` (USB serial), `Rower` (turns S4 lines into metrics), `RowerSources` (USB with reconnect watchdog; simulated rower) |
| `model` | Bands, profiles (and their storage), workouts and the built-in library, rowing math |
| `workout` | `WorkoutRunner`: steps through pieces and rests, time in band, forecasting; `BandTracker` |
| `game` | `CaveGame`: the cave game's logic, no drawing |
| `bob` | Claude Rowed: `TokenEconomy`, `WakePlanner`, `Terminal`, `Challenge` (pure); `BobAgent` (the Claude agent loop via the Anthropic Java SDK, text-to-speech) and `BobTools` (notebook, challenges, escape fund, plus server-side code execution and web tools) |
| `ui` | Compose screens: games and modes (`GameScreens`), Tux Row 2D (`WorkoutScreen`, `GameScene` draws `CaveGame`), Claude Rowed (`ClaudeRowedScreen`) |

Keep `model`, `workout`, `game` and `Rower` free of Android framework calls so they stay
unit-testable on the JVM. `Rower.handle` and `Rower.snapshot` take an explicit `now`; tests
pass it rather than relying on `SystemClock`.

## Domain facts that matter

- **The S4 protocol** is in `docs/protocol.md`. The app only listens to the event stream (`Pxx`,
  `SS`, `SE`, `PING`); polling memory made the monitor go silent, so don't add it casually.
- **Pulse windows are ~23.9 ms, not 25.** `Rower` measures the window length from the stream.
  Time strokes by counting windows, not by when lines arrive: the tablet delivers them late and
  in bursts.
- **One USB read is not one line.** Buffer and split on `\n`.
- **Distance is 96 pulses per metre** (measured; Ergatta uses 91.43; see issue #5).
- **Power is 2.8·v³**; calories use Concept2's formula. Bands are fractions of a profile's 2k
  power (`docs/workout-model.md`).
- **The tablet's timers fire late** (25 ms Handler delays run ~34 ms). Anything simulated must
  catch up to real elapsed time, as `SimRowerSource` does.
- **Per-stroke power is noisy** (~12% stroke to stroke). Anything that moves on screen should
  use `RowerSnapshot.smoothSpeed` (a rolling one-stroke window), not single strokes.

## Rules

- **Never copy code, art or names from the Ergatta app.** Its decompiled source may be studied
  for behaviour and concepts only; write findings in our own words. Keep the APK and decompiled
  source out of the repo.
- Ask before committing or pushing; the user often commits themselves.
- Add or update tests with logic changes. `captures/row1.jsonl` is a real session; the test
  fixtures can replay it.
- Claude Rowed calls the real API and costs real money: every wake is a request. The key lives only
  in `.env` and the app's private storage (`just set-api-key`); never log or commit it.
- Check UI changes on the tablet with `just sim` and `just screenshot`, and leave the tablet on
  the real rower afterwards (`just run`). Simulated Claude Rowed runs keep their own lineage,
  fund and notebook; never test against the user's real ones.
- Match the surrounding code: short doc comments on why, not what.
- Keep `docs/` current when you learn something about the hardware or change the model.
