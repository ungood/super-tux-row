# Notes from the Ergatta app

Concepts and numbers observed in the Ergatta app (com.ergatta.seaborn 2026.46.007), kept as
inspiration for our own design. None of its code, art or names are used in SuperTuxRower; the
rower protocol details are in `protocol.md`.

## Intensity zones

Six levels: rest, paddle, steady, race, sprint, max. A profile stores them as boat-speed
thresholds (equivalently, splits), so "in zone" is a simple comparison:

| Bound | Example m/s | Split /500 m |
|---|---|---|
| max top | 4.87 | 1:42.6 |
| sprint top | 4.42 | 1:53.2 |
| race top | 3.97 | 2:06.1 |
| steady top | 3.51 | 2:22.4 |
| paddle top | 2.96 | 2:49.0 |
| paddle bottom | 2.38 | 3:30.3 |

Each zone runs from the previous zone's top to its own; sprint is judged one-sided (at least
its bottom). Rest is anything below paddle.

**Deriving zones from fitness.** Ergatta models sustainable power as P(t) = C − L·ln(t) (the
Jensen model) and sets each zone's top at the power sustainable for a reference duration times
a multiplier: max 3 s × 0.985, sprint 10 s × 0.95, race 2 min × 0.85, steady 10 min × 0.75,
paddle 20 min × 0.55, paddle bottom 90 min × 0.40. Coefficients come from a 2k or 1k time
(Paul's Law: +5 s/500 m per doubling of distance converts between them).

**Defaults for a new user** come from a novice/intermediate/advanced choice mapped onto a
population of 1000 m times (mean 320 s, SD 93 s). A 1000 m all-out calibration row then sets
real zones, and later workouts recalibrate automatically.

For SuperTuxRower: start with editable split-based defaults (#4); a calibration row is #7.

## Workout structure

A workout is an ordered list of intervals:

- **type**: warm-up, working, rest, active recovery, cool-down
- **intensity**: one of the six zones
- **objective**: duration, distance, or either
- **target stroke rate** (optional)

What the rower must hold in an interval: split zone, stroke rate, both, or a heart-rate zone.

**Judging an interval.** The target is to hold the zone for a cumulative share of the
interval, looser for short intervals (about 60% for ≤ 1 min, 70% for 1–2 min, 80% for longer,
±10% for easy/hard settings). An interval can run over until the target is met. In-zone is
judged on a smoothed speed, and stroke rate within ±1 spm. The score is the share of targets
hit.

Workouts also carry a category (HIIT, endurance, conditioning, recovery), a difficulty, and a
stress score derived from time spent per zone.

## Modes

Free row (optionally over scenic video), instructor classes, interval targets, token-collecting
games, stroke-rate rhythm matching, races against ghosts or a pacer, and 3D games. They all run
on one shared workout engine: a per-mode state machine on top of common metrics.

## In-workout display

- **Top**: interval N of M with progress, time or distance left in the interval, total elapsed
  and remaining, score.
- **Metrics bar**: a large split coloured by where it falls in the zones, plus average split,
  distance, energy, heart rate, power, stroke rate.
- Interval modes draw the zone bands as a target gauge.

There is no compact/expanded toggle; that is something SuperTuxRower adds (#3).

## Architecture ideas

- A game engine pulls a flat, reused array of the latest metrics each frame instead of receiving
  a message per update; a single typed payload configures it at launch.
- One persistent GL surface behind the UI, shown and hidden rather than re-created, with a
  queue that buffers messages until it is ready.
- A recordable, replayable stream of the messages sent to the renderer, for debugging and
  regression tests (like our `captures/`).
- Shared HUD components across modes; numbers drawn directly with tabular figures to keep
  frequent updates cheap.
- Avoid: keeping a large engine resident in the main process on a 2 GB device.
