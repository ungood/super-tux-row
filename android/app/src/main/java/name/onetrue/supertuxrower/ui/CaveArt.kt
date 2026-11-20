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
import androidx.compose.ui.graphics.drawscope.translate
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/*
 * The cave game's art, drawn with shapes rather than sprites. Decorations are placed by hashing
 * their position in the world, so they stay put as the cave scrolls without the game keeping them.
 */

/** A stable pseudo-random number in 0..1 for [i]; [salt] picks an independent sequence. */
internal fun hash(i: Int, salt: Int = 0): Float {
    var h = i * 374761393 + salt * 668265263
    h = (h xor (h ushr 13)) * 1274126177
    h = h xor (h ushr 16)
    return (h and 0xFFFFFF) / 16777216f
}

private fun wrap(v: Float, size: Float) = ((v % size) + size) % size

private val IceLight = Color(0xFFE8F8FF)
private val IceMid = Color(0xFFA9D8EE)
private val IceDeep = Color(0xFF5F9CC0)
private val RockLight = Color(0xFF4A5A72)
private val RockMid = Color(0xFF323E52)
private val RockDeep = Color(0xFF1B2333)
private val Kelp = Color(0xFF2F8F6E)
private val KelpLight = Color(0xFF5CC49A)
private val PenguinBlack = Color(0xFF16181E)
private val PenguinSheen = Color(0xFF3A4458)
private val Orange = Color(0xFFFAAA1E)

/** Open water: lighter towards the surface, darker the deeper the camera is. */
internal fun DrawScope.drawWater(depth: Float) {
    val dark = depth.coerceIn(0f, 1f)
    val top = lerp(Color(0xFF1C5A86), Color(0xFF0A2440), dark)
    val bottom = lerp(Color(0xFF0A2848), Color(0xFF020814), dark)
    drawRect(Brush.verticalGradient(listOf(top, bottom)))
}

/** Slanted shafts of light from the surface, swaying slowly and drifting with the camera. */
internal fun DrawScope.drawLightRays(scroll: Float, clock: Double) {
    for (i in 0 until 5) {
        val sway = sin(clock * 0.3 + i * 1.7).toFloat() * 40f
        val x = wrap(hash(i, 1) * size.width * 1.4f - scroll * 0.08f + sway, size.width * 1.4f) - size.width * 0.2f
        val w = 60f + hash(i, 2) * 120f
        val alpha = 0.05f + 0.04f * sin(clock * 0.5 + i).toFloat()
        val path = Path().apply {
            moveTo(x, 0f)
            lineTo(x + w, 0f)
            lineTo(x + w + size.height * 0.45f, size.height)
            lineTo(x + size.height * 0.45f - w * 0.5f, size.height)
            close()
        }
        drawPath(path, Brush.verticalGradient(listOf(Color.White.copy(alpha = alpha), Color.Transparent)))
    }
}

/**
 * Far-off ice columns behind the cave, scrolling at a fraction of the cave's speed so the water
 * has depth. [scroll] is the cave's scroll in pixels, [lift] the camera's height in pixels.
 */
internal fun DrawScope.drawBackdrop(scroll: Float, lift: Float, parallax: Float, spacing: Float, color: Color, salt: Int) {
    val offset = scroll * parallax
    val first = (offset / spacing).toInt() - 1
    val last = ((offset + size.width) / spacing).toInt() + 1
    val shift = lift * parallax
    for (i in first..last) {
        val x = i * spacing - offset + hash(i, salt) * spacing * 0.5f
        val w = spacing * (0.35f + hash(i, salt + 1) * 0.4f)
        val down = size.height * (0.15f + hash(i, salt + 2) * 0.35f) + shift
        val up = size.height * (0.15f + hash(i, salt + 3) * 0.35f) - shift
        // Hanging from above, a tapering spire...
        drawPath(Path().apply {
            moveTo(x - w / 2, -50f)
            lineTo(x + w / 2, -50f)
            quadraticTo(x + w * 0.2f, down * 0.6f, x, down)
            quadraticTo(x - w * 0.2f, down * 0.6f, x - w / 2, -50f)
        }, color)
        // ...and rising from below, a broad mound.
        drawPath(Path().apply {
            moveTo(x - w * 0.8f, size.height + 50f)
            quadraticTo(x - w * 0.3f, size.height - up, x + w * 0.1f, size.height - up)
            quadraticTo(x + w * 0.6f, size.height - up * 0.7f, x + w, size.height + 50f)
        }, color)
    }
}

