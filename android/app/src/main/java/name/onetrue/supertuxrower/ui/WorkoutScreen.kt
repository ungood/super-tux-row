package name.onetrue.supertuxrower.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import name.onetrue.supertuxrower.model.Band
import name.onetrue.supertuxrower.model.Piece
import name.onetrue.supertuxrower.model.describe
import name.onetrue.supertuxrower.model.formatClock
import name.onetrue.supertuxrower.model.formatSplit
import name.onetrue.supertuxrower.model.penguinName
import name.onetrue.supertuxrower.sensor.Rower
import name.onetrue.supertuxrower.sensor.RowerSnapshot
import name.onetrue.supertuxrower.sensor.SimRowerSource
import name.onetrue.supertuxrower.workout.Phase
import name.onetrue.supertuxrower.workout.WorkoutRunner
import name.onetrue.supertuxrower.workout.WorkoutView
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.abs
import kotlin.math.PI
import kotlin.math.roundToInt

@Composable
fun WorkoutScreen(
    runner: WorkoutRunner,
    rower: Rower,
    sim: SimRowerSource?,
    compact: Boolean,
    onCompactChange: (Boolean) -> Unit,
    onDone: () -> Unit,
) {
    var view by remember { mutableStateOf(runner.view()) }
    var frame by remember { mutableLongStateOf(0L) }
    val game = remember { CaveGame() }

    LaunchedEffect(runner) {
        var last = 0L
        var sinceView = 0.0
        while (runner.phase != Phase.DONE) {
            withFrameNanos { now ->
                val dt = if (last == 0L) 0.0 else (now - last) / 1e9
                last = now
                val s = rower.snapshot()
                runner.update(dt, s)
                sim?.let { steer(it, runner) }
                game.update(dt, runner, s)
                frame++
                // The overlay only needs a few updates a second; the scene redraws every frame.
                sinceView += dt
                if (sinceView >= 0.1) {
                    sinceView = 0.0
                    view = runner.view()
                }
            }
        }
        onDone()
    }

    val target = if (view.phase == Phase.REST) null else view.piece?.band
    Column(Modifier.fillMaxSize().background(Deep)) {
        if (compact) CompactOverlay(view, Modifier.clickable { onCompactChange(false) })
        Box(Modifier.weight(1f).fillMaxWidth()) {
            GameScene(game, frame, target, Modifier.fillMaxSize())
            Row(
                Modifier.align(Alignment.TopEnd).padding(top = 72.dp, end = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedButton({ onCompactChange(!compact) }) { Text(if (compact) "Full stats" else "Compact") }
                OutlinedButton(runner::skip) { Text("Skip") }
                OutlinedButton(runner::finish) { Text("End") }
            }
        }
        if (!compact) FullOverlay(view, Modifier.clickable { onCompactChange(true) })
    }
}

/** Points the simulated rower at whatever the workout is asking for. */
private fun steer(sim: SimRowerSource, runner: WorkoutRunner) {
    val profile = runner.profile
    val piece = runner.piece
    when (runner.phase) {
        Phase.READY -> {
            sim.targetWatts = profile.twoKWatts * 0.55
            sim.targetSpm = 20.0
        }
        Phase.WORK -> {
            val band = piece?.band
            sim.targetWatts = profile.twoKWatts * (band?.middle ?: 0.95)
            sim.targetSpm = piece?.rate?.let { (it.first + it.last) / 2.0 } ?: (band?.typicalRate ?: 30).toDouble()
        }
        Phase.REST -> {
            sim.targetWatts = profile.twoKWatts * 0.3
            sim.targetSpm = 16.0
        }
        Phase.DONE -> {}
    }
}

/**
 * Tux swims through an ice cave, laid out in metres. Your boat speed moves him forward; your power
 * sets how fast he climbs or sinks: above [NEUTRAL] (a fraction of 2k power) he rises, below it he
 * sinks, faster the further you are from it.
 *
 * The cave is built from the workout: each piece's stretch slopes at the rate its target power
 * produces, so rowing the workout keeps him inside, and the camera scrolls up and down to follow.
 * While you're in the target band he's also pulled gently towards the middle of the cave, so
 * wobbles within the band don't pile up; leave the band and he drifts into a wall (a bonk).
 *
 * Heights are in world units; the screen shows [VIEW_HEIGHT] of them.
 */
private class CaveGame {
    class Slice(val metre: Double, val centre: Double, val half: Double)
    class Fish(val metre: Double, val height: Double, val gold: Boolean) {
        var eaten = false
        var passed = false
    }
    class Bubble(val metre: Double, var height: Double, val born: Double, val size: Float)

    var y = 0.0
        private set
    var vy = 0.0
        private set
    /** How far Tux has swum along the course, in metres. */
    var metres = 0.0
        private set
    val slices = ArrayDeque<Slice>()
    val fish = ArrayDeque<Fish>()
    var score = 0
        private set
    var bonks = 0
        private set
    /** Fish caught in a row; every 5 adds one to the multiplier. Reset by a miss or a bonk. */
    var combo = 0
        private set
    val multiplier: Int get() = 1 + combo / 5
    val bubbles = ArrayDeque<Bubble>()
    /** The camera's height, eased to follow the cave at Tux. */
    var camera = 0.0
        private set
    /** Your power on the band scale (0–6), eased so the gauge glides. */
    var gauge = 0.0
        private set

    var clock = 0.0
        private set
    private var nextBubble = 0.0
    private var nextSlice = 0.0
    private var nextFish = FIRST_FISH
    private var base = 0.0
    private var undulation = 0.0
    private var fishCount = 0
    private var lastBonkAt = -10.0
    private var lastStrokeAt = -10.0
    private var seenStrokes = -1
    private var averageFraction = 0.0
    private var piece = -1
    private var pieceStartedAt = 0.0

    /** Cosmetic bob after each stroke, in world units; it doesn't move Tux's real height. */
    val bob: Double
        get() {
            val t = clock - lastStrokeAt
            return if (t in 0.0..BOB_SECONDS) BOB * sin(PI * t / BOB_SECONDS) else 0.0
        }

    fun update(dt: Double, runner: WorkoutRunner, s: RowerSnapshot) {
        clock += dt
        metres += s.liveSpeed * dt
        val profile = runner.profile
        spawn(runner, s.speed)

        if (s.strokes > seenStrokes && seenStrokes >= 0 && s.moving) lastStrokeAt = clock
        seenStrokes = s.strokes

        val watts = s.smoothWatts
        val fraction = profile.fraction(watts)
        gauge += (Band.position(fraction) - gauge) * min(1.0, dt * 4)
        if (s.moving) averageFraction += (fraction - averageFraction) * min(1.0, dt / 15)

        if (runner.index != piece) {
            piece = runner.index
            pieceStartedAt = clock
        }
        // A new piece's first seconds are for bringing your effort to the new target.
        val grace = clock - pieceStartedAt < GRACE
        val target = runner.target
        val working = runner.phase == Phase.WORK
        val here = sliceAt(metres)

        if (working) {
            // Pieces without a target are measured against your own recent average.
            val neutral = if (target != null) NEUTRAL else averageFraction
            val climb = CLIMB * (fraction - neutral)
            vy += (climb - vy) * min(1.0, dt * 5)
            val inBand = target == null || profile.band(watts) == target
            val pull = if (here != null && (inBand || grace)) PULL * (here.centre - y) else 0.0
            y += (vy + pull) * dt
        } else {
            // Resting or waiting: float to the middle of the cave.
            vy = ((here?.centre ?: y) - y) * 0.8
            y += vy * dt
        }

        if (here != null) {
            val ceiling = here.centre + here.half - TUX_HALF
            val floor = here.centre - here.half + TUX_HALF
            if (y > ceiling || y < floor) {
                y = y.coerceIn(floor, ceiling)
                vy = 0.0
                // Scraping along a wall is one bonk, not one per frame.
                if (working && !grace && clock - lastBonkAt > 1.5) {
                    bonks++
                    combo = 0
                }
                lastBonkAt = clock
            }
            camera += (here.centre - camera) * min(1.0, dt * 1.5)
        }

        for (f in fish) {
            if (f.eaten || f.passed) continue
            if (f.metre <= metres && abs(y - f.height) < CATCH_RADIUS) {
                f.eaten = true
                score += (if (f.gold) 3 else 1) * multiplier
                combo++
            } else if (f.metre < metres - 1.0) {
                f.passed = true
                // Golden fish are an optional stretch; missing one doesn't break the streak.
                if (!f.gold) combo = 0
            }
        }

        // A trail of bubbles streaming off behind Tux, faster the faster he swims.
        if (s.moving && clock >= nextBubble) {
            nextBubble = clock + 0.06
            bubbles.addLast(Bubble(metres - 0.6, y + bob - 0.05 + (sin(clock * 37) * 0.05), clock, 3f + (clock * 7 % 1).toFloat() * 4f))
        }
        bubbles.forEach { it.height += 0.25 * dt }
        while (bubbles.isNotEmpty() && clock - bubbles.first().born > 1.5) bubbles.removeFirst()
        while (slices.size > 2 && slices[1].metre < metres - BEHIND) slices.removeFirst()
        while (fish.isNotEmpty() && fish.first().metre < metres - BEHIND) fish.removeFirst()
    }

    /** The cave at [metre], interpolated between slices. */
    fun sliceAt(metre: Double): Slice? {
        val i = slices.indexOfFirst { it.metre > metre }
        if (i == -1) return slices.lastOrNull()
        if (i == 0) return slices.first()
        val a = slices[i - 1]
        val b = slices[i]
        val f = (metre - a.metre) / (b.metre - a.metre)
        return Slice(metre, a.centre + (b.centre - a.centre) * f, a.half + (b.half - a.half) * f)
    }

    /** New cave and fish enter at the right edge, [LOOKAHEAD] metres ahead of Tux. */
    private fun spawn(runner: WorkoutRunner, speed: Double) {
        val profile = runner.profile
        while (nextSlice <= metres + LOOKAHEAD) {
            val m = nextSlice
            nextSlice += SLICE_METRES
            val band = runner.bandAhead((m - metres) / speed.coerceAtLeast(1.5), speed)
            // The slope that rowing at the band's middle produces, per metre at that pace.
            val slope = band?.let { CLIMB * (it.middle - NEUTRAL) / profile.speedAt(it.middle) } ?: 0.0
            base += slope * SLICE_METRES
            // A little undulation for variety, eased in and out around rests.
            undulation += ((if (band != null) 1.0 else 0.0) - undulation) * 0.08
            val wave = undulation * WAVE * sin(2 * PI * m / 55 + 2.1)
            slices.addLast(Slice(m, base + wave, HALF + (1 - undulation) * 0.4))
        }
        while (nextFish <= metres + LOOKAHEAD) {
            val m = nextFish
            nextFish += FISH_METRES
            if (runner.bandAhead((m - metres) / speed.coerceAtLeast(1.5), speed) == null && runner.phase != Phase.READY) continue
            val c = sliceAt(m) ?: continue
            fishCount++
            val (offset, gold) = when {
                // Golden fish sit where rowing at the top or bottom of the band takes you.
                fishCount % 6 == 0 -> (if (fishCount % 12 == 0) -GOLD_OFFSET else GOLD_OFFSET) to true
                else -> sin(fishCount * 1.7) * 0.1 to false
            }
            fish.addLast(Fish(m, c.centre + offset, gold))
        }
    }

    companion object {
        const val VIEW_HEIGHT = 5.0
        /** The effort, as a fraction of 2k power, at which Tux neither climbs nor sinks: Glide. */
        const val NEUTRAL = 0.70
        /** Climb rate, in world units per second, per unit of 2k power above [NEUTRAL]. */
        const val CLIMB = 1.6
        /** Pull towards the cave's middle while in band, per second. */
        const val PULL = 1.0
        const val GRACE = 6.0
        const val HALF = 0.9
        const val TUX_HALF = 0.22
        const val WAVE = 0.45
        const val GOLD_OFFSET = 0.3
        const val BOB = 0.12
        const val BOB_SECONDS = 0.8
        const val SLICE_METRES = 1.0
        const val FISH_METRES = 4.0
        const val FIRST_FISH = 12.0
        /** Metres of course visible ahead of Tux. */
        const val LOOKAHEAD = 22.0
        const val BEHIND = 15.0
        const val CATCH_RADIUS = 0.22
    }
}

@Composable
private fun GameScene(game: CaveGame, frame: Long, target: Band?, modifier: Modifier) {
    val measurer = rememberTextMeasurer()
    Canvas(modifier) {
        frame // Redraw every frame.
        val iceHeight = 56f
        val unit = (size.height - iceHeight) / CaveGame.VIEW_HEIGHT.toFloat()
        val middle = iceHeight + (size.height - iceHeight) / 2
        fun y(world: Double) = middle - ((world - game.camera) * unit).toFloat()
        val tuxX = size.width * 0.3f
        val pxPerMetre = (size.width - tuxX) / CaveGame.LOOKAHEAD.toFloat()
        fun x(metre: Double) = tuxX + ((metre - game.metres) * pxPerMetre).toFloat()

        // Water gets darker with depth.
        val depth = (-game.camera / 10).toFloat().coerceIn(-1f, 1f)
        drawRect(lerp(Color(0xFF0B2A4A), Color(0xFF030A18), (depth + 1) / 2))

        if (game.slices.size > 1) {
            val ceiling = Path().apply {
                moveTo(x(game.slices.first().metre), 0f)
                game.slices.forEach { lineTo(x(it.metre), y(it.centre + it.half)) }
                lineTo(x(game.slices.last().metre), 0f)
                close()
            }
            val floor = Path().apply {
                moveTo(x(game.slices.first().metre), size.height)
                game.slices.forEach { lineTo(x(it.metre), y(it.centre - it.half)) }
                lineTo(x(game.slices.last().metre), size.height)
                close()
            }
            drawPath(ceiling, Color(0xFFBFE6F5))
            drawPath(floor, Color(0xFF2B3446))
            val edge = Path().apply {
                game.slices.forEachIndexed { i, it ->
                    val px = x(it.metre)
                    val py = y(it.centre + it.half)
                    if (i == 0) moveTo(px, py) else lineTo(px, py)
                }
            }
            drawPath(edge, Color.White.copy(alpha = 0.8f), style = Stroke(width = 4f))
        }

        for (f in game.fish) {
            if (!f.eaten) drawFish(Offset(x(f.metre), y(f.height)), f.gold)
        }
        for (b in game.bubbles) {
            val age = ((game.clock - b.born) / 1.5).toFloat()
            drawCircle(Color.White.copy(alpha = 0.5f * (1 - age)), b.size, Offset(x(b.metre), y(b.height)), style = Stroke(width = 2f))
        }

        val tilt = (-game.vy * 40).toFloat().coerceIn(-25f, 25f)
        val at = Offset(tuxX, y(game.y + game.bob))
        rotate(tilt, pivot = at) { drawTux(at) }

        drawRect(Ice, Offset.Zero, Size(size.width, iceHeight))
        val combo = if (game.multiplier > 1) "   ·   ×${game.multiplier}" else ""
        val count = measurer.measure(
            "Fish ${game.score}   ·   Streak ${game.combo}$combo   ·   Bonks ${game.bonks}",
            TextStyle(color = Deep, fontSize = 26.sp, fontWeight = FontWeight.SemiBold),
        )
        drawText(count, topLeft = Offset((size.width - count.size.width) / 2, (iceHeight - count.size.height) / 2))

        drawPowerGauge(game, target, top = iceHeight + 24f, bottom = size.height - 24f)
    }
}

/** A slim ladder of the bands on the left edge, with your power and the target band marked. */
private fun DrawScope.drawPowerGauge(game: CaveGame, target: Band?, top: Float, bottom: Float) {
    val left = 24f
    val width = 22f
    val bandHeight = (bottom - top) / Band.entries.size
    Band.entries.forEach { band ->
        val bandTop = bottom - (band.ordinal + 1) * bandHeight
        val lit = band == target
        drawRect(band.color().copy(alpha = if (lit) 0.95f else 0.3f), Offset(left, bandTop + 1f), Size(width, bandHeight - 2f))
        if (lit) drawRect(Color.White, Offset(left - 3f, bandTop), Size(width + 6f, bandHeight), style = Stroke(width = 2f))
    }
    val position = game.gauge.toFloat()
    val markerY = bottom - position * bandHeight
    drawPath(
        Path().apply {
            moveTo(left + width + 4f, markerY)
            lineTo(left + width + 22f, markerY - 11f)
            lineTo(left + width + 22f, markerY + 11f)
            close()
        },
        Color.White,
    )
}

/** A fish swimming left, towards Tux. */
private fun DrawScope.drawFish(c: Offset, gold: Boolean) {
    val body = if (gold) Color(0xFFFFD54F) else Color(0xFF9FC6D8)
    val r = if (gold) 1.3f else 1f
    drawPath(
        Path().apply {
            moveTo(c.x + 16f * r, c.y)
            lineTo(c.x + 30f * r, c.y - 11f * r)
            lineTo(c.x + 30f * r, c.y + 11f * r)
            close()
        },
        body,
    )
    drawOval(body, Offset(c.x - 20f * r, c.y - 10f * r), Size(40f * r, 20f * r))
    drawCircle(Deep, 3f * r, Offset(c.x - 10f * r, c.y - 2f * r))
    if (gold) drawCircle(Color.White.copy(alpha = 0.6f), 26f * r, c, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2f))
}

