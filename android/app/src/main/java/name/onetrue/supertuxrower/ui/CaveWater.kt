package name.onetrue.supertuxrower.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import name.onetrue.supertuxrower.game.Cue
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/*
 * Life in the open water: the drifting things Tux can eat (jellies, seahorses, pufferfish), and
 * schools, krill, turtles and other penguins in the middle distance.
 */

/** The names `just do show` accepts for the middle distance and for drifting food. */
internal val MidwaterNames = listOf("school", "krill", "turtle", "penguins", "lanterns")
internal val DrifterNames = listOf("jellyfish", "combjelly", "seahorse", "pufferfish")

/** A kind of middle-distance life: its share of the stretches, and the daylight it lives in. */
private class Midwater(val name: String, val share: Float, val minLight: Float, val maxLight: Float = 1f)

private val Midwaters = listOf(
    Midwater("school", 0.4f, 0.3f),
    Midwater("lanterns", 0.3f, 0f, 0.35f),
    Midwater("krill", 0.12f, 0.45f),
    Midwater("turtle", 0.07f, 0.55f),
    Midwater("penguins", 0.09f, 0.5f),
)

/**
 * The middle distance, muted so nothing there competes with what Tux can eat. Each stretch of 900
 * parallax pixels may hold one thing, chosen by its hash; what lives there depends on the depth,
 * and things fade out as the light leaves their range rather than vanishing. Near the surface,
 * schools of fish, krill, turtles and penguins zooming past; in the deep, glowing lanternfish.
 */
internal fun DrawScope.drawMidwater(scroll: Float, clock: Double, light: Float) {
    val parallax = 0.35f
    val chunk = 900f
    val offset = scroll * parallax
    // Solid, a little lighter than the water, and tinted towards teal, so they read without
    // drawing the eye and stand apart from the giants behind.
    val base = lerp(lerp(Color(0xFF0C2236), Color(0xFF3D7DAA), light), Color(0xFF2E8A8A), 0.25f)
    val only = Summon.only?.takeIf { it in MidwaterNames }
    // Penguins and turtles swim well away from their own stretch, so look a few stretches beyond
    // the screen either side; otherwise they'd vanish while still in view.
    for (i in floor((offset - 3 * chunk) / chunk).toInt()..floor((offset + size.width + 3 * chunk) / chunk).toInt()) {
        val r = hash(i, 150)
        var from = 0f
        val kind = if (only != null) Midwaters.first { it.name == only } else Midwaters.firstOrNull { from += it.share; r < from } ?: continue
        // Fading over a band of light either side of its range.
        val fade = if (only != null) 1f else ((light - kind.minLight) / 0.08f + 1).coerceIn(0f, 1f) * ((kind.maxLight - light) / 0.08f + 1).coerceIn(0f, 1f)
        if (fade <= 0f) continue
        val colour = base.copy(alpha = fade)
        val left = i * chunk - offset
        when (kind.name) {
            "school" -> drawSchool(i, left, chunk, clock, colour)
            "lanterns" -> drawLanterns(Offset(left + chunk * (0.3f + hash(i, 180) * 0.4f), size.height * (0.3f + hash(i, 181) * 0.4f)), i, clock, fade)
            "krill" -> drawKrill(Offset(left + chunk * (0.3f + hash(i, 170) * 0.4f), size.height * (0.3f + hash(i, 171) * 0.4f)), i, clock, colour)
            "turtle" -> {
                val swim = (clock * 22 % 1400).toFloat()
                drawTurtle(Offset(left + chunk + 200f - swim, size.height * (0.32f + hash(i, 172) * 0.3f)), clock, colour)
            }
            "penguins" -> drawPenguins(i, left, chunk, clock, colour)
        }
    }
}

