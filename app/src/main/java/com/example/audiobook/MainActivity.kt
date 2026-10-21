package com.example.audiobook

import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.documentfile.provider.DocumentFile
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.delay

data class AudioTrack(val name: String, val uri: Uri)

class MainActivity : ComponentActivity() {
    private var exoPlayer: ExoPlayer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        exoPlayer = ExoPlayer.Builder(this).build()

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AudiobookApp(player = exoPlayer!!)
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
fun AudiobookApp(player: ExoPlayer) {
    val context = LocalContext.current
    var tracks by remember { mutableStateOf<List<AudioTrack>>(emptyList()) }
    var currentTrackIndex by remember { mutableIntStateOf(-1) }
    var isPlaying by remember { mutableStateOf(false) }
    var currentPosition by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var playbackSpeed by remember { mutableFloatStateOf(1.0f) }

    // Folder selection launcher
    val folderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri?.let {
            val scannedTracks = scanFolderForAudio(context, it)
            if (scannedTracks.isNotEmpty()) {
                tracks = scannedTracks
                currentTrackIndex = 0
                playTrack(player, scannedTracks[0])
            }
        }
    }

    // Sync ExoPlayer state with Compose UI
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
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    // Progress bar update ticker
    LaunchedEffect(isPlaying) {
        while (isPlaying) {
            currentPosition = player.currentPosition.coerceAtLeast(0L)
            duration = player.duration.coerceAtLeast(0L)
            delay(500)
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val isUnfolded = maxWidth > 600.dp

        if (isUnfolded) {
            // Foldable Unfolded Dual-Pane View
            Row(modifier = Modifier.fillMaxSize()) {
                Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    PlayerLeftPane(
                        currentTrack = tracks.getOrNull(currentTrackIndex),
                        playbackSpeed = playbackSpeed,
                        onSpeedChange = { speed ->
                            playbackSpeed = speed
                            player.playbackParameters = PlaybackParameters(speed)
                        },
                        onPickFolder = { folderPicker.launch(null) }
                    )
                }
                Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    PlayerRightPane(
                        tracks = tracks,
                        currentTrackIndex = currentTrackIndex,
                        isPlaying = isPlaying,
                        currentPosition = currentPosition,
                        duration = duration,
                        onPlayPause = {
                            if (isPlaying) player.pause() else player.play()
                        },
                        onSeek = { pos ->
                            player.seekTo(pos)
                            currentPosition = pos
                        },
                        onSkip15 = { forward ->
                            val delta = if (forward) 15000L else -15000L
                            val newPos = (player.currentPosition + delta).coerceIn(0L, duration)
                            player.seekTo(newPos)
                            currentPosition = newPos
                        },
                        onSelectTrack = { index ->
                            currentTrackIndex = index
                            playTrack(player, tracks[index])
                        }
                    )
                }
            }
        } else {
            // Foldable Cover Screen Single-Pane View
            Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                Button(
                    onClick = { folderPicker.launch(null) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Folder, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Select Audiobook Folder")
                }

                Spacer(Modifier.height(16.dp))

                PlayerRightPane(
                    tracks = tracks,
                    currentTrackIndex = currentTrackIndex,
                    isPlaying = isPlaying,
                    currentPosition = currentPosition,
                    duration = duration,
                    onPlayPause = { if (isPlaying) player.pause() else player.play() },
                    onSeek = { pos ->
                        player.seekTo(pos)
                        currentPosition = pos
                    },
                    onSkip15 = { forward ->
                        val delta = if (forward) 15000L else -15000L
                        val newPos = (player.currentPosition + delta).coerceIn(0L, duration)
                        player.seekTo(newPos)
                        currentPosition = newPos
                    },
                    onSelectTrack = { index ->
                        currentTrackIndex = index
                        playTrack(player, tracks[index])
                    }
                )
            }
        }
    }
}

@Composable
fun PlayerLeftPane(
    currentTrack: AudioTrack?,
    playbackSpeed: Float,
    onSpeedChange: (Float) -> Unit,
    onPickFolder: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Audiobooks", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            IconButton(onClick = onPickFolder) {
                Icon(Icons.Default.Folder, contentDescription = "Open Folder")
            }
        }

        // Album Art Box
        Box(
            modifier = Modifier
                .size(240.dp)
                .clip(RoundedCornerShape(28.dp))
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.MenuBook,
                contentDescription = null,
                modifier = Modifier.size(96.dp),
                tint = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }

        // Title and Speed Selector
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = currentTrack?.name ?: "No Book Selected",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(Modifier.height(16.dp))

            // Expressive Speed Buttons
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(1.0f, 1.25f, 1.5f, 2.0f).forEach { speed ->
                    FilterChip(
                        selected = playbackSpeed == speed,
                        onClick = { onSpeedChange(speed) },
                        label = { Text("${speed}x") }
                    )
                }
            }
        }
    }
}

@Composable
fun PlayerRightPane(
    tracks: List<AudioTrack>,
    currentTrackIndex: Int,
    isPlaying: Boolean,
    currentPosition: Long,
    duration: Long,
    onPlayPause: () -> Unit,
    onSeek: (Long) -> Unit,
    onSkip15: (Boolean) -> Unit,
    onSelectTrack: (Int) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // Scrubber & Time Display
        Column {
            Slider(
                value = currentPosition.toFloat(),
                onValueChange = { onSeek(it.toLong()) },
                valueRange = 0f..(duration.coerceAtLeast(1L).toFloat()),
                modifier = Modifier.fillMaxWidth()
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(formatTime(currentPosition), style = MaterialTheme.typography.bodySmall)
                Text(formatTime(duration), style = MaterialTheme.typography.bodySmall)
            }
        }

        // Expressive Playback Controls
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { onSkip15(false) }) {
                Icon(Icons.Default.Replay10, contentDescription = "Skip Back 15s", modifier = Modifier.size(36.dp))
            }

            // Organic Expressive Play Button
            FloatingActionButton(
                onClick = onPlayPause,
                shape = CircleShape,
                containerColor = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(72.dp)
            ) {
                Icon(
                    imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (isPlaying) "Pause" else "Play",
                    modifier = Modifier.size(36.dp)
                )
            }

            IconButton(onClick = { onSkip15(true) }) {
                Icon(Icons.Default.Forward10, contentDescription = "Forward 15s", modifier = Modifier.size(36.dp))
            }
        }

        // Playlist / Chapter Selector
        Text("Files / Chapters", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            itemsIndexed(tracks) { index, track ->
                val isSelected = index == currentTrackIndex
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelectTrack(index) },
                    colors = CardDefaults.cardColors(
                        containerColor = if (isSelected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface
                    )
                ) {
                    Text(
                        text = track.name,
                        modifier = Modifier.padding(16.dp),
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

private fun scanFolderForAudio(context: Context, folderUri: Uri): List<AudioTrack> {
    val trackList = mutableListOf<AudioTrack>()
    val rootDocument = DocumentFile.fromTreeUri(context, folderUri)

    rootDocument?.listFiles()?.forEach { file ->
        if (file.isFile && (file.name?.endsWith(".mp3", true) == true ||
                    file.name?.endsWith(".m4a", true) == true ||
                    file.name?.endsWith(".m4b", true) == true)) {
            file.name?.let { name ->
                trackList.add(AudioTrack(name = name, uri = file.uri))
            }
        }
    }
    return trackList.sortedBy { it.name }
}

private fun playTrack(player: ExoPlayer, track: AudioTrack) {
    player.setMediaItem(MediaItem.fromUri(track.uri))
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