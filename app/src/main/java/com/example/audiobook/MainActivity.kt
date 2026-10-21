package com.example.audiobook

import android.content.Context
import android.content.SharedPreferences
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
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
import androidx.media3.common.MediaItem
import androidx.media3.common.Metadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.extractor.metadata.id3.ChapterFrame
import kotlinx.coroutines.delay
import java.util.concurrent.TimeUnit

data class AudioTrack(val name: String, val uri: Uri)
data class Chapter(val title: String, val startMs: Long)
data class Bookmark(val id: Long, val trackName: String, val timestampMs: Long, val label: String)

class MainActivity : ComponentActivity() {
    private var exoPlayer: ExoPlayer? = null
    private lateinit var prefs: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        exoPlayer = ExoPlayer.Builder(this).build()
        prefs = getSharedPreferences("SirinAudioPreferences", Context.MODE_PRIVATE)

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    background = Color(0xFF0F0F0F),
                    surface = Color(0xFF161618),
                    surfaceVariant = Color(0xFF212124),
                    primary = Color(0xFFFF9E2C),
                    onPrimary = Color.Black,
                    secondary = Color(0xFF4DB6AC),
                    secondaryContainer = Color(0xFF2C2C30),
                    tertiary = Color(0xFFEF5350)
                )
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    SirinAudiobookApp(player = exoPlayer!!, prefs = prefs)
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

