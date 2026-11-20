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
import name.onetrue.supertuxrower.game.Sightings
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/** Sends a creature across the screen now, for checking how it looks (`just do giant whale`, `just do show urchin`). */
object Summon {
    /** A big creature to send past once; the scene hands it to the game's [Sightings]. */
    var kind: String? = null
    /** A smaller creature to show off: it fills the spots where life of its sort would go. */
    var only: String? = null
    /** Daylight to draw with instead of the game's, to see the deep without rowing there. */
    var light: Float? = null
}

/** How each giant [Sightings] knows is drawn, by name. */
private val GiantArt: Map<String, DrawScope.(Offset, Color, Double, Float) -> Unit> = mapOf(
    "whale" to { c, colour, clock, _ -> drawWhale(c, colour, clock) },
    "orca" to { c, colour, clock, _ -> drawPass(c) { at, jaw -> drawOrca(at, colour, clock, jaw) } },
    "leopardseal" to { c, colour, clock, _ -> drawPass(c) { at, jaw -> drawLeopardSeal(at, colour, clock, jaw) } },
    "belugas" to { c, colour, clock, _ -> drawBelugas(c, colour, clock) },
    "narwhal" to { c, colour, clock, _ -> drawNarwhal(c, colour, clock) },
    "manta" to { c, colour, clock, _ -> drawManta(c, colour, clock) },
    "whaleshark" to { c, colour, clock, _ -> drawWhaleShark(c, colour, clock) },
    "squid" to { c, colour, clock, light -> drawSquid(c, colour, clock, light) },
    "octopus" to { c, colour, clock, light -> drawOctopus(c, colour, clock, light) },
    "anglerfish" to { c, colour, clock, light -> drawAnglerfish(c, colour, clock, light) },
    "spermwhale" to { c, colour, clock, light -> drawSpermWhaleAndSquid(c, colour, clock, light) },
)

/** The names `just do giant` accepts. */
internal val GiantNames get() = Sightings.KINDS.map { it.name }

/**
 * The giant passing far behind the cave now, if any ([Sightings] decides which and when). Hunters
 * swoop across fast; the others swim slowly by.
 */