/** Specks drifting in the water, nearer ones moving faster. */
internal fun DrawScope.drawMarineSnow(scroll: Float, lift: Float, clock: Double) {
    for (i in 0 until 48) {
        val near = 0.3f + hash(i, 10) * 0.7f
        val x = wrap(hash(i, 11) * size.width - scroll * near, size.width)
        val y = wrap(hash(i, 12) * size.height + lift * near + (clock * 12 * near).toFloat() + sin(clock * 0.8 + i).toFloat() * 6f, size.height)
        drawCircle(Color.White.copy(alpha = 0.12f + 0.25f * near), 1.2f + 2.2f * near, Offset(x, y))
    }
}

/** One point of a cave wall, on screen. */
internal class WallPoint(val index: Int, val x: Float, val y: Float)

/** A craggy outline through [points], with a jittered midpoint between each pair. */
private fun craggy(points: List<WallPoint>, jitter: Float, salt: Int): List<Offset> {
    val out = ArrayList<Offset>(points.size * 2)
    points.forEachIndexed { i, p ->
        out.add(Offset(p.x, p.y + (hash(p.index, salt) - 0.5f) * jitter * 0.6f))
        val next = points.getOrNull(i + 1) ?: return@forEachIndexed
        out.add(Offset((p.x + next.x) / 2, (p.y + next.y) / 2 + (hash(p.index, salt + 1) - 0.5f) * jitter))
    }
    return out
}

private fun outline(edge: List<Offset>, closeAt: Float) = Path().apply {
    moveTo(edge.first().x, closeAt)
    edge.forEach { lineTo(it.x, it.y) }
    lineTo(edge.last().x, closeAt)
    close()
}

private fun line(edge: List<Offset>) = Path().apply {
    edge.forEachIndexed { i, p -> if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y) }
}

/** The ice ceiling: a glassy rim, frost along the edge and icicles hanging from it. */
internal fun DrawScope.drawCeiling(points: List<WallPoint>, clock: Double) {
    if (points.size < 2) return
    val edge = craggy(points, 18f, 20)
    val lowest = edge.maxOf { it.y }
    drawPath(outline(edge, -10f), Brush.verticalGradient(listOf(IceDeep, IceMid, IceLight), startY = lowest - 320f, endY = lowest))
    val rim = line(edge)
    // Strata in the ice, following the rim further up.
    for (k in 1..4) {
        translate(0f, -k * 46f - k * k * 6f) {
            drawPath(rim, IceLight.copy(alpha = 0.35f - k * 0.06f), style = Stroke(width = 3f))
        }
    }
    drawPath(rim, IceMid.copy(alpha = 0.6f), style = Stroke(width = 26f))
    drawPath(rim, IceLight, style = Stroke(width = 10f))
    drawPath(rim, Color.White, style = Stroke(width = 3f))

    for (p in points) {
        val r = hash(p.index, 21)
        if (r > 0.45f) continue
        val length = 24f + hash(p.index, 22) * 70f
        val width = 10f + hash(p.index, 23) * 14f
        val x = p.x + (hash(p.index, 24) - 0.5f) * 30f
        val top = p.y - 6f
        drawPath(Path().apply {
            moveTo(x - width / 2, top)
            lineTo(x + width / 2, top)
            lineTo(x + width * 0.1f, top + length)
            close()
        }, Brush.horizontalGradient(listOf(IceLight, IceMid, IceDeep), startX = x - width / 2, endX = x + width / 2))
        drawLine(Color.White.copy(alpha = 0.8f), Offset(x - width * 0.25f, top + 2f), Offset(x, top + length * 0.7f), 2f)
        // Now and then a drip gathers at the tip and falls.
        if (r < 0.12f) {
            val t = ((clock * 0.4 + hash(p.index, 25) * 10) % 1.0).toFloat()
            val drop = if (t < 0.6f) Offset(x + width * 0.1f, top + length + 3f) else Offset(x + width * 0.1f, top + length + (t - 0.6f) * 400f)
            drawCircle(IceLight.copy(alpha = if (t < 0.6f) t / 0.6f else 1 - (t - 0.6f) / 0.4f), 3.5f, drop)
        }
    }
}

