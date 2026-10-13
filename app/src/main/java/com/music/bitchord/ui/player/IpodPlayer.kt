package com.music.bitchord.ui.player

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.music.bitchord.R
import com.music.bitchord.data.model.Song
import com.music.bitchord.playback.PlaybackPosition
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.min

/**
 * The now-playing screen dressed as a click-wheel iPod: a little LCD up top
 * with the track, its artwork and a glossy blue progress bar, and the wheel
 * underneath doing what the wheel did.
 *
 * - Turning it changes the volume, one detent at a time.
 * - A tap on the centre button switches the turn to scrubbing, and the bar
 *   grows its diamond; it drops back to volume after a few idle seconds.
 * - Holding the centre button opens the track's menu.
 * - MENU puts the player away, the arrows skip, the bottom plays and pauses.
 *
 * The finish follows the app's theme: black in the dark, white in the light,
 * as the two colours the thing was actually sold in.
 */
@Composable
fun IpodPlayer(
    song: Song,
    isPlaying: Boolean,
    position: PlaybackPosition,
    durationMs: Long,
    queueIndex: Int,
    queueSize: Int,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onSeek: (Long) -> Unit,
    onMenu: () -> Unit,
    onOpenSongMenu: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val finish = if (dark) IpodFinish.Black else IpodFinish.White
    val volume = remember { PlayerPlatform.host.volume }
    val volumeLevel by volume.level.collectAsState()

    var mode by remember { mutableStateOf(WheelMode.Volume) }
    // When the wheel last moved, so the volume bar can stand in for the
    // progress bar for a moment and the scrubber can time itself out.
    var lastTurnAt by remember { mutableLongStateOf(0L) }
    var volumeTurnAt by remember { mutableLongStateOf(0L) }
    var showVolume by remember { mutableStateOf(false) }
    // Where the wheel has turned the volume to. Kept here rather than read
    // back from the system each detent: the stream has only 15-odd steps, so a
    // detent is less than one of them, and the system's answer arrives late.
    var volumeTarget by remember { mutableStateOf<Float?>(null) }
    // Where the scrubber has been turned to, ahead of the player catching up.
    var scrubTarget by remember { mutableLongStateOf(-1L) }

    LaunchedEffect(volumeTurnAt) {
        if (volumeTurnAt == 0L) return@LaunchedEffect
        showVolume = true
        delay(VOLUME_SHOWN_MS)
        showVolume = false
        volumeTarget = null
    }
    LaunchedEffect(lastTurnAt, mode) {
        if (mode != WheelMode.Scrub) return@LaunchedEffect
        delay(SCRUB_IDLE_MS)
        mode = WheelMode.Volume
        scrubTarget = -1L
    }
    LaunchedEffect(song.videoId) {
        mode = WheelMode.Volume
        scrubTarget = -1L
    }

    Box(
        modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(finish.body)),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            IpodScreen(
                song = song,
                isPlaying = isPlaying,
                position = position,
                durationMs = durationMs,
                queueIndex = queueIndex,
                queueSize = queueSize,
                mode = mode,
                showVolume = showVolume && mode == WheelMode.Volume,
                volumeLevel = volumeTarget ?: volumeLevel,
                scrubTarget = scrubTarget,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(SCREEN_ASPECT),
            )
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                ClickWheel(
                    finish = finish,
                    onMenu = onMenu,
                    onNext = onNext,
                    onPrevious = onPrevious,
                    onPlayPause = onPlayPause,
                    onCenter = {
                        showVolume = false
                        mode = if (mode == WheelMode.Volume && durationMs > 0) {
                            WheelMode.Scrub
                        } else {
                            WheelMode.Volume
                        }
                        scrubTarget = -1L
                        lastTurnAt = System.nanoTime()
                    },
                    onCenterHold = onOpenSongMenu,
                    onTurn = { steps ->
                        lastTurnAt = System.nanoTime()
                        when (mode) {
                            WheelMode.Volume -> {
                                volumeTurnAt = lastTurnAt
                                val to = ((volumeTarget ?: volume.level.value) + steps * VOLUME_STEP).coerceIn(0f, 1f)
                                volumeTarget = to
                                volume.set(to)
                            }
                            WheelMode.Scrub -> if (durationMs > 0) {
                                val from = scrubTarget.takeIf { it >= 0 } ?: position.positionMs
                                val to = (from + steps * SCRUB_STEP_MS).coerceIn(0L, durationMs)
                                scrubTarget = to
                                onSeek(to)
                            }
                        }
                    },
                    modifier = Modifier
                        .fillMaxHeight(0.92f)
                        .aspectRatio(1f, matchHeightConstraintsFirst = true),
                )
            }
        }
    }
}

