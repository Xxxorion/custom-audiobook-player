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
import android.util.LruCache
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
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.DataInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.io.InputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.math.round

// In-memory LRU Cache for Cover Art (1/8th of available app RAM)
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
    var completedBooksCount by remember { mutableIntStateOf(prefs.getInt("completed_books_count", 0)) }
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

    fun refreshBookmarksForCurrentBook() {
        if (currentBookIndex in books.indices) {
            currentBookBookmarks = loadBookBookmarks(prefs, books[currentBookIndex].id)
        }
    }

    fun applyBookSpecificSpeed(bookId: String) {
        val savedSpeed = loadBookSpeeds(prefs)[bookId] ?: 1.0f
        playbackSpeed = savedSpeed
    }

    fun updateSpeedForCurrentBook(newSpeed: Float) {
        playbackSpeed = newSpeed
        if (currentBookIndex in books.indices) {
            saveBookSpeed(prefs, books[currentBookIndex].id, newSpeed)
        }
    }

    fun loadChaptersForBook(book: Audiobook) {
        chaptersList = emptyList()
        coroutineScope.launch {
            if (book.tracks.size == 1) {
                val extracted = extractM4bChapters(context, book.tracks.first().uri)
                if (extracted.isNotEmpty()) {
                    chaptersList = extracted
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
                    currentBookIndex = 0
                    applyBookSpecificSpeed(scannedBooks[0].id)
                    refreshBookmarksForCurrentBook()
                    loadChaptersForBook(scannedBooks[0])
                    playBook(player, scannedBooks[0], 0L, autoPlay = false)
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
        player.skipSilenceEnabled = silenceTrimEnabled
    }

    // Leaking audio session native resource fix using DisposableEffect
    DisposableEffect(volumeBoostEnabled, isPlaying) {
        var enhancer: LoudnessEnhancer? = null
        if (volumeBoostEnabled && player.audioSessionId != C.AUDIO_SESSION_ID_UNSET) {
            try {
                enhancer = LoudnessEnhancer(player.audioSessionId).apply {
                    setTargetGain(1200)
                    enabled = true
                }
            } catch (_: Exception) {}
        }
        onDispose {
            try {
                enhancer?.enabled = false
                enhancer?.release()
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
            coroutineScope.launch {
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

                        applyBookSpecificSpeed(scannedBooks[savedIndex].id)
                        refreshBookmarksForCurrentBook()
                        loadChaptersForBook(scannedBooks[savedIndex])
                        updateCoverArtForBook(scannedBooks[savedIndex])
                        playBook(player, scannedBooks[savedIndex], rewindPos, autoPlay = false)
                    }
                } catch (_: Exception) {}
            }
        }
    }

    LaunchedEffect(currentPosition) {
        if (currentBookIndex in books.indices && currentPosition > 0L) {
            prefs.edit().apply {
                putString("last_book_id", books[currentBookIndex].id)
                putLong("last_track_pos", currentPosition)
                putLong("last_pause_time", System.currentTimeMillis())
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

    // Throttled SharedPreferences disk writes to 30s intervals during active listening
    LaunchedEffect(isPlaying) {
        var elapsedSecs = 0
        while (isPlaying) {
            currentPosition = player.currentPosition.coerceAtLeast(0L)
            delay(1000)
            totalListenMs += 1000L
            elapsedSecs++

            if (elapsedSecs >= 30) {
                elapsedSecs = 0
                prefs.edit().putLong("total_listen_ms", totalListenMs).apply()
            }

            if (duration > 0L && currentPosition >= duration - 2000L) {
                completedBooksCount += 1
                prefs.edit().putInt("completed_books_count", completedBooksCount).apply()
            }
        }
        if (!isPlaying) {
            prefs.edit().putLong("total_listen_ms", totalListenMs).apply()
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

    if (isCarModeActive && currentBookIndex in books.indices) {
        val activeBook = books[currentBookIndex]
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
                                    applyBookSpecificSpeed(book.id)
                                    refreshBookmarksForCurrentBook()
                                    loadChaptersForBook(book)
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
                                    applyBookSpecificSpeed(book.id)
                                    refreshBookmarksForCurrentBook()
                                    loadChaptersForBook(book)
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
    }

    if (showChaptersDialog && currentBookIndex in books.indices) {
        val activeBook = books[currentBookIndex]
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

    if (showBookmarksDialog && currentBookIndex in books.indices) {
        val activeBook = books[currentBookIndex]
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
            .background(Color(0xFF101216))
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
                modifier = Modifier.size(48.dp).clip(CircleShape).background(Color(0xFF2E323D))
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
                modifier = Modifier.size(84.dp).clip(CircleShape).background(Color(0xFF222630))
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.Replay30, contentDescription = "Rewind 30s", tint = Color.White, modifier = Modifier.size(40.dp))
                }
            }

            IconButton(
                onClick = onPlayPause,
                modifier = Modifier.size(128.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary)
            ) {
                Icon(
                    imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = "Play/Pause",
                    tint = Color.Black,
                    modifier = Modifier.size(72.dp)
                )
            }

            IconButton(
                onClick = onQuickForward,
                modifier = Modifier.size(84.dp).clip(CircleShape).background(Color(0xFF222630))
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
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2A2E39))
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
                items(count = books.size, key = { idx -> books[idx].id }) { index ->
                    val book = books[index]
                    val isSelected = books.indexOf(book) == currentBookIndex
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
                    val isSelected = books.indexOf(book) == currentBookIndex
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

                    FloatingActionButton(
                        onClick = onPlayPause,
                        shape = CircleShape,
                        containerColor = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(60.dp)
                    ) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = "Play/Pause",
                            tint = Color.Black,
                            modifier = Modifier.size(34.dp)
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

// ---------------------------------------------------------------------------
// M4B chapter reading
// Reads chapter markers straight from the MP4/M4B file structure. Supports the
// two common ways audiobooks store chapters:
//   1) a "chpl" chapter list (Audible, ffmpeg, Nero, most converters)
//   2) a QuickTime-style chapter text track (Apple / iTunes / some taggers)
// Works even when the book's index (the "moov" section) is at the end of a
// very large file, without loading the whole file into memory.
// ---------------------------------------------------------------------------

private class Mp4Box(val type: String, val start: Int, val end: Int)

private fun mp4U32(b: ByteArray, o: Int): Long =
    ((b[o].toLong() and 0xFF) shl 24) or
            ((b[o + 1].toLong() and 0xFF) shl 16) or
            ((b[o + 2].toLong() and 0xFF) shl 8) or
            (b[o + 3].toLong() and 0xFF)

private fun mp4U64(b: ByteArray, o: Int): Long = (mp4U32(b, o) shl 32) or mp4U32(b, o + 4)

private fun mp4U16(b: ByteArray, o: Int): Int =
    ((b[o].toInt() and 0xFF) shl 8) or (b[o + 1].toInt() and 0xFF)

private fun mp4ListBoxes(b: ByteArray, start: Int, end: Int): List<Mp4Box> {
    val result = mutableListOf<Mp4Box>()
    var pos = start
    while (pos + 8 <= end) {
        var size = mp4U32(b, pos)
        val type = String(b, pos + 4, 4, Charsets.ISO_8859_1)
        var headerLen = 8
        if (size == 1L) {
            if (pos + 16 > end) break
            size = mp4U64(b, pos + 8)
            headerLen = 16
        } else if (size == 0L) {
            size = (end - pos).toLong()
        }
        if (size < headerLen) break
        if (pos + size > end) size = (end - pos).toLong()
        result.add(Mp4Box(type, pos + headerLen, pos + size.toInt()))
        pos += size.toInt()
    }
    return result
}

private fun mp4Child(b: ByteArray, parent: Mp4Box, type: String): Mp4Box? =
    mp4ListBoxes(b, parent.start, parent.end).firstOrNull { it.type == type }

private fun mp4ReadAt(channel: FileChannel, position: Long, length: Int): ByteArray? {
    if (length < 0 || length > 32 * 1024 * 1024) return null
    val data = ByteArray(length)
    val buf = ByteBuffer.wrap(data)
    while (buf.hasRemaining()) {
        val n = channel.read(buf, position + buf.position())
        if (n <= 0) return null
    }
    return data
}

// Walks the top-level sections of the file (skipping the huge audio section)
// and returns the contents of the "moov" section, which holds the chapter info.
private fun mp4ReadMoov(channel: FileChannel): ByteArray? {
    val fileSize = channel.size()
    var pos = 0L
    while (pos + 8 <= fileSize) {
        val head = ByteArray(16)
        val n = channel.read(ByteBuffer.wrap(head), pos)
        if (n < 8) return null
        var size = mp4U32(head, 0)
        val type = String(head, 4, 4, Charsets.ISO_8859_1)
        var headerLen = 8L
        if (size == 1L) {
            if (n < 16) return null
            size = mp4U64(head, 8)
            headerLen = 16L
        } else if (size == 0L) {
            size = fileSize - pos
        }
        if (size < headerLen) return null
        if (type == "moov") {
            val payload = size - headerLen
            if (payload > 32L * 1024 * 1024) return null
            return mp4ReadAt(channel, pos + headerLen, payload.toInt())
        }
        pos += size
    }
    return null
}

private fun mp4ParseChpl(b: ByteArray, box: Mp4Box): List<Chapter> {
    val chapters = mutableListOf<Chapter>()
    var o = box.start
    if (o + 5 > box.end) return chapters
    val version = b[o].toInt() and 0xFF
    o += 4
    if (version == 1) o += 4
    if (o >= box.end) return chapters
    val count = b[o].toInt() and 0xFF
    o += 1
    for (c in 0 until count) {
        if (o + 9 > box.end) break
        val start100ns = mp4U64(b, o)
        o += 8
        val titleLen = b[o].toInt() and 0xFF
        o += 1
        if (o + titleLen > box.end) break
        val title = String(b, o, titleLen, Charsets.UTF_8).trim()
        o += titleLen
        chapters.add(
            Chapter(
                title = if (title.isNotBlank()) title else "Chapter ${c + 1}",
                startMs = start100ns / 10000L
            )
        )
    }
    return chapters
}

private fun mp4DecodeChapterText(data: ByteArray, offset: Int, length: Int): String {
    if (length >= 2) {
        val b0 = data[offset].toInt() and 0xFF
        val b1 = data[offset + 1].toInt() and 0xFF
        if (b0 == 0xFE && b1 == 0xFF) {
            return String(data, offset + 2, length - 2, Charsets.UTF_16BE).trim()
        }
        if (b0 == 0xFF && b1 == 0xFE) {
            return String(data, offset + 2, length - 2, Charsets.UTF_16LE).trim()
        }
    }
    return String(data, offset, length, Charsets.UTF_8).trim()
}

private fun mp4ReadTextSamples(channel: FileChannel, b: ByteArray, trak: Mp4Box): List<Chapter> {
    val mdia = mp4Child(b, trak, "mdia") ?: return emptyList()
    val mdhd = mp4Child(b, mdia, "mdhd") ?: return emptyList()
    val minf = mp4Child(b, mdia, "minf") ?: return emptyList()
    val stbl = mp4Child(b, minf, "stbl") ?: return emptyList()
    val stts = mp4Child(b, stbl, "stts") ?: return emptyList()
    val stsz = mp4Child(b, stbl, "stsz") ?: return emptyList()
    val stsc = mp4Child(b, stbl, "stsc") ?: return emptyList()
    val stco = mp4Child(b, stbl, "stco")
    val co64 = mp4Child(b, stbl, "co64")
    if (stco == null && co64 == null) return emptyList()

    val mdhdVersion = b[mdhd.start].toInt() and 0xFF
    val timescale = mp4U32(b, mdhd.start + 4 + (if (mdhdVersion == 1) 16 else 8))
    if (timescale <= 0L) return emptyList()

    // How big each chapter title sample is
    val fixedSize = mp4U32(b, stsz.start + 4)
    val sampleCount = mp4U32(b, stsz.start + 8).toInt()
    if (sampleCount <= 0 || sampleCount > 20000) return emptyList()
    val sizes = IntArray(sampleCount) { idx ->
        if (fixedSize > 0L) fixedSize.toInt() else mp4U32(b, stsz.start + 12 + 4 * idx).toInt()
    }

    // When each chapter starts
    val startTimes = LongArray(sampleCount)
    val sttsEntries = mp4U32(b, stts.start + 4).toInt()
    var o = stts.start + 8
    var tick = 0L
    var idx = 0
    for (e in 0 until sttsEntries) {
        val cnt = mp4U32(b, o).toInt()
        val delta = mp4U32(b, o + 4)
        o += 8
        for (k in 0 until cnt) {
            if (idx >= sampleCount) break
            startTimes[idx] = tick * 1000L / timescale
            tick += delta
            idx++
        }
    }

    // Where each chapter title sample is stored in the file
    val stscEntries = mp4U32(b, stsc.start + 4).toInt()
    if (stscEntries <= 0) return emptyList()
    val firstChunk = IntArray(stscEntries)
    val perChunk = IntArray(stscEntries)
    for (e in 0 until stscEntries) {
        firstChunk[e] = mp4U32(b, stsc.start + 8 + 12 * e).toInt()
        perChunk[e] = mp4U32(b, stsc.start + 12 + 12 * e).toInt()
    }

    val chunkOffsets: LongArray = if (stco != null) {
        val n = mp4U32(b, stco.start + 4).toInt()
        LongArray(n) { mp4U32(b, stco.start + 8 + 4 * it) }
    } else {
        val n = mp4U32(b, co64!!.start + 4).toInt()
        LongArray(n) { mp4U64(b, co64.start + 8 + 8 * it) }
    }

    val chapters = mutableListOf<Chapter>()
    var sampleIdx = 0
    var stscIdx = 0
    for (chunk in 1..chunkOffsets.size) {
        while (stscIdx + 1 < stscEntries && firstChunk[stscIdx + 1] <= chunk) stscIdx++
        var offset = chunkOffsets[chunk - 1]
        for (s in 0 until perChunk[stscIdx]) {
            if (sampleIdx >= sampleCount) break
            val size = sizes[sampleIdx]
            val data = mp4ReadAt(channel, offset, size)
            offset += size
            if (data != null && data.size >= 2) {
                val textLen = minOf(mp4U16(data, 0), data.size - 2)
                val title = mp4DecodeChapterText(data, 2, textLen)
                chapters.add(
                    Chapter(
                        title = if (title.isNotBlank()) title else "Chapter ${sampleIdx + 1}",
                        startMs = startTimes[sampleIdx]
                    )
                )
            }
            sampleIdx++
        }
        if (sampleIdx >= sampleCount) break
    }
    return chapters
}

private fun mp4ParseTextChapters(channel: FileChannel, b: ByteArray, moov: Mp4Box): List<Chapter> {
    val traks = mp4ListBoxes(b, moov.start, moov.end).filter { it.type == "trak" }

    // Find which track is the chapter track
    var chapterTrackId = -1L
    for (trak in traks) {
        val tref = mp4Child(b, trak, "tref") ?: continue
        val chap = mp4Child(b, tref, "chap") ?: continue
        if (chap.end - chap.start >= 4) {
            chapterTrackId = mp4U32(b, chap.start)
            break
        }
    }
    if (chapterTrackId < 0L) return emptyList()

    for (trak in traks) {
        val tkhd = mp4Child(b, trak, "tkhd") ?: continue
        val version = b[tkhd.start].toInt() and 0xFF
        val idOffset = tkhd.start + 4 + (if (version == 1) 16 else 8)
        if (idOffset + 4 > tkhd.end) continue
        if (mp4U32(b, idOffset) != chapterTrackId) continue
        return mp4ReadTextSamples(channel, b, trak)
    }
    return emptyList()
}

private suspend fun extractM4bChapters(context: Context, uri: Uri): List<Chapter> {
    return withContext(Dispatchers.IO) {
        var result: List<Chapter> = emptyList()
        try {
            val pfd = context.contentResolver.openFileDescriptor(uri, "r")
            if (pfd != null) {
                pfd.use {
                    val input = FileInputStream(it.fileDescriptor)
                    val channel = input.channel
                    val moovData = mp4ReadMoov(channel)
                    if (moovData != null) {
                        val moov = Mp4Box("moov", 0, moovData.size)
                        val udta = mp4Child(moovData, moov, "udta")
                        val chpl = if (udta != null) mp4Child(moovData, udta, "chpl") else null
                        var found: List<Chapter> = emptyList()
                        if (chpl != null) found = mp4ParseChpl(moovData, chpl)
                        if (found.isEmpty()) found = mp4ParseTextChapters(channel, moovData, moov)
                        result = found.sortedBy { c -> c.startMs }
                    }
                }
            }
        } catch (_: Exception) {}
        result
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
            val bitmap = BitmapFactory.decodeStream(stream)
            stream?.close()
            if (bitmap != null) {
                val imageBmp = bitmap.asImageBitmap()
                saveBitmapToCache(context, bookId, imageBmp)
                CoverMemoryCache.cache.put(bookId, imageBmp)
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

private fun playBook(player: ExoPlayer, book: Audiobook, startPositionMs: Long, autoPlay: Boolean = true) {
    val mediaItems = book.tracks.map { MediaItem.fromUri(it.uri) }
    player.setMediaItems(mediaItems)
    player.seekTo(0, startPositionMs)
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