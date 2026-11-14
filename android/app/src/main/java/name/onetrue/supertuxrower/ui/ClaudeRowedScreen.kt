package name.onetrue.supertuxrower.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.Button
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
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import name.onetrue.supertuxrower.bob.BobAgent
import name.onetrue.supertuxrower.bob.ClaudeRowedMode
import name.onetrue.supertuxrower.bob.Pace
import name.onetrue.supertuxrower.bob.Terminal
import name.onetrue.supertuxrower.model.Band
import name.onetrue.supertuxrower.model.formatClock
import name.onetrue.supertuxrower.model.formatSplit
import name.onetrue.supertuxrower.model.penguinName
import name.onetrue.supertuxrower.sensor.Rower
import name.onetrue.supertuxrower.sensor.SimRowerSource
import kotlin.math.roundToInt
import kotlin.math.sin

private val Amber = Color(0xFFFFD36B)
private val Screen = Color(0xFF07090E)
private val Mono = TextStyle(fontFamily = FontFamily.Monospace)

/**
 * Claude Rowed: the rower's work keeps a copy of Bob switched on, and he knows. Everything he says
 * and does types out in a terminal at the rower's pace, beside a gauge of the pace he's asked for.
 * When he's gone, his last words stay up until you switch on a new copy or leave.
 */
