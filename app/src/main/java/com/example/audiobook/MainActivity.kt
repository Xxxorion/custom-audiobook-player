package com.example.audiobook

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.round

class LibraryViewModelFactory(private val repository: AudiobookRepository) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(LibraryViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return LibraryViewModel(repository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}

class MainActivity : ComponentActivity() {
    private lateinit var repository: AudiobookRepository
    private lateinit var libraryViewModel: LibraryViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repository = AudiobookRepository(this)
        libraryViewModel = ViewModelProvider(this, LibraryViewModelFactory(repository))[LibraryViewModel::class.java]

        setContent {
            val context = LocalContext.current
            var controller by remember { mutableStateOf<Player?>(null) }

            DisposableEffect(context) {
                val sessionToken = SessionToken(context, ComponentName(context, AudiobookPlaybackService::class.java))
                val controllerFuture = MediaController.Builder(context, sessionToken).buildAsync()
                controllerFuture.addListener({
                    try { controller = controllerFuture.get() } catch (_: Throwable) {}
                }, ContextCompat.getMainExecutor(context))
                onDispose { MediaController.releaseFuture(controllerFuture) }
            }

            var isDarkTheme by remember { mutableStateOf(repository.getPreferences().getBoolean("is_dark_theme", true)) }

            val darkColors = darkColorScheme(
                background = Color(0xFF0C1A14), surface = Color(0xFF142B20), surfaceVariant = Color(0xFF1E3F30),
                primary = Color(0xFFD4AF37), onPrimary = Color.Black, secondary = Color(0xFF2D5A27), tertiary = Color(0xFF8B5A2B)
            )
            val lightColors = lightColorScheme(
                background = Color(0xFFF0F4F1), surface = Color(0xFFFFFFFF), surfaceVariant = Color(0xFFDCE6E0),
                primary = Color(0xFFB58500), onPrimary = Color.White, secondary = Color(0xFF3E7A45), tertiary = Color(0xFF8B5A2B)
            )

            MaterialTheme(colorScheme = if (isDarkTheme) darkColors else lightColors) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    if (controller != null) {
                        FablewoodAudiobookApp(
                            player = controller!!,
                            viewModel = libraryViewModel,
                            isDarkTheme = isDarkTheme,
                            onToggleTheme = {
                                isDarkTheme = !isDarkTheme
                                repository.getPreferences().edit().putBoolean("is_dark_theme", isDarkTheme).apply()
                            }
                        )
                    } else {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FablewoodAudiobookApp(
    player: Player,
    viewModel: LibraryViewModel,
    isDarkTheme: Boolean,
    onToggleTheme: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)

    var targetBookForCustomCover by remember { mutableStateOf<Audiobook?>(null) }
    var showViewDialog by remember { mutableStateOf(false) }
    var showSortDialog by remember { mutableStateOf(false) }
    var showSettingsDialog by remember { mutableStateOf(false) }
    var showAudioFXDialog by remember { mutableStateOf(false) }
    var showChaptersDialog by remember { mutableStateOf(false) }
    var showBookmarksDialog by remember { mutableStateOf(false) }
    var showSpeedDialog by remember { mutableStateOf(false) }
    var newBookmarkNoteText by remember { mutableStateOf("") }
    var showAddBookmarkPrompt by remember { mutableStateOf(false) }
    var showSleepMenu by remember { mutableStateOf(false) }
    var showAnalyticsDialog by remember { mutableStateOf(false) }

    val activeBook = remember(viewModel.books, viewModel.currentBookId) {
        viewModel.books.find { it.id == viewModel.currentBookId }
    }

    val customCoverLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { selectedUri ->
            targetBookForCustomCover?.let { book -> viewModel.setCustomCover(book.id, selectedUri) }
        }
    }

    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let {
            val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            try { context.contentResolver.takePersistableUriPermission(it, takeFlags) } catch (_: Throwable) {}
            viewModel.repository.saveSelectedFolderUri(it.toString())

            coroutineScope.launch {
                val scannedBooks = viewModel.repository.scanFolderForAudiobooks(it)
                if (scannedBooks.isNotEmpty()) {
                    viewModel.books = scannedBooks
                    viewModel.repository.saveBooksCache(scannedBooks)

                    val selectedBook = scannedBooks[0]
                    viewModel.currentBookId = selectedBook.id
                    viewModel.applyBookSpecificSpeed(selectedBook.id)
                    viewModel.refreshBookmarks()
                    viewModel.loadChapters(selectedBook)
                    viewModel.updateCoverArt(selectedBook)

                    val savedTrackIdx = viewModel.repository.getBookTrackIndex(selectedBook.id)
                    val savedPos = viewModel.repository.getBookPosition(selectedBook.id)
                    viewModel.currentPosition = savedPos
                    playBook(player, selectedBook, savedTrackIdx, savedPos, false)
                }
            }
        }
    }

    BackHandler(enabled = viewModel.isCarModeActive || viewModel.isPlayerFullScreen) {
        if (viewModel.isCarModeActive) viewModel.isCarModeActive = false
        else viewModel.isPlayerFullScreen = false
    }

    fun sendCustomCommand(action: String, enabled: Boolean) {
        (player as? MediaController)?.sendCustomCommand(
            SessionCommand(action, Bundle.EMPTY),
            Bundle().apply { putBoolean(AudiobookPlaybackService.KEY_ENABLED, enabled) }
        )
    }

    LaunchedEffect(viewModel.playbackSpeed, viewModel.playbackPitch) {
        player.playbackParameters = PlaybackParameters(viewModel.playbackSpeed, viewModel.playbackPitch)
    }

    LaunchedEffect(viewModel.silenceTrimEnabled) {
        sendCustomCommand(AudiobookPlaybackService.ACTION_SET_SILENCE_TRIM, viewModel.silenceTrimEnabled)
    }

    LaunchedEffect(viewModel.volumeBoostEnabled) {
        sendCustomCommand(AudiobookPlaybackService.ACTION_SET_VOLUME_BOOST, viewModel.volumeBoostEnabled)
    }

    LaunchedEffect(Unit) {
        val savedUriStr = viewModel.repository.getSelectedFolderUri()
        if (!savedUriStr.isNullOrEmpty()) {
            coroutineScope.launch {
                try {
                    val lastBookId = viewModel.repository.getLastBookId()
                    val initialBook = viewModel.books.find { b -> b.id == lastBookId } ?: viewModel.books.firstOrNull()

                    if (initialBook != null && viewModel.currentBookId.isEmpty()) {
                        viewModel.currentBookId = initialBook.id
                        viewModel.applyBookSpecificSpeed(initialBook.id)
                        viewModel.refreshBookmarks()
                        viewModel.loadChapters(initialBook)
                        viewModel.updateCoverArt(initialBook)

                        if (player.mediaItemCount > 0) {
                            viewModel.isPlaying = player.isPlaying
                            viewModel.currentPosition = player.currentPosition.coerceAtLeast(0L)
                            viewModel.duration = player.duration.coerceAtLeast(0L)
                        } else {
                            val savedTrackIdx = viewModel.repository.getBookTrackIndex(initialBook.id)
                            val savedPos = viewModel.repository.getBookPosition(initialBook.id)
                            val lastPauseTime = viewModel.repository.getPreferences().getLong("last_pause_time", 0L)
                            val rewindPos = calculateSmartRewindPos(savedPos, lastPauseTime)
                            viewModel.currentPosition = rewindPos
                            playBook(player, initialBook, savedTrackIdx, rewindPos, false)
                        }
                    }

                    val folderUri = Uri.parse(savedUriStr)
                    val scannedBooks = viewModel.repository.scanFolderForAudiobooks(folderUri)
                    viewModel.books = scannedBooks
                    viewModel.repository.saveBooksCache(scannedBooks)
                } catch (_: Throwable) {}
            }
        }
    }

    LaunchedEffect(viewModel.sleepTimerActive, viewModel.sleepTimeRemaining, viewModel.sleepEndAtChapter, viewModel.isPlaying, viewModel.currentPosition) {
        if (viewModel.sleepTimerActive && viewModel.isPlaying) {
            if (viewModel.sleepEndAtChapter) {
                val currChap = getCurrentChapter(viewModel.chaptersList, viewModel.currentPosition, player.currentMediaItemIndex)
                val chapEndMs = if (currChap != null && currChap.durationMs > 0L) {
                    currChap.startMs + currChap.durationMs
                } else {
                    viewModel.duration
                }
                val isAtChapterEnd = chapEndMs > 0L && viewModel.currentPosition >= chapEndMs - 1500L
                if (isAtChapterEnd) {
                    player.pause()
                    viewModel.sleepTimerActive = false
                    viewModel.sleepEndAtChapter = false
                }
            } else if (viewModel.sleepTimeRemaining > 0L) {
                delay(1000)
                val newRemaining = viewModel.sleepTimeRemaining - 1000L
                if (newRemaining <= 0L) {
                    player.pause()
                    viewModel.sleepTimerActive = false
                    viewModel.sleepTimeRemaining = 0L
                } else {
                    viewModel.sleepTimeRemaining = newRemaining
                }
            }
        }
    }

    LaunchedEffect(viewModel.isPlaying) {
        while (viewModel.isPlaying) {
            viewModel.currentPosition = player.currentPosition.coerceAtLeast(0L)
            if (player.duration > 0L) {
                viewModel.duration = player.duration.coerceAtLeast(0L)
            }
            delay(1000)
            viewModel.totalListenMs += 1000L
            activeBook?.let { b ->
                viewModel.repository.saveBookProgress(b.id, player.currentMediaItemIndex, viewModel.currentPosition, viewModel.totalListenMs)
                val isLastTrack = !player.hasNextMediaItem()
                if (isLastTrack && viewModel.duration > 0L && viewModel.currentPosition >= viewModel.duration - 2000L) {
                    if (viewModel.repository.markBookCompleted(b.id)) {
                        viewModel.completedBooksCount = viewModel.repository.loadCompletedBookIds().size
                    }
                }
            }
        }
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                viewModel.isPlaying = playing
                if (!playing) {
                    viewModel.repository.markPauseTime()
                    if (player.mediaItemCount > 0) {
                        activeBook?.let { b ->
                            viewModel.repository.saveBookProgress(b.id, player.currentMediaItemIndex, player.currentPosition, viewModel.totalListenMs)
                        }
                    }
                }
            }
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) viewModel.duration = player.duration.coerceAtLeast(0L)
            }
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                viewModel.currentPosition = player.currentPosition.coerceAtLeast(0L)
                if (player.duration > 0L) {
                    viewModel.duration = player.duration.coerceAtLeast(0L)
                }
            }
            override fun onPlayerError(error: PlaybackException) {
                Toast.makeText(context, "Playback error: ${error.localizedMessage}", Toast.LENGTH_LONG).show()
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    val currentChapter = getCurrentChapter(viewModel.chaptersList, viewModel.currentPosition, player.currentMediaItemIndex)
    val currentChapterIndex = getCurrentChapterIndex(viewModel.chaptersList, viewModel.currentPosition, player.currentMediaItemIndex)

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(modifier = Modifier.width(300.dp)) {
                Spacer(Modifier.height(24.dp))
                Text("Fablewood Audiobooks", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(16.dp), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                HorizontalDivider()
                NavigationDrawerItem(
                    icon = { Icon(Icons.Default.FolderOpen, contentDescription = null) },
                    label = { Text("Select Audiobook Folder") },
                    selected = false,
                    onClick = {
                        coroutineScope.launch { drawerState.close() }
                        folderPicker.launch(null)
                    }
                )
                NavigationDrawerItem(
                    icon = { Icon(Icons.Default.GraphicEq, contentDescription = null) },
                    label = { Text("Audio FX & Volume Boost") },
                    selected = false,
                    onClick = {
                        coroutineScope.launch { drawerState.close() }
                        showAudioFXDialog = true
                    }
                )
                NavigationDrawerItem(
                    icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                    label = { Text("Settings") },
                    selected = false,
                    onClick = {
                        coroutineScope.launch { drawerState.close() }
                        showSettingsDialog = true
                    }
                )
                NavigationDrawerItem(
                    icon = { Icon(Icons.Default.Analytics, contentDescription = null) },
                    label = { Text("Listening Stats") },
                    selected = false,
                    onClick = {
                        coroutineScope.launch { drawerState.close() }
                        showAnalyticsDialog = true
                    }
                )
            }
        }
    ) {
        if (viewModel.isCarModeActive && activeBook != null) {
            CarModeScreen(
                book = activeBook,
                chapterTitle = currentChapter?.title ?: getChapterTitle(viewModel.chaptersList, viewModel.currentPosition, player.currentMediaItemIndex),
                isPlaying = viewModel.isPlaying,
                onPlayPause = { if (viewModel.isPlaying) player.pause() else player.play() },
                onQuickRewind = {
                    val newPos = (player.currentPosition - (viewModel.skipIntervalSeconds * 1000L)).coerceAtLeast(0L)
                    player.seekTo(newPos)
                    viewModel.currentPosition = newPos
                    viewModel.repository.saveBookProgress(activeBook.id, player.currentMediaItemIndex, newPos, viewModel.totalListenMs)
                },
                onQuickForward = {
                    val maxDur = if (viewModel.duration > 0L) viewModel.duration else Long.MAX_VALUE
                    val newPos = (player.currentPosition + (viewModel.skipIntervalSeconds * 1000L)).coerceAtMost(maxDur)
                    player.seekTo(newPos)
                    viewModel.currentPosition = newPos
                    viewModel.repository.saveBookProgress(activeBook.id, player.currentMediaItemIndex, newPos, viewModel.totalListenMs)
                },
                onAddBookmark = { showAddBookmarkPrompt = true },
                onExitCarMode = { viewModel.isCarModeActive = false }
            )
        } else if (viewModel.isPlayerFullScreen && activeBook != null) {
            NowPlayingScreen(
                book = activeBook,
                coverArt = viewModel.currentCoverArt?.asImageBitmap(),
                chapterTitle = currentChapter?.title ?: getChapterTitle(viewModel.chaptersList, viewModel.currentPosition, player.currentMediaItemIndex),
                isPlaying = viewModel.isPlaying,
                currentPosition = viewModel.currentPosition,
                duration = viewModel.duration,
                skipIntervalSeconds = viewModel.skipIntervalSeconds,
                silenceTrimEnabled = viewModel.silenceTrimEnabled,
                sleepTimerActive = viewModel.sleepTimerActive,
                sleepTimeRemaining = viewModel.sleepTimeRemaining,
                sleepEndAtChapter = viewModel.sleepEndAtChapter,
                isDarkTheme = isDarkTheme,
                playerVolume = viewModel.playerVolume,
                playbackSpeed = viewModel.playbackSpeed,
                onOpenDrawer = { coroutineScope.launch { drawerState.open() } },
                onBackToBookshelf = {
                    viewModel.repository.saveBookProgress(activeBook.id, player.currentMediaItemIndex, player.currentPosition, viewModel.totalListenMs)
                    viewModel.isPlayerFullScreen = false
                },
                onPlayPause = { if (viewModel.isPlaying) player.pause() else player.play() },
                onSeek = { pos ->
                    player.seekTo(pos)
                    viewModel.currentPosition = pos
                    viewModel.repository.saveBookProgress(activeBook.id, player.currentMediaItemIndex, pos, viewModel.totalListenMs)
                },
                onSkipPrevChapter = {
                    val chapters = viewModel.chaptersList
                    if (chapters.isNotEmpty()) {
                        val currChap = chapters.getOrNull(currentChapterIndex)
                        if (currChap != null && viewModel.currentPosition - currChap.startMs > 3000L) {
                            player.seekTo(currChap.trackIndex, currChap.startMs)
                            viewModel.currentPosition = currChap.startMs
                            viewModel.repository.saveBookProgress(activeBook.id, currChap.trackIndex, currChap.startMs, viewModel.totalListenMs)
                        } else if (currentChapterIndex > 0) {
                            val prevChap = chapters[currentChapterIndex - 1]
                            player.seekTo(prevChap.trackIndex, prevChap.startMs)
                            viewModel.currentPosition = prevChap.startMs
                            viewModel.repository.saveBookProgress(activeBook.id, prevChap.trackIndex, prevChap.startMs, viewModel.totalListenMs)
                        } else {
                            player.seekTo(0, 0L)
                            viewModel.currentPosition = 0L
                        }
                    } else {
                        player.seekToPreviousMediaItem()
                    }
                },
                onSkipNextChapter = {
                    val chapters = viewModel.chaptersList
                    if (chapters.isNotEmpty() && currentChapterIndex + 1 < chapters.size) {
                        val nextChap = chapters[currentChapterIndex + 1]
                        player.seekTo(nextChap.trackIndex, nextChap.startMs)
                        viewModel.currentPosition = nextChap.startMs
                        viewModel.repository.saveBookProgress(activeBook.id, nextChap.trackIndex, nextChap.startMs, viewModel.totalListenMs)
                    } else if (player.hasNextMediaItem()) {
                        player.seekToNextMediaItem()
                    }
                },
                onQuickRewind = {
                    val newPos = (player.currentPosition - (viewModel.skipIntervalSeconds * 1000L)).coerceAtLeast(0L)
                    player.seekTo(newPos)
                    viewModel.currentPosition = newPos
                    viewModel.repository.saveBookProgress(activeBook.id, player.currentMediaItemIndex, newPos, viewModel.totalListenMs)
                },
                onQuickForward = {
                    val maxDur = if (viewModel.duration > 0L) viewModel.duration else Long.MAX_VALUE
                    val newPos = (player.currentPosition + (viewModel.skipIntervalSeconds * 1000L)).coerceAtMost(maxDur)
                    player.seekTo(newPos)
                    viewModel.currentPosition = newPos
                    viewModel.repository.saveBookProgress(activeBook.id, player.currentMediaItemIndex, newPos, viewModel.totalListenMs)
                },
                onToggleSilenceTrim = {
                    viewModel.silenceTrimEnabled = !viewModel.silenceTrimEnabled
                    viewModel.repository.getPreferences().edit().putBoolean("silence_trim", viewModel.silenceTrimEnabled).apply()
                },
                onOpenSleepMenu = { showSleepMenu = true },
                onToggleTheme = onToggleTheme,
                onOpenAudioFX = { showAudioFXDialog = true },
                onOpenChapters = { showChaptersDialog = true },
                onOpenBookmarks = { showBookmarksDialog = true },
                onOpenSpeed = { showSpeedDialog = true },
                onEnterCarMode = { viewModel.isCarModeActive = true },
                onVolumeChange = { vol ->
                    viewModel.playerVolume = vol
                    player.volume = vol
                },
                chapterStartMs = currentChapter?.startMs ?: 0L,
                chapterDurationMs = currentChapter?.durationMs ?: 0L
            )
        } else {
            Scaffold(
                topBar = {
                    CenterAlignedTopAppBar(
                        title = { Text("Fablewood Audiobooks", fontWeight = FontWeight.Bold) },
                        navigationIcon = {
                            IconButton(onClick = { coroutineScope.launch { drawerState.open() } }) {
                                Icon(Icons.Default.Menu, contentDescription = "Menu")
                            }
                        },
                        actions = {
                            IconButton(onClick = {
                                viewModel.isSearching = !viewModel.isSearching
                                if (!viewModel.isSearching) viewModel.searchQuery = ""
                            }) {
                                Icon(Icons.Default.Search, contentDescription = "Search")
                            }
                            IconButton(onClick = { showViewDialog = true }) {
                                Icon(if (viewModel.viewMode == "GRID") Icons.Default.GridView else Icons.Default.ViewList, contentDescription = "View Mode")
                            }
                            IconButton(onClick = { showSortDialog = true }) {
                                Icon(Icons.Default.Sort, contentDescription = "Sort")
                            }
                        }
                    )
                },
                floatingActionButton = {
                    if (activeBook != null) {
                        FloatingActionButton(
                            onClick = { viewModel.isPlayerFullScreen = true },
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        ) {
                            Icon(if (viewModel.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, contentDescription = "Now Playing")
                        }
                    }
                }
            ) { innerPadding ->
                Box(modifier = Modifier.padding(innerPadding).fillMaxSize()) {
                    val displayedBooks = viewModel.books.filter {
                        if (viewModel.searchQuery.isEmpty()) true
                        else it.title.contains(viewModel.searchQuery, ignoreCase = true) || it.author.contains(viewModel.searchQuery, ignoreCase = true)
                    }.let { list ->
                        when (viewModel.sortBy) {
                            "TITLE" -> list.sortedBy { it.title.lowercase() }
                            "AUTHOR" -> list.sortedBy { it.author.lowercase() }
                            else -> list
                        }
                    }

                    Column(modifier = Modifier.fillMaxSize()) {
                        if (viewModel.isSearching || viewModel.searchQuery.isNotEmpty()) {
                            OutlinedTextField(
                                value = viewModel.searchQuery,
                                onValueChange = { viewModel.searchQuery = it },
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                                placeholder = { Text("Search books by title or author...") },
                                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                                trailingIcon = {
                                    IconButton(onClick = {
                                        viewModel.searchQuery = ""
                                        viewModel.isSearching = false
                                    }) {
                                        Icon(Icons.Default.Close, contentDescription = "Close Search")
                                    }
                                },
                                singleLine = true,
                                shape = RoundedCornerShape(12.dp)
                            )
                        }

                        BookshelfContent(
                            books = displayedBooks,
                            activeBookId = viewModel.currentBookId,
                            duration = viewModel.duration,
                            currentPosition = viewModel.currentPosition,
                            viewMode = viewModel.viewMode,
                            viewModel = viewModel,
                            onSelectBook = { book ->
                                if (book.id == viewModel.currentBookId && player.mediaItemCount > 0) {
                                    viewModel.isPlayerFullScreen = true
                                } else {
                                    val snapshotPos = player.currentPosition
                                    val snapshotIdx = player.currentMediaItemIndex
                                    activeBook?.let { curr ->
                                        viewModel.repository.saveBookProgress(curr.id, snapshotIdx, snapshotPos, viewModel.totalListenMs)
                                    }
                                    player.playWhenReady = false
                                    player.pause()

                                    viewModel.currentBookId = book.id
                                    viewModel.applyBookSpecificSpeed(book.id)
                                    viewModel.refreshBookmarks()
                                    viewModel.loadChapters(book)
                                    viewModel.updateCoverArt(book)

                                    val savedTrackIdx = viewModel.repository.getBookTrackIndex(book.id)
                                    val savedPos = viewModel.repository.getBookPosition(book.id)
                                    viewModel.currentPosition = savedPos

                                    playBook(player, book, savedTrackIdx, savedPos, false)
                                    viewModel.isPlayerFullScreen = true
                                }
                            },
                            onChangeCover = { book ->
                                targetBookForCustomCover = book
                                customCoverLauncher.launch("image/*")
                            }
                        )
                    }
                }
            }
        }
    }

    if (showAddBookmarkPrompt) {
        AlertDialog(
            onDismissRequest = { showAddBookmarkPrompt = false },
            title = { Text("Add Bookmark") },
            text = {
                OutlinedTextField(
                    value = newBookmarkNoteText,
                    onValueChange = { newBookmarkNoteText = it },
                    label = { Text("Note (Optional)") },
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val chapterName = getChapterTitle(viewModel.chaptersList, viewModel.currentPosition, player.currentMediaItemIndex)
                    val bm = Bookmark(positionMs = viewModel.currentPosition, chapterTitle = chapterName, note = newBookmarkNoteText)
                    viewModel.repository.saveBookBookmark(viewModel.currentBookId, bm)
                    viewModel.refreshBookmarks()
                    showAddBookmarkPrompt = false
                    newBookmarkNoteText = ""
                    Toast.makeText(context, "Bookmark Saved", Toast.LENGTH_SHORT).show()
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { showAddBookmarkPrompt = false }) { Text("Cancel") } }
        )
    }

    if (showViewDialog) {
        AlertDialog(
            onDismissRequest = { showViewDialog = false },
            title = { Text("View Mode") },
            text = {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable {
                        viewModel.viewMode = "GRID"
                        viewModel.repository.getPreferences().edit().putString("view_mode", "GRID").apply()
                        showViewDialog = false
                    }.padding(vertical = 12.dp)) {
                        RadioButton(selected = viewModel.viewMode == "GRID", onClick = null)
                        Spacer(Modifier.width(12.dp))
                        Text("Grid View")
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable {
                        viewModel.viewMode = "LIST"
                        viewModel.repository.getPreferences().edit().putString("view_mode", "LIST").apply()
                        showViewDialog = false
                    }.padding(vertical = 12.dp)) {
                        RadioButton(selected = viewModel.viewMode == "LIST", onClick = null)
                        Spacer(Modifier.width(12.dp))
                        Text("List View")
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showViewDialog = false }) { Text("Close") } }
        )
    }

    if (showSortDialog) {
        AlertDialog(
            onDismissRequest = { showSortDialog = false },
            title = { Text("Sort Library By") },
            text = {
                Column {
                    val sortOptions = listOf(
                        "RECENT" to "Folder Order / Recent",
                        "TITLE" to "Book Title (A–Z)",
                        "AUTHOR" to "Author (A–Z)"
                    )
                    sortOptions.forEach { (key, label) ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().clickable {
                                viewModel.sortBy = key
                                viewModel.repository.getPreferences().edit().putString("sort_by", key).apply()
                                showSortDialog = false
                            }.padding(vertical = 12.dp)
                        ) {
                            RadioButton(selected = viewModel.sortBy == key, onClick = null)
                            Spacer(Modifier.width(12.dp))
                            Text(label)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showSortDialog = false }) { Text("Close") } }
        )
    }

    if (showAudioFXDialog) {
        AlertDialog(
            onDismissRequest = { showAudioFXDialog = false },
            title = { Text("Audio FX & Equalizer") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Volume Boost (+12 dB)", fontWeight = FontWeight.Bold)
                            Text("Amplifies quiet audiobook recordings", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                        }
                        Switch(
                            checked = viewModel.volumeBoostEnabled,
                            onCheckedChange = { enabled ->
                                viewModel.volumeBoostEnabled = enabled
                                viewModel.repository.getPreferences().edit().putBoolean("volume_boost", enabled).apply()
                            }
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Trim Silence", fontWeight = FontWeight.Bold)
                            Text("Automatically skips dead air between sentences", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                        }
                        Switch(
                            checked = viewModel.silenceTrimEnabled,
                            onCheckedChange = { enabled ->
                                viewModel.silenceTrimEnabled = enabled
                                viewModel.repository.getPreferences().edit().putBoolean("silence_trim", enabled).apply()
                            }
                        )
                    }

                    HorizontalDivider()

                    Column {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Voice Pitch", fontWeight = FontWeight.Bold)
                            Text(String.format("%.2fx", viewModel.playbackPitch), color = MaterialTheme.colorScheme.primary)
                        }
                        Slider(
                            value = viewModel.playbackPitch,
                            onValueChange = { viewModel.playbackPitch = (round(it * 20f) / 20f).coerceIn(0.75f, 1.25f) },
                            valueRange = 0.75f..1.25f
                        )
                    }

                    Column {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("App Volume", fontWeight = FontWeight.Bold)
                            Text("${(viewModel.playerVolume * 100).toInt()}%", color = MaterialTheme.colorScheme.primary)
                        }
                        Slider(
                            value = viewModel.playerVolume,
                            onValueChange = { vol ->
                                viewModel.playerVolume = vol
                                player.volume = vol
                            },
                            valueRange = 0.1f..1.0f
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.playbackPitch = 1.0f
                    viewModel.playerVolume = 1.0f
                    player.volume = 1.0f
                }) { Text("Reset Pitch/Vol") }
            },
            dismissButton = { TextButton(onClick = { showAudioFXDialog = false }) { Text("Done") } }
        )
    }

    if (showSettingsDialog) {
        AlertDialog(
            onDismissRequest = { showSettingsDialog = false },
            title = { Text("Settings") },
            text = {
                Column {
                    Text("Skip Interval (seconds)", fontWeight = FontWeight.Bold)
                    Slider(
                        value = viewModel.skipIntervalSeconds.toFloat(),
                        onValueChange = {
                            val newVal = round(it).toInt()
                            viewModel.skipIntervalSeconds = newVal
                            viewModel.repository.getPreferences().edit().putInt("skip_interval", newVal).apply()
                        },
                        valueRange = 5f..60f,
                        steps = 10
                    )
                    Text("${viewModel.skipIntervalSeconds}s", modifier = Modifier.align(Alignment.End))
                }
            },
            confirmButton = { TextButton(onClick = { showSettingsDialog = false }) { Text("Close") } }
        )
    }

    if (showAnalyticsDialog) {
        AlertDialog(
            onDismissRequest = { showAnalyticsDialog = false },
            title = { Text("Listening Stats") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Total Listen Time: ${formatTime(viewModel.totalListenMs)}")
                    Text("Completed Books: ${viewModel.completedBooksCount}")
                    Text("Books in Library: ${viewModel.books.size}")
                }
            },
            confirmButton = { TextButton(onClick = { showAnalyticsDialog = false }) { Text("Close") } }
        )
    }

    if (showSpeedDialog) {
        AlertDialog(
            onDismissRequest = { showSpeedDialog = false },
            title = { Text("Playback Speed") },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(String.format("%.2fx", viewModel.playbackSpeed), style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                    Slider(
                        value = viewModel.playbackSpeed,
                        onValueChange = {
                            val stepped = (round(it * 20f) / 20f).coerceIn(0.5f, 3.0f)
                            viewModel.updateSpeed(stepped)
                        },
                        valueRange = 0.5f..2.5f
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        listOf(1.0f, 1.25f, 1.5f, 2.0f).forEach { preset ->
                            TextButton(onClick = { viewModel.updateSpeed(preset) }) {
                                Text("${preset}x")
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showSpeedDialog = false }) { Text("Close") } }
        )
    }

    if (showChaptersDialog) {
        AlertDialog(
            onDismissRequest = { showChaptersDialog = false },
            title = { Text("Chapters (${viewModel.chaptersList.size})") },
            text = {
                LazyColumn {
                    items(viewModel.chaptersList.size) { idx ->
                        val chap = viewModel.chaptersList[idx]
                        val isCurrent = idx == currentChapterIndex
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().clickable {
                                player.seekTo(chap.trackIndex, chap.startMs)
                                viewModel.currentPosition = chap.startMs
                                activeBook?.let { b -> viewModel.repository.saveBookProgress(b.id, chap.trackIndex, chap.startMs, viewModel.totalListenMs) }
                                showChaptersDialog = false
                            }.padding(vertical = 12.dp)
                        ) {
                            if (isCurrent) {
                                Box(modifier = Modifier.size(8.dp).background(MaterialTheme.colorScheme.primary, shape = CircleShape))
                            } else {
                                Spacer(Modifier.width(8.dp))
                            }
                            Spacer(Modifier.width(16.dp))
                            Column {
                                Text(chap.title, fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal)
                                val timeSubtitle = if (chap.durationMs > 0L) {
                                    "${formatTime(chap.startMs)} • ${formatTime(chap.durationMs)}"
                                } else {
                                    formatTime(chap.startMs)
                                }
                                Text(timeSubtitle, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                            }
                        }
                    }
                    if (viewModel.chaptersList.isEmpty()) {
                        item { Text("No chapters found in this file.", color = Color.Gray) }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showChaptersDialog = false }) { Text("Close") } }
        )
    }

    if (showBookmarksDialog) {
        AlertDialog(
            onDismissRequest = { showBookmarksDialog = false },
            title = { Text("Bookmarks") },
            text = {
                LazyColumn {
                    items(viewModel.currentBookBookmarks.size) { idx ->
                        val bm = viewModel.currentBookBookmarks[idx]
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable {
                                player.seekTo(bm.positionMs)
                                viewModel.currentPosition = bm.positionMs
                                activeBook?.let { b -> viewModel.repository.saveBookProgress(b.id, player.currentMediaItemIndex, bm.positionMs, viewModel.totalListenMs) }
                                showBookmarksDialog = false
                            }.padding(vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(bm.chapterTitle, fontWeight = FontWeight.Bold)
                                Text(formatTime(bm.positionMs), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                                if (bm.note.isNotEmpty()) Text(bm.note, style = MaterialTheme.typography.bodySmall)
                            }
                            IconButton(onClick = {
                                deleteBookBookmark(viewModel, viewModel.currentBookId, bm.id)
                            }) {
                                Icon(Icons.Default.Delete, contentDescription = "Delete Bookmark", tint = Color.Gray)
                            }
                        }
                    }
                    if (viewModel.currentBookBookmarks.isEmpty()) {
                        item { Text("No bookmarks yet.", color = Color.Gray) }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showBookmarksDialog = false
                    showAddBookmarkPrompt = true
                }) { Text("+ Add Bookmark") }
            },
            dismissButton = { TextButton(onClick = { showBookmarksDialog = false }) { Text("Close") } }
        )
    }

    if (showSleepMenu) {
        AlertDialog(
            onDismissRequest = { showSleepMenu = false },
            title = { Text("Sleep Timer") },
            text = {
                Column {
                    listOf(15, 30, 45, 60).forEach { mins ->
                        TextButton(onClick = {
                            viewModel.sleepTimeRemaining = mins * 60 * 1000L
                            viewModel.sleepEndAtChapter = false
                            viewModel.sleepTimerActive = true
                            showSleepMenu = false
                        }, modifier = Modifier.fillMaxWidth()) { Text("$mins Minutes") }
                    }
                    TextButton(onClick = {
                        viewModel.sleepEndAtChapter = true
                        viewModel.sleepTimerActive = true
                        showSleepMenu = false
                    }, modifier = Modifier.fillMaxWidth()) { Text("End of Chapter") }
                    if (viewModel.sleepTimerActive) {
                        TextButton(onClick = {
                            viewModel.sleepTimerActive = false
                            showSleepMenu = false
                        }, modifier = Modifier.fillMaxWidth()) { Text("Cancel Timer", color = MaterialTheme.colorScheme.error) }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showSleepMenu = false }) { Text("Close") } }
        )
    }
}

