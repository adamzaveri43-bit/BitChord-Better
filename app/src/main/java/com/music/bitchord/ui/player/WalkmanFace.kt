package com.music.bitchord.ui.player

import android.text.format.DateFormat
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.music.bitchord.data.model.Song
import com.music.bitchord.playback.PlaybackPosition
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import androidx.compose.ui.graphics.drawscope.Stroke as DrawStroke

/**
 * An always-on face drawn after the first stereo cassette Walkman: an upright
 * blue body with its top panel and orange hotline button, the cassette turned
 * on its side behind a narrow smoked window, the outlined STEREO lettering and
 * the arrow, and the brushed-silver side panel with its keys and volume slide.
 *
 * Display only — it lives on the idle screen, where any touch wakes the app.
 * What moves is what would: the reels turn while the music plays, the tape
 * passing from the bottom reel to the top across the song, each reel turning
 * at the rate its own pack gives; PLAY sits pushed in while playing; the
 * volume slide stands where the volume is. The track is written on the
 * cassette's label, and the time sits above the player, lock-screen style.
 *
 * No maker's name on it, only what the device says about itself.
 */
@Composable
fun WalkmanFace(
    song: Song,
    isPlaying: Boolean,
    position: PlaybackPosition,
    durationMs: Long,
    modifier: Modifier = Modifier,
) {
    val volume = remember { PlayerPlatform.host.volume }
    val volumeLevel by volume.level.collectAsState()

    var tapeTravel by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(isPlaying) {
        if (!isPlaying) return@LaunchedEffect
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                if (last != 0L) tapeTravel += (now - last) / 1_000_000_000f
                last = now
            }
        }
    }
    val progress = if (durationMs > 0) {
        (position.positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
    } else {
        0f
    }

    Box(
        modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 18.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Clock()
            Spacer(Modifier.height(14.dp))
            BoxWithConstraints(
                Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                val u: Dp = minOf(maxWidth / GRID_W, maxHeight / GRID_H)
                val density = LocalDensity.current
                fun units(n: Float) = with(density) { (u * n).toSp() }
                fun Modifier.at(x: Float, y: Float, w: Float, h: Float): Modifier =
                    this.offset(u * x, u * y).size(u * w, u * h)

                Box(Modifier.size(u * GRID_W, u * GRID_H)) {
                    Canvas(Modifier.fillMaxSize()) {
                        drawWalkman(
                            unit = u.toPx(),
                            progress = progress,
                            tapeTravel = tapeTravel,
                            playing = isPlaying,
                            volume = volumeLevel,
                        )
                    }
                    // ---- Printing on the top panel ----
                    Text(
                        text = "STEREO CASSETTE PLAYER",
                        color = Color.White.copy(alpha = 0.92f),
                        fontSize = units(19f),
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = units(0.6f),
                        maxLines = 1,
                        modifier = Modifier.at(36f, 196f, 420f, 30f),
                    )
                    Text(
                        text = "HEADPHONES  A    B",
                        color = Color.White.copy(alpha = 0.75f),
                        fontSize = units(11f),
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        modifier = Modifier.at(312f, 104f, 170f, 30f),
                    )
                    // ---- The big outlined STEREO ----
                    Text(
                        text = "STEREO",
                        color = Color.White.copy(alpha = 0.9f),
                        fontSize = units(84f),
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        style = TextStyle(drawStyle = DrawStroke(width = with(density) { (u * 2.4f).toPx() })),
                        modifier = Modifier.at(62f, 300f, 420f, 110f),
                    )
                    // ---- The track, written on the cassette's label ----
                    // The cassette lies on its side, so the label reads
                    // bottom to top.
                    Box(
                        Modifier
                            .at(WIN_X + WIN_W - 40f, WIN_Y + 14f, 38f, WIN_H - 28f)
                            .quarterTurn(),
                    ) {
                        Text(
                            text = song.title,
                            color = InkColor,
                            fontSize = units(27f),
                            fontFamily = FontFamily.Cursive,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = u * 10),
                        )
                    }
                    Box(
                        Modifier
                            .at(WIN_X + 4f, WIN_Y + 14f, 36f, WIN_H - 28f)
                            .quarterTurn(),
                    ) {
                        Text(
                            text = song.artist,
                            color = InkColor.copy(alpha = 0.85f),
                            fontSize = units(22f),
                            fontFamily = FontFamily.Cursive,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = u * 10),
                        )
                    }
                    // ---- Engraving on the silver panel ----
                    Text(
                        text = "STOP\nEJECT",
                        color = Engraving,
                        fontSize = units(11f),
                        lineHeight = units(12f),
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.at(486f, 492f, 60f, 32f),
                    )
                    Box(
                        Modifier
                            .at(510f, 790f, 22f, 110f)
                            .quarterTurn(),
                    ) {
                        Text(
                            text = "VOLUME",
                            color = Engraving,
                            fontSize = units(11f),
                            fontWeight = FontWeight.Bold,
                            letterSpacing = units(1f),
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

/** The time and date above the player, as a lock screen has them. */
@Composable
private fun Clock() {
    val context = LocalContext.current
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000L - System.currentTimeMillis() % 60_000L)
            now = System.currentTimeMillis()
        }
    }
    val locale = Locale.getDefault()
    val time = remember(now) {
        SimpleDateFormat(if (DateFormat.is24HourFormat(context)) "HH:mm" else "h:mm", locale).format(Date(now))
    }
    val date = remember(now) {
        SimpleDateFormat(DateFormat.getBestDateTimePattern(locale, "EEEEMMMMd"), locale).format(Date(now))
    }
    Text(
        text = date,
        color = Color.White.copy(alpha = 0.55f),
        fontSize = 15.sp,
        fontWeight = FontWeight.SemiBold,
    )
    Text(
        text = time,
        color = Color.White.copy(alpha = 0.6f),
        fontSize = 56.sp,
        lineHeight = 60.sp,
        fontWeight = FontWeight.SemiBold,
    )
}

/** Lays the content out along its box's length and turns it to read bottom to top. */
private fun Modifier.quarterTurn(): Modifier = layout { measurable, constraints ->
    val w = constraints.maxWidth
    val h = constraints.maxHeight
    val placeable = measurable.measure(Constraints(maxWidth = h, maxHeight = w))
    layout(w, h) {
        // Centred across the box, starting at its bottom once turned.
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
private val Engraving = Color(0xFF4A5059)
private val InkColor = Color(0xFF1F2A5A)
private val LabelPaper = Color(0xFFEDE6D3)
private val TapeColor = Color(0xFF3A2619)

private fun DrawScope.drawWalkman(
    unit: Float,
    progress: Float,
    tapeTravel: Float,
    playing: Boolean,
    volume: Float,
) {
    fun o(x: Float, y: Float) = Offset(x * unit, y * unit)
    fun sz(w: Float, h: Float) = Size(w * unit, h * unit)
    fun r(v: Float) = CornerRadius(v * unit)

    // ---- Orange hotline button, standing up from the top ----
    drawRoundRect(
        Brush.verticalGradient(listOf(Color(0xFFFFC23A), Color(0xFFF29100)), startY = 18 * unit, endY = 80 * unit),
        o(150f, 18f), sz(96f, 62f), r(7f),
    )
    drawRoundRect(Color(0xFFC46F00), o(150f, 18f), sz(96f, 62f), r(7f), style = Stroke(1.5f * unit))

    // ---- Body ----
    drawRoundRect(
        Brush.linearGradient(
            listOf(BlueTop, Color(0xFF28478C), BlueBottom),
            start = o(0f, 60f),
            end = o(GRID_W, GRID_H),
        ),
        o(0f, 60f), sz(GRID_W, GRID_H - 60f), r(24f),
    )
    // Top panel: a touch lighter, and its seam.
    drawRoundRect(
        Color.White.copy(alpha = 0.05f),
        o(0f, 60f), sz(GRID_W, 210f), r(24f),
    )
    drawLine(Color.Black.copy(alpha = 0.35f), o(0f, 270f), o(SILVER_X, 270f), 2.5f * unit)
    drawLine(Color.White.copy(alpha = 0.12f), o(0f, 273f), o(SILVER_X, 273f), 1.5f * unit)
    // Headphone jacks.
    for (c in listOf(o(352f, 160f), o(420f, 172f))) {
        drawCircle(Color(0xFF3E9A55), 17f * unit, c)
        drawCircle(Color(0xFF9FD8A9), 17f * unit, c, style = Stroke(2f * unit))
        drawCircle(Color(0xFF0B0E14), 9f * unit, c)
    }

    // ---- Cassette window ----
    val win = RoundRect(WIN_X * unit, WIN_Y * unit, (WIN_X + WIN_W) * unit, (WIN_Y + WIN_H) * unit, r(12f))
    drawRoundRect(Color(0xFF0A0E19), o(WIN_X - 8f, WIN_Y - 8f), sz(WIN_W + 16f, WIN_H + 16f), r(16f))
    clipPath(Path().apply { addRoundRect(win) }) {
        drawCassetteOnSide(unit, progress, tapeTravel)
        // The smoked pane over it.
        drawRect(Color(0xFF0D1A3A).copy(alpha = 0.28f), o(WIN_X, WIN_Y), sz(WIN_W, WIN_H))
        drawRect(
            Brush.linearGradient(
                0f to Color.White.copy(alpha = 0.14f),
                0.3f to Color.White.copy(alpha = 0.03f),
                0.31f to Color.Transparent,
                start = o(WIN_X, WIN_Y),
                end = o(WIN_X + WIN_W, WIN_Y + WIN_H),
            ),
            o(WIN_X, WIN_Y), sz(WIN_W, WIN_H),
        )
    }

    // ---- The arrow, the way the tape runs ----
    val arrow = Path().apply {
        moveTo(355f * unit, 905f * unit)
        lineTo(355f * unit, 820f * unit)
        lineTo(332f * unit, 820f * unit)
        lineTo(370f * unit, 760f * unit)
        lineTo(408f * unit, 820f * unit)
        lineTo(385f * unit, 820f * unit)
        lineTo(385f * unit, 905f * unit)
        close()
    }
    drawPath(arrow, Color.White.copy(alpha = 0.88f), style = Stroke(2.4f * unit, join = StrokeJoin.Miter))
    // A screw in the blue.
    drawCircle(Color(0xFF0E1630), 7f * unit, o(445f, 1000f))

    // ---- Silver side panel ----
    drawRoundRect(
        Brush.horizontalGradient(
            listOf(Color(0xFFC9CDD3), Color(0xFFE6E8EB), Color(0xFFB4B9C0), Color(0xFFD3D6DA)),
            startX = SILVER_X * unit,
            endX = GRID_W * unit,
        ),
        o(SILVER_X, 150f), sz(GRID_W - SILVER_X, GRID_H - 150f), r(20f),
    )
    // Square off its left edge where it meets the blue.
    drawRect(Color(0xFFC9CDD3), o(SILVER_X, 150f), sz(20f, GRID_H - 150f))
    drawLine(Color.Black.copy(alpha = 0.3f), o(SILVER_X, 150f), o(SILVER_X, GRID_H), 1.5f * unit)
    // Brushed grain.
    var y = 152f
    while (y < GRID_H - 4f) {
        drawLine(Color.White.copy(alpha = 0.07f), o(SILVER_X + 2f, y), o(GRID_W - 4f, y), 1f)
        y += 3f
    }

    // Microphone grille.
    drawRoundRect(Color(0xFFB9BEC5), o(482f, 200f), sz(52f, 100f), r(6f))
    drawRoundRect(Color(0xFF8C9199), o(482f, 200f), sz(52f, 100f), r(6f), style = Stroke(1.2f * unit))
    var gy = 210f
    while (gy < 295f) {
        var gx = 490f
        while (gx < 530f) {
            drawCircle(Color(0xFF6A7078), 2.2f * unit, o(gx, gy))
            gx += 8f
        }
        gy += 8f
    }

    // Keys: REW and FF small, up the right; STOP/EJECT and PLAY large.
    drawSideKey(unit, x = 542f, y = 170f, w = 52f, h = 72f, pushed = false)
    drawSideKey(unit, x = 542f, y = 250f, w = 52f, h = 72f, pushed = false)
    drawSideKey(unit, x = 482f, y = 330f, w = 54f, h = 150f, pushed = false)
    drawSideKey(unit, x = 542f, y = 340f, w = 52f, h = 150f, pushed = playing)

    // ---- Volume slide ----
    val slotTop = 560f
    val slotH = 330f
    drawRoundRect(Color(0xFF7D838B), o(548f, slotTop), sz(30f, slotH), r(5f))
    drawRoundRect(Color(0xFF4D5259), o(556f, slotTop + 6f), sz(14f, slotH - 12f), r(4f))
    for (i in 0..10) {
        val ty = slotTop + 10f + i * (slotH - 20f) / 10f
        drawLine(Engraving, o(536f, ty), o(if (i % 5 == 0) 546f else 542f, ty), 1.4f * unit)
    }
    val thumbY = slotTop + 8f + (1f - volume.coerceIn(0f, 1f)) * (slotH - 76f)
    drawRoundRect(
        Brush.horizontalGradient(listOf(Color(0xFFDDE0E4), Color(0xFF9AA0A8)), startX = 544f * unit, endX = 582f * unit),
        o(544f, thumbY), sz(38f, 60f), r(4f),
    )
    var ry = thumbY + 6f
    while (ry < thumbY + 56f) {
        drawLine(Color.Black.copy(alpha = 0.3f), o(548f, ry), o(578f, ry), 1.2f * unit)
        ry += 5f
    }
    // Tone hole near the foot.
    drawCircle(Color(0xFF3E434A), 11f * unit, o(560f, 960f))
    drawCircle(Color(0xFF15171A), 7f * unit, o(560f, 960f))
}

/** A ribbed key on the silver panel; pushed in, it sits back and darker. */
private fun DrawScope.drawSideKey(unit: Float, x: Float, y: Float, w: Float, h: Float, pushed: Boolean) {
    val dx = if (pushed) -5f else 0f
    val left = (x + dx) * unit
    drawRoundRect(Color.Black.copy(alpha = 0.25f), Offset(left + 2f * unit, (y + 3f) * unit), Size(w * unit, h * unit), CornerRadius(5f * unit))
    drawRoundRect(
        Brush.horizontalGradient(
            if (pushed) listOf(Color(0xFF9EA4AC), Color(0xFFB8BDC4)) else listOf(Color(0xFFE2E5E8), Color(0xFFAEB3BA)),
            startX = left,
            endX = left + w * unit,
        ),
        Offset(left, y * unit), Size(w * unit, h * unit), CornerRadius(5f * unit),
    )
    var ry = y + 6f
    while (ry < y + h - 4f) {
        drawLine(Color.Black.copy(alpha = 0.16f), Offset(left + 4f * unit, ry * unit), Offset(left + (w - 4f) * unit, ry * unit), 1.2f * unit)
        ry += 4.5f
    }
}

/**
 * The cassette behind the window, lying on its side: label paper round the
 * two hubs, the clear tape window between them, and the packs of tape.
 */
private fun DrawScope.drawCassetteOnSide(unit: Float, progress: Float, tapeTravel: Float) {
    val cx = (WIN_X + WIN_W / 2) * unit
    drawRect(LabelPaper, Offset(WIN_X * unit, WIN_Y * unit), Size(WIN_W * unit, WIN_H * unit))
    // The coloured band across the label.
    drawRect(Color(0xFFE8622A), Offset((WIN_X + WIN_W - 44f) * unit, WIN_Y * unit), Size(4f * unit, WIN_H * unit))

    val hub = 22f * unit
    // Kept clear of the strips either side, where the label's written on.
    val full = 56f * unit
    val topC = Offset(cx, (WIN_Y + 105f) * unit)
    val bottomC = Offset(cx, (WIN_Y + WIN_H - 105f) * unit)
    // Tape runs bottom to top, as the arrow says.
    val bottomPack = hub + (full - hub) * (1f - progress)
    val topPack = hub + (full - hub) * progress

    // The clear window in the cassette's middle, and the tape across it.
    val midTop = topC.y + 30f * unit
    val midBottom = bottomC.y - 30f * unit
    drawRoundRect(
        Color(0xFF2A211B),
        Offset(cx - 36f * unit, topC.y - 50f * unit),
        Size(72f * unit, bottomC.y - topC.y + 100f * unit),
        CornerRadius(36f * unit),
    )
    drawRect(Color(0xFFC7CCD2).copy(alpha = 0.85f), Offset(cx - 22f * unit, midTop), Size(44f * unit, midBottom - midTop))

    drawCircle(TapeColor, topPack, topC)
    drawCircle(TapeColor, bottomPack, bottomC)
    drawReelHub(topC, hub, tapeTravel / topPack * REEL_RATE * unit)
    drawReelHub(bottomC, hub, tapeTravel / bottomPack * REEL_RATE * unit)
}

/** A white sprocket with its six drive teeth, turned by [angleRad]. */
private fun DrawScope.drawReelHub(center: Offset, radius: Float, angleRad: Float) {
    drawCircle(Color(0xFFF2F2F2), radius, center)
    drawCircle(Color(0xFF1A1410), radius * 0.62f, center)
    rotate(Math.toDegrees(angleRad.toDouble()).toFloat(), center) {
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

// The face's grid: the body and its silver panel, with the hotline button
// standing above.
private const val GRID_W = 600f
private const val GRID_H = 1040f
private const val SILVER_X = 470f
private const val WIN_X = 110f
private const val WIN_Y = 440f
private const val WIN_W = 200f
private const val WIN_H = 470f
/** Scales tape travel into reel turns, for a believable playing speed. */
private const val REEL_RATE = 120f
