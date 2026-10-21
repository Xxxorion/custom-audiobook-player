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
import androidx.media3.common.MediaItem
import androidx.media3.common.Metadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.extractor.metadata.id3.ChapterFrame
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

data class AudioTrack(val name: String, val author: String, val title: String, val uri: Uri)
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
                    tertiary = Color(0xFFE53935)
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SirinAudiobookApp(player: ExoPlayer, prefs: SharedPreferences) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)

    var tracks by remember { mutableStateOf<List<AudioTrack>>(emptyList()) }
    var currentTrackIndex by remember { mutableIntStateOf(-1) }
    var isPlaying by remember { mutableStateOf(false) }
    var currentPosition by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var playbackSpeed by remember { mutableFloatStateOf(1.0f) }

    var selectedFolderUriString by remember { mutableStateOf(prefs.getString("selected_folder", "") ?: "") }
    var autoScanOnStartup by remember { mutableStateOf(prefs.getBoolean("auto_scan_startup", true)) }
    var viewMode by remember { mutableStateOf(prefs.getString("view_mode", "GRID") ?: "GRID") }
    var sortBy by remember { mutableStateOf(prefs.getString("sort_by", "AUTHOR") ?: "AUTHOR") }
    var filterStatus by remember { mutableStateOf("ALL") }

    var searchQuery by remember { mutableStateOf("") }
    var isSearching by remember { mutableStateOf(false) }

    var showViewDialog by remember { mutableStateOf(false) }
    var showSortDialog by remember { mutableStateOf(false) }
    var showSettingsDialog by remember { mutableStateOf(false) }

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

    fun rescanCurrentFolder() {
        if (selectedFolderUriString.isNotEmpty()) {
            try {
                val folderUri = Uri.parse(selectedFolderUriString)
                val scannedTracks = scanFolderForAudio(context, folderUri)
                if (scannedTracks.isNotEmpty()) {
                    tracks = scannedTracks
                }
            } catch (_: Exception) {}
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
                if (autoScanOnStartup) {
                    rescanCurrentFolder()
                }
            } catch (_: Exception) {}
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

    val organizedTracks = remember(tracks, currentTrackIndex, sortBy, searchQuery) {
        if (tracks.isEmpty()) emptyList()
        else {
            val filtered = if (searchQuery.isNotBlank()) {
                tracks.filter { it.name.contains(searchQuery, ignoreCase = true) || it.author.contains(searchQuery, ignoreCase = true) }
            } else tracks

            val pinnedTrack = if (currentTrackIndex in tracks.indices) tracks[currentTrackIndex] else null
            val remaining = filtered.filter { it != pinnedTrack }

            val sortedRemaining = if (sortBy == "AUTHOR") {
                remaining.sortedBy { it.author }
            } else {
                remaining.sortedBy { it.title }
            }

            if (pinnedTrack != null && filtered.contains(pinnedTrack)) {
                listOf(pinnedTrack) + sortedRemaining
            } else {
                sortedRemaining
            }
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(drawerContainerColor = Color(0xFF161618)) {
                Spacer(Modifier.height(24.dp))
                Row(
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Headphones, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp))
                    Spacer(Modifier.width(12.dp))
                    Text("Audiobooks", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Color.White)
                }
                Divider(modifier = Modifier.padding(vertical = 12.dp), color = Color(0xFF2C2C30))

                NavigationDrawerItem(
                    label = { Text("Scan folder", color = Color.White) },
                    icon = { Icon(Icons.Default.Sync, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                    selected = false,
                    onClick = {
                        coroutineScope.launch { drawerState.close() }
                        rescanCurrentFolder()
                    }
                )

                NavigationDrawerItem(
                    label = { Text("Settings", color = Color.White) },
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
                            Icon(Icons.Default.Menu, contentDescription = "Menu", tint = Color.White)
                        }

                        if (isSearching) {
                            TextField(
                                value = searchQuery,
                                onValueChange = { searchQuery = it },
                                placeholder = { Text("Search title or author...", color = Color.Gray) },
                                singleLine = true,
                                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                                colors = TextFieldDefaults.colors(
                                    focusedContainerColor = Color(0xFF212124),
                                    unfocusedContainerColor = Color(0xFF161618),
                                    focusedTextColor = Color.White,
                                    unfocusedTextColor = Color.White
                                )
                            )
                            IconButton(onClick = { isSearching = false; searchQuery = "" }) {
                                Icon(Icons.Default.Close, contentDescription = "Close Search", tint = Color.White)
                            }
                        } else {
                            Text("SIRIN", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Color.White)

                            Row {
                                IconButton(onClick = { isSearching = true }) {
                                    Icon(Icons.Default.Search, contentDescription = "Search", tint = Color.White)
                                }
                                IconButton(onClick = { showSortDialog = true }) {
                                    Icon(Icons.Default.Sort, contentDescription = "Sort", tint = Color.White)
                                }
                                IconButton(onClick = { showViewDialog = true }) {
                                    Icon(
                                        imageVector = if (viewMode == "GRID") Icons.Default.GridView else Icons.Default.ViewList,
                                        contentDescription = "View Switcher",
                                        tint = Color.White
                                    )
                                }
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilterChip(
                            selected = filterStatus == "ALL",
                            onClick = { filterStatus = "ALL" },
                            label = { Text("All (${tracks.size})", color = if (filterStatus == "ALL") Color.Black else Color.White) },
                            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = MaterialTheme.colorScheme.primary)
                        )
                        FilterChip(
                            selected = filterStatus == "NOT_STARTED",
                            onClick = { filterStatus = "NOT_STARTED" },
                            label = { Text("Not started", color = if (filterStatus == "NOT_STARTED") Color.Black else Color.White) },
                            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = MaterialTheme.colorScheme.primary)
                        )
                        FilterChip(
                            selected = filterStatus == "IN_PROGRESS",
                            onClick = { filterStatus = "IN_PROGRESS" },
                            label = { Text("In progress", color = if (filterStatus == "IN_PROGRESS") Color.Black else Color.White) },
                            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = MaterialTheme.colorScheme.primary)
                        )
                    }
                }
            },
            bottomBar = {
                if (currentTrackIndex in tracks.indices) {
                    val activeTrack = tracks[currentTrackIndex]
                    Surface(
                        color = Color(0xFF161618),
                        modifier = Modifier.fillMaxWidth().clickable { showChapterSheet = false }
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
                                    Text(activeTrack.title, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(activeTrack.author, style = MaterialTheme.typography.bodySmall, color = Color.Gray, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }

                            IconButton(onClick = { if (isPlaying) player.pause() else player.play() }) {
                                Icon(
                                    imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                    contentDescription = "Play/Pause",
                                    tint = MaterialTheme.colorScheme.tertiary,
                                    modifier = Modifier.size(32.dp)
                                )
                            }
                        }
                    }
                }
            }
        ) { padding ->
            Box(modifier = Modifier.padding(padding).fillMaxSize()) {
                if (organizedTracks.isEmpty()) {
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
                            itemsIndexed(organizedTracks) { _, track ->
                                val indexInOriginal = tracks.indexOf(track)
                                val isSelected = indexInOriginal == currentTrackIndex
                                val cardArt = remember(track.uri) { loadEmbeddedCoverArt(context, track.uri) }
                                val cardProgress = if (isSelected && duration > 0L) (currentPosition.toFloat() / duration).coerceIn(0f, 1f) else 0f

                                GridBookCard(
                                    track = track,
                                    coverArt = cardArt,
                                    progress = cardProgress,
                                    isSelected = isSelected,
                                    onClick = {
                                        currentTrackIndex = indexInOriginal
                                        chaptersList = emptyList()
                                        currentCoverArt = loadEmbeddedCoverArt(context, track.uri)
                                        playTrack(player, track, if (isSelected) currentPosition else 0L)
                                    }
                                )
                            }
                        }
                    } else {
                        LazyColumn(
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            itemsIndexed(organizedTracks) { _, track ->
                                val indexInOriginal = tracks.indexOf(track)
                                val isSelected = indexInOriginal == currentTrackIndex
                                val cardArt = remember(track.uri) { loadEmbeddedCoverArt(context, track.uri) }
                                val cardProgress = if (isSelected && duration > 0L) (currentPosition.toFloat() / duration).coerceIn(0f, 1f) else 0f

                                ListBookCard(
                                    track = track,
                                    coverArt = cardArt,
                                    progress = cardProgress,
                                    durationText = if (isSelected && duration > 0L) formatTime(duration) else "",
                                    isSelected = isSelected,
                                    onClick = {
                                        currentTrackIndex = indexInOriginal
                                        chaptersList = emptyList()
                                        currentCoverArt = loadEmbeddedCoverArt(context, track.uri)
                                        playTrack(player, track, if (isSelected) currentPosition else 0L)
                                    }
                                )
                            }
                        }
                    }
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
                                    Text("Grid View", color = Color.White)
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
                                    Text("List View", color = Color.White)
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
                                    Text("By Author Name", color = Color.White)
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
                                    Text("By Book Title", color = Color.White)
                                }
                            }
                        },
                        confirmButton = { TextButton(onClick = { showSortDialog = false }) { Text("Cancel") } }
                    )
                }

                // Settings Dialog
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
                                Divider(color = Color(0xFF2C2C30))
                                Spacer(Modifier.height(16.dp))

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text("Auto-scan on startup", fontWeight = FontWeight.Bold, color = Color.White)
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
            }
        }
    }
}

@Composable
fun GridBookCard(
    track: AudioTrack,
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
        Text(track.title, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(track.author, style = MaterialTheme.typography.bodySmall, color = Color.Gray, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun ListBookCard(
    track: AudioTrack,
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
                Text(track.title, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(track.author, style = MaterialTheme.typography.bodySmall, color = Color.Gray, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (durationText.isNotEmpty()) {
                    Text(durationText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
            }

            Icon(Icons.Default.Headphones, contentDescription = null, tint = if (isSelected) MaterialTheme.colorScheme.primary else Color.DarkGray)
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
    return trackList
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
                val authorName = if (currentDir != rootDir && currentDir.name != null) currentDir.name!! else "Unknown Author"
                val cleanTitle = fileName.substringBeforeLast(".")
                trackList.add(AudioTrack(name = fileName, author = authorName, title = cleanTitle, uri = file.uri))
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