package name.onetrue.supertuxrower.ui

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/*
 * Easter eggs: things frozen into the ice and buried in the rock, about one every 150 m in each.
 * They don't move; they're there to be spotted. Drawn inside the ice's or rock's outline, so they
 * never poke out of it.
 */

/** Whether the wall point at [index] holds something buried, and if so which of [kinds]. */
private fun buried(index: Int, salt: Int, kinds: Int): Int? {
    val showing = Summon.only == "buried"
    if (if (showing) index % 6 != 0 else hash(index, salt) > 0.0065f) return null
    return (hash(index, salt + 1) * kinds).toInt().coerceAtMost(kinds - 1)
}

/** Things frozen in the ice above [points], seen through a frosting of it. */
internal fun DrawScope.drawFrozen(points: List<WallPoint>) {
    for (p in points) {
        val kind = buried(p.index, 600, 5) ?: continue
        val at = Offset(p.x, p.y - 70f - hash(p.index, 602) * 60f)
        rotate((hash(p.index, 603) - 0.5f) * 60f, pivot = at) {
            when (kind) {
                0 -> drawFrozenFish(at)
                1 -> drawBoot(at)
                2 -> drawDivingHelmet(at)
                3 -> drawAmmonite(at, Color(0xFFB08A60))
                else -> drawBottle(at)
            }
        }
        // The ice over it: a frosted haze and a few trapped bubbles.
        drawOval(IceMid.copy(alpha = 0.35f), Offset(at.x - 50f, at.y - 34f), Size(100f, 68f))
        repeat(4) { k -> drawCircle(Color.White.copy(alpha = 0.5f), 2f + k, Offset(at.x - 30f + hash(p.index, 604 + k) * 60f, at.y - 26f + hash(p.index, 608 + k) * 52f), style = Stroke(1f)) }
    }
}

/** Things buried in the rock below [points]. */
internal fun DrawScope.drawFossils(points: List<WallPoint>) {
    val bone = Color(0xFFCFC6B0)
    for (p in points) {
        val kind = buried(p.index, 620, 5) ?: continue
        val at = Offset(p.x, p.y + 70f + hash(p.index, 622) * 70f)
        rotate((hash(p.index, 623) - 0.5f) * 40f, pivot = at) {
            when (kind) {
                0 -> drawFishSkeleton(at, bone)
                1 -> drawSkull(at, bone)
                2 -> drawChest(at)
                3 -> drawAmphora(at)
                else -> drawAmmonite(at, bone)
            }
        }
    }
}

private fun DrawScope.drawFrozenFish(c: Offset) {
    val body = Color(0xFF8AA8B8)
    drawPath(Path().apply {
        moveTo(c.x + 22f, c.y)
        lineTo(c.x + 38f, c.y - 12f)
        lineTo(c.x + 38f, c.y + 12f)
        close()
    }, body)
    drawOval(body, Offset(c.x - 28f, c.y - 12f), Size(54f, 24f))
    drawCircle(Color.White, 4f, Offset(c.x - 16f, c.y - 3f))
    // Eyes crossed: it's been here a while.
    drawLine(Color(0xFF2A3A48), Offset(c.x - 19f, c.y - 6f), Offset(c.x - 13f, c.y), 1.6f)
    drawLine(Color(0xFF2A3A48), Offset(c.x - 19f, c.y), Offset(c.x - 13f, c.y - 6f), 1.6f)
}

private fun DrawScope.drawBoot(c: Offset) {
    val leather = Color(0xFF5A3A24)
    drawRoundRect(leather, Offset(c.x - 10f, c.y - 30f), Size(22f, 40f), CornerRadius(4f))
    drawRoundRect(leather, Offset(c.x - 28f, c.y + 2f), Size(40f, 16f), CornerRadius(8f))
    drawLine(Color(0xFF2A1A10), Offset(c.x - 30f, c.y + 18f), Offset(c.x + 12f, c.y + 18f), 4f, StrokeCap.Round)
    for (k in 0..2) drawLine(Color(0xFFC8B890), Offset(c.x - 6f, c.y - 22f + k * 8f), Offset(c.x + 8f, c.y - 18f + k * 8f), 1.5f)
}

private fun DrawScope.drawDivingHelmet(c: Offset) {
    val brass = Color(0xFFB8893A)
    drawCircle(brass, 28f, c)
    drawRoundRect(brass, Offset(c.x - 30f, c.y + 18f), Size(60f, 14f), CornerRadius(4f))
    drawCircle(Color(0xFF2A4A5A), 12f, Offset(c.x - 4f, c.y))
    drawCircle(brass, 12f, Offset(c.x - 4f, c.y), style = Stroke(4f))
    drawCircle(Color(0xFF2A4A5A), 6f, Offset(c.x + 18f, c.y - 8f))
    drawCircle(Color.White.copy(alpha = 0.4f), 3f, Offset(c.x - 8f, c.y - 4f))
}

private fun DrawScope.drawAmmonite(c: Offset, colour: Color) {
    drawCircle(colour, 24f, c)
    val ridge = Color(0xFF6A5A44)
    drawPath(Path().apply {
        moveTo(c.x, c.y)
        for (t in 1..40) {
            val a = t * 0.38f
            val r = t * 0.6f
            lineTo(c.x + cos(a) * r, c.y + sin(a) * r)
        }
    }, ridge, style = Stroke(2f))
    for (k in 0 until 12) {
        val a = k * PI.toFloat() / 6
        drawLine(ridge, Offset(c.x + cos(a) * 16f, c.y + sin(a) * 16f), Offset(c.x + cos(a) * 23f, c.y + sin(a) * 23f), 1.5f)
    }
}

