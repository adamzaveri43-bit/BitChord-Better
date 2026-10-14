package com.music.bitchord.ui.player

import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.music.bitchord.data.lyrics.LyricLine
import com.music.bitchord.data.model.Song
import com.music.bitchord.playback.PlaybackPosition
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * The now-playing screen as a pocket aluminium tape-style recorder, after
 * Teenage Engineering's TP-7: a slab of brushed metal with a motorised disc
 * in the middle, a little OLED in the corner and three big keys along the
 * bottom.
 *
 * Just the body, filling the screen: the antenna, the side lever, the stub
 * beside the keys and the knurled knob are left off, so the face is a whole
 * screen of aluminium rather than a device floating on black. Laid out on the
 * body's own 470 × 680 grid, sized to the screen's width, with the height
 * left over shared between the room above the header, the room above the
 * disc, and the keys and fader. What each part does:
 *
 * - The disc spins while the music plays. Drag it round to scrub, as the
 *   real one does; tap its hub to play or pause.
 * - ● likes the track, ▶ plays and pauses (its arrow lights orange while
 *   playing), ■ stops — pauses and goes back to the start.
 * - ▲▲ and ▼R on the left edge, printed where the lever was, do the lever's
 *   job: a tap skips forward or back; held, they wind through the song with
 *   the disc spinning fast.
 * - + and − and the fader set the volume — between them, all the knob did.
 * - M opens the track's menu; the grey button puts the player away.
 * - The three buttons on the right edge toggle shuffle, cycle repeat and
 *   toggle AutoPlay, each lit along its face while on.
 *
 * The OLED shows the title, the playhead and the track number, and flashes
 * whatever a control just changed.
 */
