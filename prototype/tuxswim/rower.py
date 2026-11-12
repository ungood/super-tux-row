"""Stroke input sources for the game: the WaterRower, a recorded session, or the keyboard.

Every source exposes the same state, read once per frame:
  driving       -- True between stroke start (SS) and stroke end (SE)
  pulse_rate    -- flywheel speed in pulses/second, lightly smoothed (for thrust)
  speed_rate    -- flywheel speed in pulses/second, smoothed over ~1 s (for boat speed)
  total_pulses  -- pulses since the source started; ~96 per metre
  stroke_count  -- stroke starts since the source started
  strokes       -- monotonic timestamps of recent stroke starts
"""
import json
import math
import threading
import time
from collections import deque

PORT = "/dev/serial/by-id/usb-Microchip_Technology_Inc._WR_USB_sensor-if00"
# Each Pxx line counts pulses in a ~25 ms window.
WINDOW_HZ = 40
FAST_SMOOTHING = 0.2
SLOW_SMOOTHING = 0.03


class Source:
    name = "none"

    def __init__(self):
        self.driving = False
        self.pulse_rate = 0.0
        self.speed_rate = 0.0
        self.total_pulses = 0.0
        self.stroke_count = 0
        self.strokes = deque(maxlen=8)
        self.connected = False

    def stroke_rate(self):
        """Strokes per minute from the last few stroke starts."""
        if len(self.strokes) < 2 or time.monotonic() - self.strokes[-1] > 6:
            return 0.0
        span = self.strokes[-1] - self.strokes[0]
        return 60 * (len(self.strokes) - 1) / span if span else 0.0

    def handle(self, line):
        if line == "SS":
            self.driving = True
            self.stroke_count += 1
            self.strokes.append(time.monotonic())
        elif line == "SE":
            self.driving = False
        elif line == "PING":
            self.driving = False
            self.pulse_rate = self.speed_rate = 0.0
        elif line.startswith("P") and len(line) == 3:
            try:
                n = int(line[1:], 16)
            except ValueError:
                return
            self.total_pulses += n
            self.pulse_rate += FAST_SMOOTHING * (n * WINDOW_HZ - self.pulse_rate)
            self.speed_rate += SLOW_SMOOTHING * (n * WINDOW_HZ - self.speed_rate)
        elif line == "_WR_":
            self.connected = True

    def update(self, now):
        pass

    def close(self):
        pass


class RowerSource(Source):
    """Reads the S4 event stream. Only listens; never polls memory, which keeps the link stable."""

    name = "rower"

    def __init__(self, port=PORT):
        super().__init__()
        self.port = port
        self._stop = threading.Event()
        threading.Thread(target=self._run, daemon=True).start()

    def _run(self):
        import serial

        while not self._stop.is_set():
            try:
                with serial.Serial(self.port, 19200, timeout=0.5) as ser:
                    self._session(ser)
            except (OSError, serial.SerialException):
                self.connected = False
                self._stop.wait(2)

    def _session(self, ser):
        last_line = time.monotonic()
        last_hello = 0.0
        while not self._stop.is_set():
            now = time.monotonic()
            # A sleeping monitor ignores the handshake, so repeat it until it answers.
            if not self.connected and now - last_hello > 2:
                ser.write(b"USB\r\n")
                last_hello = now
            # The S4 can go silent mid-session; reopening the port recovers it.
            if self.connected and now - last_line > 5:
                self.connected = False
                return
            line = ser.readline().decode("ascii", "replace").strip()
            if line:
                last_line = now
                self.handle(line)
        ser.write(b"EXIT\r\n")

    def close(self):
        self._stop.set()


class ReplaySource(Source):
    """Replays the SS/SE/P events of a capture made with tools/log.py, looping forever."""

    name = "replay"

    def __init__(self, path):
        super().__init__()
        self.events = []
        for raw in open(path):
            e = json.loads(raw)
            if e["k"] in ("SS", "SE", "PING"):
                self.events.append((e["t"], e["k"]))
            elif e["k"] == "P":
                self.events.append((e["t"], f"P{e['n']:02X}"))
        self.length = self.events[-1][0]
        self.index = 0
        self.start = time.monotonic()
        self.connected = True

    def update(self, now):
        while True:
            t, line = self.events[self.index]
            if self.start + t > now:
                return
            self.handle(line)
            self.index += 1
            if self.index == len(self.events):
                self.index = 0
                self.start += self.length


class KeyboardSource(Source):
    """Hold space to pull. Simulates a flywheel: spins up while held, coasts down when released."""

    name = "keyboard"
    PULL_RATE = 420
    SPIN_UP = 2.0
    COAST = 0.15

    def __init__(self):
        super().__init__()
        self.connected = True
        self.last = time.monotonic()

    def set_pulling(self, pulling):
        if pulling and not self.driving:
            self.stroke_count += 1
            self.strokes.append(time.monotonic())
        self.driving = pulling

    def update(self, now):
        dt = now - self.last
        self.last = now
        if self.driving:
            self.pulse_rate += (self.PULL_RATE - self.pulse_rate) * (1 - math.exp(-self.SPIN_UP * dt))
        else:
            self.pulse_rate *= math.exp(-self.COAST * dt)
        self.speed_rate = self.pulse_rate
        self.total_pulses += self.pulse_rate * dt