private enum class WheelMode { Volume, Scrub }

private class IpodFinish(
    val body: List<Color>,
    val wheel: List<Color>,
    val wheelEdge: Color,
    val label: Color,
    val center: List<Color>,
    val centerEdge: Color,
) {
    companion object {
        val White = IpodFinish(
            body = listOf(Color(0xFFF7F7F8), Color(0xFFE4E4E7)),
            wheel = listOf(Color(0xFFFFFFFF), Color(0xFFF1F1F3)),
            wheelEdge = Color(0xFFCDCDD2),
            label = Color(0xFFA8A9AE),
            center = listOf(Color(0xFFF4F4F6), Color(0xFFFFFFFF)),
            centerEdge = Color(0xFFCFCFD4),
        )
        val Black = IpodFinish(
            body = listOf(Color(0xFF2A2A2D), Color(0xFF0E0E10)),
            wheel = listOf(Color(0xFF38383C), Color(0xFF242427)),
            wheelEdge = Color(0xFF4A4A4F),
            label = Color(0xFFDADADF),
            center = listOf(Color(0xFF151517), Color(0xFF242427)),
            centerEdge = Color(0xFF4A4A4F),
        )
    }
}

// ---- The LCD ----

private val LcdText = Color(0xFF111111)
private val LcdSubtext = Color(0xFF4A4D52)

@Composable
private fun IpodScreen(
    song: Song,
    isPlaying: Boolean,
    position: PlaybackPosition,
    durationMs: Long,
    queueIndex: Int,
    queueSize: Int,
    mode: WheelMode,
    showVolume: Boolean,
    volumeLevel: Float,
    scrubTarget: Long,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .shadow(6.dp, RoundedCornerShape(10.dp))
            .clip(RoundedCornerShape(10.dp))
            .background(Color.Black)
            .padding(4.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(Brush.verticalGradient(listOf(Color.White, Color(0xFFE9EDF2)))),
    ) {
        TitleBar(isPlaying)
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
        ) {
            Text(
                text = if (queueSize > 0) {
                    stringResource(R.string.ipod_track_of, queueIndex + 1, queueSize)
                } else {
                    ""
                },
                fontSize = 11.sp,
                color = LcdSubtext,
                maxLines = 1,
            )
            Spacer(Modifier.height(6.dp))
            Row(
                Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ReflectedArtwork(
                    url = song.thumbnailUrl,
                    modifier = Modifier
                        .fillMaxHeight()
                        .aspectRatio(1f / (1f + REFLECTION)),
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = song.title,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = LcdText,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        lineHeight = 18.sp,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = song.artist,
                        fontSize = 13.sp,
                        color = LcdText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    song.albumName?.takeIf(String::isNotBlank)?.let { album ->
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = album,
                            fontSize = 13.sp,
                            color = LcdSubtext,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            if (showVolume) {
                VolumeBar(volumeLevel)
                Spacer(Modifier.height(17.dp))
            } else {
                val shownMs = scrubTarget.takeIf { it >= 0 && mode == WheelMode.Scrub }
                    ?: position.positionMs
                val fraction = if (durationMs > 0) (shownMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
                GlossBar(fraction = fraction, diamond = mode == WheelMode.Scrub)
                Spacer(Modifier.height(3.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(formatClock(shownMs), fontSize = 11.sp, color = LcdText, fontWeight = FontWeight.SemiBold)
                    Text(
                        if (durationMs > 0) "-" + formatClock(durationMs - shownMs) else "--:--",
                        fontSize = 11.sp,
                        color = LcdText,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}

@Composable
private fun TitleBar(isPlaying: Boolean) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(22.dp)
            .background(Brush.verticalGradient(listOf(Color(0xFFFDFDFE), Color(0xFFC9CED6))))
            .drawWithContent {
                drawContent()
                drawLine(Color(0xFF8A919B), Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx())
            }
            .padding(horizontal = 8.dp),
    ) {
        Canvas(
            Modifier
                .align(Alignment.CenterStart)
                .size(10.dp),
        ) {
            if (isPlaying) drawPlayGlyph(LcdText) else drawPauseGlyph(LcdText)
        }
        Text(
            text = stringResource(R.string.ipod_now_playing),
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            color = LcdText,
            modifier = Modifier.align(Alignment.Center),
        )
        Canvas(
            Modifier
                .align(Alignment.CenterEnd)
                .size(width = 22.dp, height = 10.dp),
        ) { drawBattery() }
    }
}

/** The artwork with the mirrored fade underneath it the iPod drew. */
@Composable
private fun ReflectedArtwork(url: String?, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier) {
        val side = maxWidth
        Column {
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(side)
                    .background(Color(0xFFCCD1D8)),
            )
            Box(
                Modifier
                    .size(width = side, height = side * REFLECTION)
                    .clip(RectangleShape),
            ) {
                AsyncImage(
                    model = url,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    alignment = Alignment.BottomCenter,
                    modifier = Modifier
                        .wrapContentSize(Alignment.TopStart, unbounded = true)
                        .size(side)
                        .graphicsLayer {
                            scaleY = -1f
                            alpha = 0.35f
                        },
                )
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xFFEDF0F4)))),
                )
            }
        }
    }
}