private fun DrawScope.drawBottle(c: Offset) {
    val glass = Color(0xFF5E9A6A)
    drawRoundRect(glass, Offset(c.x - 26f, c.y - 10f), Size(44f, 20f), CornerRadius(8f))
    drawRect(glass, Offset(c.x + 16f, c.y - 4f), Size(14f, 8f))
    drawRect(Color(0xFF8A6A40), Offset(c.x + 28f, c.y - 4f), Size(5f, 8f))
    // The rolled-up message inside.
    drawRoundRect(Color(0xFFEDE3C8), Offset(c.x - 18f, c.y - 5f), Size(26f, 10f), CornerRadius(3f))
    drawLine(Color(0xFFB04030), Offset(c.x - 6f, c.y - 5f), Offset(c.x - 6f, c.y + 5f), 1.5f)
}

private fun DrawScope.drawFishSkeleton(c: Offset, bone: Color) {
    drawLine(bone, Offset(c.x - 30f, c.y), Offset(c.x + 34f, c.y), 3f, StrokeCap.Round)
    for (k in 0 until 7) {
        val x = c.x - 18f + k * 7f
        val h = 14f - kotlin.math.abs(k - 3) * 2f
        drawLine(bone, Offset(x, c.y - h), Offset(x + 3f, c.y + h), 2f, StrokeCap.Round)
    }
    drawPath(Path().apply {
        moveTo(c.x + 34f, c.y)
        lineTo(c.x + 46f, c.y - 10f)
        lineTo(c.x + 46f, c.y + 10f)
        close()
    }, bone, style = Stroke(2f))
    drawCircle(bone, 11f, Offset(c.x - 36f, c.y), style = Stroke(2.5f))
    drawCircle(bone, 2.5f, Offset(c.x - 38f, c.y - 3f))
}

/** A dinosaur's skull: long jaws and a row of teeth. */
private fun DrawScope.drawSkull(c: Offset, bone: Color) {
    drawPath(Path().apply {
        moveTo(c.x - 44f, c.y + 4f)
        quadraticTo(c.x - 40f, c.y - 16f, c.x, c.y - 20f)
        quadraticTo(c.x + 30f, c.y - 22f, c.x + 34f, c.y)
        lineTo(c.x + 20f, c.y + 8f)
        lineTo(c.x - 44f, c.y + 8f)
        close()
    }, bone)
    drawPath(Path().apply {
        moveTo(c.x - 42f, c.y + 12f)
        lineTo(c.x + 16f, c.y + 12f)
        lineTo(c.x + 10f, c.y + 20f)
        lineTo(c.x - 36f, c.y + 18f)
        close()
    }, bone)
    val hole = RockMid
    drawCircle(hole, 7f, Offset(c.x + 14f, c.y - 8f))
    drawOval(hole, Offset(c.x - 12f, c.y - 12f), Size(16f, 10f))
    for (k in 0 until 7) drawLine(bone, Offset(c.x - 38f + k * 7f, c.y + 8f), Offset(c.x - 37f + k * 7f, c.y + 13f), 2f)
}

private fun DrawScope.drawChest(c: Offset) {
    val wood = Color(0xFF6A4424)
    val gold = Color(0xFFE0B040)
    // Ajar, with a glint of treasure.
    drawCircle(gold, 6f, Offset(c.x - 8f, c.y - 14f))
    drawCircle(gold, 5f, Offset(c.x + 4f, c.y - 15f))
    drawCircle(gold, 5f, Offset(c.x + 14f, c.y - 13f))
    drawSparkle(Offset(c.x + 2f, c.y - 22f), 6f, Color.White)
    rotate(-14f, pivot = Offset(c.x - 26f, c.y - 12f)) {
        drawRoundRect(wood, Offset(c.x - 26f, c.y - 26f), Size(52f, 14f), CornerRadius(6f))
    }
    drawRect(wood, Offset(c.x - 26f, c.y - 12f), Size(52f, 28f))
    drawRect(gold, Offset(c.x - 26f, c.y - 12f), Size(52f, 4f))
    drawRect(gold, Offset(c.x - 4f, c.y - 8f), Size(8f, 10f))
}

private fun DrawScope.drawAmphora(c: Offset) {
    val clay = Color(0xFFA0583A)
    drawPath(Path().apply {
        moveTo(c.x - 6f, c.y - 30f)
        lineTo(c.x + 6f, c.y - 30f)
        quadraticTo(c.x + 22f, c.y - 10f, c.x + 14f, c.y + 16f)
        lineTo(c.x, c.y + 30f)
        lineTo(c.x - 14f, c.y + 16f)
        quadraticTo(c.x - 22f, c.y - 10f, c.x - 6f, c.y - 30f)
        close()
    }, clay)
    for (side in listOf(-1f, 1f)) drawArc(clay, if (side < 0) 90f else -90f, 180f, false, Offset(c.x + side * 10f - 7f, c.y - 30f), Size(14f, 20f), style = Stroke(3f))
    drawLine(Color(0xFF2A1A12), Offset(c.x - 14f, c.y - 4f), Offset(c.x + 14f, c.y - 4f), 2f)
    drawLine(Color(0xFF2A1A12), Offset(c.x - 12f, c.y + 6f), Offset(c.x + 12f, c.y + 6f), 2f)
}
