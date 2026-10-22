package com.example.audiobook

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.util.LruCache
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import androidx.media3.common.MediaItem
import androidx.media3.common.Metadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.extractor.metadata.id3.ChapterFrame
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.DataInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.math.round

private object CoverMemoryCache {
    private val maxMemory = (Runtime.getRuntime().maxMemory() / 1024).toInt()
    private val cacheSize = (maxMemory / 8).coerceAtLeast(1024)
    val cache = object : LruCache<String, ImageBitmap>(cacheSize) {
        override fun sizeOf(key: String, bitmap: ImageBitmap): Int {
            return bitmap.asAndroidBitmap().byteCount / 1024
        }
    }
}

data class AudioTrack(val name: String, val uri: Uri)
data class Audiobook(
    val id: String,
    val title: String,
    val author: String,
    val series: String = "",
    val tracks: List<AudioTrack>
)
data class Chapter(val title: String, val startMs: Long)

data class Bookmark(
    val id: String = UUID.randomUUID().toString(),
    val positionMs: Long,
    val chapterTitle: String,
    val note: String,
    val timestampStr: String = SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault()).format(Date())
)

class MainActivity : ComponentActivity() {
    private lateinit var prefs: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("SirinAudioPreferences", Context.MODE_PRIVATE)

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

            var isDarkTheme by remember { mutableStateOf(prefs.getBoolean("is_dark_theme", true)) }

            // FABLEWOOD THEME COLORS
            val darkColors = darkColorScheme(
                background = Color(0xFF0C1A14), // Deep forest green
                surface = Color(0xFF142B20), // Rich mossy surface
                surfaceVariant = Color(0xFF1E3F30),
                primary = Color(0xFFD4AF37), // Warm Gold accent
                onPrimary = Color.Black,
                secondary = Color(0xFF2D5A27),
                tertiary = Color(0xFF8B5A2B) // Wood tone
            )

            val lightColors = lightColorScheme(
                background = Color(0xFFF0F4F1), // Crisp light green-grey
                surface = Color(0xFFFFFFFF),
                surfaceVariant = Color(0xFFDCE6E0),
                primary = Color(0xFFB58500), // Dark Goldenrod
                onPrimary = Color.White,
                secondary = Color(0xFF3E7A45),
                tertiary = Color(0xFF8B5A2B)
            )

