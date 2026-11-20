package name.onetrue.supertuxrower.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.lerp
import name.onetrue.supertuxrower.game.Cue
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/*
 * More life on the cave floor (the table of what lives where is in CaveLife). Each creature stands
 * on [base], a point on the floor's rim; up is towards smaller y.
 */

private fun smoothstep(from: Float, to: Float, v: Float): Float {
    val t = ((v - from) / (to - from)).coerceIn(0f, 1f)
    return t * t * (3 - 2 * t)
}

/** Sea urchins: one to three spiky balls, purple or nearly black, creeping along on their spines. */
internal fun DrawScope.drawUrchins(base: Offset, seed: Int, clock: Double) {
    val count = 1 + (hash(seed, 300) * 3).toInt()
    val purple = hash(seed, 301) < 0.6f
    for (k in 0 until count) {
        val r = 8f + hash(seed, 302 + k) * 7f
        val creep = sin(clock * 0.08 + hash(seed, 306 + k) * 6).toFloat() * 14f
        val c = Offset(base.x + (k - (count - 1) / 2f) * 26f + creep, base.y - r * 0.7f)
        val body = if (purple) Color.hsv(285f + hash(seed, 310 + k) * 25f, 0.55f, 0.45f) else Color(0xFF1E1A26)
        val spine = if (purple) Color.hsv(290f, 0.4f, 0.7f) else Color(0xFF3E3850)
        // Spines all round but the bottom, which sits in the sand.
        for (j in 0 until 18) {
            val a = (PI + j * PI * 1.15 / 17 - 0.07 * PI + sin(clock * 1.5 + j * 0.7 + k) * 0.06).toFloat()
            val len = r * (1.7f + hash(seed * 7 + k, 320 + j) * 0.6f)
            drawLine(spine, c, Offset(c.x + cos(a) * len, c.y + sin(a) * len), 1.8f, StrokeCap.Round)
        }
        drawCircle(body, r, c)
        drawCircle(Color.White.copy(alpha = 0.18f), r * 0.35f, Offset(c.x - r * 0.3f, c.y - r * 0.35f))
    }
}

private val FanHues = listOf(355f, 18f, 285f, 45f)

/** A soft coral sea fan: a flat branching fan, swaying, with polyps at its tips. */
internal fun DrawScope.drawSeaFan(base: Offset, seed: Int, clock: Double) {
    val hue = pickHue(seed, 330, FanHues, spread = 8f)
    val colour = Color.hsv(hue, 0.65f, 0.8f)
    val tip = Color.hsv(hue, 0.3f, 1f)
    val size = 0.8f + hash(seed, 331) * 0.6f
    val sway = sin(clock * 1.1 + hash(seed, 332) * 6).toFloat() * 5f
    rotate(sway, pivot = base) {
        fun branch(from: Offset, angle: Float, length: Float, depth: Int, salt: Int) {
            val a = angle * PI.toFloat() / 180f
            val to = Offset(from.x + sin(a) * length, from.y - cos(a) * length)
            drawLine(colour, from, to, (depth + 1) * 1.6f * size, StrokeCap.Round)
            if (depth == 0) {
                drawCircle(tip, 2.2f * size, to)
                return
            }
            val split = 18f + hash(seed, salt) * 16f
            branch(to, angle - split, length * 0.72f, depth - 1, salt * 2 + 1)
            branch(to, angle + split, length * 0.72f, depth - 1, salt * 2 + 2)
        }
        branch(base, (hash(seed, 333) - 0.5f) * 10f, 20f * size, 3, 1)
    }
}

