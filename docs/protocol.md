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
