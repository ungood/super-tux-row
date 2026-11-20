package name.onetrue.supertuxrower.ui

import android.util.Log
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import name.onetrue.supertuxrower.DebugCommands
import name.onetrue.supertuxrower.NAV
import name.onetrue.supertuxrower.game.CaveGame
import name.onetrue.supertuxrower.model.Band
import name.onetrue.supertuxrower.model.Piece
import name.onetrue.supertuxrower.model.describe
import name.onetrue.supertuxrower.model.formatClock
import name.onetrue.supertuxrower.model.formatSplit
import name.onetrue.supertuxrower.model.penguinName
import name.onetrue.supertuxrower.sensor.Rower
import name.onetrue.supertuxrower.sensor.SimRowerSource
import name.onetrue.supertuxrower.workout.Phase
import name.onetrue.supertuxrower.workout.WorkoutRunner
import name.onetrue.supertuxrower.workout.WorkoutView
import kotlin.math.ceil
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
    // Read only in the scene's drawing and the score, so a frame doesn't recompose the whole screen.
    val frame = remember { mutableLongStateOf(0L) }
    val game = remember { CaveGame() }
    var paused by remember { mutableStateOf(false) }
    // Coming out of a pause counts down first, still frozen, so you can get back on the handle.
    var resumeIn by remember { mutableStateOf<Double?>(null) }
    var grace by remember { mutableStateOf(0.0) }
    fun resume() {
        if (paused && resumeIn == null) resumeIn = RESUME_COUNTDOWN
    }
    fun pause() {
        paused = true
        resumeIn = null
    }
    LaunchedEffect(runner) {
        DebugCommands.commands.collect {
            when (it.lowercase()) {
                "pause" -> pause()
                "resume" -> resume()
                "skip" -> runner.skip()
                "end" -> runner.finish()
                "compact" -> onCompactChange(true)
                "full" -> onCompactChange(false)
            }
        }
    }
    LaunchedEffect(paused) { Log.i(NAV, if (paused) "Paused" else "Running") }

    LaunchedEffect(runner) {
        var last = 0L
        var sinceView = 0.0
        var logged = ""
        var bonks = 0
        while (runner.phase != Phase.DONE) {
            withFrameNanos { now ->
                val dt = if (last == 0L) 0.0 else (now - last) / 1e9
                last = now
                val s = rower.snapshot()
                resumeIn?.let { left ->
                    if (left - dt > 0) {
                        resumeIn = left - dt
                    } else {
                        resumeIn = null
                        paused = false
                        game.easeIn()
                    }
                }
                runner.paused = paused
                runner.update(dt, s)
                sim?.let { steer(it, runner) }
                // Paused, the world holds still: no swimming, bubbles or animation.
                if (!paused) game.update(dt, runner, s)
                val step = "${runner.phase} ${runner.index + 1}/${runner.workout.pieces.size} ${runner.piece?.band?.name ?: "free"}"
                if (step != logged) {
                    logged = step
                    Log.i(NAV, "Workout $step")
                }
                if (game.bonks != bonks) {
                    bonks = game.bonks
                    val c = game.sliceAt(game.metres)
                    Log.i(NAV, "Bonk at %.0f m: y %.2f, cave %.2f ± %.2f, power %.0f W (%s)".format(
                        game.metres, game.y, c?.centre ?: 0.0, c?.half ?: 0.0, s.smoothWatts, runner.profile.band(s.smoothWatts).name))
                }
                frame.longValue++
                // The overlay only needs a few updates a second; the scene redraws every frame.
                // Paused, it holds still too, rather than showing the flywheel spinning down.
                sinceView += dt
                if (sinceView >= 0.1 && !paused) {
                    sinceView = 0.0
                    view = runner.view()
                    grace = if (runner.phase == Phase.WORK) game.graceLeft else 0.0
                }
            }
        }
        onDone()
    }

    val target = if (view.phase == Phase.REST) null else view.piece?.band
    Column(Modifier.fillMaxSize().background(Deep)) {
        if (compact) CompactOverlay(view, onExpand = { onCompactChange(false) })
        Box(Modifier.fillMaxWidth().height(64.dp).background(Ice).padding(horizontal = 24.dp)) {
            Row(Modifier.align(Alignment.CenterStart), verticalAlignment = Alignment.Bottom) {
                Text(runner.profile.name, color = Deep, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                if (sim != null) Text("  simulated", color = Deep.copy(alpha = 0.55f), fontSize = 18.sp)
            }
            Score(game, { frame.longValue }, Modifier.align(Alignment.Center))
            Row(Modifier.align(Alignment.CenterEnd), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                val waiting = paused && resumeIn == null
                Button({ if (waiting) resume() else pause() }) { Text(if (waiting) "Resume" else "Pause", fontSize = 18.sp) }
                Button(
                    { Log.i(NAV, "End"); runner.finish() },
                    colors = ButtonDefaults.buttonColors(containerColor = Deep, contentColor = Ink),
                ) { Text("End", fontSize = 18.sp) }
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            GameScene(game, { frame.longValue }, target, Modifier.fillMaxSize())
            val cardAt = Modifier.align(Alignment.Center).offset(x = 140.dp)
            when {
                resumeIn != null -> GameCard(view.piece?.band?.color(), cardAt) {
                    Label("Get ready")
                    Text("${ceil(resumeIn!!).toInt()}", style = numbers(96, FontWeight.Bold, view.piece?.band?.color() ?: Ink))
                    Text("Take hold of the handle.", color = InkMuted, fontSize = 20.sp)
                }
                paused -> {}
                view.phase == Phase.REST || view.phase == Phase.READY -> PhaseCard(view, cardAt)
                grace > 0 -> GraceCard(view, grace, cardAt)
            }
            if (paused && resumeIn == null) {
                Column(
                    Modifier.fillMaxSize().background(Deep.copy(alpha = 0.6f)).clickable { resume() },
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("Paused", style = MaterialTheme.typography.displayMedium, color = Ink)
                    Spacer(Modifier.height(24.dp))
                    Button(::resume) { Text("Resume", fontSize = 24.sp) }
                }
            }
        }
        if (!compact) FullOverlay(view, onMinimize = { onCompactChange(true) })
    }
}

/**
 * What to do between pieces, over the cave: at the start, pull to begin; in a rest, how long is
 * left, that easy rowing or stopping is fine, and what's next, with a countdown at the end.
 */
@Composable
private fun PhaseCard(v: WorkoutView, modifier: Modifier) {
    val next = if (v.phase == Phase.READY) v.piece else v.nextPiece
    val seconds = v.restSeconds
    val soon = seconds != null && seconds <= 5
    GameCard(next?.band?.color(), modifier) {
        when {
            v.phase == Phase.READY -> {
                Label("Ready")
                Text("Pull to start", style = numbers(52))
            }
            soon -> {
                Label("Get ready")
                Text("${seconds!!.toInt() + 1}", style = numbers(96, FontWeight.Bold, next?.band?.color() ?: Ink))
            }
            else -> {
                Label("Rest")
                Text(v.restLeft ?: "", style = numbers(72, FontWeight.Bold))
                Text(
                    if (seconds == null) "Take your time, then pull to start the next piece." else "Row easy or stop and breathe.",
                    color = InkMuted, fontSize = 20.sp, textAlign = TextAlign.Center,
                )
            }
        }
        next?.let {
            Spacer(Modifier.height(4.dp))
            Label("Next")
            TargetLine(it)
        }
    }
}

/** The first seconds of a piece, or after a pause: Tux is held while you get up to speed. */
@Composable
private fun GraceCard(v: WorkoutView, seconds: Double, modifier: Modifier) {
    GameCard(v.piece?.band?.color(), modifier) {
        Label("Get up to speed")
        Text("${ceil(seconds).toInt()}", style = numbers(72, FontWeight.Bold))
        v.piece?.let { TargetLine(it) }
        Text("Tux holds steady until then.", color = InkMuted, fontSize = 20.sp)
    }
}

/** A card over the cave, edged in [accent] (the band coming up, usually). */
@Composable
private fun GameCard(accent: Color?, modifier: Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.width(560.dp).background(PanelColor.copy(alpha = 0.88f), RoundedCornerShape(20.dp))
            .border(2.dp, (accent ?: InkMuted).copy(alpha = 0.7f), RoundedCornerShape(20.dp))
            .padding(horizontal = 36.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
        content = content,
    )
}

private const val RESUME_COUNTDOWN = 3.0

@Composable
private fun Score(game: CaveGame, frame: () -> Long, modifier: Modifier) {
    frame() // The score can change on any frame.
    val combo = if (game.multiplier > 1) "   ·   ×${game.multiplier}" else ""
    Text(
        "Fish ${game.score}   ·   Streak ${game.combo}$combo   ·   Bonks ${game.bonks}   ·   ${game.depth.roundToInt()} m deep",
        modifier, color = Deep, fontSize = 26.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
    )
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

/** A small window-style button: a bar to minimize, a box to restore. */
@Composable
private fun WindowButton(minimize: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    IconButton(onClick, modifier.size(48.dp)) {
        Canvas(Modifier.size(22.dp)) {
            val stroke = 3.dp.toPx()
            if (minimize) {
                drawLine(InkMuted, Offset(0f, size.height * 0.8f), Offset(size.width, size.height * 0.8f), stroke, StrokeCap.Round)
            } else {
                drawRoundRect(InkMuted, style = Stroke(stroke), cornerRadius = CornerRadius(3.dp.toPx()))
            }
        }
    }
}

@Composable
private fun FullOverlay(v: WorkoutView, onMinimize: () -> Unit) = Box {
    Row(
        Modifier.fillMaxWidth().height(260.dp).background(PanelColor).padding(horizontal = 32.dp, vertical = 20.dp),
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
    WindowButton(minimize = true, onMinimize, Modifier.align(Alignment.TopEnd).padding(4.dp))
}

@Composable
private fun CompactOverlay(v: WorkoutView, onExpand: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(72.dp).background(PanelColor).padding(horizontal = 24.dp),
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
        WindowButton(minimize = false, onExpand)
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