/** A brittle star: a small disc with five long thin arms that snake about. */
internal fun DrawScope.drawBrittleStar(base: Offset, seed: Int, clock: Double) {
    val hue = pickHue(seed, 340, listOf(25f, 300f, 40f), spread = 10f)
    val colour = Color.hsv(hue, 0.4f, 0.75f)
    val disc = Offset(base.x + sin(clock * 0.12 + seed).toFloat() * 18f, base.y - 7f)
    for (k in 0 until 5) {
        val a = (-90f + k * 72f + hash(seed, 341) * 30f) * PI.toFloat() / 180f
        val wave = sin(clock * 1.6 + k * 1.3 + seed).toFloat()
        val len = 30f + hash(seed, 342 + k) * 10f
        // Arms reaching down would go into the floor; they spread along it instead.
        val dy = sin(a).coerceAtMost(0.15f)
        val end = Offset(disc.x + cos(a) * len, disc.y + dy * len * 0.6f + 4f)
        val bend = Offset(disc.x + cos(a) * len * 0.5f - dy * wave * 12f, disc.y + dy * len * 0.3f + cos(a) * wave * 10f - 2f)
        val path = Path().apply {
            moveTo(disc.x, disc.y)
            quadraticTo(bend.x, bend.y, end.x, end.y)
        }
        drawPath(path, colour, style = Stroke(width = 3f, cap = StrokeCap.Round))
        drawPath(path, Color.Black.copy(alpha = 0.25f), style = Stroke(width = 3f, pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(2f, 5f))))
    }
    drawCircle(colour, 7f, disc)
    drawCircle(Color.hsv(hue, 0.5f, 0.5f), 3f, disc)
}

/** Feather-duster worms: feathery crowns on tubes, which snap shut as Tux comes by. */
internal fun DrawScope.drawFeatherDusters(base: Offset, seed: Int, life: Life) {
    val worms = 2 + (hash(seed, 350) * 2).toInt()
    val hue = pickHue(seed, 351, listOf(20f, 300f, 340f, 50f), spread = 10f)
    for (k in 0 until worms) {
        val root = base.x + (k - (worms - 1) / 2f) * 18f
        val x = root + sin(life.clock * 0.9 + k + seed).toFloat() * 4f
        val h = 18f + hash(seed, 352 + k) * 26f
        drawLine(Color(0xFF8A7458), Offset(root, base.y + 4f), Offset(x, base.y - h), 5f, StrokeCap.Round)
        val open = 1 - smoothstep(0.2f, 0.45f, life.near(x, 280f))
        if (open < 0.05f) continue
        val crown = Offset(x, base.y - h - 2f)
        val r = 20f * open
        for (j in 0 until 11) {
            val a = (-170f + j * 16f + sin(life.clock * 1.5 + j + k).toFloat() * 3f) * PI.toFloat() / 180f
            val end = Offset(crown.x + cos(a) * r, crown.y + sin(a) * r * 1.1f)
            // Banded filaments: dark at the base, coloured, then pale at the tip.
            drawLine(Color.hsv(hue, 0.7f, 0.65f), crown, end, 2.2f, StrokeCap.Round)
            drawLine(Color.hsv(hue, 0.25f, 1f), Offset((crown.x + end.x * 2) / 3, (crown.y + end.y * 2) / 3), end, 2.2f, StrokeCap.Round)
        }
    }
}

