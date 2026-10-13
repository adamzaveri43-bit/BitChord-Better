package com.music.bitchord.ui.player

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import com.music.bitchord.data.model.Song
import com.music.bitchord.playback.PlaybackPosition
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * An idle face drawn after the first Walkman seen from the front, filling the
 * screen: the speckled blue face under its darker top band, the silver SONY,
 * the smoked window with its dotted glass, the two copper-ringed hubs and the
 * frosted panel between them, the outlined arrow, WALKMAN in silver at the
 * foot, and the brushed grey side bar down the right.
 *
 * The side bar carries what's worth having without waking the phone —
 * previous, play/pause and next, engraved into it as keys. A touch anywhere
 * else on the face wakes the app, as on every idle face.
 *
 * While the music plays the hubs turn, each at the rate its own pack of tape
 * gives, the pack (seen dimly through the smoked glass) passing from the
 * bottom hub to the top across the song; the title is written on the frosted
 * panel.
 *
 * Drawn in layers so only the hubs redraw while they turn, at 30 fps.
 */
@Composable
fun WalkmanFace(
    song: Song,
    isPlaying: Boolean,
    position: PlaybackPosition,
    durationMs: Long,
    modifier: Modifier = Modifier,
    onPlayPause: () -> Unit = {},
    onNext: () -> Unit = {},
    onPrevious: () -> Unit = {},
) {
    val progress = if (durationMs > 0) {
        (position.positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
    } else {
        0f
    }
    val currentProgress by rememberUpdatedState(progress)

    // Each hub's own angle, advanced each frame by how fast it turns at that
    // moment, so a change in pack size changes the speed and never the angle.
    var topAngle by remember { mutableFloatStateOf(0f) }
    var bottomAngle by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(isPlaying) {
        if (!isPlaying) return@LaunchedEffect
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                if (last == 0L) {
                    last = now
                } else if (now - last >= REEL_FRAME_NANOS) {
                    val dt = (now - last) / 1_000_000_000f
                    last = now
                    val p = currentProgress
                    topAngle = (topAngle + dt * degreesPerSecond(pack(p, top = true))) % 360f
                    bottomAngle = (bottomAngle + dt * degreesPerSecond(pack(p, top = false))) % 360f
                }
            }
        }
    }

    Box(
        modifier
            .fillMaxSize()
            .background(BlueDeep)
            .clipToBounds(),
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val density = LocalDensity.current
            val u: Dp = maxWidth / GRID_W
            val g = remember(maxHeight / u) { Layout(maxHeight / u) }
            fun units(n: Float) = with(density) { (u * n).toSp() }
            fun Modifier.at(x: Float, y: Float, w: Float, h: Float): Modifier =
                this.offset(u * x, u * y).size(u * w, u * h)

            // ---- Static: body, band, window, side bar, arrow ----
            Canvas(Modifier.fillMaxSize()) { drawBody(u.toPx(), g) }

            // ---- The hubs and their packs: the only part that moves ----
            Canvas(Modifier.fillMaxSize()) {
                drawHubs(u.toPx(), g, progress, topAngle, bottomAngle)
            }

            // ---- The title, written on the frosted panel ----
            Box(
                Modifier
                    .at(g.panelX + 6f, g.panelY + 8f, g.panelW - 12f, g.panelH - 16f)
                    .quarterTurn(),
                contentAlignment = Alignment.Center,
            ) {
                androidx.compose.foundation.layout.Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = song.title,
                        color = Color(0xFF2A3140),
                        fontSize = units(19f),
                        fontFamily = FontFamily.Cursive,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        text = song.artist,
                        color = Color(0xFF2A3140).copy(alpha = 0.75f),
                        fontSize = units(15f),
                        fontFamily = FontFamily.Cursive,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                    )
                }
            }

            // ---- Silver lettering ----
            Text(
                text = "SONY",
                fontSize = units(58f),
                fontFamily = SonyFont,
                fontWeight = FontWeight.Black,
                letterSpacing = units(SONY_TRACKING),
                maxLines = 1,
                textAlign = TextAlign.Center,
                style = TextStyle(brush = SilverText, shadow = LetterShadow),
                // Letter spacing trails the last letter too, so a centred word
                // sits half a space left of centre; this puts it back.
                modifier = Modifier
                    .at(0f, g.sonyY, BLUE_W, 74f)
                    .padding(start = u * SONY_TRACKING),
            )
            Text(
                text = "WALKMAN",
                fontSize = units(54f),
                fontFamily = WalkmanFont,
                letterSpacing = units(WALKMAN_TRACKING),
                maxLines = 1,
                textAlign = TextAlign.Center,
                style = TextStyle(brush = SilverText, shadow = LetterShadow),
                modifier = Modifier
                    .at(0f, g.walkmanY, BLUE_W, 74f)
                    .padding(start = u * WALKMAN_TRACKING),
            )

            // ---- Keys on the side bar ----
            SideKey(KeyGlyph.Previous, Modifier.at(SIDE_KEY_X, g.keysY, SIDE_KEY_W, SIDE_KEY_H), onPrevious)
            SideKey(
                if (isPlaying) KeyGlyph.Pause else KeyGlyph.Play,
                Modifier.at(SIDE_KEY_X, g.keysY + SIDE_KEY_STEP, SIDE_KEY_W, SIDE_KEY_H),
                onPlayPause,
            )
            SideKey(KeyGlyph.Next, Modifier.at(SIDE_KEY_X, g.keysY + SIDE_KEY_STEP * 2, SIDE_KEY_W, SIDE_KEY_H), onNext)
        }
    }
}