@Composable
private fun GlossBar(fraction: Float, diamond: Boolean) {
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(11.dp),
    ) {
        val r = CornerRadius(size.height / 2)
        drawRoundRect(
            brush = Brush.verticalGradient(listOf(Color(0xFFDDE1E6), Color(0xFFFFFFFF))),
            cornerRadius = r,
        )
        if (fraction > 0f) {
            drawRoundRect(
                brush = Brush.verticalGradient(
                    0f to Color(0xFFA9D2FA),
                    0.5f to Color(0xFF4F95E6),
                    0.51f to Color(0xFF2E78D6),
                    1f to Color(0xFF5CA6F0),
                ),
                size = Size((size.width * fraction).coerceAtLeast(size.height), size.height),
                cornerRadius = r,
            )
        }
        drawRoundRect(
            color = Color(0xFF7D848E),
            cornerRadius = r,
            style = Stroke(1.dp.toPx()),
        )
        if (diamond) {
            val x = (size.width * fraction).coerceIn(size.height / 2, size.width - size.height / 2)
            val h = size.height * 0.85f
            val path = Path().apply {
                moveTo(x, size.height / 2 - h)
                lineTo(x + h * 0.7f, size.height / 2)
                lineTo(x, size.height / 2 + h)
                lineTo(x - h * 0.7f, size.height / 2)
                close()
            }
            drawPath(path, Brush.verticalGradient(listOf(Color.White, Color(0xFFB6BCC5))))
            drawPath(path, Color(0xFF55606C), style = Stroke(1.dp.toPx()))
        }
    }
}

@Composable
private fun VolumeBar(level: Float) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(12.dp)) { drawSpeaker(waves = 0) }
        Spacer(Modifier.width(6.dp))
        Box(Modifier.weight(1f)) { GlossBar(fraction = level, diamond = false) }
        Spacer(Modifier.width(6.dp))
        Canvas(Modifier.size(14.dp)) { drawSpeaker(waves = 2) }
    }
}

// ---- The wheel ----