            MaterialTheme(colorScheme = if (isDarkTheme) darkColors else lightColors) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    if (controller != null) {
                        SirinAudiobookApp(
                            player = controller!!,
                            prefs = prefs,
                            isDarkTheme = isDarkTheme,
                            onToggleTheme = {
                                isDarkTheme = !isDarkTheme
                                prefs.edit().putBoolean("is_dark_theme", isDarkTheme).apply()
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

@Composable
fun ContrastThemeIcon(
    tint: Color = MaterialTheme.colorScheme.primary,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val strokeWidth = 2.dp.toPx()
        val radius = (size.minDimension / 2f) - strokeWidth
        val center = Offset(size.width / 2f, size.height / 2f)

        drawCircle(
            color = tint,
            radius = radius,
            center = center,
            style = Stroke(width = strokeWidth)
        )

        val innerRadius = (radius - (strokeWidth / 2f)).coerceAtLeast(0f)
        drawArc(
            color = tint,
            startAngle = 90f,
            sweepAngle = 180f,
            useCenter = true,
            topLeft = Offset(center.x - innerRadius, center.y - innerRadius),
            size = Size(innerRadius * 2f, innerRadius * 2f)
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SirinAudiobookApp(
    player: Player,
    prefs: SharedPreferences,
    isDarkTheme: Boolean,
    onToggleTheme: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)

    var books by remember { mutableStateOf<List<Audiobook>>(emptyList()) }
    var currentBookId by remember { mutableStateOf(prefs.getString("last_book_id", "") ?: "") }
    var isPlaying by remember { mutableStateOf(false) }
    var currentPosition by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }

    var isPlayerFullScreen by remember { mutableStateOf(false) }
    var isCarModeActive by remember { mutableStateOf(false) }

    BackHandler(enabled = isCarModeActive || isPlayerFullScreen) {
        if (isCarModeActive) {
            isCarModeActive = false
        } else {
            isPlayerFullScreen = false
        }
    }

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

    var playbackSpeed by remember { mutableFloatStateOf(1.0f) }
    var playbackPitch by remember { mutableFloatStateOf(prefs.getFloat("last_pitch", 1.0f)) }
    var volumeBoostEnabled by remember { mutableStateOf(prefs.getBoolean("volume_boost", false)) }
    var silenceTrimEnabled by remember { mutableStateOf(prefs.getBoolean("silence_trim", false)) }
    var skipIntervalSeconds by remember { mutableIntStateOf(prefs.getInt("skip_interval", 15)) }
    var playerVolume by remember { mutableFloatStateOf(player.volume) }

    var sleepTimerActive by remember { mutableStateOf(false) }
    var sleepTimeRemaining by remember { mutableLongStateOf(0L) }
    var sleepEndAtChapter by remember { mutableStateOf(false) }
    var showSleepMenu by remember { mutableStateOf(false) }

    var totalListenMs by remember { mutableLongStateOf(prefs.getLong("total_listen_ms", 0L)) }
    var completedBooksCount by remember { mutableIntStateOf(loadCompletedBookIds(prefs).size) }
    var showAnalyticsDialog by remember { mutableStateOf(false) }

    var selectedFolderUriString by remember { mutableStateOf(prefs.getString("selected_folder", "") ?: "") }
    var viewMode by remember { mutableStateOf(prefs.getString("view_mode", "GRID") ?: "GRID") }
    var sortBy by remember { mutableStateOf(prefs.getString("sort_by", "AUTHOR") ?: "AUTHOR") }

    var searchQuery by remember { mutableStateOf("") }
    var isSearching by remember { mutableStateOf(false) }

    var showViewDialog by remember { mutableStateOf(false) }
    var showSortDialog by remember { mutableStateOf(false) }
    var showSettingsDialog by remember { mutableStateOf(false) }
    var showAudioFXDialog by remember { mutableStateOf(false) }
    var showChaptersDialog by remember { mutableStateOf(false) }
    var showBookmarksDialog by remember { mutableStateOf(false) }
    var showSpeedDialog by remember { mutableStateOf(false) }
    var newBookmarkNoteText by remember { mutableStateOf("") }
    var showAddBookmarkPrompt by remember { mutableStateOf(false) }

    var currentCoverArt by remember { mutableStateOf<ImageBitmap?>(null) }
    var chaptersList by remember { mutableStateOf<List<Chapter>>(emptyList()) }
    var currentBookBookmarks by remember { mutableStateOf<List<Bookmark>>(emptyList()) }

    val activeBook = remember(books, currentBookId) {
        books.find { it.id == currentBookId }
    }

    fun sendCustomCommand(action: String, enabled: Boolean) {
        (player as? MediaController)?.sendCustomCommand(
            SessionCommand(action, Bundle.EMPTY),
            Bundle().apply { putBoolean(AudiobookPlaybackService.KEY_ENABLED, enabled) }
        )
    }

    fun refreshBookmarksForCurrentBook() {
        activeBook?.let {
            currentBookBookmarks = loadBookBookmarks(prefs, it.id)
        }
    }

    fun applyBookSpecificSpeed(bookId: String) {
        val savedSpeed = loadBookSpeeds(prefs)[bookId] ?: 1.0f
        playbackSpeed = savedSpeed
    }

    fun updateSpeedForCurrentBook(newSpeed: Float) {
        playbackSpeed = newSpeed
        activeBook?.let {
            saveBookSpeed(prefs, it.id, newSpeed)
        }
    }

    fun loadChaptersForBook(book: Audiobook) {
        chaptersList = emptyList()
        coroutineScope.launch {
            val firstTrack = book.tracks.firstOrNull()
            if (book.tracks.size == 1 && firstTrack != null) {
                val uriStr = firstTrack.uri.toString().lowercase()
                if (uriStr.endsWith(".m4b") || firstTrack.name.lowercase().endsWith(".m4b")) {
                    val extracted = extractM4bChapters(context, firstTrack.uri)
                    if (extracted.isNotEmpty()) {
                        chaptersList = extracted
                    }
                }
            }
        }
    }

    val backupLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        uri?.let {
            coroutineScope.launch(Dispatchers.IO) {
                val jsonBackup = JSONObject().apply {
                    put("total_listen_ms", totalListenMs)
                    put("completed_books_count", completedBooksCount)
                    put("last_speed", playbackSpeed)
                    put("last_pitch", playbackPitch)
                    put("skip_interval", skipIntervalSeconds)
                    put("selected_folder", selectedFolderUriString)
                    put("book_speeds_json", prefs.getString("book_speeds_json", "{}"))
                    put("book_bookmarks_json", prefs.getString("book_bookmarks_json", "{}"))
                    put("completed_books_set_json", prefs.getString("completed_books_set_json", "[]"))
                    put("backup_date", SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date()))
                }.toString(4)

                context.contentResolver.openOutputStream(it)?.use { stream ->
                    stream.write(jsonBackup.toByteArray())
                }
            }
        }
    }

    val restoreLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            coroutineScope.launch(Dispatchers.IO) {
                try {
                    context.contentResolver.openInputStream(it)?.use { stream ->
                        val reader = BufferedReader(InputStreamReader(stream))
                        val jsonText = reader.readText()
                        val json = JSONObject(jsonText)

                        totalListenMs = json.optLong("total_listen_ms", totalListenMs)
                        playbackSpeed = json.optDouble("last_speed", playbackSpeed.toDouble()).toFloat()
                        playbackPitch = json.optDouble("last_pitch", playbackPitch.toDouble()).toFloat()
                        skipIntervalSeconds = json.optInt("skip_interval", skipIntervalSeconds)

                        prefs.edit().apply {
                            putLong("total_listen_ms", totalListenMs)
                            putFloat("last_speed", playbackSpeed)
                            putFloat("last_pitch", playbackPitch)
                            putInt("skip_interval", skipIntervalSeconds)
                            putString("book_speeds_json", json.optString("book_speeds_json", "{}"))
                            putString("book_bookmarks_json", json.optString("book_bookmarks_json", "{}"))
                            putString("completed_books_set_json", json.optString("completed_books_set_json", "[]"))
                            apply()
                        }
                        completedBooksCount = loadCompletedBookIds(prefs).size
                    }
                } catch (_: Exception) {}
            }
        }
    }

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