private enum class KeyGlyph { Previous, Play, Pause, Next }

/** A key pressed into the side bar: shaded while held, a tap passes through. */
@Composable
private fun SideKey(glyph: KeyGlyph, modifier: Modifier, onTap: () -> Unit) {
    val view = LocalView.current
    var down by remember { mutableStateOf(false) }
    val tap by rememberUpdatedState(onTap)
    Canvas(
        modifier.pointerInput(Unit) {
            detectTapGestures(
                onPress = {
                    down = true
                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    tryAwaitRelease()
                    down = false
                },
                onTap = { tap() },
            )
        },
    ) {
        val r = CornerRadius(size.width * 0.22f)
        // The recess, then the key in it.
        drawRoundRect(Color(0xFF8E9298), cornerRadius = r)
        drawRoundRect(
            Brush.verticalGradient(
                if (down) listOf(Color(0xFFB4B7BC), Color(0xFFC8CBCF)) else listOf(Color(0xFFE4E6E9), Color(0xFFBFC2C7)),
            ),
            topLeft = Offset(2f, 2f),
            size = Size(size.width - 4f, size.height - 4f),
            cornerRadius = r,
        )
        val c = center
        val s = size.width * 0.17f
        val ink = Color(0xFF4A4F57)
        fun tri(x0: Float, right: Boolean, w: Float) {
            val tip = if (right) x0 + w else x0 - w
            drawPath(
                Path().apply {
                    moveTo(x0, c.y - s)
                    lineTo(tip, c.y)
                    lineTo(x0, c.y + s)
                    close()
                },
                ink,
            )
        }
        val bar = s * 0.38f
        when (glyph) {
            KeyGlyph.Play -> tri(c.x - s * 0.7f, right = true, w = s * 1.6f)
            KeyGlyph.Pause -> {
                drawRect(ink, Offset(c.x - s * 0.8f, c.y - s), Size(bar * 1.4f, s * 2))
                drawRect(ink, Offset(c.x + s * 0.8f - bar * 1.4f, c.y - s), Size(bar * 1.4f, s * 2))
            }
            KeyGlyph.Next -> {
                tri(c.x - s * 1.1f, right = true, w = s * 1.4f)
                drawRect(ink, Offset(c.x + s * 0.3f, c.y - s), Size(bar, s * 2))
            }
            KeyGlyph.Previous -> {
                tri(c.x + s * 1.1f, right = false, w = s * 1.4f)
                drawRect(ink, Offset(c.x - s * 0.3f - bar, c.y - s), Size(bar, s * 2))
            }
        }
    }
}