@Composable
fun SirinAudiobookApp(player: ExoPlayer, prefs: SharedPreferences) {
    val context = LocalContext.current
    var selectedTab by remember { mutableIntStateOf(0) }
    var tracks by remember { mutableStateOf<List<AudioTrack>>(emptyList()) }
    var currentTrackIndex by remember { mutableIntStateOf(-1) }
    var isPlaying by remember { mutableStateOf(false) }
    var currentPosition by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var playbackSpeed by remember { mutableFloatStateOf(1.0f) }
    var selectedFolderUriString by remember { mutableStateOf(prefs.getString("selected_folder", "") ?: "") }

    var currentCoverArt by remember { mutableStateOf<ImageBitmap?>(null) }
    var chaptersList by remember { mutableStateOf<List<Chapter>>(emptyList()) }
    var showChapterSheet by remember { mutableStateOf(false) }

    var sleepTimerActive by remember { mutableStateOf(false) }
    var sleepTimeRemaining by remember { mutableLongStateOf(0L) }
    var showSleepMenu by remember { mutableStateOf(false) }

    var bookmarksList by remember { mutableStateOf<List<Bookmark>>(emptyList()) }
    var showBookmarkDialog by remember { mutableStateOf(false) }
    var newBookmarkLabel by remember { mutableStateOf("") }

    val folderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri?.let {
            val stringUri = it.toString()
            selectedFolderUriString = stringUri
            prefs.edit().putString("selected_folder", stringUri).apply()
            val scannedTracks = scanFolderForAudio(context, it)
            if (scannedTracks.isNotEmpty()) {
                tracks = scannedTracks
                val lastTrackUri = prefs.getString("last_track_uri", "")
                val savedIndex = scannedTracks.indexOfFirst { t -> t.uri.toString() == lastTrackUri }
                if (savedIndex != -1) {
                    currentTrackIndex = savedIndex
                    val savedPos = prefs.getLong("last_track_pos", 0L)
                    currentCoverArt = loadEmbeddedCoverArt(context, scannedTracks[savedIndex].uri)
                    playTrack(player, scannedTracks[savedIndex], savedPos)
                } else {
                    currentTrackIndex = 0
                    currentCoverArt = loadEmbeddedCoverArt(context, scannedTracks[0].uri)
                    playTrack(player, scannedTracks[0], 0L)
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        if (selectedFolderUriString.isNotEmpty()) {
            try {
                val folderUri = Uri.parse(selectedFolderUriString)
                val scannedTracks = scanFolderForAudio(context, folderUri)
                tracks = scannedTracks
                val lastTrackUri = prefs.getString("last_track_uri", "")
                val savedIndex = scannedTracks.indexOfFirst { t -> t.uri.toString() == lastTrackUri }
                if (savedIndex != -1) {
                    currentTrackIndex = savedIndex
                    val savedPos = prefs.getLong("last_track_pos", 0L)
                    currentPosition = savedPos
                    playbackSpeed = prefs.getFloat("last_speed", 1.0f)
                    currentCoverArt = loadEmbeddedCoverArt(context, scannedTracks[savedIndex].uri)
                    player.playbackParameters = PlaybackParameters(playbackSpeed)
                    playTrack(player, scannedTracks[savedIndex], savedPos)
                    player.pause()
                }
            } catch (e: Exception) {
                // Ignore folder loading error
            }
        }
    }

    LaunchedEffect(currentPosition) {
        if (currentTrackIndex != -1 && tracks.isNotEmpty() && currentPosition > 0L) {
            prefs.edit().apply {
                putString("last_track_uri", tracks[currentTrackIndex].uri.toString())
                putLong("last_track_pos", currentPosition)
                putFloat("last_speed", playbackSpeed)
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

    Scaffold(
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    icon = { Icon(Icons.Default.LibraryBooks, contentDescription = "Library") },
                    label = { Text("Library") }
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    icon = { Icon(Icons.Default.PlayCircle, contentDescription = "Now Playing") },
                    label = { Text("Playing") }
                )
                NavigationBarItem(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    icon = { Icon(Icons.Default.Bookmark, contentDescription = "Bookmarks") },
                    label = { Text("Saved") }
                )
                NavigationBarItem(
                    selected = selectedTab == 3,
                    onClick = { selectedTab = 3 },
                    icon = { Icon(Icons.Default.Settings, contentDescription = "Settings") },
                    label = { Text("Settings") }
                )
            }
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            when (selectedTab) {
                0 -> LibraryScreen(
                    tracks = tracks,
                    currentTrackIndex = currentTrackIndex,
                    currentPosition = currentPosition,
                    duration = duration,
                    onPickFolder = { folderPicker.launch(null) },
                    onSelectTrack = { index ->
                        currentTrackIndex = index
                        chaptersList = emptyList()
                        currentCoverArt = loadEmbeddedCoverArt(context, tracks[index].uri)
                        playTrack(player, tracks[index], 0L)
                        selectedTab = 1
                    }
                )
                1 -> PlayerScreen(
                    currentTrack = tracks.getOrNull(currentTrackIndex),
                    coverArt = currentCoverArt,
                    chapters = chaptersList,
                    isPlaying = isPlaying,
                    currentPosition = currentPosition,
                    duration = duration,
                    playbackSpeed = playbackSpeed,
                    sleepTimerActive = sleepTimerActive,
                    sleepTimeRemaining = sleepTimeRemaining,
                    showSleepMenu = showSleepMenu,
                    showChapterSheet = showChapterSheet,
                    onPlayPause = { if (isPlaying) player.pause() else player.play() },
                    onSeek = { pos -> player.seekTo(pos); currentPosition = pos },
                    onSkip15 = { forward ->
                        val delta = if (forward) 15000L else -15000L
                        val newPos = (player.currentPosition + delta).coerceIn(0L, duration)
                        player.seekTo(newPos)
                        currentPosition = newPos
                    },
                    onSpeedChange = { speed ->
                        playbackSpeed = speed
                        player.playbackParameters = PlaybackParameters(speed)
                    },
                    onOpenSleepMenu = { showSleepMenu = true },
                    onCloseSleepMenu = { showSleepMenu = false },
                    onOpenChapters = { showChapterSheet = true },
                    onCloseChapters = { showChapterSheet = false },
                    onSelectChapter = { chapter ->
                        player.seekTo(chapter.startMs)
                        currentPosition = chapter.startMs
                        showChapterSheet = false
                    },
                    onStartTimer = { min ->
                        if (min == 0) {
                            sleepTimerActive = false
                            sleepTimeRemaining = 0L
                        } else {
                            sleepTimeRemaining = min * 60 * 1000L
                            sleepTimerActive = true
                        }
                        showSleepMenu = false
                    },
                    onAddBookmarkRequest = {
                        if (currentTrackIndex != -1) {
                            newBookmarkLabel = "Timestamp at " + formatTime(currentPosition)
                            showBookmarkDialog = true
                        }
                    }
                )
                2 -> BookmarksScreen(
                    bookmarks = bookmarksList,
                    onGoToBookmark = { bookmark ->
                        val matchedIndex = tracks.indexOfFirst { it.name == bookmark.trackName }
                        if (matchedIndex != -1) {
                            currentTrackIndex = matchedIndex
                            currentCoverArt = loadEmbeddedCoverArt(context, tracks[matchedIndex].uri)
                            playTrack(player, tracks[matchedIndex], bookmark.timestampMs)
                            selectedTab = 1
                        }
                    },
                    onDelete = { bookmark ->
                        bookmarksList = bookmarksList.filterNot { it.id == bookmark.id }
                    }
                )
                3 -> SettingsScreen(
                    selectedFolder = selectedFolderUriString,
                    onPickFolder = { folderPicker.launch(null) },
                    onClearCache = {
                        prefs.edit().clear().apply()
                        tracks = emptyList()
                        currentTrackIndex = -1
                        selectedFolderUriString = ""
                        currentCoverArt = null
                        chaptersList = emptyList()
                    }
                )
            }

            if (showBookmarkDialog) {
                AlertDialog(
                    onDismissRequest = { showBookmarkDialog = false },
                    title = { Text("Save Bookmark") },
                    text = {
                        Column {
                            Text("Name this location to remember exactly what is happening here:")
                            Spacer(Modifier.height(8.dp))
                            TextField(
                                value = newBookmarkLabel,
                                onValueChange = { newBookmarkLabel = it },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    },
                    confirmButton = {
                        Button(onClick = {
                            if (currentTrackIndex != -1 && tracks.isNotEmpty()) {
                                bookmarksList = bookmarksList + Bookmark(
                                    id = System.currentTimeMillis(),
                                    trackName = tracks[currentTrackIndex].name,
                                    timestampMs = currentPosition,
                                    label = newBookmarkLabel
                                )
                            }
                            showBookmarkDialog = false
                        }) {
                            Text("Save")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showBookmarkDialog = false }) {
                            Text("Cancel")
                        }
                    }
                )
            }
        }
    }
}

@Composable
fun LibraryScreen(
    tracks: List<AudioTrack>,
    currentTrackIndex: Int,
    currentPosition: Long,
    duration: Long,
    onPickFolder: () -> Unit,
    onSelectTrack: (Int) -> Unit
) {
    val context = LocalContext.current

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Your Bookshelf", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = Color.White)
            IconButton(onClick = onPickFolder) {
                Icon(Icons.Default.CreateNewFolder, contentDescription = "Add Folder", tint = MaterialTheme.colorScheme.primary)
            }
        }

        Spacer(Modifier.height(16.dp))

        if (tracks.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(64.dp), tint = Color.DarkGray)
                    Spacer(Modifier.height(16.dp))
                    Text("No audiobooks found.", style = MaterialTheme.typography.bodyLarge, color = Color.Gray)
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = onPickFolder) {
                        Text("Add Audiobook Folder")
                    }
                }
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                itemsIndexed(tracks) { index, track ->
                    val isSelected = index == currentTrackIndex
                    val cardArt = remember(track.uri) { loadEmbeddedCoverArt(context, track.uri) }

                    Card(
                        modifier = Modifier.fillMaxWidth().clickable { onSelectTrack(index) },
                        colors = CardDefaults.cardColors(
                            containerColor = if (isSelected) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface
                        ),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier.size(60.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.primary),
                                contentAlignment = Alignment.Center
                            ) {
                                if (cardArt != null) {
                                    Image(
                                        bitmap = cardArt,
                                        contentDescription = null,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                } else {
                                    Icon(Icons.Default.Book, contentDescription = null, tint = Color.Black)
                                }
                            }
                            Spacer(Modifier.width(16.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(track.name, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Spacer(Modifier.height(4.dp))
                                Text("Select to listen", style = MaterialTheme.typography.bodySmall, color = Color.Gray)

                                if (isSelected && duration > 0L) {
                                    Spacer(Modifier.height(8.dp))
                                    val progress = (currentPosition.toFloat() / duration).coerceIn(0f, 1f)
                                    LinearProgressIndicator(
                                        progress = { progress },
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp))
                                    )
                                }
                            }
                            Icon(Icons.Default.ChevronRight, contentDescription = null, tint = Color.DarkGray)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun PlayerScreen(
    currentTrack: AudioTrack?,
    coverArt: ImageBitmap?,
    chapters: List<Chapter>,
    isPlaying: Boolean,
    currentPosition: Long,
    duration: Long,
    playbackSpeed: Float,
    sleepTimerActive: Boolean,
    sleepTimeRemaining: Long,
    showSleepMenu: Boolean,
    showChapterSheet: Boolean,
    onPlayPause: () -> Unit,
    onSeek: (Long) -> Unit,
    onSkip15: (Boolean) -> Unit,
    onSpeedChange: (Float) -> Unit,
    onOpenSleepMenu: () -> Unit,
    onCloseSleepMenu: () -> Unit,
    onOpenChapters: () -> Unit,
    onCloseChapters: () -> Unit,
    onSelectChapter: (Chapter) -> Unit,
    onStartTimer: (Int) -> Unit,
    onAddBookmarkRequest: () -> Unit
) {
    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onOpenSleepMenu) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.Bedtime,
                            contentDescription = "Sleep Timer",
                            tint = if (sleepTimerActive) MaterialTheme.colorScheme.secondary else Color.White,
                            modifier = Modifier.size(28.dp)
                        )
                        if (sleepTimerActive) {
                            Badge(modifier = Modifier.offset(x = 12.dp, y = (-12).dp)) {
                                Text("${TimeUnit.MILLISECONDS.toMinutes(sleepTimeRemaining)}m")
                            }
                        }
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Sirin Audio Player", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Color.White)
                    if (chapters.isNotEmpty()) {
                        Spacer(Modifier.width(8.dp))
                        IconButton(onClick = onOpenChapters) {
                            Icon(Icons.Default.FormatListNumbered, contentDescription = "Chapters", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
                        }
                    }
                }

                IconButton(onClick = onAddBookmarkRequest) {
                    Icon(Icons.Default.BookmarkBorder, contentDescription = "Add Bookmark", tint = Color.White, modifier = Modifier.size(28.dp))
                }
            }

            Box(
                modifier = Modifier
                    .size(260.dp)
                    .clip(RoundedCornerShape(32.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                if (coverArt != null) {
                    Image(
                        bitmap = coverArt,
                        contentDescription = "Book Cover Art",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Icon(Icons.Default.Headphones, contentDescription = null, modifier = Modifier.size(96.dp), tint = MaterialTheme.colorScheme.primary)
                }
            }

            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = currentTrack?.name ?: "No audio file loaded",
                    style = MaterialTheme.typography.titleLarge,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
                Spacer(Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    listOf(1.0f, 1.25f, 1.5f, 2.0f, 2.5f).forEach { speed ->
                        FilterChip(
                            selected = playbackSpeed == speed,
                            onClick = { onSpeedChange(speed) },
                            label = { Text("${speed}x", color = if (playbackSpeed == speed) Color.Black else Color.White) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primary,
                                containerColor = Color(0xFF2C2C30)
                            ),
                            modifier = Modifier.padding(horizontal = 4.dp)
                        )
                    }
                }
            }

            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                Slider(
                    value = currentPosition.toFloat(),
                    onValueChange = { onSeek(it.toLong()) },
                    valueRange = 0f..(duration.coerceAtLeast(1L).toFloat()),
                    colors = SliderDefaults.colors(
                        thumbColor = MaterialTheme.colorScheme.primary,
                        activeTrackColor = MaterialTheme.colorScheme.primary,
                        inactiveTrackColor = Color.DarkGray
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(formatTime(currentPosition), style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                    Text(formatTime(duration), style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { onSkip15(false) }) {
                    Icon(Icons.Default.Replay10, contentDescription = "Rewind 15s", tint = Color.White, modifier = Modifier.size(36.dp))
                }

                FloatingActionButton(
                    onClick = onPlayPause,
                    shape = CircleShape,
                    containerColor = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(72.dp)
                ) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = "Playback Action Toggle",
                        tint = Color.Black,
                        modifier = Modifier.size(36.dp)
                    )
                }

                IconButton(onClick = { onSkip15(true) }) {
                    Icon(Icons.Default.Forward10, contentDescription = "Fast Forward 15s", tint = Color.White, modifier = Modifier.size(36.dp))
                }
            }
        }

        if (showChapterSheet) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.6f))
                    .clickable { onCloseChapters() },
                contentAlignment = Alignment.BottomCenter
            ) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight(0.6f)
                        .clickable(enabled = false) {},
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
                ) {
                    Column(modifier = Modifier.padding(24.dp)) {
                        Text("Chapters", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Color.White)
                        Spacer(Modifier.height(16.dp))
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            itemsIndexed(chapters) { _, chapter ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { onSelectChapter(chapter) }
                                        .padding(vertical = 12.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(chapter.title, color = Color.White, modifier = Modifier.weight(1f))
                                    Text(formatTime(chapter.startMs), color = Color.Gray)
                                }
                            }
                        }
                    }
                }
            }
        }

        if (showSleepMenu) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.6f))
                    .clickable { onCloseSleepMenu() },
                contentAlignment = Alignment.BottomCenter
            ) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = false) {},
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
                ) {
                    Column(modifier = Modifier.padding(24.dp)) {
                        Text("Sleep Timer Preset", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Color.White)
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
                                    .clickable { onStartTimer(min) }
                                    .padding(vertical = 12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(label, color = Color.White)
                                if (min == 0 && !sleepTimerActive) {
                                    Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                } else if (sleepTimerActive && sleepTimeRemaining / 60000L == min.toLong()) {
                                    Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun BookmarksScreen(
    bookmarks: List<Bookmark>,
    onGoToBookmark: (Bookmark) -> Unit,
    onDelete: (Bookmark) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("Your Saved Locations", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = Color.White)
        Spacer(Modifier.height(16.dp))

        if (bookmarks.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("You haven't saved any bookmarks yet.", style = MaterialTheme.typography.bodyLarge, color = Color.Gray)
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(bookmarks.size) { index ->
                    val bookmark = bookmarks[index]
                    Card(
                        modifier = Modifier.fillMaxWidth().clickable { onGoToBookmark(bookmark) },
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Bookmark, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(16.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(bookmark.label, fontWeight = FontWeight.Bold, color = Color.White)
                                Text(bookmark.trackName, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                            }
                            IconButton(onClick = { onDelete(bookmark) }) {
                                Icon(Icons.Default.Delete, contentDescription = "Remove Bookmark", tint = MaterialTheme.colorScheme.tertiary)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SettingsScreen(
    selectedFolder: String,
    onPickFolder: () -> Unit,
    onClearCache: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("App Settings", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = Color.White)
        Spacer(Modifier.height(24.dp))

        Card(
            modifier = Modifier.fillMaxWidth().clickable { onPickFolder() },
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(16.dp))
                Column {
                    Text("Root storage folder path", fontWeight = FontWeight.Bold, color = Color.White)
                    Text(
                        text = if (selectedFolder.isNotEmpty()) selectedFolder else "Tap to choose storage folder location",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.Gray,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        Card(
            modifier = Modifier.fillMaxWidth().clickable { onClearCache() },
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.DeleteForever, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary)
                Spacer(Modifier.width(16.dp))
                Column {
                    Text("Clear saved application state", fontWeight = FontWeight.Bold, color = Color.White)
                    Text("Resets storage links, speed, and positioning history", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                }
            }
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

private fun scanFolderForAudio(context: Context, folderUri: Uri): List<AudioTrack> {
    val trackList = mutableListOf<AudioTrack>()
    val rootDocument = DocumentFile.fromTreeUri(context, folderUri) ?: return emptyList()
    traverseDirectory(rootDocument, rootDocument, trackList)
    return trackList.sortedBy { it.name }
}

private fun traverseDirectory(currentDir: DocumentFile, rootDir: DocumentFile, trackList: MutableList<AudioTrack>) {
    val files = currentDir.listFiles()
    for (file in files) {
        if (file.isDirectory) {
            traverseDirectory(file, rootDir, trackList)
        } else if (file.isFile) {
            val fileName = file.name ?: continue
            if (fileName.endsWith(".mp3", ignoreCase = true) ||
                fileName.endsWith(".m4a", ignoreCase = true) ||
                fileName.endsWith(".m4b", ignoreCase = true)
            ) {
                val displayName = if (currentDir != rootDir && currentDir.name != null) {
                    "${currentDir.name} - $fileName"
                } else {
                    fileName
                }
                trackList.add(AudioTrack(name = displayName, uri = file.uri))
            }
        }
    }
}

private fun playTrack(player: ExoPlayer, track: AudioTrack, startPositionMs: Long) {
    player.setMediaItem(MediaItem.fromUri(track.uri))
    player.seekTo(startPositionMs)
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