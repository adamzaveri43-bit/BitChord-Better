package com.music.bitchord.ui.player

import android.os.Build
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
                    if (face == IdleFace.WALKMAN) {
                        // The Walkman's side keys work without waking: they
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
                IdleFace.APPLE_MUSIC -> AppleMusicIdleFace(song, position, durationMs)
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

/** Nudges the content a few dp each minute, against burn-in. */
@Composable
private fun Modifier.burnInDrift(): Modifier {
    var x by remember { mutableStateOf(0) }
    var y by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000L)
            x = Random.nextInt(-DRIFT_DP, DRIFT_DP + 1)
            y = Random.nextInt(-DRIFT_DP, DRIFT_DP + 1)
        }
    }
    return offset(x.dp, y.dp)
}

/**
 * The iPhone's always-on lock screen with music playing: the date and a big
 * clock up top, the artwork large and dimmed over a faint wash of its own
 * colours, and the track underneath.
 */
@Composable
private fun AppleMusicIdleFace(
    song: Song,
    position: PlaybackPosition,
    durationMs: Long,
) {
    val context = LocalContext.current
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            // Wake on the minute rather than polling.
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
        SimpleDateFormat(DateFormat.getBestDateTimePattern(locale, "EEEEMMMMd"), locale).format(Date(now))
    }

    Box(Modifier.fillMaxSize()) {
        // The artwork's colours, blurred to a wash. Blur needs Android 12;
        // before that the wash is just a much dimmer copy.
        AsyncImage(
            model = song.thumbnailUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) 0.35f else 0.12f }
                .blur(80.dp),
        )
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.45f)),
        )
        Column(
            Modifier
                .fillMaxSize()
                .burnInDrift()
                .padding(horizontal = 36.dp, vertical = 56.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = date,
                color = Color.White.copy(alpha = 0.7f),
                fontSize = 19.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = time,
                color = Color.White.copy(alpha = 0.72f),
                fontSize = 96.sp,
                lineHeight = 100.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = (-2).sp,
            )
            Spacer(Modifier.weight(1f))
            AsyncImage(
                model = song.thumbnailUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth(0.82f)
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(14.dp))
                    .graphicsLayer { alpha = 0.6f },
            )
            Spacer(Modifier.height(22.dp))
            Text(
                text = song.title,
                color = Color.White.copy(alpha = 0.72f),
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
            Text(
                text = song.artist,
                color = Color.White.copy(alpha = 0.5f),
                fontSize = 15.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(14.dp))
            val fraction = if (durationMs > 0) {
                (position.positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
            } else {
                0f
            }
            Canvas(
                Modifier
                    .fillMaxWidth(0.82f)
                    .height(4.dp),
            ) {
                val r = CornerRadius(size.height / 2)
                drawRoundRect(Color.White.copy(alpha = 0.18f), cornerRadius = r)
                drawRoundRect(
                    Color.White.copy(alpha = 0.5f),
                    size = Size(size.width * fraction, size.height),
                    cornerRadius = r,
                )
            }
        }
    }
}

/** Enough over-scan that a [DRIFT_DP] nudge can't uncover an edge. */
private const val FULL_BLEED_SCALE = 1.04f
private const val PAUSED_GRACE_MS = 60_000L
private const val POKE_INTERVAL_MS = 1_000L
private const val DRIFT_DP = 6
