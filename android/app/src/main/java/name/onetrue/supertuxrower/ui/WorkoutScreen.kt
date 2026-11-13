package name.onetrue.supertuxrower.ui

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
