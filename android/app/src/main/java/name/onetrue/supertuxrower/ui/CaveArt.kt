package name.onetrue.supertuxrower.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import kotlin.math.PI
import kotlin.math.atan2
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

internal val IceLight = Color(0xFFE8F8FF)
internal val IceMid = Color(0xFFA9D8EE)
private val IceDeep = Color(0xFF5F9CC0)
private val RockLight = Color(0xFF4A5A72)
private val RockMid = Color(0xFF323E52)
private val RockDeep = Color(0xFF1B2333)
private val Sand = Color(0xFF5E6478)
private val SandLight = Color(0xFF8C93A6)
private val PenguinBlack = Color(0xFF16181E)
private val PenguinSheen = Color(0xFF3A4458)
private val Orange = Color(0xFFFAAA1E)

/** Open water: bright near the surface, fading towards black as [light] falls. */
internal fun DrawScope.drawWater(light: Float) {
    val top = lerp(Color(0xFF071A30), Color(0xFF2A78A8), light)
    val bottom = lerp(Color(0xFF030A16), Color(0xFF0E3A60), light)
    drawRect(Brush.verticalGradient(listOf(top, bottom)))
}

/** Slanted shafts of daylight, swaying slowly; they fade out quickly as Tux goes deeper. */
internal fun DrawScope.drawLightRays(scroll: Float, clock: Double, light: Float) {
    val strength = light * light
    if (strength < 0.02f) return
    for (i in 0 until 5) {
        val sway = sin(clock * 0.3 + i * 1.7).toFloat() * 40f
        val x = wrap(hash(i, 1) * size.width * 1.4f - scroll * 0.08f + sway, size.width * 1.4f) - size.width * 0.2f
        val w = 60f + hash(i, 2) * 120f
        val alpha = (0.06f + 0.04f * sin(clock * 0.5 + i).toFloat()) * strength
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

/** Specks drifting in the water, nearer ones moving faster. In the dark they glow faintly. */
internal fun DrawScope.drawMarineSnow(scroll: Float, lift: Float, clock: Double, light: Float) {
    val tint = lerp(Color(0xFF7FF5E8), Color.White, light)
    for (i in 0 until 48) {
        val near = 0.3f + hash(i, 10) * 0.7f
        val x = wrap(hash(i, 11) * size.width - scroll * near, size.width)
        val y = wrap(hash(i, 12) * size.height + lift * near + (clock * 12 * near).toFloat() + sin(clock * 0.8 + i).toFloat() * 6f, size.height)
        val twinkle = if (light < 0.5f) 0.6f + 0.4f * sin(clock * 3 + i * 2.3).toFloat() else 1f
        drawCircle(tint.copy(alpha = (0.12f + 0.25f * near) * twinkle), 1.2f + 2.2f * near, Offset(x, y))
    }
}

/**
 * Darkness closing in as daylight fails: a pool of dim light around Tux at [at], black towards the
 * edges. Drawn over the world, so everything in it gets darker together.
 */
internal fun DrawScope.drawDarkness(at: Offset, light: Float) {
    val dark = ((1 - light) / (1 - 0.15f)).coerceIn(0f, 1f)
    if (dark < 0.02f) return
    val night = Color(0xFF01040A)
    drawRect(
        Brush.radialGradient(
            0f to night.copy(alpha = 0.15f * dark),
            0.35f to night.copy(alpha = 0.45f * dark),
            1f to night.copy(alpha = 0.88f * dark),
            center = at, radius = size.width * 0.75f,
        ),
    )
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

/** The ice ceiling: layered and faceted, glinting, with icicles growing out of its rim. */
internal fun DrawScope.drawCeiling(points: List<WallPoint>, clock: Double) {
    if (points.size < 2) return
    val edge = craggy(points, 18f, 20)
    val body = outline(edge, -10f)
    val rim = line(edge)
    drawPath(body, lerp(IceDeep, IceMid, 0.55f))

    clipPath(body) {
        // Shading in bands that follow the rim, lightest at the edge, so it stays put as the cave
        // scrolls (a gradient across the screen slides with whatever part of the rim is lowest).
        for ((width, colour) in listOf(260f to IceMid.copy(alpha = 0.6f), 90f to lerp(IceMid, IceLight, 0.4f), 40f to lerp(IceMid, IceLight, 0.75f))) {
            drawPath(rim, colour, style = Stroke(width = width, join = StrokeJoin.Round))
        }
        // Crystal facets: pale and shadowed planes set into the ice.
        for (p in points) {
            val r = hash(p.index, 26)
            if (r > 0.3f) continue
            val up = 40f + hash(p.index, 27) * 170f
            val w = 50f + hash(p.index, 28) * 90f
            val x = p.x + (hash(p.index, 29) - 0.5f) * 40f
            val y = p.y - up
            // Long and irregular, lying along the strata rather than square to them.
            val facet = Path().apply {
                moveTo(x - w * 0.6f, y + (hash(p.index, 72) - 0.5f) * 14f)
                lineTo(x - w * 0.1f, y - 10f - hash(p.index, 73) * 14f)
                lineTo(x + w * 0.6f, y + (hash(p.index, 74) - 0.5f) * 10f)
                lineTo(x + w * 0.05f, y + 8f + hash(p.index, 75) * 10f)
                close()
            }
            drawPath(facet, (if (r < 0.15f) Color.White else IceDeep).copy(alpha = 0.1f))
            drawPath(facet, Color.White.copy(alpha = 0.1f), style = Stroke(width = 1.5f))
        }
        // Strata, following the rim further up.
        for (k in 1..4) {
            translate(0f, -k * 46f - k * k * 6f) {
                drawPath(rim, IceLight.copy(alpha = 0.35f - k * 0.06f), style = Stroke(width = 3f))
            }
        }
        // Cracks running up from the rim, some forking: a dark fissure with a bright edge.
        for (p in points) {
            if (hash(p.index, 60) > 0.2f) continue
            var at = Offset(p.x, p.y - 6f)
            val crack = Path().apply { moveTo(at.x, at.y) }
            val steps = 4 + (hash(p.index, 69) * 3).toInt()
            for (k in 0 until steps) {
                at = Offset(at.x + (hash(p.index, 61 + k) - 0.5f) * 44f, at.y - 16f - hash(p.index, 65 + k) * 26f)
                crack.lineTo(at.x, at.y)
                if (k == 1 && hash(p.index, 76) < 0.6f) {
                    // A branch off to one side.
                    val side = if (hash(p.index, 77) < 0.5f) -1f else 1f
                    crack.moveTo(at.x, at.y)
                    crack.lineTo(at.x + side * 30f, at.y - 20f)
                    crack.lineTo(at.x + side * 44f, at.y - 46f)
                    crack.moveTo(at.x, at.y)
                }
            }
            drawPath(crack, IceDeep.copy(alpha = 0.8f), style = Stroke(width = 3.5f, join = StrokeJoin.Round, cap = StrokeCap.Round))
            translate(1.5f, 1f) { drawPath(crack, Color.White.copy(alpha = 0.8f), style = Stroke(width = 1.5f, join = StrokeJoin.Round)) }
        }
    }

    // Icicles start inside the ice, so the rim drawn over them hides the join.
    for (p in points) {
        if (hash(p.index, 21) > 0.45f) continue
        val length = 30f + hash(p.index, 22) * 70f
        val width = 14f + hash(p.index, 23) * 16f
        val x = p.x + (hash(p.index, 24) - 0.5f) * 30f
        drawIcicle(x, p.y - 4f, width, length)
        if (hash(p.index, 25) < 0.4f) drawIcicle(x + width * 0.7f, p.y - 4f, width * 0.55f, length * 0.5f)
    }

    drawPath(rim, IceMid, style = Stroke(width = 24f))
    drawPath(rim, IceLight, style = Stroke(width = 12f))
    drawPath(rim, Color.White, style = Stroke(width = 3f))

    // Glints come and go along the rim.
    for (p in points) {
        if (hash(p.index, 70) > 0.15f) continue
        val t = sin(clock * 1.3 + hash(p.index, 71) * 20).toFloat()
        if (t > 0.6f) drawSparkle(Offset(p.x, p.y - 4f), 10f * (t - 0.6f) / 0.4f, Color.White)
    }
}

/** An icicle hanging from [top]: rim-coloured at the root, clearer towards the tip. */
private fun DrawScope.drawIcicle(x: Float, top: Float, width: Float, length: Float) {
    val tip = Offset(x + width * 0.08f, top + length)
    drawPath(Path().apply {
        moveTo(x - width / 2, top)
        lineTo(x + width / 2, top)
        quadraticTo(x + width * 0.25f, top + length * 0.5f, tip.x, tip.y)
        quadraticTo(x - width * 0.2f, top + length * 0.5f, x - width / 2, top)
        close()
    }, Brush.verticalGradient(listOf(IceLight, IceMid, IceMid.copy(alpha = 0.55f)), startY = top, endY = tip.y))
    drawLine(Color.White.copy(alpha = 0.85f), Offset(x - width * 0.2f, top + 4f), Offset(x - width * 0.02f, top + length * 0.7f), 2f)
}

/** The rock floor: kelp rooted behind it, sediment layers, grit and boulders, and life on top. */
internal fun DrawScope.drawFloor(points: List<WallPoint>, clock: Double) {
    if (points.size < 2) return
    val edge = craggy(points, 22f, 30)
    val highest = edge.minOf { it.y }

    // Kelp first, rooted below the rim, so the ground hides where it grows from.
    for (p in points) {
        if (hash(p.index, 31) > 0.25f) continue
        val strands = 1 + (hash(p.index, 32) * 3).toInt()
        repeat(strands) { k -> drawKelp(p.x + (hash(p.index, 33 + k) - 0.5f) * 40f, p.y + 26f, 80f + hash(p.index, 36 + k) * 120f, hash(p.index, 39 + k) * 6f, clock) }
    }

    val body = outline(edge, size.height + 10f)
    drawPath(body, Brush.verticalGradient(listOf(RockMid, RockDeep), startY = highest, endY = size.height))
    val rim = line(edge)
    clipPath(body) {
        // Sediment: darker bands under lighter seams, following the rim down.
        for (k in 1..4) {
            translate(0f, k * 38f + k * k * 8f) {
                drawPath(rim, RockDeep.copy(alpha = 0.5f), style = Stroke(width = 14f))
                drawPath(rim, RockLight.copy(alpha = 0.22f), style = Stroke(width = 2f))
            }
        }
        // Grit and pebbles set in the rock.
        for (p in points) {
            repeat(3) { k ->
                val h = hash(p.index, 80 + k)
                if (h > 0.6f) return@repeat
                val r = 2f + hash(p.index, 84 + k) * 7f
                val at = Offset(p.x + (hash(p.index, 88 + k) - 0.5f) * 60f, p.y + 20f + hash(p.index, 92 + k) * 220f)
                drawOval(RockLight.copy(alpha = 0.35f), Offset(at.x - r, at.y - r * 0.7f), Size(r * 2, r * 1.4f))
            }
        }
    }
    // A sandy top to the rock.
    drawPath(rim, Sand.copy(alpha = 0.7f), style = Stroke(width = 12f))
    drawPath(rim, SandLight, style = Stroke(width = 3f))

    for ((i, p) in points.withIndex()) {
        val r = hash(p.index, 41)
        // The floor's slope here, so things that crawl along it lie along it.
        val before = points[(i - 1).coerceAtLeast(0)]
        val after = points[(i + 1).coerceAtMost(points.lastIndex)]
        val slope = (atan2(after.y - before.y, after.x - before.x) * 180 / PI).toFloat()
        when {
            r < 0.3f -> {
                val w = 20f + hash(p.index, 42) * 36f
                val h = w * (0.5f + hash(p.index, 43) * 0.3f)
                val at = Offset(p.x + (hash(p.index, 44) - 0.5f) * 30f, p.y + h * 0.25f)
                drawOval(RockMid, Offset(at.x - w / 2, at.y - h / 2), Size(w, h))
                drawOval(RockLight, Offset(at.x - w * 0.35f, at.y - h * 0.45f), Size(w * 0.5f, h * 0.35f))
            }
            else -> drawFloorLife(p, r, clock, slope)
        }
    }
}

private fun DrawScope.drawKelp(baseX: Float, baseY: Float, height: Float, phase: Float, clock: Double) {
    val path = Path()
    path.moveTo(baseX, baseY)
    val segments = 6
    var px = baseX
    var py = baseY
    for (s in 1..segments) {
        val f = s / segments.toFloat()
        val sway = sin(clock * 1.6 + phase + f * 2.5).toFloat() * 16f * f
        val nx = baseX + sway
        val ny = baseY - height * f
        path.quadraticTo(px + sway * 0.5f, (py + ny) / 2, nx, ny)
        px = nx
        py = ny
    }
    // Each strand a slightly different green.
    val hue = 145f + (phase / 6f - 0.5f) * 40f
    val shade = 0.48f + (phase * 7 % 1) * 0.2f
    drawPath(path, Color.hsv(hue, 0.65f, shade), style = Stroke(width = 9f, cap = StrokeCap.Round))
    drawPath(path, Color.hsv(hue, 0.45f, shade + 0.25f).copy(alpha = 0.6f), style = Stroke(width = 3f, cap = StrokeCap.Round))
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