private fun DrawScope.drawTux(c: Offset) {
    val black = Color(0xFF16181E)
    val orange = Color(0xFFFAAA1E)
    drawPath(Path().apply { moveTo(c.x - 44f, c.y - 6f); lineTo(c.x - 64f, c.y - 16f); lineTo(c.x - 60f, c.y + 6f); close() }, orange)
    drawOval(black, Offset(c.x - 48f, c.y - 24f), Size(96f, 48f))
    drawOval(Color.White, Offset(c.x - 30f, c.y - 2f), Size(64f, 24f))
    drawCircle(black, 22f, Offset(c.x + 40f, c.y - 10f))
    drawCircle(Color.White, 7f, Offset(c.x + 46f, c.y - 15f))
    drawCircle(Color.Black, 3.5f, Offset(c.x + 48f, c.y - 15f))
    drawPath(Path().apply { moveTo(c.x + 58f, c.y - 14f); lineTo(c.x + 76f, c.y - 7f); lineTo(c.x + 58f, c.y - 1f); close() }, orange)
}

@Composable
private fun FullOverlay(v: WorkoutView, modifier: Modifier) {
    Row(
        modifier.fillMaxWidth().height(260.dp).background(PanelColor).padding(horizontal = 32.dp, vertical = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(40.dp),
    ) {
        Column(Modifier.weight(1.3f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            when (v.phase) {
                Phase.READY -> {
                    Label("Ready")
                    Text("Pull to start", style = MaterialTheme.typography.displaySmall, color = Ink)
                    v.piece?.let { TargetLine(it) }
                }
                Phase.WORK -> {
                    val piece = v.piece!!
                    Label("${piece.tag.label} ${v.pieceNumber} of ${v.pieceCount}")
                    TargetLine(piece)
                    Text(v.goalLeft, style = numbers(56))
                    v.progress?.let { p ->
                        LinearProgressIndicator(
                            progress = { p.coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth().height(10.dp),
                            color = piece.band?.color() ?: InkMuted,
                        )
                    }
                }
                Phase.REST -> {
                    Label("Rest")
                    Text(v.restLeft ?: "", style = numbers(56))
                    v.nextPiece?.let {
                        Label("Next")
                        TargetLine(it)
                    }
                }
                Phase.DONE -> Text("Done!", style = MaterialTheme.typography.displaySmall, color = Ink)
            }
        }
        Column(
            Modifier.weight(1f).fillMaxHeight(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(formatSplit(v.speed), style = numbers(84, FontWeight.Bold, v.band.color()))
            Text("/500m · ${v.band.penguinName()}", color = InkMuted, fontSize = 20.sp)
            if (v.onTarget) Text("On target", color = v.band.color(), fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        }
        Column(Modifier.weight(1.3f).fillMaxHeight(), verticalArrangement = Arrangement.SpaceEvenly) {
            val rate = v.piece?.rate?.let { " (${it.first}–${it.last})" } ?: ""
            StatRow("Watts", "${v.watts.roundToInt()}", "Rate", "${v.spm.roundToInt()} spm$rate")
            StatRow("Distance", "${v.distance.roundToInt()} m", "Time", formatClock(v.elapsed))
            StatRow("Calories", "${v.calories.roundToInt()}", "In band", v.pieceInBand?.let { "${(it * 100).roundToInt()}%" } ?: "–")
        }
    }
}

@Composable
private fun CompactOverlay(v: WorkoutView, modifier: Modifier) {
    Row(
        modifier.fillMaxWidth().height(72.dp).background(PanelColor).padding(horizontal = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(28.dp),
    ) {
        val piece = v.piece
        when (v.phase) {
            Phase.READY -> Text("Pull to start", style = numbers(28))
            Phase.WORK -> {
                Text("${v.pieceNumber}/${v.pieceCount}", style = numbers(24, color = InkMuted))
                piece?.let { Swatch(it.band) }
                Text(piece?.band?.penguinName() ?: "Free", style = numbers(26))
                Text(v.goalLeft, style = numbers(30))
            }
            Phase.REST -> {
                Text("Rest", style = numbers(26, color = InkMuted))
                Text(v.restLeft ?: "", style = numbers(30))
            }
            Phase.DONE -> Text("Done!", style = numbers(28))
        }
        Spacer(Modifier.weight(1f))
        Text(formatSplit(v.speed), style = numbers(34, FontWeight.Bold, v.band.color()))
        Text("${v.spm.roundToInt()} spm", style = numbers(24))
        Text(v.pieceInBand?.let { "${(it * 100).roundToInt()}% in band" } ?: "", style = numbers(24, color = InkMuted))
    }
}

@Composable
private fun Label(text: String) = Text(text.uppercase(), color = InkMuted, fontSize = 16.sp, letterSpacing = 2.sp)

@Composable
private fun Swatch(band: Band?) =
    Box(Modifier.size(22.dp).background(band?.color() ?: InkMuted, RoundedCornerShape(4.dp)))

@Composable
private fun TargetLine(piece: Piece) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Swatch(piece.band)
        Spacer(Modifier.width(12.dp))
        val rate = piece.rate?.let { " · rate ${it.first}–${it.last}" } ?: ""
        Text("${piece.goal.describe()} · ${piece.band?.penguinName() ?: "Free"}$rate", style = numbers(28))
    }
}

@Composable
private fun StatRow(label1: String, value1: String, label2: String, value2: String) {
    Row(Modifier.fillMaxWidth()) {
        Stat(label1, value1, Modifier.weight(1f))
        Stat(label2, value2, Modifier.weight(1f))
    }
}

@Composable
private fun Stat(label: String, value: String, modifier: Modifier) {
    Column(modifier) {
        Label(label)
        Text(value, style = numbers(30))
    }
}