/** The rock floor: bevelled boulders, kelp swaying, and the odd starfish. */
internal fun DrawScope.drawFloor(points: List<WallPoint>, clock: Double) {
    if (points.size < 2) return
    val edge = craggy(points, 22f, 30)
    val highest = edge.minOf { it.y }
    drawPath(outline(edge, size.height + 10f), Brush.verticalGradient(listOf(RockMid, RockDeep), startY = highest, endY = size.height))

    // Kelp behind the boulders, rooted along the edge.
    for (p in points) {
        if (hash(p.index, 31) > 0.25f) continue
        val strands = 1 + (hash(p.index, 32) * 3).toInt()
        repeat(strands) { k ->
            val baseX = p.x + (hash(p.index, 33 + k) - 0.5f) * 40f
            val height = 60f + hash(p.index, 36 + k) * 120f
            val phase = hash(p.index, 39 + k) * 6f
            val path = Path()
            path.moveTo(baseX, p.y + 8f)
            val segments = 6
            var px = baseX
            var py = p.y + 8f
            for (s in 1..segments) {
                val f = s / segments.toFloat()
                val sway = sin(clock * 1.6 + phase + f * 2.5).toFloat() * 16f * f
                val nx = baseX + sway
                val ny = p.y + 8f - height * f
                path.quadraticTo(px + sway * 0.5f, (py + ny) / 2, nx, ny)
                px = nx
                py = ny
            }
            drawPath(path, Kelp, style = Stroke(width = 9f, cap = StrokeCap.Round))
            drawPath(path, KelpLight.copy(alpha = 0.6f), style = Stroke(width = 3f, cap = StrokeCap.Round))
        }
    }

    val rim = line(edge)
    drawPath(rim, RockLight, style = Stroke(width = 8f))
    for (p in points) {
        val r = hash(p.index, 41)
        if (r < 0.4f) {
            val w = 20f + hash(p.index, 42) * 36f
            val h = w * (0.5f + hash(p.index, 43) * 0.3f)
            val at = Offset(p.x + (hash(p.index, 44) - 0.5f) * 30f, p.y + h * 0.25f)
            drawOval(RockMid, Offset(at.x - w / 2, at.y - h / 2), Size(w, h))
            drawOval(RockLight, Offset(at.x - w * 0.35f, at.y - h * 0.45f), Size(w * 0.5f, h * 0.35f))
        } else if (r > 0.94f) {
            drawStarfish(Offset(p.x, p.y + 6f), 14f, hash(p.index, 45) * 72f)
        }
    }
}

private fun DrawScope.drawStarfish(c: Offset, r: Float, angle: Float) {
    val path = Path()
    for (i in 0 until 10) {
        val a = (angle + i * 36f - 90f) * PI.toFloat() / 180f
        val len = if (i % 2 == 0) r else r * 0.45f
        val p = Offset(c.x + cos(a) * len, c.y + sin(a) * len)
        if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
    }
    path.close()
    drawPath(path, Color(0xFFE86A6A))
    drawCircle(Color(0xFFF4A1A1), r * 0.2f, c)
}