/**
 * Where everything sits on a face [gridH] units tall. The reference is 646
 * tall; on a taller screen the extra goes into the top band and the spaces
 * between the lettering and the window, with the window itself taking a
 * little, so nothing is drawn out of shape.
 */
private class Layout(gridH0: Float) {
    val gridH = gridH0.coerceAtLeast(REF_H)
    private val e = gridH - REF_H
    val bandH = 98f + e * 0.08f
    val sonyY = bandH + 22f + e * 0.06f
    val winX = 82f
    val winW = 128f
    val winY = sonyY + 96f + e * 0.12f
    val winH = 318f + e * 0.32f
    val cx = winX + winW / 2f
    val topHubY = winY + 58f
    val bottomHubY = winY + winH - 58f
    val panelW = 66f
    val panelX = cx - panelW / 2f
    val panelY = topHubY + 46f
    val panelH = bottomHubY - topHubY - 92f
    val walkmanY = winY + winH + 30f + e * 0.2f
    val arrowCx = 258f
    val arrowY = winY + winH * 0.4f
    val holeY = winY + winH * 0.62f
    val keysY = winY
}

/** How far round a hub turns in a second, the tape running at a steady speed. */
private fun degreesPerSecond(radius: Float): Float = (TAPE_SPEED / radius) * (180f / PI.toFloat())

/** The radius of a hub's pack of tape: bare hub to full across the song. */
private fun pack(progress: Float, top: Boolean): Float {
    val f = if (top) progress else 1f - progress
    return HUB_R + (FULL_R - HUB_R) * f
}

/** Lays the content out along its box's length and turns it to read bottom to top. */
private fun Modifier.quarterTurn(): Modifier = layout { measurable, constraints ->
    val w = constraints.maxWidth
    val h = constraints.maxHeight
    val placeable = measurable.measure(Constraints(maxWidth = h, maxHeight = w))
    layout(w, h) {
        placeable.placeWithLayer(
            x = (w - placeable.width) / 2,
            y = (h - placeable.height) / 2,
        ) {
            rotationZ = -90f
        }
    }
}

// ---- Drawing ----

private val BlueFace = Color(0xFF2E6DB4)
private val BlueFaceLow = Color(0xFF235A9C)
private val BlueBand = Color(0xFF1E4F8E)
private val BlueDeep = Color(0xFF1B4A86)
private val SilverText = Brush.verticalGradient(
    0f to Color(0xFFF4F5F7),
    0.45f to Color(0xFFC9CDD3),
    0.55f to Color(0xFFA7ADB6),
    1f to Color(0xFFE2E5E9),
)
private val LetterShadow = Shadow(Color(0x66000000), Offset(0f, 2f), 3f)

/**
 * The wordmark's slab serif. Sony's is a reworked Clarendon; Besley is an open
 * Clarendon revival, set at its heaviest.
 */
@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
private val SonyFont = FontFamily(
    androidx.compose.ui.text.font.Font(
        com.music.bitchord.R.font.besley_variable,
        FontWeight.Black,
        variationSettings = androidx.compose.ui.text.font.FontVariation.Settings(
            androidx.compose.ui.text.font.FontVariation.weight(900),
        ),
    ),
)

/** The logo's rounded, geometric capitals: the open Righteous comes closest. */
private val WalkmanFont = FontFamily(androidx.compose.ui.text.font.Font(com.music.bitchord.R.font.righteous))

private const val SONY_TRACKING = 1.5f
private const val WALKMAN_TRACKING = 2f

