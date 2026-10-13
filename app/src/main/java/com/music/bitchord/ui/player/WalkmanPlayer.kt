package com.music.bitchord.ui.player

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.music.bitchord.R
import com.music.bitchord.data.model.Song
import com.music.bitchord.playback.PlaybackPosition
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * The now-playing screen dressed as a portable cassette player: the tape
 * behind a smoked window, its reels turning while it plays, a three-digit
 * counter and a volume knob, and a row of mechanical keys along the bottom.
 *
 * - PLAY latches down while the music plays; pressing it again pauses.
 * - ◀◀ and ▶▶ skip a track on a tap and, held, cue through the song with the
 *   reels spinning fast, the way the keys wound tape.
 * - STOP/EJECT puts the player away.
 * - Dragging the knob up or down sets the volume.
 * - Holding the cassette opens the track's menu.
 *
 * The reels are drawn to the playhead: the tape pack moves from the left reel
 * to the right one across the song, and each reel turns at the rate its own
 * pack size gives — the empty one fast, the full one slow — as tape running
 * at a constant speed past the head would make them.
 *
 * Graphite and orange under the dark theme, silver and blue under the light.
 */
@Composable
fun WalkmanPlayer(
    song: Song,
    isPlaying: Boolean,
    position: PlaybackPosition,
    durationMs: Long,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onSeek: (Long) -> Unit,
    onEject: () -> Unit,
    onOpenSongMenu: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val finish = if (dark) WalkmanFinish.Graphite else WalkmanFinish.Silver
    val volume = remember { PlayerPlatform.host.volume }
    val volumeLevel by volume.level.collectAsState()

    // -1 rewinding, 1 fast-forwarding, 0 neither: drives the fast reels.
    var cueDirection by remember { mutableIntStateOf(0) }
    // Where cueing has wound to, ahead of the player catching up.
    var cueTarget by remember { mutableLongStateOf(-1L) }
    LaunchedEffect(song.videoId) {
        cueDirection = 0
        cueTarget = -1L
    }

    val shownMs = cueTarget.takeIf { it >= 0 } ?: position.positionMs
    val progress = if (durationMs > 0) (shownMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f

    // One shared reel angle; each reel scales it by its own pack size.
    var tapeTravel by remember { mutableFloatStateOf(0f) }
    val running = isPlaying || cueDirection != 0
    LaunchedEffect(running, cueDirection) {
        if (!running) return@LaunchedEffect
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                if (last != 0L) {
                    val dt = (now - last) / 1_000_000_000f
                    val speed = if (cueDirection != 0) cueDirection * CUE_SPEED else 1f
                    tapeTravel += dt * speed
                }
                last = now
            }
        }
    }

    Box(
        modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(finish.body)),
    ) {
        Canvas(Modifier.fillMaxSize()) { drawBrushedLines() }
        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 22.dp, vertical = 14.dp),
        ) {
            Header(finish)
            Spacer(Modifier.height(14.dp))
            CassetteWindow(
                song = song,
                finish = finish,
                progress = progress,
                tapeTravel = { tapeTravel },
                onHold = onOpenSongMenu,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1.45f),
            )
            Spacer(Modifier.height(18.dp))
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    TapeCounter(seconds = (shownMs / 1000).toInt())
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = formatTime(shownMs) + "  /  " +
                            (if (durationMs > 0) formatTime(durationMs) else "--:--"),
                        color = finish.engraving,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = FontFamily.Monospace,
                    )
                }
                VolumeKnob(
                    finish = finish,
                    level = volumeLevel,
                    onChange = { volume.set(it) },
                )
            }
            Spacer(Modifier.weight(1f))
            KeyRow(
                finish = finish,
                isPlaying = isPlaying,
                onEject = onEject,
                onPlayPause = onPlayPause,
                onRewindTap = onPrevious,
                onForwardTap = onNext,
                onCue = { direction ->
                    cueDirection = direction
                    if (direction == 0) {
                        cueTarget = -1L
                    }
                },
                onCueStep = { direction ->
                    if (durationMs > 0) {
                        val from = cueTarget.takeIf { it >= 0 } ?: position.positionMs
                        val to = (from + direction * CUE_STEP_MS).coerceIn(0L, durationMs)
                        cueTarget = to
                        onSeek(to)
                    }
                },
            )
        }
    }
}

