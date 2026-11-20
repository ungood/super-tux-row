package name.onetrue.supertuxrower.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/*
 * The cave's other inhabitants: life on the floor, schools of fish in the distance, and now and
 * then something big passing far behind. Like the rest of the art they're placed by hashing world
 * position, so the game doesn't keep them.
 */

/** Something living on the floor at [p], chosen by [r] (a hash from 0.3 up; most are bare). */
internal fun DrawScope.drawFloorLife(p: WallPoint, r: Float, clock: Double) {
    val seed = p.index
    val at = Offset(p.x + (hash(seed, 100) - 0.5f) * 24f, p.y + 4f)
    when {
        r < 0.37f -> drawAnemone(at, seed, clock)
        r < 0.43f -> drawSponge(at, seed)
        r < 0.48f -> drawTubeWorms(at, seed, clock)
        r < 0.51f -> drawCrab(at, seed, clock)
        r < 0.54f -> drawStarfish(Offset(at.x, at.y + 2f), 12f + hash(seed, 46) * 6f, hash(seed, 45) * 72f, seed)
    }
}

/** A hue from [hues], nudged a little either way, so no two look quite the same. */
private fun pickHue(seed: Int, salt: Int, hues: List<Float>, spread: Float = 12f): Float {
    val base = hues[(hash(seed, salt) * hues.size).toInt().coerceAtMost(hues.lastIndex)]
    return ((base + (hash(seed, salt + 1) - 0.5f) * 2 * spread) + 360f) % 360f
}

private val AnemoneHues = listOf(335f, 290f, 25f, 170f, 50f)

/** A sea anemone: a stubby column and a crown of tentacles waving. */
private fun DrawScope.drawAnemone(base: Offset, seed: Int, clock: Double) {
    val hue = pickHue(seed, 101, AnemoneHues)
    val colour = Color.hsv(hue, 0.5f + hash(seed, 103) * 0.2f, 0.75f + hash(seed, 104) * 0.2f)
    val tip = Color.hsv(hue, 0.25f, 1f)
    val size = 0.8f + hash(seed, 102) * 0.6f
    val top = Offset(base.x, base.y - 18f * size)
    val count = 9
    for (k in 0 until count) {
        val spread = (k - (count - 1) / 2f) / ((count - 1) / 2f)
        val sway = sin(clock * 1.8 + seed + k * 0.6).toFloat() * 6f
        val end = Offset(top.x + spread * 22f * size + sway, top.y - (20f + 10f * (1 - abs(spread))) * size)
        val path = Path().apply {
            moveTo(top.x + spread * 6f * size, top.y)
            quadraticTo(top.x + spread * 18f * size, top.y - 8f * size, end.x, end.y)
        }
        drawPath(path, colour, style = Stroke(width = 5f * size, cap = StrokeCap.Round))
        drawCircle(tip, 3f * size, end)
    }
    drawRoundRect(colour.copy(alpha = 0.9f), Offset(base.x - 9f * size, top.y - 2f), Size(18f * size, base.y - top.y + 6f), androidx.compose.ui.geometry.CornerRadius(6f))
}

/** A cluster of tube sponges, open at the top. */
private fun DrawScope.drawSponge(base: Offset, seed: Int) {
    // Mostly ochres and oranges, sometimes purple or red.
    val hue = pickHue(seed, 110, listOf(20f, 35f, 48f, 48f, 285f, 355f), spread = 8f)
    val tubes = 2 + (hash(seed, 111) * 3).toInt()
    for (k in 0 until tubes) {
        val colour = Color.hsv(hue, 0.55f + hash(seed, 125 + k) * 0.25f, 0.6f + hash(seed, 128 + k) * 0.3f)
        val w = 14f + hash(seed, 112 + k) * 8f
        val h = 26f + hash(seed, 116 + k) * 40f
        val x = base.x + (k - (tubes - 1) / 2f) * 15f
        drawRoundRect(colour, Offset(x - w / 2, base.y - h), Size(w, h + 6f), androidx.compose.ui.geometry.CornerRadius(w / 2))
        drawOval(colour.copy(red = colour.red * 0.5f, green = colour.green * 0.5f, blue = colour.blue * 0.5f), Offset(x - w * 0.35f, base.y - h + 2f), Size(w * 0.7f, 6f))
        // Pores.
        repeat(3) { j -> drawCircle(Color.Black.copy(alpha = 0.2f), 1.6f, Offset(x + (hash(seed, 120 + j + k) - 0.5f) * w * 0.6f, base.y - h * (0.3f + j * 0.2f))) }
    }
}

