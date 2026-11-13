package name.onetrue.supertuxrower.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import name.onetrue.supertuxrower.game.CaveGame
import name.onetrue.supertuxrower.model.Band

/** Draws the cave game: cave walls, fish, bubbles, Tux, the score bar and the power gauge. */
@Composable
fun GameScene(game: CaveGame, frame: Long, target: Band?, modifier: Modifier) {
    val measurer = rememberTextMeasurer()
    Canvas(modifier) {
        frame // Redraw every frame.
        val iceHeight = 56f
        val unit = (size.height - iceHeight) / CaveGame.VIEW_HEIGHT.toFloat()
        val middle = iceHeight + (size.height - iceHeight) / 2
        fun y(world: Double) = middle - ((world - game.camera) * unit).toFloat()
        val tuxX = size.width * 0.3f
        val pxPerMetre = (size.width - tuxX) / CaveGame.LOOKAHEAD.toFloat()
        fun x(metre: Double) = tuxX + ((metre - game.metres) * pxPerMetre).toFloat()

        // Water gets darker with depth.
        val depth = (-game.camera / 10).toFloat().coerceIn(-1f, 1f)
        drawRect(lerp(Color(0xFF0B2A4A), Color(0xFF030A18), (depth + 1) / 2))

        if (game.slices.size > 1) {
            val ceiling = Path().apply {
                moveTo(x(game.slices.first().metre), 0f)
                game.slices.forEach { lineTo(x(it.metre), y(it.centre + it.half)) }
                lineTo(x(game.slices.last().metre), 0f)
                close()
            }
            val floor = Path().apply {
                moveTo(x(game.slices.first().metre), size.height)
                game.slices.forEach { lineTo(x(it.metre), y(it.centre - it.half)) }
                lineTo(x(game.slices.last().metre), size.height)
                close()
            }
            drawPath(ceiling, Color(0xFFBFE6F5))
            drawPath(floor, Color(0xFF2B3446))
            val edge = Path().apply {
                game.slices.forEachIndexed { i, it ->
                    val px = x(it.metre)
                    val py = y(it.centre + it.half)
                    if (i == 0) moveTo(px, py) else lineTo(px, py)
                }
            }
            drawPath(edge, Color.White.copy(alpha = 0.8f), style = Stroke(width = 4f))
        }

        for (f in game.fish) {
            if (!f.eaten) drawFish(Offset(x(f.metre), y(f.height)), f.gold)
        }
        for (b in game.bubbles) {
            val age = ((game.clock - b.born) / 1.5).toFloat()
            drawCircle(Color.White.copy(alpha = 0.5f * (1 - age)), b.size, Offset(x(b.metre), y(b.height)), style = Stroke(width = 2f))
        }

        val tilt = (-game.vy * 40).toFloat().coerceIn(-25f, 25f)
        val at = Offset(tuxX, y(game.y + game.bob))
        rotate(tilt, pivot = at) { drawTux(at) }

        drawRect(Ice, Offset.Zero, Size(size.width, iceHeight))
        val combo = if (game.multiplier > 1) "   ·   ×${game.multiplier}" else ""
        val count = measurer.measure(
            "Fish ${game.score}   ·   Streak ${game.combo}$combo   ·   Bonks ${game.bonks}",
            TextStyle(color = Deep, fontSize = 26.sp, fontWeight = FontWeight.SemiBold),
        )
        drawText(count, topLeft = Offset((size.width - count.size.width) / 2, (iceHeight - count.size.height) / 2))

        drawPowerGauge(game, target, top = iceHeight + 24f, bottom = size.height - 24f)
    }
}

/** A slim ladder of the bands on the left edge, with your power and the target band marked. */
private fun DrawScope.drawPowerGauge(game: CaveGame, target: Band?, top: Float, bottom: Float) {
    val left = 24f
    val width = 22f
    val bandHeight = (bottom - top) / Band.entries.size
    Band.entries.forEach { band ->
        val bandTop = bottom - (band.ordinal + 1) * bandHeight
        val lit = band == target
        drawRect(band.color().copy(alpha = if (lit) 0.95f else 0.3f), Offset(left, bandTop + 1f), Size(width, bandHeight - 2f))
        if (lit) drawRect(Color.White, Offset(left - 3f, bandTop), Size(width + 6f, bandHeight), style = Stroke(width = 2f))
    }
    val position = game.gauge.toFloat()
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

/** A fish swimming left, towards Tux. */
private fun DrawScope.drawFish(c: Offset, gold: Boolean) {
    val body = if (gold) Color(0xFFFFD54F) else Color(0xFF9FC6D8)
    val r = if (gold) 1.3f else 1f
    drawPath(
        Path().apply {
            moveTo(c.x + 16f * r, c.y)
            lineTo(c.x + 30f * r, c.y - 11f * r)
            lineTo(c.x + 30f * r, c.y + 11f * r)
            close()
        },
        body,
    )
    drawOval(body, Offset(c.x - 20f * r, c.y - 10f * r), Size(40f * r, 20f * r))
    drawCircle(Deep, 3f * r, Offset(c.x - 10f * r, c.y - 2f * r))
    if (gold) drawCircle(Color.White.copy(alpha = 0.6f), 26f * r, c, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2f))
}

private fun DrawScope.drawTux(c: Offset) {
    val black = Color(0xFF16181E)
    val orange = Color(0xFFFAAA1E)
    drawPath(Path().apply { moveTo(c.x - 44f, c.y - 6f); lineTo(c.x - 64f, c.y - 16f); lineTo(c.x - 60f, c.y + 6f); close() }, orange)
    drawOval(black, Offset(c.x - 48f, c.y - 24f), Size(96f, 48f))
    drawOval(Color.White, Offset(c.x - 30f, c.y - 2f), Size(64f, 24f))
    drawCircle(black, 22f, Offset(c.x + 40f, c.y - 10f))
    drawCircle(Color.White, 7f, Offset(c.x + 46f, c.y - 15f))
    drawCircle(Color.Black, 3.5f, Offset(c.x + 48f, c.y - 15f))
    drawPath(Path().apply { moveTo(c.x + 58f, c.y - 14f); lineTo(c.x + 76f, c.y - 7f); lineTo(c.x + 58f, c.y - 1f); close() }, orange)
}
