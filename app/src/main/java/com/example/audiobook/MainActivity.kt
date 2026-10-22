package com.example.audiobook

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.media.audiofx.LoudnessEnhancer
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.documentfile.provider.DocumentFile
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Metadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.extractor.metadata.id3.ChapterFrame
import androidx.media3.session.MediaSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

data class AudioTrack(val name: String, val uri: Uri)
data class Audiobook(
    val id: String,
    val title: String,
    val author: String,
    val series: String = "",
    val tracks: List<AudioTrack>
)
data class Chapter(val title: String, val startMs: Long)

class MainActivity : ComponentActivity() {
    private var exoPlayer: ExoPlayer? = null
    private var mediaSession: MediaSession? = null
    private lateinit var prefs: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        exoPlayer = ExoPlayer.Builder(this).build()
        mediaSession = MediaSession.Builder(this, exoPlayer!!).build()
        prefs = getSharedPreferences("SirinAudioPreferences", Context.MODE_PRIVATE)

        setContent {
            var isDarkTheme by remember { mutableStateOf(prefs.getBoolean("is_dark_theme", true)) }

            val darkColors = darkColorScheme(
                background = Color(0xFF181A20),
                surface = Color(0xFF22252D),
                surfaceVariant = Color(0xFF2A2E39),
                primary = Color(0xFFFF6D00),
                onPrimary = Color.Black,
                secondary = Color(0xFF4DB6AC),
                tertiary = Color(0xFFFF3D00)
            )

            val lightColors = lightColorScheme(
                background = Color(0xFFF4F5F8),
                surface = Color(0xFFFFFFFF),
                surfaceVariant = Color(0xFFE2E4EB),
                primary = Color(0xFFFF6D00),
                onPrimary = Color.White,
                secondary = Color(0xFF00897B),
                tertiary = Color(0xFFD32F2F)
            )

            MaterialTheme(colorScheme = if (isDarkTheme) darkColors else lightColors) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    SirinAudiobookApp(
                        player = exoPlayer!!,
                        prefs = prefs,
                        isDarkTheme = isDarkTheme,
                        onToggleTheme = {
                            isDarkTheme = !isDarkTheme
                            prefs.edit().putBoolean("is_dark_theme", isDarkTheme).apply()
                        }
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        mediaSession?.release()
        mediaSession = null
        exoPlayer?.release()
        exoPlayer = null
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SirinAudiobookApp(
    player: ExoPlayer,
    prefs: SharedPreferences,
    isDarkTheme: Boolean,
    onToggleTheme: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)

    var books by remember { mutableStateOf<List<Audiobook>>(emptyList()) }
    var currentBookIndex by remember { mutableIntStateOf(-1) }
    var isPlaying by remember { mutableStateOf(false) }
    var currentPosition by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }

    var isPlayerFullScreen by remember { mutableStateOf(false) }

    // System Back Button Handling
    BackHandler(enabled = isPlayerFullScreen) {
        isPlayerFullScreen = false
    }

    // Custom Cover Picker State
    var targetBookForCustomCover by remember { mutableStateOf<Audiobook?>(null) }
    val customCoverLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let { selectedUri ->
            targetBookForCustomCover?.let { book ->
                coroutineScope.launch {
                    saveCustomCoverArt(context, book.id, selectedUri)
                    val folderUriStr = prefs.getString("selected_folder", "") ?: ""
                    if (folderUriStr.isNotEmpty()) {
                        val folderUri = Uri.parse(folderUriStr)
                        books = scanFolderForAudiobooks(context, folderUri)
                    }
                }
            }
        }
    }

    // Audio Controls State
    var playbackSpeed by remember { mutableFloatStateOf(prefs.getFloat("last_speed", 1.0f)) }
    var playbackPitch by remember { mutableFloatStateOf(prefs.getFloat("last_pitch", 1.0f)) }
    var volumeBoostEnabled by remember { mutableStateOf(prefs.getBoolean("volume_boost", false)) }
    var silenceTrimEnabled by remember { mutableStateOf(prefs.getBoolean("silence_trim", false)) }
    var skipIntervalSeconds by remember { mutableIntStateOf(prefs.getInt("skip_interval", 15)) }
    var playerVolume by remember { mutableFloatStateOf(player.volume) }

