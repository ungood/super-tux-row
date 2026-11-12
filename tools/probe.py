#!/usr/bin/env python3
"""Probe a WaterRower S4 monitor over its USB CDC-ACM serial port.

Handshake: send "USB", expect "_WR_". Then poll memory with IRS/IRD/IRT and
log the unsolicited events (P = pulses, SS/SE = stroke start/end, PING).
"""
import sys
import threading
import time

import serial

PORT = sys.argv[1] if len(sys.argv) > 1 else "/dev/ttyACM0"
DURATION = float(sys.argv[2]) if len(sys.argv) > 2 else 20

# address: (name, size, base)
MEMORY_MAP = {
    "055": ("total_distance_m", "D", 16),
    "140": ("total_strokes", "D", 16),
    "088": ("watts", "D", 16),
    "08A": ("total_kcal", "T", 16),
    "148": ("total_speed_cmps", "D", 16),
    "14A": ("avg_speed_cmps", "D", 16),
    "1A0": ("heart_rate", "D", 16),
    "1A9": ("stroke_rate", "S", 16),
    "1E1": ("display_sec", "S", 10),
    "1E2": ("display_min", "S", 10),
    "0A9": ("tank_volume", "S", 16),
}


def send(ser, cmd):
    ser.write((cmd + "\r\n").encode("ascii"))


def reader(ser, stop):
    while not stop.is_set():
        line = ser.readline().decode("ascii", "replace").strip()
        if not line:
            continue
        t = f"{time.monotonic():10.3f}"
        if line.startswith("ID") and line[3:6] in MEMORY_MAP:
            name, _, base = MEMORY_MAP[line[3:6]]
            print(t, f"{line:16} {name} = {int(line[6:], base)}")
        elif line.startswith("P") and not line.startswith("PING"):
            print(t, f"{line:16} pulses = {int(line[1:], 16)}")
        else:
            print(t, line)


def main():
    ser = serial.Serial(PORT, 19200, timeout=0.5)
    stop = threading.Event()
    th = threading.Thread(target=reader, args=(ser, stop), daemon=True)
    th.start()
    send(ser, "USB")
    time.sleep(0.5)
    send(ser, "IV?")
    time.sleep(0.3)
    end = time.monotonic() + DURATION
    try:
        while time.monotonic() < end:
            for addr, (_, size, _) in MEMORY_MAP.items():
                send(ser, f"IR{size}{addr}")
                time.sleep(0.025)
            time.sleep(0.5)
    except KeyboardInterrupt:
        pass
    send(ser, "EXIT")
    time.sleep(0.2)
    stop.set()
    th.join()
    ser.close()


if __name__ == "__main__":
    main()