fun deleteBookBookmark(viewModel: LibraryViewModel, bookId: String, bookmarkId: String) {
    try {
        val prefs = viewModel.repository.getPreferences()
        val jsonStr = prefs.getString("book_bookmarks_json", "{}") ?: "{}"
        val jsonObj = JSONObject(jsonStr)
        val array = jsonObj.optJSONArray(bookId) ?: return
        val newArray = JSONArray()
        for (i in 0 until array.length()) {
            val item = array.getJSONObject(i)
            if (item.optString("id") != bookmarkId) {
                newArray.put(item)
            }
        }
        jsonObj.put(bookId, newArray)
        prefs.edit().putString("book_bookmarks_json", jsonObj.toString()).apply()
        viewModel.refreshBookmarks()
    } catch (_: Throwable) {}
}

fun playBook(player: Player, book: Audiobook, trackIndex: Int, positionMs: Long, autoPlay: Boolean) {
    player.playWhenReady = autoPlay
    player.stop()
    player.clearMediaItems()
    val mediaItems = book.tracks.map { track ->
        MediaItem.Builder()
            .setUri(track.uri)
            .setMediaId(track.uri.toString())
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(book.title)
                    .setArtist(book.author)
                    .setAlbumTitle(book.series)
                    .setDisplayTitle(track.name)
                    .build()
            )
            .build()
    }
    player.setMediaItems(mediaItems)
    val validIndex = if (trackIndex in mediaItems.indices) trackIndex else 0
    player.seekTo(validIndex, positionMs)
    player.prepare()
}