internal fun DrawScope.drawGiant(sighting: Sightings.Sighting?, life: Life) {
    val s = sighting ?: return
    // Solid, so their parts don't show through each other; darker than the water and tinted
    // towards indigo, so they stand apart from the bluer life in the middle distance.
    val silhouette = lerp(lerp(Color(0xFF040C17), Color(0xFF143A58), life.light), Color(0xFF1C1A48), 0.35f)
    val scale = size.width / Sightings.SCREEN.toFloat()
    val x = size.width + (Sightings.MARGIN * scale).toFloat() - (s.age * s.kind.speed * scale).toFloat()
    // The camera keeps the cave mid-screen, so they swim there too.
    val at = Offset(x, size.height * (0.38f + s.height.toFloat() * 0.24f))
    if (x in -200f..size.width + 200f) s.kind.cue?.let { life.cue(it, s.id) }
    val art = GiantArt[s.kind.name] ?: return
    scale(GIANT_SCALE, pivot = at) { art(this, at, silhouette, life.clock, life.light) }
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

/**
 * A hunter at [c] as it crosses the screen: it swoops down and up, banking along its path, and as
 * it passes Tux it opens its jaws ([body] gets how wide, 0 to 1).
 */
private fun DrawScope.drawPass(c: Offset, body: DrawScope.(Offset, Float) -> Unit) {
    val wave = 260f
    val dip = 90f
    val at = Offset(c.x, c.y + sin(c.x / wave) * dip)
    // Pointing along its path: it swims left, so a rising path to the right means nose down.
    val slope = cos(c.x / wave) * dip / wave
    val bank = (kotlin.math.atan(slope) * 180 / PI).toFloat()
    val tux = size.width * 0.3f
    val jaw = (1 - abs(c.x - tux) / 380f).coerceIn(0f, 1f)
    rotate(bank, pivot = at) { body(at, jaw) }
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


/**
 * A leopard seal on the hunt: long and sinuous, with a big reptilian head, long fore-flippers and a
 * spotted throat. [jaw] from 0 to 1 opens its mouth.
 */
private fun DrawScope.drawLeopardSeal(c: Offset, colour: Color, clock: Double, jaw: Float) {
    val beat = sin(clock * 5).toFloat()
    val belly = lerp(colour, Color.White, 0.22f)
    // Hind flippers, beating side to side like a fish's tail.
    rotate(beat * 18f, pivot = Offset(c.x + 170f, c.y)) {
        for (side in listOf(-1f, 1f)) drawPath(Path().apply {
            moveTo(c.x + 165f, c.y)
            quadraticTo(c.x + 210f, c.y + side * 16f, c.x + 240f, c.y + side * 30f)
            quadraticTo(c.x + 220f, c.y + side * 6f, c.x + 165f, c.y + side * 2f)
        }, colour)
    }
    val body = Path().apply {
        moveTo(c.x - 200f, c.y)
        quadraticTo(c.x - 200f, c.y - 32f, c.x - 150f, c.y - 34f)
        quadraticTo(c.x - 120f, c.y - 30f, c.x - 100f, c.y - 36f)
        quadraticTo(c.x + 20f, c.y - 50f, c.x + 170f, c.y - 6f)
        lineTo(c.x + 170f, c.y + 6f)
        quadraticTo(c.x + 20f, c.y + 46f, c.x - 100f, c.y + 30f)
        quadraticTo(c.x - 130f, c.y + 26f, c.x - 160f, c.y + 24f)
        quadraticTo(c.x - 200f, c.y + 20f, c.x - 200f, c.y)
    }
    drawPath(body, colour)
    clipPath(body) {
        drawOval(belly, Offset(c.x - 210f, c.y + 6f), Size(340f, 60f))
        // Its spots.
        for (k in 0 until 14) drawCircle(lerp(colour, Color.Black, 0.3f), 4f + (k % 3) * 2f, Offset(c.x - 170f + k * 22f, c.y + 14f + sin(k * 2.3f) * 10f))
    }
    // Long fore-flipper, sweeping.
    rotate(30f + beat * 10f, pivot = Offset(c.x - 70f, c.y + 22f)) {
        drawOval(colour, Offset(c.x - 80f, c.y + 14f), Size(110f, 24f))
    }
    drawCircle(Color.White.copy(alpha = 0.25f), 5f, Offset(c.x - 160f, c.y - 16f))
    if (jaw > 0.05f) {
        val gape = 30f * jaw
        val corner = Offset(c.x - 120f, c.y + 4f)
        drawPath(Path().apply {
            moveTo(c.x - 204f, c.y)
            lineTo(corner.x, corner.y)
            lineTo(c.x - 196f, c.y + gape)
            close()
        }, lerp(Color(0xFF5A1620), colour, 0.3f))
        val teeth = lerp(Color.White, colour, 0.25f)
        for (k in 0 until 5) {
            val x = c.x - 196f + k * 14f
            drawPath(Path().apply { moveTo(x, c.y + 1f); lineTo(x + 6f, c.y + 1f); lineTo(x + 3f, c.y + 1f + 10f * jaw); close() }, teeth)
        }
    }
}

/** A pod of three belugas: white, round-headed, swimming along together. */
private fun DrawScope.drawBelugas(c: Offset, colour: Color, clock: Double) {
    val white = lerp(colour, Color(0xFFE8F4F8), 0.45f)
    for (k in 0 until 3) {
        val at = Offset(c.x + k * 230f - 230f, c.y + (k - 1) * 70f + sin(clock * 0.8 + k * 2).toFloat() * 14f)
        val s = 0.8f + (k % 2) * 0.2f
        scale(s, pivot = at) { drawBeluga(at, white, colour, clock + k) }
    }
}

private fun DrawScope.drawBeluga(c: Offset, white: Color, colour: Color, clock: Double) {
    val beat = sin(clock * 2).toFloat()
    rotate(beat * 14f, pivot = Offset(c.x + 110f, c.y)) {
        drawPath(Path().apply {
            moveTo(c.x + 100f, c.y)
            quadraticTo(c.x + 140f, c.y - 30f, c.x + 160f, c.y - 28f)
            quadraticTo(c.x + 145f, c.y, c.x + 160f, c.y + 28f)
            quadraticTo(c.x + 140f, c.y + 30f, c.x + 100f, c.y)
        }, white)
    }
    drawPath(Path().apply {
        // The melon: a big rounded forehead over a short beak.
        moveTo(c.x - 130f, c.y + 8f)
        quadraticTo(c.x - 136f, c.y - 10f, c.x - 118f, c.y - 14f)
        quadraticTo(c.x - 110f, c.y - 48f, c.x - 60f, c.y - 44f)
        quadraticTo(c.x + 40f, c.y - 40f, c.x + 110f, c.y - 5f)
        lineTo(c.x + 110f, c.y + 5f)
        quadraticTo(c.x + 20f, c.y + 42f, c.x - 70f, c.y + 34f)
        quadraticTo(c.x - 120f, c.y + 30f, c.x - 130f, c.y + 8f)
    }, white)
    rotate(25f + beat * 8f, pivot = Offset(c.x - 60f, c.y + 24f)) {
        drawOval(white, Offset(c.x - 70f, c.y + 18f), Size(50f, 18f))
    }
    drawCircle(colour.copy(alpha = 0.7f), 4f, Offset(c.x - 98f, c.y - 6f))
    drawLine(colour.copy(alpha = 0.5f), Offset(c.x - 130f, c.y + 6f), Offset(c.x - 110f, c.y + 10f), 2f)
}

/** A narwhal: a mottled grey whale with a long spiral tusk. */
private fun DrawScope.drawNarwhal(c: Offset, colour: Color, clock: Double) {
    val beat = sin(clock * 1.6).toFloat()
    val mottle = lerp(colour, Color.White, 0.15f)
    drawLine(lerp(colour, Color.White, 0.3f), Offset(c.x - 160f, c.y - 6f), Offset(c.x - 360f, c.y - 22f), 7f, StrokeCap.Round)
    for (k in 0 until 10) {
        val x = c.x - 170f - k * 19f
        drawLine(colour, Offset(x, c.y - 10f - k * 1.5f), Offset(x - 8f, c.y - 2f - k * 1.5f), 2f)
    }
    rotate(beat * 12f, pivot = Offset(c.x + 150f, c.y)) {
        drawPath(Path().apply {
            moveTo(c.x + 140f, c.y)
            quadraticTo(c.x + 190f, c.y - 44f, c.x + 215f, c.y - 40f)
            quadraticTo(c.x + 195f, c.y, c.x + 215f, c.y + 40f)
            quadraticTo(c.x + 190f, c.y + 44f, c.x + 140f, c.y)
        }, colour)
    }
    val body = Path().apply {
        moveTo(c.x - 170f, c.y)
        quadraticTo(c.x - 170f, c.y - 46f, c.x - 90f, c.y - 50f)
        quadraticTo(c.x + 60f, c.y - 46f, c.x + 150f, c.y - 6f)
        lineTo(c.x + 150f, c.y + 6f)
        quadraticTo(c.x + 40f, c.y + 46f, c.x - 90f, c.y + 42f)
        quadraticTo(c.x - 165f, c.y + 36f, c.x - 170f, c.y)
    }
    drawPath(body, colour)
    clipPath(body) {
        for (k in 0 until 22) drawCircle(mottle, 5f + (k % 4) * 2f, Offset(c.x - 140f + hash(k, 520) * 280f, c.y - 44f + hash(k, 521) * 50f))
    }
    rotate(25f + beat * 6f, pivot = Offset(c.x - 90f, c.y + 26f)) {
        drawOval(colour, Offset(c.x - 100f, c.y + 18f), Size(56f, 20f))
    }
    drawCircle(Color.White.copy(alpha = 0.25f), 5f, Offset(c.x - 130f, c.y - 10f))
}

/**
 * A manta ray gliding, seen side-on from a little above: its wings, foreshortened, beat slowly up
 * and down, the far one behind its body and the near one in front.
 */
private fun DrawScope.drawManta(c: Offset, colour: Color, clock: Double) {
    val flap = sin(clock * 1.2).toFloat()
    val near = lerp(colour, Color.White, 0.1f)
    drawLine(colour, Offset(c.x + 60f, c.y), Offset(c.x + 280f, c.y + 6f + flap * 8f), 5f, StrokeCap.Round)
    fun wing(side: Float, shade: Color) {
        val tip = Offset(c.x + 70f, c.y + side * 70f - flap * 80f)
        drawPath(Path().apply {
            moveTo(c.x - 80f, c.y)
            quadraticTo(c.x - 30f, c.y + side * 50f - flap * 40f, tip.x, tip.y)
            quadraticTo(c.x + 40f, c.y + side * 20f - flap * 20f, c.x + 90f, c.y)
            close()
        }, shade)
    }
    wing(-1f, colour)
    drawOval(colour, Offset(c.x - 100f, c.y - 24f), Size(200f, 48f))
    // Cephalic fins, curled forward either side of its mouth.
    for (side in listOf(-1f, 1f)) drawPath(Path().apply {
        moveTo(c.x - 90f, c.y + side * 12f)
        quadraticTo(c.x - 135f, c.y + side * 18f, c.x - 128f, c.y + side * 2f)
    }, colour, style = Stroke(width = 11f, cap = StrokeCap.Round))
    wing(1f, near)
    drawCircle(Color.White.copy(alpha = 0.2f), 4f, Offset(c.x - 78f, c.y - 12f))
}

/** A whale shark: huge and slow, with a broad flat head, pale spots and a tall tail fin. */
private fun DrawScope.drawWhaleShark(c: Offset, colour: Color, clock: Double) {
    val beat = sin(clock * 0.9).toFloat()
    val spot = lerp(colour, Color.White, 0.3f)
    rotate(beat * 9f, pivot = Offset(c.x + 260f, c.y)) {
        drawPath(Path().apply {
            moveTo(c.x + 240f, c.y - 8f)
            quadraticTo(c.x + 300f, c.y - 80f, c.x + 340f, c.y - 130f)
            quadraticTo(c.x + 330f, c.y - 40f, c.x + 300f, c.y + 2f)
            quadraticTo(c.x + 320f, c.y + 40f, c.x + 330f, c.y + 70f)
            quadraticTo(c.x + 280f, c.y + 30f, c.x + 240f, c.y + 8f)
            close()
        }, colour)
    }
    drawPath(Path().apply {
        moveTo(c.x - 10f, c.y - 56f)
        quadraticTo(c.x + 20f, c.y - 120f, c.x + 50f, c.y - 120f)
        quadraticTo(c.x + 50f, c.y - 80f, c.x + 80f, c.y - 50f)
        close()
    }, colour)
    val body = Path().apply {
        moveTo(c.x - 300f, c.y + 4f)
        quadraticTo(c.x - 300f, c.y - 50f, c.x - 200f, c.y - 60f)
        quadraticTo(c.x + 50f, c.y - 64f, c.x + 250f, c.y - 8f)
        lineTo(c.x + 250f, c.y + 8f)
        quadraticTo(c.x + 50f, c.y + 60f, c.x - 200f, c.y + 54f)
        quadraticTo(c.x - 300f, c.y + 50f, c.x - 300f, c.y + 4f)
    }
    drawPath(body, colour)
    clipPath(body) {
        for (k in 0 until 60) {
            val f = hash(k, 530)
            drawCircle(spot, 3f + hash(k, 532) * 3f, Offset(c.x - 260f + f * 500f, c.y - 56f + hash(k, 531) * 70f))
        }
        for (k in 0 until 3) drawLine(spot, Offset(c.x - 200f + k * 140f, c.y - 58f), Offset(c.x - 180f + k * 140f, c.y + 10f), 3f)
    }
    // A wide, flat mouth at the very front.
    drawLine(lerp(colour, Color.Black, 0.5f), Offset(c.x - 300f, c.y + 8f), Offset(c.x - 250f, c.y + 14f), 5f, StrokeCap.Round)
    rotate(30f + beat * 6f, pivot = Offset(c.x - 160f, c.y + 40f)) {
        drawOval(colour, Offset(c.x - 170f, c.y + 30f), Size(120f, 30f))
    }
    drawCircle(Color.White.copy(alpha = 0.25f), 4f, Offset(c.x - 245f, c.y - 10f))
}

/**
 * An anglerfish in the deep, swimming left: a round body that is mostly head, an underbite jaw
 * jutting forward with needle teeth along both lips, and a glowing lure dangling on a rod in
 * front, brighter the darker it is.
 */
private fun DrawScope.drawAnglerfish(c: Offset, colour: Color, clock: Double, light: Float) {
    val bob = sin(clock * 0.8).toFloat() * 12f
    val at = Offset(c.x, c.y + bob)
    val beat = sin(clock * 3).toFloat()
    rotate(beat * 12f, pivot = Offset(at.x + 90f, at.y)) {
        drawPath(Path().apply {
            moveTo(at.x + 80f, at.y)
            lineTo(at.x + 150f, at.y - 40f)
            quadraticTo(at.x + 135f, at.y, at.x + 150f, at.y + 40f)
            close()
        }, colour)
    }
    // Its mouth works slowly open and shut.
    val gape = 6f + (sin(clock * 0.9).toFloat() + 1) * 10f
    val upperLip = Offset(at.x - 104f, at.y - 6f)
    val corner = Offset(at.x - 34f, at.y + 10f)
    val lowerLip = Offset(at.x - 118f, at.y + 14f + gape)
    // The body, and the jaw below the mouth as part of it.
    drawOval(colour, Offset(at.x - 110f, at.y - 75f), Size(200f, 140f))
    drawPath(Path().apply {
        moveTo(lowerLip.x, lowerLip.y)
        lineTo(corner.x, corner.y)
        lineTo(at.x + 10f, at.y + 50f)
        quadraticTo(at.x - 70f, at.y + 66f, lowerLip.x + 6f, lowerLip.y + 14f)
        close()
    }, colour)
    // Inside the mouth, between the lips.
    drawPath(Path().apply {
        moveTo(upperLip.x, upperLip.y)
        lineTo(corner.x, corner.y)
        lineTo(lowerLip.x, lowerLip.y)
        close()
    }, lerp(Color.Black, colour, 0.3f))
    // Teeth along each lip, pointing into the mouth, longest at the front.
    val teeth = lerp(Color.White, colour, 0.35f)
    for (k in 0 until 7) {
        val f = 0.06f + k * 0.11f
        val length = 14f * (1 - f) + 3f
        val up = Offset(upperLip.x + (corner.x - upperLip.x) * f, upperLip.y + (corner.y - upperLip.y) * f)
        drawPath(Path().apply { moveTo(up.x - 2.5f, up.y); lineTo(up.x + 2.5f, up.y); lineTo(up.x, up.y + length); close() }, teeth)
        val low = Offset(lowerLip.x + (corner.x - lowerLip.x) * f, lowerLip.y + (corner.y - lowerLip.y) * f)
        drawPath(Path().apply { moveTo(low.x - 2.5f, low.y); lineTo(low.x + 2.5f, low.y); lineTo(low.x, low.y - length); close() }, teeth)
    }
    // The lure on its rod, out in front of its face.
    val sway = sin(clock * 1.3).toFloat() * 14f
    val lure = Offset(at.x - 150f + sway, at.y - 70f)
    drawPath(Path().apply {
        moveTo(at.x - 50f, at.y - 66f)
        quadraticTo(at.x - 110f, at.y - 150f, lure.x, lure.y)
    }, colour, style = Stroke(width = 5f, cap = StrokeCap.Round))
    val glow = 0.5f + 0.5f * (1 - light)
    val flicker = 0.8f + 0.2f * sin(clock * 5).toFloat()
    drawCircle(Color(0xFF9CFFF0).copy(alpha = 0.18f * glow * flicker), 60f, lure)
    drawCircle(Color(0xFF9CFFF0).copy(alpha = 0.35f * glow * flicker), 26f, lure)
    drawCircle(lerp(Color(0xFF6FBFB4), Color(0xFFE8FFFB), glow), 10f, lure)
    drawEye(Offset(at.x - 56f, at.y - 30f), 8f, light)
}

/**
 * A sperm whale wrestling a giant squid: the whale's great blunt head, the squid's arms wrapped
 * round it and its mantle trailing below. They roll slowly as they struggle.
 */
private fun DrawScope.drawSpermWhaleAndSquid(c: Offset, colour: Color, clock: Double, light: Float) {
    val roll = sin(clock * 0.5).toFloat() * 8f
    val squid = lerp(colour, Color(0xFF6A2A30), 0.35f)
    rotate(roll, pivot = c) {
        val beat = sin(clock * 0.9).toFloat()
        // The squid's mantle and fins, trailing back below the whale.
        drawPath(Path().apply {
            moveTo(c.x - 160f, c.y + 60f)
            quadraticTo(c.x + 40f, c.y + 150f, c.x + 200f, c.y + 170f + beat * 10f)
            quadraticTo(c.x + 40f, c.y + 100f, c.x - 120f, c.y + 40f)
            close()
        }, squid)
        rotate(beat * 10f, pivot = Offset(c.x + 410f, c.y)) {
            drawPath(Path().apply {
                moveTo(c.x + 390f, c.y)
                quadraticTo(c.x + 450f, c.y - 70f, c.x + 490f, c.y - 60f)
                quadraticTo(c.x + 460f, c.y, c.x + 490f, c.y + 60f)
                quadraticTo(c.x + 450f, c.y + 70f, c.x + 390f, c.y)
            }, colour)
        }
        // The whale: a long box of a head, then tapering away.
        drawPath(Path().apply {
            moveTo(c.x - 330f, c.y - 70f)
            quadraticTo(c.x - 336f, c.y - 90f, c.x - 300f, c.y - 92f)
            lineTo(c.x - 40f, c.y - 90f)
            quadraticTo(c.x + 200f, c.y - 70f, c.x + 400f, c.y - 8f)
            lineTo(c.x + 400f, c.y + 8f)
            quadraticTo(c.x + 160f, c.y + 64f, c.x - 60f, c.y + 60f)
            lineTo(c.x - 250f, c.y + 50f)
            quadraticTo(c.x - 330f, c.y + 46f, c.x - 330f, c.y - 70f)
        }, colour)
        // Its narrow lower jaw.
        drawLine(lerp(colour, Color.White, 0.15f), Offset(c.x - 300f, c.y + 46f), Offset(c.x - 80f, c.y + 52f), 10f, StrokeCap.Round)
        drawCircle(Color.White.copy(alpha = 0.25f), 6f, Offset(c.x - 130f, c.y + 10f))
        // The squid's arms, wrapped over the head and writhing.
        for (k in 0 until 6) {
            val w = sin(clock * 1.5 + k).toFloat()
            drawPath(Path().apply {
                moveTo(c.x - 140f + k * 12f, c.y + 60f)
                cubicTo(
                    c.x - 200f + k * 30f, c.y - 40f + w * 20f,
                    c.x - 260f + k * 20f + w * 30f, c.y - 120f,
                    c.x - 300f + k * 40f + w * 30f, c.y - 60f + k * 14f,
                )
            }, squid, style = Stroke(width = 10f - k, cap = StrokeCap.Round))
        }
        drawEye(Offset(c.x - 110f, c.y + 80f), 12f, light)
    }
}