/** A hermit crab hauling a spiral shell, ambling one way, pausing, then back. */
internal fun DrawScope.drawHermitCrab(base: Offset, seed: Int, clock: Double) {
    val phase = hash(seed, 360) * 6.0
    val x = base.x + sin(clock * 0.2 + phase).toFloat() * 44f
    val facing = if (cos(clock * 0.2 + phase) >= 0) 1f else -1f
    val moving = abs(cos(clock * 0.2 + phase)) > 0.25
    val step = if (moving) sin(clock * 10).toFloat() else 0f
    val shellHue = pickHue(seed, 361, listOf(30f, 15f, 330f), spread = 8f)
    val shellC = Offset(x - facing * 6f, base.y - 16f)
    val body = Color(0xFFD9542C)
    // Legs out of the shell's mouth.
    for (k in 0..2) {
        val hip = Offset(x + facing * (8f + k * 3f), base.y - 8f)
        val foot = Offset(hip.x + facing * (8f + k * 4f), base.y + 1f - if (k % 2 == 0) step * 3f else -step * 3f)
        drawLine(body, hip, foot, 2.6f, StrokeCap.Round)
    }
    // The shell: a cone coiled into a spiral.
    drawCircle(Color.hsv(shellHue, 0.35f, 0.85f), 16f, shellC)
    drawCircle(Color.hsv(shellHue, 0.45f, 0.65f), 16f, shellC, style = Stroke(width = 2f))
    drawPath(Path().apply {
        moveTo(shellC.x, shellC.y)
        for (t in 1..24) {
            val a = t * 0.5f
            val r = t * 0.6f
            lineTo(shellC.x + cos(a) * r * -facing, shellC.y + sin(a) * r)
        }
    }, Color.hsv(shellHue, 0.5f, 0.5f), style = Stroke(width = 2f))
    drawPath(Path().apply {
        moveTo(shellC.x - facing * 12f, shellC.y - 10f)
        lineTo(shellC.x - facing * 30f, shellC.y - 2f)
        lineTo(shellC.x - facing * 12f, shellC.y + 10f)
        close()
    }, Color.hsv(shellHue, 0.35f, 0.75f))
    // Head, big claw and eye stalks.
    val head = Offset(x + facing * 10f, base.y - 14f)
    drawCircle(body, 6f, head)
    drawOval(body, Offset(head.x + facing * 4f - 7f, head.y - 2f), Size(14f, 9f))
    for (k in 0..1) {
        val eye = Offset(head.x + facing * (3f + k * 4f), head.y - 12f)
        drawLine(body, head, eye, 1.8f)
        drawCircle(Color.Black, 2.2f, eye)
    }
}

/** A sea cucumber inching along: it stretches its front forward, then draws its back in. */
internal fun DrawScope.drawSeaCucumber(base: Offset, seed: Int, clock: Double) = scale(1.4f, pivot = base) {
    val hue = pickHue(seed, 370, listOf(20f, 0f, 35f, 320f), spread = 8f)
    val colour = Color.hsv(hue, 0.6f, 0.55f)
    val light = Color.hsv(hue, 0.45f, 0.8f)
    val cycle = (clock * 0.35 + hash(seed, 371) * 5)
    val stretch = sin(cycle * 2 * PI).toFloat()
    // It creeps forward over each cycle, then the course of creeping wraps round.
    val creep = ((cycle % 6.0) / 6.0).toFloat() * 30f - 15f
    val length = 54f + stretch * 8f
    val tall = 15f - stretch * 2f
    val left = base.x + creep - length / 2
    drawRoundRect(colour, Offset(left, base.y - tall), Size(length, tall + 2f), androidx.compose.ui.geometry.CornerRadius(tall / 2))
    // Knobbly papillae along its back.
    for (k in 0 until 7) {
        val f = (k + 0.5f) / 7f
        drawCircle(light, 2.5f, Offset(left + length * f, base.y - tall + 1f + sin(f * 9f + seed) * 1f))
    }
    // Feeding tentacles at the front, dabbing at the sand.
    val front = Offset(left, base.y - tall / 2)
    for (k in -1..1) {
        val a = (180f + k * 35f + sin(clock * 3 + k).toFloat() * 12f) * PI.toFloat() / 180f
        drawLine(light, front, Offset(front.x + cos(a) * 9f, front.y + sin(a) * 9f), 2f, StrokeCap.Round)
    }
}

