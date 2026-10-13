package com.music.bitchord.ui.player

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.music.bitchord.R
import com.music.bitchord.data.model.BrowseItem
import com.music.bitchord.data.model.DetailPage
import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.LibraryPage
import com.music.bitchord.data.model.SearchResult
import com.music.bitchord.data.model.ShelfItem
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.UiState
import com.music.bitchord.playback.PlaybackPosition

/** What the iPod interface asks the app to do. */
class IpodActions(
    /** Plays [songs] from the given index, as a list called [title] (with its page, if it has one). */
    val playSongs: (songs: List<Song>, index: Int, title: String, browseId: String?) -> Unit,
    val shuffleSongs: (songs: List<Song>, title: String, browseId: String?) -> Unit,
    /** A shelf card that is a track: starts a station from it. */
    val playShelfItem: (item: ShelfItem, shelfTitle: String) -> Unit,
    /** A shelf card that is a page: opens it, and says whether a page did open. */
    val openShelfItem: (item: ShelfItem) -> Boolean,
    val openBrowse: (item: BrowseItem) -> Unit,
    val closeDetail: () -> Unit,
    val playSearchSong: (song: Song) -> Unit,
    val search: (query: String) -> Unit,
    val loadLibrary: () -> Unit,
    val loadHome: () -> Unit,
    val playPause: () -> Unit,
    val next: () -> Unit,
    val previous: () -> Unit,
    val seek: (Long) -> Unit,
    val toggleShuffle: () -> Unit,
    val cycleRepeat: () -> Unit,
    val openSongMenu: (Song) -> Unit,
    /** Back to the regular interface. */
    val exit: () -> Unit,
    /** Back to the regular interface, on its settings. */
    val openSettings: () -> Unit,
)

private sealed interface IpodPage {
    data object Main : IpodPage
    data object Music : IpodPage
    data object ListenNow : IpodPage
    data object Settings : IpodPage
    data object Search : IpodPage
    data object SearchResults : IpodPage
    data object NowPlaying : IpodPage
    data class Shelf(val shelf: HomeShelf) : IpodPage
    data class Songs(val title: String, val songs: List<Song>) : IpodPage
    data class Detail(val browseId: String, val title: String) : IpodPage
}

/** A page on the stack, with where its highlight and its scroll are. */
private class IpodEntry(val page: IpodPage) {
    val selected: MutableIntState = mutableIntStateOf(0)
    val list = LazyListState()
}

private class IpodRow(
    val label: String,
    val detail: String? = null,
    val opens: Boolean = false,
    /** The track this row is, for holding the centre button. */
    val song: Song? = null,
    val onSelect: () -> Unit = {},
)

/**
 * The whole app as a click-wheel iPod: menus on the LCD, the wheel to move
 * through them, the centre button to choose, MENU to go back. Built from the
 * same pages the regular interface reads — the library, Listen Now, a page's
 * tracks, search — so it is another way into the same music rather than a
 * second copy of it.
 *
 * Now Playing is the iPod player style's own screen, drawn in the same body,
 * so going in and out of it changes only the LCD.
 *
 * Search is spelled out with the wheel, a letter at a time, as on the device.
 */