            coroutineScope.launch {
                val scannedBooks = scanFolderForAudiobooks(context, it)
                if (scannedBooks.isNotEmpty()) {
                    books = scannedBooks
                    val selectedBook = scannedBooks[0]
                    currentBookId = selectedBook.id
                    applyBookSpecificSpeed(selectedBook.id)
                    refreshBookmarksForCurrentBook()
                    loadChaptersForBook(selectedBook)
                    playBook(player, selectedBook, trackIndex = 0, startPositionMs = 0L, autoPlay = false)
                }
            }
        }
    }

    fun scanSavedFolder() {
        val savedUriStr = prefs.getString("selected_folder", "") ?: ""
        if (savedUriStr.isNotEmpty()) {
            coroutineScope.launch {
                try {
                    val folderUri = Uri.parse(savedUriStr)
                    val scannedBooks = scanFolderForAudiobooks(context, folderUri)
                    if (scannedBooks.isNotEmpty()) {
                        books = scannedBooks
                    }
                } catch (_: Exception) {}
            }
        }
    }

    LaunchedEffect(playbackSpeed, playbackPitch) {
        player.playbackParameters = PlaybackParameters(playbackSpeed, playbackPitch)
    }

    LaunchedEffect(silenceTrimEnabled) {
        sendCustomCommand(AudiobookPlaybackService.ACTION_SET_SILENCE_TRIM, silenceTrimEnabled)
    }

    LaunchedEffect(volumeBoostEnabled) {
        sendCustomCommand(AudiobookPlaybackService.ACTION_SET_VOLUME_BOOST, volumeBoostEnabled)
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
            coroutineScope.launch {
                try {
                    val folderUri = Uri.parse(savedUriStr)
                    val scannedBooks = scanFolderForAudiobooks(context, folderUri)
                    books = scannedBooks
                    val lastBookId = prefs.getString("last_book_id", "")
                    val savedBook = scannedBooks.find { b -> b.id == lastBookId }
                    if (savedBook != null) {
                        currentBookId = savedBook.id
                        val savedTrackIdx = prefs.getInt("last_track_index", 0)
                        val savedPos = prefs.getLong("last_track_pos", 0L)
                        val lastPauseTime = prefs.getLong("last_pause_time", 0L)
                        val rewindPos = calculateSmartRewindPos(savedPos, lastPauseTime)
                        currentPosition = rewindPos

                        applyBookSpecificSpeed(savedBook.id)
                        refreshBookmarksForCurrentBook()
                        loadChaptersForBook(savedBook)
                        updateCoverArtForBook(savedBook)
                        playBook(player, savedBook, trackIndex = savedTrackIdx, startPositionMs = rewindPos, autoPlay = false)
                    }
                } catch (_: Exception) {}
            }
        }
    }

    LaunchedEffect(sleepTimerActive, sleepTimeRemaining, sleepEndAtChapter, isPlaying, currentPosition) {
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
        var saveCounter = 0
        while (isPlaying) {
            currentPosition = player.currentPosition.coerceAtLeast(0L)
            delay(1000)
            totalListenMs += 1000L
            saveCounter++

            if (saveCounter >= 15) {
                saveCounter = 0
                activeBook?.let { b ->
                    prefs.edit().apply {
                        putString("last_book_id", b.id)
                        putInt("last_track_index", player.currentMediaItemIndex)
                        putLong("last_track_pos", currentPosition)
                        putLong("total_listen_ms", totalListenMs)
                        apply()
                    }
                }
            }

            activeBook?.let { b ->
                val isLastTrack = !player.hasNextMediaItem()
                if (isLastTrack && duration > 0L && currentPosition >= duration - 2000L) {
                    if (markBookCompleted(prefs, b.id)) {
                        completedBooksCount = loadCompletedBookIds(prefs).size
                    }
                }
            }
        }

        if (!isPlaying) {
            activeBook?.let { b ->
                prefs.edit().apply {
                    putString("last_book_id", b.id)
                    putInt("last_track_index", player.currentMediaItemIndex)
                    putLong("last_track_pos", currentPosition)
                    putLong("total_listen_ms", totalListenMs)
                    apply()
                }
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
            override fun onPlayerError(error: PlaybackException) {
                Toast.makeText(context, "Playback error: ${error.localizedMessage}", Toast.LENGTH_LONG).show()
            }
            override fun onMetadata(metadata: Metadata) {
                val parsedChapters = mutableListOf<Chapter>()
                for (i in 0 until metadata.length()) {
                    val entry = metadata.get(i)
                    if (entry is ChapterFrame) {
                        val title = entry.id ?: "Chapter ${parsedChapters.size + 1}"
                        parsedChapters.add(Chapter(title = title, startMs = entry.startTimeMs.toLong()))
                    } else {
                        try {
                            val clazz = entry.javaClass
                            if (clazz.simpleName.contains("Chapter", ignoreCase = true)) {
                                val titleStr = clazz.methods.find { it.name == "getTitle" || it.name == "getName" }?.invoke(entry)?.toString()
                                    ?: clazz.fields.find { it.name == "title" || it.name == "id" }?.get(entry)?.toString()
                                    ?: "Chapter ${parsedChapters.size + 1}"

                                val startMsVal = clazz.methods.find { it.name == "getStartTimeMs" || it.name == "getStartMs" }?.invoke(entry)
                                    ?: clazz.fields.find { it.name == "startTimeMs" || it.name == "startMs" }?.get(entry)

                                val startMs = when (startMsVal) {
                                    is Long -> startMsVal
                                    is Int -> startMsVal.toLong()
                                    else -> 0L
                                }
                                parsedChapters.add(Chapter(title = titleStr, startMs = startMs))
                            }
                        } catch (_: Exception) {}
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

    val organizedBooks = remember(books, currentBookId, sortBy, searchQuery) {
        if (books.isEmpty()) emptyList()
        else {
            val filtered = if (searchQuery.isNotBlank()) {
                books.filter { it.title.contains(searchQuery, ignoreCase = true) || it.author.contains(searchQuery, ignoreCase = true) || it.series.contains(searchQuery, ignoreCase = true) }
            } else books

            val pinnedBook = books.find { it.id == currentBookId }
            val remaining = filtered.filter { it.id != pinnedBook?.id }

            val sortedRemaining = if (sortBy == "AUTHOR") {
                remaining.sortedBy { it.author }
            } else {
                remaining.sortedBy { it.title }
            }

            if (pinnedBook != null && filtered.any { it.id == pinnedBook.id }) {
                listOf(pinnedBook) + sortedRemaining
            } else {
                sortedRemaining
            }
        }
    }

    if (isCarModeActive && activeBook != null) {
        val currentTrackName = if (player.mediaItemCount > 1) {
            activeBook.tracks.getOrNull(player.currentMediaItemIndex)?.name ?: activeBook.title
        } else {
            chaptersList.findLast { currentPosition >= it.startMs }?.title ?: "Chapter 1"
        }

        CarModeScreen(
            book = activeBook,
            chapterTitle = currentTrackName,
            isPlaying = isPlaying,
            onPlayPause = { if (isPlaying) player.pause() else player.play() },
            onQuickRewind = {
                val newPos = (player.currentPosition - 30000L).coerceAtLeast(0L)
                player.seekTo(newPos)
                currentPosition = newPos
            },
            onQuickForward = {
                val newPos = (player.currentPosition + 30000L).coerceAtMost(duration)
                player.seekTo(newPos)
                currentPosition = newPos
            },
            onAddBookmark = {
                saveBookBookmark(
                    prefs,
                    activeBook.id,
                    Bookmark(positionMs = currentPosition, chapterTitle = currentTrackName, note = "Car Bookmark")
                )
                refreshBookmarksForCurrentBook()
            },
            onExitCarMode = { isCarModeActive = false }
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

                if (isUnfolded && activeBook != null) {
                    Row(modifier = Modifier.fillMaxSize()) {
                        Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                            BookshelfContent(
                                books = organizedBooks,
                                activeBookId = currentBookId,
                                duration = duration,
                                currentPosition = currentPosition,
                                viewMode = viewMode,
                                onSelectBook = { book ->
                                    currentBookId = book.id
                                    applyBookSpecificSpeed(book.id)
                                    refreshBookmarksForCurrentBook()
                                    loadChaptersForBook(book)
                                    updateCoverArtForBook(book)
                                    playBook(player, book, trackIndex = 0, startPositionMs = 0L, autoPlay = false)
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
                                playbackSpeed = playbackSpeed,
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
                                onOpenChapters = { showChaptersDialog = true },
                                onOpenBookmarks = { showBookmarksDialog = true },
                                onOpenSpeed = { showSpeedDialog = true },
                                onEnterCarMode = { isCarModeActive = true },
                                onVolumeChange = { vol ->
                                    playerVolume = vol
                                    player.volume = vol
                                }
                            )
                        }
                    }
                } else if (isPlayerFullScreen && activeBook != null) {
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
                        playbackSpeed = playbackSpeed,
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
                        onOpenChapters = { showChaptersDialog = true },
                        onOpenBookmarks = { showBookmarksDialog = true },
                        onOpenSpeed = { showSpeedDialog = true },
                        onEnterCarMode = { isCarModeActive = true },
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
                                        Text("FABLEWOOD", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)

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
                            if (activeBook != null) {
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
                                activeBookId = currentBookId,
                                duration = duration,
                                currentPosition = currentPosition,
                                viewMode = viewMode,
                                onSelectBook = { book ->
                                    currentBookId = book.id
                                    applyBookSpecificSpeed(book.id)
                                    refreshBookmarksForCurrentBook()
                                    loadChaptersForBook(book)
                                    updateCoverArtForBook(book)
                                    playBook(player, book, trackIndex = 0, startPositionMs = 0L, autoPlay = true)
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
    }

    if (showChaptersDialog && activeBook != null) {
        val displayChapters = if (chaptersList.isNotEmpty()) {
            chaptersList
        } else {
            activeBook.tracks.mapIndexed { idx, track -> Chapter(track.name, 0L) }
        }

        AlertDialog(
            onDismissRequest = { showChaptersDialog = false },
            title = { Text("Table of Contents (${displayChapters.size})") },
            text = {
                Box(modifier = Modifier.heightIn(max = 350.dp)) {
                    LazyColumn {
                        items(count = displayChapters.size, key = { idx -> idx }) { idx ->
                            val chapter = displayChapters[idx]
                            val isCurrent = if (chaptersList.isNotEmpty()) {
                                val nextStart = chaptersList.getOrNull(idx + 1)?.startMs ?: Long.MAX_VALUE
                                currentPosition >= chapter.startMs && currentPosition < nextStart
                            } else {
                                player.currentMediaItemIndex == idx
                            }

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        if (player.mediaItemCount > 1) {
                                            player.seekTo(idx, 0L)
                                        } else {
                                            player.seekTo(chapter.startMs)
                                            currentPosition = chapter.startMs
                                        }
                                        showChaptersDialog = false
                                    }
                                    .background(if (isCurrent) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent)
                                    .padding(vertical = 12.dp, horizontal = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = chapter.title,
                                    fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                                    color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.weight(1f)
                                )
                                if (chapter.startMs > 0L) {
                                    Text(
                                        text = formatTime(chapter.startMs),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Color.Gray
                                    )
                                }
                            }
                            Divider(color = MaterialTheme.colorScheme.surfaceVariant)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showChaptersDialog = false }) { Text("Close") } }
        )
    }

    if (showBookmarksDialog && activeBook != null) {
        val currentTrackName = if (player.mediaItemCount > 1) {
            activeBook.tracks.getOrNull(player.currentMediaItemIndex)?.name ?: activeBook.title
        } else {
            chaptersList.findLast { currentPosition >= it.startMs }?.title ?: "Chapter 1"
        }

        AlertDialog(
            onDismissRequest = { showBookmarksDialog = false },
            title = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Bookmarks & Notes")
                    IconButton(onClick = { showAddBookmarkPrompt = true }) {
                        Icon(Icons.Default.Add, contentDescription = "Add Bookmark", tint = MaterialTheme.colorScheme.primary)
                    }
                }
            },
            text = {
                Column {
                    if (showAddBookmarkPrompt) {
                        OutlinedTextField(
                            value = newBookmarkNoteText,
                            onValueChange = { newBookmarkNoteText = it },
                            label = { Text("Bookmark Note (Optional)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
                        )
                        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                            TextButton(onClick = { showAddBookmarkPrompt = false; newBookmarkNoteText = "" }) { Text("Cancel") }
                            Button(onClick = {
                                saveBookBookmark(
                                    prefs,
                                    activeBook.id,
                                    Bookmark(positionMs = currentPosition, chapterTitle = currentTrackName, note = newBookmarkNoteText)
                                )
                                newBookmarkNoteText = ""
                                showAddBookmarkPrompt = false
                                refreshBookmarksForCurrentBook()
                            }) { Text("Save Pin") }
                        }
                        Divider(modifier = Modifier.padding(vertical = 8.dp))
                    }

                    if (currentBookBookmarks.isEmpty()) {
                        Text("No bookmarks saved for this book.", color = Color.Gray, modifier = Modifier.padding(vertical = 16.dp))
                    } else {
                        Box(modifier = Modifier.heightIn(max = 300.dp)) {
                            LazyColumn {
                                items(count = currentBookBookmarks.size, key = { idx -> currentBookBookmarks[idx].id }) { idx ->
                                    val bm = currentBookBookmarks[idx]
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                player.seekTo(bm.positionMs)
                                                currentPosition = bm.positionMs
                                                showBookmarksDialog = false
                                            }
                                            .padding(vertical = 10.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Text(formatTime(bm.positionMs), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                                                Spacer(Modifier.width(8.dp))
                                                Text(bm.chapterTitle, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                                            }
                                            if (bm.note.isNotBlank()) {
                                                Text(bm.note, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                                            }
                                            Text(bm.timestampStr, style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                                        }
                                        IconButton(onClick = {
                                            deleteBookBookmark(prefs, activeBook.id, bm.id)
                                            refreshBookmarksForCurrentBook()
                                        }) {
                                            Icon(Icons.Default.Delete, contentDescription = "Delete", tint = Color.Gray)
                                        }
                                    }
                                    Divider(color = MaterialTheme.colorScheme.surfaceVariant)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showBookmarksDialog = false }) { Text("Done") } }
        )
    }

    if (showSpeedDialog) {
        AlertDialog(
            onDismissRequest = { showSpeedDialog = false },
            title = { Text("Playback Speed (${String.format("%.2f", playbackSpeed)}x)") },
            text = {
                Column {
                    Slider(
                        value = playbackSpeed,
                        onValueChange = {
                            val rounded = (round(it * 20f) / 20f)
                            updateSpeedForCurrentBook(rounded)
                        },
                        valueRange = 0.5f..3.5f,
                        steps = 59,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        listOf(0.8f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f).forEach { spd ->
                            FilterChip(
                                selected = (playbackSpeed == spd),
                                onClick = { updateSpeedForCurrentBook(spd) },
                                label = { Text("${spd}x") }
                            )
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showSpeedDialog = false }) { Text("Done") } }
        )
    }

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
fun CarModeScreen(
    book: Audiobook,
    chapterTitle: String,
    isPlaying: Boolean,
    onPlayPause: () -> Unit,
    onQuickRewind: () -> Unit,
    onQuickForward: () -> Unit,
    onAddBookmark: () -> Unit,
    onExitCarMode: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF08120E))
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.DirectionsCar, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(36.dp))
                Spacer(Modifier.width(12.dp))
                Text("CAR MODE", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Color.White)
            }
            IconButton(
                onClick = onExitCarMode,
                modifier = Modifier.size(48.dp).clip(CircleShape).background(Color(0xFF1A3324))
            ) {
                Icon(Icons.Default.Close, contentDescription = "Exit Car Mode", tint = Color.White, modifier = Modifier.size(28.dp))
            }
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(horizontal = 16.dp)) {
            Text(
                text = book.title,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = chapterTitle,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onQuickRewind,
                modifier = Modifier.size(84.dp).clip(CircleShape).background(Color(0xFF12241C))
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.Replay30, contentDescription = "Rewind 30s", tint = Color.White, modifier = Modifier.size(40.dp))
                }
            }

            IconButton(
                onClick = onPlayPause,
                modifier = Modifier.size(128.dp)
            ) {
                Icon(
                    imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = "Play/Pause",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(96.dp)
                )
            }

            IconButton(
                onClick = onQuickForward,
                modifier = Modifier.size(84.dp).clip(CircleShape).background(Color(0xFF12241C))
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.Forward30, contentDescription = "Forward 30s", tint = Color.White, modifier = Modifier.size(40.dp))
                }
            }
        }

        Button(
            onClick = onAddBookmark,
            modifier = Modifier.fillMaxWidth().height(60.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1A3324))
        ) {
            Icon(Icons.Default.BookmarkAdd, contentDescription = null, tint = Color.White, modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(12.dp))
            Text("DROP BOOKMARK PIN", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Color.White)
        }
    }
}

@Composable
fun BookshelfContent(
    books: List<Audiobook>,
    activeBookId: String,
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
                items(count = books.size, key = { idx -> books[idx].id }) { index ->
                    val book = books[index]
                    val isSelected = book.id == activeBookId
                    var cardArt by remember(book.id) { mutableStateOf<ImageBitmap?>(null) }

                    LaunchedEffect(book.id) {
                        cardArt = getOrFetchCoverArt(context, book)
                    }

                    val cardProgress = if (isSelected && duration > 0L) (currentPosition.toFloat() / duration).coerceIn(0f, 1f) else 0f

                    GridBookCard(
                        book = book,
                        coverArt = cardArt,
                        progress = cardProgress,
                        isSelected = isSelected,
                        onClick = { onSelectBook(book) },
                        onLongClick = { onChangeCover(book) }
                    )
                }
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(count = books.size, key = { idx -> books[idx].id }) { index ->
                    val book = books[index]
                    val isSelected = book.id == activeBookId
                    var cardArt by remember(book.id) { mutableStateOf<ImageBitmap?>(null) }

                    LaunchedEffect(book.id) {
                        cardArt = getOrFetchCoverArt(context, book)
                    }

                    val cardProgress = if (isSelected && duration > 0L) (currentPosition.toFloat() / duration).coerceIn(0f, 1f) else 0f

                    ListBookCard(
                        book = book,
                        coverArt = cardArt,
                        progress = cardProgress,
                        durationText = if (isSelected && duration > 0L) formatTime(duration) else "${book.tracks.size} chapters",
                        isSelected = isSelected,
                        onClick = { onSelectBook(book) },
                        onLongClick = { onChangeCover(book) }
                    )
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
    sleepEndAtChapter: Boolean,
    isDarkTheme: Boolean,
    playerVolume: Float,
    playbackSpeed: Float,
    onOpenDrawer: () -> Unit,
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
    onOpenChapters: () -> Unit,
    onOpenBookmarks: () -> Unit,
    onOpenSpeed: () -> Unit,
    onEnterCarMode: () -> Unit,
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
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onOpenDrawer) {
                Icon(Icons.Default.Menu, contentDescription = "Open Drawer Menu")
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onEnterCarMode) {
                    Icon(Icons.Default.DirectionsCar, contentDescription = "Car Mode", tint = MaterialTheme.colorScheme.primary)
                }
                IconButton(onClick = onOpenChapters) {
                    Icon(Icons.Default.FormatListNumbered, contentDescription = "Chapters", tint = MaterialTheme.colorScheme.primary)
                }
                IconButton(onClick = onOpenBookmarks) {
                    Icon(Icons.Default.Bookmark, contentDescription = "Bookmarks", tint = MaterialTheme.colorScheme.primary)
                }
            }

            IconButton(onClick = onBackToBookshelf) {
                Icon(Icons.Default.Book, contentDescription = "Back to Bookshelf")
            }
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Text(
                text = book.title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = if (book.series.isNotEmpty()) "${book.series} • ${book.author}" else book.author,
                style = MaterialTheme.typography.bodyMedium,
                color = Color.Gray,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Box(
            modifier = Modifier
                .size(220.dp)
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

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Duration: ${formatTime(duration)}", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            Text("Read: ${formatTime(currentPosition)} ( $percentRead% )", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(24.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = chapterTitle,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.clickable { onOpenChapters() }
                )

                Spacer(Modifier.height(6.dp))

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

                Spacer(Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onSkipPrevChapter) {
                        Icon(Icons.Default.SkipPrevious, contentDescription = "Prev Chapter", modifier = Modifier.size(32.dp))
                    }

                    IconButton(onClick = onQuickRewind) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.Replay, contentDescription = "Rewind", modifier = Modifier.size(38.dp))
                            Text(
                                text = "$skipIntervalSeconds",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(top = 2.dp)
                            )
                        }
                    }

                    IconButton(
                        onClick = onPlayPause,
                        modifier = Modifier.size(60.dp)
                    ) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = "Play/Pause",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(52.dp)
                        )
                    }

                    IconButton(onClick = onQuickForward) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.RotateRight, contentDescription = "Forward", modifier = Modifier.size(38.dp))
                            Text(
                                text = "$skipIntervalSeconds",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(top = 2.dp)
                            )
                        }
                    }

                    IconButton(onClick = onSkipNextChapter) {
                        Icon(Icons.Default.SkipNext, contentDescription = "Next Chapter", modifier = Modifier.size(32.dp))
                    }
                }

                Spacer(Modifier.height(14.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onOpenSpeed) {
                        Text(
                            text = "${String.format("%.1f", playbackSpeed)}x",
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }

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

                    IconButton(onClick = onOpenSleepMenu) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.Bedtime,
                                contentDescription = "Sleep Timer",
                                tint = if (sleepTimerActive) MaterialTheme.colorScheme.primary else Color.Gray
                            )
                            if (sleepTimerActive) {
                                Badge(modifier = Modifier.offset(x = 10.dp, y = (-10).dp)) {
                                    Text(if (sleepEndAtChapter) "End" else "${TimeUnit.MILLISECONDS.toMinutes(sleepTimeRemaining)}m")
                                }
                            }
                        }
                    }

                    IconButton(onClick = onToggleTheme) {
                        ContrastThemeIcon(
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp)
                        )
                    }

                    IconButton(onClick = onOpenAudioFX) {
                        Icon(Icons.Default.GraphicEq, contentDescription = "Equalizer", tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GridBookCard(
    book: Audiobook,
    coverArt: ImageBitmap?,
    progress: Float,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
    ) {
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
        Text(if (book.series.isNotEmpty()) "${book.series} • ${book.author}" else book.author, style = MaterialTheme.typography.bodySmall, color = Color.Gray, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ListBookCard(
    book: Audiobook,
    coverArt: ImageBitmap?,
    progress: Float,
    durationText: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            ),
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
                Text(if (book.series.isNotEmpty()) "${book.series} • ${book.author}" else book.author, style = MaterialTheme.typography.bodySmall, color = Color.Gray, maxLines = 1, overflow = TextOverflow.Ellipsis)
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

private fun loadCompletedBookIds(prefs: SharedPreferences): Set<String> {
    val set = mutableSetOf<String>()
    try {
        val jsonStr = prefs.getString("completed_books_set_json", "[]") ?: "[]"
        val array = JSONArray(jsonStr)
        for (i in 0 until array.length()) {
            set.add(array.getString(i))
        }
    } catch (_: Exception) {}
    return set
}

private fun markBookCompleted(prefs: SharedPreferences, bookId: String): Boolean {
    val set = loadCompletedBookIds(prefs).toMutableSet()
    if (!set.contains(bookId)) {
        set.add(bookId)
        val array = JSONArray()
        set.forEach { array.put(it) }
        prefs.edit().putString("completed_books_set_json", array.toString()).apply()
        return true
    }
    return false
}

private fun loadBookSpeeds(prefs: SharedPreferences): MutableMap<String, Float> {
    val map = mutableMapOf<String, Float>()
    try {
        val jsonStr = prefs.getString("book_speeds_json", "{}") ?: "{}"
        val jsonObj = JSONObject(jsonStr)
        val keys = jsonObj.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            map[key] = jsonObj.getDouble(key).toFloat()
        }
    } catch (_: Exception) {}
    return map
}

private fun saveBookSpeed(prefs: SharedPreferences, bookId: String, speed: Float) {
    try {
        val map = loadBookSpeeds(prefs)
        map[bookId] = speed
        val jsonObj = JSONObject()
        map.forEach { (k, v) -> jsonObj.put(k, v.toDouble()) }
        prefs.edit().putString("book_speeds_json", jsonObj.toString()).apply()
    } catch (_: Exception) {}
}

private fun loadBookBookmarks(prefs: SharedPreferences, bookId: String): List<Bookmark> {
    val list = mutableListOf<Bookmark>()
    try {
        val jsonStr = prefs.getString("book_bookmarks_json", "{}") ?: "{}"
        val jsonObj = JSONObject(jsonStr)
        val array = jsonObj.optJSONArray(bookId) ?: JSONArray()
        for (i in 0 until array.length()) {
            val item = array.getJSONObject(i)
            list.add(
                Bookmark(
                    id = item.optString("id", UUID.randomUUID().toString()),
                    positionMs = item.optLong("positionMs", 0L),
                    chapterTitle = item.optString("chapterTitle", "Chapter"),
                    note = item.optString("note", ""),
                    timestampStr = item.optString("timestampStr", "")
                )
            )
        }
    } catch (_: Exception) {}
    return list.sortedBy { it.positionMs }
}

private fun saveBookBookmark(prefs: SharedPreferences, bookId: String, bookmark: Bookmark) {
    try {
        val jsonStr = prefs.getString("book_bookmarks_json", "{}") ?: "{}"
        val jsonObj = JSONObject(jsonStr)
        val array = jsonObj.optJSONArray(bookId) ?: JSONArray()

        val newObj = JSONObject().apply {
            put("id", bookmark.id)
            put("positionMs", bookmark.positionMs)
            put("chapterTitle", bookmark.chapterTitle)
            put("note", bookmark.note)
            put("timestampStr", bookmark.timestampStr)
        }
        array.put(newObj)
        jsonObj.put(bookId, array)
        prefs.edit().putString("book_bookmarks_json", jsonObj.toString()).apply()
    } catch (_: Exception) {}
}

private fun deleteBookBookmark(prefs: SharedPreferences, bookId: String, bookmarkId: String) {
    try {
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
    } catch (_: Exception) {}
}

private suspend fun extractM4bChapters(context: Context, uri: Uri): List<Chapter> {
    return withContext(Dispatchers.IO) {
        val chapters = mutableListOf<Chapter>()
        try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val maxHeaderBytes = 8 * 1024 * 1024
                val buffer = ByteArray(maxHeaderBytes)
                val bytesRead = stream.read(buffer, 0, maxHeaderBytes)
                if (bytesRead <= 0) return@withContext emptyList()

                var i = 0
                while (i < bytesRead - 8) {
                    if (buffer[i] == 'c'.code.toByte() &&
                        buffer[i + 1] == 'h'.code.toByte() &&
                        buffer[i + 2] == 'p'.code.toByte() &&
                        buffer[i + 3] == 'l'.code.toByte()
                    ) {
                        var offset = i + 8
                        if (offset + 4 <= bytesRead) {
                            val count = ((buffer[offset].toInt() and 0xFF) shl 24) or
                                        ((buffer[offset + 1].toInt() and 0xFF) shl 16) or
                                        ((buffer[offset + 2].toInt() and 0xFF) shl 8) or
                                        (buffer[offset + 3].toInt() and 0xFF)
                            offset += 4

                            for (c in 0 until count) {
                                if (offset + 9 > bytesRead) break
                                val duration100ns = DataInputStream(buffer.inputStream().apply { skip(offset.toLong()) }).readLong()
                                offset += 8

                                val titleLen = buffer[offset].toInt() and 0xFF
                                offset += 1

                                if (offset + titleLen > bytesRead) break
                                val titleBytes = buffer.copyOfRange(offset, offset + titleLen)
                                val title = String(titleBytes, Charsets.UTF_8).trim()
                                offset += titleLen

                                val startMs = duration100ns / 10000L
                                chapters.add(Chapter(title = if (title.isNotBlank()) title else "Chapter ${c + 1}", startMs = startMs))
                            }
                        }
                        break
                    }
                    i++
                }
            }
        } catch (_: Exception) {}
        chapters
    }
}

private suspend fun getOrFetchCoverArt(context: Context, book: Audiobook): ImageBitmap? {
    return withContext(Dispatchers.IO) {
        CoverMemoryCache.cache.get(book.id)?.let { return@withContext it }

        val cacheFile = File(context.filesDir, "covers/${book.id.hashCode()}.png")
        if (cacheFile.exists()) {
            val bmp = decodeSampledBitmapFromFile(cacheFile.absolutePath, 350, 350)
            if (bmp != null) {
                val imageBmp = bmp.asImageBitmap()
                CoverMemoryCache.cache.put(book.id, imageBmp)
                return@withContext imageBmp
            }
        }

        val embedded = loadEmbeddedCoverArt(context, book.tracks.first().uri)
        if (embedded != null) {
            saveBitmapToCache(context, book.id, embedded)
            CoverMemoryCache.cache.put(book.id, embedded)
            return@withContext embedded
        }

        val onlineBmp = fetchOnlineCoverArt(book.title, book.author)
        if (onlineBmp != null) {
            saveBitmapToCache(context, book.id, onlineBmp)
            CoverMemoryCache.cache.put(book.id, onlineBmp)
            return@withContext onlineBmp
        }

        null
    }
}

private fun decodeSampledBitmapFromFile(path: String, reqWidth: Int, reqHeight: Int): Bitmap? {
    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, options)
    options.inSampleSize = calculateInSampleSize(options, reqWidth, reqHeight)
    options.inJustDecodeBounds = false
    return BitmapFactory.decodeFile(path, options)
}

private fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
    val (height: Int, width: Int) = options.outHeight to options.outWidth
    var inSampleSize = 1
    if (height > reqHeight || width > reqWidth) {
        val halfHeight = height / 2
        val halfWidth = width / 2
        while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
            inSampleSize *= 2
        }
    }
    return inSampleSize
}

private fun saveBitmapToCache(context: Context, bookId: String, imageBitmap: ImageBitmap) {
    try {
        val coversDir = File(context.filesDir, "covers")
        if (!coversDir.exists()) coversDir.mkdirs()
        val cacheFile = File(coversDir, "${bookId.hashCode()}.png")

        val androidBmp = imageBitmap.asAndroidBitmap()
        FileOutputStream(cacheFile).use { out ->
            androidBmp.compress(Bitmap.CompressFormat.PNG, 90, out)
        }
    } catch (_: Exception) {}
}

private suspend fun saveCustomCoverArt(context: Context, bookId: String, uri: Uri) {
    withContext(Dispatchers.IO) {
        try {
            val stream = context.contentResolver.openInputStream(uri)
            if (stream != null) {
                val bitmap = decodeSampledBitmapFromStream(stream, 350, 350)
                stream.close()
                if (bitmap != null) {
                    val imageBmp = bitmap.asImageBitmap()
                    saveBitmapToCache(context, bookId, imageBmp)
                    CoverMemoryCache.cache.put(bookId, imageBmp)
                }
            }
        } catch (_: Exception) {}
    }
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
                    val stream = imgUrl.openStream()
                    val bitmap = decodeSampledBitmapFromStream(stream, 350, 350)
                    stream.close()
                    return@withContext bitmap?.asImageBitmap()
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }
}