private class WalkmanFinish(
    val body: List<Color>,
    val panel: List<Color>,
    val engraving: Color,
    val accent: Color,
    val key: List<Color>,
    val keyPressed: List<Color>,
    val keyLabel: Color,
    val knob: List<Color>,
) {
    companion object {
        val Silver = WalkmanFinish(
            body = listOf(Color(0xFFD9DCE1), Color(0xFFB9BDC4)),
            panel = listOf(Color(0xFF41618F), Color(0xFF2C4468)),
            engraving = Color(0xFF2C3440),
            accent = Color(0xFFE8622A),
            key = listOf(Color(0xFFF2F3F5), Color(0xFFC7CBD1)),
            keyPressed = listOf(Color(0xFFB4B8BF), Color(0xFFD0D3D8)),
            keyLabel = Color(0xFF2C3440),
            knob = listOf(Color(0xFF4A6A98), Color(0xFF233756)),
        )
        val Graphite = WalkmanFinish(
            body = listOf(Color(0xFF2E2F33), Color(0xFF17181A)),
            panel = listOf(Color(0xFF3A3B40), Color(0xFF232427)),
            engraving = Color(0xFFB9BCC2),
            accent = Color(0xFFF07A2E),
            key = listOf(Color(0xFF45464B), Color(0xFF2A2B2F)),
            keyPressed = listOf(Color(0xFF1E1F22), Color(0xFF2C2D31)),
            keyLabel = Color(0xFFD8DADF),
            knob = listOf(Color(0xFF55565C), Color(0xFF242528)),
        )
    }
}

// ---- Body ----

private fun DrawScope.drawBrushedLines() {
    val step = 2.dp.toPx()
    var y = 0f
    var i = 0
    while (y < size.height) {
        drawLine(
            color = if (i % 2 == 0) Color.White.copy(alpha = 0.035f) else Color.Black.copy(alpha = 0.03f),
            start = Offset(0f, y),
            end = Offset(size.width, y),
            strokeWidth = 1f,
        )
        y += step
        i++
    }
}

@Composable
private fun Header(finish: WalkmanFinish) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(Brush.verticalGradient(finish.panel))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.walkman_face_title),
            color = Color.White,
            fontSize = 15.sp,
            fontWeight = FontWeight.Black,
            fontStyle = FontStyle.Italic,
            letterSpacing = 1.sp,
            modifier = Modifier.weight(1f),
        )
        Box(
            Modifier
                .size(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(finish.accent),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = stringResource(R.string.walkman_face_subtitle),
            color = Color.White.copy(alpha = 0.75f),
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp,
        )
    }
}

// ---- The cassette ----

private val ShellColor = Color(0xFF1C1C1E)
private val LabelPaper = Color(0xFFF4ECDA)
private val LabelStripe = Color(0xFFE8622A)
private val TapeColor = Color(0xFF3A2619)
private val InkColor = Color(0xFF1F2A5A)

@Composable
private fun CassetteWindow(
    song: Song,
    finish: WalkmanFinish,
    progress: Float,
    // Read at draw time only: it changes every frame while the tape runs.
    tapeTravel: () -> Float,
    onHold: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val view = LocalView.current
    val hold by rememberUpdatedState(onHold)
    Box(
        modifier
            .shadow(8.dp, RoundedCornerShape(14.dp))
            .clip(RoundedCornerShape(14.dp))
            // The door: a smoked pane in a frame.
            .background(Brush.verticalGradient(finish.panel))
            .padding(10.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF0D0D0F))
            .padding(10.dp)
            .pointerInput(Unit) {
                detectTapGestures(
                    onLongPress = {
                        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                        hold()
                    },
                )
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            drawCassette(progress = progress, tapeTravel = tapeTravel())
            // The smoked glass over the lot, catching the light.
            drawRect(
                Brush.linearGradient(
                    0f to Color.White.copy(alpha = 0.10f),
                    0.35f to Color.White.copy(alpha = 0.02f),
                    0.36f to Color.Transparent,
                    1f to Color.Black.copy(alpha = 0.12f),
                    start = Offset.Zero,
                    end = Offset(size.width, size.height),
                ),
            )
        }
        // The label's handwriting, over the paper the canvas drew.
        Column(
            Modifier
                .fillMaxWidth()
                .padding(start = 28.dp, end = 20.dp, top = 16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "A",
                    color = ShellColor,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Black,
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = song.title,
                    color = InkColor,
                    fontSize = 19.sp,
                    fontFamily = FontFamily.Cursive,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = song.artist,
                color = InkColor.copy(alpha = 0.85f),
                fontSize = 15.sp,
                fontFamily = FontFamily.Cursive,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 24.dp),
            )
        }
    }
}