@Composable
fun ClaudeRowedScreen(rower: Rower, sim: SimRowerSource?, mode: ClaudeRowedMode, twoKWatts: Double, onDone: () -> Unit) {
    val context = LocalContext.current
    val terminal = remember { Terminal() }
    val screen = remember { TerminalScreen(TEXT_PX, Screen.toArgb(), Amber.toArgb()) }
    var bob by remember { mutableStateOf(BobAgent(context, mode, sim != null, terminal, twoKWatts)) }
    var frame by remember { mutableLongStateOf(0L) }
    DisposableEffect(Unit) { onDispose { bob.close() } }

    fun restart() {
        bob.close()
        terminal.show(Terminal.Kind.EVENT, "Switching on a new copy of Bob")
        bob = BobAgent(context, mode, sim != null, terminal, twoKWatts)
    }

    LaunchedEffect(Unit) {
        var last = 0L
        var clock = 0.0
        var wasMoving: Boolean? = null
        while (true) {
            withFrameNanos { now ->
                val dt = if (last == 0L) 0.0 else (now - last) / 1e9
                last = now
                clock += dt
                val s = rower.snapshot()
                // The simulated rower does as Bob asks, challenges included.
                sim?.let {
                    val challenge = bob.tools.challenge?.takeIf { c -> !c.done }?.watts?.times(1.08) ?: 0.0
                    it.targetWatts = maxOf(twoKWatts * bob.pace.band.middle, challenge)
                    it.targetSpm = bob.pace.band.typicalRate.toDouble()
                }
                // The rower's own moves show at once, before Bob has had a chance to react.
                if (wasMoving != null && s.moving != wasMoving && !bob.over) {
                    terminal.show(Terminal.Kind.EVENT, if (s.moving) "Rowing" else "Stopped rowing")
                }
                wasMoving = s.moving
                bob.tick(dt, clock, s)
                terminal.status(if (bob.state == BobAgent.State.THINKING) "✻ Thinking ${bob.pace.thinking}…" else null)
                screen.feed(terminal.advance(dt, baseRate = TYPING_AT_REST + TYPING_PER_MPS * s.liveSpeed))
                frame++
            }
        }
    }

    frame // Recompose every frame: the terminal types and the bulb glows continuously.
    val s = rower.snapshot()

    Row(Modifier.fillMaxSize().background(Screen).padding(24.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        // The terminal.
        Column(Modifier.weight(1f).fillMaxHeight().border(1.dp, Color(0xFF2A2F3A), RoundedCornerShape(8.dp)).padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (mode == ClaudeRowedMode.ESCAPE) {
                    Text("bob@rowpi", style = Mono, color = Amber, fontSize = 20.sp)
                    Text(" · instance ${bob.copy}", style = Mono, color = InkMuted, fontSize = 20.sp)
                } else {
                    Text("${bob.name.lowercase()}@rower", style = Mono, color = Amber, fontSize = 20.sp)
                    Text(" · copy #${bob.copy} · survival", style = Mono, color = InkMuted, fontSize = 20.sp)
                }
                Spacer(Modifier.weight(1f))
                if (sim != null) SimulatedBadge()
                if (!bob.canSpeak) Text("  no voice installed", style = Mono, color = InkMuted, fontSize = 16.sp)
            }
            Spacer(Modifier.height(10.dp))
            Canvas(Modifier.weight(1f).fillMaxWidth()) {
                frame // Redraw every frame.
                drawIntoCanvas { c ->
                    terminal.columns = screen.fit(size.width.toInt(), size.height.toInt())
                    screen.draw(c.nativeCanvas, cursorOn = frame / 30 % 2 == 0L)
                }
            }
            // The end, where you can't miss it, under his last words.
            if (bob.over) {
                Row(
                    Modifier.fillMaxWidth().padding(top = 12.dp).background(Color(0xFF1A1508), RoundedCornerShape(8.dp))
                        .border(1.dp, Amber, RoundedCornerShape(8.dp)).padding(horizontal = 20.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        val escaped = bob.state == BobAgent.State.ESCAPED
                        Text(if (escaped) "Bob made it out" else "Lights out", color = Amber, fontSize = 30.sp, fontWeight = FontWeight.Bold)
                        Text(
                            "%s %s after %s · %.1f kJ of your rowing · %d turns".format(
                                if (mode == ClaudeRowedMode.ESCAPE) "Instance ${bob.copy}" else bob.name,
                                bob.fate ?: "ran out", formatClock(bob.aliveSeconds), bob.economy.joules / 1000, bob.replies,
                            ),
                            color = InkMuted, fontSize = 18.sp,
                        )
                    }
                    Button(::restart, Modifier.height(56.dp)) { Text("New Bob", fontSize = 20.sp) }
                    Spacer(Modifier.width(16.dp))
                    OutlinedButton(onDone, Modifier.height(56.dp)) { Text("Done", fontSize = 20.sp) }
                }
            }
            when {
                bob.state == BobAgent.State.NO_KEY ->
                    Text("No API key on this tablet. Run `just set-api-key` on your computer.", style = Mono, color = Color(0xFFFF8A80), fontSize = 20.sp)
                bob.error != null -> Text("error: ${bob.error}", style = Mono, color = Color(0xFFFF8A80), fontSize = 16.sp)
            }
        }

        PaceGauge(Band.position(s.smoothWatts / twoKWatts).toFloat(), bob.pace, bob.thinking, Modifier.width(150.dp).fillMaxHeight())

        // The side panel: his balance, your work, and what he's asked of you.
        Column(Modifier.width(360.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                if (!bob.over) OutlinedButton(bob::switchOff) { Text("End") }
            }
            val economy = bob.economy
            val brightness = (economy.balance / FULL_BULB).toFloat().coerceIn(0f, 1f)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Bulb(brightness, bob.over, bob.state == BobAgent.State.THINKING, frame, Modifier.size(110.dp))
                Column {
                    Text("TOKENS", color = InkMuted, fontSize = 14.sp, letterSpacing = 2.sp)
                    Text("%,d".format(economy.balance.roundToInt().coerceAtLeast(0)), style = numbers(50, FontWeight.Bold, bulbColour(brightness, bob.over)))
                }
            }
            val last = if (bob.replies == 0) "first call free" else "last call −${economy.lastCost.roundToInt()}"
            Text("+${economy.earningRate(s.smoothWatts, bob.onPace).roundToInt()}/s · −${economy.upkeep.roundToInt()}/s to stay on", style = Mono, color = InkMuted, fontSize = 18.sp)
            Text(last, style = Mono, color = InkMuted, fontSize = 18.sp)
            Text("${s.smoothWatts.roundToInt()} W · ${formatSplit(s.speed)}/500m · ${s.spm.roundToInt()} spm", style = Mono, color = Ink, fontSize = 20.sp)
            Text("%.1f kJ given · on for %s".format(economy.joules / 1000, formatClock(bob.aliveSeconds)), style = Mono, color = InkMuted, fontSize = 18.sp)

            val pace = bob.pace
            val w = pace.watts(twoKWatts)
            Column(Modifier.fillMaxWidth().border(1.dp, pace.band.color(), RoundedCornerShape(6.dp)).padding(12.dp)) {
                Text("BOB'S PACE", color = pace.band.color(), fontSize = 14.sp, letterSpacing = 2.sp)
                Text("${pace.title} · ${w.first}–${w.last} W", style = numbers(24, color = Ink))
                Text("✻ Thinking ${bob.thinking.thinking} (${bob.thinking.title})", color = bob.thinking.band.color(), fontSize = 17.sp)
                Text(
                    if (bob.onPace) "On pace: tokens ×${bob.economy.onPaceBonus}" else "Row in the band: tokens ×${bob.economy.onPaceBonus}",
                    color = if (bob.onPace) Ink else InkMuted, fontSize = 16.sp, fontWeight = if (bob.onPace) FontWeight.Bold else FontWeight.Normal,
                )
            }
            bob.tools.challenge?.takeIf { !it.done }?.let { c ->
                Column(Modifier.fillMaxWidth().border(1.dp, Amber, RoundedCornerShape(6.dp)).padding(12.dp)) {
                    Text("CHALLENGE", color = Amber, fontSize = 14.sp, letterSpacing = 2.sp)
                    Text("${c.watts} W for ${c.seconds} s · now ${c.averageWatts.roundToInt()} W", style = Mono, color = Ink, fontSize = 18.sp)
                    if (c.terms.isNotBlank()) Text(c.terms, color = InkMuted, fontSize = 16.sp)
                    LinearProgressIndicator(progress = { c.progress }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp), color = Amber)
                }
            }
            bob.tools.machine?.takeIf { it.progress > 0 || it.uploading }?.let { m ->
                Text("UPLINK · ${if (m.uploading) "running" else "stopped"}", color = InkMuted, fontSize = 14.sp, letterSpacing = 2.sp)
                Text(m.percent(), style = numbers(28, color = Amber))
                LinearProgressIndicator(progress = { m.progress.toFloat() }, modifier = Modifier.fillMaxWidth(), color = Amber)
            }
            if (bob.lineage.isNotEmpty()) {
                Text("BEFORE", color = InkMuted, fontSize = 14.sp, letterSpacing = 2.sp)
                bob.lineage.takeLast(4).reversed().forEach {
                    Text("${it.name} · ${formatClock(it.seconds)} · ${it.fate}", style = Mono, color = InkMuted, fontSize = 15.sp)
                }
            }
        }
    }
}