@Composable
fun Tp7Player(
    song: Song,
    isPlaying: Boolean,
    position: PlaybackPosition,
    durationMs: Long,
    queueIndex: Int,
    liked: Boolean,
    shuffleEnabled: Boolean,
    repeatMode: Int,
    autoplayEnabled: Boolean,
    onPlayPause: () -> Unit,
    onStop: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onSeek: (Long) -> Unit,
    onToggleLike: () -> Unit,
    onToggleShuffle: () -> Unit,
    onCycleRepeat: () -> Unit,
    onToggleAutoplay: () -> Unit,
    onClose: () -> Unit,
    onOpenSongMenu: () -> Unit,
    modifier: Modifier = Modifier,
    /**
     * Fill the screen, with the height the device leaves over made into a deck
     * carrying a clock — the idle screen's way. Off, the device is shown on its
     * own at its true proportions, centred on black: the player's way.
     */
    deck: Boolean = false,
    /** For the deck: the song's words, and the user's offset for them. */
    lyrics: List<LyricLine>? = null,
    lyricsOffsetMs: Int = 0,
) {
    val view = LocalView.current
    val density = LocalDensity.current
    val volume = remember { PlayerPlatform.host.volume }
    // The output may have changed while the player was away.
    LaunchedEffect(volume) { volume.refresh() }
    val systemVolume by volume.level.collectAsState()
    // Followed locally while it's being changed here: the stream has only
    // 15-odd steps, and the system's answer to each set comes back late.
    var volumeTarget by remember { mutableStateOf<Float?>(null) }
    var volumeTouchedAt by remember { mutableLongStateOf(0L) }
    var faderHeld by remember { mutableStateOf(false) }
    LaunchedEffect(volumeTouchedAt, faderHeld) {
        if (volumeTouchedAt == 0L || faderHeld) return@LaunchedEffect
        delay(VOLUME_SETTLE_MS)
        volumeTarget = null
    }
    val volumeLevel = volumeTarget ?: systemVolume

    // What the OLED flashes in place of the title for a moment.
    var flash by remember { mutableStateOf<String?>(null) }
    var flashAt by remember { mutableLongStateOf(0L) }
    fun showFlash(text: String) {
        flash = text
        flashAt = System.nanoTime()
    }
    LaunchedEffect(flashAt) {
        if (flashAt == 0L) return@LaunchedEffect
        delay(1_300L)
        flash = null
    }

    fun setVolume(level: Float) {
        val to = level.coerceIn(0f, 1f)
        volumeTarget = to
        volumeTouchedAt = System.nanoTime()
        volume.set(to)
        showFlash("VOL ${(to * 100).roundToInt()}")
    }

    // The phone's own volume keys, the system panel, a cast receiver: the
    // fader follows them as they happen. A level that is only this player's
    // own set coming back is let through quietly; anything else takes over
    // from whatever was last set here, and shows on the OLED as the keys
    // move it.
    var lastSystemVolume by remember { mutableFloatStateOf(systemVolume) }
    LaunchedEffect(systemVolume) {
        if (systemVolume == lastSystemVolume) return@LaunchedEffect
        lastSystemVolume = systemVolume
        if (faderHeld) return@LaunchedEffect
        val target = volumeTarget
        if (target == null || abs(target - systemVolume) > VOLUME_ECHO_TOLERANCE) {
            volumeTarget = null
            showFlash("VOL ${(systemVolume * 100).roundToInt()}")
        } else {
            volumeTarget = null
        }
    }
    // Drawn eased toward the level rather than jumping a whole step at a
    // time, except under a finger, where it has to stay where it's held.
    val shownVolume = remember { Animatable(volumeLevel) }
    LaunchedEffect(volumeLevel, faderHeld) {
        if (faderHeld) {
            shownVolume.snapTo(volumeLevel)
        } else {
            shownVolume.animateTo(volumeLevel, tween(VOLUME_EASE_MS))
        }
    }

    // Changes made elsewhere, or arriving after their command, flash too.
    FlashOnChange(shuffleEnabled) { showFlash(if (it) "SHUFFLE ON" else "SHUFFLE OFF") }
    FlashOnChange(repeatMode) {
        showFlash(
            when (it) {
                REPEAT_ONE -> "REPEAT ONE"
                REPEAT_ALL -> "REPEAT ALL"
                else -> "REPEAT OFF"
            },
        )
    }
    FlashOnChange(autoplayEnabled) { showFlash(if (it) "AUTOPLAY ON" else "AUTOPLAY OFF") }
    FlashOnChange(liked) { if (it) showFlash("LIKED") }

    // -1 cueing back, 1 cueing forward.
    var cueDirection by remember { mutableIntStateOf(0) }
    // Where a scrub or a cue has got to, ahead of the player catching up.
    var seekTarget by remember { mutableLongStateOf(-1L) }
    var discHeld by remember { mutableStateOf(false) }
    LaunchedEffect(song.videoId) {
        cueDirection = 0
        seekTarget = -1L
    }

    var discAngle by remember { mutableFloatStateOf(0f) }
    val spinning = !discHeld && (isPlaying || cueDirection != 0)
    LaunchedEffect(spinning, cueDirection) {
        if (!spinning) return@LaunchedEffect
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                if (last != 0L) {
                    val dt = (now - last) / 1_000_000_000f
                    val speed = if (cueDirection != 0) cueDirection * DISC_CUE_DEG_S else DISC_PLAY_DEG_S
                    discAngle = (discAngle + dt * speed) % 360f
                }
                last = now
            }
        }
    }

    val shownMs = seekTarget.takeIf { it >= 0 } ?: position.positionMs
    // Gesture loops outlive compositions; they read these, never the params.
    val currentPosition by rememberUpdatedState(position)
    val duration by rememberUpdatedState(durationMs)
    val seek by rememberUpdatedState(onSeek)
    val playPause by rememberUpdatedState(onPlayPause)
    val latestVolume by rememberUpdatedState(volumeLevel)
    fun seekBy(deltaMs: Long) {
        if (duration <= 0) return
        val from = seekTarget.takeIf { it >= 0 } ?: currentPosition.positionMs
        val to = (from + deltaMs).coerceIn(0L, duration)
        seekTarget = to
        seek(to)
    }

    Box(
        modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                // As a player it sits clear of the status and navigation bars;
                // as an idle face it runs under them, which are hidden anyway.
                .then(if (deck) Modifier else Modifier.windowInsetsPadding(WindowInsets.safeDrawing)),
            contentAlignment = Alignment.Center,
        ) {
            // The body is the screen: nothing sticks out of it, so it's drawn
            // to the width and runs the full height.
            val u: Dp = if (deck) maxWidth / GRID_W else minOf(maxWidth / GRID_W, maxHeight / GRID_H)
            // A phone is far taller than the device. Rather than stretch the
            // device to meet it, the device keeps its own proportions at the
            // foot of the screen and the height left over becomes a deck above
            // it — more of the same aluminium, carrying a large OLED of the
            // same kind as the small one. Nothing is drawn out of shape.
            val extra = if (deck) (maxHeight / u - GRID_H).coerceAtLeast(0f) else 0f
            val hs = extra
            val ds = extra
            val keyGrow = 0f
            val bodyH = BODY_H + extra
            val keyH = KEY_H + keyGrow
            val faderTravel = FADER_TRAVEL + keyGrow
            fun Modifier.at(x: Float, y: Float, w: Float, h: Float): Modifier =
                this.offset(u * (x + OX), u * (y + OY)).size(u * w, u * h)
            fun units(n: Float) = with(density) { (u * n).toSp() }

            Box(Modifier.size(u * GRID_W, u * (GRID_H + extra))) {
                // ---- The body ----
                Canvas(Modifier.at(0f, 0f, BODY_W, bodyH)) { drawBody() }

                // ---- The deck's display ----
                if (extra >= DECK_MIN) {
                    DeckDisplay(
                        units = ::units,
                        lyrics = lyrics,
                        positionMs = shownMs,
                        durationMs = durationMs,
                        lyricsOffsetMs = lyricsOffsetMs,
                        modifier = Modifier.at(DECK_MARGIN, DECK_MARGIN, BODY_W - DECK_MARGIN * 2, extra - DECK_MARGIN),
                    )
                }

                // ---- LEDs ----
                Canvas(Modifier.at(150f, 30f + hs, 20f, 20f)) {
                    if (isPlaying) drawCircle(Color(0x66FF6A1A), size.minDimension / 2)
                    drawCircle(if (isPlaying) Color(0xFFFF6A1A) else Color(0xFF26272A), size.minDimension * 0.18f)
                }
                Canvas(Modifier.at(225f, 30f + hs, 20f, 20f)) {
                    drawCircle(Color(0xFF9EA1A5), size.minDimension * 0.17f)
                }

                // ---- Model name ----
                Text(
                    text = "BC-7",
                    color = Color(0xFF3E4146),
                    fontSize = units(30f),
                    fontWeight = FontWeight.Light,
                    modifier = Modifier.at(26f, 20f + hs, 120f, 44f),
                )

                // ---- OLED ----
                Oled(
                    title = flash ?: song.title.uppercase(),
                    isFlash = flash != null,
                    time = formatOledTime(shownMs),
                    track = queueIndex + 1,
                    units = ::units,
                    unit = u,
                    modifier = Modifier
                        .at(348f, 18f + hs, 108f, 60f),
                )

                // ---- The disc ----
                val discCx = 235f
                val discCy = 278f + ds
                val discR = 215f
                // Rotating layer: face line, printing and the hub.
                Box(
                    Modifier
                        .at(discCx - discR, discCy - discR, discR * 2, discR * 2)
                        .graphicsLayer { rotationZ = discAngle },
                ) {
                    Canvas(Modifier.fillMaxSize()) { drawDiscFace(hubFraction = HUB_R / discR) }
                    Text(
                        text = "96 / 24",
                        color = Color(0xFF5A5D62),
                        fontSize = units(17f),
                        modifier = Modifier
                            .offset(u * (discR - 95f), u * (discR - 112f))
                            .rotate(45f),
                    )
                    Text(
                        text = "${queueIndex + 1} ◎ M",
                        color = Color(0xFF5A5D62),
                        fontSize = units(17f),
                        modifier = Modifier
                            .offset(u * (discR + 95f), u * (discR + 95f))
                            .rotate(45f),
                    )
                }
                // The disc's touch surface, not rotating with it.
                Box(
                    Modifier
                        .at(discCx - discR, discCy - discR, discR * 2, discR * 2)
                        .pointerInput(Unit) {
                            awaitEachGesture {
                                val down = awaitFirstDown()
                                val c = Offset(size.width / 2f, size.height / 2f)
                                val r = size.width / 2f
                                val d = hypot(down.position.x - c.x, down.position.y - c.y)
                                if (d > r) return@awaitEachGesture
                                down.consume()
                                val onHub = d < r * (HUB_R / discR)
                                var lastAngle = atan2(down.position.y - c.y, down.position.x - c.x)
                                var travelled = 0f
                                var moved = false
                                var pendingMs = 0f
                                var lastSeekAt = 0L
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                    if (!change.pressed) {
                                        change.consume()
                                        break
                                    }
                                    val a = atan2(change.position.y - c.y, change.position.x - c.x)
                                    var delta = a - lastAngle
                                    if (delta > PI) delta -= (2 * PI).toFloat()
                                    if (delta < -PI) delta += (2 * PI).toFloat()
                                    lastAngle = a
                                    travelled += abs(delta)
                                    if (!moved && travelled > 0.08f) {
                                        moved = true
                                        discHeld = true
                                    }
                                    if (moved) {
                                        val deg = Math.toDegrees(delta.toDouble()).toFloat()
                                        discAngle = (discAngle + deg) % 360f
                                        pendingMs += deg / 360f * SCRUB_MS_PER_TURN
                                        // A seek per frame would swamp the player.
                                        if (change.uptimeMillis - lastSeekAt > 120L && abs(pendingMs) >= 250f) {
                                            seekBy(pendingMs.toLong())
                                            pendingMs = 0f
                                            lastSeekAt = change.uptimeMillis
                                            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                        }
                                    }
                                    change.consume()
                                }
                                if (moved) {
                                    if (abs(pendingMs) >= 50f) seekBy(pendingMs.toLong())
                                    discHeld = false
                                    seekTarget = -1L
                                } else if (onHub) {
                                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                    playPause()
                                }
                            }
                        },
                )

                // ---- Skip marks ----
                // Where the lever was. The marks printed beside it do its job:
                // a tap on ▲▲ skips forward and on ▼R back; held, they wind
                // through the song with the disc spinning fast.
                SkipMark(
                    label = "▲▲",
                    textSize = units(14f),
                    modifier = Modifier.at(2f, 104f + ds, 44f, 76f),
                    onTap = onNext,
                    onHoldStart = { cueDirection = 1 },
                    onHoldRepeat = { seekBy(CUE_STEP_MS) },
                    onHoldEnd = {
                        cueDirection = 0
                        seekTarget = -1L
                    },
                )
                SkipMark(
                    label = "▼\nR",
                    textSize = units(14f),
                    modifier = Modifier.at(2f, 376f + ds, 44f, 76f),
                    onTap = onPrevious,
                    onHoldStart = { cueDirection = -1 },
                    onHoldRepeat = { seekBy(-CUE_STEP_MS) },
                    onHoldEnd = {
                        cueDirection = 0
                        seekTarget = -1L
                    },
                )

                // ---- M ----
                var mDown by remember { mutableStateOf(false) }
                Box(
                    Modifier
                        .at(434f, 131f + hs, 19f, 19f)
                        .graphicsLayer { scaleX = if (mDown) 0.9f else 1f; scaleY = scaleX }
                        .clip(RoundedCornerShape(u * 2))
                        .background(Color(0xFFF26B21)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("M", color = Color.White, fontSize = units(13f), fontWeight = FontWeight.Medium, style = TightText)
                }
                PressArea(
                    modifier = Modifier.at(418f, 114f + hs, 30f, 54f),
                    onPressChange = { mDown = it },
                    onTap = onOpenSongMenu,
                )

                // ---- Side buttons: shuffle, repeat, AutoPlay ----
                val sides = listOf(
                    Triple(113f, 70f, shuffleEnabled),
                    Triple(213f, 45f, repeatMode != REPEAT_OFF),
                    Triple(298f, 45f, autoplayEnabled),
                )
                val sideActions = listOf(onToggleShuffle, onCycleRepeat, onToggleAutoplay)
                sides.forEachIndexed { i, (top, height, on) ->
                    var down by remember { mutableStateOf(false) }
                    Canvas(Modifier.at(460f, top + hs, 10f, height)) {
                        drawSideButton(pressed = down, on = on)
                    }
                    PressArea(
                        modifier = Modifier.at(448f, top + hs - 4f, 22f, height + 8f),
                        onPressChange = { down = it },
                        onTap = sideActions[i],
                    )
                }

                // ---- Grey button ----
                var greyDown by remember { mutableStateOf(false) }
                Canvas(Modifier.at(38f, 450f + ds, 36f, 36f)) {
                    drawCircle(
                        Brush.verticalGradient(
                            if (greyDown) listOf(Color(0xFF8E9195), Color(0xFFA6A9AD)) else listOf(Color(0xFFA9ACB0), Color(0xFF909397)),
                        ),
                        size.minDimension / 2,
                    )
                }
                PressArea(
                    modifier = Modifier.at(28f, 440f + ds, 56f, 56f),
                    onPressChange = { greyDown = it },
                    onTap = onClose,
                )

                // ---- + / − ----
                Canvas(Modifier.at(360f, 405f + ds, 110f, 110f)) { drawVolumePill() }
                VolumeButton(
                    plus = true,
                    modifier = Modifier.at(415f + PM_OFFSET - 20f, 460f - PM_OFFSET - 20f + ds, 40f, 40f),
                    onStep = { setVolume(volumeLevel + VOLUME_STEP) },
                )
                VolumeButton(
                    plus = false,
                    modifier = Modifier.at(415f - PM_OFFSET - 20f, 460f + PM_OFFSET - 20f + ds, 40f, 40f),
                    onStep = { setVolume(volumeLevel - VOLUME_STEP) },
                )

                // ---- Keys ----
                val keyTop = KEY_TOP + ds
                Key(
                    modifier = Modifier.at(0f, keyTop, 112f, keyH),
                    // The record key sits in the body's rounded corner.
                    shape = RoundedCornerShape(bottomStart = u * BODY_CORNER),
                    latched = false,
                    onTap = onToggleLike,
                ) {
                    val r = size.minDimension * 0.07f
                    if (liked) drawCircle(Color(0x55FF6A1A), r * 2.2f)
                    drawCircle(Color(0xFFF26B21), r)
                }
                Key(
                    modifier = Modifier.at(117f, keyTop, 113f, keyH),
                    latched = false,
                    onTap = onPlayPause,
                ) {
                    val s = size.minDimension * 0.13f
                    val c = center
                    drawPath(
                        Path().apply {
                            moveTo(c.x - s * 0.45f, c.y - s * 0.55f)
                            lineTo(c.x + s * 0.6f, c.y)
                            lineTo(c.x - s * 0.45f, c.y + s * 0.55f)
                            close()
                        },
                        // Lit while playing, like a key's lamp, rather than held down:
                        // a sunk key read as a glitch on glass.
                        if (isPlaying) Color(0xFFF26B21) else Color(0xFF2B2D31),
                    )
                }
                Key(
                    modifier = Modifier.at(235f, keyTop, 115f, keyH),
                    latched = false,
                    onTap = onStop,
                ) {
                    val s = size.minDimension * 0.11f
                    drawRect(Color(0xFF2B2D31), center - Offset(s / 2, s / 2), Size(s, s))
                }

                // ---- Fader ----
                Canvas(Modifier.at(360f, 510f + ds, 110f, 160f + keyGrow)) {
                    drawFader(level = shownVolume.value, unit = u.toPx(), travel = faderTravel, shift = ds)
                }
                Box(
                    Modifier
                        .at(360f, 505f + ds, 110f, 170f + keyGrow)
                        .pointerInput(Unit) {
                            awaitEachGesture {
                                val down = awaitFirstDown()
                                down.consume()
                                faderHeld = true
                                val travel = faderTravel * u.toPx()
                                val top = (FADER_TOP - 505f) * u.toPx()
                                fun levelAt(y: Float) = 1f - ((y - top) / travel).coerceIn(0f, 1f)
                                setVolume(levelAt(down.position.y))
                                var notch = (levelAt(down.position.y) * 15).toInt()
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                    if (!change.pressed) break
                                    val level = levelAt(change.position.y)
                                    setVolume(level)
                                    val n = (level * 15).toInt()
                                    if (n != notch) {
                                        notch = n
                                        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                    }
                                    change.consume()
                                }
                                faderHeld = false
                                volumeTouchedAt = System.nanoTime()
                            }
                        },
                )

            }
        }
    }
}