private fun DrawScope.drawCassette(progress: Float, tapeTravel: Float) {
    val w = size.width
    val h = size.height
    val corner = CornerRadius(w * 0.03f)

    // Shell.
    drawRoundRect(ShellColor, cornerRadius = corner)
    // Screw holes in the corners.
    val screw = w * 0.018f
    for (p in listOf(Offset(w * 0.04f, h * 0.06f), Offset(w * 0.96f, h * 0.06f), Offset(w * 0.04f, h * 0.94f), Offset(w * 0.96f, h * 0.94f))) {
        drawCircle(Color(0xFF3A3A3D), screw, p)
        drawLine(Color(0xFF111112), Offset(p.x - screw * 0.6f, p.y), Offset(p.x + screw * 0.6f, p.y), 1.5f)
    }

    // The label, with its coloured band.
    val labelTop = h * 0.08f
    val labelLeft = w * 0.07f
    val labelSize = Size(w * 0.86f, h * 0.66f)
    drawRoundRect(LabelPaper, Offset(labelLeft, labelTop), labelSize, CornerRadius(w * 0.015f))
    drawRect(LabelStripe, Offset(labelLeft, labelTop + labelSize.height * 0.40f), Size(labelSize.width, labelSize.height * 0.05f))
    // Ruled lines for the handwriting.
    for (i in 1..2) {
        val y = labelTop + labelSize.height * 0.16f * i + labelSize.height * 0.03f
        drawLine(Color(0xFFB9B2A3), Offset(labelLeft + w * 0.04f, y), Offset(labelLeft + labelSize.width - w * 0.04f, y), 1f)
    }

    // The tape window cut through the label.
    val winTop = labelTop + labelSize.height * 0.50f
    val winH = labelSize.height * 0.40f
    val winLeft = w * 0.2f
    val winW = w * 0.6f
    val window = RoundRect(winLeft, winTop, winLeft + winW, winTop + winH, CornerRadius(winH * 0.5f))
    val windowPath = Path().apply { addRoundRect(window) }

    val reelY = winTop + winH / 2
    val leftX = w * 0.31f
    val rightX = w * 0.69f
    val hub = winH * 0.32f
    val fullPack = w * 0.17f
    val leftPack = hub + (fullPack - hub) * (1f - progress)
    val rightPack = hub + (fullPack - hub) * progress

    clipPath(windowPath) {
        drawRect(Color(0xFF1A1410), Offset(winLeft, winTop), Size(winW, winH))
        // The tape packs, and the run of tape between them under the window.
        drawCircle(TapeColor, leftPack, Offset(leftX, reelY))
        drawCircle(TapeColor, rightPack, Offset(rightX, reelY))
        drawLine(TapeColor, Offset(leftX, reelY + leftPack), Offset(rightX, reelY + rightPack), 3f)
        // Each reel turns for the tape it takes up: the smaller, the faster.
        drawReelHub(Offset(leftX, reelY), hub, tapeTravel / leftPack * REEL_RATE)
        drawReelHub(Offset(rightX, reelY), hub, tapeTravel / rightPack * REEL_RATE)
    }
    drawRoundRect(Color(0xFF4A4A4E), Offset(winLeft, winTop), Size(winW, winH), CornerRadius(winH * 0.5f), style = Stroke(2f))

    // The bottom edge: the trapezoid with the head openings.
    val trapTop = h * 0.80f
    val trap = Path().apply {
        moveTo(w * 0.2f, h)
        lineTo(w * 0.25f, trapTop)
        lineTo(w * 0.75f, trapTop)
        lineTo(w * 0.8f, h)
        close()
    }
    drawPath(trap, Color(0xFF29292C))
    for (x in listOf(0.33f, 0.5f, 0.67f)) {
        drawRect(Color(0xFF0E0E0F), Offset(w * x - w * 0.025f, h * 0.88f), Size(w * 0.05f, h * 0.07f))
    }
    drawCircle(Color(0xFF0E0E0F), w * 0.018f, Offset(w * 0.29f, h * 0.9f))
    drawCircle(Color(0xFF0E0E0F), w * 0.018f, Offset(w * 0.71f, h * 0.9f))
}

