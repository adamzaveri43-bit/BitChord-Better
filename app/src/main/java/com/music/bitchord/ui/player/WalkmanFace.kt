package com.music.bitchord.ui.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import com.music.bitchord.data.model.Song
import com.music.bitchord.playback.PlaybackPosition
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * An idle face drawn after the front of the first stereo cassette Walkman,
 * the TPS-L2, filling the screen.
 *
 * Only the front: the blue face with its top panel — the maker's name, the
 * model line, the two headphone jacks and the orange hotline button along the
 * top edge — the outlined STEREO, the cassette on its side behind its smoked
 * window, and the arrow. The silver side panel, with its keys, microphone and
 * volume slide, is the device's side rather than its front, and on a face
 * that's only looked at they would be nothing but clutter; leaving them off
 * gives the window the room.
 *
 * Display only: on the idle screen any touch wakes the app. The reels turn
 * while the music plays, the tape passing from the bottom reel to the top
 * across the song, each turning at the rate its own pack gives for tape
 * running at a steady speed; the track is written along the cassette's label.
 *
 * Drawn in layers so that only the reels redraw while they turn — the body,
 * the printing and the glass are drawn once — and the reels update at 30 fps,
 * which is all a turning sprocket needs, on a screen left on for hours.
 */
@Composable
fun WalkmanFace(
    song: Song,
    isPlaying: Boolean,
    position: PlaybackPosition,
    durationMs: Long,
    modifier: Modifier = Modifier,
) {
    val progress = if (durationMs > 0) {
        (position.positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
    } else {
        0f
    }
    val currentProgress by rememberUpdatedState(progress)

    // Each reel's own angle, advanced a little each frame by how fast that
    // reel turns at that moment. (Working the angle out from the tape played
    // so far over the reel's current size made the whole angle jump every
    // time the size moved.)
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
                    topAngle = (topAngle + dt * reelDegreesPerSecond(packRadius(p, top = true))) % 360f
                    bottomAngle = (bottomAngle + dt * reelDegreesPerSecond(packRadius(p, top = false))) % 360f
                }
            }
        }
    }

    Box(
        modifier
            .fillMaxSize()
            .background(BlueBottom)
            .clipToBounds(),
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val density = LocalDensity.current
            // Drawn to the width; the face runs the full height, with the
            // window centred in whatever room is below the lettering.
            val u: Dp = maxWidth / GRID_W
            val gridH = (maxHeight / u).coerceAtLeast(MIN_GRID_H)
            val geo = remember(gridH) { WalkmanGeometry(gridH) }
            fun units(n: Float) = with(density) { (u * n).toSp() }
            fun Modifier.at(x: Float, y: Float, w: Float, h: Float): Modifier =
                this.offset(u * x, u * y).size(u * w, u * h)

            // ---- Static: body, panel, window frame, the cassette's paper ----
            Canvas(Modifier.fillMaxSize()) { drawBodyAndPaper(u.toPx(), geo) }

            // ---- The only part that moves ----
            Canvas(Modifier.fillMaxSize()) {
                drawReels(u.toPx(), geo, progress, topAngle, bottomAngle)
            }

            // ---- The track, written along the cassette's label ----
            Box(
                Modifier
                    .at(geo.winX + geo.winW - 42f, geo.winY + 16f, 38f, geo.winH - 32f)
                    .quarterTurn(),
            ) {
                Text(
                    text = song.title,
                    color = InkColor,
                    fontSize = units(28f),
                    fontFamily = FontFamily.Cursive,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = u * 10),
                )
            }
            Box(
                Modifier
                    .at(geo.winX + 4f, geo.winY + 16f, 38f, geo.winH - 32f)
                    .quarterTurn(),
            ) {
                Text(
                    text = song.artist,
                    color = InkColor.copy(alpha = 0.85f),
                    fontSize = units(23f),
                    fontFamily = FontFamily.Cursive,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = u * 10),
                )
            }

            // ---- The glass over the window, and the arrow ----
            Canvas(Modifier.fillMaxSize()) { drawGlassAndArrow(u.toPx(), geo) }

            // ---- Printing ----
            Text(
                text = "SONY",
                color = Color.White,
                fontSize = units(74f),
                fontFamily = FontFamily.Serif,
                fontWeight = FontWeight.Bold,
                letterSpacing = units(5f),
                maxLines = 1,
                modifier = Modifier.at(40f, 78f, 330f, 96f),
            )
            Text(
                text = "STEREO CASSETTE PLAYER  TPS-L2",
                color = Color.White.copy(alpha = 0.9f),
                fontSize = units(19f),
                fontWeight = FontWeight.SemiBold,
                letterSpacing = units(0.6f),
                maxLines = 1,
                modifier = Modifier.at(42f, 186f, 520f, 30f),
            )
            Text(
                text = "HEADPHONES",
                color = Color.White.copy(alpha = 0.75f),
                fontSize = units(12f),
                fontWeight = FontWeight.Bold,
                letterSpacing = units(1f),
                maxLines = 1,
                modifier = Modifier.at(418f, 58f, 160f, 28f),
            )
            Text(
                text = "A",
                color = Color.White.copy(alpha = 0.75f),
                fontSize = units(12f),
                fontWeight = FontWeight.Bold,
                modifier = Modifier.at(437f, 86f, 24f, 26f),
            )
            Text(
                text = "B",
                color = Color.White.copy(alpha = 0.75f),
                fontSize = units(12f),
                fontWeight = FontWeight.Bold,
                modifier = Modifier.at(517f, 98f, 24f, 26f),
            )
            Text(
                text = "STEREO",
                color = Color.White.copy(alpha = 0.92f),
                fontSize = units(100f),
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                style = TextStyle(drawStyle = Stroke(width = with(density) { (u * 2.6f).toPx() })),
                modifier = Modifier.at(84f, STEREO_Y, 480f, 130f),
            )
        }
    }
}