/** A bubble: a faint ring with a glint. */
internal fun DrawScope.drawBubble(c: Offset, r: Float, alpha: Float) {
    drawCircle(Color(0xFFBFE9FF).copy(alpha = 0.15f * alpha), r, c)
    drawCircle(Color.White.copy(alpha = 0.6f * alpha), r, c, style = Stroke(width = 1.6f))
    drawCircle(Color.White.copy(alpha = 0.8f * alpha), r * 0.3f, Offset(c.x - r * 0.35f, c.y - r * 0.35f))
}

/** A fish swimming left, towards Tux, its tail beating. Golden ones glow. */
internal fun DrawScope.drawFish(c: Offset, gold: Boolean, clock: Double, seed: Int) {
    val r = if (gold) 1.3f else 1f
    val phase = hash(seed, 50) * 6f
    val beat = sin(clock * 9 + phase).toFloat()
    val at = Offset(c.x, c.y + sin(clock * 2 + phase).toFloat() * 4f)
    val body = if (gold) Color(0xFFFFD54F) else Color(0xFF9FC6D8)
    val belly = if (gold) Color(0xFFFFF3C4) else Color(0xFFE3F1F7)
    val back = if (gold) Color(0xFFE8A317) else Color(0xFF5E8EA8)

    if (gold) {
        val pulse = 0.5f + 0.5f * sin(clock * 4 + phase).toFloat()
        drawCircle(Brush.radialGradient(listOf(Color(0xFFFFE082).copy(alpha = 0.35f + 0.2f * pulse), Color.Transparent), at, 50f * r), 50f * r, at)
        // Glints circling it.
        for (k in 0 until 3) {
            val a = clock * 2 + k * 2.1
            drawSparkle(Offset(at.x + cos(a).toFloat() * 34f * r, at.y + sin(a).toFloat() * 22f * r), 5f + 3f * pulse, Color.White)
        }
    }
    scale(r, r, pivot = at) {
        rotate(beat * 18f, pivot = Offset(at.x + 16f, at.y)) {
            drawPath(Path().apply {
                moveTo(at.x + 14f, at.y)
                lineTo(at.x + 32f, at.y - 12f)
                quadraticTo(at.x + 27f, at.y, at.x + 32f, at.y + 12f)
                close()
            }, back)
        }
        drawOval(Brush.verticalGradient(listOf(back, body, belly), startY = at.y - 10f, endY = at.y + 10f), Offset(at.x - 20f, at.y - 10f), Size(40f, 20f))
        drawPath(Path().apply {
            moveTo(at.x - 4f, at.y - 9f)
            quadraticTo(at.x + 4f, at.y - 18f, at.x + 10f, at.y - 8f)
            close()
        }, back)
        drawCircle(Color.White, 4f, Offset(at.x - 10f, at.y - 2f))
        drawCircle(Deep, 2.4f, Offset(at.x - 11f, at.y - 2f))
    }
}

/** A four-pointed twinkle. */
internal fun DrawScope.drawSparkle(c: Offset, r: Float, color: Color) {
    drawPath(Path().apply {
        moveTo(c.x, c.y - r)
        quadraticTo(c.x, c.y, c.x + r, c.y)
        quadraticTo(c.x, c.y, c.x, c.y + r)
        quadraticTo(c.x, c.y, c.x - r, c.y)
        quadraticTo(c.x, c.y, c.x, c.y - r)
    }, color)
}

/** The burst where a fish was eaten: [t] runs 0 to 1 over its life. */
internal fun DrawScope.drawCatch(c: Offset, gold: Boolean, t: Float) {
    val color = if (gold) Color(0xFFFFD54F) else Color(0xFFBFE6F5)
    drawCircle(color.copy(alpha = 0.6f * (1 - t)), 14f + 50f * t, c, style = Stroke(width = 4f * (1 - t) + 1f))
    for (k in 0 until 8) {
        val a = k * PI / 4 + 0.3
        val d = 10f + 60f * t
        drawSparkle(Offset(c.x + cos(a).toFloat() * d, c.y + sin(a).toFloat() * d), 7f * (1 - t), color)
    }
}