/** The white sprocket in a reel's middle, with its six drive teeth. */
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
            )
        }
    }
}

// ---- Counter and knob ----

@Composable
private fun TapeCounter(seconds: Int) {
    val value = seconds.mod(1000)
    Row(
        Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(Color(0xFF0E0E10))
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        "%03d".format(value).forEach { digit ->
            Box(
                Modifier
                    .size(width = 20.dp, height = 28.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(
                        Brush.verticalGradient(
                            listOf(Color(0xFF2A2A2D), Color(0xFFEDEDED), Color(0xFFEDEDED), Color(0xFF2A2A2D)),
                        ),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = digit.toString(),
                    color = Color(0xFF111111),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}

@Composable
private fun VolumeKnob(
    finish: WalkmanFinish,
    level: Float,
    onChange: (Float) -> Unit,
) {
    val view = LocalView.current
    val change by rememberUpdatedState(onChange)
    val current by rememberUpdatedState(level)
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(76.dp)
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        down.consume()
                        // Followed locally: the system's answer to each set
                        // comes back late and in coarse steps.
                        var target = current
                        var lastY = down.position.y
                        var lastNotch = (target * KNOB_NOTCHES).toInt()
                        while (true) {
                            val event = awaitPointerEvent()
                            val c = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!c.pressed) break
                            val dy = lastY - c.position.y
                            lastY = c.position.y
                            target = (target + dy / (size.height * 2.5f)).coerceIn(0f, 1f)
                            val notch = (target * KNOB_NOTCHES).toInt()
                            if (notch != lastNotch) {
                                lastNotch = notch
                                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                            }
                            change(target)
                            c.consume()
                        }
                    }
                },
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val r = size.minDimension / 2f
                val c = center
                // Scale ticks round the knob, 0 to 10 over 270°.
                for (i in 0..10) {
                    val a = Math.toRadians((135 + i * 27).toDouble())
                    val dir = Offset(cos(a).toFloat(), sin(a).toFloat())
                    drawLine(
                        finish.engraving.copy(alpha = if (i % 5 == 0) 0.9f else 0.5f),
                        c + dir * (r * 0.84f),
                        c + dir * r,
                        if (i % 5 == 0) 2.5f else 1.5f,
                    )
                }
                val knobR = r * 0.72f
                drawCircle(Color.Black.copy(alpha = 0.25f), knobR + 2.dp.toPx(), c + Offset(0f, 2.dp.toPx()))
                drawCircle(Brush.verticalGradient(finish.knob, startY = c.y - knobR, endY = c.y + knobR), knobR, c)
                // Knurling round the rim.
                for (i in 0 until 36) {
                    val a = i * (2 * PI / 36)
                    val dir = Offset(cos(a).toFloat(), sin(a).toFloat())
                    drawLine(Color.Black.copy(alpha = 0.25f), c + dir * (knobR * 0.86f), c + dir * knobR, 1.5f)
                }
                // The pointer.
                val a = Math.toRadians((135 + level.coerceIn(0f, 1f) * 270).toDouble())
                val dir = Offset(cos(a).toFloat(), sin(a).toFloat())
                drawLine(finish.accent, c + dir * (knobR * 0.25f), c + dir * (knobR * 0.8f), 4f)
            }
        }
        Text(
            text = stringResource(R.string.walkman_volume),
            color = finish.engraving,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp,
        )
    }
}

// ---- Keys ----

private enum class KeyGlyph { Stop, Rewind, Play, Forward }

