package name.onetrue.supertuxrower.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
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

object HiddenLayers { var set = emptySet<String>() }
private fun shown(layer: String) = layer !in HiddenLayers.set

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

        // Water gets darker with depth.
        if (shown("water")) drawWater(((-game.camera / 10).toFloat() + 1) / 2)
        if (shown("rays")) drawLightRays(scroll, clock)
        if (shown("backdrop")) drawBackdrop(scroll, lift, 0.2f, 420f, Color(0xFF2A6C94).copy(alpha = 0.25f), salt = 60)
        if (shown("backdrop")) drawBackdrop(scroll, lift, 0.45f, 300f, Color(0xFF184A6E).copy(alpha = 0.45f), salt = 70)
        if (shown("snow")) drawMarineSnow(scroll, lift, clock)

        // A bonk shakes the world, but not the score bar or gauge.
        val sinceBonk = (clock - game.bonkedAt).toFloat()
        val shake = if (sinceBonk < 0.4f) (1 - sinceBonk / 0.4f) * 14f else 0f
        translate(sin(clock * 70).toFloat() * shake, cos(clock * 55).toFloat() * shake * 0.6f) {
            val visible = game.slices.filter { x(it.metre) in -100f..size.width + 100f }
            if (shown("ceiling")) drawCeiling(visible.map { WallPoint(it.metre.roundToInt(), x(it.metre), y(it.centre + it.half)) }, clock)
            if (shown("floor")) drawFloor(visible.map { WallPoint(it.metre.roundToInt(), x(it.metre), y(it.centre - it.half)) }, clock)

            if (shown("fish")) for (f in game.fish) {
                val at = Offset(x(f.metre), y(f.height))
                if (!f.eaten) {
                    drawFish(at, f.gold, clock, f.metre.roundToInt())
                    continue
                }
                val t = ((clock - f.eatenAt) / 0.8).toFloat()
                if (t >= 1f) continue
                // Eaten where Tux was, so the burst rides along with him.
                val burst = Offset(tuxX + 50f, y(f.height))
                drawCatch(burst, f.gold, t)
                val points = measurer.measure(
                    "+${f.points}",
                    TextStyle(color = (if (f.gold) Color(0xFFFFD54F) else Color.White).copy(alpha = 1 - t), fontSize = 28.sp, fontWeight = FontWeight.Bold),
                )
                drawText(points, topLeft = Offset(burst.x - points.size.width / 2, burst.y - 50f - 60f * t))
            }
            if (shown("bubbles")) for (b in game.bubbles) {
                val age = ((clock - b.born) / 1.5).toFloat()
                val wobble = sin(clock * 6 + b.born * 13).toFloat() * 4f * age
                drawBubble(Offset(x(b.metre) + wobble, y(b.height)), b.size * (1 + age * 0.5f), 1 - age)
            }

            val tilt = (-game.vy * 40).toFloat().coerceIn(-25f, 25f)
            val at = Offset(tuxX, y(game.y + game.bob))
            val dizzy = (1 - sinceBonk / 1.6f).coerceIn(0f, 1f)
            rotate(tilt, pivot = at) { drawTux(at, clock, game.sinceStroke, dizzy) }
            // A red flash on the bonk.
            if (shake > 0f) drawRect(Color(0xFFFF5252).copy(alpha = 0.18f * shake / 14f), Offset(-40f, -40f), Size(size.width + 80f, size.height + 80f))
        }

        drawPowerGauge(game.gauge.toFloat(), target, top = 24f, bottom = size.height - 24f)
    }
}

/**
 * A slim ladder of the bands, with your power and the target band marked. [position] runs from 0
 * (bottom of rest) to 6 (top of the last band), as [Band.position] gives it.
 */
internal fun DrawScope.drawPowerGauge(position: Float, target: Band?, top: Float, bottom: Float, left: Float = 24f, width: Float = 22f) {
    val bandHeight = (bottom - top) / Band.entries.size
    Band.entries.forEach { band ->
        val bandTop = bottom - (band.ordinal + 1) * bandHeight
        val lit = band == target
        drawRect(band.color().copy(alpha = if (lit) 0.95f else 0.3f), Offset(left, bandTop + 1f), Size(width, bandHeight - 2f))
        if (lit) drawRect(Color.White, Offset(left - 3f, bandTop), Size(width + 6f, bandHeight), style = Stroke(width = 2f))
    }
    val markerY = bottom - position * bandHeight
    drawPath(
        Path().apply {
            moveTo(left + width + 4f, markerY)
            lineTo(left + width + 22f, markerY - 11f)
            lineTo(left + width + 22f, markerY + 11f)
            close()
        },
        Color.White,
    )
}