/**
 * Where everything goes for a face [gridH] units tall: the window takes the
 * room below the lettering, as large as the cassette's proportions allow,
 * centred in it.
 */
private class WalkmanGeometry(val gridH: Float) {
    private val roomTop = STEREO_Y + 140f
    private val roomBottom = gridH - 50f
    private val room = (roomBottom - roomTop).coerceAtLeast(300f)
    val winH = minOf(room - 40f, MAX_WIN_W / WIN_ASPECT)
    val winW = winH * WIN_ASPECT
    val winX = 104f
    val winY = roomTop + (room - winH) / 2f
    val cx = winX + winW / 2f
    val topY = winY + winH * 0.24f
    val bottomY = winY + winH * 0.76f
    val hub = winW * 0.10f
    val full = winW * 0.26f
}

/** The cassette's window is a little under half as wide as it's tall. */
private const val WIN_ASPECT = 0.43f
private const val MAX_WIN_W = 300f

/** How far round a reel of [radius] turns in a second, with the tape running at a steady speed. */
private fun reelDegreesPerSecond(radius: Float): Float =
    (TAPE_SPEED / radius.coerceAtLeast(1f)) * (180f / PI.toFloat())

/** A reel's pack, as a fraction of the way from bare hub to full, in window-width units. */
private fun packRadius(progress: Float, top: Boolean): Float {
    // Measured against a reference window so the turning rate doesn't depend
    // on the screen; the drawing scales the same fraction to the real window.
    val hub = REF_WIN_W * 0.10f
    val full = REF_WIN_W * 0.26f
    val f = if (top) progress else 1f - progress
    return hub + (full - hub) * f
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

private val BlueTop = Color(0xFF34569F)
private val BlueBottom = Color(0xFF1D3778)
private val InkColor = Color(0xFF1F2A5A)
private val LabelPaper = Color(0xFFEDE6D3)
private val TapeColor = Color(0xFF3A2619)

private fun DrawScope.drawBodyAndPaper(unit: Float, g: WalkmanGeometry) {
    fun o(x: Float, y: Float) = Offset(x * unit, y * unit)
    fun sz(w: Float, h: Float) = Size(w * unit, h * unit)
    fun r(v: Float) = CornerRadius(v * unit)

    // The blue face.
    drawRect(
        Brush.linearGradient(
            listOf(BlueTop, Color(0xFF28478C), BlueBottom),
            start = Offset.Zero,
            end = o(GRID_W, g.gridH),
        ),
    )
    // Top panel: a touch lighter, and the seam under it.
    drawRect(Color.White.copy(alpha = 0.05f), Offset.Zero, sz(GRID_W, PANEL_H))
    drawLine(Color.Black.copy(alpha = 0.35f), o(0f, PANEL_H), o(GRID_W, PANEL_H), 2.5f * unit)
    drawLine(Color.White.copy(alpha = 0.12f), o(0f, PANEL_H + 3f), o(GRID_W, PANEL_H + 3f), 1.5f * unit)

    // The orange hotline button, along the top edge.
    drawRoundRect(
        Brush.verticalGradient(listOf(Color(0xFFF29100), Color(0xFFFFC23A)), startY = 0f, endY = 34f * unit),
        o(176f, -10f), sz(110f, 44f), r(8f),
    )
    drawRoundRect(Color(0xFFC46F00), o(176f, -10f), sz(110f, 44f), r(8f), style = Stroke(1.5f * unit))

    // Headphone jacks.
    for (c in listOf(o(445f, 140f), o(525f, 152f))) {
        drawCircle(Color(0xFF3E9A55), 18f * unit, c)
        drawCircle(Color(0xFF9FD8A9), 18f * unit, c, style = Stroke(2f * unit))
        drawCircle(Color(0xFF0B0E14), 9.5f * unit, c)
    }

    // The window's frame, and the cassette's label paper behind the glass.
    drawRoundRect(Color(0xFF0A0E19), o(g.winX - 9f, g.winY - 9f), sz(g.winW + 18f, g.winH + 18f), r(16f))
    val win = RoundRect(g.winX * unit, g.winY * unit, (g.winX + g.winW) * unit, (g.winY + g.winH) * unit, r(12f))
    clipPath(Path().apply { addRoundRect(win) }) {
        drawRect(LabelPaper, o(g.winX, g.winY), sz(g.winW, g.winH))
        // The coloured band along the label.
        drawRect(Color(0xFFE8622A), o(g.winX + g.winW - 48f, g.winY), sz(4f, g.winH))
        // The cassette's own clear window, between the hubs.
        drawRoundRect(
            Color(0xFF2A211B),
            o(g.cx - g.winW * 0.18f, g.topY - g.full * 0.75f),
            sz(g.winW * 0.36f, g.bottomY - g.topY + g.full * 1.5f),
            r(g.winW * 0.18f),
        )
        drawRect(
            Color(0xFFC7CCD2).copy(alpha = 0.85f),
            o(g.cx - g.winW * 0.11f, g.topY + g.hub * 1.4f),
            sz(g.winW * 0.22f, g.bottomY - g.topY - g.hub * 2.8f),
        )
    }
}

/** The tape packs and the two hubs: the only things that change while it plays. */
private fun DrawScope.drawReels(
    unit: Float,
    g: WalkmanGeometry,
    progress: Float,
    topAngle: Float,
    bottomAngle: Float,
) {
    val win = RoundRect(g.winX * unit, g.winY * unit, (g.winX + g.winW) * unit, (g.winY + g.winH) * unit, CornerRadius(12f * unit))
    val scale = g.winW / REF_WIN_W
    val topC = Offset(g.cx * unit, g.topY * unit)
    val bottomC = Offset(g.cx * unit, g.bottomY * unit)
    clipPath(Path().apply { addRoundRect(win) }) {
        drawCircle(TapeColor, packRadius(progress, top = true) * scale * unit, topC)
        drawCircle(TapeColor, packRadius(progress, top = false) * scale * unit, bottomC)
        drawReelHub(topC, g.hub * unit, topAngle)
        drawReelHub(bottomC, g.hub * unit, bottomAngle)
    }
}

/** The smoked pane over the window, and the outlined arrow beside it. */
private fun DrawScope.drawGlassAndArrow(unit: Float, g: WalkmanGeometry) {
    fun o(x: Float, y: Float) = Offset(x * unit, y * unit)
    fun sz(w: Float, h: Float) = Size(w * unit, h * unit)
    val win = RoundRect(g.winX * unit, g.winY * unit, (g.winX + g.winW) * unit, (g.winY + g.winH) * unit, CornerRadius(12f * unit))
    clipPath(Path().apply { addRoundRect(win) }) {
        drawRect(Color(0xFF0D1A3A).copy(alpha = 0.26f), o(g.winX, g.winY), sz(g.winW, g.winH))
        drawRect(
            Brush.linearGradient(
                0f to Color.White.copy(alpha = 0.14f),
                0.3f to Color.White.copy(alpha = 0.03f),
                0.31f to Color.Transparent,
                start = o(g.winX, g.winY),
                end = o(g.winX + g.winW, g.winY + g.winH),
            ),
            o(g.winX, g.winY), sz(g.winW, g.winH),
        )
    }
    // The arrow: the way the tape runs, beside the lower half of the window.
    val ax = g.winX + g.winW + 70f
    val bottom = g.winY + g.winH * 0.88f
    val tip = g.winY + g.winH * 0.58f
    val shaft = 15f
    val head = 38f
    val neck = tip + 62f
    val arrow = Path().apply {
        moveTo((ax - shaft) * unit, bottom * unit)
        lineTo((ax - shaft) * unit, neck * unit)
        lineTo((ax - head) * unit, neck * unit)
        lineTo(ax * unit, tip * unit)
        lineTo((ax + head) * unit, neck * unit)
        lineTo((ax + shaft) * unit, neck * unit)
        lineTo((ax + shaft) * unit, bottom * unit)
        close()
    }
    drawPath(arrow, Color.White.copy(alpha = 0.88f), style = Stroke(2.6f * unit, join = StrokeJoin.Miter))
    // The small hole beside the window.
    drawCircle(Color(0xFF0E1630), 7f * unit, o(ax + 20f, g.winY + g.winH * 0.42f))
}

/** A white sprocket with its six drive teeth, turned by [angleDeg]. */
private fun DrawScope.drawReelHub(center: Offset, radius: Float, angleDeg: Float) {
    drawCircle(Color(0xFFF2F2F2), radius, center)
    drawCircle(Color(0xFF1A1410), radius * 0.62f, center)
    rotate(angleDeg, center) {
        for (i in 0 until 6) {
            val a = (i * PI / 3).toFloat()
            val dir = Offset(cos(a), sin(a))
            drawLine(
                color = Color(0xFFF2F2F2),
                start = center + dir * (radius * 0.35f),
                end = center + dir * (radius * 0.66f),
                strokeWidth = radius * 0.16f,
                cap = StrokeCap.Butt,
            )
        }
    }
}

private const val GRID_W = 600f
private const val MIN_GRID_H = 1000f
private const val PANEL_H = 250f
private const val STEREO_Y = 300f
/** The window width the turning rates are worked out against. */
private const val REF_WIN_W = 250f
/** Tape speed, in reference units a second: about half a turn a second off a bare hub. */
private const val TAPE_SPEED = 80f
/** 30 fps: plenty for a turning sprocket, and a third of the work at 90 Hz. */
private const val REEL_FRAME_NANOS = 33_000_000L
