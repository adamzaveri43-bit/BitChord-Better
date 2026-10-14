package com.music.bitchord.ui.player

import android.os.Build
import kotlinx.coroutines.launch
import androidx.compose.ui.unit.IntOffset
import com.music.bitchord.data.lyrics.LyricLine
import android.view.HapticFeedbackConstants
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.FastRewind
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material3.Icon
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import com.music.bitchord.R
import com.music.bitchord.data.model.artworkAt
import android.os.SystemClock
import android.text.format.DateFormat
import android.view.WindowManager
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.ui.res.stringResource
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import coil3.compose.AsyncImage
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.playback.PlaybackPosition
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.random.Random

/** Which face the idle screen wears, if any. */
enum class IdleFace { APPLE_MUSIC, WALKMAN, TP7 }

/**
 * An always-on face for the app, the nearest thing to one Android lets an app
 * have: a dim full-screen face over everything, opened by hand from the
 * player's menu and put away by a touch.
 *
 * - Nothing brings it up on its own; [shown] is the caller's to set.
 * - The face's window holds the screen on, drops the brightness and hides the
 *   system bars.
 * - The lock in the corner keeps it up: locked, touches and back do nothing
 *   and the face stays until the lock is held to undo it.
 * - The content drifts by a few dp every few minutes, so nothing sits on the
 *   same pixels for hours.
 * - Unlocked and paused for a while, the face steps aside and lets the screen
 *   sleep. Locked, it stays.
 *
 * Shown in a dialog so it lands above the player, which is a sheet in a
 * window of its own.
 */