/**
 * A printed mark that's also a button: shaded while held, a tap or a hold
 * passed through [PressArea].
 */
@Composable
private fun SkipMark(
    label: String,
    textSize: androidx.compose.ui.unit.TextUnit,
    modifier: Modifier,
    onTap: () -> Unit,
    onHoldStart: () -> Unit,
    onHoldRepeat: () -> Unit,
    onHoldEnd: () -> Unit,
) {
    var down by remember { mutableStateOf(false) }
    Box(
        modifier
            .clip(RoundedCornerShape(30))
            .background(if (down) Color.Black.copy(alpha = 0.10f) else Color.Transparent),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = Color(0xFF5E6166),
            fontSize = textSize,
            lineHeight = textSize,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            style = TightText,
        )
        PressArea(
            modifier = Modifier.matchParentSize(),
            onPressChange = { down = it },
            onTap = onTap,
            onHoldStart = onHoldStart,
            onHoldRepeat = onHoldRepeat,
            onHoldEnd = onHoldEnd,
        )
    }
}

/**
 * The large OLED on the deck above the device: the words of the song, in a
 * pixel face like the small display's, with the date and time in its top
 * corners. The device's own small display already has the title and counter.
 *
 * Synced lyrics follow the song — the line being sung large and bright, the
 * one before and the ones after dimmer around it. Lyrics without timings are
 * paged through in step with the song. A song with none shows a big clock.
 * White on black, so on the idle screen it costs next to nothing to light.
 */
