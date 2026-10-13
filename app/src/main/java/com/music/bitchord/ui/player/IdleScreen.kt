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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.random.Random

/** Which face the idle screen wears, if any. */
enum class IdleFace { APPLE_MUSIC, WALKMAN, TP7 }

/**
 * When someone last touched the app.
 *
 * The activity reports its own touches and keys through `onUserInteraction`,
 * but the player and the song menu are sheets in windows of their own, whose
 * touches never pass through the activity — those report through
 * [pokeIdleOnTouch].
 */
object IdleActivity {
    private val _lastInteraction = MutableStateFlow(SystemClock.uptimeMillis())
    val lastInteraction: StateFlow<Long> = _lastInteraction.asStateFlow()

    fun poke() {
        // Once a second is plenty for a timer counted in tens of seconds. A
        // scroll reports every frame, and each report would otherwise wake the
        // idle screen's timer and recompose it at the screen's refresh rate.
        val now = SystemClock.uptimeMillis()
        if (now - _lastInteraction.value < POKE_INTERVAL_MS) return
        _lastInteraction.value = now
    }
}

/** Counts every touch inside this node as activity, without taking it. */
fun Modifier.pokeIdleOnTouch(): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            awaitPointerEvent(PointerEventPass.Initial)
            IdleActivity.poke()
        }
    }
}

/**
 * An always-on face for the app, the nearest thing to one Android lets an app
 * have: while music plays and nobody touches the phone for [delaySeconds], a
 * dim full-screen face takes over, and any touch puts it away.
 *
 * - The screen is held on while music plays with this enabled; otherwise the
 *   system's own timeout would turn it off before the face ever showed.
 * - The face's window drops the brightness and hides the system bars.
 * - The content drifts by a few dp each minute, so nothing sits on the same
 *   pixels for hours.
 * - Paused for a while, the face steps aside and lets the screen sleep.
 *
 * Shown in a dialog so it lands above the player, which is a sheet in a
 * window of its own.
 */
@Composable
fun IdleScreenHost(
    face: IdleFace?,
    delaySeconds: Int,
    song: Song?,
    isPlaying: Boolean,
    position: PlaybackPosition,
    durationMs: Long,
    queueIndex: Int = 0,
    /** How bright the face is, in percent of full: the screen's own level while it's up. */
    brightnessPercent: Int = AppSettings.DEFAULT_IDLE_BRIGHTNESS,
    /** For faces that carry transport keys (the Walkman's side bar). */
    onPlayPause: () -> Unit = {},
    onNext: () -> Unit = {},
    onPrevious: () -> Unit = {},
    liked: Boolean = false,
    onToggleLike: () -> Unit = {},
    /** For the TP-7's deck: the song's words. */
    lyrics: List<LyricLine>? = null,
) {
    var shown by remember { mutableStateOf(false) }
    val last by IdleActivity.lastInteraction.collectAsState()

    // Held on while the face could still come up, and while it is up.
    val view = LocalView.current
    val holdScreen = face != null && song != null && (isPlaying || shown)
    DisposableEffect(view, holdScreen) {
        view.keepScreenOn = holdScreen
        onDispose { view.keepScreenOn = false }
    }

    LaunchedEffect(last, face, delaySeconds, isPlaying, song == null) {
        if (face == null || song == null || !isPlaying) return@LaunchedEffect
        val wait = last + delaySeconds * 1000L - SystemClock.uptimeMillis()
        if (wait > 0) delay(wait)
        shown = true
    }
    LaunchedEffect(shown, isPlaying) {
        if (shown && !isPlaying) {
            delay(PAUSED_GRACE_MS)
            shown = false
        }
    }
    LaunchedEffect(face, song == null) {
        if (face == null || song == null) shown = false
    }
    if (!shown || face == null || song == null) return

    val wake = {
        shown = false
        IdleActivity.poke()
    }
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
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black)
                .graphicsLayer { alpha = fade.value }
                .pointerInput(face) {
                    if (face == IdleFace.WALKMAN || face == IdleFace.APPLE_MUSIC) {
                        // These faces' keys work without waking: they
                        // take their own taps, and anything they don't take —
                        // a touch anywhere else — wakes the app as it lifts.
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false)
                            val up = waitForUpOrCancellation()
                            if (up != null) wake()
                        }
                    } else {
                        // Any touch at all wakes the app, and none reaches the face.
                        awaitEachGesture {
                            val down = awaitFirstDown(pass = PointerEventPass.Initial)
                            down.consume()
                            wake()
                        }
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
                        liked = false,
                        shuffleEnabled = false,
                        repeatMode = 0,
                        autoplayEnabled = false,
                        onPlayPause = {},
                        onStop = {},
                        onNext = {},
                        onPrevious = {},
                        onSeek = {},
                        onToggleLike = {},
                        onToggleShuffle = {},
                        onCycleRepeat = {},
                        onToggleAutoplay = {},
                        onClose = {},
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
private const val POKE_INTERVAL_MS = 1_000L
private const val DRIFT_DP = 2
private const val DRIFT_INTERVAL_MS = 180_000L
private const val DRIFT_GLIDE_MS = 4_000