private fun DrawScope.drawSchool(i: Int, left: Float, chunk: Float, clock: Double, colour: Color) {
    val phase = hash(i, 151) * 6f
    val swim = sin(clock * 0.12 + phase)
    val facing = if (cos(clock * 0.12 + phase) >= 0) 1f else -1f
    val centre = Offset(
        left + hash(i, 152) * chunk * 0.6f + swim.toFloat() * 160f,
        size.height * (0.3f + hash(i, 153) * 0.4f),
    )
    val count = 5 + (hash(i, 154) * 7).toInt()
    for (k in 0 until count) {
        val at = Offset(
            centre.x + (hash(i * 31 + k, 155) - 0.5f) * 140f,
            centre.y + (hash(i * 31 + k, 156) - 0.5f) * 60f + sin(clock * 2 + k).toFloat() * 3f,
        )
        drawTinyFish(at, facing, colour, clock + k)
    }
}

private fun DrawScope.drawTinyFish(at: Offset, facing: Float, colour: Color, clock: Double) {
    val beat = sin(clock * 10).toFloat() * 3f
    drawPath(Path().apply {
        moveTo(at.x - facing * 8f, at.y)
        lineTo(at.x - facing * 15f, at.y - 5f + beat)
        lineTo(at.x - facing * 15f, at.y + 5f + beat)
        close()
    }, colour)
    drawOval(colour, Offset(at.x - 10f, at.y - 4f), Size(20f, 8f))
}

/** A cloud of krill, pinkish specks milling about together. */
private fun DrawScope.drawKrill(c: Offset, seed: Int, clock: Double, colour: Color) {
    val pink = lerp(colour, Color(0xFFFFA494), 0.6f)
    val drift = Offset(sin(clock * 0.2 + seed).toFloat() * 60f, sin(clock * 0.3 + seed).toFloat() * 20f)
    for (k in 0 until 80) {
        val r = hash(seed * 97 + k, 173)
        val a = clock * (0.3 + hash(seed * 97 + k, 174) * 0.4) + k
        val at = Offset(c.x + drift.x + cos(a).toFloat() * 150f * r, c.y + drift.y + sin(a * 1.3).toFloat() * 60f * r)
        drawLine(pink, at, Offset(at.x + 5f, at.y + sin(a * 3).toFloat()), 3f, StrokeCap.Round)
    }
}

/**
 * A sea turtle swimming left, side on: a domed shell with its plates marked, a pale rim of belly
 * under it, a beaked head out front, and long front flippers that sweep down and back like oars.
 */