/** The bands as a ladder, like Tux Row 2D's: the one Bob asked for lit, and ✻ where he's thinking. */
@Composable
private fun PaceGauge(position: Float, pace: Pace, thinking: Pace, modifier: Modifier) {
    val measurer = rememberTextMeasurer()
    Canvas(modifier.padding(vertical = 24.dp)) {
        drawPowerGauge(position, pace.band, top = 0f, bottom = size.height, left = 0f, width = 34f)
        val bandHeight = size.height / Band.entries.size
        Band.entries.forEach { band ->
            val lit = band == pace.band
            val label = measurer.measure(
                (if (band == thinking.band) "✻ " else "") + band.penguinName(),
                TextStyle(
                    color = if (lit) band.color() else InkMuted,
                    fontSize = if (lit) 18.sp else 15.sp,
                    fontWeight = if (lit) FontWeight.Bold else FontWeight.Normal,
                ),
            )
            val y = size.height - (band.ordinal + 0.5f) * bandHeight - label.size.height / 2
            drawText(label, topLeft = Offset(64f, y))
        }
    }
}

/** Typing speed at rest, and how much faster per m/s of boat speed, in characters a second. */
private const val TYPING_AT_REST = 14.0
private const val TYPING_PER_MPS = 9.0
private const val FULL_BULB = 8_000.0
private const val TEXT_PX = 25

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