@Composable
fun IdleScreenHost(
    face: IdleFace,
    shown: Boolean,
    onDismiss: () -> Unit,
    song: Song?,
    isPlaying: Boolean,
    position: PlaybackPosition,
    durationMs: Long,
    queueIndex: Int = 0,
    /** How bright the face is, in percent of full: the screen's own level while it's up. */
    brightnessPercent: Int = AppSettings.DEFAULT_IDLE_BRIGHTNESS,
    /** For faces that carry working keys: the Walkman's side bar, the Apple card, the whole TP-7. */
    onPlayPause: () -> Unit = {},
    onNext: () -> Unit = {},
    onPrevious: () -> Unit = {},
    liked: Boolean = false,
    onToggleLike: () -> Unit = {},
    onSeek: (Long) -> Unit = {},
    onStop: () -> Unit = {},
    shuffleEnabled: Boolean = false,
    repeatMode: Int = 0,
    autoplayEnabled: Boolean = false,
    onToggleShuffle: () -> Unit = {},
    onCycleRepeat: () -> Unit = {},
    onToggleAutoplay: () -> Unit = {},
    /** For the TP-7's deck: the song's words. */
    lyrics: List<LyricLine>? = null,
) {
    val dismiss by rememberUpdatedState(onDismiss)
    // Nothing left to show.
    LaunchedEffect(shown, song == null) {
        if (shown && song == null) dismiss()
    }
    if (!shown || song == null) return

    // Kept for as long as the face is up, and forgotten when it goes.
    var locked by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(isPlaying, locked) {
        if (!isPlaying && !locked) {
            delay(PAUSED_GRACE_MS)
            dismiss()
        }
    }
    val wake = { if (!locked) dismiss() }
    Dialog(
        onDismissRequest = wake,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
            dismissOnClickOutside = false,
        ),
    ) {
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        DisposableEffect(window, brightnessPercent) {
            window?.let { w ->
                w.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
                w.setDimAmount(0f)
                w.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                w.attributes = w.attributes.apply {
                    screenBrightness = (brightnessPercent / 100f).coerceIn(0.01f, 1f)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        layoutInDisplayCutoutMode =
                            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                    }
                }
                WindowCompat.getInsetsController(w, w.decorView).apply {
                    hide(WindowInsetsCompat.Type.systemBars())
                    systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                }
            }
            onDispose { }
        }
        val fade = remember { Animatable(0f) }
        LaunchedEffect(Unit) { fade.animateTo(1f, tween(900)) }
        // A word on what the lock is doing, for a moment after it's touched.
        var hint by remember { mutableStateOf<Int?>(null) }
        var hintAt by remember { mutableLongStateOf(0L) }
        fun showHint(text: Int) {
            hint = text
            hintAt = SystemClock.uptimeMillis()
        }
        LaunchedEffect(hintAt) {
            if (hintAt == 0L) return@LaunchedEffect
            delay(HINT_MS)
            hint = null
        }
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black)
                .graphicsLayer { alpha = fade.value },
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        // Every face's keys work without waking: they take their
                        // own taps and drags, and anything they don't take — a
                        // touch anywhere else — wakes the app as it lifts.
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false)
                            val up = waitForUpOrCancellation()
                            if (up != null) wake()
                        }
                    },
            ) {
                when (face) {
                    IdleFace.APPLE_MUSIC -> AppleMusicIdleFace(
                        song = song,
                        isPlaying = isPlaying,
                        position = position,
                        durationMs = durationMs,
                        liked = liked,
                        onToggleLike = onToggleLike,
                        onPlayPause = onPlayPause,
                        onNext = onNext,
                        onPrevious = onPrevious,
                    )
                    IdleFace.TP7 -> {
                        Tp7Player(
                            song = song,
                            isPlaying = isPlaying,
                            position = position,
                            durationMs = durationMs,
                            queueIndex = queueIndex,
                            liked = liked,
                            shuffleEnabled = shuffleEnabled,
                            repeatMode = repeatMode,
                            autoplayEnabled = autoplayEnabled,
                            onPlayPause = onPlayPause,
                            onStop = onStop,
                            onNext = onNext,
                            onPrevious = onPrevious,
                            onSeek = onSeek,
                            onToggleLike = onToggleLike,
                            onToggleShuffle = onToggleShuffle,
                            onCycleRepeat = onCycleRepeat,
                            onToggleAutoplay = onToggleAutoplay,
                            onClose = wake,
                            onOpenSongMenu = {},
                            modifier = Modifier.fullBleedDrift(),
                            deck = true,
                            lyrics = lyrics,
                            lyricsOffsetMs = AppSettings.lyricsOffsetMs.collectAsState().value,
                        )
                    }
                    IdleFace.WALKMAN -> {
                        WalkmanFace(
                            song = song,
                            isPlaying = isPlaying,
                            position = position,
                            durationMs = durationMs,
                            modifier = Modifier.fullBleedDrift(),
                            onPlayPause = onPlayPause,
                            onNext = onNext,
                            onPrevious = onPrevious,
                        )
                    }
                }
                // Below what the screen can dim to on its own, a veil takes the face
                // further down; at the top of the range there's none.
                val veil = idleVeil(brightnessPercent)
                if (veil > 0f) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = veil)),
                    )
                }
            }
            // Locked, the face takes no touches at all — its keys included, so a
            // pocket can't skip a track — and a tap only says how to get out.
            if (locked) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            awaitEachGesture {
                                awaitFirstDown(requireUnconsumed = false).consume()
                                waitForUpOrCancellation()?.let {
                                    it.consume()
                                    showHint(R.string.aod_hold_to_unlock)
                                }
                            }
                        },
                )
            }
            IdleLockButton(
                locked = locked,
                onLock = {
                    locked = true
                    showHint(R.string.aod_locked)
                },
                onUnlock = {
                    locked = false
                    showHint(R.string.aod_unlocked)
                },
                onTapWhileLocked = { showHint(R.string.aod_hold_to_unlock) },
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(start = 12.dp, bottom = 6.dp),
            )
            hint?.let { text ->
                Text(
                    text = stringResource(text),
                    color = Color.White,
                    fontFamily = SfPro,
                    fontSize = 13.sp,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .windowInsetsPadding(WindowInsets.safeDrawing)
                        .padding(bottom = 60.dp)
                        .clip(RoundedCornerShape(50))
                        .background(Color.Black.copy(alpha = 0.7f))
                        .padding(horizontal = 14.dp, vertical = 7.dp),
                )
            }
        }
    }
}

/**
 * The lock in the face's corner: a tap locks the face in place; once locked,
 * only a hold undoes it, so nothing brushing the screen can.
 */