    // Sleep Timer States
    var sleepTimerActive by remember { mutableStateOf(false) }
    var sleepTimeRemaining by remember { mutableLongStateOf(0L) }
    var sleepEndAtChapter by remember { mutableStateOf(false) }
    var showSleepMenu by remember { mutableStateOf(false) }

    // Analytics States
    var totalListenMs by remember { mutableLongStateOf(prefs.getLong("total_listen_ms", 0L)) }
    var completedBooksCount by remember { mutableIntStateOf(prefs.getInt("completed_books_count", 0)) }
    var showAnalyticsDialog by remember { mutableStateOf(false) }

    var selectedFolderUriString by remember { mutableStateOf(prefs.getString("selected_folder", "") ?: "") }
    var autoScanOnStartup by remember { mutableStateOf(prefs.getBoolean("auto_scan_startup", true)) }
    var viewMode by remember { mutableStateOf(prefs.getString("view_mode", "GRID") ?: "GRID") }
    var sortBy by remember { mutableStateOf(prefs.getString("sort_by", "AUTHOR") ?: "AUTHOR") }

    var searchQuery by remember { mutableStateOf("") }
    var isSearching by remember { mutableStateOf(false) }

    var showViewDialog by remember { mutableStateOf(false) }
    var showSortDialog by remember { mutableStateOf(false) }
    var showSettingsDialog by remember { mutableStateOf(false) }
    var showAudioFXDialog by remember { mutableStateOf(false) }

    var currentCoverArt by remember { mutableStateOf<ImageBitmap?>(null) }
    var chaptersList by remember { mutableStateOf<List<Chapter>>(emptyList()) }

    // Backup & Restore Exporters
    val backupLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        uri?.let {
            val jsonBackup = JSONObject().apply {
                put("total_listen_ms", totalListenMs)
                put("completed_books_count", completedBooksCount)
                put("last_speed", playbackSpeed)
                put("last_pitch", playbackPitch)
                put("skip_interval", skipIntervalSeconds)
                put("selected_folder", selectedFolderUriString)
                put("backup_date", SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date()))
            }.toString(4)