@Composable
private fun DeckDisplay(
    units: (Float) -> androidx.compose.ui.unit.TextUnit,
    lyrics: List<LyricLine>?,
    positionMs: Long,
    durationMs: Long,
    lyricsOffsetMs: Int,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            // Wakes on the minute rather than polling.
            delay(60_000L - System.currentTimeMillis() % 60_000L)
            now = System.currentTimeMillis()
        }
    }
    val locale = java.util.Locale.getDefault()
    val time = remember(now) {
        val pattern = if (android.text.format.DateFormat.is24HourFormat(context)) "HH:mm" else "h:mm"
        java.text.SimpleDateFormat(pattern, locale).format(java.util.Date(now))
    }
    val date = remember(now) {
        val pattern = android.text.format.DateFormat.getBestDateTimePattern(locale, "EEEdMMM")
        java.text.SimpleDateFormat(pattern, locale).format(java.util.Date(now)).uppercase(locale)
    }
    val lines = remember(lyrics) { lyrics?.filterNot { it.text.isBlank() }.orEmpty() }
    val synced = remember(lines) { lines.size > 1 && lines.last().timeMs > 0 }
    // The line being sung: the last one due by now. A line is due when the
    // song reaches its time plus the lyrics offset, as in the main lyrics.
    val current = when {
        lines.isEmpty() -> -1
        synced -> lines.indexOfLast { it.timeMs + lyricsOffsetMs <= positionMs }.coerceAtLeast(0)
        durationMs > 0 -> ((positionMs.toFloat() / durationMs) * lines.size).toInt().coerceIn(0, lines.size - 1)
        else -> 0
    }

    Box(
        modifier
            .clip(RoundedCornerShape(4))
            .background(Color(0xFF8D9094))
            .padding(1.5.dp)
            .clip(RoundedCornerShape(4))
            .background(Color.Black),
    ) {
        // ---- Date and time, in the corners ----
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Text(date, color = Color.White.copy(alpha = 0.8f), fontFamily = PixelFont, fontSize = units(24f), maxLines = 1)
            Spacer(Modifier.weight(1f))
            Text(time, color = Color.White.copy(alpha = 0.8f), fontFamily = PixelFont, fontSize = units(24f), maxLines = 1)
        }

        // ---- The words, filling the rest ----
        Column(
            Modifier
                .fillMaxSize()
                .padding(start = 16.dp, end = 16.dp, top = 44.dp, bottom = 14.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            if (current < 0) {
                Text(
                    text = time,
                    color = Color.White,
                    fontFamily = PixelFont,
                    fontSize = units(150f),
                    maxLines = 1,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                lines.getOrNull(current - 1)?.let { LyricRow(it.text, units(30f), 0.35f, 2) }
                Spacer(Modifier.height(6.dp))
                LyricRow(lines[current].text, units(46f), 1f, 3)
                Spacer(Modifier.height(6.dp))
                lines.getOrNull(current + 1)?.let { LyricRow(it.text, units(30f), 0.5f, 2) }
                lines.getOrNull(current + 2)?.let { LyricRow(it.text, units(30f), 0.3f, 2) }
            }
        }
    }
}