@Composable
private fun IdleLockButton(
    locked: Boolean,
    onLock: () -> Unit,
    onUnlock: () -> Unit,
    onTapWhileLocked: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val view = LocalView.current
    val lock by rememberUpdatedState(onLock)
    val unlock by rememberUpdatedState(onUnlock)
    val tapLocked by rememberUpdatedState(onTapWhileLocked)
    val isLocked by rememberUpdatedState(locked)
    Box(
        modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.45f))
            .border(0.6.dp, Color.White.copy(alpha = if (locked) 0.35f else 0.18f), CircleShape)
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = {
                        if (isLocked) {
                            tapLocked()
                        } else {
                            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                            lock()
                        }
                    },
                    onLongPress = {
                        if (isLocked) {
                            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                            unlock()
                        }
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = if (locked) Icons.Rounded.Lock else Icons.Rounded.LockOpen,
            contentDescription = stringResource(if (locked) R.string.aod_unlock else R.string.aod_lock),
            tint = Color.White.copy(alpha = if (locked) 0.85f else 0.6f),
            modifier = Modifier.size(20.dp),
        )
    }
}

/**
 * [burnInDrift] for a face that covers the whole screen: drawn a few percent
 * larger, so the nudge never opens a gap at an edge.
 */
@Composable
private fun Modifier.fullBleedDrift(): Modifier =
    burnInDrift().graphicsLayer {
        scaleX = FULL_BLEED_SCALE
        scaleY = FULL_BLEED_SCALE
    }

/** How much black goes over the face at [percent] brightness: none from 40% up. */
private fun idleVeil(percent: Int): Float =
    ((40 - percent) / 40f).coerceIn(0f, 1f) * 0.45f

/**
 * Moves the content a pixel or two every few minutes, against burn-in — too
 * little, and too slowly, to be seen. (It used to jump up to 6dp in one go
 * every minute, which read as the face shifting out of place on its own.)
 */
@Composable
private fun Modifier.burnInDrift(): Modifier {
    val x = remember { Animatable(0f) }
    val y = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(DRIFT_INTERVAL_MS)
            val tx = Random.nextInt(-DRIFT_DP, DRIFT_DP + 1).toFloat()
            val ty = Random.nextInt(-DRIFT_DP, DRIFT_DP + 1).toFloat()
            kotlinx.coroutines.coroutineScope {
                launch { x.animateTo(tx, tween(DRIFT_GLIDE_MS)) }
                launch { y.animateTo(ty, tween(DRIFT_GLIDE_MS)) }
            }
        }
    }
    return offset { IntOffset(x.value.dp.roundToPx(), y.value.dp.roundToPx()) }
}

/**
 * The iPhone's lock screen with music playing, as its always-on display shows
 * it: the artwork large across the top, running down into a blurred wash of
 * its own colours that fills the screen; the time and date small along the
 * top; and the frosted Now Playing card low down with the track, its progress
 * and its controls.
 *
 * The card's controls work as they do there — like, previous, play/pause and
 * next — without waking the app; a touch anywhere else wakes it.
 */