private fun DrawScope.drawTurtle(c: Offset, clock: Double, colour: Color) = scale(1.3f, pivot = c) {
    val stroke = sin(clock * 1.2).toFloat()
    val shell = lerp(colour, Color(0xFF5E7A40), 0.5f)
    val seam = lerp(shell, Color.Black, 0.3f)
    val skin = lerp(colour, Color(0xFF9AB080), 0.45f)
    val far = lerp(skin, colour, 0.4f)
    // A front flipper: long and tapering, from the shoulder back past the shell's middle.
    fun flipper(shade: Color, swing: Float) = rotate(swing, pivot = Offset(c.x - 34f, c.y + 2f)) {
        drawPath(Path().apply {
            moveTo(c.x - 40f, c.y - 2f)
            quadraticTo(c.x - 10f, c.y + 2f, c.x + 26f, c.y + 26f)
            quadraticTo(c.x - 6f, c.y + 18f, c.x - 34f, c.y + 10f)
            close()
        }, shade)
    }
    // The far side's flippers, behind the shell, half a stroke apart from the near ones.
    flipper(far, -stroke * 28f - 6f)
    drawPath(Path().apply {
        moveTo(c.x + 42f, c.y)
        lineTo(c.x + 64f, c.y + 6f - stroke * 4f)
        lineTo(c.x + 44f, c.y + 10f)
        close()
    }, far)
    // Head and neck.
    drawPath(Path().apply {
        moveTo(c.x - 44f, c.y - 10f)
        quadraticTo(c.x - 58f, c.y - 16f, c.x - 70f, c.y - 16f)
        quadraticTo(c.x - 86f, c.y - 14f, c.x - 88f, c.y - 6f)
        quadraticTo(c.x - 84f, c.y + 2f, c.x - 68f, c.y + 2f)
        quadraticTo(c.x - 56f, c.y + 2f, c.x - 44f, c.y + 4f)
        close()
    }, skin)
    drawCircle(lerp(colour, Color.Black, 0.6f), 2.6f, Offset(c.x - 76f, c.y - 10f))
    drawLine(lerp(skin, Color.Black, 0.3f), Offset(c.x - 88f, c.y - 5f), Offset(c.x - 78f, c.y - 3f), 1.5f)
    // The shell: a dome over a flatter belly, with a paler rim between.
    val dome = Path().apply {
        moveTo(c.x - 52f, c.y + 2f)
        quadraticTo(c.x - 46f, c.y - 36f, c.x, c.y - 38f)
        quadraticTo(c.x + 44f, c.y - 36f, c.x + 54f, c.y + 2f)
        close()
    }
    drawPath(dome, shell)
    drawPath(Path().apply {
        moveTo(c.x - 52f, c.y + 2f)
        lineTo(c.x + 54f, c.y + 2f)
        quadraticTo(c.x + 40f, c.y + 12f, c.x, c.y + 12f)
        quadraticTo(c.x - 40f, c.y + 12f, c.x - 52f, c.y + 2f)
        close()
    }, lerp(skin, Color.White, 0.25f))
    // Plates: a row along the top, edged by the rim's smaller ones.
    for (k in 0..2) {
        val x = c.x - 26f + k * 26f
        drawPath(Path().apply {
            moveTo(x - 12f, c.y - 16f)
            lineTo(x - 7f, c.y - 30f + (if (k == 1) -3f else 0f))
            lineTo(x + 7f, c.y - 30f + (if (k == 1) -3f else 0f))
            lineTo(x + 12f, c.y - 16f)
            lineTo(x + 7f, c.y - 6f)
            lineTo(x - 7f, c.y - 6f)
            close()
        }, seam, style = Stroke(width = 2f))
    }
    for (k in 0..6) drawLine(seam, Offset(c.x - 44f + k * 15f, c.y - 2f), Offset(c.x - 46f + k * 15f, c.y + 2f), 1.5f)
    // The near front flipper over everything, and the near hind one.
    flipper(skin, stroke * 28f - 6f)
    drawPath(Path().apply {
        moveTo(c.x + 40f, c.y + 4f)
        lineTo(c.x + 62f, c.y + 12f + stroke * 4f)
        lineTo(c.x + 40f, c.y + 12f)
        close()
    }, skin)
}

/** A few penguins torpedoing past, quicker than anything else in the water. */
private fun DrawScope.drawPenguins(seed: Int, left: Float, chunk: Float, clock: Double, colour: Color) {
    val run = 3600f
    val dash = ((clock * 480 + hash(seed, 175) * run) % run).toFloat()
    val lead = left + chunk + 1200f - dash
    val y = size.height * (0.3f + hash(seed, 176) * 0.4f)
    val dark = lerp(colour, Color.Black, 0.35f)
    val belly = lerp(colour, Color.White, 0.3f)
    for (k in 0 until 3 + (hash(seed, 177) * 2).toInt()) {
        val at = Offset(lead + k * 90f + hash(seed * 7 + k, 178) * 40f, y + (hash(seed * 7 + k, 179) - 0.5f) * 90f + sin(clock * 3 + k).toFloat() * 8f)
        val flap = sin(clock * 16 + k).toFloat()
        drawOval(dark, Offset(at.x - 30f, at.y - 9f), Size(60f, 18f))
        drawOval(belly, Offset(at.x - 24f, at.y), Size(46f, 8f))
        drawPath(Path().apply {
            moveTo(at.x - 30f, at.y - 2f)
            lineTo(at.x - 40f, at.y + 1f)
            lineTo(at.x - 29f, at.y + 3f)
        }, dark)
        drawLine(dark, Offset(at.x - 4f, at.y - 2f), Offset(at.x + 14f, at.y - 2f + flap * 12f), 5f, StrokeCap.Round)
        // Their wake of bubbles.
        for (j in 1..3) drawCircle(belly.copy(alpha = 0.5f), 2f + j * 0.5f, Offset(at.x + 30f + j * 16f, at.y + sin(clock * 9 + j + k).toFloat() * 4f))
    }
}