private fun DrawScope.drawBody(unit: Float, g: Layout) {
    fun o(x: Float, y: Float) = Offset(x * unit, y * unit)
    fun sz(w: Float, h: Float) = Size(w * unit, h * unit)
    fun r(v: Float) = CornerRadius(v * unit)

    // The blue face, and its darker band across the top.
    drawRect(Brush.verticalGradient(listOf(BlueFace, BlueFaceLow)), Offset.Zero, sz(BLUE_W, g.gridH))
    drawRect(Brush.verticalGradient(listOf(Color(0xFF214F8C), BlueBand)), Offset.Zero, sz(BLUE_W, g.bandH))
    drawLine(Color.Black.copy(alpha = 0.3f), o(0f, g.bandH), o(BLUE_W, g.bandH), 2f * unit)
    drawLine(Color.White.copy(alpha = 0.12f), o(0f, g.bandH + 2.5f), o(BLUE_W, g.bandH + 2.5f), 1.2f * unit)
    // The fine speckle of the paint.
    val rnd = Random(7)
    val specks = List(SPECKS) { Offset(rnd.nextFloat() * BLUE_W * unit, rnd.nextFloat() * g.gridH * unit) }
    drawPoints(specks, PointMode.Points, Color.White.copy(alpha = 0.10f), strokeWidth = 1.2f * unit, cap = StrokeCap.Round)
    val darkSpecks = List(SPECKS) { Offset(rnd.nextFloat() * BLUE_W * unit, rnd.nextFloat() * g.gridH * unit) }
    drawPoints(darkSpecks, PointMode.Points, Color.Black.copy(alpha = 0.12f), strokeWidth = 1.2f * unit, cap = StrokeCap.Round)

    // ---- The window ----
    drawRoundRect(Color(0xFF0B0F16), o(g.winX - 7f, g.winY - 7f), sz(g.winW + 14f, g.winH + 14f), r(14f))
    val win = RoundRect(g.winX * unit, g.winY * unit, (g.winX + g.winW) * unit, (g.winY + g.winH) * unit, r(10f))
    clipPath(Path().apply { addRoundRect(win) }) {
        drawRect(
            Brush.verticalGradient(listOf(Color(0xFF2A3038), Color(0xFF151A21))),
            o(g.winX, g.winY), sz(g.winW, g.winH),
        )
        // The dotted texture of the glass.
        val dots = ArrayList<Offset>()
        var y = g.winY + 4f
        while (y < g.winY + g.winH) {
            var x = g.winX + 4f
            while (x < g.winX + g.winW) {
                dots += o(x, y)
                x += 6f
            }
            y += 6f
        }
        drawPoints(dots, PointMode.Points, Color.White.copy(alpha = 0.07f), strokeWidth = 1.6f * unit, cap = StrokeCap.Round)
        // The frosted panel between the hubs.
        drawRoundRect(Color.Black.copy(alpha = 0.35f), o(g.panelX + 2f, g.panelY + 3f), sz(g.panelW, g.panelH), r(6f))
        drawRoundRect(
            Brush.linearGradient(
                listOf(Color(0xFFF3F4F6), Color(0xFFD6D9DD), Color(0xFFBFC3C8)),
                start = o(g.panelX, g.panelY),
                end = o(g.panelX + g.panelW, g.panelY + g.panelH),
            ),
            o(g.panelX, g.panelY), sz(g.panelW, g.panelH), r(6f),
        )
        // A soft sheen down the glass.
        drawRect(
            Brush.linearGradient(
                0f to Color.White.copy(alpha = 0.10f),
                0.35f to Color.White.copy(alpha = 0.02f),
                0.36f to Color.Transparent,
                start = o(g.winX, g.winY),
                end = o(g.winX + g.winW, g.winY + g.winH),
            ),
            o(g.winX, g.winY), sz(g.winW, g.winH),
        )
    }

    // ---- The arrow ----
    val ax = g.arrowCx
    val top = g.arrowY
    val bottom = top + 84f
    val neck = top + 34f
    val arrow = Path().apply {
        moveTo((ax - 7f) * unit, bottom * unit)
        lineTo((ax - 7f) * unit, neck * unit)
        lineTo((ax - 18f) * unit, neck * unit)
        lineTo(ax * unit, top * unit)
        lineTo((ax + 18f) * unit, neck * unit)
        lineTo((ax + 7f) * unit, neck * unit)
        lineTo((ax + 7f) * unit, bottom * unit)
        close()
    }
    drawPath(arrow, Color.White.copy(alpha = 0.92f), style = Stroke(2.2f * unit, join = StrokeJoin.Miter))

    // ---- The grey side bar ----
    drawRect(
        Brush.horizontalGradient(
            listOf(Color(0xFFB9BCC1), Color(0xFFDADCDF), Color(0xFFCFD2D6), Color(0xFFB4B8BD)),
            startX = BLUE_W * unit,
            endX = GRID_W * unit,
        ),
        o(BLUE_W, 0f), sz(GRID_W - BLUE_W, g.gridH),
    )
    drawLine(Color.Black.copy(alpha = 0.35f), o(BLUE_W, 0f), o(BLUE_W, g.gridH), 1.6f * unit)
    val greySpecks = List(SPECKS / 3) {
        Offset((BLUE_W + rnd.nextFloat() * (GRID_W - BLUE_W)) * unit, rnd.nextFloat() * g.gridH * unit)
    }
    drawPoints(greySpecks, PointMode.Points, Color.Black.copy(alpha = 0.10f), strokeWidth = 1.1f * unit, cap = StrokeCap.Round)
    // The little round button low on the bar.
    val hole = o(SIDE_KEY_X + SIDE_KEY_W / 2f, g.holeY + SIDE_KEY_STEP * 1.2f)
    drawCircle(Color(0xFF8E9298), 9f * unit, hole)
    drawCircle(Brush.verticalGradient(listOf(Color(0xFFE6E8EB), Color(0xFFAFB3B9)), startY = hole.y - 7f * unit, endY = hole.y + 7f * unit), 7f * unit, hole)
}