@Composable
private fun KeyRow(
    finish: WalkmanFinish,
    isPlaying: Boolean,
    onEject: () -> Unit,
    onPlayPause: () -> Unit,
    onRewindTap: () -> Unit,
    onForwardTap: () -> Unit,
    onCue: (Int) -> Unit,
    onCueStep: (Int) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Color.Black.copy(alpha = 0.35f))
            .padding(6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        MechanicalKey(
            glyph = KeyGlyph.Stop,
            label = stringResource(R.string.walkman_stop_eject),
            finish = finish,
            latched = false,
            onTap = onEject,
            modifier = Modifier.weight(1f),
        )
        MechanicalKey(
            glyph = KeyGlyph.Rewind,
            label = stringResource(R.string.walkman_rew),
            finish = finish,
            latched = false,
            onTap = onRewindTap,
            onHoldStart = { onCue(-1) },
            onHoldRepeat = { onCueStep(-1) },
            onHoldEnd = { onCue(0) },
            modifier = Modifier.weight(1f),
        )
        MechanicalKey(
            glyph = KeyGlyph.Play,
            label = stringResource(R.string.walkman_play),
            finish = finish,
            latched = isPlaying,
            accent = true,
            onTap = onPlayPause,
            modifier = Modifier.weight(1.3f),
        )
        MechanicalKey(
            glyph = KeyGlyph.Forward,
            label = stringResource(R.string.walkman_ff),
            finish = finish,
            latched = false,
            onTap = onForwardTap,
            onHoldStart = { onCue(1) },
            onHoldRepeat = { onCueStep(1) },
            onHoldEnd = { onCue(0) },
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun MechanicalKey(
    glyph: KeyGlyph,
    label: String,
    finish: WalkmanFinish,
    latched: Boolean,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
    accent: Boolean = false,
    onHoldStart: (() -> Unit)? = null,
    onHoldRepeat: (() -> Unit)? = null,
    onHoldEnd: (() -> Unit)? = null,
) {
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    var pressed by remember { mutableStateOf(false) }
    val tap by rememberUpdatedState(onTap)
    val holdStart by rememberUpdatedState(onHoldStart)
    val holdRepeat by rememberUpdatedState(onHoldRepeat)
    val holdEnd by rememberUpdatedState(onHoldEnd)
    val down = pressed || latched
    // How far the key sinks into the body when it's down.
    val sink = if (down) 3.dp else 0.dp

    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(64.dp)
                .pointerInput(Unit) {
                    detectTapGestures(
                        onPress = {
                            pressed = true
                            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                            var held = false
                            val job: Job? = if (holdRepeat != null) {
                                scope.launch {
                                    delay(KEY_HOLD_MS)
                                    held = true
                                    holdStart?.invoke()
                                    while (true) {
                                        holdRepeat?.invoke()
                                        delay(CUE_REPEAT_MS)
                                    }
                                }
                            } else {
                                null
                            }
                            val released = tryAwaitRelease()
                            job?.cancel()
                            pressed = false
                            if (held) {
                                holdEnd?.invoke()
                            } else if (released) {
                                tap()
                            }
                        },
                    )
                },
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .offset(y = sink)
                    .shadow(if (down) 1.dp else 4.dp, RoundedCornerShape(6.dp))
                    .clip(RoundedCornerShape(6.dp))
                    .background(Brush.verticalGradient(if (down) finish.keyPressed else finish.key)),
                contentAlignment = Alignment.Center,
            ) {
                Canvas(Modifier.size(width = 30.dp, height = 16.dp)) {
                    drawKeyGlyph(glyph, if (accent) finish.accent else finish.keyLabel)
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = label,
            color = finish.engraving,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.5.sp,
            maxLines = 1,
        )
    }
}

private fun DrawScope.drawKeyGlyph(glyph: KeyGlyph, color: Color) {
    val h = size.height
    val cx = size.width / 2
    fun triangle(x0: Float, pointRight: Boolean, w: Float) {
        val tip = if (pointRight) x0 + w else x0 - w
        drawPath(
            Path().apply {
                moveTo(x0, 0f)
                lineTo(tip, h / 2)
                lineTo(x0, h)
                close()
            },
            color,
        )
    }
    when (glyph) {
        KeyGlyph.Stop -> drawRect(color, Offset(cx - h / 2, 0f), Size(h, h))
        KeyGlyph.Play -> triangle(cx - h * 0.45f, pointRight = true, w = h * 0.95f)
        KeyGlyph.Forward -> {
            triangle(cx - h * 0.8f, pointRight = true, w = h * 0.8f)
            triangle(cx, pointRight = true, w = h * 0.8f)
        }
        KeyGlyph.Rewind -> {
            triangle(cx + h * 0.8f, pointRight = false, w = h * 0.8f)
            triangle(cx, pointRight = false, w = h * 0.8f)
        }
    }
}

private fun formatTime(ms: Long): String {
    val total = (ms.coerceAtLeast(0L) / 1000).toInt()
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

/** How many times faster the reels go while cueing. */
private const val CUE_SPEED = 10f
/** Scales tape travel into reel turns, for a believable playing speed. */
private const val REEL_RATE = 300f
private const val CUE_STEP_MS = 4_000L
private const val CUE_REPEAT_MS = 120L
private const val KEY_HOLD_MS = 400L
private const val KNOB_NOTCHES = 20