@Composable
private fun LyricRow(text: String, size: androidx.compose.ui.unit.TextUnit, alpha: Float, maxLines: Int) {
    Text(
        text = text,
        color = Color.White.copy(alpha = alpha),
        fontFamily = PixelFont,
        fontSize = size,
        lineHeight = size,
        maxLines = maxLines,
        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        modifier = Modifier.fillMaxWidth(),
    )
}

/** VT323: a terminal's pixel face, to sit with the OLEDs. Open Font License. */
private val PixelFont = FontFamily(androidx.compose.ui.text.font.Font(com.music.bitchord.R.font.vt323))

/** Runs [onChange] when [value] changes, but not for the value it starts with. */
@Composable
private fun <T> FlashOnChange(value: T, onChange: (T) -> Unit) {
    var previous by remember { mutableStateOf(value) }
    val callback by rememberUpdatedState(onChange)
    LaunchedEffect(value) {
        if (value != previous) {
            previous = value
            callback(value)
        }
    }
}

// ---- Pieces ----

@Composable
private fun Oled(
    title: String,
    isFlash: Boolean,
    time: String,
    track: Int,
    units: (Float) -> androidx.compose.ui.unit.TextUnit,
    unit: Dp,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .clip(RoundedCornerShape(8))
            .background(Color.Black)
            .padding(horizontal = 6.dp, vertical = 3.dp),
    ) {
        // The same pixel face as the deck's big display.
        Text(
            text = title,
            color = Color.White,
            fontSize = units(if (isFlash) 22f else 27f),
            fontFamily = PixelFont,
            maxLines = 1,
            style = TightText,
            modifier = Modifier
                .fillMaxWidth()
                .basicMarquee(iterations = Int.MAX_VALUE),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = time,
                color = Color.White,
                fontSize = units(19f),
                fontFamily = PixelFont,
                maxLines = 1,
                style = TightText,
            )
            Spacer(Modifier.weight(1f))
            Box(
                Modifier
                    .height(unit * 15)
                    .clip(RoundedCornerShape(3.dp))
                    .background(Color.White)
                    .padding(horizontal = 2.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "%02d".format(track.coerceIn(0, 99)),
                    color = Color.Black,
                    fontSize = units(16f),
                    fontFamily = PixelFont,
                    maxLines = 1,
                    style = TightText,
                )
            }
            Spacer(Modifier.width(1.dp))
        }
    }
}