@Composable
private fun AppleMusicIdleFace(
    song: Song,
    isPlaying: Boolean,
    position: PlaybackPosition,
    durationMs: Long,
    liked: Boolean,
    onToggleLike: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
) {
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
        val pattern = if (DateFormat.is24HourFormat(context)) "HH:mm" else "h:mm"
        SimpleDateFormat(pattern, locale).format(Date(now))
    }
    val date = remember(now) {
        SimpleDateFormat(DateFormat.getBestDateTimePattern(locale, "EEEMMMd"), locale).format(Date(now))
    }
    // As large as the screen is wide, for the art and the wash both.
    val art = remember(song.thumbnailUrl) { song.artworkAt(1200) ?: song.thumbnailUrl }
    val sf = SfPro

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val width = maxWidth
        // The wash: the artwork blurred out to the edges. Blur needs Android
        // 12; before that, a dim copy does the same job.
        AsyncImage(
            model = art,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) 1f else 0.55f }
                .blur(60.dp),
        )
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.12f)),
        )
        // The artwork itself, square across the top, fading into the wash.
        AsyncImage(
            model = art,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .padding(top = width * 0.16f)
                .fillMaxWidth()
                .aspectRatio(1f)
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawContent()
                    drawRect(
                        Brush.verticalGradient(
                            0.62f to Color.Black,
                            1f to Color.Transparent,
                        ),
                        blendMode = BlendMode.DstIn,
                    )
                },
        )

        Column(
            Modifier
                .fillMaxSize()
                .burnInDrift()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // ---- Time and date, small, along the top ----
            Row(
                Modifier.padding(top = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = time,
                    color = Color.White,
                    fontFamily = sf,
                    fontWeight = FontWeight.Bold,
                    fontSize = 20.sp,
                    style = AppleShadowText,
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    text = date,
                    color = Color.White,
                    fontFamily = sf,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 20.sp,
                    style = AppleShadowText,
                )
            }
            Spacer(Modifier.weight(1f))

            // ---- The Now Playing card ----
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(26.dp))
                    .background(Color.White.copy(alpha = 0.20f))
                    .border(0.6.dp, Color.White.copy(alpha = 0.25f), RoundedCornerShape(26.dp))
                    .padding(horizontal = 18.dp, vertical = 14.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = song.title,
                    color = Color.White,
                    fontFamily = sf,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = song.artist,
                    color = Color.White.copy(alpha = 0.72f),
                    fontFamily = sf,
                    fontSize = 16.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(10.dp))
                val positionMs = position.positionMs
                val fraction = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = clock(positionMs),
                        color = Color.White.copy(alpha = 0.6f),
                        fontFamily = sf,
                        fontSize = 11.sp,
                        modifier = Modifier.width(40.dp),
                    )
                    Canvas(
                        Modifier
                            .weight(1f)
                            .height(5.dp),
                    ) {
                        val r = CornerRadius(size.height / 2)
                        drawRoundRect(Color.White.copy(alpha = 0.28f), cornerRadius = r)
                        drawRoundRect(
                            Color.White.copy(alpha = 0.85f),
                            size = Size(size.width * fraction, size.height),
                            cornerRadius = r,
                        )
                    }
                    Text(
                        text = if (durationMs > 0) "-" + clock(durationMs - positionMs) else "",
                        color = Color.White.copy(alpha = 0.6f),
                        fontFamily = sf,
                        fontSize = 11.sp,
                        textAlign = TextAlign.End,
                        modifier = Modifier.width(40.dp),
                    )
                }
                Spacer(Modifier.height(10.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CardButton(if (liked) Icons.Rounded.Star else Icons.Rounded.StarBorder, 26.dp, onToggleLike)
                    CardButton(Icons.Rounded.FastRewind, 34.dp, onPrevious)
                    CardButton(if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, 42.dp, onPlayPause)
                    CardButton(Icons.Rounded.FastForward, 34.dp, onNext)
                    // Where AirPlay sits on the phone; kept as space so the
                    // transport stays centred.
                    Spacer(Modifier.size(44.dp))
                }
            }
            Spacer(Modifier.height(36.dp))
        }
    }
}

/** A card control: a glyph with a generous touch target, taps its own. */
@Composable
private fun CardButton(icon: ImageVector, size: Dp, onTap: () -> Unit) {
    val view = LocalView.current
    Box(
        Modifier
            .size(44.dp)
            .clip(CircleShape)
            .clickable {
                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                onTap()
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(size))
    }
}

private val SfPro = FontFamily(
    Font(R.font.sf_pro_display_regular, FontWeight.W400),
    Font(R.font.sf_pro_display_semibold, FontWeight.W600),
    Font(R.font.sf_pro_display_bold, FontWeight.W700),
)

private val AppleShadowText = TextStyle(shadow = Shadow(Color.Black.copy(alpha = 0.25f), Offset(0f, 1f), 4f))

private fun clock(ms: Long): String {
    val total = (ms.coerceAtLeast(0L) / 1000).toInt()
    return "%d:%02d".format(total / 60, total % 60)
}

/** Enough over-scan that a [DRIFT_DP] nudge can't uncover an edge. */
private const val FULL_BLEED_SCALE = 1.02f
private const val PAUSED_GRACE_MS = 60_000L
private const val HINT_MS = 2_000L
private const val DRIFT_DP = 2
private const val DRIFT_INTERVAL_MS = 180_000L
private const val DRIFT_GLIDE_MS = 4_000