/** A school of lanternfish in the deep: dark little fish shown by the lights along their sides. */
private fun DrawScope.drawLanterns(c: Offset, seed: Int, clock: Double, fade: Float) {
    val glow = Color(0xFF7FF5E8)
    val swim = sin(clock * 0.15 + seed).toFloat() * 120f
    for (k in 0 until 14) {
        val at = Offset(
            c.x + swim + (hash(seed * 31 + k, 182) - 0.5f) * 220f + sin(clock * 0.8 + k).toFloat() * 6f,
            c.y + (hash(seed * 31 + k, 183) - 0.5f) * 90f + sin(clock * 1.3 + k).toFloat() * 4f,
        )
        val blink = 0.6f + 0.4f * sin(clock * 2 + k * 1.7).toFloat()
        drawCircle(glow.copy(alpha = 0.15f * fade * blink), 9f, at)
        for (j in 0 until 3) drawCircle(glow.copy(alpha = 0.8f * fade * blink), 1.6f, Offset(at.x - 6f + j * 5f, at.y + 2f))
    }
}

/**
 * Something drifting that Tux can eat, at [c] where the game has it. They bob only a little, so
 * they're caught where they look to be.
 */
internal fun DrawScope.drawDrifter(name: String, c: Offset, seed: Int, life: Life) {
    when (name) {
        "jellyfish" -> drawJellyfish(c, seed, life)
        "combjelly" -> drawCombJelly(c, seed, life.clock)
        "seahorse" -> drawSeahorse(c, seed, life.clock)
        else -> drawPufferfish(c, seed, life)
    }
}

private val JellyHues = listOf(320f, 280f, 20f, 200f)

/** A jellyfish pulsing slowly upward and sinking back, tentacles trailing; it glows in the deep. */
private fun DrawScope.drawJellyfish(c: Offset, seed: Int, life: Life) {
    val clock = life.clock
    val hue = JellyHues[(hash(seed, 510) * JellyHues.size).toInt().coerceAtMost(JellyHues.lastIndex)]
    val pulse = ((sin(clock * 2 + seed).toFloat() + 1) / 2)
    val at = Offset(c.x, c.y - 10f + sin(clock * 0.5 + seed).toFloat() * 5f)
    val w = 46f * (1 - pulse * 0.15f)
    val h = 30f * (1 + pulse * 0.1f)
    val glow = (1 - life.light).coerceIn(0f, 1f)
    val bell = Color.hsv(hue, 0.35f + glow * 0.2f, 1f)
    if (glow > 0.2f) drawCircle(Brush.radialGradient(listOf(bell.copy(alpha = 0.35f * glow), Color.Transparent), at, 90f), 90f, at)
    for (k in 0 until 6) {
        val x = at.x - w * 0.4f + k * w * 0.16f
        drawPath(Path().apply {
            moveTo(x, at.y)
            quadraticTo(x + sin(clock * 1.5 + k).toFloat() * 10f, at.y + 40f, x + sin(clock * 1.2 + k * 2).toFloat() * 14f, at.y + 70f + k % 3 * 10f)
        }, bell.copy(alpha = 0.45f), style = Stroke(width = 1.6f, cap = StrokeCap.Round))
    }
    // Frilly oral arms in the middle.
    for (k in -1..1) drawPath(Path().apply {
        moveTo(at.x + k * 6f, at.y)
        quadraticTo(at.x + k * 10f + sin(clock * 2 + k).toFloat() * 6f, at.y + 22f, at.x + k * 4f, at.y + 40f)
    }, bell.copy(alpha = 0.6f), style = Stroke(width = 5f, cap = StrokeCap.Round))
    drawArc(bell.copy(alpha = 0.55f + 0.25f * glow), 180f, 180f, true, Offset(at.x - w / 2, at.y - h), Size(w, h * 2))
    drawArc(Color.White.copy(alpha = 0.35f), 200f, 60f, false, Offset(at.x - w * 0.35f, at.y - h * 0.8f), Size(w * 0.7f, h * 1.4f), style = Stroke(width = 2f))
}

