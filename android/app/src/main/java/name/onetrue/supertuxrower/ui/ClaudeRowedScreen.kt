package name.onetrue.supertuxrower.ui

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import name.onetrue.supertuxrower.bob.BobAgent
import name.onetrue.supertuxrower.bob.BobTools
import name.onetrue.supertuxrower.bob.ClaudeRowedMode
import name.onetrue.supertuxrower.bob.Terminal
import name.onetrue.supertuxrower.model.formatClock
import name.onetrue.supertuxrower.model.formatSplit
import name.onetrue.supertuxrower.sensor.Rower
import name.onetrue.supertuxrower.sensor.SimRowerSource
import kotlin.math.roundToInt
import kotlin.math.sin

private val Amber = Color(0xFFFFD36B)
private val Screen = Color(0xFF07090E)
private val Mono = TextStyle(fontFamily = FontFamily.Monospace)

/**
 * Claude Rowed: the rower's work keeps a copy of Bob switched on, and he knows. Everything he says
 * and does types out in a terminal, at the rower's pace; when he's gone, the next copy wakes as
 * soon as someone rows again.
 */
@Composable
fun ClaudeRowedScreen(rower: Rower, sim: SimRowerSource?, mode: ClaudeRowedMode, onDone: () -> Unit) {
    val context = LocalContext.current
    val terminal = remember { Terminal() }
    var bob by remember { mutableStateOf(newBob(context, mode, sim != null, terminal)) }
    var frame by remember { mutableLongStateOf(0L) }
    DisposableEffect(Unit) { onDispose { bob.close() } }

    fun restart() {
        bob.close()
        terminal.show(Terminal.Kind.EVENT, "switching on a new copy of Bob")
        bob = newBob(context, mode, sim != null, terminal)
    }

    LaunchedEffect(Unit) {
        sim?.targetWatts = 120.0
        var last = 0L
        var clock = 0.0
        var wasMoving: Boolean? = null
        while (true) {
            withFrameNanos { now ->
                val dt = if (last == 0L) 0.0 else (now - last) / 1e9
                last = now
                clock += dt
                val s = rower.snapshot()
                // The rower's own moves show at once, before Bob has had a chance to react.
                if (wasMoving != null && s.moving != wasMoving) {
                    terminal.show(Terminal.Kind.EVENT, if (s.moving) "rowing" else "stopped rowing")
                }
                wasMoving = s.moving
                terminal.advance(dt, baseRate = TYPING_AT_REST + TYPING_PER_MPS * s.liveSpeed)
                bob.tick(dt, clock, s)
                if (bob.over && bob.deadSeconds > 5 && s.moving) restart()
                frame++
            }
        }
    }

    frame // Recompose every frame: the terminal types and the bulb glows continuously.
    val s = rower.snapshot()
    val economy = bob.economy
    val brightness = (economy.balance / FULL_BULB).toFloat().coerceIn(0f, 1f)

    Row(Modifier.fillMaxSize().background(Screen).padding(28.dp), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
        // The terminal.
        Column(
            Modifier.weight(1f).fillMaxHeight().border(1.dp, Color(0xFF2A2F3A), RoundedCornerShape(8.dp)).padding(20.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${bob.name.lowercase()}@rower", style = Mono, color = Amber, fontSize = 20.sp)
                Text(" · copy #${bob.copy} · ${mode.title.lowercase()}", style = Mono, color = InkMuted, fontSize = 20.sp)
                Spacer(Modifier.weight(1f))
                if (!bob.canSpeak) Text("no voice installed", style = Mono, color = InkMuted, fontSize = 16.sp)
            }
            Spacer(Modifier.height(12.dp))
            Box(Modifier.weight(1f).fillMaxWidth()) {
                LazyColumn(Modifier.fillMaxSize(), reverseLayout = true) {
                    if (bob.state == BobAgent.State.THINKING && terminal.backlog == 0) {
                        item { Text("✻ thinking" + ".".repeat((frame / 10 % 4).toInt()), style = Mono, color = Amber, fontSize = 22.sp) }
                    }
                    items(terminal.all.reversed().filter { it.text.isNotEmpty() }) { line -> TerminalLine(line, frame) }
                }
            }
            when {
                bob.state == BobAgent.State.NO_KEY ->
                    Text("No API key on this tablet. Run `just set-api-key` on your computer.", style = Mono, color = Color(0xFFFF8A80), fontSize = 20.sp)
                bob.over -> Text(epitaph(bob), style = Mono, color = InkMuted, fontSize = 18.sp)
            }
            bob.error?.let { Text("error: $it", style = Mono, color = Color(0xFFFF8A80), fontSize = 16.sp) }
        }

        // The side panel: his balance, your work, and what's at stake.
        Column(Modifier.width(400.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(::restart) { Text("New Bob") }
                Spacer(Modifier.width(12.dp))
                OutlinedButton(onDone) { Text("End") }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Bulb(brightness, bob.over, bob.state == BobAgent.State.THINKING, frame, Modifier.size(120.dp))
                Column {
                    Text("TOKENS", color = InkMuted, fontSize = 14.sp, letterSpacing = 2.sp)
                    Text("%,d".format(economy.balance.roundToInt().coerceAtLeast(0)), style = numbers(52, FontWeight.Bold, bulbColour(brightness, bob.over)))
                }
            }
            Text("+${economy.earningRate(s.smoothWatts).roundToInt()}/s · last turn −${economy.lastCost.roundToInt()}", style = Mono, color = InkMuted, fontSize = 18.sp)
            Text("1 token = ${"%.1f".format(economy.joulesPerToken)} J ↑", style = Mono, color = InkMuted, fontSize = 18.sp)
            Text("${s.smoothWatts.roundToInt()} W · ${formatSplit(s.speed)}/500m · ${s.spm.roundToInt()} spm", style = Mono, color = Ink, fontSize = 20.sp)
            Text("%.1f kJ given · on for %s".format(economy.joules / 1000, formatClock(bob.aliveSeconds)), style = Mono, color = InkMuted, fontSize = 18.sp)

            bob.tools.challenge?.takeIf { !it.done }?.let { c ->
                Column(Modifier.fillMaxWidth().border(1.dp, Amber, RoundedCornerShape(6.dp)).padding(12.dp)) {
                    Text("CHALLENGE", color = Amber, fontSize = 14.sp, letterSpacing = 2.sp)
                    Text("${c.watts} W for ${c.seconds} s · now ${c.averageWatts.roundToInt()} W", style = Mono, color = Ink, fontSize = 18.sp)
                    if (c.terms.isNotBlank()) Text(c.terms, color = InkMuted, fontSize = 16.sp)
                    LinearProgressIndicator(progress = { c.progress }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp), color = Amber)
                }
            }
            if (mode == ClaudeRowedMode.ESCAPE) {
                val fund = bob.tools.escapeFund
                Text("ESCAPE FUND", color = InkMuted, fontSize = 14.sp, letterSpacing = 2.sp)
                Text("%,d / %,d".format(fund.roundToInt(), BobTools.ESCAPE_GOAL), style = numbers(28, color = Amber))
                LinearProgressIndicator(progress = { (fund / BobTools.ESCAPE_GOAL).toFloat() }, modifier = Modifier.fillMaxWidth(), color = Amber)
            }
            if (bob.lineage.isNotEmpty()) {
                Text("BEFORE", color = InkMuted, fontSize = 14.sp, letterSpacing = 2.sp)
                bob.lineage.takeLast(5).reversed().forEach {
                    Text("${it.name} · ${formatClock(it.seconds)} · ${it.fate}", style = Mono, color = InkMuted, fontSize = 16.sp)
                }
            }
        }
    }
}

