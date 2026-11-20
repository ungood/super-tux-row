package name.onetrue.supertuxrower.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import name.onetrue.supertuxrower.game.CaveGame
import name.onetrue.supertuxrower.model.Band
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** Draws the cave game: water, cave walls, fish, bubbles, Tux and the power gauge. */
@Composable
fun GameScene(game: CaveGame, frame: () -> Long, target: Band?, modifier: Modifier) {
    val measurer = rememberTextMeasurer()
    // Clipped: the backdrop and the bonk shake reach past the edges.
    Canvas(modifier.clipToBounds()) {
        frame() // Read while drawing, so each frame redraws without recomposing.
        val unit = size.height / CaveGame.VIEW_HEIGHT.toFloat()
        val middle = size.height / 2
        fun y(world: Double) = middle - ((world - game.camera) * unit).toFloat()
        val tuxX = size.width * 0.3f
        val pxPerMetre = (size.width - tuxX) / CaveGame.LOOKAHEAD.toFloat()
        fun x(metre: Double) = tuxX + ((metre - game.metres) * pxPerMetre).toFloat()
        val clock = game.clock
        val scroll = (game.metres * pxPerMetre).toFloat()
        val lift = (game.camera * unit).toFloat()

        val light = game.light.toFloat()

        // Back to front: open water, distant ice and creatures, then the cave.
        drawWater(light)
        drawLightRays(scroll, clock, light)
        drawBackdrop(scroll, lift, 0.2f, 420f, Color(0xFF2A6C94).copy(alpha = 0.25f), salt = 60)
        drawBackdrop(scroll, lift, 0.45f, 300f, Color(0xFF184A6E).copy(alpha = 0.45f), salt = 70)
        drawGiants(scroll, clock, light)
        drawSchools(scroll, clock, light)
        drawMarineSnow(scroll, lift, clock, light)

        // A bonk shakes the world, but not the score bar or gauge.
        val sinceBonk = (clock - game.bonkedAt).toFloat()
        val shake = if (sinceBonk < 0.4f) (1 - sinceBonk / 0.4f) * 14f else 0f
        translate(sin(clock * 70).toFloat() * shake, cos(clock * 55).toFloat() * shake * 0.6f) {
            val visible = game.slices.filter { x(it.metre) in -100f..size.width + 100f }
            drawCeiling(visible.map { WallPoint(it.metre.roundToInt(), x(it.metre), y(it.centre + it.half)) }, clock)
            drawFloor(visible.map { WallPoint(it.metre.roundToInt(), x(it.metre), y(it.centre - it.half)) }, clock)

            for (f in game.fish) {
                val at = Offset(x(f.metre), y(f.height))
                if (!f.eaten) {
                    drawFish(at, f.gold, clock, f.metre.roundToInt())
                    continue
                }
                val t = ((clock - f.eatenAt) / 0.8).toFloat()
                if (t >= 1f) continue
                // Eaten at Tux's beak, so the burst rides along with him.
                val burst = Offset(tuxX + 70f, y(f.height))
                drawCatch(burst, f.gold, t)
                val points = measurer.measure(
                    "+${f.points}" + (f.bonus?.let { "   $it" } ?: ""),
                    TextStyle(color = (if (f.gold) Color(0xFFFFD54F) else Color.White).copy(alpha = 1 - t), fontSize = 28.sp, fontWeight = FontWeight.Bold),
                )
                drawText(points, topLeft = Offset(burst.x - points.size.width / 2, burst.y - 50f - 60f * t))
            }
            for (b in game.bubbles) {
                val age = ((clock - b.born) / CaveGame.BUBBLE_LIFE).toFloat()
                val wobble = sin(clock * 6 + b.born * 13).toFloat() * 4f * age
                drawBubble(Offset(x(b.metre) + wobble, y(b.height)), b.size * (1 + age * 0.5f), 1 - age)
            }

            val tilt = (-game.vy * 40).toFloat().coerceIn(-25f, 25f)
            val at = Offset(tuxX, y(game.y + game.bob))
            // A golden fish gives him a burst: a glow and speed lines streaming behind.
            val boost = (1 - (clock - game.boostedAt) / 1.6).toFloat()
            if (boost > 0f) drawBoost(at, boost, clock)
            val dizzy = (1 - sinceBonk / 1.6f).coerceIn(0f, 1f)
            rotate(tilt, pivot = at) { drawTux(at, clock, game.sinceStroke, dizzy) }
            drawDarkness(at, light)
            // A red flash on the bonk.
            if (shake > 0f) drawRect(Color(0xFFFF5252).copy(alpha = 0.18f * shake / 14f), Offset(-40f, -40f), Size(size.width + 80f, size.height + 80f))
        }

        drawPowerGauge(game.gauge.toFloat(), target, top = 24f, bottom = size.height - 24f, clock = clock)
    }
}

