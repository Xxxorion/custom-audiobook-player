package com.example.audiobook

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URLEncoder
import java.util.Locale
import kotlin.math.round

private const val TAG = "Fablewood"

data class ExtractedCoverPalette(
    val dominantHue: Float,
    val dominantSat: Float,
    val accentHue: Float,
    val accentSat: Float
)

fun extractCoverPaletteFromBitmap(bitmap: Bitmap?): ExtractedCoverPalette? {
    if (bitmap == null || bitmap.width <= 0 || bitmap.height <= 0) return null
    return try {
        val scaled = Bitmap.createScaledBitmap(bitmap, 24, 24, true)
        val pixels = IntArray(24 * 24)
        scaled.getPixels(pixels, 0, 24, 0, 0, 24, 24)

        val bucketCount = 36
        val bucketWeights = FloatArray(bucketCount)
        val bucketHueSum = FloatArray(bucketCount)
        val bucketSatSum = FloatArray(bucketCount)
        val bucketVibrancy = FloatArray(bucketCount)

        val hsv = FloatArray(3)
        for (px in pixels) {
            val alpha = (px ushr 24) and 0xFF
            if (alpha < 128) continue
            AndroidColor.colorToHSV(px, hsv)
            val h = hsv[0]
            val s = hsv[1]
            val v = hsv[2]

            // Ignore near-black, washed-out white, and monochrome grey pixels
            if (v < 0.14f || (v > 0.94f && s < 0.18f) || s < 0.14f) continue

            val bucket = ((h / 10f).toInt()).coerceIn(0, bucketCount - 1)
            val weight = s * v
            bucketWeights[bucket] += weight
            bucketHueSum[bucket] += h * weight
            bucketSatSum[bucket] += s * weight
            bucketVibrancy[bucket] += (s * s * v)
        }

        var domBucket = -1
        var maxWeight = 0f
        for (i in 0 until bucketCount) {
            if (bucketWeights[i] > maxWeight) {
                maxWeight = bucketWeights[i]
                domBucket = i
            }
        }

        if (domBucket == -1 || maxWeight <= 0f) return null

        val domHue = (bucketHueSum[domBucket] / maxWeight).coerceIn(0f, 360f)
        val domSat = (bucketSatSum[domBucket] / maxWeight).coerceIn(0.2f, 1f)

        var accentBucket = domBucket
        var maxVib = 0f
        for (i in 0 until bucketCount) {
            if (bucketWeights[i] <= 0f) continue
            val avgH = bucketHueSum[i] / bucketWeights[i]
            val hueDiff = kotlin.math.abs(avgH - domHue).let { if (it > 180f) 360f - it else it }
            val distinctBonus = if (hueDiff > 25f) 1.35f else 1.0f
            val score = bucketVibrancy[i] * distinctBonus
            if (score > maxVib) {
                maxVib = score
                accentBucket = i
            }
        }

        val accWeight = bucketWeights[accentBucket].coerceAtLeast(0.001f)
        val accHue = (bucketHueSum[accentBucket] / accWeight).coerceIn(0f, 360f)
        val accSat = (bucketSatSum[accentBucket] / accWeight).coerceIn(0.35f, 1f)

        ExtractedCoverPalette(
            dominantHue = domHue,
            dominantSat = domSat,
            accentHue = accHue,
            accentSat = accSat
        )
    } catch (t: Throwable) {
        Log.w(TAG, "Failed extracting palette from cover art", t)
        null
    }
}

private fun hsvColor(hue: Float, sat: Float, value: Float): Color {
    val cleanHue = ((hue % 360f) + 360f) % 360f
    val argb = AndroidColor.HSVToColor(
        floatArrayOf(
            cleanHue,
            sat.coerceIn(0f, 1f),
            value.coerceIn(0f, 1f)
        )
    )
    return Color(argb)
}

fun buildDynamicColorScheme(palette: ExtractedCoverPalette?, isDarkTheme: Boolean): ColorScheme {
    val defaultDark = darkColorScheme(
        background = Color(0xFF0C1A14),
        surface = Color(0xFF142B20),
        surfaceVariant = Color(0xFF1E3F30),
        onSurfaceVariant = Color(0xFFB5C7BD),
        primary = Color(0xFFD4AF37),
        onPrimary = Color.Black,
        secondary = Color(0xFF2D5A27),
        tertiary = Color(0xFF8B5A2B)
    )
    val defaultLight = lightColorScheme(
        background = Color(0xFFF0F4F1),
        surface = Color(0xFFFFFFFF),
        surfaceVariant = Color(0xFFDCE6E0),
        onSurfaceVariant = Color(0xFF44564C),
        primary = Color(0xFFB58500),
        onPrimary = Color.White,
        secondary = Color(0xFF3E7A45),
        tertiary = Color(0xFF8B5A2B)
    )

    if (palette == null) {
        return if (isDarkTheme) defaultDark else defaultLight
    }

    return if (isDarkTheme) {
        val bg = hsvColor(palette.dominantHue, (palette.dominantSat * 0.58f).coerceIn(0.22f, 0.52f), 0.12f)
        val surf = hsvColor(palette.dominantHue, (palette.dominantSat * 0.52f).coerceIn(0.22f, 0.48f), 0.19f)
        val surfVar = hsvColor(palette.dominantHue, (palette.dominantSat * 0.48f).coerceIn(0.24f, 0.50f), 0.28f)
        val onSurfVar = hsvColor(palette.dominantHue, 0.14f, 0.82f)
        val prim = hsvColor(palette.accentHue, palette.accentSat.coerceIn(0.50f, 0.82f), 0.92f)
        val sec = hsvColor(palette.dominantHue, 0.45f, 0.42f)
        val tert = hsvColor(palette.accentHue, 0.65f, 0.72f)

        defaultDark.copy(
            background = bg,
            surface = surf,
            surfaceVariant = surfVar,
            onSurfaceVariant = onSurfVar,
            primary = prim,
            onPrimary = Color.Black,
            secondary = sec,
            tertiary = tert
        )
    } else {
        val bg = hsvColor(palette.dominantHue, 0.09f, 0.96f)
        val surf = Color(0xFFFFFFFF)
        val surfVar = hsvColor(palette.dominantHue, 0.18f, 0.90f)
        val onSurfVar = hsvColor(palette.dominantHue, 0.30f, 0.32f)
        val prim = hsvColor(palette.accentHue, palette.accentSat.coerceIn(0.65f, 0.90f), 0.62f)
        val sec = hsvColor(palette.dominantHue, 0.50f, 0.48f)
        val tert = hsvColor(palette.accentHue, 0.60f, 0.55f)

        defaultLight.copy(
            background = bg,
            surface = surf,
            surfaceVariant = surfVar,
            onSurfaceVariant = onSurfVar,
            primary = prim,
            onPrimary = Color.White,
            secondary = sec,
            tertiary = tert
        )
    }
}