/** A nudibranch: a tiny sea slug in loud colours, with a frill of tipped cerata along its back. */
internal fun DrawScope.drawNudibranch(base: Offset, seed: Int, clock: Double) = scale(1.7f, pivot = base) {
    val schemes = listOf(
        Triple(Color(0xFF8E44D9), Color(0xFFFF9A2E), Color(0xFFFFF0F0)),
        Triple(Color(0xFF2F6BFF), Color(0xFFFFE14D), Color(0xFFFFFFFF)),
        Triple(Color(0xFFFF5FA2), Color(0xFFFFFFFF), Color(0xFFFFD43B)),
        Triple(Color(0xFF14C8B4), Color(0xFFFF4D6D), Color(0xFFFFFFFF)),
    )
    val (body, tip, stripe) = schemes[(hash(seed, 380) * schemes.size).toInt().coerceAtMost(schemes.lastIndex)]
    val x = base.x + sin(clock * 0.1 + hash(seed, 381) * 6).toFloat() * 20f
    val ripple = sin(clock * 4).toFloat()
    drawOval(body, Offset(x - 16f, base.y - 9f), Size(32f, 10f))
    drawLine(stripe, Offset(x - 12f, base.y - 5f), Offset(x + 12f, base.y - 5f), 1.6f, StrokeCap.Round)
    for (k in 0 until 6) {
        val cx = x - 10f + k * 4.2f
        val sway = sin(clock * 2 + k).toFloat() * 1.5f + ripple * 0.5f
        val top = Offset(cx + sway, base.y - 16f - (if (k in 2..3) 2f else 0f))
        drawLine(body, Offset(cx, base.y - 8f), top, 3.2f, StrokeCap.Round)
        drawCircle(tip, 2f, top)
    }
    // Rhinophores, the pair of horns at the front.
    for (k in 0..1) drawLine(tip, Offset(x - 13f + k * 3f, base.y - 8f), Offset(x - 16f + k * 3f, base.y - 15f), 2f, StrokeCap.Round)
}

/**
 * A giant clam with a vivid mantle. It gapes and closes slowly, and claps shut as Tux swims over;
 * that clap is heard.
 */
internal fun DrawScope.drawClam(base: Offset, seed: Int, life: Life) {
    val near = life.near(base.x, 240f)
    if (near > 0.5f) life.cue(Cue.CLAM, seed * 31 + 1)
    val cycle = 0.6f + 0.4f * sin(life.clock * 0.5 + hash(seed, 390) * 6).toFloat()
    val open = cycle * (1 - smoothstep(0.25f, 0.5f, near))
    val w = 64f + hash(seed, 391) * 18f
    val h = w * 0.42f
    val shell = Color(0xFFCFC7B0)
    val ridge = Color(0xFF9C9480)
    val mantle = pickHue(seed, 392, listOf(190f, 160f, 270f, 45f), spread = 10f)
    val lip = base.y - h * 0.9f
    val gape = open * h * 0.65f
    // The mantle shows in the gap: rich colour with paler spots.
    if (gape > 1f) {
        drawOval(
            Brush.verticalGradient(listOf(Color.hsv(mantle, 0.8f, 0.9f), Color.hsv(mantle, 0.9f, 0.5f)), startY = lip - gape, endY = lip + 6f),
            Offset(base.x - w * 0.42f, lip - gape - 4f), Size(w * 0.84f, gape + 10f),
        )
        repeat(6) { k -> drawCircle(Color.hsv(mantle, 0.3f, 1f).copy(alpha = 0.8f), 2f, Offset(base.x - w * 0.3f + k * w * 0.12f, lip - gape * 0.5f + (k % 2) * 3f - 2f)) }
    }
    // A valve: a scalloped bowl with radial ridges; the lower one, then the upper lifted by the gape.
    fun valve(edgeY: Float, up: Boolean) {
        val dir = if (up) -1f else 1f
        val path = Path().apply {
            moveTo(base.x - w / 2, edgeY)
            val waves = 5
            for (k in 0 until waves) {
                val x0 = base.x - w / 2 + w * k / waves
                quadraticTo(x0 + w / waves / 2, edgeY - dir * 7f, x0 + w / waves, edgeY)
            }
            quadraticTo(base.x + w / 2, edgeY + dir * h * 0.9f, base.x, edgeY + dir * h * 0.9f)
            quadraticTo(base.x - w / 2, edgeY + dir * h * 0.9f, base.x - w / 2, edgeY)
            close()
        }
        drawPath(path, shell)
        for (k in 1 until 5) {
            val x0 = base.x - w / 2 + w * k / 5
            drawLine(ridge, Offset(x0, edgeY), Offset(base.x + (x0 - base.x) * 0.4f, edgeY + dir * h * 0.8f), 2f)
        }
        drawPath(path, ridge, style = Stroke(width = 1.5f))
    }
    valve(lip, up = false)
    valve(lip - gape, up = true)
}