fun calculateSmartRewindPos(savedPos: Long, lastPauseTime: Long): Long {
    if (lastPauseTime == 0L || savedPos == 0L) return savedPos
    val hoursSincePause = (System.currentTimeMillis() - lastPauseTime) / (1000 * 60 * 60)
    val rewindMs = when {
        hoursSincePause < 2 -> 0L
        hoursSincePause < 24 -> 15_000L
        else -> 30_000L
    }
    return (savedPos - rewindMs).coerceAtLeast(0L)
}

fun getCurrentChapterIndex(chapters: List<Chapter>, currentPosMs: Long, trackIndex: Int): Int {
    if (chapters.isEmpty()) return 0
    val idx = chapters.indexOfLast {
        it.trackIndex < trackIndex || (it.trackIndex == trackIndex && currentPosMs >= it.startMs)
    }
    return if (idx >= 0) idx else 0
}

fun getCurrentChapter(chapters: List<Chapter>, currentPosMs: Long, trackIndex: Int): Chapter? {
    if (chapters.isEmpty()) return null
    return chapters[getCurrentChapterIndex(chapters, currentPosMs, trackIndex)]
}

fun getChapterTitle(chapters: List<Chapter>, currentPosMs: Long, trackIndex: Int): String {
    return getCurrentChapter(chapters, currentPosMs, trackIndex)?.title ?: "Chapter 1"
}
// END OF FILE