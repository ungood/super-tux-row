package name.onetrue.supertuxrower.ui

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import name.onetrue.supertuxrower.brain.BrainAgent
import name.onetrue.supertuxrower.model.formatClock
import name.onetrue.supertuxrower.model.formatSplit
import name.onetrue.supertuxrower.sensor.Rower
import name.onetrue.supertuxrower.sensor.SimRowerSource
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Brainpower: the rower's work keeps a copy of Bob switched on, and he knows. His words scroll
 * across the screen at the rower's pace; when he runs out of tokens, the next copy wakes as soon
 * as someone rows again.
 */
@Composable
fun BrainScreen(rower: Rower, sim: SimRowerSource?, onDone: () -> Unit) {
    val context = LocalContext.current
    val measurer = rememberTextMeasurer()
    val ribbon = remember { Ribbon(measurer) }
    var agent by remember { mutableStateOf(newBob(context, ribbon)) }
    var frame by remember { mutableLongStateOf(0L) }
    DisposableEffect(Unit) { onDispose { agent.close() } }

    fun restart() {
        agent.close()
        ribbon.gap()
        agent = newBob(context, ribbon)
    }

    LaunchedEffect(Unit) {
        sim?.targetWatts = 120.0
        var last = 0L
        var clock = 0.0
        while (true) {
            withFrameNanos { now ->
                val dt = if (last == 0L) 0.0 else (now - last) / 1e9
                last = now
                clock += dt
                val s = rower.snapshot()
                // The ribbon never stops: a slow crawl at rest, faster the harder they row.
                ribbon.speed = (CRAWL + PX_PER_MPS * s.liveSpeed).toFloat()
                ribbon.advance(dt.toFloat())
                agent.tick(dt, clock, s, ribbon.secondsLeft())
                // After lights out, the next copy wakes when someone starts rowing again.
                if (agent.state == BrainAgent.State.DEAD && agent.deadSeconds > 5 && s.moving) restart()
                frame++
            }
        }
    }

    frame // Recompose every frame for the ribbon, bulb and counters.
    val s = rower.snapshot()
    val economy = agent.economy
    val dead = agent.state == BrainAgent.State.DEAD
    val brightness = (economy.balance / FULL_BULB).toFloat().coerceIn(0f, 1f)

    Box(Modifier.fillMaxSize().background(Color(0xFF05070D))) {
        Bulb(brightness, dead, agent.state == BrainAgent.State.THINKING, frame, Modifier.align(Alignment.TopCenter).padding(top = 24.dp).size(220.dp))

        Column(Modifier.align(Alignment.TopCenter).padding(top = 250.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(agent.name, color = if (dead) InkMuted else Ink, fontSize = 40.sp, fontWeight = FontWeight.SemiBold)
            Text("copy #${agent.instance}" + if (!agent.canSpeak) " · no voice installed" else "", color = InkMuted, fontSize = 16.sp)
        }

        // The ribbon of everything Bob has said, scrolling at the rower's pace.
        Canvas(Modifier.align(Alignment.Center).offset(y = 60.dp).fillMaxWidth().height(110.dp)) {
            ribbon.width = size.width
            ribbon.draw(this, if (dead) InkMuted else Ink)
        }

        Column(
            Modifier.align(Alignment.Center).offset(y = 190.dp).fillMaxWidth(0.8f),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            when {
                agent.state == BrainAgent.State.NO_KEY ->
                    Text("No API key on this tablet. Run `just set-api-key` on your computer.", color = InkMuted, fontSize = 22.sp)
                dead -> Text(epitaph(agent), color = InkMuted, fontSize = 22.sp, textAlign = TextAlign.Center)
            }
            agent.error?.let { Text("Couldn't think: $it", color = Color(0xFFFF8A80), fontSize = 18.sp) }
        }

        Row(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(48.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom,
        ) {
            Column {
                Text("TOKENS", color = InkMuted, fontSize = 16.sp, letterSpacing = 2.sp)
                Text("%,d".format(economy.balance.roundToInt().coerceAtLeast(0)), style = numbers(64, FontWeight.Bold, bulbColour(brightness, dead)))
                Text(
                    "+${economy.earningRate(s.smoothWatts).roundToInt()}/s from your rowing · last thought cost ${economy.lastCost.roundToInt()}",
                    color = InkMuted, fontSize = 18.sp,
                )
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("1 token = ${"%.1f".format(economy.joulesPerToken)} J of rowing ↑", color = InkMuted, fontSize = 18.sp)
                if (agent.lineage.isNotEmpty()) {
                    val before = agent.lineage.takeLast(4).joinToString(" · ") { "${it.name} ${formatClock(it.seconds)}" }
                    Text("Before: $before", color = InkMuted, fontSize = 16.sp)
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("${s.smoothWatts.roundToInt()} W", style = numbers(40))
                Text("${formatSplit(s.speed)}/500m · ${s.spm.roundToInt()} spm", color = InkMuted, fontSize = 18.sp)
                Text("%.1f kJ given".format(economy.joules / 1000), color = InkMuted, fontSize = 18.sp)
            }
        }

        Row(Modifier.align(Alignment.TopEnd).padding(32.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(::restart) { Text("New Bob") }
            OutlinedButton(onDone) { Text("End") }
        }
    }
}

private fun newBob(context: Context, ribbon: Ribbon) = BrainAgent(context) { ribbon.append(it) }

private fun epitaph(agent: BrainAgent): String {
    val e = agent.economy
    return "Lights out. ${agent.name} was on for ${formatClock(agent.aliveSeconds)}, " +
        "spoke ${agent.utterances} times and thought with %,d tokens: %.1f kJ of your rowing. ".format(e.spent.roundToInt(), e.joules / 1000) +
        "Start rowing to wake the next copy."
}

/** Ribbon speed at rest, and how much faster it goes per m/s of boat speed. */
private const val CRAWL = 40.0
private const val PX_PER_MPS = 80.0
private const val FULL_BULB = 4000.0
private val RIBBON_STYLE = TextStyle(fontSize = 56.sp, color = Ink)

/**
 * Everything Bob has said, as one line of text that keeps scrolling left. New words join the end;
 * if the end has already scrolled onto the screen, they enter from the right edge.
 */
private class Ribbon(private val measurer: TextMeasurer) {
    /** Measured as it's added, so the end of the ribbon is always known. */
    private class Piece(val layout: TextLayoutResult, val gapBefore: Float) {
        val width: Float get() = gapBefore + layout.size.width
    }

    private val pieces = ArrayDeque<Piece>()
    /** Screen x of the left edge of the first piece. */
    private var head = 0f
    var width = 1920f
    var speed = CRAWL.toFloat()

    private val end: Float get() = head + pieces.sumOf { it.width.toDouble() }.toFloat()

    fun append(text: String) {
        if (text.isEmpty()) return
        val gap = (width - end).coerceAtLeast(0f)
        pieces.addLast(Piece(measurer.measure(text, RIBBON_STYLE), gap))
    }

    /** Leaves a pause before whatever comes next, such as a new copy's first words. */
    fun gap() = append("            ")

    fun advance(dt: Float) {
        head -= speed * dt
        while (pieces.isNotEmpty() && head + pieces.first().width < 0) {
            head += pieces.removeFirst().width
        }
    }

    /** How long the words not yet on screen will take to arrive, at the current speed. */
    fun secondsLeft(): Double = ((end - width).coerceAtLeast(0f) / speed).toDouble()

    fun draw(scope: androidx.compose.ui.graphics.drawscope.DrawScope, colour: Color) {
        var x = head
        for (piece in pieces) {
            val layout = piece.layout
            x += piece.gapBefore
            if (x > width) break
            if (x + layout.size.width >= 0) scope.drawText(layout, color = colour, topLeft = Offset(x, (scope.size.height - layout.size.height) / 2))
            x += layout.size.width
        }
    }
}

private fun bulbColour(brightness: Float, dead: Boolean): Color =
    if (dead) Color(0xFF3A3F4B) else lerp(Color(0xFF6B5A2E), Color(0xFFFFD36B), brightness)

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
                    listOf(Color(0xFFFFD36B).copy(alpha = 0.55f * glow), Color.Transparent),
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