/** A flatfish half-buried in the sand, only its eyes up, and they follow Tux. */
internal fun DrawScope.drawFlatfish(base: Offset, seed: Int, life: Life) {
    // Every so often it lifts off, shuffles along and settles again in a puff of sand.
    val cycle = ((life.clock * 0.07 + hash(seed, 403) * 7) % 1.0).toFloat()
    val hop = if (cycle > 0.9f) sin((cycle - 0.9f) / 0.1f * PI.toFloat()) else 0f
    if (cycle > 0.9f) repeat(5) { k ->
        val t = (cycle - 0.9f) / 0.1f
        drawCircle(Color(0xFFA7A08A).copy(alpha = 0.5f * (1 - t)), 3f + t * 6f, Offset(base.x - 30f + k * 15f, base.y - t * 12f))
    }
    val at = Offset(base.x + cycle.coerceAtLeast(0.9f).minus(0.9f) * 120f - 6f, base.y - hop * 22f)
    drawFlatfishAt(at, seed, life, buried = hop < 0.05f)
}

private fun DrawScope.drawFlatfishAt(base: Offset, seed: Int, life: Life, buried: Boolean) {
    val w = 56f + hash(seed, 400) * 16f
    val sand = Color(0xFF8C8670)
    // Its fin fringe rippling along the edge.
    repeat(9) { k ->
        val f = k / 8f
        drawCircle(sand, 3f, Offset(base.x - w * 0.48f + f * w * 0.96f, base.y - 1f + sin(life.clock * 5 + k).toFloat() * 1.5f))
    }
    drawOval(sand, Offset(base.x - w / 2, base.y - 9f), Size(w, 16f))
    repeat(7) { k ->
        drawCircle(Color(0xFF5E5A48).copy(alpha = 0.7f), 1.6f + hash(seed, 401 + k) * 1.6f, Offset(base.x - w * 0.4f + hash(seed, 410 + k) * w * 0.8f, base.y - 4f + hash(seed, 420 + k) * 6f))
    }
    // Sand grains heaped over its edges, while it lies buried.
    if (buried) {
        drawOval(Color(0xFFA7A08A), Offset(base.x - w * 0.6f, base.y - 3f), Size(w * 0.35f, 8f))
        drawOval(Color(0xFFA7A08A), Offset(base.x + w * 0.25f, base.y - 3f), Size(w * 0.35f, 8f))
    }
    // Blinks every few seconds.
    val blink = ((life.clock * 0.3 + hash(seed, 402) * 3) % 1.0) > 0.97
    val look = ((life.tux.x - base.x) / 300f).coerceIn(-1f, 1f) * 2.2f
    val lookUp = ((life.tux.y - base.y) / 300f).coerceIn(-1f, 1f) * 1.5f
    for (k in 0..1) {
        val eye = Offset(base.x - 8f + k * 11f, base.y - 11f - k * 2f)
        drawCircle(Color(0xFF6E6852), 5.5f, eye)
        if (blink) {
            drawLine(Color(0xFF3A3628), Offset(eye.x - 4f, eye.y), Offset(eye.x + 4f, eye.y), 2f)
        } else {
            drawCircle(Color(0xFFF0E9C8), 4.2f, eye)
            drawCircle(Color.Black, 2.2f, Offset(eye.x + look, eye.y + lookUp))
        }
    }
}

