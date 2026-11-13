# WaterRower S4 USB protocol — as observed on this unit

Device: USB `04d8:000a` "Microchip Technology Inc. WR USB sensor", CDC-ACM at
`/dev/serial/by-id/usb-Microchip_Technology_Inc._WR_USB_sensor-if00`.
Firmware reply to `IV?`: `IV10105` (model 1 = S4, version 01.05).

ASCII lines, `\r\n` terminated. Baud setting is irrelevant (CDC); 19200 used.

## Session

| PC sends | S4 replies | Notes |
|---|---|---|
| `USB` | `_WR_` | Starts the session; nothing is sent before this. |
| `IV?` | `IV10105` | Model + firmware. |
| `EXIT` | — | Ends the session. |

Bulk writes work; per-character pacing is not needed on this firmware.

## Unsolicited events

| Line | Meaning |
|---|---|
| `Pxx` | Paddle pulses in the last ~25 ms window, hex. ~40 lines/s while moving. |
| `SS` / `SE` | Stroke start (drive) / stroke end (recovery). |
| `PING` | ~1/s while the flywheel is stopped. |

Measured: **~96 pulses per metre** (18702 pulses over 195 m in captures/row1.jsonl).

## Memory reads

`IRS<addr>` / `IRD<addr>` / `IRT<addr>` read 1/2/3 bytes; reply is
`IDS|IDD|IDT<addr><hex>`, or `ERROR`. Multi-byte reads are big-endian in the
reply string and start at `<addr>` (IRS on 057 returns the low byte of IRD057).

**Only 18 addresses are readable on firmware 01.05** (full scan 000–3FF in
captures/scan_idle.json); all others return `ERROR`. Several addresses used by
other projects (055 distance, 148 speed, 1A6 pace, 1E2/1E3 minutes/hours,
0A9 tank volume) are **not** readable here.

| Addr | Read | Meaning (from captures/row1.jsonl) |
|---|---|---|
| 057 | IRD | Distance, metres (hex) |
| 140 | IRD | Stroke count |
| 088 | IRD | Power, watts |
| 08A | IRT | Energy, kcal |
| 14A | IRD | Speed, cm/s (e.g. 0x15D = 3.49 m/s, matches distance rate) |
| 1A9 | IRS | Stroke rate, strokes/min (hex) |
| 1E0 | IRS | Display clock, tenths of a second (decimal digits) |
| 1E1 | IRS | Display clock, seconds 00–59 (decimal digits); minutes not readable |
| 1A0 | IRS | Heart rate? 0 with no strap |
| 1A1 | IRS | Unknown; 00 → 01 once rowing started |
| 00D 041 042 043 044 047 083 084 | IRS | Constant during rowing: 00 01 04 83 84 04 20 23. Probably settings. |

## Reliability

- While rowing, ~3% of reads come back as `ERROR` or as a corrupted reply (prefix
  dropped, e.g. `\x000083`). Validate the `ID?<addr>` prefix and retry.
- Firing commands without waiting for replies (25 ms apart, as other projects do)
  made the S4 go completely silent after ~1.5 s, until the port was reopened.
  One-request-at-a-time (wait for reply, 0.5 s timeout) ran 60 s with no stall.

## How the Ergatta app uses the S4

From reading the decompiled Ergatta app (com.ergatta.seaborn 2026.46.007). Behaviour and constants
only; none of its code is used here.

**Connection.** It claims only the CDC data interface (index 1) and uses its bulk endpoints. It
sends no USB control transfers (no line coding, no DTR/RTS), so those are unnecessary. It sends
`USB` on open and never checks for `_WR_`. Minimum 11 ms between USB calls; 5 s read timeout.

**Commands.** Only `USB`, `RESET` and `EXIT`. `RESET` (not otherwise documented here) is sent
before each workout and on close: `RESET`, 100 ms, `EXIT`. No memory reads at all.

**Events.** Only `P` pulses and `PING` feed the metrics. `SS`/`SE` are ignored.

**Strokes** are detected from pulses: a 13-sample (~325 ms) rolling sum of pulses; four strictly
increasing sums in a row mean the drive has started, which counts a stroke. Strokes less than
1.2 s apart are ignored (max 50 spm). Stroke rate is 60 / mean of the last 3 stroke periods
(each clamped to 5 s). After 10 s without a stroke, rate resets to 0.

**Metrics.**
- Distance: 350 mm per 32 pulses = **10.9375 mm per pulse (91.43 pulses/m)**. We measured ~96
  against the monitor's `057` counter; one of the two needs checking against the S4 display.
- Speed: pulses over a trailing **10 s** window (divisor at least 3 s) for display; an 810 ms
  window for "raw" speed. A gap over 10 s resets speed and stroke state.
- Pace: 500 / speed. Power: **2.8·v³**, from the 10 s speed (same formula as ours).
- Calories: not from power. kcal = weight × METs × hours, with METs from the user's intensity
  zone (rest 1.5, paddle 3.5, steady 6, race 8, sprint 10.5, max 11.5).

**Robustness.**
- Watchdog: during a workout, no `P` or `PING` for 5 s triggers a full close and reopen.
- 50 failed reads trigger a reconnect; 3 reconnects without a valid packet give up.
- Weakness to avoid: it assumes one USB read is one line, so merged or split lines are lost
  (including a stroke line merged with a pulse). Buffer and split on `\n` instead.