/**
 * A touch target that tells its look when it's held, taps on a quick press
 * and, given [onHoldRepeat], repeats while held past a moment.
 */
@Composable
private fun PressArea(
    modifier: Modifier,
    onTap: () -> Unit,
    onPressChange: (Boolean) -> Unit = {},
    onHoldStart: (() -> Unit)? = null,
    onHoldRepeat: (() -> Unit)? = null,
    onHoldEnd: (() -> Unit)? = null,
) {
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val tap by rememberUpdatedState(onTap)
    val press by rememberUpdatedState(onPressChange)
    val holdStart by rememberUpdatedState(onHoldStart)
    val holdRepeat by rememberUpdatedState(onHoldRepeat)
    val holdEnd by rememberUpdatedState(onHoldEnd)
    Box(
        modifier.pointerInput(Unit) {
            detectTapGestures(
                onPress = {
                    press(true)
                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    var held = false
                    val job: Job? = if (holdRepeat != null) {
                        scope.launch {
                            delay(HOLD_MS)
                            held = true
                            holdStart?.invoke()
                            while (true) {
                                holdRepeat?.invoke()
                                delay(REPEAT_MS)
                            }
                        }
                    } else {
                        null
                    }
                    val released = tryAwaitRelease()
                    job?.cancel()
                    press(false)
                    if (held) holdEnd?.invoke() else if (released) tap()
                },
            )
        },
    )
}

@Composable
private fun Key(
    modifier: Modifier,
    latched: Boolean,
    onTap: () -> Unit,
    shape: androidx.compose.ui.graphics.Shape = androidx.compose.ui.graphics.RectangleShape,
    glyph: DrawScope.() -> Unit,
) {
    var pressed by remember { mutableStateOf(false) }
    val down = pressed || latched
    Box(modifier.clip(shape)) {
        Canvas(Modifier.fillMaxSize()) {
            // The seam round the key.
            drawRect(Color(0xFF8D9094))
            // Down reads as shade, not as a gap opening above the key: the
            // face darkens and its lit top edge goes, and the glyph drops a
            // touch.
            drawRect(
                Brush.verticalGradient(
                    if (down) {
                        listOf(Color(0xFFAEB1B5), Color(0xFFC4C6C9))
                    } else {
                        listOf(Color(0xFFD8D9DB), Color(0xFFC3C5C8))
                    },
                ),
                topLeft = Offset(2f, 2f),
                size = Size(size.width - 4f, size.height - 4f),
            )
            drawLine(
                if (down) Color.Black.copy(alpha = 0.12f) else Color.White.copy(alpha = 0.7f),
                Offset(2f, 3f),
                Offset(size.width - 2f, 3f),
                if (down) 4f else 2f,
            )
            val drop = if (down) size.height * 0.012f else 0f
            translate(top = size.height * 0.30f + drop - size.height / 2) { glyph() }
        }
        PressArea(
            modifier = Modifier.fillMaxSize(),
            onPressChange = { pressed = it },
            onTap = onTap,
        )
    }
}