@Composable
fun IpodApp(
    home: UiState<List<HomeShelf>>,
    library: UiState<LibraryPage>,
    detail: DetailPage?,
    results: UiState<List<SearchResult>>?,
    initialQuery: String,
    song: Song?,
    isPlaying: Boolean,
    position: PlaybackPosition,
    durationMs: Long,
    queueIndex: Int,
    queueSize: Int,
    shuffleEnabled: Boolean,
    repeatMode: Int,
    nowPlayingRequests: Int,
    actions: IpodActions,
    modifier: Modifier = Modifier,
) {
    val stack = remember { mutableStateListOf(IpodEntry(IpodPage.Main)) }
    val top = stack.last()

    fun push(page: IpodPage) {
        stack.add(IpodEntry(page))
    }
    fun pop() {
        if (stack.size <= 1) return
        val leaving = stack.removeAt(stack.lastIndex)
        if (leaving.page is IpodPage.Detail) actions.closeDetail()
    }

    // The app asked for the player (a notification, a link): show it here.
    LaunchedEffect(nowPlayingRequests) {
        if (nowPlayingRequests > 0 && song != null && top.page != IpodPage.NowPlaying) push(IpodPage.NowPlaying)
    }
    // Nothing left to show as playing.
    LaunchedEffect(song == null, top) {
        if (song == null && top.page == IpodPage.NowPlaying) pop()
    }

    BackHandler(enabled = stack.size > 1) { pop() }

    // Kept above the Now Playing branch, so a visit there doesn't lose them.
    var query by remember { mutableStateOf(initialQuery) }
    val keys = remember { searchKeys() }
    var keyIndex by remember { mutableIntStateOf(0) }

    // Anything that lands on the body and not on a control stops here, rather
    // than reaching the regular interface underneath.
    val absorb = Modifier.pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                awaitPointerEvent().changes.forEach { it.consume() }
            }
        }
    }

    if (top.page == IpodPage.NowPlaying && song != null) {
        Box(modifier.then(absorb)) {
            IpodPlayer(
                song = song,
                isPlaying = isPlaying,
                position = position,
                durationMs = durationMs,
                queueIndex = queueIndex,
                queueSize = queueSize,
                onPlayPause = actions.playPause,
                onNext = actions.next,
                onPrevious = actions.previous,
                onSeek = actions.seek,
                onMenu = ::pop,
                onOpenSongMenu = { actions.openSongMenu(song) },
            )
        }
        return
    }

    val finish = rememberIpodFinish()

    // ---- What each page lists ----
    val loading = stringResource(R.string.ipod_loading)
    val couldntLoad = stringResource(R.string.ipod_couldnt_load)
    val nothingHere = stringResource(R.string.ipod_nothing_here)
    val shuffleSongsLabel = stringResource(R.string.ipod_shuffle_songs)
    val likedSongsLabel = stringResource(R.string.ipod_liked_songs)
    val songsLabel = stringResource(R.string.ipod_songs)
    val musicLabel = stringResource(R.string.ipod_music)
    val listenNowLabel = stringResource(R.string.listen_now)
    val searchLabel = stringResource(R.string.search)
    val settingsLabel = stringResource(R.string.ipod_settings)
    val nowPlayingLabel = stringResource(R.string.ipod_now_playing)
    val onLabel = stringResource(R.string.ipod_on)
    val offLabel = stringResource(R.string.ipod_off)
    val repeatAll = stringResource(R.string.ipod_repeat_all)
    val repeatOne = stringResource(R.string.ipod_repeat_one)
    val shuffleLabel = stringResource(R.string.ipod_shuffle)
    val repeatLabel = stringResource(R.string.ipod_repeat)
    val classicLabel = stringResource(R.string.ipod_classic_interface)
    val moreSettingsLabel = stringResource(R.string.ipod_more_settings)
    val searchingLabel = stringResource(R.string.ipod_searching)
    val noResultsLabel = stringResource(R.string.ipod_no_results)

    val libraryPage = (library as? UiState.Success)?.data

    fun songRows(songs: List<Song>, title: String, browseId: String?): List<IpodRow> =
        if (songs.isEmpty()) {
            listOf(IpodRow(nothingHere))
        } else {
            listOf(IpodRow(shuffleSongsLabel, onSelect = {
                actions.shuffleSongs(songs, title, browseId)
                push(IpodPage.NowPlaying)
            })) + songs.mapIndexed { i, s ->
                IpodRow(s.title, song = s, onSelect = {
                    actions.playSongs(songs, i, title, browseId)
                    push(IpodPage.NowPlaying)
                })
            }
        }

    fun shelfRow(item: ShelfItem, shelfTitle: String): IpodRow {
        val isPage = item.videoId == null && item.browseId != null
        return IpodRow(item.title, opens = isPage, onSelect = {
            when {
                item.videoId != null -> {
                    actions.playShelfItem(item, shelfTitle)
                    push(IpodPage.NowPlaying)
                }
                item.browseId != null -> if (actions.openShelfItem(item)) {
                    push(IpodPage.Detail(item.browseId!!, item.title))
                }
            }
        })
    }

    fun <T> stateRows(state: UiState<T>?, rows: (T) -> List<IpodRow>): List<IpodRow> = when (state) {
        is UiState.Success -> rows(state.data).ifEmpty { listOf(IpodRow(nothingHere)) }
        is UiState.Error -> listOf(IpodRow(couldntLoad))
        UiState.Loading, null -> listOf(IpodRow(loading))
    }

    val page = top.page
    val title: String
    val rows: List<IpodRow>
    when (page) {
        IpodPage.Main -> {
            title = "iPod"
            rows = buildList {
                add(IpodRow(musicLabel, opens = true, onSelect = {
                    if (library !is UiState.Success) actions.loadLibrary()
                    push(IpodPage.Music)
                }))
                add(IpodRow(listenNowLabel, opens = true, onSelect = {
                    if (home is UiState.Error) actions.loadHome()
                    push(IpodPage.ListenNow)
                }))
                add(IpodRow(searchLabel, opens = true, onSelect = { push(IpodPage.Search) }))
                add(IpodRow(settingsLabel, opens = true, onSelect = { push(IpodPage.Settings) }))
                val pool = libraryPage?.likedSongs?.ifEmpty { null } ?: libraryPage?.librarySongs.orEmpty()
                if (pool.isNotEmpty()) {
                    add(IpodRow(shuffleSongsLabel, onSelect = {
                        actions.shuffleSongs(pool, shuffleSongsLabel, null)
                        push(IpodPage.NowPlaying)
                    }))
                }
                if (song != null) {
                    add(IpodRow(nowPlayingLabel, opens = true, onSelect = { push(IpodPage.NowPlaying) }))
                }
            }
        }
        IpodPage.Music -> {
            title = musicLabel
            rows = stateRows(library) { lib ->
                buildList {
                    if (lib.likedSongs.isNotEmpty()) {
                        add(IpodRow(likedSongsLabel, opens = true, onSelect = { push(IpodPage.Songs(likedSongsLabel, lib.likedSongs)) }))
                    }
                    if (lib.librarySongs.isNotEmpty()) {
                        add(IpodRow(songsLabel, opens = true, onSelect = { push(IpodPage.Songs(songsLabel, lib.librarySongs)) }))
                    }
                    lib.shelves.filter { it.items.isNotEmpty() }.forEach { shelf ->
                        add(IpodRow(shelf.title, opens = true, onSelect = { push(IpodPage.Shelf(shelf)) }))
                    }
                }
            }
        }
        IpodPage.ListenNow -> {
            title = listenNowLabel
            rows = stateRows(home) { shelves ->
                shelves.filter { it.items.isNotEmpty() }.map { shelf ->
                    IpodRow(shelf.title, opens = true, onSelect = { push(IpodPage.Shelf(shelf)) })
                }
            }
        }
        is IpodPage.Shelf -> {
            title = page.shelf.title
            rows = page.shelf.items.map { shelfRow(it, page.shelf.title) }
        }
        is IpodPage.Songs -> {
            title = page.title
            rows = songRows(page.songs, page.title, null)
        }
        is IpodPage.Detail -> {
            val shown = detail?.takeIf { it.browseId == page.browseId }
            title = shown?.title ?: page.title
            rows = if (shown == null) {
                listOf(IpodRow(loading))
            } else {
                val songs = when (val s = shown.songs) {
                    is UiState.Success -> songRows(s.data, shown.title, shown.browseId)
                        .takeUnless { s.data.isEmpty() && shown.sections.isNotEmpty() }
                        .orEmpty()
                    is UiState.Error -> listOf(IpodRow(couldntLoad))
                    UiState.Loading -> listOf(IpodRow(loading))
                }
                val sections = shown.sections.filter { it.items.isNotEmpty() }.map { section ->
                    IpodRow(section.title, opens = true, onSelect = { push(IpodPage.Shelf(section)) })
                }
                (songs + sections).ifEmpty { listOf(IpodRow(nothingHere)) }
            }
        }
        IpodPage.Settings -> {
            title = settingsLabel
            rows = listOf(
                IpodRow(shuffleLabel, detail = if (shuffleEnabled) onLabel else offLabel, onSelect = actions.toggleShuffle),
                IpodRow(
                    repeatLabel,
                    detail = when (repeatMode) {
                        REPEAT_MODE_ONE -> repeatOne
                        REPEAT_MODE_ALL -> repeatAll
                        else -> offLabel
                    },
                    onSelect = actions.cycleRepeat,
                ),
                IpodRow(classicLabel, detail = onLabel, onSelect = actions.exit),
                IpodRow(moreSettingsLabel, opens = true, onSelect = actions.openSettings),
            )
        }
        IpodPage.SearchResults -> {
            title = query.ifBlank { searchLabel }
            rows = when (results) {
                null, UiState.Loading -> listOf(IpodRow(searchingLabel))
                is UiState.Error -> listOf(IpodRow(couldntLoad))
                is UiState.Success -> results.data.map { result ->
                    when (result) {
                        is SearchResult.TopTrack -> searchSongRow(result.song, actions) { push(IpodPage.NowPlaying) }
                        is SearchResult.Track -> searchSongRow(result.song, actions) { push(IpodPage.NowPlaying) }
                        is SearchResult.Browse -> IpodRow(result.item.title, opens = true, onSelect = {
                            actions.openBrowse(result.item)
                            push(IpodPage.Detail(result.item.browseId, result.item.title))
                        })
                    }
                }.ifEmpty { listOf(IpodRow(noResultsLabel)) }
            }
        }
        IpodPage.Search, IpodPage.NowPlaying -> {
            title = searchLabel
            rows = emptyList()
        }
    }

    // A list can shrink under the highlight (a page finishing loading, say).
    val selected = top.selected
    val selectedIndex = selected.intValue.coerceIn(0, (rows.size - 1).coerceAtLeast(0))

    Box(modifier.then(absorb)) {
        IpodBody(
            finish = finish,
            lcd = { lcdModifier ->
                IpodLcd(
                    title = title,
                    isPlaying = if (song != null) isPlaying else null,
                    modifier = lcdModifier,
                ) {
                    when (page) {
                        IpodPage.Search -> SearchLcd(query = query, keys = keys, keyIndex = keyIndex)
                        IpodPage.Main -> Row(Modifier.fillMaxSize()) {
                            MenuList(
                                rows = rows,
                                selected = selectedIndex,
                                list = top.list,
                                modifier = Modifier
                                    .weight(0.56f)
                                    .fillMaxHeight(),
                            )
                            // The main menu's other half: the artwork.
                            val art = song?.thumbnailUrl ?: libraryPage?.likedSongs?.firstOrNull()?.thumbnailUrl
                            Box(
                                Modifier
                                    .weight(0.44f)
                                    .fillMaxHeight()
                                    .background(Color(0xFFCCD1D8)),
                            ) {
                                if (art != null) {
                                    AsyncImage(
                                        model = art,
                                        contentDescription = null,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize(),
                                    )
                                }
                                // The soft edge where the menu meets it.
                                Box(
                                    Modifier
                                        .fillMaxHeight()
                                        .width(10.dp)
                                        .background(
                                            Brush.horizontalGradient(listOf(Color.Black.copy(alpha = 0.25f), Color.Transparent)),
                                        ),
                                )
                            }
                        }
                        else -> MenuList(
                            rows = rows,
                            selected = selectedIndex,
                            list = top.list,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            },
            wheel = { wheelModifier ->
                ClickWheel(
                    finish = finish,
                    onMenu = ::pop,
                    onNext = actions.next,
                    onPrevious = actions.previous,
                    onPlayPause = actions.playPause,
                    onCenter = {
                        if (page == IpodPage.Search) {
                            when (val key = keys[keyIndex]) {
                                SearchKey.Space -> query += " "
                                SearchKey.Delete -> query = query.dropLast(1)
                                SearchKey.Done -> if (query.isNotBlank()) {
                                    actions.search(query.trim())
                                    push(IpodPage.SearchResults)
                                }
                                is SearchKey.Letter -> query += key.char
                            }
                        } else {
                            rows.getOrNull(selectedIndex)?.onSelect?.invoke()
                        }
                    },
                    onCenterHold = {
                        rows.getOrNull(selectedIndex)?.song?.let(actions.openSongMenu)
                    },
                    onTurn = { steps ->
                        if (page == IpodPage.Search) {
                            keyIndex = (keyIndex + steps).mod(keys.size)
                        } else if (rows.isNotEmpty()) {
                            selected.intValue = (selectedIndex + steps).coerceIn(0, rows.lastIndex)
                        }
                    },
                    modifier = wheelModifier,
                )
            },
        )
    }
}

private fun searchSongRow(song: Song, actions: IpodActions, then: () -> Unit) =
    IpodRow("${song.title} – ${song.artist}", song = song, onSelect = {
        actions.playSearchSong(song)
        then()
    })

private val RowHeight = 25.dp
private val HighlightTop = Color(0xFF6CB3F8)
private val HighlightBottom = Color(0xFF1F6FD6)

/**
 * The list, with the highlight bar the wheel moves and a scroll bar when it
 * runs past the screen.
 */
@Composable
private fun MenuList(
    rows: List<IpodRow>,
    selected: Int,
    list: LazyListState,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    BoxWithConstraints(modifier) {
        val visible = (maxHeight / RowHeight).toInt().coerceAtLeast(1)
        // Keeps the highlight on screen, scrolling a row at a time like the
        // device rather than recentring.
        LaunchedEffect(selected, visible) {
            val first = list.firstVisibleItemIndex
            when {
                selected < first -> list.scrollToItem(selected)
                selected >= first + visible -> list.scrollToItem(selected - visible + 1)
            }
        }
        val scrolls = rows.size > visible
        Row(Modifier.fillMaxSize()) {
            LazyColumn(
                state = list,
                userScrollEnabled = false,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
            ) {
                itemsIndexed(rows) { index, row ->
                    MenuRow(row, highlighted = index == selected)
                }
            }
            if (scrolls) {
                Canvas(
                    Modifier
                        .width(9.dp)
                        .fillMaxHeight()
                        .padding(horizontal = 1.dp, vertical = 2.dp),
                ) {
                    drawRoundRect(
                        color = Color(0xFF8B929B),
                        style = Stroke(1.dp.toPx()),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(3.dp.toPx()),
                    )
                    val thumbH = (size.height * visible / rows.size).coerceAtLeast(with(density) { 10.dp.toPx() })
                    val maxFirst = (rows.size - visible).coerceAtLeast(1)
                    val y = (size.height - thumbH) * (list.firstVisibleItemIndex.toFloat() / maxFirst).coerceIn(0f, 1f)
                    drawRoundRect(
                        brush = Brush.horizontalGradient(listOf(HighlightTop, HighlightBottom)),
                        topLeft = Offset(0f, y),
                        size = androidx.compose.ui.geometry.Size(size.width, thumbH),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(3.dp.toPx()),
                    )
                }
            }
        }
    }
}

@Composable
private fun MenuRow(row: IpodRow, highlighted: Boolean) {
    val textColor = if (highlighted) Color.White else LcdText
    Row(
        Modifier
            .fillMaxWidth()
            .height(RowHeight)
            .then(
                if (highlighted) {
                    Modifier.background(Brush.verticalGradient(listOf(HighlightTop, HighlightBottom)))
                } else {
                    Modifier
                },
            )
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = row.label,
            color = textColor,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        row.detail?.let {
            Text(
                text = it,
                color = if (highlighted) Color.White else LcdSubtext,
                fontSize = 13.sp,
                maxLines = 1,
            )
        }
        if (row.opens) {
            Canvas(
                Modifier
                    .padding(start = 4.dp)
                    .size(width = 7.dp, height = 11.dp),
            ) {
                val w = 2.dp.toPx()
                drawLine(textColor, Offset(0f, 0f), Offset(size.width, size.height / 2), w)
                drawLine(textColor, Offset(size.width, size.height / 2), Offset(0f, size.height), w)
            }
        }
    }
}

private sealed interface SearchKey {
    data class Letter(val char: Char) : SearchKey
    data object Space : SearchKey
    data object Delete : SearchKey
    data object Done : SearchKey
}

private fun searchKeys(): List<SearchKey> =
    ('A'..'Z').map { SearchKey.Letter(it) } +
        ('0'..'9').map { SearchKey.Letter(it) } +
        listOf(SearchKey.Space, SearchKey.Delete, SearchKey.Done)

/**
 * The device's search screen: what's been spelled so far, and the letter
 * strip along the bottom that the wheel runs along.
 */
@Composable
private fun SearchLcd(query: String, keys: List<SearchKey>, keyIndex: Int) {
    val space = stringResource(R.string.ipod_search_space)
    val delete = stringResource(R.string.ipod_search_delete)
    val done = stringResource(R.string.ipod_search_done)
    fun label(key: SearchKey) = when (key) {
        is SearchKey.Letter -> key.char.toString()
        SearchKey.Space -> space
        SearchKey.Delete -> delete
        SearchKey.Done -> done
    }
    Column(
        Modifier
            .fillMaxSize()
            .padding(10.dp),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(4.dp))
                .background(Color.White)
                .padding(horizontal = 8.dp, vertical = 6.dp),
        ) {
            Text(
                text = "$query|",
                color = LcdText,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = stringResource(R.string.ipod_search_hint),
            color = LcdSubtext,
            fontSize = 11.sp,
        )
        Spacer(Modifier.weight(1f))
        // A window on the strip, the chosen key in the middle.
        val window = 3
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(4.dp))
                .background(Color(0xFFDCE1E8))
                .padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            for (offset in -window..window) {
                val key = keys[(keyIndex + offset).mod(keys.size)]
                val chosen = offset == 0
                Box(
                    Modifier
                        .clip(RoundedCornerShape(3.dp))
                        .then(
                            if (chosen) {
                                Modifier.background(Brush.verticalGradient(listOf(HighlightTop, HighlightBottom)))
                            } else {
                                Modifier
                            },
                        )
                        .padding(horizontal = 5.dp, vertical = 2.dp),
                ) {
                    Text(
                        text = label(key),
                        color = if (chosen) Color.White else LcdText,
                        fontSize = if (chosen) 17.sp else 13.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

// Player.REPEAT_MODE_* without pulling Media3 in here.
private const val REPEAT_MODE_ONE = 1
private const val REPEAT_MODE_ALL = 2
