package com.example.audiobook

import android.content.Context
import android.content.SharedPreferences
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.media.audiofx.LoudnessEnhancer
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.documentfile.provider.DocumentFile
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Metadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.extractor.metadata.id3.ChapterFrame
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

data class AudioTrack(val name: String, val uri: Uri)
data class Audiobook(
    val id: String,
    val title: String,
    val author: String,
    val tracks: List<AudioTrack>
)
data class Chapter(val title: String, val startMs: Long)

class MainActivity : ComponentActivity() {
    private var exoPlayer: ExoPlayer? = null
    private lateinit var prefs: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        exoPlayer = ExoPlayer.Builder(this).build()
        prefs = getSharedPreferences("SirinAudioPreferences", Context.MODE_PRIVATE)

        setContent {
            var isDarkTheme by remember { mutableStateOf(prefs.getBoolean("is_dark_theme", true)) }

            val darkColors = darkColorScheme(
                background = Color(0xFF181A20),
                surface = Color(0xFF22252D),
                surfaceVariant = Color(0xFF2A2E39),
                primary = Color(0xFFFF6D00), // Vibrant Orange accent from reference image
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

    // Audio Controls State
    var playbackSpeed by remember { mutableFloatStateOf(prefs.getFloat("last_speed", 1.0f)) }
    var playbackPitch by remember { mutableFloatStateOf(prefs.getFloat("last_pitch", 1.0f)) }
    var volumeBoostEnabled by remember { mutableStateOf(prefs.getBoolean("volume_boost", false)) }
    var silenceTrimEnabled by remember { mutableStateOf(prefs.getBoolean("silence_trim", false)) }
    var skipIntervalSeconds by remember { mutableIntStateOf(prefs.getInt("skip_interval", 15)) }
    var playerVolume by remember { mutableFloatStateOf(player.volume) }

    var selectedFolderUriString by remember { mutableStateOf(prefs.getString("selected_folder", "") ?: "") }
    var autoScanOnStartup by remember { mutableStateOf(prefs.getBoolean("auto_scan_startup", true)) }
    var viewMode by remember { mutableStateOf(prefs.getString("view_mode", "GRID") ?: "GRID") }
    var sortBy by remember { mutableStateOf(prefs.getString("sort_by", "AUTHOR") ?: "AUTHOR") }

    var searchQuery by remember { mutableStateOf("") }
    var isSearching by remember { mutableStateOf(false) }
    var isPlayerFullScreen by remember { mutableStateOf(false) }

    var showViewDialog by remember { mutableStateOf(false) }
    var showSortDialog by remember { mutableStateOf(false) }
    var showSettingsDialog by remember { mutableStateOf(false) }
    var showAudioFXDialog by remember { mutableStateOf(false) }

    var currentCoverArt by remember { mutableStateOf<ImageBitmap?>(null) }
    var chaptersList by remember { mutableStateOf<List<Chapter>>(emptyList()) }
    var showChapterSheet by remember { mutableStateOf(false) }

    var sleepTimerActive by remember { mutableStateOf(false) }
    var sleepTimeRemaining by remember { mutableLongStateOf(0L) }
    var showSleepMenu by remember { mutableStateOf(false) }

    val folderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri?.let {
            val stringUri = it.toString()
            selectedFolderUriString = stringUri
            prefs.edit().putString("selected_folder", stringUri).apply()
            val scannedBooks = scanFolderForAudiobooks(context, it)
            if (scannedBooks.isNotEmpty()) {
                books = scannedBooks
                currentBookIndex = 0
                playBook(player, scannedBooks[0], 0L)
            }
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
        val localArt = loadEmbeddedCoverArt(context, book.tracks.first().uri)
        if (localArt != null) {
            currentCoverArt = localArt
        } else {
            currentCoverArt = null
            coroutineScope.launch {
                val onlineArt = fetchOnlineCoverArt(book.title, book.author)
                if (onlineArt != null) {
                    currentCoverArt = onlineArt
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        if (selectedFolderUriString.isNotEmpty()) {
            try {
                val folderUri = Uri.parse(selectedFolderUriString)
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
                    playBook(player, scannedBooks[savedIndex], rewindPos)
                    player.pause()
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

    LaunchedEffect(sleepTimerActive, sleepTimeRemaining, isPlaying) {
        if (sleepTimerActive && sleepTimeRemaining > 0L && isPlaying) {
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

    LaunchedEffect(isPlaying) {
        while (isPlaying) {
            currentPosition = player.currentPosition.coerceAtLeast(0L)
            delay(500)
        }
    }

    val organizedBooks = remember(books, currentBookIndex, sortBy, searchQuery) {
        if (books.isEmpty()) emptyList()
        else {
            val filtered = if (searchQuery.isNotBlank()) {
                books.filter { it.title.contains(searchQuery, ignoreCase = true) || it.author.contains(searchQuery, ignoreCase = true) }
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

    if (isPlayerFullScreen && currentBookIndex in books.indices) {
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
            isDarkTheme = isDarkTheme,
            playerVolume = playerVolume,
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
                            if (selectedFolderUriString.isNotEmpty()) {
                                val folderUri = Uri.parse(selectedFolderUriString)
                                val scannedBooks = scanFolderForAudiobooks(context, folderUri)
                                if (scannedBooks.isNotEmpty()) books = scannedBooks
                            }
                        }
                    )

                    NavigationDrawerItem(
                        label = { Text("Settings") },
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
                                    placeholder = { Text("Search title or author...") },
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
                    if (organizedBooks.isEmpty()) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(64.dp), tint = Color.DarkGray)
                                Spacer(Modifier.height(16.dp))
                                Text("No audiobooks found.", style = MaterialTheme.typography.bodyLarge, color = Color.Gray)
                                Spacer(Modifier.height(16.dp))
                                Button(onClick = { folderPicker.launch(null) }) {
                                    Text("Select Audiobook Folder")
                                }
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
                                itemsIndexed(organizedBooks) { _, book ->
                                    val indexInOriginal = books.indexOf(book)
                                    val isSelected = indexInOriginal == currentBookIndex
                                    val cardArt = remember(book.id) { loadEmbeddedCoverArt(context, book.tracks.first().uri) }
                                    val cardProgress = if (isSelected && duration > 0L) (currentPosition.toFloat() / duration).coerceIn(0f, 1f) else 0f

                                    GridBookCard(
                                        book = book,
                                        coverArt = cardArt,
                                        progress = cardProgress,
                                        isSelected = isSelected,
                                        onClick = {
                                            currentBookIndex = indexInOriginal
                                            chaptersList = emptyList()
                                            updateCoverArtForBook(book)
                                            playBook(player, book, if (isSelected) currentPosition else 0L)
                                            isPlayerFullScreen = true
                                        }
                                    )
                                }
                            }
                        } else {
                            LazyColumn(
                                contentPadding = PaddingValues(16.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                itemsIndexed(organizedBooks) { _, book ->
                                    val indexInOriginal = books.indexOf(book)
                                    val isSelected = indexInOriginal == currentBookIndex
                                    val cardArt = remember(book.id) { loadEmbeddedCoverArt(context, book.tracks.first().uri) }
                                    val cardProgress = if (isSelected && duration > 0L) (currentPosition.toFloat() / duration).coerceIn(0f, 1f) else 0f

                                    ListBookCard(
                                        book = book,
                                        coverArt = cardArt,
                                        progress = cardProgress,
                                        durationText = if (isSelected && duration > 0L) formatTime(duration) else "${book.tracks.size} chapters",
                                        isSelected = isSelected,
                                        onClick = {
                                            currentBookIndex = indexInOriginal
                                            chaptersList = emptyList()
                                            updateCoverArtForBook(book)
                                            playBook(player, book, if (isSelected) currentPosition else 0L)
                                            isPlayerFullScreen = true
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Audio FX Modal Dialog
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

    // View Switcher Dialog
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

    // Sort Dialog
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

    // Settings Dialog with Configurable Skip Interval
    if (showSettingsDialog) {
        AlertDialog(
            onDismissRequest = { showSettingsDialog = false },
            title = { Text("App Settings") },
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

                    Spacer(Modifier.height(16.dp))
                    Divider(color = MaterialTheme.colorScheme.surfaceVariant)
                    Spacer(Modifier.height(16.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Auto-scan on startup", fontWeight = FontWeight.Bold)
                            Text("Check for new audiobooks whenever the app opens", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                        }
                        Switch(
                            checked = autoScanOnStartup,
                            onCheckedChange = {
                                autoScanOnStartup = it
                                prefs.edit().putBoolean("auto_scan_startup", it).apply()
                            }
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showSettingsDialog = false }) { Text("Done") } }
        )
    }

    // Sleep Timer Sheet
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
                        "60 Minutes" to 60
                    ).forEach { (label, min) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    if (min == 0) {
                                        sleepTimerActive = false
                                        sleepTimeRemaining = 0L
                                    } else {
                                        sleepTimeRemaining = min * 60 * 1000L
                                        sleepTimerActive = true
                                    }
                                    showSleepMenu = false
                                }
                                .padding(vertical = 12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(label)
                            if ((min == 0 && !sleepTimerActive) || (sleepTimerActive && sleepTimeRemaining / 60000L == min.toLong())) {
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
fun NowPlayingScreen(
    book: Audiobook,
    coverArt: ImageBitmap?,
    chapterTitle: String,
    isPlaying: Boolean,
    currentPosition: Long,
    duration: Long,
    skipIntervalSeconds: Int,
    silenceTrimEnabled: Boolean,
    sleepTimerActive: Boolean,
    sleepTimeRemaining: Long,
    isDarkTheme: Boolean,
    playerVolume: Float,
    onBackToBookshelf: () -> Unit,
    onPlayPause: () -> Unit,
    onSeek: (Long) -> Unit,
    onSkipPrevChapter: () -> Unit,
    onSkipNextChapter: () -> Unit,
    onQuickRewind: () -> Unit,
    onQuickForward: () -> Unit,
    onToggleSilenceTrim: () -> Unit,
    onOpenSleepMenu: () -> Unit,
    onToggleTheme: () -> Unit,
    onOpenAudioFX: () -> Unit,
    onVolumeChange: (Float) -> Unit
) {
    val percentRead = if (duration > 0L) ((currentPosition.toDouble() / duration.toDouble()) * 100).toInt() else 0

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // Top Header
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBackToBookshelf) {
                Icon(Icons.Default.Menu, contentDescription = "Menu", tint = MaterialTheme.colorScheme.onBackground)
            }
            Text("NOW PLAYING", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
            IconButton(onClick = onBackToBookshelf) {
                Icon(Icons.Default.Book, contentDescription = "Bookshelf", tint = MaterialTheme.colorScheme.onBackground)
            }
        }

        // Title and Author
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Text(
                text = book.title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onBackground
            )
            Text(
                text = book.author,
                style = MaterialTheme.typography.bodyMedium,
                color = Color.Gray,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        // Large Cover Art
        Box(
            modifier = Modifier
                .size(260.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            if (coverArt != null) {
                Image(bitmap = coverArt, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else {
                Icon(Icons.Default.Headphones, contentDescription = null, modifier = Modifier.size(96.dp), tint = MaterialTheme.colorScheme.primary)
            }
        }

        // Stats Display
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Duration: ${formatTime(duration)}", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            Text("Read: ${formatTime(currentPosition)} ( $percentRead% )", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
        }

        // Bottom Player Card Container
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(24.dp)
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = chapterTitle,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Spacer(Modifier.height(8.dp))

                // Chapter Scrubber Bar
                Slider(
                    value = currentPosition.toFloat(),
                    onValueChange = { onSeek(it.toLong()) },
                    valueRange = 0f..(duration.coerceAtLeast(1L).toFloat()),
                    colors = SliderDefaults.colors(
                        thumbColor = MaterialTheme.colorScheme.primary,
                        activeTrackColor = MaterialTheme.colorScheme.primary,
                        inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(formatTime(currentPosition), style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                    Text("-${formatTime((duration - currentPosition).coerceAtLeast(0L))}", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                }

                Spacer(Modifier.height(12.dp))

                // Main Controls Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onSkipPrevChapter) {
                        Icon(Icons.Default.SkipPrevious, contentDescription = "Prev Chapter", modifier = Modifier.size(32.dp), tint = MaterialTheme.colorScheme.onSurface)
                    }

                    IconButton(onClick = onQuickRewind) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.Replay, contentDescription = "Rewind", modifier = Modifier.size(32.dp), tint = MaterialTheme.colorScheme.onSurface)
                            Text("${skipIntervalSeconds}s", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                        }
                    }

                    FloatingActionButton(
                        onClick = onPlayPause,
                        shape = CircleShape,
                        containerColor = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(64.dp)
                    ) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = "Play/Pause",
                            tint = Color.Black,
                            modifier = Modifier.size(36.dp)
                        )
                    }

                    IconButton(onClick = onQuickForward) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.Forward, contentDescription = "Forward", modifier = Modifier.size(32.dp), tint = MaterialTheme.colorScheme.onSurface)
                            Text("${skipIntervalSeconds}s", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                        }
                    }

                    IconButton(onClick = onSkipNextChapter) {
                        Icon(Icons.Default.SkipNext, contentDescription = "Next Chapter", modifier = Modifier.size(32.dp), tint = MaterialTheme.colorScheme.onSurface)
                    }
                }

                Spacer(Modifier.height(20.dp))

                // Secondary Feature Toggles Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Silence Trimming Toggle
                    IconButton(
                        onClick = onToggleSilenceTrim,
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(if (silenceTrimEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Icon(
                            imageVector = Icons.Default.ContentCut,
                            contentDescription = "Trim Silence",
                            tint = if (silenceTrimEnabled) Color.Black else Color.Gray
                        )
                    }

                    // Sleep Timer
                    IconButton(onClick = onOpenSleepMenu) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.Bedtime,
                                contentDescription = "Sleep Timer",
                                tint = if (sleepTimerActive) MaterialTheme.colorScheme.primary else Color.Gray
                            )
                            if (sleepTimerActive) {
                                Badge(modifier = Modifier.offset(x = 10.dp, y = (-10).dp)) {
                                    Text("${TimeUnit.MILLISECONDS.toMinutes(sleepTimeRemaining)}m")
                                }
                            }
                        }
                    }

                    // Dark / Light Theme Toggle
                    IconButton(onClick = onToggleTheme) {
                        Icon(
                            imageVector = if (isDarkTheme) Icons.Default.DarkMode else Icons.Default.LightMode,
                            contentDescription = "Toggle Theme",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }

                    // Equalizer / Audio FX
                    IconButton(onClick = onOpenAudioFX) {
                        Icon(Icons.Default.GraphicEq, contentDescription = "Equalizer", tint = MaterialTheme.colorScheme.primary)
                    }
                }

                Spacer(Modifier.height(16.dp))

                // Volume Slider Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.VolumeMute, contentDescription = "Low Volume", tint = Color.Gray, modifier = Modifier.size(20.dp))
                    Slider(
                        value = playerVolume,
                        onValueChange = onVolumeChange,
                        valueRange = 0f..1f,
                        colors = SliderDefaults.colors(
                            thumbColor = MaterialTheme.colorScheme.primary,
                            activeTrackColor = MaterialTheme.colorScheme.primary,
                            inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant
                        ),
                        modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
                    )
                    Icon(Icons.Default.VolumeUp, contentDescription = "High Volume", tint = Color.Gray, modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}

@Composable
fun GridBookCard(
    book: Audiobook,
    coverArt: ImageBitmap?,
    progress: Float,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth().clickable { onClick() }) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            if (coverArt != null) {
                Image(bitmap = coverArt, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else {
                Icon(Icons.Default.Book, contentDescription = null, modifier = Modifier.size(48.dp), tint = Color.Gray)
            }

            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(8.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color.Black.copy(alpha = 0.6f))
                    .padding(4.dp)
            ) {
                Icon(Icons.Default.Headphones, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
            }

            if (progress > 0f) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth(progress)
                        .height(4.dp)
                        .background(MaterialTheme.colorScheme.tertiary)
                )
            }
        }

        Spacer(Modifier.height(8.dp))
        Text(book.title, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(book.author, style = MaterialTheme.typography.bodySmall, color = Color.Gray, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun ListBookCard(
    book: Audiobook,
    coverArt: ImageBitmap?,
    progress: Float,
    durationText: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable { onClick() },
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface
        ),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center
            ) {
                if (coverArt != null) {
                    Image(bitmap = coverArt, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                } else {
                    Icon(Icons.Default.Book, contentDescription = null, tint = Color.Black)
                }

                if (progress > 0f) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .fillMaxWidth(progress)
                            .height(4.dp)
                            .background(MaterialTheme.colorScheme.tertiary)
                    )
                }
            }

            Spacer(Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(book.title, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(book.author, style = MaterialTheme.typography.bodySmall, color = Color.Gray, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (durationText.isNotEmpty()) {
                    Text(durationText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
            }

            Icon(Icons.Default.Headphones, contentDescription = null, tint = if (isSelected) MaterialTheme.colorScheme.primary else Color.DarkGray)
        }
    }
}

private fun calculateSmartRewindPos(savedPos: Long, lastPauseTime: Long): Long {
    if (lastPauseTime <= 0L) return savedPos
    val elapsedMs = System.currentTimeMillis() - lastPauseTime
    val rewindMs = when {
        elapsedMs >= 8 * 3600 * 1000L -> 30000L
        elapsedMs >= 1 * 3600 * 1000L -> 15000L
        elapsedMs >= 10 * 60 * 1000L -> 5000L
        else -> 0L
    }
    return (savedPos - rewindMs).coerceAtLeast(0L)
}

private suspend fun fetchOnlineCoverArt(title: String, author: String): ImageBitmap? {
    return withContext(Dispatchers.IO) {
        try {
            val query = URLEncoder.encode("$title $author", "UTF-8")
            val url = URL("https://openlibrary.org/search.json?q=$query&limit=1")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 3000
            conn.readTimeout = 3000
            val jsonText = conn.inputStream.bufferedReader().use { it.readText() }
            val docs = JSONObject(jsonText).optJSONArray("docs")
            if (docs != null && docs.length() > 0) {
                val coverI = docs.getJSONObject(0).optInt("cover_i", -1)
                if (coverI != -1) {
                    val imgUrl = URL("https://covers.openlibrary.org/b/id/$coverI-M.jpg")
                    val bitmap = BitmapFactory.decodeStream(imgUrl.openStream())
                    return@withContext bitmap?.asImageBitmap()
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }
}

private fun loadEmbeddedCoverArt(context: Context, uri: Uri): ImageBitmap? {
    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(context, uri)
        val artBytes = retriever.embeddedPicture
        retriever.release()
        if (artBytes != null) {
            BitmapFactory.decodeByteArray(artBytes, 0, artBytes.size)?.asImageBitmap()
        } else null
    } catch (e: Exception) {
        try { retriever.release() } catch (_: Exception) {}
        null
    }
}

private fun scanFolderForAudiobooks(context: Context, folderUri: Uri): List<Audiobook> {
    val bookList = mutableListOf<Audiobook>()
    val rootDocument = DocumentFile.fromTreeUri(context, folderUri) ?: return emptyList()
    traverseDirectoryForBooks(rootDocument, rootDocument, bookList)
    return bookList
}

private fun traverseDirectoryForBooks(currentDir: DocumentFile, rootDir: DocumentFile, bookList: MutableList<Audiobook>) {
    val files = currentDir.listFiles()
    val audioFiles = mutableListOf<DocumentFile>()
    val subDirs = mutableListOf<DocumentFile>()

    for (file in files) {
        if (file.isDirectory) {
            subDirs.add(file)
        } else if (file.isFile) {
            val name = file.name ?: continue
            if (name.endsWith(".mp3", ignoreCase = true) ||
                name.endsWith(".m4a", ignoreCase = true) ||
                name.endsWith(".m4b", ignoreCase = true)
            ) {
                audioFiles.add(file)
            }
        }
    }

    if (audioFiles.isNotEmpty()) {
        val sortedTracks = audioFiles.map {
            AudioTrack(name = it.name?.substringBeforeLast(".") ?: "Track", uri = it.uri)
        }.sortedWith(Comparator { a, b -> a.name.compareTo(b.name, ignoreCase = true) })

        val parentDir = currentDir.parentFile
        val authorName = if (currentDir != rootDir && parentDir != null && parentDir != rootDir && parentDir.name != null) {
            parentDir.name!!
        } else {
            "Unknown Author"
        }

        val bookTitle = if (currentDir.name != null && currentDir != rootDir) currentDir.name!! else sortedTracks.first().name

        if (audioFiles.size == 1 && sortedTracks.first().name.endsWith(".m4b", ignoreCase = true)) {
            bookList.add(
                Audiobook(
                    id = sortedTracks.first().uri.toString(),
                    title = sortedTracks.first().name,
                    author = if (currentDir.name != null && currentDir != rootDir) currentDir.name!! else authorName,
                    tracks = sortedTracks
                )
            )
        } else {
            bookList.add(
                Audiobook(
                    id = currentDir.uri.toString(),
                    title = bookTitle,
                    author = authorName,
                    tracks = sortedTracks
                )
            )
        }
    }

    for (dir in subDirs) {
        traverseDirectoryForBooks(dir, rootDir, bookList)
    }
}

private fun playBook(player: ExoPlayer, book: Audiobook, startPositionMs: Long) {
    val mediaItems = book.tracks.map { MediaItem.fromUri(it.uri) }
    player.setMediaItems(mediaItems)
    player.seekTo(0, startPositionMs)
    player.prepare()
    player.play()
}

private fun formatTime(ms: Long): String {
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    val hours = minutes / 60
    val remainingMinutes = minutes % 60

    return if (hours > 0) {
        String.format("%d:%02d:%02d", hours, remainingMinutes, seconds)
    } else {
        String.format("%02d:%02d", remainingMinutes, seconds)
    }
}