"""Workout metrics derived from the flywheel pulse stream, and the stats panel that shows them."""
import pygame

PULSES_PER_M = 96  # measured against the S4's own distance counter
# Concept2's pace-to-power relation, the de facto standard for ergometers.
WATTS_PER_MPS3 = 2.8
MAX_STROKE_GAP = 6  # longer than this between strokes counts as a rest, not a stroke

SURFACE = (26, 26, 25)
GRID = (56, 56, 53)
TEXT = (240, 239, 236)
TEXT_2 = (195, 194, 183)
SERIES = (57, 135, 229)


def watts(speed):
    return WATTS_PER_MPS3 * speed**3


def split(speed):
    """Time per 500 m as m:ss.s."""
    if speed < 0.3:
        return "-:--.-"
    s = 500 / speed
    return f"{int(s // 60)}:{s % 60:04.1f}"


def clock(seconds):
    seconds = max(0, int(seconds))
    return f"{seconds // 60}:{seconds % 60:02d}"


class Metrics:
    def __init__(self, target_seconds):
        self.target = target_seconds
        self.elapsed = 0.0
        self.base_pulses = None
        self.distance = 0.0
        self.speed = 0.0
        self.strokes = []  # (elapsed, spm, watts, m/s), one per completed stroke
        self.last_stroke = None  # (elapsed, distance) at the last stroke start
        self.seen_strokes = None

    def update(self, source, dt, active):
        if self.base_pulses is None:
            self.base_pulses = source.total_pulses
            self.seen_strokes = source.stroke_count
        self.distance = (source.total_pulses - self.base_pulses) / PULSES_PER_M
        self.speed = source.speed_rate / PULSES_PER_M
        if not active:
            self.last_stroke = None
            self.seen_strokes = source.stroke_count
            return
        self.elapsed += dt
        if source.stroke_count != self.seen_strokes:
            self.seen_strokes = source.stroke_count
            if self.last_stroke:
                t0, d0 = self.last_stroke
                duration = self.elapsed - t0
                if 0 < duration < MAX_STROKE_GAP:
                    speed = (self.distance - d0) / duration
                    self.strokes.append((self.elapsed, 60 / duration, watts(speed), speed))
            self.last_stroke = (self.elapsed, self.distance)

    @property
    def stroke_speed(self):
        """Speed averaged over the last stroke, like a monitor shows; instantaneous before the first."""
        if self.strokes and self.elapsed - self.strokes[-1][0] < MAX_STROKE_GAP:
            return self.strokes[-1][3]
        return self.speed

    @property
    def progress(self):
        return min(1.0, self.elapsed / self.target)

    @property
    def avg_speed(self):
        return self.distance / self.elapsed if self.elapsed > 1 else 0.0

    def avg_spm(self):
        return sum(s[1] for s in self.strokes) / len(self.strokes) if self.strokes else 0.0

    def avg_watts(self):
        return sum(s[2] for s in self.strokes) / len(self.strokes) if self.strokes else 0.0


class Panel:
    HEIGHT = 180

    def __init__(self, width):
        self.width = width
        self.label = pygame.font.Font(None, 20)
        self.value = pygame.font.Font(None, 34)
        self.title = pygame.font.Font(None, 22)

    def draw(self, screen, top, m, spm):
        r = pygame.Rect(0, top, self.width, self.HEIGHT)
        pygame.draw.rect(screen, SURFACE, r)
        tiles = [
            ("time", f"{clock(m.elapsed)} / {clock(m.target)}"),
            ("distance", f"{m.distance:,.0f} m"),
            ("split /500m", split(m.stroke_speed)),
            ("avg split", split(m.avg_speed)),
            ("watts", f"{watts(m.stroke_speed):.0f}"),
            ("stroke rate", f"{spm:.0f} spm"),
        ]
        for i, (label, value) in enumerate(tiles):
            x = 16 + (i % 2) * 132
            y = top + 14 + (i // 2) * 54
            screen.blit(self.label.render(label, True, TEXT_2), (x, y))
            screen.blit(self.value.render(value, True, TEXT), (x, y + 16))

        x0 = 300
        w = (self.width - x0 - 16 - 2 * 16) // 3
        speeds = [(t, v) for t, _, _, v in m.strokes]
        self.line_chart(screen, pygame.Rect(x0, top + 12, w, self.HEIGHT - 24), "Speed per stroke (m/s)", speeds, m.target)
        rates = [(t, s) for t, s, _, _ in m.strokes]
        self.dot_chart(screen, pygame.Rect(x0 + w + 16, top + 12, w, self.HEIGHT - 24), "Stroke rate (spm)", rates, m.target)
        powers = [p for _, _, p, _ in m.strokes[-30:]]
        self.bar_chart(screen, pygame.Rect(x0 + 2 * (w + 16), top + 12, w, self.HEIGHT - 24), "Watts, last 30 strokes", powers, 30)

    def frame(self, screen, rect, title, ymax):
        """Draws the title and a recessive y grid; returns the plot rect and a value-to-y function."""
        screen.blit(self.title.render(title, True, TEXT), (rect.x, rect.y))
        plot = pygame.Rect(rect.x + 30, rect.y + 24, rect.w - 30, rect.h - 30)
        step = nice_step(ymax)
        v = 0
        while v <= ymax + 1e-9:
            y = plot.bottom - int(plot.h * v / ymax)
            pygame.draw.line(screen, GRID, (plot.x, y), (plot.right, y), 1)
            img = self.label.render(f"{v:g}", True, TEXT_2)
            screen.blit(img, img.get_rect(midright=(plot.x - 6, y)))
            v += step
        return plot, lambda val: plot.bottom - int(plot.h * min(val, ymax) / ymax)

    def line_chart(self, screen, rect, title, points, xmax):
        ymax = nice_ceiling(max([v for _, v in points] + [3]))
        plot, to_y = self.frame(screen, rect, title, ymax)
        pts = [(plot.x + int(plot.w * min(t, xmax) / xmax), to_y(v)) for t, v in points]
        if len(pts) > 1:
            pygame.draw.lines(screen, SERIES, False, pts, 2)
        if pts:
            pygame.draw.circle(screen, SERIES, pts[-1], 4)

    def dot_chart(self, screen, rect, title, points, xmax):
        ymax = nice_ceiling(max([v for _, v in points] + [30]))
        plot, to_y = self.frame(screen, rect, title, ymax)
        for t, v in points:
            pygame.draw.circle(screen, SERIES, (plot.x + int(plot.w * min(t, xmax) / xmax), to_y(v)), 3)

    def bar_chart(self, screen, rect, title, values, slots):
        ymax = nice_ceiling(max(values + [100]))
        plot, to_y = self.frame(screen, rect, title, ymax)
        slot = plot.w / slots
        for i, v in enumerate(values):
            x = plot.x + int(i * slot) + 1
            top = to_y(v)
            if plot.bottom - top > 0:
                pygame.draw.rect(screen, SERIES, (x, top, max(1, int(slot) - 2), plot.bottom - top), border_top_left_radius=2, border_top_right_radius=2)


def nice_step(ymax):
    for step in (0.5, 1, 2, 5, 10, 20, 50, 100, 200, 500):
        if ymax / step <= 4:
            return step
    return ymax / 4


def nice_ceiling(v):
    step = nice_step(v * 1.1)
    return step * int(v * 1.1 / step + 0.999)