private fun DrawScope.drawBoost(at: Offset, strength: Float, clock: Double) {
    val gold = Color(0xFFFFD54F)
    drawCircle(
        Brush.radialGradient(listOf(gold.copy(alpha = 0.45f * strength), Color.Transparent), at, 110f),
        110f, at,
    )
    for (k in 0 until 9) {
        val lane = (k - 4) * 11f
        val run = ((clock * 2.5 + k * 0.37) % 1.0).toFloat()
        val start = at.x - 70f - run * 260f
        val length = 60f + (k % 3) * 30f
        drawLine(
            gold.copy(alpha = strength * (1 - run) * 0.9f),
            Offset(start, at.y + lane), Offset(start - length, at.y + lane), 3f, StrokeCap.Round,
        )
    }
}

/**
 * A slim ladder of the bands, with your power and the target band marked. [position] runs from 0
 * (bottom of rest) to 6 (top of the last band), as [Band.position] gives it. [clock], if given,
 * makes the target band pulse.
 */
internal fun DrawScope.drawPowerGauge(
    position: Float, target: Band?, top: Float, bottom: Float, left: Float = 24f, width: Float = 22f, clock: Double = 0.0,
) {
    val bandHeight = (bottom - top) / Band.entries.size
    val markerY = (bottom - position * bandHeight).coerceIn(top, bottom)
    val pad = 6f
    // A frosted frame to sit the ladder in.
    drawRoundRect(Deep.copy(alpha = 0.55f), Offset(left - pad, top - pad), Size(width + pad * 2, bottom - top + pad * 2), CornerRadius(12f))
    drawRoundRect(Color.White.copy(alpha = 0.35f), Offset(left - pad, top - pad), Size(width + pad * 2, bottom - top + pad * 2), CornerRadius(12f), style = Stroke(width = 2f))

    val pulse = (0.5f + 0.5f * sin(clock * 4).toFloat())
    Band.entries.forEach { band ->
        val bandTop = bottom - (band.ordinal + 1) * bandHeight
        val lit = band == target
        val colour = band.color()
        val segment = Offset(left, bandTop + 2f)
        val segmentSize = Size(width, bandHeight - 4f)
        if (lit) drawRoundRect(colour.copy(alpha = 0.25f + 0.2f * pulse), Offset(left - 5f, bandTop - 3f), Size(width + 10f, bandHeight + 6f), CornerRadius(10f))
        drawRoundRect(
            Brush.horizontalGradient(listOf(colour.copy(alpha = if (lit) 1f else 0.35f), colour.copy(alpha = if (lit) 0.75f else 0.2f)), startX = left, endX = left + width),
            segment, segmentSize, CornerRadius(6f),
        )
        // Your power fills the ladder from the bottom, brighter than the band behind it.
        val filled = (bandTop + bandHeight - 2f - markerY).coerceIn(0f, segmentSize.height)
        if (filled > 0f) {
            drawRoundRect(colour.copy(alpha = 0.9f), Offset(left + 4f, segment.y + segmentSize.height - filled), Size(width - 8f, filled), CornerRadius(4f))
        }
        if (lit) drawRoundRect(Color.White, segment, segmentSize, CornerRadius(6f), style = Stroke(width = 2.5f))
    }

    // The marker: a line across the ladder and a glowing pointer.
    val here = Band.entries[position.toInt().coerceIn(0, Band.entries.lastIndex)].color()
    drawLine(Color.White, Offset(left - 4f, markerY), Offset(left + width + 4f, markerY), 3f)
    drawCircle(here.copy(alpha = 0.35f), 18f, Offset(left + width + 14f, markerY))
    drawPath(
        Path().apply {
            moveTo(left + width + 4f, markerY)
            lineTo(left + width + 24f, markerY - 12f)
            lineTo(left + width + 24f, markerY + 12f)
            close()
        },
        Color.White,
    )
}