/** The packs of tape, dim behind the smoked glass, and the two turning hubs. */
private fun DrawScope.drawHubs(unit: Float, g: Layout, progress: Float, topAngle: Float, bottomAngle: Float) {
    val win = RoundRect(g.winX * unit, g.winY * unit, (g.winX + g.winW) * unit, (g.winY + g.winH) * unit, CornerRadius(10f * unit))
    val top = Offset(g.cx * unit, g.topHubY * unit)
    val bottom = Offset(g.cx * unit, g.bottomHubY * unit)
    clipPath(Path().apply { addRoundRect(win) }) {
        drawCircle(Color(0xFF2E2017).copy(alpha = 0.85f), pack(progress, top = true) * unit, top)
        drawCircle(Color(0xFF2E2017).copy(alpha = 0.85f), pack(progress, top = false) * unit, bottom)
    }
    drawHub(top, unit, topAngle)
    drawHub(bottom, unit, bottomAngle)
}

/** A copper-ringed hub with its three spokes, turned by [angleDeg]. */
private fun DrawScope.drawHub(center: Offset, unit: Float, angleDeg: Float) {
    val ring = 17f * unit
    drawCircle(Color(0xFF1C1712), ring + 2f * unit, center)
    drawCircle(Color(0xFFA9774B), ring, center, style = Stroke(2.6f * unit))
    drawCircle(Brush.verticalGradient(listOf(Color(0xFFE9E3D8), Color(0xFFB9B1A3)), startY = center.y - ring, endY = center.y + ring), ring - 3f * unit, center)
    rotate(angleDeg, center) {
        for (i in 0 until 3) {
            val a = (i * 2 * PI / 3 - PI / 2).toFloat()
            val dir = Offset(cos(a), sin(a))
            drawLine(
                Color(0xFF26211C),
                start = center,
                end = center + dir * (ring - 5f * unit),
                strokeWidth = 3.2f * unit,
                cap = StrokeCap.Round,
            )
        }
    }
    drawCircle(Color(0xFF26211C), 2.6f * unit, center)
}

// The reference's own grid: 410 wide, the blue face 335 of it.
private const val GRID_W = 410f
private const val BLUE_W = 335f
private const val REF_H = 646f
private const val SIDE_KEY_X = 349f
private const val SIDE_KEY_W = 48f
private const val SIDE_KEY_H = 74f
private const val SIDE_KEY_STEP = 92f
/**
 * A pack's radius runs from just past the hub's copper ring, so an all-but-empty
 * reel still shows its thin ring of tape, to nearly the window's edge when full.
 */
private const val HUB_R = 23f
private const val FULL_R = 42f
private const val SPECKS = 1400
/** Tape speed in grid units a second: about half a turn a second off a bare hub. */
private const val TAPE_SPEED = 55f
/** 30 fps: plenty for a turning hub, on a screen left on for hours. */
private const val REEL_FRAME_NANOS = 33_000_000L