private fun decodeSampledBitmapFromStream(stream: InputStream, reqWidth: Int, reqHeight: Int): Bitmap? {
    val bytes = stream.readBytes()
    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    options.inSampleSize = calculateInSampleSize(options, reqWidth, reqHeight)
    options.inJustDecodeBounds = false
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
}

private fun loadEmbeddedCoverArt(context: Context, uri: Uri): ImageBitmap? {
    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(context, uri)
        val artBytes = retriever.embeddedPicture
        retriever.release()
        if (artBytes != null) {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(artBytes, 0, artBytes.size, options)
            options.inSampleSize = calculateInSampleSize(options, 350, 350)
            options.inJustDecodeBounds = false
            BitmapFactory.decodeByteArray(artBytes, 0, artBytes.size, options)?.asImageBitmap()
        } else null
    } catch (e: Exception) {
        try { retriever.release() } catch (_: Exception) {}
        null
    }
}

private suspend fun scanFolderForAudiobooks(context: Context, folderUri: Uri): List<Audiobook> {
    return withContext(Dispatchers.IO) {
        val bookList = mutableListOf<Audiobook>()
        val rootDocument = DocumentFile.fromTreeUri(context, folderUri) ?: return@withContext emptyList()
        traverseDirectoryForBooks(rootDocument, rootDocument, bookList)
        bookList
    }
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
        val grandParentDir = parentDir?.parentFile

        var authorName = "Unknown Author"
        var seriesName = ""

        if (currentDir != rootDir && parentDir != null && parentDir != rootDir) {
            if (grandParentDir != null && grandParentDir != rootDir && grandParentDir.name != null) {
                authorName = grandParentDir.name!!
                seriesName = parentDir.name!!
            } else if (parentDir.name != null) {
                authorName = parentDir.name!!
            }
        }

        val bookTitle = if (currentDir.name != null && currentDir != rootDir) currentDir.name!! else sortedTracks.first().name

        if (audioFiles.size == 1 && sortedTracks.first().name.endsWith(".m4b", ignoreCase = true)) {
            bookList.add(
                Audiobook(
                    id = sortedTracks.first().uri.toString(),
                    title = sortedTracks.first().name,
                    author = authorName,
                    series = seriesName,
                    tracks = sortedTracks
                )
            )
        } else {
            bookList.add(
                Audiobook(
                    id = currentDir.uri.toString(),
                    title = bookTitle,
                    author = authorName,
                    series = seriesName,
                    tracks = sortedTracks
                )
            )
        }
    }

    for (dir in subDirs) {
        traverseDirectoryForBooks(dir, rootDir, bookList)
    }
}

private fun playBook(player: Player, book: Audiobook, trackIndex: Int, startPositionMs: Long, autoPlay: Boolean = true) {
    val mediaItems = book.tracks.map { MediaItem.fromUri(it.uri) }
    player.setMediaItems(mediaItems)
    val validTrackIdx = trackIndex.coerceIn(0, (mediaItems.size - 1).coerceAtLeast(0))
    player.seekTo(validTrackIdx, startPositionMs)
    player.prepare()
    player.playWhenReady = autoPlay
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