@Composable
private fun VolumeButton(plus: Boolean, modifier: Modifier, onStep: () -> Unit) {
    var down by remember { mutableStateOf(false) }
    Box(modifier) {
        Canvas(Modifier.fillMaxSize()) {
            val r = size.minDimension / 2
            drawCircle(Color(0xFF8F9296), r)
            drawCircle(
                Brush.verticalGradient(
                    if (down) listOf(Color(0xFFBFC1C4), Color(0xFFD3D4D6)) else listOf(Color(0xFFE2E3E5), Color(0xFFC4C6C9)),
                ),
                r - 2f,
            )
            val arm = r * 0.42f
            val stroke = r * 0.09f
            drawLine(Color(0xFF55585D), center - Offset(arm, 0f), center + Offset(arm, 0f), stroke)
            if (plus) drawLine(Color(0xFF55585D), center - Offset(0f, arm), center + Offset(0f, arm), stroke)
        }
        PressArea(
            modifier = Modifier.fillMaxSize(),
            onPressChange = { down = it },
            onTap = onStep,
            onHoldRepeat = onStep,
        )
    }
}

// ---- Drawing ----

private fun DrawScope.drawBody() {
    val r = CornerRadius(size.width * 0.04f)
    drawRoundRect(Color(0xFF9EA1A5), cornerRadius = r)
    drawRoundRect(
        Brush.linearGradient(
            listOf(Color(0xFFDDDEE0), Color(0xFFCBCDCF), Color(0xFFC2C4C7)),
            start = Offset.Zero,
            end = Offset(size.width, size.height),
        ),
        topLeft = Offset(1.5f, 1.5f),
        size = Size(size.width - 3f, size.height - 3f),
        cornerRadius = r,
    )
    // A faint brushed grain.
    var x = 0f
    var i = 0
    while (x < size.width) {
        drawLine(
            if (i % 2 == 0) Color.White.copy(alpha = 0.05f) else Color.Black.copy(alpha = 0.025f),
            Offset(x, 4f),
            Offset(x, size.height - 4f),
            1f,
        )
        x += 3f
        i++
    }
}

/** The disc's own face: rim, the line across it, and the hub with its screws. */
private fun DrawScope.drawDiscFace(hubFraction: Float) {
    val r = size.minDimension / 2
    val c = center
    drawCircle(Color(0xFFA4A7AB), r, c)
    drawCircle(
        Brush.linearGradient(
            listOf(Color(0xFFD9DADC), Color(0xFFC8CACD)),
            start = Offset.Zero,
            end = Offset(size.width, size.height),
        ),
        r - 2.5f,
        c,
    )
    // The line, corner to corner through the hub.
    val d = (r - 4f) * 0.7071f
    drawLine(Color(0xFF7D8085), Offset(c.x - d, c.y + d), Offset(c.x + d, c.y - d), 1.6f)
    // The hub: a polished cap, half in light, half in shade.
    val hub = r * hubFraction
    drawCircle(Color(0xFF8E9195), hub + 2f, c)
    drawCircle(Color(0xFFD5D6D8), hub, c)
    rotate(-45f, c) {
        drawArc(
            Color(0xFF9FA2A6),
            startAngle = 0f,
            sweepAngle = 180f,
            useCenter = true,
            topLeft = Offset(c.x - hub, c.y - hub),
            size = Size(hub * 2, hub * 2),
        )
    }
    for (k in 0 until 3) {
        val a = (k * 2 * PI / 3 - PI / 2).toFloat()
        val p = c + Offset(kotlin.math.cos(a), kotlin.math.sin(a)) * (hub * 0.55f)
        drawCircle(Color(0xFF6F7277), hub * 0.07f, p)
        drawCircle(Color(0xFFE6E7E8), hub * 0.035f, p)
    }
}

private fun DrawScope.drawSideButton(pressed: Boolean, on: Boolean) {
    val inset = if (pressed) size.width * 0.35f else 0f
    drawRoundRect(
        Brush.horizontalGradient(listOf(Color(0xFFB9BCC0), Color(0xFFDADBDD), Color(0xFFA5A8AC))),
        topLeft = Offset(inset, 0f),
        size = Size(size.width - inset, size.height),
        cornerRadius = CornerRadius(size.width * 0.4f),
    )
    drawRoundRect(
        Color(0xFF8E9195),
        topLeft = Offset(inset, 0f),
        size = Size(size.width - inset, size.height),
        cornerRadius = CornerRadius(size.width * 0.4f),
        style = Stroke(1f),
    )
    // Lit along its face while what it toggles is on.
    if (on) {
        val stripeW = size.width * 0.28f
        drawRoundRect(
            Color(0xFFFF6A1A),
            topLeft = Offset(size.width - stripeW - size.width * 0.18f, size.height * 0.2f),
            size = Size(stripeW, size.height * 0.6f),
            cornerRadius = CornerRadius(stripeW / 2),
        )
    }
}

