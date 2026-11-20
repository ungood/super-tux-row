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
import name.onetrue.supertuxrower.game.Cue
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

/**
 * What the cave's life needs to know each frame: the time, the daylight, where Tux is on screen
 * (some creatures react as he passes) and where to send the sounds they make. [cue]'s key names
 * the creature, so a sound plays once per visit rather than every frame.
 */
internal class Life(val clock: Double, val light: Float, val tux: Offset, val cue: (Cue, Int) -> Unit = { _, _ -> })

/** How close Tux is to [x] on screen, 1 right beside it and 0 from [reach] away. */
internal fun Life.near(x: Float, reach: Float = 260f) = (1 - abs(x - tux.x) / reach).coerceIn(0f, 1f)

/** A spot on the floor for something to live: [at] on the rim, the floor's [slope] in degrees. */
internal class FloorSpot(val at: Offset, val seed: Int, val slope: Float)

/** A kind of floor life, its share of the floor's spots, and the daylight it lives in. */
private class FloorKind(
    val name: String,
    val share: Float,
    val minLight: Float = 0f,
    val maxLight: Float = 1f,
    val draw: DrawScope.(FloorSpot, Life) -> Unit,
)

/** Things that crawl lie along the floor; things that grow stand upright. */
private fun crawling(draw: DrawScope.(FloorSpot, Life) -> Unit): DrawScope.(FloorSpot, Life) -> Unit = { s, l -> rotate(s.slope, pivot = s.at) { draw(s, l) } }

/**
 * In order from r = 0.25 (below that are boulders). A spot whose kind doesn't live at its depth is
 * bare, as are spots past the last, so about two thirds of the floor is.
 */
private val FloorKinds = listOf(
    FloorKind("anemone", 0.045f) { s, l -> drawAnemone(s.at, s.seed, l.clock) },
    FloorKind("sponge", 0.03f) { s, l -> drawSponge(s.at, s.seed, l.clock) },
    FloorKind("tubeworms", 0.025f, maxLight = 0.7f) { s, l -> drawTubeWorms(s.at, s.seed, l.clock) },
    FloorKind("crab", 0.02f, minLight = 0.3f, draw = crawling { s, l -> drawCrab(s.at, s.seed, l.clock) }),
    FloorKind("starfish", 0.015f, draw = crawling { s, _ -> drawStarfish(Offset(s.at.x, s.at.y + 2f), 12f + hash(s.seed, 46) * 6f, hash(s.seed, 45) * 72f, s.seed) }),
    FloorKind("urchin", 0.02f, minLight = 0.35f) { s, l -> drawUrchins(s.at, s.seed, l.clock) },
    FloorKind("coral", 0.02f, minLight = 0.4f) { s, l -> drawSeaFan(s.at, s.seed, l.clock) },
    FloorKind("brittlestar", 0.015f, maxLight = 0.7f, draw = crawling { s, l -> drawBrittleStar(s.at, s.seed, l.clock) }),
    FloorKind("featherduster", 0.015f, minLight = 0.3f) { s, l -> drawFeatherDusters(s.at, s.seed, l) },
    FloorKind("hermitcrab", 0.012f, minLight = 0.45f, draw = crawling { s, l -> drawHermitCrab(s.at, s.seed, l.clock) }),
    FloorKind("seacucumber", 0.012f, maxLight = 0.8f, draw = crawling { s, l -> drawSeaCucumber(s.at, s.seed, l.clock) }),
    FloorKind("nudibranch", 0.012f, minLight = 0.4f, draw = crawling { s, l -> drawNudibranch(s.at, s.seed, l.clock) }),
    FloorKind("clam", 0.008f, minLight = 0.5f, draw = crawling { s, l -> drawClam(s.at, s.seed, l) }),
    FloorKind("flatfish", 0.01f, minLight = 0.3f, draw = crawling { s, l -> drawFlatfish(s.at, s.seed, l) }),
    FloorKind("lobster", 0.01f, minLight = 0.3f, draw = crawling { s, l -> drawLobster(s.at, s.seed, l.clock) }),
    FloorKind("seaspider", 0.012f, maxLight = 0.6f) { s, l -> drawSeaSpider(s.at, s.seed, l.clock) },
    FloorKind("eel", 0.008f, minLight = 0.25f) { s, l -> drawEel(s.at, s.seed, l) },
    FloorKind("isopod", 0.015f, maxLight = 0.35f, draw = crawling { s, l -> drawIsopod(s.at, s.seed, l.clock) }),
    FloorKind("anchor", 0.003f, draw = crawling { s, _ -> drawAnchor(s.at, s.seed) }),
)

/** The names `just do show` accepts for floor life. */
internal val FloorKindNames get() = FloorKinds.map { it.name }

/**
 * Something living on the floor at [p], chosen by [r] (a hash from 0.3 up; some are bare). Things
 * that grow stand upright; things that crawl lie along the floor's [slope], in degrees.
 */
internal fun DrawScope.drawFloorLife(p: WallPoint, r: Float, life: Life, slope: Float) {
    val seed = p.index
    val spot = FloorSpot(Offset(p.x + (hash(seed, 100) - 0.5f) * 24f, p.y + 4f), seed, slope)
    // Showing one kind off: it takes every few spots, wherever it would otherwise live.
    Summon.only?.let { only ->
        FloorKinds.firstOrNull { it.name == only }?.let { if (r < 0.35f) it.draw(this, spot, life); return }
    }
    var from = 0.25f
    for (kind in FloorKinds) {
        from += kind.share
        if (r >= from) continue
        if (p.light in kind.minLight..kind.maxLight) kind.draw(this, spot, life)
        return
    }
}

/** A hue from [hues], nudged a little either way, so no two look quite the same. */
internal fun pickHue(seed: Int, salt: Int, hues: List<Float>, spread: Float = 12f): Float {
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

/** A cluster of tube sponges, open at the top, slowly breathing out wisps of water. */
private fun DrawScope.drawSponge(base: Offset, seed: Int, clock: Double) {
    // Mostly ochres and oranges, sometimes purple or red.
    val hue = pickHue(seed, 110, listOf(20f, 35f, 48f, 48f, 285f, 355f), spread = 8f)
    val tubes = 2 + (hash(seed, 111) * 3).toInt()
    for (k in 0 until tubes) {
        val colour = Color.hsv(hue, 0.55f + hash(seed, 125 + k) * 0.25f, 0.6f + hash(seed, 128 + k) * 0.3f)
        val w = 14f + hash(seed, 112 + k) * 8f
        val h = (26f + hash(seed, 116 + k) * 40f) * (1 + 0.05f * sin(clock * 0.8 + k * 1.3).toFloat())
        val x = base.x + (k - (tubes - 1) / 2f) * 15f + sin(clock * 0.6 + k + seed).toFloat() * 2f
        // Specks drifting up out of the top as it pumps water through.
        repeat(3) { j ->
            val rise = ((clock * 0.4 + j / 3.0 + hash(seed, 140 + k) ) % 1.0).toFloat()
            drawCircle(Color.White.copy(alpha = 0.35f * (1 - rise)), 1.8f, Offset(x + sin(clock * 2 + j).toFloat() * 4f, base.y - h - rise * 40f))
        }
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
