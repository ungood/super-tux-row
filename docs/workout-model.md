# Workout model

How SuperTuxRow describes intensity and structures workouts. It blends rowing's traditional
training bands, Concept2-style interval structure and Peloton-style effort cues; see
`ergatta-notes.md` for what we deliberately do differently from Ergatta.

## Intensity bands

Each profile stores a **reference 2k time**. Bands are fractions of the power at that 2k pace
(watts = 2.8·v³), following the rowing training bands:

| Band ID | Rowing band | % of 2k power | Rough stroke rate | Penguin name |
|---|---|---|---|---|
| `rest` | — | below 50% | — | Rest / Waddle |
| `ut2` | UT2, aerobic base | 50–65% | 18–20 | Drift |
| `ut1` | UT1, aerobic endurance | 65–75% | 20–23 | Glide |
| `at` | AT, threshold | 75–85% | 24–28 | Chase |
| `tr` | TR, transport / VO2 max | 85–100% | 28–32 | Surge |
| `an` | AN, anaerobic | above 100% | 32+ | Launch |

- **Band IDs are internal and stable.** Workouts and profiles store IDs; each mode supplies its own
  display names. The penguin games use the names above, with rest shown as Rest or Waddle
  depending on the game (e.g. resting means hopping out onto the ice and waddling about). Other
  modes can use plain names.
- **Colours** run cool to hot, from ice blue (`rest`) to orange-red (`an`), and are the same in
  every mode so a band is recognisable at a glance.
- **Defaults are editable.** A new profile picks a starting 2k time (beginner 9:00, fit 8:00,
  strong 7:00); the band boundaries follow from it. Either the 2k time or any boundary can then be
  edited. A calibration row (#7) will later set the 2k time from a real test.

## Structure

A **workout** has a name, a mode (free row, Tux Swim, video, ...) and a list of **pieces**:

- **Goal**: a time, distance or calories, or open-ended.
- **Target**: a band, optionally with a stroke-rate range.
- **Rest after**: a fixed time, until ready (start the next piece when you like), or none. Rest
  can be a full stop or easy rowing.
- **Tag**: warm-up, work or cool-down, which the overlay shows differently.

**Repeats** group pieces into sets. Compact notation:

```
4 × (500m @ surge, 1:00 rest)
30:00 @ ut1 r20–22
warm-up 5:00 @ ut2; 8 × (1:00 @ an, 1:00 rest); cool-down 5:00 @ ut2
```

## Feedback

- **Time in band**: the share of each piece, and of the whole workout, spent in the target band
  (and stroke-rate range, if set). Shown live and at the end.
- No pass/fail threshold and no stretching pieces until a target is met. Scores or stars are up
  to individual game modes.

## Starter library

Classic public workouts:

- 2k test
- 30:00 @ ut1, rate 20
- 4 × 1000m @ tr, 5:00 rest
- 8 × 500m @ tr, 1:30 rest
- Pyramid: 250 / 500 / 750 / 1000 / 750 / 500 / 250 m @ tr, rest equal to work
- 20 × (1:00 @ an, 1:00 rest)
- Recovery: 20:00 @ ut2

## Sources

- Concept2 workout types and variable intervals:
  <https://www.concept2.nl/en/service/monitors/pm5/how-to-use/understanding-workout-types>,
  <https://www.concept2.com/products/setting-variable-interval-workout>
- Peloton Row pace targets: <https://www.pelobuddy.com/ten-pace-target-levels-row/>
- Training bands: <https://plus.britishrowing.org/wp-content/uploads/2023/01/GBRowingTeam_TrainingMatrix.pdf>,
  <https://getwatta.pages.dev/calculators/training-pace-calculator>,
  <https://www.threeriversrowing.org/post/science-and-secrets-pt-2-how-to-set-your-training-zones>