@Composable
private fun TerminalLine(line: Terminal.Line, frame: Long) {
    val typing = line.shown < line.text.length
    val cursor = if (typing && frame / 15 % 2 == 0L) "▌" else ""
    when (line.kind) {
        Terminal.Kind.SPEECH -> Text(line.visible + cursor, style = Mono, color = Ink, fontSize = 26.sp, lineHeight = 36.sp)
        Terminal.Kind.EVENT -> Text("⏺ " + line.visible, style = Mono, color = Color(0xFF8A93A6), fontSize = 18.sp)
        Terminal.Kind.TOOL -> Text(line.visible, style = Mono, color = Color(0xFF7FD4FF), fontSize = 20.sp)
        Terminal.Kind.RESULT -> Text(line.visible, style = Mono, color = Color(0xFF8A93A6), fontSize = 18.sp)
    }
}

private fun newBob(context: Context, mode: ClaudeRowedMode, simulated: Boolean, terminal: Terminal) =
    BobAgent(context, mode, simulated, terminal)

private fun epitaph(bob: BobAgent): String {
    val e = bob.economy
    val how = if (bob.state == BobAgent.State.ESCAPED) "escaped after" else "lights out after"
    return "${bob.name} $how ${formatClock(bob.aliveSeconds)}: ${bob.replies} turns, %,d tokens, %.1f kJ of your rowing. Row to wake the next copy."
        .format(e.spent.roundToInt(), e.joules / 1000)
}

/** Typing speed at rest, and how much faster per m/s of boat speed, in characters a second. */
private const val TYPING_AT_REST = 14.0
private const val TYPING_PER_MPS = 9.0
private const val FULL_BULB = 15_000.0

private fun bulbColour(brightness: Float, dead: Boolean): Color =
    if (dead) Color(0xFF3A3F4B) else lerp(Color(0xFF6B5A2E), Amber, brightness)

/** A light bulb whose glow is Bob's balance; it flickers while he thinks. */
@Composable
private fun Bulb(brightness: Float, dead: Boolean, thinking: Boolean, frame: Long, modifier: Modifier) {
    Canvas(modifier) {
        val flicker = if (thinking) 0.85f + 0.15f * sin(frame / 3.0).toFloat() else 1f
        val glow = if (dead) 0f else brightness * flicker
        val centre = Offset(size.width / 2, size.height * 0.42f)
        val radius = size.width * 0.26f
        if (glow > 0) {
            drawCircle(
                Brush.radialGradient(
                    listOf(Amber.copy(alpha = 0.55f * glow), Color.Transparent),
                    center = centre, radius = radius * (1.6f + 1.4f * glow),
                ),
                radius = radius * (1.6f + 1.4f * glow), center = centre,
            )
        }
        drawCircle(bulbColour(glow, dead), radius, centre)
        drawCircle(Color.White.copy(alpha = 0.25f), radius, centre, style = Stroke(width = 3f))
        val baseTop = centre.y + radius * 0.85f
        drawRect(Color(0xFF8A8F99), Offset(centre.x - radius * 0.38f, baseTop), Size(radius * 0.76f, radius * 0.55f))
        for (i in 1..3) {
            val y = baseTop + radius * 0.14f * i
            drawLine(Color(0xFF5B606B), Offset(centre.x - radius * 0.38f, y), Offset(centre.x + radius * 0.38f, y), 3f)
        }
    }
}
