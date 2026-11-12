#!/usr/bin/env python3
"""Tux Swim: a Flappy Bird-style rowing workout where Tux swims under the Antarctic ice.

Pull on the rower (or hold space) and Tux swims upward; between strokes he sinks.
Harder pulls give more thrust, and the boat speed sets how fast he swims forward.
The course gets harder as the workout goes on.

Keys: Tab stats panel, Left/Right workout length (before starting), R restart, Esc quit.
"""
import argparse
import math
import os
import random
import sys
import time

import pygame

from rower import KeyboardSource, ReplaySource, RowerSource, PORT
from stats import Metrics, Panel, clock, split, watts

W, H = 960, 540
CEILING = 70  # underside of the ice sheet
FLOOR = H - 40  # top of the seabed
TUX_X = 220
TUX_R = 18

# Physics, in pixels and seconds, with y pointing down.
SINK = 260  # downward pull between strokes
THRUST_PER_PULSE = 2.4  # upward acceleration per flywheel pulse/s while driving
DRAG = 2.5  # linear water drag, 1/s
MAX_SPEED = 420

PX_PER_M = 45  # ~3.3 m/s, an easy paddle, scrolls at ~150 px/s
OBSTACLE_W = 90
INVULNERABLE = 1.5  # seconds after a bonk
REST_AFTER = 5  # seconds without a stroke before the workout clock pauses

WORKOUTS = [5, 10, 15, 20, 30, 45, 60]  # minutes

# Course difficulty at the start and end of the workout; interpolated by progress.
EASY = {"gap": 270, "wander": 40, "spacing": 15}
HARD = {"gap": 150, "wander": 150, "spacing": 8.5}

ICE = (205, 238, 250)
ICE_SHADE = (150, 205, 230)
ICE_EDGE = (235, 250, 255)
ROCK = (38, 44, 58)
ROCK_EDGE = (62, 72, 92)
KELP = (40, 110, 80)
BLACK = (22, 24, 30)
WHITE = (245, 245, 240)
ORANGE = (250, 170, 30)
BEAK = (38, -4)  # beak tip relative to the centre of the Tux sprite


def step(y, vy, driving, pulse_rate, dt):
    """Advance Tux's depth by dt. Returns the new (y, vy)."""
    accel = SINK - (THRUST_PER_PULSE * pulse_rate if driving else 0)
    vy += accel * dt
    vy *= math.exp(-DRAG * dt)
    vy = max(-MAX_SPEED, min(MAX_SPEED, vy))
    return y + vy * dt, vy


def difficulty(progress):
    t = progress * progress * (3 - 2 * progress)  # smoothstep: gentle start and finish to the ramp
    return {k: EASY[k] + (HARD[k] - EASY[k]) * t for k in EASY}


def lerp(a, b, t):
    return tuple(int(a[i] + (b[i] - a[i]) * t) for i in range(3))


def make_background():
    bg = pygame.Surface((W, H))
    top, bottom = (40, 110, 160), (4, 14, 40)
    for y in range(H):
        pygame.draw.line(bg, lerp(top, bottom, y / H), (0, y), (W, y))
    # Faint light shafts coming through the ice.
    shafts = pygame.Surface((W, H), pygame.SRCALPHA)
    rng = random.Random(3)
    for _ in range(7):
        x = rng.randint(0, W)
        w = rng.randint(30, 90)
        pygame.draw.polygon(shafts, (180, 230, 255, 18), [(x, CEILING), (x + w, CEILING), (x + w + 160, H), (x + 160, H)])
    bg.blit(shafts, (0, 0))
    return bg


def make_ice_sheet(width):
    """The ice ceiling as a tileable strip with a jagged underside."""
    s = pygame.Surface((width, CEILING + 24), pygame.SRCALPHA)
    rng = random.Random(7)
    pts = [(0, 0), (width, 0)]
    x = width
    while x > 0:
        pts.append((x, CEILING + rng.randint(-6, 14)))
        x -= rng.randint(18, 40)
    pts.append((0, CEILING + 4))
    pygame.draw.polygon(s, ICE, pts)
    pygame.draw.lines(s, ICE_EDGE, False, pts[2:], 3)
    pygame.draw.rect(s, ICE_SHADE, (0, 0, width, 22))
    return s