/** Tube worms: white tubes with red plumes that pull in now and then. */
private fun DrawScope.drawTubeWorms(base: Offset, seed: Int, clock: Double) {
    val worms = 3 + (hash(seed, 130) * 3).toInt()
    for (k in 0 until worms) {
        val h = 20f + hash(seed, 131 + k) * 36f
        val x = base.x + (k - (worms - 1) / 2f) * 9f
        drawLine(Color(0xFFE8E4DA), Offset(x, base.y + 4f), Offset(x, base.y - h), 6f, StrokeCap.Round)
        // Out most of the time; ducks in for a moment every so often.
        val cycle = ((clock * 0.25 + hash(seed, 137 + k) * 7) % 1.0).toFloat()
        val out = if (cycle < 0.85f) 1f else abs(cycle - 0.925f) / 0.075f
        if (out < 0.1f) continue
        val crown = Offset(x, base.y - h - 2f)
        for (j in -2..2) {
            val a = (-90f + j * 22f + sin(clock * 2 + k + j).toFloat() * 6f) * PI.toFloat() / 180f
            drawLine(Color(0xFFD8343C), crown, Offset(crown.x + cos(a) * 12f * out, crown.y + sin(a) * 12f * out), 3f, StrokeCap.Round)
        }
    }
}

/** A crab, scuttling back and forth sideways. */
private fun DrawScope.drawCrab(base: Offset, seed: Int, clock: Double) {
    val phase = hash(seed, 140) * 6f
    val walk = sin(clock * 0.6 + phase)
    val x = base.x + walk.toFloat() * 30f
    val moving = abs(cos(clock * 0.6 + phase)) > 0.2
    val step = if (moving) sin(clock * 14).toFloat() else 0f
    val body = Offset(x, base.y - 12f)
    val hue = pickHue(seed, 141, listOf(8f, 20f), spread = 6f)
    val shell = Color.hsv(hue, 0.75f, 0.85f)
    val dark = Color.hsv(hue, 0.8f, 0.55f)
    // Legs.
    for (side in listOf(-1f, 1f)) for (k in 0..2) {
        val lift = if ((k % 2 == 0) == (side > 0)) step * 4f else -step * 4f
        val hip = Offset(body.x + side * (6f + k * 5f), body.y + 4f)
        val knee = Offset(hip.x + side * 10f, hip.y - 4f + lift)
        drawLine(dark, hip, knee, 3f, StrokeCap.Round)
        drawLine(dark, knee, Offset(knee.x + side * 4f, base.y + 2f), 3f, StrokeCap.Round)
    }
    // Claws, raised.
    for (side in listOf(-1f, 1f)) {
        val claw = Offset(body.x + side * 26f, body.y - 10f + sin(clock * 3 + side).toFloat() * 2f)
        drawLine(shell, Offset(body.x + side * 12f, body.y - 2f), claw, 4f, StrokeCap.Round)
        drawCircle(shell, 7f, claw)
        drawLine(dark, claw, Offset(claw.x + side * 6f, claw.y - 5f), 2.5f, StrokeCap.Round)
    }
    drawOval(shell, Offset(body.x - 18f, body.y - 9f), Size(36f, 20f))
    drawOval(Color.White.copy(alpha = 0.2f), Offset(body.x - 10f, body.y - 7f), Size(16f, 6f))
    // Eyes on stalks.
    for (side in listOf(-1f, 1f)) {
        val eye = Offset(body.x + side * 6f, body.y - 16f)
        drawLine(dark, Offset(body.x + side * 5f, body.y - 7f), eye, 2f)
        drawCircle(Color.White, 3.5f, eye)
        drawCircle(Color.Black, 1.8f, eye)
    }
}

