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
                    try {
                        controller = controllerFuture.get()
                    } catch (_: Exception) {}
                }, ContextCompat.getMainExecutor(context))

                onDispose {
                    MediaController.releaseFuture(controllerFuture)
                }
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
                        SirinAudiobookApp(
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
fun SirinAudiobookApp(
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
            targetBookForCustomCover?.let { book ->
                viewModel.setCustomCover(book.id, selectedUri)
            }
        }
    }

    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let {
            val takeFlags: Int = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            try { context.contentResolver.takePersistableUriPermission(it, takeFlags) } catch (_: Exception) {}
            
            viewModel.repository.saveSelectedFolderUri(it.toString())
            
            coroutineScope.launch {
                val scannedBooks = viewModel.repository.scanFolderForAudiobooks(it)
                if (scannedBooks.isNotEmpty()) {
                    viewModel.books = scannedBooks
                    val selectedBook = scannedBooks[0]
                    viewModel.currentBookId = selectedBook.id
                    viewModel.applyBookSpecificSpeed(selectedBook.id)
                    viewModel.refreshBookmarks()
                    viewModel.loadChapters(selectedBook)
                    
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
                    val folderUri = Uri.parse(savedUriStr)
                    val scannedBooks = viewModel.repository.scanFolderForAudiobooks(folderUri)
                    viewModel.books = scannedBooks
                    
                    val lastBookId = viewModel.repository.getLastBookId()
                    val savedBook = scannedBooks.find { b -> b.id == lastBookId } ?: scannedBooks.firstOrNull()
                    if (savedBook != null) {
                        viewModel.currentBookId = savedBook.id
                        val savedTrackIdx = viewModel.repository.getBookTrackIndex(savedBook.id)
                        val savedPos = viewModel.repository.getBookPosition(savedBook.id)
                        val lastPauseTime = viewModel.repository.getPreferences().getLong("last_pause_time", 0L)
                        val rewindPos = calculateSmartRewindPos(savedPos, lastPauseTime)
                        
                        viewModel.currentPosition = rewindPos
                        viewModel.applyBookSpecificSpeed(savedBook.id)
                        viewModel.refreshBookmarks()
                        viewModel.loadChapters(savedBook)
                        viewModel.updateCoverArt(savedBook)
                        playBook(player, savedBook, savedTrackIdx, rewindPos, false)
                    }
                } catch (_: Exception) {}
            }
        }
    }

    LaunchedEffect(viewModel.sleepTimerActive, viewModel.sleepTimeRemaining, viewModel.sleepEndAtChapter, viewModel.isPlaying, viewModel.currentPosition) {
        if (viewModel.sleepTimerActive && viewModel.isPlaying) {
            if (viewModel.sleepEndAtChapter) {
                val isAtChapterEnd = (viewModel.duration > 0L && viewModel.currentPosition >= viewModel.duration - 1500L) || (!player.hasNextMediaItem() && viewModel.currentPosition >= viewModel.duration - 1000L)
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

    // --- CONTINUOUS BACKGROUND POSITION SAVING ---
    LaunchedEffect(viewModel.isPlaying) {
        while (viewModel.isPlaying) {
            viewModel.currentPosition = player.currentPosition.coerceAtLeast(0L)
            delay(1000)
            viewModel.totalListenMs += 1000L
            activeBook?.let { b ->
                viewModel.repository.saveBookProgress(b.id, player.currentMediaItemIndex, viewModel.currentPosition, viewModel.totalListenMs)
            }

            activeBook?.let { b ->
                val isLastTrack = !player.hasNextMediaItem()
                if (isLastTrack && viewModel.duration > 0L && viewModel.currentPosition >= viewModel.duration - 2000L) {
                    if (viewModel.repository.markBookCompleted(b.id)) {
                        viewModel.completedBooksCount = viewModel.repository.loadCompletedBookIds().size
                    }
                }
            }
        }

        // Save immediately when playback stops
        activeBook?.let { b ->
            viewModel.repository.saveBookProgress(b.id, player.currentMediaItemIndex, player.currentPosition, viewModel.totalListenMs)
        }
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                viewModel.isPlaying = playing
                if (!playing) {
                    viewModel.repository.markPauseTime()
                    activeBook?.let { b ->
                        viewModel.repository.saveBookProgress(b.id, player.currentMediaItemIndex, player.currentPosition, viewModel.totalListenMs)
                    }
                }
            }
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) viewModel.duration = player.duration.coerceAtLeast(0L)
            }
            override fun onPlayerError(error: PlaybackException) {
                Toast.makeText(context, "Playback error: ${error.localizedMessage}", Toast.LENGTH_LONG).show()
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(modifier = Modifier.width(300.dp)) {
                Spacer(Modifier.height(24.dp))
                Text("Sirin Audiobooks", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(16.dp), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
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
                chapterTitle = getChapterTitle(viewModel.chaptersList, viewModel.currentPosition),
                isPlaying = viewModel.isPlaying,
                onPlayPause = { if (viewModel.isPlaying) player.pause() else player.play() },
                onQuickRewind = { 
                    player.seekTo(player.currentPosition - (viewModel.skipIntervalSeconds * 1000L))
                    activeBook.let { b -> viewModel.repository.saveBookProgress(b.id, player.currentMediaItemIndex, player.currentPosition, viewModel.totalListenMs) }
                },
                onQuickForward = { 
                    player.seekTo(player.currentPosition + (viewModel.skipIntervalSeconds * 1000L))
                    activeBook.let { b -> viewModel.repository.saveBookProgress(b.id, player.currentMediaItemIndex, player.currentPosition, viewModel.totalListenMs) }
                },
                onAddBookmark = { showAddBookmarkPrompt = true },
                onExitCarMode = { viewModel.isCarModeActive = false }
            )
        } else if (viewModel.isPlayerFullScreen && activeBook != null) {
            NowPlayingScreen(
                book = activeBook,
                coverArt = viewModel.currentCoverArt?.asImageBitmap(),
                chapterTitle = getChapterTitle(viewModel.chaptersList, viewModel.currentPosition),
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
                    // Save position instantly when closing full player
                    activeBook.let { b -> viewModel.repository.saveBookProgress(b.id, player.currentMediaItemIndex, player.currentPosition, viewModel.totalListenMs) }
                    viewModel.isPlayerFullScreen = false 
                },
                onPlayPause = { if (viewModel.isPlaying) player.pause() else player.play() },
                onSeek = { pos -> 
                    player.seekTo(pos)
                    viewModel.currentPosition = pos
                    activeBook.let { b -> viewModel.repository.saveBookProgress(b.id, player.currentMediaItemIndex, pos, viewModel.totalListenMs) }
                },
                onSkipPrevChapter = {
                    val chap = viewModel.chaptersList.lastOrNull { it.startMs < viewModel.currentPosition - 2000L }
                    if (chap != null) player.seekTo(chap.startMs) else player.seekToPreviousMediaItem()
                    activeBook.let { b -> viewModel.repository.saveBookProgress(b.id, player.currentMediaItemIndex, player.currentPosition, viewModel.totalListenMs) }
                },
                onSkipNextChapter = {
                    val chap = viewModel.chaptersList.firstOrNull { it.startMs > viewModel.currentPosition + 2000L }
                    if (chap != null) player.seekTo(chap.startMs) else player.seekToNextMediaItem()
                    activeBook.let { b -> viewModel.repository.saveBookProgress(b.id, player.currentMediaItemIndex, player.currentPosition, viewModel.totalListenMs) }
                },
                onQuickRewind = { 
                    player.seekTo(player.currentPosition - (viewModel.skipIntervalSeconds * 1000L))
                    activeBook.let { b -> viewModel.repository.saveBookProgress(b.id, player.currentMediaItemIndex, player.currentPosition, viewModel.totalListenMs) }
                },
                onQuickForward = { 
                    player.seekTo(player.currentPosition + (viewModel.skipIntervalSeconds * 1000L))
                    activeBook.let { b -> viewModel.repository.saveBookProgress(b.id, player.currentMediaItemIndex, player.currentPosition, viewModel.totalListenMs) }
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
                }
            )
        } else {
            Scaffold(
                topBar = {
                    CenterAlignedTopAppBar(
                        title = { Text("Sirin Library", fontWeight = FontWeight.Bold) },
                        navigationIcon = {
                            IconButton(onClick = { coroutineScope.launch { drawerState.open() } }) {
                                Icon(Icons.Default.Menu, contentDescription = "Menu")
                            }
                        },
                        actions = {
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
                            "RECENT" -> list
                            else -> list
                        }
                    }

                    Column(modifier = Modifier.fillMaxSize()) {
                        if (viewModel.isSearching || viewModel.searchQuery.isNotEmpty()) {
                            OutlinedTextField(
                                value = viewModel.searchQuery,
                                onValueChange = { viewModel.searchQuery = it },
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                                placeholder = { Text("Search books...") },
                                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                                trailingIcon = {
                                    if (viewModel.searchQuery.isNotEmpty()) {
                                        IconButton(onClick = { viewModel.searchQuery = "" }) { Icon(Icons.Default.Clear, contentDescription = null) }
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
                                // Save current playing book state before switching
                                activeBook?.let { curr ->
                                    viewModel.repository.saveBookProgress(curr.id, player.currentMediaItemIndex, player.currentPosition, viewModel.totalListenMs)
                                }
                                
                                viewModel.currentBookId = book.id
                                viewModel.applyBookSpecificSpeed(book.id)
                                viewModel.refreshBookmarks()
                                viewModel.loadChapters(book)
                                viewModel.updateCoverArt(book)
                                
                                val savedTrackIdx = viewModel.repository.getBookTrackIndex(book.id)
                                val savedPos = viewModel.repository.getBookPosition(book.id)
                                viewModel.currentPosition = savedPos
                                
                                // Load book into player and PAUSE (autoPlay = false) so user manually presses play
                                playBook(player, book, savedTrackIdx, savedPos, false)
                                viewModel.isPlayerFullScreen = true
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

    // --- DIALOGS ---
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
                    val chapterName = getChapterTitle(viewModel.chaptersList, viewModel.currentPosition)
                    val bm = Bookmark(positionMs = viewModel.currentPosition, chapterTitle = chapterName, note = newBookmarkNoteText)
                    viewModel.repository.saveBookBookmark(viewModel.currentBookId, bm)
                    viewModel.refreshBookmarks()
                    showAddBookmarkPrompt = false
                    newBookmarkNoteText = ""
                    Toast.makeText(context, "Bookmark Saved", Toast.LENGTH_SHORT).show()
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { showAddBookmarkPrompt = false }) { Text("Cancel") }
            }
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
                Column {
                    Text("Total Listen Time: ${formatTime(viewModel.totalListenMs)}")
                    Text("Completed Books: ${viewModel.completedBooksCount}")
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
                Column {
                    Slider(
                        value = viewModel.playbackSpeed,
                        onValueChange = { viewModel.updateSpeed(it) },
                        valueRange = 0.5f..3.0f,
                        steps = 24
                    )
                    Text(String.format("%.1fx", viewModel.playbackSpeed), modifier = Modifier.align(Alignment.CenterHorizontally))
                }
            },
            confirmButton = { TextButton(onClick = { showSpeedDialog = false }) { Text("Close") } }
        )
    }
    
    if (showChaptersDialog) {
        AlertDialog(
            onDismissRequest = { showChaptersDialog = false },
            title = { Text("Chapters") },
            text = {
                LazyColumn {
                    items(viewModel.chaptersList.size) { idx ->
                        val chap = viewModel.chaptersList[idx]
                        Text(
                            text = chap.title,
                            modifier = Modifier.fillMaxWidth().clickable {
                                player.seekTo(chap.startMs)
                                viewModel.currentPosition = chap.startMs
                                activeBook?.let { b -> viewModel.repository.saveBookProgress(b.id, player.currentMediaItemIndex, chap.startMs, viewModel.totalListenMs) }
                                showChaptersDialog = false
                            }.padding(vertical = 12.dp)
                        )
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
                        Column(modifier = Modifier.fillMaxWidth().clickable {
                            player.seekTo(bm.positionMs)
                            viewModel.currentPosition = bm.positionMs
                            activeBook?.let { b -> viewModel.repository.saveBookProgress(b.id, player.currentMediaItemIndex, bm.positionMs, viewModel.totalListenMs) }
                            showBookmarksDialog = false
                        }.padding(vertical = 8.dp)) {
                            Text(bm.chapterTitle, fontWeight = FontWeight.Bold)
                            Text(formatTime(bm.positionMs), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                            if (bm.note.isNotEmpty()) Text(bm.note, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    if (viewModel.currentBookBookmarks.isEmpty()) {
                        item { Text("No bookmarks yet.", color = Color.Gray) }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showBookmarksDialog = false }) { Text("Close") } }
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

fun playBook(player: Player, book: Audiobook, trackIndex: Int, positionMs: Long, autoPlay: Boolean) {
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
    if (autoPlay) player.play()
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

fun getChapterTitle(chapters: List<Chapter>, currentPosMs: Long): String {
    if (chapters.isEmpty()) return "Chapter 1"
    var currentChapter = chapters.first()
    for (chapter in chapters) {
        if (currentPosMs >= chapter.startMs) {
            currentChapter = chapter
        } else {
            break
        }
    }
    return currentChapter.title
}