/** A comb jelly: a clear little lozenge with rows of beating combs that shimmer rainbow. */
private fun DrawScope.drawCombJelly(c: Offset, seed: Int, clock: Double) {
    val at = Offset(c.x + sin(clock * 0.4 + seed).toFloat() * 4f, c.y + sin(clock * 0.6 + seed).toFloat() * 5f)
    val w = 36f
    val h = 56f
    drawOval(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.05f), Color(0xFFBFF3FF).copy(alpha = 0.3f)), at, h / 2), Offset(at.x - w / 2, at.y - h / 2), Size(w, h))
    drawOval(Color.White.copy(alpha = 0.35f), Offset(at.x - w / 2, at.y - h / 2), Size(w, h), style = Stroke(width = 1.5f))
    for (row in 0 until 4) {
        val rx = (row - 1.5f) / 1.5f * w * 0.42f
        for (j in 0 until 7) {
            val f = (j + 0.5f) / 7f - 0.5f
            val y = at.y + f * h * 0.85f
            val x = at.x + rx * (1 - 4 * f * f).coerceAtLeast(0.2f)
            // The light breaks into colours as it travels down each row.
            val hue = ((clock * 240 - j * 45 + row * 30) % 360).toFloat().let { if (it < 0) it + 360 else it }
            drawCircle(Color.hsv(hue, 0.7f, 1f).copy(alpha = 0.85f), 2.2f, Offset(x, y))
        }
    }
}

/** A seahorse bobbing upright, its little back fin fluttering. */
private fun DrawScope.drawSeahorse(c: Offset, seed: Int, clock: Double) {
    val hue = pickHue(seed, 520, listOf(40f, 25f, 10f, 300f), spread = 8f)
    val body = Color.hsv(hue, 0.65f, 0.85f)
    val dark = Color.hsv(hue, 0.7f, 0.55f)
    val at = Offset(c.x, c.y + 10f + sin(clock * 1.1 + seed).toFloat() * 4f)
    rotate(sin(clock * 0.7 + seed).toFloat() * 5f, pivot = at) {
        // Its spine, head to curled tail, drawn as overlapping discs.
        val spine = listOf(
            Triple(0f, -30f, 7f), Triple(2f, -24f, 6f), Triple(5f, -17f, 7.5f), Triple(6f, -10f, 8f),
            Triple(5f, -3f, 7f), Triple(2f, 3f, 5.5f), Triple(-1f, 8f, 4.5f), Triple(-2f, 13f, 3.5f),
            Triple(0f, 17f, 3f), Triple(3f, 18f, 2.5f), Triple(5f, 16f, 2f), Triple(4f, 13f, 1.8f),
        )
        val s = 1.4f
        for (k in 0 until spine.lastIndex) {
            val (x0, y0, r0) = spine[k]
            val (x1, y1, r1) = spine[k + 1]
            for (t in 0 until 4) {
                val f = t / 4f
                drawCircle(body, (r0 + (r1 - r0) * f) * s, Offset(at.x + (x0 + (x1 - x0) * f) * s, at.y + (y0 + (y1 - y0) * f) * s))
            }
        }
        // Ridges across the belly.
        for (k in 0 until 6) drawLine(dark, Offset(at.x + (-1f + k * 0.4f) * s, at.y + (-18f + k * 5f) * s), Offset(at.x + (9f - k * 0.6f) * s, at.y + (-19f + k * 5f) * s), 1.2f)
        // Snout, crown and eye.
        drawLine(body, Offset(at.x - 2f * s, at.y - 31f * s), Offset(at.x - 14f * s, at.y - 28f * s), 4f * s, StrokeCap.Round)
        drawCircle(body, 3f * s, Offset(at.x + 2f * s, at.y - 37f * s))
        drawCircle(Color.Black, 1.5f * s, Offset(at.x - 1f * s, at.y - 32f * s))
        val flutter = sin(clock * 14).toFloat() * 3f
        drawPath(Path().apply {
            moveTo(at.x + 11f * s, at.y - 14f * s)
            lineTo(at.x + (17f + flutter) * s, at.y - 10f * s)
            lineTo(at.x + 11f * s, at.y - 5f * s)
            close()
        }, Color.hsv(hue, 0.3f, 1f).copy(alpha = 0.8f))
    }
}