internal fun DrawScope.drawStarfish(c: Offset, r: Float, angle: Float, seed: Int) {
    val hue = pickHue(seed, 47, listOf(355f, 15f, 30f, 280f), spread = 8f)
    val path = Path()
    for (i in 0 until 10) {
        val a = (angle + i * 36f - 90f) * PI.toFloat() / 180f
        val len = if (i % 2 == 0) r else r * 0.45f
        val p = Offset(c.x + cos(a) * len, c.y + sin(a) * len)
        if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
    }
    path.close()
    drawPath(path, Color.hsv(hue, 0.6f, 0.9f))
    drawCircle(Color.hsv(hue, 0.3f, 1f), r * 0.2f, c)
}

/**
 * Schools of small fish in the middle distance, muted so they don't compete with the fish Tux can
 * catch. Each stretch of 900 parallax pixels has a school about half the time.
 */
internal fun DrawScope.drawSchools(scroll: Float, clock: Double, light: Float) {
    val parallax = 0.35f
    val chunk = 900f
    val offset = scroll * parallax
    // Solid, a little lighter than the water so they read without drawing the eye.
    val colour = lerp(Color(0xFF0C2236), Color(0xFF3D7DAA), light)
    for (i in floor((offset - chunk) / chunk).toInt()..floor((offset + size.width + chunk) / chunk).toInt()) {
        if (hash(i, 150) > 0.5f) continue
        val phase = hash(i, 151) * 6f
        val swim = sin(clock * 0.12 + phase)
        val facing = if (cos(clock * 0.12 + phase) >= 0) 1f else -1f
        val centre = Offset(
            i * chunk - offset + hash(i, 152) * chunk * 0.6f + swim.toFloat() * 160f,
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

/**
 * Now and then something big passes far behind the cave: a whale near the surface, a squid or an
 * octopus at any depth. Each stretch of 5000 parallax pixels (roughly 300 m of rowing, though they
 * swim too) holds one about a third of the time, so they stay a surprise.
 */
/** Sends a big creature across the screen now, for checking how they look (`just do giant whale`). */
object Summon {
    var kind: String? = null
    internal var at = Double.NaN
}

internal fun DrawScope.drawGiants(scroll: Float, clock: Double, light: Float) {
    // Solid, so their parts don't show through each other, and darker than the water around them.
    val silhouette = lerp(Color(0xFF040C17), Color(0xFF143A58), light)
    Summon.kind?.let { kind ->
        if (Summon.at.isNaN()) Summon.at = clock
        val speed = if (kind == "orca") ORCA_SPEED else 160.0
        val x = size.width + 500f - ((clock - Summon.at) * speed).toFloat()
        val at = Offset(x, size.height * 0.5f)
        scale(GIANT_SCALE, pivot = at) {
            when (kind) {
                "whale" -> drawWhale(at, silhouette, clock)
                "orca" -> drawOrcaPass(at, silhouette, clock)
                "squid" -> drawSquid(at, silhouette, clock, light)
                else -> drawOctopus(at, silhouette, clock, light)
            }
        }
        if (x < -600f) Summon.kind = null
    }
    val parallax = 0.25f
    val chunk = 5000f
    // They swim towards Tux as well as scrolling past, so they come by even when you're slow.
    val offset = scroll * parallax + (clock * 14).toFloat()
    for (i in floor((offset - chunk) / chunk).toInt()..floor((offset + size.width + chunk) / chunk).toInt()) {
        if (hash(i, 160) > 0.33f) continue
        val x = i * chunk - offset + hash(i, 161) * (chunk - 1500f)
        if (x < -1200f || x > size.width + 1200f) continue
        // The camera keeps the cave mid-screen, so they swim there too, not up behind the ice.
        val y = size.height * (0.38f + hash(i, 162) * 0.24f)
        val kind = hash(i, 163)
        scale(GIANT_SCALE, pivot = Offset(x, y)) {
            when {
                kind < 0.3f && light > 0.35f -> drawWhale(Offset(x, y), silhouette, clock)
                // An orca hunts: from when it comes in at the right, it covers ground much faster.
                kind < 0.5f && light > 0.3f -> {
                    val entry = size.width + 700f
                    drawOrcaPass(Offset(entry - (entry - x) * 3.5f, y), silhouette, clock)
                }
                kind < 0.75f -> drawSquid(Offset(x, y), silhouette, clock, light)
                else -> drawOctopus(Offset(x, y), silhouette, clock, light)
            }
        }
    }
}

private const val GIANT_SCALE = 1.5f

/** A whale swimming left, its flukes beating slowly. */
private fun DrawScope.drawWhale(c: Offset, colour: Color, clock: Double) {
    val beat = sin(clock * 1.1).toFloat()
    val belly = lerp(colour, Color.White, 0.12f)
    // Flukes, at the end of a tapering tail.
    rotate(beat * 10f, pivot = Offset(c.x + 300f, c.y)) {
        drawPath(Path().apply {
            moveTo(c.x + 280f, c.y)
            quadraticTo(c.x + 360f, c.y - 70f, c.x + 400f, c.y - 60f)
            quadraticTo(c.x + 370f, c.y, c.x + 400f, c.y + 60f)
            quadraticTo(c.x + 360f, c.y + 70f, c.x + 280f, c.y)
        }, colour)
    }
    drawPath(Path().apply {
        moveTo(c.x - 320f, c.y + 10f)
        quadraticTo(c.x - 320f, c.y - 80f, c.x - 150f, c.y - 85f)
        quadraticTo(c.x + 120f, c.y - 80f, c.x + 300f, c.y - 8f)
        lineTo(c.x + 300f, c.y + 8f)
        quadraticTo(c.x + 100f, c.y + 70f, c.x - 150f, c.y + 70f)
        quadraticTo(c.x - 300f, c.y + 65f, c.x - 320f, c.y + 10f)
    }, colour)
    // Throat grooves and a flipper.
    for (k in 0..3) drawLine(belly, Offset(c.x - 280f + k * 6f, c.y + 30f + k * 8f), Offset(c.x - 60f, c.y + 38f + k * 8f), 3f)
    rotate(-beat * 8f + 20f, pivot = Offset(c.x - 120f, c.y + 40f)) {
        drawOval(colour, Offset(c.x - 130f, c.y + 30f), Size(120f, 26f))
    }
    drawCircle(Color.White.copy(alpha = 0.25f), 6f, Offset(c.x - 230f, c.y - 10f))
}

private const val ORCA_SPEED = 520.0

/**
 * An orca on the hunt, at [c] as it crosses the screen: it swoops down and up, banking along its
 * path, and as it passes Tux it opens its jaws.
 */
private fun DrawScope.drawOrcaPass(c: Offset, colour: Color, clock: Double) {
    val wave = 260f
    val dip = 90f
    val at = Offset(c.x, c.y + sin(c.x / wave) * dip)
    // Pointing along its path: it swims left, so a rising path to the right means nose down.
    val slope = cos(c.x / wave) * dip / wave
    val bank = (kotlin.math.atan(slope) * 180 / PI).toFloat()
    val tux = size.width * 0.3f
    val jaw = (1 - abs(c.x - tux) / 380f).coerceIn(0f, 1f)
    rotate(bank, pivot = at) { drawOrca(at, colour, clock, jaw) }
}

/**
 * An orca swimming left: smaller than the whale, with a fast tail, a tall dorsal fin and the white
 * eye patch and belly. [jaw] from 0 to 1 opens its mouth.
 */
private fun DrawScope.drawOrca(c: Offset, colour: Color, clock: Double, jaw: Float) {
    val beat = sin(clock * 6).toFloat()
    val dark = lerp(Color(0xFF02060C), colour, 0.35f)
    val white = lerp(Color(0xFFE8F0F4), colour, 0.45f)
    rotate(beat * 16f, pivot = Offset(c.x + 150f, c.y)) {
        drawPath(Path().apply {
            moveTo(c.x + 140f, c.y)
            quadraticTo(c.x + 190f, c.y - 40f, c.x + 215f, c.y - 36f)
            quadraticTo(c.x + 195f, c.y, c.x + 215f, c.y + 36f)
            quadraticTo(c.x + 190f, c.y + 40f, c.x + 140f, c.y)
        }, dark)
    }
    // Dorsal fin, tall and slightly swept back.
    drawPath(Path().apply {
        moveTo(c.x - 20f, c.y - 44f)
        quadraticTo(c.x - 10f, c.y - 110f, c.x + 20f, c.y - 130f)
        quadraticTo(c.x + 10f, c.y - 80f, c.x + 40f, c.y - 40f)
        close()
    }, dark)
    val body = Path().apply {
        moveTo(c.x - 170f, c.y + 6f)
        quadraticTo(c.x - 165f, c.y - 50f, c.x - 60f, c.y - 50f)
        quadraticTo(c.x + 80f, c.y - 44f, c.x + 150f, c.y - 6f)
        lineTo(c.x + 150f, c.y + 6f)
        quadraticTo(c.x + 60f, c.y + 46f, c.x - 60f, c.y + 46f)
        quadraticTo(c.x - 160f, c.y + 42f, c.x - 170f, c.y + 6f)
    }
    drawPath(body, dark)
    clipPath(body) {
        // White belly, sweeping up behind.
        drawPath(Path().apply {
            moveTo(c.x - 175f, c.y + 14f)
            quadraticTo(c.x - 60f, c.y + 10f, c.x + 10f, c.y + 22f)
            quadraticTo(c.x + 50f, c.y + 6f, c.x + 80f, c.y - 6f)
            lineTo(c.x + 70f, c.y + 60f)
            lineTo(c.x - 175f, c.y + 60f)
            close()
        }, white)
        // Grey saddle behind the fin.
        drawOval(lerp(dark, white, 0.35f), Offset(c.x + 10f, c.y - 52f), Size(70f, 22f))
    }
    // Eye patch and flipper.
    drawOval(white, Offset(c.x - 135f, c.y - 30f), Size(48f, 18f))
    rotate(25f + beat * 6f, pivot = Offset(c.x - 80f, c.y + 30f)) {
        drawOval(dark, Offset(c.x - 95f, c.y + 22f), Size(60f, 22f))
    }
    if (jaw > 0.05f) {
        // The mouth gapes from the snout back to the corner, teeth top and bottom.
        val gape = 34f * jaw
        val corner = Offset(c.x - 95f, c.y + 6f)
        val top = c.y + 2f
        val bottom = top + gape
        drawPath(Path().apply {
            moveTo(c.x - 176f, top)
            lineTo(corner.x, corner.y)
            lineTo(c.x - 168f, bottom)
            close()
        }, lerp(Color(0xFF5A1620), colour, 0.3f))
        val teeth = lerp(Color.White, colour, 0.25f)
        for (k in 0 until 6) {
            val f = k / 6f
            val x = c.x - 170f + f * 62f
            val upper = top + (corner.y - top) * f
            val lower = bottom + (corner.y - bottom) * f
            drawPath(Path().apply { moveTo(x, upper); lineTo(x + 5f, upper); lineTo(x + 2.5f, upper + 8f * jaw); close() }, teeth)
            drawPath(Path().apply { moveTo(x + 4f, lower); lineTo(x + 9f, lower); lineTo(x + 6.5f, lower - 8f * jaw); close() }, teeth)
        }
    }
}

/** A squid jetting left: the mantle leads, the arms trail and bunch with each pulse. */
private fun DrawScope.drawSquid(c: Offset, colour: Color, clock: Double, light: Float) {
    val pulse = (sin(clock * 2.2).toFloat() + 1) / 2
    val bob = sin(clock * 0.7).toFloat() * 20f
    val at = Offset(c.x, c.y + bob)
    drawPath(Path().apply {
        moveTo(at.x - 150f, at.y)
        quadraticTo(at.x - 60f, at.y - 50f * (0.9f + pulse * 0.2f), at.x + 60f, at.y - 34f)
        lineTo(at.x + 60f, at.y + 34f)
        quadraticTo(at.x - 60f, at.y + 50f * (0.9f + pulse * 0.2f), at.x - 150f, at.y)
    }, colour)
    // Fins at the tip.
    drawPath(Path().apply {
        moveTo(at.x - 150f, at.y)
        lineTo(at.x - 100f, at.y - 46f)
        lineTo(at.x - 80f, at.y)
        lineTo(at.x - 100f, at.y + 46f)
        close()
    }, colour)
    for (k in 0 until 8) {
        val spread = (k - 3.5f) / 3.5f
        val length = (k % 3 + 2) * 45f * (0.8f + 0.4f * pulse)
        val path = Path().apply {
            moveTo(at.x + 60f, at.y + spread * 26f)
            quadraticTo(
                at.x + 60f + length * 0.5f, at.y + spread * (30f + 30f * pulse) + sin(clock * 3 + k).toFloat() * 14f,
                at.x + 60f + length, at.y + spread * (20f + 50f * pulse),
            )
        }
        drawPath(path, colour, style = Stroke(width = 9f - abs(spread) * 3f, cap = StrokeCap.Round))
    }
    drawEye(Offset(at.x + 40f, at.y - 10f), 9f, light)
}

/** An octopus drifting by, arms curling. */
private fun DrawScope.drawOctopus(c: Offset, colour: Color, clock: Double, light: Float) {
    val bob = sin(clock * 0.9).toFloat() * 16f
    val at = Offset(c.x, c.y + bob)
    for (k in 0 until 8) {
        val spread = (k - 3.5f) / 3.5f
        val curl = sin(clock * 1.4 + k * 0.8).toFloat()
        val path = Path().apply {
            moveTo(at.x + spread * 40f, at.y + 30f)
            cubicTo(
                at.x + spread * 80f, at.y + 90f,
                at.x + spread * 110f + curl * 40f, at.y + 140f,
                at.x + spread * 90f + curl * 60f, at.y + 170f + curl * 20f,
            )
        }
        drawPath(path, colour, style = Stroke(width = 16f - abs(spread) * 4f, cap = StrokeCap.Round))
    }
    scale(1f, 1f + sin(clock * 1.2).toFloat() * 0.06f, pivot = at) {
        drawOval(colour, Offset(at.x - 70f, at.y - 90f), Size(140f, 130f))
    }
    drawEye(Offset(at.x - 26f, at.y + 2f), 8f, light)
    drawEye(Offset(at.x + 26f, at.y + 2f), 8f, light)
}

/** A big creature's eye: faint in daylight, glowing in the dark. */
private fun DrawScope.drawEye(at: Offset, r: Float, light: Float) {
    val glow = (1 - light).coerceIn(0f, 1f)
    if (glow > 0.3f) drawCircle(Color(0xFF8CF7E5).copy(alpha = 0.25f * glow), r * 3f, at)
    drawCircle(lerp(Color.White.copy(alpha = 0.3f), Color(0xFFB8FFF3), glow), r, at)
}