def make_seabed(width):
    s = pygame.Surface((width, H - FLOOR + 20), pygame.SRCALPHA)
    rng = random.Random(11)
    pts = [(0, s.get_height()), (width, s.get_height())]
    x = width
    while x > 0:
        pts.append((x, rng.randint(6, 22)))
        x -= rng.randint(20, 50)
    pts.append((0, 14))
    pygame.draw.polygon(s, (28, 30, 40), pts)
    return s


def make_icicle(length, rng):
    """An ice keel hanging from the sheet: a cluster of downward spikes."""
    w = OBSTACLE_W
    s = pygame.Surface((w, length), pygame.SRCALPHA)
    main = [(8, 0), (w - 8, 0), (w // 2 + rng.randint(-6, 6), length)]
    pygame.draw.polygon(s, ICE, main)
    for side in (0, 1):
        short = rng.randint(length // 3, max(length // 3 + 1, int(length * 0.7)))
        cx = rng.randint(14, 28) if side == 0 else w - rng.randint(14, 28)
        pygame.draw.polygon(s, ICE_SHADE, [(cx - 14, 0), (cx + 14, 0), (cx, short)])
    pygame.draw.line(s, ICE_EDGE, (main[0][0] + 6, 0), main[2], 3)
    return s


def make_rock(height, rng):
    """A rock spire rising from the seabed, with some kelp."""
    w = OBSTACLE_W
    s = pygame.Surface((w, height), pygame.SRCALPHA)
    pts = [(0, height), (w, height)]
    for i, fy in enumerate((0.75, 0.45, 0.15, 0.0, 0.2, 0.5, 0.8)):
        side = i / 6
        x = int(w * (0.08 + 0.84 * (1 - side)))
        x = int(w / 2 + (x - w / 2) * (0.55 + 0.45 * fy))
        pts.append((x + rng.randint(-5, 5), int(height * fy)))
    pygame.draw.polygon(s, ROCK, pts)
    pygame.draw.lines(s, ROCK_EDGE, False, pts[2:], 3)
    for _ in range(2):
        kx = rng.randint(10, w - 10)
        top = height - rng.randint(30, min(90, height))
        pygame.draw.lines(s, KELP, False, [(kx + (6 if i % 2 else -6), y) for i, y in enumerate(range(height, top, -12))], 4)
    return s


def make_tux():
    """Tux swimming to the right, drawn on a small surface so it can be rotated."""
    s = pygame.Surface((84, 56), pygame.SRCALPHA)
    pygame.draw.polygon(s, ORANGE, [(4, 20), (16, 26), (6, 34)])  # trailing feet
    pygame.draw.polygon(s, ORANGE, [(2, 28), (16, 30), (6, 40)])
    pygame.draw.ellipse(s, BLACK, (10, 10, 60, 36))  # body
    pygame.draw.ellipse(s, WHITE, (22, 24, 44, 20))  # belly
    pygame.draw.ellipse(s, BLACK, (52, 8, 28, 26))  # head
    pygame.draw.ellipse(s, WHITE, (64, 13, 9, 11))  # eye
    pygame.draw.circle(s, BLACK, (70, 19), 3)
    pygame.draw.polygon(s, ORANGE, [(76, 20), (84, 24), (76, 28)])  # beak
    pygame.draw.polygon(s, (10, 12, 16), [(34, 22), (50, 24), (30, 40)])  # flipper
    return s


class Obstacle:
    def __init__(self, metre, gap_y, gap, rng):
        self.metre = metre  # course position of the obstacle's left edge
        self.gap_y = gap_y
        self.gap = gap
        self.passed = False
        self.x = 0.0
        top_len = int(gap_y - gap / 2 - CEILING + 20)
        bottom_len = int(FLOOR - (gap_y + gap / 2) + 20)
        self.top = make_icicle(max(top_len, 20), rng)
        self.bottom = make_rock(max(bottom_len, 20), rng)
        self.top_mask = pygame.mask.from_surface(self.top)
        self.bottom_mask = pygame.mask.from_surface(self.bottom)

    def top_pos(self):
        return int(self.x), CEILING - 20

    def bottom_pos(self):
        return int(self.x), int(self.gap_y + self.gap / 2)

    def draw(self, screen):
        screen.blit(self.top, self.top_pos())
        screen.blit(self.bottom, self.bottom_pos())

    def hits(self, mask, pos):
        for m, (ox, oy) in ((self.top_mask, self.top_pos()), (self.bottom_mask, self.bottom_pos())):
            if m.overlap(mask, (pos[0] - ox, pos[1] - oy)):
                return True
        return False


class Bubble:
    def __init__(self, x, y, rng):
        self.x, self.y = x, y
        self.r = rng.uniform(2, 5)
        self.vx = rng.uniform(-20, 20)
        self.vy = rng.uniform(-90, -50)

    def update(self, dt, scroll):
        self.x += self.vx * dt - scroll
        self.y += self.vy * dt
        return self.y > CEILING and self.x > -10


class Game:
    def __init__(self, sources, minutes):
        self.sources = sources
        self.keyboard = next(s for s in sources if isinstance(s, KeyboardSource))
        # Speed and stroke data come from the rower when there is one, otherwise the keyboard.
        self.primary = sources[-1]
        self.minutes = minutes
        self.rng = random.Random()
        self.font = pygame.font.Font(None, 34)
        self.big = pygame.font.Font(None, 84)
        self.background = make_background()
        self.ice = make_ice_sheet(W)
        self.seabed = make_seabed(W)
        self.tux = make_tux()
        self.panel = Panel(W)
        self.show_panel = False
        self.was_driving = False
        self.reset()

    def reset(self):
        self.state = "ready"
        self.metrics = Metrics(self.minutes * 60)
        self.y = (CEILING + FLOOR) / 2
        self.vy = 0.0
        self.course = 0.0  # metres swum, smoothed for display
        self.scroll = 0.0
        self.obstacles = []
        self.bubbles = []
        self.clock = 0.0
        self.score = 0
        self.streak = 0
        self.best_streak = 0
        self.bonks = 0
        self.bonk_time = -10.0
        self.last_stroke = 0.0

    def change_workout(self, delta):
        if self.state != "ready":
            return
        i = WORKOUTS.index(self.minutes) if self.minutes in WORKOUTS else 1
        self.minutes = WORKOUTS[max(0, min(len(WORKOUTS) - 1, i + delta))]
        self.metrics = Metrics(self.minutes * 60)

    def input(self):
        if self.keyboard.driving:
            return True, self.keyboard.pulse_rate
        return self.primary.driving, self.primary.pulse_rate

    def spawn(self):
        d = difficulty(self.metrics.progress)
        last = self.obstacles[-1] if self.obstacles else None
        metre = last.metre + d["spacing"] if last else self.course + 20
        prev = last.gap_y if last else (CEILING + FLOOR) / 2
        lo, hi = CEILING + d["gap"] / 2 + 20, FLOOR - d["gap"] / 2 - 20
        gap_y = max(lo, min(hi, prev + self.rng.uniform(-d["wander"], d["wander"])))
        o = Obstacle(metre, gap_y, d["gap"], self.rng)
        o.x = self.screen_x(metre)
        self.obstacles.append(o)

    def screen_x(self, metre):
        return TUX_X - 30 + (metre - self.course) * PX_PER_M

    def update(self, dt):
        self.clock += dt
        now = time.monotonic()
        for s in self.sources:
            s.update(now)
        driving, rate = self.input()
        stroke = driving and not self.was_driving
        self.was_driving = driving
        if stroke:
            self.last_stroke = self.clock

        if self.state == "ready":
            self.y = (CEILING + FLOOR) / 2 + math.sin(self.clock * 2) * 10
            self.metrics.update(self.primary, 0, False)
            self.metrics.base_pulses = None
            if stroke:
                self.state = "play"
        elif self.state == "rest":
            self.y += ((CEILING + FLOOR) / 2 - self.y) * min(1, dt * 2)
            self.vy = 0
            self.metrics.update(self.primary, dt, False)
            if stroke:
                self.state = "play"
        elif self.state in ("play", "done"):
            active = self.state == "play"
            self.metrics.update(self.primary, dt, active)
            self.y, self.vy = step(self.y, self.vy, driving, rate, dt)
            moved = self.metrics.speed * dt
            self.course += moved
            self.scroll = moved * PX_PER_M
            for o in self.obstacles:
                o.x = self.screen_x(o.metre)
            self.obstacles = [o for o in self.obstacles if o.x > -OBSTACLE_W - 30]
            if active:
                while not self.obstacles or self.obstacles[-1].x < W + 200:
                    self.spawn()
                for o in self.obstacles:
                    if not o.passed and o.x + OBSTACLE_W < TUX_X - TUX_R:
                        o.passed = True
                        self.score += 1
                        self.streak += 1
                        self.best_streak = max(self.best_streak, self.streak)
                self.check_bonk()
                if self.metrics.elapsed >= self.metrics.target:
                    self.state = "done"
                elif self.clock - self.last_stroke > REST_AFTER:
                    self.state = "rest"
            else:
                self.y = max(CEILING + TUX_R, min(FLOOR - TUX_R, self.y))
                if self.vy and (self.y <= CEILING + TUX_R or self.y >= FLOOR - TUX_R):
                    self.vy = 0

        if driving and self.rng.random() < dt * 25:
            bx, by = self.beak()
            self.bubbles.append(Bubble(bx, by, self.rng))
        self.bubbles = [b for b in self.bubbles if b.update(dt, self.scroll if self.state in ("play", "done") else 0)]

    def check_bonk(self):
        invulnerable = self.clock - self.bonk_time < INVULNERABLE
        hit_wall = False
        if self.y - TUX_R < CEILING:
            self.y, self.vy, hit_wall = CEILING + TUX_R, abs(self.vy) * 0.4, True
        elif self.y + TUX_R > FLOOR:
            self.y, self.vy, hit_wall = FLOOR - TUX_R, -abs(self.vy) * 0.4, True
        if invulnerable:
            return
        if hit_wall or self.hits_obstacle():
            self.bonks += 1
            self.streak = 0
            self.bonk_time = self.clock

    def angle(self):
        if self.clock - self.bonk_time < 0.6:
            return (self.clock - self.bonk_time) / 0.6 * 360  # tumble after a bonk
        return -max(-50, min(50, self.vy / 6))

    def beak(self):
        a = math.radians(self.angle())
        bx, by = BEAK
        return TUX_X + bx * math.cos(a) + by * math.sin(a), self.y - bx * math.sin(a) + by * math.cos(a)

    def tux_sprite(self):
        sprite = pygame.transform.rotate(self.tux, self.angle())
        return sprite, sprite.get_rect(center=(TUX_X, int(self.y)))

    def hits_obstacle(self):
        sprite, rect = self.tux_sprite()
        mask = pygame.mask.from_surface(sprite)
        return any(o.hits(mask, rect.topleft) for o in self.obstacles)

    def spm(self):
        return max(s.stroke_rate() for s in self.sources)

    def draw(self, screen):
        screen.blit(self.background, (0, 0))
        for o in self.obstacles:
            o.draw(screen)
        sx = -int(self.course * PX_PER_M) % W
        for dx in (-W, 0):
            screen.blit(self.seabed, (sx + dx, FLOOR - 10))
            screen.blit(self.ice, (sx + dx, 0))
        for b in self.bubbles:
            pygame.draw.circle(screen, (200, 235, 255), (int(b.x), int(b.y)), int(b.r), 1)
        blinking = self.clock - self.bonk_time < INVULNERABLE and int(self.clock * 10) % 2
        if not blinking:
            sprite, rect = self.tux_sprite()
            screen.blit(sprite, rect)
        self.draw_hud(screen)
        if self.show_panel:
            self.panel.draw(screen, H, self.metrics, self.spm())

    def text(self, screen, msg, font, center):
        for off, color in (((2, 2), (0, 0, 0)), ((0, 0), WHITE)):
            img = font.render(msg, True, color)
            screen.blit(img, img.get_rect(center=(center[0] + off[0], center[1] + off[1])))

    def draw_hud(self, screen):
        m = self.metrics
        ink = (30, 50, 70)
        status = ""
        if not self.primary.connected:
            status = "   rower: waiting… start rowing"
        left = f"{clock(m.target - m.elapsed)} left   {m.distance:,.0f} m   {split(m.stroke_speed)} /500m{status}"
        screen.blit(self.font.render(left, True, ink), (14, 10))
        right = f"streak {self.streak}   bonks {self.bonks}"
        img = self.font.render(right, True, ink)
        screen.blit(img, img.get_rect(topright=(W - 14, 10)))
        # Workout progress along the bottom of the ice shelf band.
        pygame.draw.rect(screen, (150, 205, 230), (0, 40, W, 5))
        pygame.draw.rect(screen, ORANGE, (0, 40, int(W * m.progress), 5))
        self.text(screen, str(self.score), self.big, (W // 2, 120))

        if self.state == "ready":
            self.text(screen, "Pull to swim!", self.big, (W // 2, H // 2 - 60))
            self.text(screen, f"<  {self.minutes} minute workout  >", self.font, (W // 2, H // 2))
            self.text(screen, "Tab: stats", self.font, (W // 2, H // 2 + 36))
        elif self.state == "rest":
            self.text(screen, "Resting", self.big, (W // 2, H // 2 - 60))
            self.text(screen, "pull to carry on", self.font, (W // 2, H // 2 - 10))
        elif self.state == "done":
            self.draw_summary(screen)

    def draw_summary(self, screen):
        m = self.metrics
        box = pygame.Surface((520, 300), pygame.SRCALPHA)
        box.fill((10, 20, 40, 200))
        screen.blit(box, box.get_rect(center=(W // 2, H // 2 + 20)))
        lines = [
            f"{m.target // 60} minutes done",
            f"{m.distance:,.0f} m   avg {split(m.avg_speed)} /500m",
            f"avg {m.avg_spm():.0f} spm   avg {m.avg_watts():.0f} W",
            f"{self.score} gaps   best streak {self.best_streak}   {self.bonks} bonks",
            "R to go again",
        ]
        self.text(screen, lines[0], self.big, (W // 2, H // 2 - 70))
        for i, line in enumerate(lines[1:]):
            self.text(screen, line, self.font, (W // 2, H // 2 + i * 40))


def set_mode(show_panel, fullscreen):
    flags = pygame.SCALED | (pygame.FULLSCREEN if fullscreen else 0)
    return pygame.display.set_mode((W, H + (Panel.HEIGHT if show_panel else 0)), flags)


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--replay", help="drive the game from a tools/log.py capture instead of the rower")
    p.add_argument("--keyboard", action="store_true", help="don't connect to the rower")
    p.add_argument("--port", default=PORT)
    p.add_argument("--minutes", type=int, default=10, help="workout length")
    p.add_argument("--stats", action="store_true", help="start with the stats panel open")
    p.add_argument("--fullscreen", action="store_true")
    p.add_argument("--screenshot", help="save a frame here after --frames frames, then exit")
    p.add_argument("--frames", type=int, default=300)
    args = p.parse_args()

    pygame.init()
    pygame.display.set_caption("Tux Swim")
    screen = set_mode(args.stats, args.fullscreen)

    sources = [KeyboardSource()]
    if args.replay:
        sources.append(ReplaySource(args.replay))
    elif not args.keyboard and os.path.exists(args.port):
        sources.append(RowerSource(args.port))

    game = Game(sources, args.minutes)
    game.show_panel = args.stats
    clock_ = pygame.time.Clock()
    frame = 0
    while True:
        dt = min(clock_.tick(60) / 1000, 1 / 20)
        for event in pygame.event.get():
            if event.type == pygame.QUIT or (event.type == pygame.KEYDOWN and event.key == pygame.K_ESCAPE):
                for s in sources:
                    s.close()
                pygame.quit()
                return
            if event.type in (pygame.KEYDOWN, pygame.KEYUP) and event.key == pygame.K_SPACE:
                game.keyboard.set_pulling(event.type == pygame.KEYDOWN)
            if event.type == pygame.KEYDOWN:
                if event.key == pygame.K_TAB:
                    game.show_panel = not game.show_panel
                    screen = set_mode(game.show_panel, args.fullscreen)
                elif event.key == pygame.K_LEFT:
                    game.change_workout(-1)
                elif event.key == pygame.K_RIGHT:
                    game.change_workout(1)
                elif event.key == pygame.K_r:
                    game.reset()
        game.update(dt)
        game.draw(screen)
        pygame.display.flip()
        frame += 1
        if args.screenshot and frame >= args.frames:
            pygame.image.save(screen, args.screenshot)
            for s in sources:
                s.close()
            pygame.quit()
            return


if __name__ == "__main__":
    sys.exit(main())