@Composable
fun rememberAnimatedColorScheme(target: ColorScheme): ColorScheme {
    val spec = tween<Color>(durationMillis = 550)
    val background by animateColorAsState(target.background, spec, label = "bg")
    val surface by animateColorAsState(target.surface, spec, label = "surf")
    val surfaceVariant by animateColorAsState(target.surfaceVariant, spec, label = "surfVar")
    val onSurfaceVariant by animateColorAsState(target.onSurfaceVariant, spec, label = "onSurfVar")
    val primary by animateColorAsState(target.primary, spec, label = "prim")
    val secondary by animateColorAsState(target.secondary, spec, label = "sec")
    val tertiary by animateColorAsState(target.tertiary, spec, label = "tert")

    return target.copy(
        background = background,
        surface = surface,
        surfaceVariant = surfaceVariant,
        onSurfaceVariant = onSurfaceVariant,
        primary = primary,
        secondary = secondary,
        tertiary = tertiary
    )
}

class LibraryViewModelFactory(private val repository: AudiobookRepository) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(LibraryViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return LibraryViewModel(repository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}

class PlayerBinder(
    val player: Player,
    private val viewModel: LibraryViewModel,
    private val appContext: Context
) {
    fun sendCustomCommand(action: String, enabled: Boolean) {
        (player as? MediaController)?.sendCustomCommand(
            SessionCommand(action, Bundle.EMPTY),
            Bundle().apply { putBoolean(AudiobookPlaybackService.KEY_ENABLED, enabled) }
        )
    }

    fun playBook(book: Audiobook, trackIndex: Int, positionMs: Long, autoPlay: Boolean) {
        viewModel.repository.touchBookLastPlayed(book.id)
        player.playWhenReady = autoPlay
        player.stop()
        player.clearMediaItems()
        val artBytes = viewModel.repository.bitmapToJpegBytes(viewModel.currentCoverArt)
        val mediaItems = buildBookMediaItems(book, artBytes)
        player.setMediaItems(mediaItems)
        val validIndex = if (trackIndex in mediaItems.indices) trackIndex else 0
        viewModel.currentTrackIndex = validIndex
        viewModel.currentPosition = positionMs.coerceAtLeast(0L)
        player.seekTo(validIndex, positionMs.coerceAtLeast(0L))
        player.prepare()
    }

    fun updatePlayingArtwork(book: Audiobook, artworkBytes: ByteArray?) {
        if (artworkBytes == null || artworkBytes.isEmpty() || player.mediaItemCount != book.tracks.size) return
        try {
            for (i in book.tracks.indices) {
                val track = book.tracks[i]
                val existing = player.getMediaItemAt(i)
                if (existing.mediaMetadata.artworkData != null) continue
                val updatedMeta = existing.mediaMetadata.buildUpon()
                    .setTitle(book.title)
                    .setArtist(book.author)
                    .setAlbumTitle(book.series)
                    .setDisplayTitle(track.name)
                    .setArtworkData(artworkBytes, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
                    .build()
                val updatedItem = MediaItem.Builder()
                    .setUri(track.uri)
                    .setMediaId(track.uri.toString())
                    .setMediaMetadata(updatedMeta)
                    .build()
                player.replaceMediaItem(i, updatedItem)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Failed updating MediaSession artwork", t)
        }
    }

    fun seekLive(positionMs: Long) {
        val safePos = positionMs.coerceAtLeast(0L)
        player.seekTo(safePos)
        viewModel.currentPosition = safePos
    }

    fun seekAndPersist(trackIndex: Int = player.currentMediaItemIndex, positionMs: Long) {
        val safePos = positionMs.coerceAtLeast(0L)
        val safeTrack = trackIndex.coerceAtLeast(0)
        player.seekTo(safeTrack, safePos)
        viewModel.currentTrackIndex = safeTrack
        viewModel.currentPosition = safePos
        viewModel.saveProgressNow(viewModel.currentBookId, safeTrack, safePos)
    }
}

class MainActivity : ComponentActivity() {
    private lateinit var repository: AudiobookRepository
    private lateinit var libraryViewModel: LibraryViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repository = AudiobookRepository(applicationContext)
        libraryViewModel = ViewModelProvider(this, LibraryViewModelFactory(repository))[LibraryViewModel::class.java]

        setContent {
            val context = LocalContext.current
            var controller by remember { mutableStateOf<Player?>(null) }

            DisposableEffect(context) {
                val appCtx = context.applicationContext
                val sessionToken = SessionToken(appCtx, ComponentName(appCtx, AudiobookPlaybackService::class.java))
                val controllerFuture = MediaController.Builder(appCtx, sessionToken).buildAsync()
                controllerFuture.addListener({
                    try {
                        controller = controllerFuture.get()
                    } catch (t: Throwable) {
                        Log.e(TAG, "Failed connecting MediaController", t)
                    }
                }, ContextCompat.getMainExecutor(appCtx))
                onDispose { MediaController.releaseFuture(controllerFuture) }
            }

            var isDarkTheme by remember {
                mutableStateOf(repository.getPreferences().getBoolean("is_dark_theme", true))
            }
            var dynamicThemeEnabled by remember {
                mutableStateOf(repository.getPreferences().getBoolean("dynamic_cover_theme", true))
            }
            var extractedPalette by remember { mutableStateOf<ExtractedCoverPalette?>(null) }

            LaunchedEffect(libraryViewModel.currentCoverArt, dynamicThemeEnabled) {
                if (!dynamicThemeEnabled || libraryViewModel.currentCoverArt == null) {
                    extractedPalette = null
                } else {
                    val bmp = libraryViewModel.currentCoverArt
                    extractedPalette = withContext(Dispatchers.Default) {
                        extractCoverPaletteFromBitmap(bmp)
                    }
                }
            }

            val targetColorScheme = remember(extractedPalette, isDarkTheme, dynamicThemeEnabled) {
                buildDynamicColorScheme(
                    palette = if (dynamicThemeEnabled) extractedPalette else null,
                    isDarkTheme = isDarkTheme
                )
            }
            val animatedColorScheme = rememberAnimatedColorScheme(targetColorScheme)

            MaterialTheme(colorScheme = animatedColorScheme) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    if (controller != null) {
                        val binder = remember(controller) {
                            PlayerBinder(controller!!, libraryViewModel, context.applicationContext)
                        }
                        FablewoodAudiobookApp(
                            binder = binder,
                            viewModel = libraryViewModel,
                            isDarkTheme = isDarkTheme,
                            dynamicThemeEnabled = dynamicThemeEnabled,
                            onToggleTheme = {
                                isDarkTheme = !isDarkTheme
                                repository.getPreferences().edit().putBoolean("is_dark_theme", isDarkTheme).apply()
                            },
                            onToggleDynamicTheme = { enabled ->
                                dynamicThemeEnabled = enabled
                                repository.getPreferences().edit().putBoolean("dynamic_cover_theme", enabled).apply()
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

private fun requiredAudioPermission(): String {
    return if (Build.VERSION.SDK_INT >= 33) {
        Manifest.permission.READ_MEDIA_AUDIO
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }
}

private fun hasAudioReadPermission(context: Context): Boolean {
    return ContextCompat.checkSelfPermission(context, requiredAudioPermission()) == PackageManager.PERMISSION_GRANTED
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FablewoodAudiobookApp(
    binder: PlayerBinder,
    viewModel: LibraryViewModel,
    isDarkTheme: Boolean,
    dynamicThemeEnabled: Boolean,
    onToggleTheme: () -> Unit,
    onToggleDynamicTheme: (Boolean) -> Unit
) {
    val player = binder.player
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)

    var targetBookForCoverAction by remember { mutableStateOf<Audiobook?>(null) }
    var showCoverOptionsDialog by remember { mutableStateOf(false) }
    var liveCoverSearchQuery by remember { mutableStateOf("") }

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

    var hasRequestedNotificationPermission by remember {
        mutableStateOf(viewModel.repository.getPreferences().getBoolean("notif_perm_requested", false))
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        Log.i(TAG, "POST_NOTIFICATIONS permission result: $granted")
    }

    fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 && !hasRequestedNotificationPermission) {
            hasRequestedNotificationPermission = true
            viewModel.repository.getPreferences().edit().putBoolean("notif_perm_requested", true).apply()
            val status = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            if (status != PackageManager.PERMISSION_GRANTED) {
                try {
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } catch (t: Throwable) {
                    Log.w(TAG, "Could not launch notification permission request", t)
                }
            }
        }
    }

    fun runAutoDeviceScan(explicitUserAction: Boolean) {
        coroutineScope.launch {
            try {
                viewModel.repository.saveScanMode("AUTO")
                viewModel.selectedFolderUri = "AUTO_SCAN"
                val scannedBooks = viewModel.repository.scanDeviceForAudiobooks()
                if (scannedBooks.isNotEmpty()) {
                    viewModel.books = scannedBooks
                    viewModel.repository.saveBooksCache(scannedBooks)
                    val lastBookId = viewModel.repository.getLastBookId()
                    if (viewModel.currentBookId.isEmpty() || scannedBooks.none { it.id == viewModel.currentBookId }) {
                        val firstBook = scannedBooks.find { it.id == lastBookId } ?: scannedBooks.first()
                        viewModel.selectBookMetadata(firstBook, isDifferentBook = true)
                        val savedTrackIdx = viewModel.repository.getBookTrackIndex(firstBook.id)
                        val savedPos = viewModel.repository.getBookPosition(firstBook.id)
                        binder.playBook(firstBook, savedTrackIdx, savedPos, false)
                    }
                    FablewoodWidgetProvider.updateAllWidgets(context)
                    if (explicitUserAction) {
                        Toast.makeText(context, "Found ${scannedBooks.size} audiobook(s)", Toast.LENGTH_SHORT).show()
                    }
                } else if (explicitUserAction && viewModel.books.isEmpty()) {
                    Toast.makeText(context, context.getString(R.string.no_supported_audio_files), Toast.LENGTH_SHORT).show()
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Auto device scan failed", t)
                if (explicitUserAction) {
                    Toast.makeText(context, context.getString(R.string.folder_scan_failed), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    val audioPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            runAutoDeviceScan(explicitUserAction = true)
        } else {
            Toast.makeText(context, context.getString(R.string.audio_permission_denied), Toast.LENGTH_LONG).show()
        }
    }

    fun triggerAutoScanWithPermission() {
        if (hasAudioReadPermission(context)) {
            runAutoDeviceScan(explicitUserAction = true)
        } else {
            try {
                audioPermissionLauncher.launch(requiredAudioPermission())
            } catch (t: Throwable) {
                Log.w(TAG, "Could not launch audio permission request", t)
            }
        }
    }

    val activeBook = remember(viewModel.books, viewModel.currentBookId) {
        viewModel.books.find { it.id == viewModel.currentBookId }
    }

    val customCoverLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { selectedUri ->
            targetBookForCoverAction?.let { book ->
                viewModel.setCustomCover(book.id, selectedUri) { success ->
                    if (success) {
                        Toast.makeText(context, "Custom cover saved!", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let {
            val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION
            try {
                context.contentResolver.takePersistableUriPermission(it, takeFlags)
            } catch (t: Throwable) {
                Log.w(TAG, "Failed taking persistable read URI permission", t)
            }
            val uriStr = it.toString()
            viewModel.repository.saveSelectedFolderUri(uriStr)
            viewModel.repository.saveScanMode("FOLDER")
            viewModel.selectedFolderUri = uriStr

            coroutineScope.launch {
                try {
                    val scannedBooks = viewModel.repository.scanFolderForAudiobooks(it)
                    viewModel.books = scannedBooks
                    viewModel.repository.saveBooksCache(scannedBooks)

                    if (scannedBooks.isNotEmpty()) {
                        val selectedBook = scannedBooks[0]
                        viewModel.selectBookMetadata(selectedBook, isDifferentBook = true)
                        val savedTrackIdx = viewModel.repository.getBookTrackIndex(selectedBook.id)
                        val savedPos = viewModel.repository.getBookPosition(selectedBook.id)
                        binder.playBook(selectedBook, savedTrackIdx, savedPos, false)
                        FablewoodWidgetProvider.updateAllWidgets(context)
                    }
                } catch (t: Throwable) {
                    Log.e(TAG, "Explicit folder scan threw an error", t)
                    Toast.makeText(context, context.getString(R.string.folder_scan_failed), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    BackHandler(enabled = viewModel.isCarModeActive || viewModel.isPlayerFullScreen) {
        if (viewModel.isCarModeActive) viewModel.isCarModeActive = false
        else viewModel.isPlayerFullScreen = false
    }

    LaunchedEffect(viewModel.playbackSpeed, viewModel.playbackPitch) {
        player.playbackParameters = PlaybackParameters(viewModel.playbackSpeed, viewModel.playbackPitch)
    }

    LaunchedEffect(viewModel.silenceTrimEnabled) {
        binder.sendCustomCommand(AudiobookPlaybackService.ACTION_SET_SILENCE_TRIM, viewModel.silenceTrimEnabled)
    }

    LaunchedEffect(viewModel.volumeBoostEnabled) {
        binder.sendCustomCommand(AudiobookPlaybackService.ACTION_SET_VOLUME_BOOST, viewModel.volumeBoostEnabled)
    }

    LaunchedEffect(viewModel.currentCoverArt, activeBook?.id) {
        val book = activeBook ?: return@LaunchedEffect
        val bmp = viewModel.currentCoverArt ?: return@LaunchedEffect
        val jpegBytes = viewModel.repository.bitmapToJpegBytes(bmp)
        binder.updatePlayingArtwork(book, jpegBytes)
        FablewoodWidgetProvider.updateAllWidgets(
            context = context,
            isPlayingOverride = viewModel.isPlaying,
            titleOverride = book.title,
            authorOverride = book.author
        )
    }

    LaunchedEffect(Unit) {
        val lastBookId = viewModel.repository.getLastBookId()
        val initialBook = viewModel.books.find { b -> b.id == lastBookId } ?: viewModel.books.firstOrNull()

        if (initialBook != null && viewModel.currentBookId.isEmpty()) {
            viewModel.selectBookMetadata(initialBook, isDifferentBook = true)
            if (player.mediaItemCount > 0) {
                viewModel.isPlaying = player.isPlaying
                viewModel.currentTrackIndex = player.currentMediaItemIndex
                viewModel.currentPosition = player.currentPosition.coerceAtLeast(0L)
                viewModel.duration = player.duration.coerceAtLeast(0L)
            } else {
                val savedTrackIdx = viewModel.repository.getBookTrackIndex(initialBook.id)
                val savedPos = viewModel.repository.getBookPosition(initialBook.id)
                val lastPauseTime = viewModel.repository.getPreferences().getLong("last_pause_time", 0L)
                val rewindPos = calculateSmartRewindPos(savedPos, lastPauseTime)
                binder.playBook(initialBook, savedTrackIdx, rewindPos, false)
            }
        }

        val scanMode = viewModel.repository.getScanMode()
        val savedUriStr = viewModel.repository.getSelectedFolderUri()

        if (scanMode == "FOLDER" && !savedUriStr.isNullOrEmpty()) {
            viewModel.selectedFolderUri = savedUriStr
            try {
                val folderUri = Uri.parse(savedUriStr)
                val scannedBooks = viewModel.repository.scanFolderForAudiobooks(folderUri)
                if (scannedBooks.isNotEmpty()) {
                    viewModel.books = scannedBooks
                    viewModel.repository.saveBooksCache(scannedBooks)
                    if (viewModel.currentBookId.isEmpty()) {
                        val firstBook = scannedBooks.find { b -> b.id == lastBookId } ?: scannedBooks.first()
                        viewModel.selectBookMetadata(firstBook, isDifferentBook = true)
                        val savedTrackIdx = viewModel.repository.getBookTrackIndex(firstBook.id)
                        val savedPos = viewModel.repository.getBookPosition(firstBook.id)
                        binder.playBook(firstBook, savedTrackIdx, savedPos, false)
                    }
                } else if (viewModel.books.isNotEmpty()) {
                    Log.w(TAG, "Launch folder refresh returned empty; preserving cached library")
                    Toast.makeText(context, context.getString(R.string.folder_refresh_failed), Toast.LENGTH_SHORT).show()
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Launch folder refresh failed; preserving cached library", t)
                if (viewModel.books.isNotEmpty()) {
                    Toast.makeText(context, context.getString(R.string.folder_refresh_failed), Toast.LENGTH_SHORT).show()
                }
            }
        } else {
            if (hasAudioReadPermission(context)) {
                runAutoDeviceScan(explicitUserAction = false)
            } else if (viewModel.books.isEmpty()) {
                triggerAutoScanWithPermission()
            }
        }
    }

    LaunchedEffect(viewModel.sleepTimerActive, viewModel.sleepEndAtChapter, viewModel.isPlaying, viewModel.sleepTimerVersion) {
        if (viewModel.sleepTimerActive && viewModel.isPlaying && !viewModel.sleepEndAtChapter) {
            while (viewModel.sleepTimerActive && !viewModel.sleepEndAtChapter && viewModel.sleepTimeRemaining > 0L) {
                delay(1000L)
                val next = (viewModel.sleepTimeRemaining - 1000L).coerceAtLeast(0L)
                viewModel.sleepTimeRemaining = next
                if (next <= 0L) {
                    player.pause()
                    viewModel.cancelSleepTimer()
                    break
                }
            }
        }
    }

    LaunchedEffect(viewModel.isPlaying) {
        var ticksSinceSave = 0
        while (viewModel.isPlaying) {
            viewModel.currentTrackIndex = player.currentMediaItemIndex
            viewModel.currentPosition = player.currentPosition.coerceAtLeast(0L)
            if (player.duration > 0L) {
                viewModel.duration = player.duration.coerceAtLeast(0L)
            }

            if (viewModel.sleepTimerActive && viewModel.sleepEndAtChapter) {
                val currChap = getCurrentChapter(viewModel.chaptersList, viewModel.currentPosition, viewModel.currentTrackIndex)
                val chapEndMs = if (currChap != null && currChap.durationMs > 0L) {
                    currChap.startMs + currChap.durationMs
                } else {
                    viewModel.duration
                }
                if (chapEndMs > 0L && viewModel.currentPosition >= chapEndMs - 1500L) {
                    player.pause()
                    viewModel.cancelSleepTimer()
                }
            }

            delay(1000L)
            viewModel.totalListenMs += 1000L
            ticksSinceSave++

            activeBook?.let { b ->
                if (ticksSinceSave >= 10) {
                    ticksSinceSave = 0
                    viewModel.repository.saveBookProgress(
                        b.id,
                        viewModel.currentTrackIndex,
                        viewModel.currentPosition,
                        viewModel.totalListenMs,
                        immediate = false
                    )
                }
                val isLastTrack = !player.hasNextMediaItem()
                if (isLastTrack && viewModel.duration > 0L && viewModel.currentPosition >= viewModel.duration - 2000L) {
                    if (viewModel.repository.markBookCompleted(b.id)) {
                        viewModel.completedBooksCount = viewModel.repository.loadCompletedBookIds().size
                        viewModel.progressRevision++
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
                            viewModel.saveProgressNow(b.id, player.currentMediaItemIndex, player.currentPosition)
                        }
                    }
                }
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) {
                    viewModel.duration = player.duration.coerceAtLeast(0L)
                }
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                viewModel.currentTrackIndex = player.currentMediaItemIndex
                viewModel.currentPosition = player.currentPosition.coerceAtLeast(0L)
                if (player.duration > 0L) {
                    viewModel.duration = player.duration.coerceAtLeast(0L)
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                Log.e(TAG, "ExoPlayer error", error)
                Toast.makeText(context, "Playback error: ${error.localizedMessage}", Toast.LENGTH_LONG).show()
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    val currentChapter = getCurrentChapter(viewModel.chaptersList, viewModel.currentPosition, viewModel.currentTrackIndex)
    val currentChapterIndex = getCurrentChapterIndex(viewModel.chaptersList, viewModel.currentPosition, viewModel.currentTrackIndex)

    val isMultiFileActive = activeBook != null && activeBook.tracks.size > 1
    val totalBookDurationMs = if (isMultiFileActive && viewModel.currentBookTrackDurations.isNotEmpty()) {
        val sum = bookDurationMs(viewModel.currentBookTrackDurations)
        if (sum > 0L) sum else viewModel.duration
    } else {
        viewModel.duration
    }
    val totalBookReadMs = if (isMultiFileActive && viewModel.currentBookTrackDurations.isNotEmpty()) {
        bookOffsetMs(viewModel.currentBookTrackDurations, viewModel.currentTrackIndex, viewModel.currentPosition)
    } else {
        viewModel.currentPosition
    }

    val togglePlayPause = {
        if (viewModel.isPlaying) {
            player.pause()
        } else {
            requestNotificationPermissionIfNeeded()
            activeBook?.let { viewModel.repository.touchBookLastPlayed(it.id) }
            player.play()
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(modifier = Modifier.width(300.dp)) {
                Spacer(Modifier.height(24.dp))
                Text(
                    text = stringResource(R.string.app_header_title),
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.padding(16.dp),
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                HorizontalDivider()
                NavigationDrawerItem(
                    icon = { Icon(Icons.Default.LibraryMusic, contentDescription = null) },
                    label = { Text(stringResource(R.string.scan_device_audiobooks)) },
                    selected = false,
                    onClick = {
                        coroutineScope.launch { drawerState.close() }
                        triggerAutoScanWithPermission()
                    }
                )
                NavigationDrawerItem(
                    icon = { Icon(Icons.Default.FolderOpen, contentDescription = null) },
                    label = { Text(stringResource(R.string.select_audiobook_folder)) },
                    selected = false,
                    onClick = {
                        coroutineScope.launch { drawerState.close() }
                        folderPicker.launch(null)
                    }
                )
                NavigationDrawerItem(
                    icon = { Icon(Icons.Default.ImageSearch, contentDescription = null) },
                    label = {
                        Text(
                            if (viewModel.isBatchFetchingCovers) "Finding Missing Covers..."
                            else "Auto-Find Missing Covers"
                        )
                    },
                    selected = false,
                    onClick = {
                        coroutineScope.launch { drawerState.close() }
                        Toast.makeText(context, "Searching online for missing covers...", Toast.LENGTH_SHORT).show()
                        viewModel.autoFillMissingCovers { count ->
                            Toast.makeText(context, "Updated $count book cover(s)", Toast.LENGTH_SHORT).show()
                        }
                    }
                )
                NavigationDrawerItem(
                    icon = { Icon(Icons.Default.GraphicEq, contentDescription = null) },
                    label = { Text(stringResource(R.string.audio_fx)) },
                    selected = false,
                    onClick = {
                        coroutineScope.launch { drawerState.close() }
                        showAudioFXDialog = true
                    }
                )
                NavigationDrawerItem(
                    icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                    label = { Text(stringResource(R.string.settings)) },
                    selected = false,
                    onClick = {
                        coroutineScope.launch { drawerState.close() }
                        showSettingsDialog = true
                    }
                )
                NavigationDrawerItem(
                    icon = { Icon(Icons.Default.Analytics, contentDescription = null) },
                    label = { Text(stringResource(R.string.listening_stats)) },
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
                chapterTitle = currentChapter?.title ?: getChapterTitle(viewModel.chaptersList, viewModel.currentPosition, viewModel.currentTrackIndex),
                isPlaying = viewModel.isPlaying,
                skipIntervalSeconds = viewModel.skipIntervalSeconds,
                onPlayPause = togglePlayPause,
                onQuickRewind = {
                    val newPos = (player.currentPosition - (viewModel.skipIntervalSeconds * 1000L)).coerceAtLeast(0L)
                    binder.seekAndPersist(player.currentMediaItemIndex, newPos)
                },
                onQuickForward = {
                    val maxDur = if (viewModel.duration > 0L) viewModel.duration else Long.MAX_VALUE
                    val newPos = (player.currentPosition + (viewModel.skipIntervalSeconds * 1000L)).coerceAtMost(maxDur)
                    binder.seekAndPersist(player.currentMediaItemIndex, newPos)
                },
                onAddBookmark = { showAddBookmarkPrompt = true },
                onExitCarMode = { viewModel.isCarModeActive = false }
            )
        } else if (viewModel.isPlayerFullScreen && activeBook != null) {
            NowPlayingScreen(
                book = activeBook,
                coverArt = viewModel.currentCoverArt?.asImageBitmap(),
                chapterTitle = currentChapter?.title ?: getChapterTitle(viewModel.chaptersList, viewModel.currentPosition, viewModel.currentTrackIndex),
                isPlaying = viewModel.isPlaying,
                currentPosition = viewModel.currentPosition,
                duration = viewModel.duration,
                bookPositionMs = totalBookReadMs,
                bookDurationMs = totalBookDurationMs,
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
                    viewModel.saveProgressNow(activeBook.id, player.currentMediaItemIndex, player.currentPosition)
                    viewModel.isPlayerFullScreen = false
                },
                onPlayPause = togglePlayPause,
                onSeekLive = { pos -> binder.seekLive(pos) },
                onSeekFinished = { pos -> binder.seekAndPersist(player.currentMediaItemIndex, pos) },
                onSkipPrevChapter = {
                    val chapters = viewModel.chaptersList
                    if (chapters.isNotEmpty()) {
                        val currChap = chapters.getOrNull(currentChapterIndex)
                        if (currChap != null && viewModel.currentPosition - currChap.startMs > 3000L) {
                            binder.seekAndPersist(currChap.trackIndex, currChap.startMs)
                        } else if (currentChapterIndex > 0) {
                            val prevChap = chapters[currentChapterIndex - 1]
                            binder.seekAndPersist(prevChap.trackIndex, prevChap.startMs)
                        } else {
                            binder.seekAndPersist(0, 0L)
                        }
                    } else {
                        player.seekToPreviousMediaItem()
                        viewModel.saveProgressNow(activeBook.id, player.currentMediaItemIndex, player.currentPosition)
                    }
                },
                onSkipNextChapter = {
                    val chapters = viewModel.chaptersList
                    if (chapters.isNotEmpty() && currentChapterIndex + 1 < chapters.size) {
                        val nextChap = chapters[currentChapterIndex + 1]
                        binder.seekAndPersist(nextChap.trackIndex, nextChap.startMs)
                    } else if (player.hasNextMediaItem()) {
                        player.seekToNextMediaItem()
                        viewModel.saveProgressNow(activeBook.id, player.currentMediaItemIndex, player.currentPosition)
                    }
                },
                onQuickRewind = {
                    val newPos = (player.currentPosition - (viewModel.skipIntervalSeconds * 1000L)).coerceAtLeast(0L)
                    binder.seekAndPersist(player.currentMediaItemIndex, newPos)
                },
                onQuickForward = {
                    val maxDur = if (viewModel.duration > 0L) viewModel.duration else Long.MAX_VALUE
                    val newPos = (player.currentPosition + (viewModel.skipIntervalSeconds * 1000L)).coerceAtMost(maxDur)
                    binder.seekAndPersist(player.currentMediaItemIndex, newPos)
                },
                onToggleSilenceTrim = {
                    viewModel.setSilenceTrim(!viewModel.silenceTrimEnabled)
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
                        title = { Text(stringResource(R.string.app_header_title), fontWeight = FontWeight.Bold) },
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
                                Icon(if (viewModel.viewMode == "GRID") Icons.Default.GridView else Icons.Default.ViewList, contentDescription = stringResource(R.string.view_mode))
                            }
                            IconButton(onClick = { showSortDialog = true }) {
                                Icon(Icons.Default.Sort, contentDescription = stringResource(R.string.sort_library_by))
                            }
                        }
                    )
                },
                bottomBar = {
                    if (activeBook != null) {
                        MiniPlayerBar(
                            book = activeBook,
                            coverArt = viewModel.currentCoverArt?.asImageBitmap(),
                            chapterTitle = currentChapter?.title ?: getChapterTitle(viewModel.chaptersList, viewModel.currentPosition, viewModel.currentTrackIndex),
                            isPlaying = viewModel.isPlaying,
                            onPlayPause = togglePlayPause,
                            onOpenPlayer = { viewModel.isPlayerFullScreen = true }
                        )
                    }
                }
            ) { innerPadding ->
                Box(modifier = Modifier.padding(innerPadding).fillMaxSize()) {
                    val displayedBooks = remember(
                        viewModel.books,
                        viewModel.searchQuery,
                        viewModel.sortBy,
                        viewModel.currentBookId,
                        viewModel.progressRevision
                    ) {
                        val filtered = viewModel.books.filter {
                            if (viewModel.searchQuery.isEmpty()) true
                            else it.title.contains(viewModel.searchQuery, ignoreCase = true) ||
                                it.author.contains(viewModel.searchQuery, ignoreCase = true)
                        }

                        val activeId = viewModel.currentBookId.ifEmpty {
                            viewModel.repository.getLastBookId() ?: ""
                        }

                        val baseSorted = when (viewModel.sortBy) {
                            "TITLE" -> filtered.sortedWith { a, b -> compareNatural(a.title, b.title) }
                            "AUTHOR" -> filtered.sortedWith { a, b -> compareNatural(a.author, b.author) }
                            else -> {
                                filtered.sortedWith { a, b ->
                                    val timeA = viewModel.repository.getBookLastPlayedTime(a.id)
                                    val timeB = viewModel.repository.getBookLastPlayedTime(b.id)
                                    if (timeA != timeB) {
                                        timeB.compareTo(timeA)
                                    } else {
                                        val posA = viewModel.repository.getBookPosition(a.id)
                                        val posB = viewModel.repository.getBookPosition(b.id)
                                        if (posA != posB) {
                                            posB.compareTo(posA)
                                        } else {
                                            0
                                        }
                                    }
                                }
                            }
                        }

                        if (activeId.isNotEmpty()) {
                            val (pinned, rest) = baseSorted.partition { it.id == activeId }
                            pinned + rest
                        } else {
                            baseSorted
                        }
                    }

                    val activeProgressFraction = if (totalBookDurationMs > 0L) {
                        (totalBookReadMs.toFloat() / totalBookDurationMs).coerceIn(0f, 1f)
                    } else 0f

                    val activeSubtitleText = when {
                        viewModel.chaptersList.size > 1 && totalBookDurationMs > 0L ->
                            "${viewModel.chaptersList.size} chapters • ${formatTime(totalBookDurationMs)}"
                        totalBookDurationMs > 0L -> formatTime(totalBookDurationMs)
                        else -> ""
                    }

                    Column(modifier = Modifier.fillMaxSize()) {
                        if (viewModel.isSearching || viewModel.searchQuery.isNotEmpty()) {
                            OutlinedTextField(
                                value = viewModel.searchQuery,
                                onValueChange = { viewModel.searchQuery = it },
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                                placeholder = { Text(stringResource(R.string.search_books_hint)) },
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
                            activeBookProgress = activeProgressFraction,
                            activeBookSubtitle = activeSubtitleText,
                            hasSelectedFolder = viewModel.selectedFolderUri.isNotEmpty(),
                            viewMode = viewModel.viewMode,
                            viewModel = viewModel,
                            onAutoScanDevice = { triggerAutoScanWithPermission() },
                            onChooseFolder = { folderPicker.launch(null) },
                            onSelectBook = { book ->
                                viewModel.repository.touchBookLastPlayed(book.id)
                                viewModel.progressRevision++
                                if (book.id == viewModel.currentBookId && player.mediaItemCount > 0) {
                                    viewModel.isPlayerFullScreen = true
                                } else {
                                    val snapshotPos = player.currentPosition
                                    val snapshotIdx = player.currentMediaItemIndex
                                    activeBook?.let { curr ->
                                        viewModel.saveProgressNow(curr.id, snapshotIdx, snapshotPos)
                                    }
                                    player.playWhenReady = false
                                    player.pause()

                                    viewModel.selectBookMetadata(book, isDifferentBook = true)
                                    val savedTrackIdx = viewModel.repository.getBookTrackIndex(book.id)
                                    val savedPos = viewModel.repository.getBookPosition(book.id)
                                    binder.playBook(book, savedTrackIdx, savedPos, false)
                                    viewModel.isPlayerFullScreen = true
                                }
                            },
                            onLongPressBook = { book ->
                                targetBookForCoverAction = book
                                val initialCleanQuery = cleanAudiobookSearchQuery(book.title, book.author)
                                liveCoverSearchQuery = initialCleanQuery
                                showCoverOptionsDialog = true
                                viewModel.searchOnlineCoverCandidates(initialCleanQuery)
                            }
                        )
                    }
                }
            }
        }
    }

    if (showCoverOptionsDialog && targetBookForCoverAction != null) {
        val targetBook = targetBookForCoverAction!!
        AlertDialog(
            onDismissRequest = { showCoverOptionsDialog = false },
            title = { Text(stringResource(R.string.cover_art_options)) },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 440.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedTextField(
                        value = liveCoverSearchQuery,
                        onValueChange = { liveCoverSearchQuery = it },
                        label = { Text("Search book title or author") },
                        singleLine = true,
                        trailingIcon = {
                            IconButton(onClick = { viewModel.searchOnlineCoverCandidates(liveCoverSearchQuery) }) {
                                Icon(Icons.Default.Search, contentDescription = "Search Covers", tint = MaterialTheme.colorScheme.primary)
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                showCoverOptionsDialog = false
                                customCoverLauncher.launch("image/*")
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.PhotoLibrary, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Device Image", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }

                        OutlinedButton(
                            onClick = {
                                try {
                                    val encoded = URLEncoder.encode("$liveCoverSearchQuery audiobook cover", "UTF-8")
                                    val webIntent = Intent(
                                        Intent.ACTION_VIEW,
                                        Uri.parse("https://www.google.com/search?tbm=isch&q=$encoded")
                                    )
                                    context.startActivity(webIntent)
                                } catch (t: Throwable) {
                                    Log.w(TAG, "Failed launching web image search", t)
                                }
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Public, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Web Search", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }

                    HorizontalDivider()

                    if (viewModel.isSearchingCovers) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 28.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.height(10.dp))
                                Text(
                                    text = "Searching Apple Books, Google Books & Open Library...",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    } else if (viewModel.coverSearchCandidates.isEmpty()) {
                        Text(
                            text = "No covers found for \"$liveCoverSearchQuery\". Edit the title above and tap the search icon, or use Web Search.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp)
                        )
                    } else {
                        Text(
                            text = "Tap a cover to apply:",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )

                        viewModel.coverSearchCandidates.chunked(2).forEach { rowItems ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                rowItems.forEach { candidate ->
                                    Card(
                                        modifier = Modifier
                                            .weight(1f)
                                            .clickable {
                                                showCoverOptionsDialog = false
                                                Toast.makeText(context, "Applying high-res cover...", Toast.LENGTH_SHORT).show()
                                                viewModel.selectOnlineCoverCandidate(targetBook.id, candidate) {
                                                    Toast.makeText(context, "Cover updated!", Toast.LENGTH_SHORT).show()
                                                }
                                            },
                                        shape = RoundedCornerShape(12.dp),
                                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                                    ) {
                                        Column(
                                            modifier = Modifier.padding(8.dp),
                                            horizontalAlignment = Alignment.CenterHorizontally
                                        ) {
                                            Image(
                                                bitmap = candidate.previewBitmap.asImageBitmap(),
                                                contentDescription = candidate.title,
                                                contentScale = ContentScale.Crop,
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .aspectRatio(1f)
                                                    .clip(RoundedCornerShape(8.dp))
                                            )
                                            Spacer(Modifier.height(6.dp))
                                            Text(
                                                text = candidate.title,
                                                style = MaterialTheme.typography.bodySmall,
                                                fontWeight = FontWeight.Bold,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Text(
                                                text = candidate.source,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.primary,
                                                maxLines = 1
                                            )
                                        }
                                    }
                                }
                                if (rowItems.size == 1) {
                                    Spacer(modifier = Modifier.weight(1f))
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showCoverOptionsDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    if (showAddBookmarkPrompt) {
        AlertDialog(
            onDismissRequest = { showAddBookmarkPrompt = false },
            title = { Text(stringResource(R.string.add_bookmark)) },
            text = {
                OutlinedTextField(
                    value = newBookmarkNoteText,
                    onValueChange = { newBookmarkNoteText = it },
                    label = { Text(stringResource(R.string.bookmark_note_label)) },
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val trackIdx = player.currentMediaItemIndex.coerceAtLeast(0)
                    val chapterName = getChapterTitle(viewModel.chaptersList, viewModel.currentPosition, trackIdx)
                    viewModel.addBookmark(trackIdx, viewModel.currentPosition, chapterName, newBookmarkNoteText)
                    showAddBookmarkPrompt = false
                    newBookmarkNoteText = ""
                    Toast.makeText(context, "Bookmark Saved", Toast.LENGTH_SHORT).show()
                }) { Text(stringResource(R.string.save)) }
            },
            dismissButton = {
                TextButton(onClick = { showAddBookmarkPrompt = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    if (showViewDialog) {
        AlertDialog(
            onDismissRequest = { showViewDialog = false },
            title = { Text(stringResource(R.string.view_mode)) },
            text = {
                Column {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().clickable {
                            viewModel.updateViewMode("GRID")
                            showViewDialog = false
                        }.padding(vertical = 12.dp)
                    ) {
                        RadioButton(selected = viewModel.viewMode == "GRID", onClick = null)
                        Spacer(Modifier.width(12.dp))
                        Text(stringResource(R.string.grid_view))
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().clickable {
                            viewModel.updateViewMode("LIST")
                            showViewDialog = false
                        }.padding(vertical = 12.dp)
                    ) {
                        RadioButton(selected = viewModel.viewMode == "LIST", onClick = null)
                        Spacer(Modifier.width(12.dp))
                        Text(stringResource(R.string.list_view))
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showViewDialog = false }) { Text(stringResource(R.string.close)) } }
        )
    }

    if (showSortDialog) {
        AlertDialog(
            onDismissRequest = { showSortDialog = false },
            title = { Text(stringResource(R.string.sort_library_by)) },
            text = {
                Column {
                    val sortOptions = listOf(
                        "RECENT" to stringResource(R.string.sort_recent),
                        "TITLE" to stringResource(R.string.sort_title),
                        "AUTHOR" to stringResource(R.string.sort_author)
                    )
                    sortOptions.forEach { (key, label) ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().clickable {
                                viewModel.updateSortBy(key)
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
            confirmButton = { TextButton(onClick = { showSortDialog = false }) { Text(stringResource(R.string.close)) } }
        )
    }

    if (showAudioFXDialog) {
        AlertDialog(
            onDismissRequest = { showAudioFXDialog = false },
            title = { Text(stringResource(R.string.audio_fx)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.volume_boost_title), fontWeight = FontWeight.Bold)
                            Text(
                                stringResource(R.string.volume_boost_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = viewModel.volumeBoostEnabled,
                            onCheckedChange = { enabled -> viewModel.setVolumeBoost(enabled) }
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.trim_silence_title), fontWeight = FontWeight.Bold)
                            Text(
                                stringResource(R.string.trim_silence_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = viewModel.silenceTrimEnabled,
                            onCheckedChange = { enabled -> viewModel.setSilenceTrim(enabled) }
                        )
                    }

                    HorizontalDivider()

                    Column {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(stringResource(R.string.voice_pitch), fontWeight = FontWeight.Bold)
                            Text(String.format(Locale.US, "%.2fx", viewModel.playbackPitch), color = MaterialTheme.colorScheme.primary)
                        }
                        Slider(
                            value = viewModel.playbackPitch,
                            onValueChange = { viewModel.updatePitch(round(it * 20f) / 20f) },
                            valueRange = 0.75f..1.25f
                        )
                    }

                    Column {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(stringResource(R.string.app_volume), fontWeight = FontWeight.Bold)
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
                    viewModel.updatePitch(1.0f)
                    viewModel.playerVolume = 1.0f
                    player.volume = 1.0f
                }) { Text(stringResource(R.string.reset_pitch_vol)) }
            },
            dismissButton = { TextButton(onClick = { showAudioFXDialog = false }) { Text(stringResource(R.string.done)) } }
        )
    }

    if (showSettingsDialog) {
        AlertDialog(
            onDismissRequest = { showSettingsDialog = false },
            title = { Text(stringResource(R.string.settings)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Dynamic Cover Theme", fontWeight = FontWeight.Bold)
                            Text(
                                "Match UI colors to the active audiobook's cover art",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = dynamicThemeEnabled,
                            onCheckedChange = { onToggleDynamicTheme(it) }
                        )
                    }

                    HorizontalDivider()

                    Column {
                        Text(stringResource(R.string.skip_interval_label), fontWeight = FontWeight.Bold)
                        Slider(
                            value = viewModel.skipIntervalSeconds.toFloat(),
                            onValueChange = {
                                val newVal = round(it).toInt()
                                viewModel.updateSkipInterval(newVal)
                            },
                            valueRange = 5f..60f,
                            steps = 10
                        )
                        Text("${viewModel.skipIntervalSeconds}s", modifier = Modifier.align(Alignment.End))
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showSettingsDialog = false }) { Text(stringResource(R.string.close)) } }
        )
    }

    if (showAnalyticsDialog) {
        AlertDialog(
            onDismissRequest = { showAnalyticsDialog = false },
            title = { Text(stringResource(R.string.listening_stats)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Total Listen Time: ${formatTime(viewModel.totalListenMs)}")
                    Text("Completed Books: ${viewModel.completedBooksCount}")
                    Text("Books in Library: ${viewModel.books.size}")
                }
            },
            confirmButton = { TextButton(onClick = { showAnalyticsDialog = false }) { Text(stringResource(R.string.close)) } }
        )
    }

    if (showSpeedDialog) {
        AlertDialog(
            onDismissRequest = { showSpeedDialog = false },
            title = { Text(stringResource(R.string.playback_speed)) },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = String.format(Locale.US, "%.2fx", viewModel.playbackSpeed),
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )
                    Slider(
                        value = viewModel.playbackSpeed,
                        onValueChange = {
                            val stepped = (round(it * 20f) / 20f).coerceIn(0.5f, 3.0f)
                            viewModel.updateSpeed(stepped)
                        },
                        valueRange = 0.5f..3.0f
                    )
                    val presets = listOf(1.0f, 1.25f, 1.5f, 2.0f, 2.5f, 3.0f)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        presets.take(3).forEach { preset ->
                            TextButton(onClick = { viewModel.updateSpeed(preset) }) {
                                Text(String.format(Locale.US, "%.2gx", preset))
                            }
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        presets.drop(3).forEach { preset ->
                            TextButton(onClick = { viewModel.updateSpeed(preset) }) {
                                Text(String.format(Locale.US, "%.2gx", preset))
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showSpeedDialog = false }) { Text(stringResource(R.string.close)) } }
        )
    }

    if (showChaptersDialog) {
        AlertDialog(
            onDismissRequest = { showChaptersDialog = false },
            title = { Text("Chapters (${viewModel.chaptersList.size})") },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    viewModel.chaptersList.forEachIndexed { idx, chap ->
                        val isCurrent = idx == currentChapterIndex
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().clickable {
                                binder.seekAndPersist(chap.trackIndex, chap.startMs)
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
                                Text(
                                    text = timeSubtitle,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                    if (viewModel.chaptersList.isEmpty()) {
                        Text(stringResource(R.string.no_chapters_found), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showChaptersDialog = false }) { Text(stringResource(R.string.close)) } }
        )
    }

    if (showBookmarksDialog) {
        AlertDialog(
            onDismissRequest = { showBookmarksDialog = false },
            title = { Text(stringResource(R.string.bookmarks)) },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    viewModel.currentBookBookmarks.forEach { bm ->
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable {
                                binder.seekAndPersist(bm.trackIndex, bm.positionMs)
                                showBookmarksDialog = false
                            }.padding(vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(bm.chapterTitle, fontWeight = FontWeight.Bold)
                                Text(formatTime(bm.positionMs), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                                if (bm.note.isNotEmpty()) {
                                    Text(bm.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            IconButton(onClick = { viewModel.deleteBookmark(bm.id) }) {
                                Icon(Icons.Default.Delete, contentDescription = "Delete Bookmark", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    if (viewModel.currentBookBookmarks.isEmpty()) {
                        Text(stringResource(R.string.no_bookmarks_yet), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showBookmarksDialog = false
                    showAddBookmarkPrompt = true
                }) { Text(stringResource(R.string.add_bookmark_btn)) }
            },
            dismissButton = { TextButton(onClick = { showBookmarksDialog = false }) { Text(stringResource(R.string.close)) } }
        )
    }

    if (showSleepMenu) {
        AlertDialog(
            onDismissRequest = { showSleepMenu = false },
            title = { Text(stringResource(R.string.sleep_timer)) },
            text = {
                Column {
                    listOf(15, 30, 45, 60).forEach { mins ->
                        TextButton(
                            onClick = {
                                viewModel.startTimedSleep(mins)
                                showSleepMenu = false
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("$mins Minutes") }
                    }
                    TextButton(
                        onClick = {
                            viewModel.startEndOfChapterSleep()
                            showSleepMenu = false
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(stringResource(R.string.end_of_chapter)) }
                    if (viewModel.sleepTimerActive) {
                        TextButton(
                            onClick = {
                                viewModel.cancelSleepTimer()
                                showSleepMenu = false
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(stringResource(R.string.cancel_timer), color = MaterialTheme.colorScheme.error) }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showSleepMenu = false }) { Text(stringResource(R.string.close)) } }
        )
    }
}

fun calculateSmartRewindPos(
    savedPos: Long,
    lastPauseTime: Long,
    nowMs: Long = System.currentTimeMillis()
): Long {
    if (lastPauseTime <= 0L || savedPos <= 0L) return savedPos.coerceAtLeast(0L)
    val elapsedMs = (nowMs - lastPauseTime).coerceAtLeast(0L)
    val hoursSincePause = elapsedMs / (1000L * 60L * 60L)
    val rewindMs = when {
        hoursSincePause < 2L -> 0L
        hoursSincePause < 24L -> 15_000L
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