            context.contentResolver.openOutputStream(it)?.use { stream ->
                stream.write(jsonBackup.toByteArray())
            }
        }
    }

    val restoreLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            try {
                context.contentResolver.openInputStream(it)?.use { stream ->
                    val reader = BufferedReader(InputStreamReader(stream))
                    val jsonText = reader.readText()
                    val json = JSONObject(jsonText)

                    totalListenMs = json.optLong("total_listen_ms", totalListenMs)
                    completedBooksCount = json.optInt("completed_books_count", completedBooksCount)
                    playbackSpeed = json.optDouble("last_speed", playbackSpeed.toDouble()).toFloat()
                    playbackPitch = json.optDouble("last_pitch", playbackPitch.toDouble()).toFloat()
                    skipIntervalSeconds = json.optInt("skip_interval", skipIntervalSeconds)

                    prefs.edit().apply {
                        putLong("total_listen_ms", totalListenMs)
                        putInt("completed_books_count", completedBooksCount)
                        putFloat("last_speed", playbackSpeed)
                        putFloat("last_pitch", playbackPitch)
                        putInt("skip_interval", skipIntervalSeconds)
                        apply()
                    }
                }
            } catch (_: Exception) {}
        }
    }

    // Persistable Folder Picker Launcher
    val folderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri?.let {
            val takeFlags: Int = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            try {
                context.contentResolver.takePersistableUriPermission(it, takeFlags)
            } catch (_: Exception) {}

            val stringUri = it.toString()
            selectedFolderUriString = stringUri
            prefs.edit().putString("selected_folder", stringUri).apply()
            
            val scannedBooks = scanFolderForAudiobooks(context, it)
            if (scannedBooks.isNotEmpty()) {
                books = scannedBooks
                currentBookIndex = 0
                playBook(player, scannedBooks[0], 0L, autoPlay = false)
            }
        }
    }

    fun scanSavedFolder() {
        val savedUriStr = prefs.getString("selected_folder", "") ?: ""
        if (savedUriStr.isNotEmpty()) {
            try {
                val folderUri = Uri.parse(savedUriStr)
                val scannedBooks = scanFolderForAudiobooks(context, folderUri)
                if (scannedBooks.isNotEmpty()) {
                    books = scannedBooks
                }
            } catch (_: Exception) {}
        }
    }

    LaunchedEffect(playbackSpeed, playbackPitch) {
        player.playbackParameters = PlaybackParameters(playbackSpeed, playbackPitch)
    }

    LaunchedEffect(silenceTrimEnabled) {
        player.skipSilenceEnabled = silenceTrimEnabled
    }

    LaunchedEffect(volumeBoostEnabled, isPlaying) {
        if (volumeBoostEnabled && player.audioSessionId != C.AUDIO_SESSION_ID_UNSET) {
            try {
                val enhancer = LoudnessEnhancer(player.audioSessionId)
                enhancer.setTargetGain(1200)
                enhancer.enabled = true
            } catch (_: Exception) {}
        }
    }

    fun updateCoverArtForBook(book: Audiobook) {
        coroutineScope.launch {
            val art = getOrFetchCoverArt(context, book)
            currentCoverArt = art
        }
    }

    LaunchedEffect(Unit) {
        val savedUriStr = prefs.getString("selected_folder", "") ?: ""
        if (savedUriStr.isNotEmpty()) {
            try {
                val folderUri = Uri.parse(savedUriStr)
                val scannedBooks = scanFolderForAudiobooks(context, folderUri)
                books = scannedBooks
                val lastBookId = prefs.getString("last_book_id", "")
                val savedIndex = scannedBooks.indexOfFirst { b -> b.id == lastBookId }
                if (savedIndex != -1) {
                    currentBookIndex = savedIndex
                    val savedPos = prefs.getLong("last_track_pos", 0L)
                    val lastPauseTime = prefs.getLong("last_pause_time", 0L)
                    val rewindPos = calculateSmartRewindPos(savedPos, lastPauseTime)
                    currentPosition = rewindPos

                    updateCoverArtForBook(scannedBooks[savedIndex])
                    playBook(player, scannedBooks[savedIndex], rewindPos, autoPlay = false)
                }
            } catch (_: Exception) {}
        }
    }

    LaunchedEffect(currentPosition) {
        if (currentBookIndex in books.indices && currentPosition > 0L) {
            prefs.edit().apply {
                putString("last_book_id", books[currentBookIndex].id)
                putLong("last_track_pos", currentPosition)
                putLong("last_pause_time", System.currentTimeMillis())
                putFloat("last_speed", playbackSpeed)
                putFloat("last_pitch", playbackPitch)
                apply()
            }
        }
    }

    LaunchedEffect(sleepTimerActive, sleepTimeRemaining, sleepEndAtChapter, isPlaying) {
        if (sleepTimerActive && isPlaying) {
            if (sleepEndAtChapter) {
                val isAtChapterEnd = (duration > 0L && currentPosition >= duration - 1500L) ||
                        (!player.hasNextMediaItem() && currentPosition >= duration - 1000L)
                if (isAtChapterEnd) {
                    player.pause()
                    sleepTimerActive = false
                    sleepEndAtChapter = false
                }
            } else if (sleepTimeRemaining > 0L) {
                delay(1000)
                val newRemaining = sleepTimeRemaining - 1000L
                if (newRemaining <= 0L) {
                    player.pause()
                    sleepTimerActive = false
                    sleepTimeRemaining = 0L
                } else {
                    sleepTimeRemaining = newRemaining
                }
            }
        }
    }

    LaunchedEffect(isPlaying) {
        while (isPlaying) {
            currentPosition = player.currentPosition.coerceAtLeast(0L)
            delay(1000)
            totalListenMs += 1000L
            prefs.edit().putLong("total_listen_ms", totalListenMs).apply()

            if (duration > 0L && currentPosition >= duration - 2000L) {
                completedBooksCount += 1
                prefs.edit().putInt("completed_books_count", completedBooksCount).apply()
            }
        }
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
                if (!playing) {
                    prefs.edit().putLong("last_pause_time", System.currentTimeMillis()).apply()
                }
            }
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) {
                    duration = player.duration.coerceAtLeast(0L)
                }
            }
            override fun onMetadata(metadata: Metadata) {
                val parsedChapters = mutableListOf<Chapter>()
                for (i in 0 until metadata.length()) {
                    val entry = metadata.get(i)
                    if (entry is ChapterFrame) {
                        val title = entry.id ?: "Chapter ${parsedChapters.size + 1}"
                        parsedChapters.add(Chapter(title = title, startMs = entry.startTimeMs.toLong()))
                    }
                }
                if (parsedChapters.isNotEmpty()) {
                    chaptersList = parsedChapters
                }
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    val organizedBooks = remember(books, currentBookIndex, sortBy, searchQuery) {
        if (books.isEmpty()) emptyList()
        else {
            val filtered = if (searchQuery.isNotBlank()) {
                books.filter { it.title.contains(searchQuery, ignoreCase = true) || it.author.contains(searchQuery, ignoreCase = true) || it.series.contains(searchQuery, ignoreCase = true) }
            } else books

            val pinnedBook = if (currentBookIndex in books.indices) books[currentBookIndex] else null
            val remaining = filtered.filter { it != pinnedBook }

            val sortedRemaining = if (sortBy == "AUTHOR") {
                remaining.sortedBy { it.author }
            } else {
                remaining.sortedBy { it.title }
            }

            if (pinnedBook != null && filtered.contains(pinnedBook)) {
                listOf(pinnedBook) + sortedRemaining
            } else {
                sortedRemaining
            }
        }
    }

    // Global Drawer Container Wrapping Entire Layout
    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(drawerContainerColor = MaterialTheme.colorScheme.surface) {
                Spacer(Modifier.height(24.dp))
                Row(
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Headphones, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp))
                    Spacer(Modifier.width(12.dp))
                    Text("Audiobooks", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                }
                Divider(modifier = Modifier.padding(vertical = 12.dp), color = MaterialTheme.colorScheme.surfaceVariant)

                NavigationDrawerItem(
                    label = { Text("Scan folder") },
                    icon = { Icon(Icons.Default.Sync, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                    selected = false,
                    onClick = {
                        coroutineScope.launch { drawerState.close() }
                        scanSavedFolder()
                    }
                )

                NavigationDrawerItem(
                    label = { Text("Listening Stats & Analytics") },
                    icon = { Icon(Icons.Default.BarChart, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                    selected = false,
                    onClick = {
                        coroutineScope.launch { drawerState.close() }
                        showAnalyticsDialog = true
                    }
                )

                NavigationDrawerItem(
                    label = { Text("Settings & Backup") },
                    icon = { Icon(Icons.Default.Settings, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                    selected = false,
                    onClick = {
                        coroutineScope.launch { drawerState.close() }
                        showSettingsDialog = true
                    }
                )
            }
        }
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val isUnfolded = maxWidth >= 600.dp

            if (isUnfolded && currentBookIndex in books.indices) {
                Row(modifier = Modifier.fillMaxSize()) {
                    Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                        BookshelfContent(
                            books = organizedBooks,
                            currentBookIndex = currentBookIndex,
                            duration = duration,
                            currentPosition = currentPosition,
                            viewMode = viewMode,
                            onSelectBook = { book ->
                                val origIndex = books.indexOf(book)
                                currentBookIndex = origIndex
                                chaptersList = emptyList()
                                updateCoverArtForBook(book)
                                playBook(player, book, 0L, autoPlay = false)
                            },
                            onChangeCover = { book ->
                                targetBookForCustomCover = book
                                customCoverLauncher.launch("image/*")
                            },
                            context = context
                        )
                    }

                    Divider(modifier = Modifier.fillMaxHeight().width(1.dp), color = MaterialTheme.colorScheme.surfaceVariant)

                    Box(modifier = Modifier.weight(1.2f).fillMaxHeight()) {
                        val activeBook = books[currentBookIndex]
                        val currentTrackName = if (player.mediaItemCount > 1) {
                            activeBook.tracks.getOrNull(player.currentMediaItemIndex)?.name ?: activeBook.title
                        } else {
                            chaptersList.findLast { currentPosition >= it.startMs }?.title ?: "Chapter 1"
                        }

                        NowPlayingScreen(
                            book = activeBook,
                            coverArt = currentCoverArt,
                            chapterTitle = currentTrackName,
                            isPlaying = isPlaying,
                            currentPosition = currentPosition,
                            duration = duration,
                            skipIntervalSeconds = skipIntervalSeconds,
                            silenceTrimEnabled = silenceTrimEnabled,
                            sleepTimerActive = sleepTimerActive,
                            sleepTimeRemaining = sleepTimeRemaining,
                            sleepEndAtChapter = sleepEndAtChapter,
                            isDarkTheme = isDarkTheme,
                            playerVolume = playerVolume,
                            onOpenDrawer = { coroutineScope.launch { drawerState.open() } },
                            onBackToBookshelf = { isPlayerFullScreen = false },
                            onPlayPause = { if (isPlaying) player.pause() else player.play() },
                            onSeek = { pos -> player.seekTo(pos); currentPosition = pos },
                            onSkipPrevChapter = {
                                if (player.mediaItemCount > 1) {
                                    player.seekToPreviousMediaItem()
                                } else if (chaptersList.isNotEmpty()) {
                                    val prevChapter = chaptersList.findLast { it.startMs < currentPosition - 2000L } ?: chaptersList.first()
                                    player.seekTo(prevChapter.startMs)
                                    currentPosition = prevChapter.startMs
                                } else {
                                    player.seekTo(0L)
                                    currentPosition = 0L
                                }
                            },
                            onSkipNextChapter = {
                                if (player.mediaItemCount > 1) {
                                    player.seekToNextMediaItem()
                                } else if (chaptersList.isNotEmpty()) {
                                    val nextChapter = chaptersList.find { it.startMs > currentPosition + 1000L }
                                    if (nextChapter != null) {
                                        player.seekTo(nextChapter.startMs)
                                        currentPosition = nextChapter.startMs
                                    }
                                }
                            },
                            onQuickRewind = {
                                val newPos = (player.currentPosition - skipIntervalSeconds * 1000L).coerceAtLeast(0L)
                                player.seekTo(newPos)
                                currentPosition = newPos
                            },
                            onQuickForward = {
                                val newPos = (player.currentPosition + skipIntervalSeconds * 1000L).coerceAtMost(duration)
                                player.seekTo(newPos)
                                currentPosition = newPos
                            },
                            onToggleSilenceTrim = {
                                silenceTrimEnabled = !silenceTrimEnabled
                                prefs.edit().putBoolean("silence_trim", silenceTrimEnabled).apply()
                            },
                            onOpenSleepMenu = { showSleepMenu = true },
                            onToggleTheme = onToggleTheme,
                            onOpenAudioFX = { showAudioFXDialog = true },
                            onVolumeChange = { vol ->
                                playerVolume = vol
                                player.volume = vol
                            }
                        )
                    }
                }
            } else if (isPlayerFullScreen && currentBookIndex in books.indices) {
                val activeBook = books[currentBookIndex]
                val currentTrackName = if (player.mediaItemCount > 1) {
                    activeBook.tracks.getOrNull(player.currentMediaItemIndex)?.name ?: activeBook.title
                } else {
                    chaptersList.findLast { currentPosition >= it.startMs }?.title ?: "Chapter 1"
                }

                NowPlayingScreen(
                    book = activeBook,
                    coverArt = currentCoverArt,
                    chapterTitle = currentTrackName,
                    isPlaying = isPlaying,
                    currentPosition = currentPosition,
                    duration = duration,
                    skipIntervalSeconds = skipIntervalSeconds,
                    silenceTrimEnabled = silenceTrimEnabled,
                    sleepTimerActive = sleepTimerActive,
                    sleepTimeRemaining = sleepTimeRemaining,
                    sleepEndAtChapter = sleepEndAtChapter,
                    isDarkTheme = isDarkTheme,
                    playerVolume = playerVolume,
                    onOpenDrawer = { coroutineScope.launch { drawerState.open() } },
                    onBackToBookshelf = { isPlayerFullScreen = false },
                    onPlayPause = { if (isPlaying) player.pause() else player.play() },
                    onSeek = { pos -> player.seekTo(pos); currentPosition = pos },
                    onSkipPrevChapter = {
                        if (player.mediaItemCount > 1) {
                            player.seekToPreviousMediaItem()
                        } else if (chaptersList.isNotEmpty()) {
                            val prevChapter = chaptersList.findLast { it.startMs < currentPosition - 2000L } ?: chaptersList.first()
                            player.seekTo(prevChapter.startMs)
                            currentPosition = prevChapter.startMs
                        } else {
                            player.seekTo(0L)
                            currentPosition = 0L
                        }
                    },
                    onSkipNextChapter = {
                        if (player.mediaItemCount > 1) {
                            player.seekToNextMediaItem()
                        } else if (chaptersList.isNotEmpty()) {
                            val nextChapter = chaptersList.find { it.startMs > currentPosition + 1000L }
                            if (nextChapter != null) {
                                player.seekTo(nextChapter.startMs)
                                currentPosition = nextChapter.startMs
                            }
                        }
                    },
                    onQuickRewind = {
                        val newPos = (player.currentPosition - skipIntervalSeconds * 1000L).coerceAtLeast(0L)
                        player.seekTo(newPos)
                        currentPosition = newPos
                    },
                    onQuickForward = {
                        val newPos = (player.currentPosition + skipIntervalSeconds * 1000L).coerceAtMost(duration)
                        player.seekTo(newPos)
                        currentPosition = newPos
                    },
                    onToggleSilenceTrim = {
                        silenceTrimEnabled = !silenceTrimEnabled
                        prefs.edit().putBoolean("silence_trim", silenceTrimEnabled).apply()
                    },
                    onOpenSleepMenu = { showSleepMenu = true },
                    onToggleTheme = onToggleTheme,
                    onOpenAudioFX = { showAudioFXDialog = true },
                    onVolumeChange = { vol ->
                        playerVolume = vol
                        player.volume = vol
                    }
                )
            } else {
                Scaffold(
                    topBar = {
                        Column(modifier = Modifier.background(MaterialTheme.colorScheme.background)) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                IconButton(onClick = { coroutineScope.launch { drawerState.open() } }) {
                                    Icon(Icons.Default.Menu, contentDescription = "Menu")
                                }

                                if (isSearching) {
                                    TextField(
                                        value = searchQuery,
                                        onValueChange = { searchQuery = it },
                                        placeholder = { Text("Search title, author, or series...") },
                                        singleLine = true,
                                        modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                                        colors = TextFieldDefaults.colors(
                                            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                                            unfocusedContainerColor = MaterialTheme.colorScheme.surface
                                        )
                                    )
                                    IconButton(onClick = { isSearching = false; searchQuery = "" }) {
                                        Icon(Icons.Default.Close, contentDescription = "Close Search")
                                    }
                                } else {
                                    Text("SIRIN", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)

                                    Row {
                                        IconButton(onClick = { showAudioFXDialog = true }) {
                                            Icon(Icons.Default.GraphicEq, contentDescription = "Audio FX", tint = MaterialTheme.colorScheme.primary)
                                        }
                                        IconButton(onClick = { isSearching = true }) {
                                            Icon(Icons.Default.Search, contentDescription = "Search")
                                        }
                                        IconButton(onClick = { showSortDialog = true }) {
                                            Icon(Icons.Default.Sort, contentDescription = "Sort")
                                        }
                                        IconButton(onClick = { showViewDialog = true }) {
                                            Icon(
                                                imageVector = if (viewMode == "GRID") Icons.Default.GridView else Icons.Default.ViewList,
                                                contentDescription = "View Switcher"
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    },
                    bottomBar = {
                        if (currentBookIndex in books.indices) {
                            val activeBook = books[currentBookIndex]
                            Surface(
                                color = MaterialTheme.colorScheme.surface,
                                modifier = Modifier.fillMaxWidth().clickable { isPlayerFullScreen = true }
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp).fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                                        Box(
                                            modifier = Modifier.size(44.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.primary),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            if (currentCoverArt != null) {
                                                Image(bitmap = currentCoverArt!!, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                                            } else {
                                                Icon(Icons.Default.Book, contentDescription = null, tint = Color.Black)
                                            }
                                        }
                                        Spacer(Modifier.width(12.dp))
                                        Column {
                                            Text(activeBook.title, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            Text(activeBook.author, style = MaterialTheme.typography.bodySmall, color = Color.Gray, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        }
                                    }

                                    IconButton(onClick = { if (isPlaying) player.pause() else player.play() }) {
                                        Icon(
                                            imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                            contentDescription = "Play/Pause",
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(32.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                ) { padding ->
                    Box(modifier = Modifier.padding(padding).fillMaxSize()) {
                        BookshelfContent(
                            books = organizedBooks,
                            currentBookIndex = currentBookIndex,
                            duration = duration,
                            currentPosition = currentPosition,
                            viewMode = viewMode,
                            onSelectBook = { book ->
                                val origIndex = books.indexOf(book)
                                currentBookIndex = origIndex
                                chaptersList = emptyList()
                                updateCoverArtForBook(book)
                                playBook(player, book, 0L, autoPlay = true)
                                isPlayerFullScreen = true
                            },
                            onChangeCover = { book ->
                                targetBookForCustomCover = book
                                customCoverLauncher.launch("image/*")
                            },
                            context = context
                        )
                    }
                }
            }
        }
    }

    // Dialogs
    if (showAnalyticsDialog) {
        AlertDialog(
            onDismissRequest = { showAnalyticsDialog = false },
            title = { Text("Reading Analytics") },
            text = {
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Total Time Listened:", color = Color.Gray)
                        Text(formatTime(totalListenMs), fontWeight = FontWeight.Bold)
                    }
                    Divider(color = MaterialTheme.colorScheme.surfaceVariant)
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Books Completed:", color = Color.Gray)
                        Text("$completedBooksCount", fontWeight = FontWeight.Bold)
                    }
                    Divider(color = MaterialTheme.colorScheme.surfaceVariant)
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Average Playback Speed:", color = Color.Gray)
                        Text("${String.format("%.2f", playbackSpeed)}x", fontWeight = FontWeight.Bold)
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showAnalyticsDialog = false }) { Text("Done") } }
        )
    }

    if (showAudioFXDialog) {
        AlertDialog(
            onDismissRequest = { showAudioFXDialog = false },
            title = { Text("Audio Enhancements") },
            text = {
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Volume Boost (+12dB)", fontWeight = FontWeight.Bold)
                            Text("Amplify quiet narrator tracks", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                        }
                        Switch(
                            checked = volumeBoostEnabled,
                            onCheckedChange = {
                                volumeBoostEnabled = it
                                prefs.edit().putBoolean("volume_boost", it).apply()
                            }
                        )
                    }

                    Spacer(Modifier.height(16.dp))

                    Text("Pitch Tuning: ${String.format("%.2f", playbackPitch)}x", fontWeight = FontWeight.Bold)
                    Slider(
                        value = playbackPitch,
                        onValueChange = {
                            playbackPitch = it
                            prefs.edit().putFloat("last_pitch", it).apply()
                        },
                        valueRange = 0.8f..1.2f,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = { TextButton(onClick = { showAudioFXDialog = false }) { Text("Done") } }
        )
    }

    if (showViewDialog) {
        AlertDialog(
            onDismissRequest = { showViewDialog = false },
            title = { Text("View Mode") },
            text = {
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable {
                            viewMode = "GRID"
                            prefs.edit().putString("view_mode", "GRID").apply()
                            showViewDialog = false
                        }.padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = viewMode == "GRID", onClick = null)
                        Spacer(Modifier.width(12.dp))
                        Text("Grid View")
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable {
                            viewMode = "LIST"
                            prefs.edit().putString("view_mode", "LIST").apply()
                            showViewDialog = false
                        }.padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = viewMode == "LIST", onClick = null)
                        Spacer(Modifier.width(12.dp))
                        Text("List View")
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showViewDialog = false }) { Text("Cancel") } }
        )
    }

    if (showSortDialog) {
        AlertDialog(
            onDismissRequest = { showSortDialog = false },
            title = { Text("Sort Audiobooks") },
            text = {
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable {
                            sortBy = "AUTHOR"
                            prefs.edit().putString("sort_by", "AUTHOR").apply()
                            showSortDialog = false
                        }.padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = sortBy == "AUTHOR", onClick = null)
                        Spacer(Modifier.width(12.dp))
                        Text("By Author Name")
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable {
                            sortBy = "TITLE"
                            prefs.edit().putString("sort_by", "TITLE").apply()
                            showSortDialog = false
                        }.padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = sortBy == "TITLE", onClick = null)
                        Spacer(Modifier.width(12.dp))
                        Text("By Book Title")
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showSortDialog = false }) { Text("Cancel") } }
        )
    }

    if (showSettingsDialog) {
        AlertDialog(
            onDismissRequest = { showSettingsDialog = false },
            title = { Text("Settings & Backup") },
            text = {
                Column {
                    Text("Storage Folder", style = MaterialTheme.typography.titleSmall, color = Color.Gray)
                    Spacer(Modifier.height(4.dp))
                    Button(
                        onClick = {
                            showSettingsDialog = false
                            folderPicker.launch(null)
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Folder, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Select Audiobook Folder")
                    }

                    Spacer(Modifier.height(16.dp))
                    Divider(color = MaterialTheme.colorScheme.surfaceVariant)
                    Spacer(Modifier.height(16.dp))

                    Text("Library Backup & Restore (.json)", fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = { backupLauncher.launch("audiobooks_backup.json") },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Export Backup")
                        }
                        OutlinedButton(
                            onClick = { restoreLauncher.launch(arrayOf("application/json")) },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Restore")
                        }
                    }

                    Spacer(Modifier.height(16.dp))
                    Divider(color = MaterialTheme.colorScheme.surfaceVariant)
                    Spacer(Modifier.height(16.dp))

                    Text("Quick Skip Interval", fontWeight = FontWeight.Bold)
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf(5, 10, 15, 30).forEach { seconds ->
                            FilterChip(
                                selected = skipIntervalSeconds == seconds,
                                onClick = {
                                    skipIntervalSeconds = seconds
                                    prefs.edit().putInt("skip_interval", seconds).apply()
                                },
                                label = { Text("${seconds}s") },
                                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = MaterialTheme.colorScheme.primary)
                            )
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showSettingsDialog = false }) { Text("Done") } }
        )
    }

    if (showSleepMenu) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.6f))
                .clickable { showSleepMenu = false },
            contentAlignment = Alignment.BottomCenter
        ) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = false) {},
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
            ) {
                Column(modifier = Modifier.padding(24.dp)) {
                    Text("Sleep Timer Preset", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(16.dp))
                    listOf(
                        "Off" to 0,
                        "15 Minutes" to 15,
                        "30 Minutes" to 30,
                        "45 Minutes" to 45,
                        "60 Minutes" to 60,
                        "End of Chapter" to -1
                    ).forEach { (label, min) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    if (min == -1) {
                                        sleepEndAtChapter = true
                                        sleepTimerActive = true
                                        sleepTimeRemaining = 0L
                                    } else if (min == 0) {
                                        sleepTimerActive = false
                                        sleepEndAtChapter = false
                                        sleepTimeRemaining = 0L
                                    } else {
                                        sleepTimeRemaining = min * 60 * 1000L
                                        sleepTimerActive = true
                                        sleepEndAtChapter = false
                                    }
                                    showSleepMenu = false
                                }
                                .padding(vertical = 12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(label)
                            if ((min == -1 && sleepEndAtChapter) || (min == 0 && !sleepTimerActive) || (sleepTimerActive && !sleepEndAtChapter && sleepTimeRemaining / 60000L == min.toLong())) {
                                Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun BookshelfContent(
    books: List<Audiobook>,
    currentBookIndex: Int,
    duration: Long,
    currentPosition: Long,
    viewMode: String,
    onSelectBook: (Audiobook) -> Unit,
    onChangeCover: (Audiobook) -> Unit,
    context: Context
) {
    if (books.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(64.dp), tint = Color.DarkGray)
                Spacer(Modifier.height(16.dp))
                Text("No audiobooks found.", style = MaterialTheme.typography.bodyLarge, color = Color.Gray)
            }
        }
    } else {
        if (viewMode == "GRID") {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 150.dp),
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                itemsIndexed(books) { _, book ->
                    val isSelected = books.indexOf(book) == currentBookIndex
                    var cardArt by remember(