/** A pufferfish, which blows itself up into a spiky ball as Tux comes close; that puff is heard. */
private fun DrawScope.drawPufferfish(c: Offset, seed: Int, life: Life) {
    val clock = life.clock
    val near = life.near(c.x, 320f)
    val puff = ((near - 0.3f) / 0.25f).coerceIn(0f, 1f).let { it * it * (3 - 2 * it) }
    if (puff > 0.5f) life.cue(Cue.PUFF, seed * 7 + 3)
    val at = Offset(c.x + sin(clock * 0.5 + seed).toFloat() * 4f, c.y + sin(clock * 0.9 + seed).toFloat() * 4f)
    val rx = 20f + 10f * puff
    val ry = 13f + 17f * puff
    val skin = Color(0xFFC8C08A)
    val belly = Color(0xFFF2EED8)
    val spot = Color(0xFF5A5030)
    val beat = sin(clock * 12).toFloat()
    // Tail and fins.
    drawPath(Path().apply {
        moveTo(at.x + rx - 2f, at.y)
        lineTo(at.x + rx + 12f, at.y - 8f + beat * 2f)
        lineTo(at.x + rx + 12f, at.y + 8f + beat * 2f)
        close()
    }, skin)
    if (puff > 0.05f) for (k in 0 until 20) {
        val a = k * 2 * PI.toFloat() / 20
        val from = Offset(at.x + cos(a) * rx, at.y + sin(a) * ry)
        drawLine(spot, from, Offset(from.x + cos(a) * 8f * puff, from.y + sin(a) * 8f * puff), 1.6f, StrokeCap.Round)
    }
    drawOval(skin, Offset(at.x - rx, at.y - ry), Size(rx * 2, ry * 2))
    drawOval(belly, Offset(at.x - rx * 0.85f, at.y), Size(rx * 1.7f, ry * 0.95f))
    for (k in 0 until 6) drawCircle(spot, 2f, Offset(at.x - rx * 0.5f + hash(seed * 5 + k, 530) * rx, at.y - ry * 0.7f + hash(seed * 5 + k, 531) * ry * 0.6f))
    drawOval(skin, Offset(at.x - 2f, at.y - 2f + beat * 2f), Size(10f, 6f))
    // A big worried eye and a small pursed mouth.
    val eye = Offset(at.x - rx * 0.55f, at.y - ry * 0.3f)
    drawCircle(Color.White, 5f + 2f * puff, eye)
    drawCircle(Color.Black, 2.6f + puff, Offset(eye.x - 1f, eye.y))
    drawCircle(Color(0xFF8A6A40), 2.5f, Offset(at.x - rx + 1f, at.y + 2f))
}