/** A lobster tucked under a rock: claws, head and long feelers poking out. */
internal fun DrawScope.drawLobster(base: Offset, seed: Int, clock: Double) {
    val red = Color(0xFFC0392B)
    val dark = Color(0xFF7A1F16)
    // It creeps out to look about, then backs under again.
    val out = (sin(clock * 0.25 + hash(seed, 435) * 6).toFloat() + 1) / 2
    val head = Offset(base.x - 6f - 26f * out, base.y - 8f)
    // Feelers, waving slowly.
    for (k in 0..1) {
        val wave = sin(clock * 1.2 + k * 1.7 + seed).toFloat()
        drawPath(Path().apply {
            moveTo(head.x, head.y - 3f)
            quadraticTo(head.x - 30f, head.y - 30f - k * 10f + wave * 8f, head.x - 60f - k * 10f, head.y - 22f - k * 18f + wave * 14f)
        }, dark, style = Stroke(width = 2f, cap = StrokeCap.Round))
    }
    // Claws held forward, opening a little now and then.
    val nip = (sin(clock * 2.5 + seed).toFloat() + 1) * 6f
    for (k in 0..1) {
        val y = head.y + 2f + k * 6f
        val claw = Offset(head.x - 26f - k * 4f, y)
        drawLine(red, Offset(head.x - 4f, y - 2f), claw, 4f, StrokeCap.Round)
        drawOval(red, Offset(claw.x - 16f, claw.y - 6f), Size(20f, 11f))
        rotate(-nip, pivot = Offset(claw.x - 6f, claw.y)) {
            drawLine(red, Offset(claw.x - 6f, claw.y - 3f), Offset(claw.x - 20f, claw.y - 6f), 4f, StrokeCap.Round)
        }
    }
    drawOval(red, Offset(head.x - 8f, head.y - 7f), Size(26f, 13f))
    drawCircle(Color.Black, 2f, Offset(head.x - 2f, head.y - 6f))
    // The rock it hides under, over its back half.
    drawOval(RockMid, Offset(base.x - 22f, base.y - 36f), Size(64f, 42f))
    drawOval(RockLight, Offset(base.x - 12f, base.y - 33f), Size(28f, 12f))
}

/** A sea spider: a speck of a body on eight very long legs, stepping slowly. */
internal fun DrawScope.drawSeaSpider(base: Offset, seed: Int, clock: Double) {
    val colour = if (hash(seed, 430) < 0.5f) Color(0xFFE0743A) else Color(0xFFE8D27A)
    val x = base.x + sin(clock * 0.1 + seed).toFloat() * 40f
    val body = Offset(x, base.y - 30f)
    for (side in listOf(-1f, 1f)) for (k in 0 until 4) {
        val lift = sin(clock * 1.4 + k * 1.6 + (if (side > 0) PI else 0.0)).toFloat().coerceAtLeast(0f)
        val hip = Offset(body.x + side * (k - 1.5f) * 2.5f, body.y)
        val reach = 22f + k * 10f
        val knee = Offset(hip.x + side * reach * 0.55f, hip.y - 26f - k * 3f - lift * 6f)
        val foot = Offset(hip.x + side * reach, base.y + 2f - lift * 6f)
        drawLine(colour, hip, knee, 2f, StrokeCap.Round)
        drawLine(colour, knee, foot, 1.6f, StrokeCap.Round)
        drawCircle(colour, 1.8f, knee)
    }
    for (k in 0 until 3) drawCircle(colour, 3f - k * 0.5f, Offset(body.x + (k - 1) * 4f, body.y))
}