/**
 * Tux swimming right. [sinceStroke] is seconds since the last stroke, which sweeps his flipper
 * back; [dizzy] runs 1 to 0 after a bonk, while stars circle his head.
 */
internal fun DrawScope.drawTux(c: Offset, clock: Double, sinceStroke: Double, dizzy: Float) {
    // Feet kick behind him.
    val kick = sin(clock * 7).toFloat()
    for (k in 0..1) {
        val swing = (if (k == 0) kick else -kick) * 10f
        drawPath(Path().apply {
            moveTo(c.x - 40f, c.y + 4f + k * 6f)
            lineTo(c.x - 66f, c.y - 6f + k * 10f + swing)
            lineTo(c.x - 62f, c.y + 12f + k * 10f + swing)
            close()
        }, if (k == 0) Orange else Color(0xFFE0901A))
    }

    // Body, with a sheen along the back.
    drawOval(PenguinBlack, Offset(c.x - 50f, c.y - 25f), Size(100f, 50f))
    drawOval(Brush.verticalGradient(listOf(PenguinSheen, Color.Transparent), startY = c.y - 24f, endY = c.y - 4f), Offset(c.x - 40f, c.y - 23f), Size(76f, 20f))
    drawOval(Color.White, Offset(c.x - 34f, c.y - 2f), Size(70f, 24f))
    drawOval(Color(0xFFDCE6EE), Offset(c.x - 30f, c.y + 10f), Size(60f, 11f))

    // The flipper: a stroke sweeps it back hard, then it paddles gently.
    val angle = if (sinceStroke < 0.5) {
        val t = (sinceStroke / 0.5).toFloat()
        -40f + 90f * sin(t * PI.toFloat())
    } else {
        sin(clock * 3).toFloat() * 12f
    }
    val shoulder = Offset(c.x + 8f, c.y - 6f)
    rotate(angle, pivot = shoulder) {
        drawPath(Path().apply {
            moveTo(shoulder.x + 6f, shoulder.y - 4f)
            quadraticTo(shoulder.x - 14f, shoulder.y + 4f, shoulder.x - 40f, shoulder.y + 18f)
            quadraticTo(shoulder.x - 10f, shoulder.y + 14f, shoulder.x + 6f, shoulder.y + 6f)
            close()
        }, Color(0xFF262A33))
    }

    // Head: blinks every few seconds.
    val head = Offset(c.x + 42f, c.y - 10f)
    drawCircle(PenguinBlack, 24f, head)
    drawCircle(Color.White, 12f, Offset(head.x + 8f, head.y + 6f))
    val blink = (clock % 4.0) < 0.12
    val eye = Offset(head.x + 7f, head.y - 5f)
    if (blink) {
        drawLine(Color.White, Offset(eye.x - 6f, eye.y), Offset(eye.x + 6f, eye.y), 3f)
    } else {
        drawCircle(Color.White, 7.5f, eye)
        drawCircle(Color.Black, 4f, Offset(eye.x + 2.5f, eye.y))
        drawCircle(Color.White, 1.4f, Offset(eye.x + 3.5f, eye.y - 1.5f))
    }
    drawPath(Path().apply {
        moveTo(head.x + 18f, head.y - 4f)
        quadraticTo(head.x + 40f, head.y + 1f, head.x + 38f, head.y + 3f)
        lineTo(head.x + 18f, head.y + 9f)
        close()
    }, Orange)

    if (dizzy > 0f) {
        for (k in 0 until 3) {
            val a = clock * 5 + k * 2.1
            val star = Offset(head.x + cos(a).toFloat() * 30f, head.y - 30f + sin(a).toFloat() * 8f)
            drawSparkle(star, 9f * dizzy, Color(0xFFFFE082).copy(alpha = dizzy))
        }
    }
}