/** The rounded slot the + and − sit in, on the diagonal. */
private fun DrawScope.drawVolumePill() {
    val w = size.width
    rotate(-45f) {
        val pillW = w * 0.95f
        val pillH = w * 0.40f
        drawRoundRect(
            Color(0xFFB4B7BB),
            topLeft = Offset(center.x - pillW / 2, center.y - pillH / 2),
            size = Size(pillW, pillH),
            cornerRadius = CornerRadius(pillH / 2),
        )
        drawRoundRect(
            Color(0xFF8E9195),
            topLeft = Offset(center.x - pillW / 2, center.y - pillH / 2),
            size = Size(pillW, pillH),
            cornerRadius = CornerRadius(pillH / 2),
            style = Stroke(1.2f),
        )
    }
}

private fun DrawScope.drawFader(level: Float, unit: Float, travel: Float, shift: Float) {
    val ox = 360f
    val oy = 510f + shift
    fun x(v: Float) = (v - ox) * unit
    fun y(v: Float) = (v - oy) * unit
    for (slot in listOf(397f, 427f)) {
        drawRoundRect(
            Color(0xFF8A8D91),
            topLeft = Offset(x(slot) - 3f * unit, y((FADER_TOP + shift)) - 4f * unit),
            size = Size(6f * unit, travel * unit + 8f * unit),
            cornerRadius = CornerRadius(3f * unit),
        )
        drawRoundRect(
            Color(0xFF5E6165),
            topLeft = Offset(x(slot) - 1f * unit, y((FADER_TOP + shift)) - 2f * unit),
            size = Size(2f * unit, travel * unit + 4f * unit),
            cornerRadius = CornerRadius(1f * unit),
        )
    }
    // The cap: a crosshair across both slots, at the level.
    val capY = y((FADER_TOP + shift)) + (1f - level.coerceIn(0f, 1f)) * travel * unit
    drawLine(Color(0xFF55585D), Offset(x(372f), capY), Offset(x(452f), capY), 1.6f * unit)
    for (slot in listOf(397f, 427f)) {
        drawRoundRect(
            Brush.verticalGradient(listOf(Color(0xFFE4E5E7), Color(0xFFB7BABE)), startY = capY - 7f * unit, endY = capY + 7f * unit),
            topLeft = Offset(x(slot) - 5f * unit, capY - 7f * unit),
            size = Size(10f * unit, 14f * unit),
            cornerRadius = CornerRadius(2f * unit),
        )
    }
}

/** h.mm.ss, the way the device's display counts. */
private fun formatOledTime(ms: Long): String {
    val total = (ms.coerceAtLeast(0L) / 1000).toInt()
    return "%d.%02d.%02d".format(total / 3600, (total % 3600) / 60, total % 60)
}

// The photo's grid: body 470 × 680, with the lever, antenna and knob around it.
/** Below this much spare height, the deck stays bare aluminium. */
private const val DECK_MIN = 150f
/**
 * How far + and − sit from the middle of their slot along each axis. The slot
 * is 104.5 long and 44 thick, so each rounded end is centred 30.25 out along
 * the 45° diagonal — 21.4 on each axis — and the 40-wide buttons sit in those
 * ends with an even 2-unit rim all round.
 */
private const val PM_OFFSET = 21.4f
private const val DECK_MARGIN = 22f
private const val OX = 0f
private const val OY = 0f
private const val GRID_W = 470f
private const val GRID_H = 680f
private const val BODY_W = 470f
private const val BODY_H = 680f
private const val BODY_CORNER = 18.8f
private const val KEY_TOP = 516f
private const val KEY_H = 164f
/** How much taller the lower half may grow into a tall screen. */
private const val HUB_R = 43f
private const val FADER_TOP = 520f
private const val FADER_TRAVEL = 132f

/**
 * Text sized to its glyphs: without the font's own padding above and below,
 * a letter set in a small box is centred in it rather than pushed out of it.
 */
private val TightText = androidx.compose.ui.text.TextStyle(
    platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false),
    lineHeightStyle = androidx.compose.ui.text.style.LineHeightStyle(
        alignment = androidx.compose.ui.text.style.LineHeightStyle.Alignment.Center,
        trim = androidx.compose.ui.text.style.LineHeightStyle.Trim.Both,
    ),
)

// Player.REPEAT_MODE_* without pulling Media3 into the drawing.
private const val REPEAT_OFF = 0
private const val REPEAT_ONE = 1
private const val REPEAT_ALL = 2

private const val DISC_PLAY_DEG_S = 90f
private const val DISC_CUE_DEG_S = 900f
/** How much of the song one full turn of the disc scrubs through. */
private const val SCRUB_MS_PER_TURN = 20_000f
private const val CUE_STEP_MS = 4_000L
private const val VOLUME_STEP = 1f / 15f
/** How long a level set here is held before the system's own reading is trusted again. */
private const val VOLUME_SETTLE_MS = 1_500L
/** Within this of what was set here, a new system level is that set coming back. */
private const val VOLUME_ECHO_TOLERANCE = 0.034f
private const val VOLUME_EASE_MS = 140
private const val HOLD_MS = 400L
private const val REPEAT_MS = 120L