/** A moray-ish eel in a crack in the rock: it pokes its head out and ducks back in when Tux nears. */
internal fun DrawScope.drawEel(base: Offset, seed: Int, life: Life) {
    val hole = Offset(base.x, base.y + 30f)
    drawOval(Color(0xFF080B12), Offset(hole.x - 14f, hole.y - 9f), Size(28f, 18f))
    val cycle = ((life.clock * 0.18 + hash(seed, 440) * 5) % 1.0).toFloat()
    val peek = smoothstep(0.05f, 0.25f, cycle) * (1 - smoothstep(0.8f, 0.95f, cycle))
    val out = peek * (1 - smoothstep(0.3f, 0.6f, life.near(hole.x, 300f)))
    if (out < 0.03f) return
    val green = if (hash(seed, 441) < 0.5f) Color(0xFF5B6E2E) else Color(0xFF7A6A3A)
    val sway = sin(life.clock * 1.3).toFloat() * 6f * out
    val head = Offset(hole.x - 18f * out + sway, hole.y - 46f * out)
    drawPath(Path().apply {
        moveTo(hole.x, hole.y)
        quadraticTo(hole.x + 8f, hole.y - 26f * out, head.x, head.y)
    }, green, style = Stroke(width = 15f, cap = StrokeCap.Round))
    // It breathes with its mouth open, as morays do.
    val gape = 3f + (sin(life.clock * 2.4).toFloat() + 1) * 2f
    drawOval(green, Offset(head.x - 14f, head.y - 7f), Size(20f, 13f))
    drawLine(Color(0xFF2A1A12), Offset(head.x - 13f, head.y + 1f), Offset(head.x - 2f, head.y + 1f + gape * 0.3f), gape * 0.6f, StrokeCap.Round)
    drawCircle(Color(0xFFE8E070), 2.4f, Offset(head.x - 6f, head.y - 3f))
    drawCircle(Color.Black, 1.2f, Offset(head.x - 6.5f, head.y - 3f))
}

/** A giant isopod, only in the deep: an armoured pill-bug the size of a shoe, trundling along. */
internal fun DrawScope.drawIsopod(base: Offset, seed: Int, clock: Double) {
    val x = base.x + sin(clock * 0.08 + hash(seed, 450) * 6).toFloat() * 40f
    val shell = Color(0xFFB8A9C2)
    val seam = Color(0xFF7C6E88)
    val walk = sin(clock * 6).toFloat()
    for (k in 0 until 7) {
        val lx = x - 22f + k * 7.5f
        drawLine(seam, Offset(lx, base.y - 4f), Offset(lx - 3f + (if (k % 2 == 0) walk else -walk) * 2f, base.y + 2f), 2f, StrokeCap.Round)
    }
    drawArc(shell, 180f, 180f, true, Offset(x - 30f, base.y - 22f), Size(60f, 40f))
    for (k in 1 until 7) {
        val sx = x - 30f + k * 60f / 7
        drawLine(seam, Offset(sx, base.y - 2f), Offset(sx + (sx - x) * 0.08f, base.y - 2f - 18f * sin(PI.toFloat() * k / 7)), 1.6f)
    }
    // Head and antennae at the front (left), tail fan at the back.
    drawOval(shell, Offset(x - 38f, base.y - 10f), Size(14f, 10f))
    drawLine(seam, Offset(x - 36f, base.y - 8f), Offset(x - 50f, base.y - 16f + walk), 1.6f, StrokeCap.Round)
    drawLine(seam, Offset(x - 36f, base.y - 6f), Offset(x - 52f, base.y - 6f), 1.6f, StrokeCap.Round)
    drawCircle(Color(0xFF2A2230), 2.4f, Offset(x - 33f, base.y - 7f))
    drawOval(shell, Offset(x + 24f, base.y - 8f), Size(14f, 8f))
}