@Composable
private fun ClickWheel(
    finish: IpodFinish,
    onMenu: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onPlayPause: () -> Unit,
    onCenter: () -> Unit,
    onCenterHold: () -> Unit,
    onTurn: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val view = LocalView.current
    val menuLabel = stringResource(R.string.ipod_menu)
    val callbacks by rememberUpdatedState(
        WheelCallbacks(onMenu, onNext, onPrevious, onPlayPause, onCenter, onCenterHold, onTurn),
    )
    // Which part of the wheel is held down, for its pressed shading.
    var pressed by remember { mutableStateOf<WheelZone?>(null) }
    var centerPressed by remember { mutableFloatStateOf(0f) }

    Box(
        modifier
            .shadow(10.dp, CircleShape)
            .clip(CircleShape)
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val c = Offset(size.width / 2f, size.height / 2f)
                    val outer = min(size.width, size.height) / 2f
                    // The box is square; its corners are not the wheel.
                    val zone = zoneOf(down.position, c, outer) ?: return@awaitEachGesture
                    down.consume()
                    pressed = zone
                    if (zone == WheelZone.Center) centerPressed = 1f
                    val downAt = down.uptimeMillis
                    var lastAngle = angleOf(down.position, c)
                    var travelled = 0f
                    var carry = 0f
                    var turning = false
                    var held = false
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) {
                            change.consume()
                            break
                        }
                        if (zone == WheelZone.Center) {
                            if (!held && change.uptimeMillis - downAt > HOLD_MS) {
                                held = true
                                view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                                callbacks.onCenterHold()
                            }
                            change.consume()
                            continue
                        }
                        if (change.positionChange() != Offset.Zero) {
                            val angle = angleOf(change.position, c)
                            var delta = angle - lastAngle
                            if (delta > PI) delta -= (2 * PI).toFloat()
                            if (delta < -PI) delta += (2 * PI).toFloat()
                            lastAngle = angle
                            travelled += abs(delta)
                            if (travelled > TURN_SLOP) turning = true
                            if (turning) {
                                pressed = null
                                carry += delta
                                val steps = (carry / DETENT).toInt()
                                if (steps != 0) {
                                    carry -= steps * DETENT
                                    view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                    callbacks.onTurn(steps)
                                }
                            }
                        }
                        change.consume()
                    }
                    pressed = null
                    centerPressed = 0f
                    if (turning || held) return@awaitEachGesture
                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    when (zone) {
                        WheelZone.Menu -> callbacks.onMenu()
                        WheelZone.Next -> callbacks.onNext()
                        WheelZone.Previous -> callbacks.onPrevious()
                        WheelZone.PlayPause -> callbacks.onPlayPause()
                        WheelZone.Center -> callbacks.onCenter()
                    }
                }
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val outer = size.minDimension / 2f
            val inner = outer * CENTER_RATIO
            drawCircle(Brush.verticalGradient(finish.wheel), outer)
            drawCircle(finish.wheelEdge, outer - 0.5.dp.toPx(), style = Stroke(1.dp.toPx()))
            pressed?.takeIf { it != WheelZone.Center }?.let { zone ->
                val start = when (zone) {
                    WheelZone.Menu -> -135f
                    WheelZone.Next -> -45f
                    WheelZone.PlayPause -> 45f
                    else -> 135f
                }
                drawArc(
                    color = Color.Black.copy(alpha = 0.06f),
                    startAngle = start,
                    sweepAngle = 90f,
                    useCenter = true,
                )
            }
            val glyph = outer * 0.11f
            val labelOffset = (outer + inner) / 2f
            val cx = size.width / 2f
            val cy = size.height / 2f
            drawSkipGlyph(Offset(cx + labelOffset, cy), glyph, finish.label, forward = true)
            drawSkipGlyph(Offset(cx - labelOffset, cy), glyph, finish.label, forward = false)
            drawPlayPauseGlyph(Offset(cx, cy + labelOffset), glyph, finish.label)
            // The centre button, sunk a touch into the wheel.
            drawCircle(Color.Black.copy(alpha = 0.10f), inner + 2.dp.toPx())
            drawCircle(
                Brush.verticalGradient(
                    if (centerPressed > 0f) finish.center.reversed() else finish.center,
                    startY = cy - inner,
                    endY = cy + inner,
                ),
                inner,
            )
            drawCircle(finish.centerEdge, inner, style = Stroke(1.dp.toPx()))
        }
        Text(
            text = menuLabel,
            color = finish.label,
            fontWeight = FontWeight.Bold,
            fontSize = 15.sp,
            letterSpacing = 0.5.sp,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .ringLabel(),
        )
    }
}

/**
 * Drops the MENU label to the middle of the ring, whatever size the wheel is.
 * Placed by TopCenter in the wheel's square box, so only its depth is worked
 * out here.
 */
private fun Modifier.ringLabel(): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
    val outer = constraints.maxWidth / 2f
    val ring = outer * (1f - CENTER_RATIO) / 2f
    val y = (ring - placeable.height / 2f).toInt().coerceAtLeast(0)
    layout(placeable.width, placeable.height + y) { placeable.place(0, y) }
}

private class WheelCallbacks(
    val onMenu: () -> Unit,
    val onNext: () -> Unit,
    val onPrevious: () -> Unit,
    val onPlayPause: () -> Unit,
    val onCenter: () -> Unit,
    val onCenterHold: () -> Unit,
    val onTurn: (Int) -> Unit,
)

private enum class WheelZone { Menu, Next, PlayPause, Previous, Center }

private fun zoneOf(p: Offset, c: Offset, outer: Float): WheelZone? {
    val d = hypot(p.x - c.x, p.y - c.y)
    if (d > outer) return null
    if (d < outer * CENTER_RATIO) return WheelZone.Center
    // Screen coordinates: y grows downwards, so 90° is the bottom.
    val deg = Math.toDegrees(atan2((p.y - c.y).toDouble(), (p.x - c.x).toDouble()))
    return when {
        deg >= -45 && deg < 45 -> WheelZone.Next
        deg >= 45 && deg < 135 -> WheelZone.PlayPause
        deg >= -135 && deg < -45 -> WheelZone.Menu
        else -> WheelZone.Previous
    }
}

private fun angleOf(p: Offset, c: Offset): Float = atan2(p.y - c.y, p.x - c.x)