/** An old anchor, rusting on the floor with its chain trailing off. */
internal fun DrawScope.drawAnchor(base: Offset, seed: Int) {
    val rust = Color(0xFF7A4A2A)
    val dark = Color(0xFF4A2C1A)
    rotate(-62f + hash(seed, 460) * 10f, pivot = base) {
        val top = Offset(base.x, base.y - 70f)
        drawLine(rust, Offset(base.x, base.y - 6f), top, 8f, StrokeCap.Round)
        drawLine(rust, Offset(top.x - 18f, top.y + 12f), Offset(top.x + 18f, top.y + 12f), 6f, StrokeCap.Round)
        drawCircle(rust, 7f, top, style = Stroke(width = 4f))
        drawArc(rust, 20f, 140f, false, Offset(base.x - 30f, base.y - 36f), Size(60f, 40f), style = Stroke(width = 7f, cap = StrokeCap.Round))
        for (side in listOf(-1f, 1f)) {
            val fluke = Offset(base.x + side * 28f, base.y - 22f)
            drawPath(Path().apply {
                moveTo(fluke.x, fluke.y + 6f)
                lineTo(fluke.x + side * 6f, fluke.y - 12f)
                lineTo(fluke.x - side * 8f, fluke.y - 2f)
                close()
            }, rust)
        }
        repeat(5) { k -> drawCircle(dark, 2f + hash(seed, 461 + k) * 2f, Offset(base.x + (hash(seed, 470 + k) - 0.5f) * 8f, base.y - 10f - k * 12f)) }
    }
    // The chain, lying along the floor.
    for (k in 0 until 8) {
        val c = Offset(base.x - 30f - k * 11f, base.y - 2f - sin(k * 0.9f) * 2f)
        drawOval(dark, Offset(c.x - 7f, c.y - 4f), Size(14f, 8f), style = Stroke(width = 3f))
    }
}

/**
 * A wrecked ship half sunk in the floor at [at], [width] across, tilted with the floor. Drawn before
 * the rock, so the floor buries its lower half.
 */
internal fun DrawScope.drawWreck(at: Offset, width: Float, seed: Int, light: Float) {
    val wood = lerp(Color(0xFF1A1410), Color(0xFF4A3A2A), light)
    val plank = lerp(Color(0xFF0E0A08), Color(0xFF2C2218), light)
    val tilt = -8f + hash(seed, 480) * 16f
    rotate(tilt, pivot = at) {
        val left = at.x - width / 2
        val deck = at.y - width * 0.22f
        val hull = Path().apply {
            moveTo(left - width * 0.06f, deck - width * 0.05f)
            lineTo(left + width, deck)
            quadraticTo(left + width * 0.98f, at.y + 40f, left + width * 0.85f, at.y + 60f)
            lineTo(left + width * 0.12f, at.y + 60f)
            quadraticTo(left, at.y + 20f, left - width * 0.06f, deck - width * 0.05f)
            close()
        }
        drawPath(hull, wood)
        for (k in 1..4) {
            val y = deck + k * width * 0.06f
            drawLine(plank, Offset(left + width * 0.02f, y), Offset(left + width * 0.97f, y + 4f), 3f)
        }
        // A broken hole in the side, portholes and the stump of a mast.
        drawOval(Color(0xFF05080E), Offset(left + width * 0.55f, deck + width * 0.06f), Size(width * 0.16f, width * 0.1f))
        repeat(3) { k -> drawCircle(plank, width * 0.02f, Offset(left + width * (0.15f + k * 0.12f), deck + width * 0.06f)) }
        drawLine(wood, Offset(left + width * 0.38f, deck), Offset(left + width * 0.45f, deck - width * 0.5f), width * 0.03f)
        drawLine(wood, Offset(left + width * 0.32f, deck - width * 0.3f), Offset(left + width * 0.55f, deck - width * 0.36f), width * 0.015f)
        // Torn rigging trailing from the mast.
        drawPath(Path().apply {
            moveTo(left + width * 0.45f, deck - width * 0.5f)
            quadraticTo(left + width * 0.6f, deck - width * 0.3f, left + width * 0.9f, deck)
        }, plank, style = Stroke(width = 2f))
        drawLine(wood, Offset(left + width, deck), Offset(left + width * 1.12f, deck - width * 0.12f), width * 0.02f)
    }
}