// ---- Glyphs ----

private fun DrawScope.drawPlayGlyph(color: Color) {
    val path = Path().apply {
        moveTo(size.width * 0.15f, 0f)
        lineTo(size.width, size.height / 2)
        lineTo(size.width * 0.15f, size.height)
        close()
    }
    drawPath(path, color)
}

private fun DrawScope.drawPauseGlyph(color: Color) {
    val w = size.width * 0.3f
    drawRect(color, Offset(size.width * 0.1f, 0f), Size(w, size.height))
    drawRect(color, Offset(size.width * 0.6f, 0f), Size(w, size.height))
}

private fun DrawScope.drawBattery() {
    val body = Size(size.width - 3.dp.toPx(), size.height)
    drawRoundRect(
        brush = Brush.verticalGradient(listOf(Color(0xFFB9F07A), Color(0xFF4FAE1B))),
        size = body,
        cornerRadius = CornerRadius(2.dp.toPx()),
    )
    drawRoundRect(
        color = Color(0xFF3C4148),
        size = body,
        cornerRadius = CornerRadius(2.dp.toPx()),
        style = Stroke(1.dp.toPx()),
    )
    drawRect(
        Color(0xFF3C4148),
        Offset(body.width, size.height * 0.3f),
        Size(3.dp.toPx(), size.height * 0.4f),
    )
}

private fun DrawScope.drawSpeaker(waves: Int) {
    val color = LcdSubtext
    val h = size.height
    val path = Path().apply {
        moveTo(0f, h * 0.35f)
        lineTo(h * 0.25f, h * 0.35f)
        lineTo(h * 0.55f, h * 0.1f)
        lineTo(h * 0.55f, h * 0.9f)
        lineTo(h * 0.25f, h * 0.65f)
        lineTo(0f, h * 0.65f)
        close()
    }
    drawPath(path, color)
    repeat(waves) { i ->
        val r = h * (0.25f + 0.2f * i)
        drawArc(
            color = color,
            startAngle = -45f,
            sweepAngle = 90f,
            useCenter = false,
            topLeft = Offset(h * 0.5f - r, h / 2 - r),
            size = Size(r * 2, r * 2),
            style = Stroke(1.2.dp.toPx()),
        )
    }
}

/** ▶▶| or |◀◀, the bar on the far side from the way it skips. */
private fun DrawScope.drawSkipGlyph(at: Offset, s: Float, color: Color, forward: Boolean) {
    val dir = if (forward) 1f else -1f
    val tri = s * 0.9f
    val bar = s * 0.22f
    val total = tri * 2 + bar
    val start = at.x - dir * total / 2
    for (i in 0..1) {
        val x0 = start + dir * tri * i
        drawPath(
            Path().apply {
                moveTo(x0, at.y - s / 2)
                lineTo(x0 + dir * tri, at.y)
                lineTo(x0, at.y + s / 2)
                close()
            },
            color,
        )
    }
    val barX = start + dir * tri * 2
    drawRect(
        color,
        Offset(if (forward) barX else barX - bar, at.y - s / 2),
        Size(bar, s),
    )
}

/** ▶❚❚ */
private fun DrawScope.drawPlayPauseGlyph(at: Offset, s: Float, color: Color) {
    val tri = s * 0.85f
    val bar = s * 0.22f
    val gap = s * 0.18f
    val total = tri + gap + bar * 2 + gap * 0.8f
    val x0 = at.x - total / 2
    drawPath(
        Path().apply {
            moveTo(x0, at.y - s / 2)
            lineTo(x0 + tri, at.y)
            lineTo(x0, at.y + s / 2)
            close()
        },
        color,
    )
    val b1 = x0 + tri + gap
    drawRect(color, Offset(b1, at.y - s / 2), Size(bar, s))
    drawRect(color, Offset(b1 + bar + gap * 0.8f, at.y - s / 2), Size(bar, s))
}

private fun formatClock(ms: Long): String {
    val total = (ms.coerceAtLeast(0L) / 1000).toInt()
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

private const val SCREEN_ASPECT = 4f / 3f
private const val REFLECTION = 0.28f
private const val CENTER_RATIO = 0.38f
/** 24 clicks a turn, about where the real wheel's felt. */
private const val DETENT = (2 * PI / 24).toFloat()
private const val TURN_SLOP = 0.12f
private const val HOLD_MS = 550L
private const val VOLUME_STEP = 1f / 32f
private const val SCRUB_STEP_MS = 5_000L
private const val VOLUME_SHOWN_MS = 1_500L
private const val SCRUB_IDLE_MS = 4_000L
