package com.example.audiobook

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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

fun formatTime(ms: Long): String {
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

@Composable
fun ContrastThemeIcon(
    tint: Color = MaterialTheme.colorScheme.primary,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val strokeWidth = 2.dp.toPx()
        val radius = (size.minDimension / 2f) - strokeWidth
        val center = Offset(size.width / 2f, size.height / 2f)
        drawCircle(color = tint, radius = radius, center = center, style = Stroke(width = strokeWidth))
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
        modifier = Modifier.fillMaxSize().background(Color(0xFF08120E)).padding(24.dp),
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
                Icon(Icons.Default.Replay30, contentDescription = "Rewind", tint = Color.White, modifier = Modifier.size(40.dp))
            }

            IconButton(onClick = onPlayPause, modifier = Modifier.size(128.dp)) {
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
                Icon(Icons.Default.Forward30, contentDescription = "Forward", tint = Color.White, modifier = Modifier.size(40.dp))
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
    viewModel: LibraryViewModel,
    onSelectBook: (Audiobook) -> Unit,
    onChangeCover: (Audiobook) -> Unit
) {
    if (books.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(64.dp), tint = Color.DarkGray)
                Spacer(Modifier.height(16.dp))
                Text("No audiobooks found.", style = MaterialTheme.typography.bodyLarge, color = Color.Gray)
            }
        }
    } else if (viewMode == "GRID") {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 150.dp),
            contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            items(count = books.size, key = { idx -> books[idx].id }) { index ->
                BookCardWrapper(
                    book = books[index],
                    activeBookId = activeBookId,
                    duration = duration,
                    currentPosition = currentPosition,
                    viewMode = "GRID",
                    viewModel = viewModel,
                    onSelectBook = onSelectBook,
                    onChangeCover = onChangeCover
                )
            }
        }
    } else {
        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(count = books.size, key = { idx -> books[idx].id }) { index ->
                BookCardWrapper(
                    book = books[index],
                    activeBookId = activeBookId,
                    duration = duration,
                    currentPosition = currentPosition,
                    viewMode = "LIST",
                    viewModel = viewModel,
                    onSelectBook = onSelectBook,
                    onChangeCover = onChangeCover
                )
            }
        }
    }
}

@Composable
fun BookCardWrapper(
    book: Audiobook,
    activeBookId: String,
    duration: Long,
    currentPosition: Long,
    viewMode: String,
    viewModel: LibraryViewModel,
    onSelectBook: (Audiobook) -> Unit,
    onChangeCover: (Audiobook) -> Unit
) {
    val context = LocalContext.current
    val isSelected = book.id == activeBookId

    var cardArt by remember(book.id) { mutableStateOf<ImageBitmap?>(null) }
    var cardProgress by remember(book.id, isSelected, currentPosition, duration) { mutableStateOf(0f) }
    var subtitleText by remember(book.id, isSelected, duration) { mutableStateOf("") }

    LaunchedEffect(book.id, isSelected, currentPosition, duration) {
        if (cardArt == null) {
            val bmp = viewModel.repository.getOrFetchCoverArt(book)
            cardArt = bmp?.asImageBitmap()
        }

        if (isSelected && duration > 0L) {
            // Actively Playing Book
            cardProgress = (currentPosition.toFloat() / duration).coerceIn(0f, 1f)
            subtitleText = if (viewModel.chaptersList.size > 1) {
                "${viewModel.chaptersList.size} chapters • ${formatTime(duration)}"
            } else {
                formatTime(duration)
            }
        } else {
            // Background / Saved Books
            val isCompleted = viewModel.repository.loadCompletedBookIds().contains(book.id)
            if (isCompleted) {
                cardProgress = 1f
                subtitleText = "Completed"
            } else {
                val savedPos = viewModel.repository.getBookPosition(book.id)
                val savedTrackIdx = viewModel.repository.getBookTrackIndex(book.id)

                if (savedPos > 0L || savedTrackIdx > 0) {
                    val prefs = viewModel.repository.getPreferences()
                    var bookDur = prefs.getLong("book_dur_${book.id}", 0L)

                    // If it's a single m4b file and we don't know the length yet, asynchronously fetch it
                    if (bookDur == 0L && book.tracks.size == 1) {
                        withContext(Dispatchers.IO) {
                            try {
                                val retriever = android.media.MediaMetadataRetriever()
                                retriever.setDataSource(context, book.tracks.first().uri)
                                bookDur = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                                retriever.release()
                                if (bookDur > 0L) {
                                    prefs.edit().putLong("book_dur_${book.id}", bookDur).apply()
                                }
                            } catch (_: Throwable) {}
                        }
                    }

                    if (book.tracks.size > 1) {
                        val trackRatio = savedTrackIdx.toFloat() / book.tracks.size
                        cardProgress = trackRatio.coerceIn(0.01f, 0.99f)
                        subtitleText = "Part ${savedTrackIdx + 1} of ${book.tracks.size}"
                    } else if (bookDur > 0L) {
                        cardProgress = (savedPos.toFloat() / bookDur).coerceIn(0.01f, 0.99f)
                        val remaining = (bookDur - savedPos).coerceAtLeast(0L)
                        subtitleText = "${formatTime(remaining)} left"
                    } else {
                        cardProgress = 0.05f
                        subtitleText = "In Progress"
                    }
                } else {
                    cardProgress = 0f
                    subtitleText = if (book.tracks.size == 1 && book.tracks.first().isM4b) "M4B Audiobook" else "${book.tracks.size} tracks"
                }
            }
        }
    }

    if (viewMode == "GRID") {
        GridBookCard(
            book = book,
            coverArt = cardArt,
            progress = cardProgress,
            subtitleText = subtitleText,
            isSelected = isSelected,
            onClick = { onSelectBook(book) },
            onLongClick = { onChangeCover(book) }
        )
    } else {
        ListBookCard(
            book = book,
            coverArt = cardArt,
            progress = cardProgress,
            subtitleText = subtitleText,
            isSelected = isSelected,
            onClick = { onSelectBook(book) },
            onLongClick = { onChangeCover(book) }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GridBookCard(
    book: Audiobook,
    coverArt: ImageBitmap?,
    progress: Float,
    subtitleText: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLongClick)
    ) {
        Box(
            modifier = Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            if (coverArt != null) {
                Image(bitmap = coverArt, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else {
                Icon(Icons.Default.Book, contentDescription = null, modifier = Modifier.size(48.dp), tint = Color.Gray)
            }

            Box(
                modifier = Modifier.align(Alignment.BottomStart).padding(8.dp).clip(RoundedCornerShape(6.dp)).background(Color.Black.copy(alpha = 0.6f)).padding(4.dp)
            ) {
                Icon(Icons.Default.Headphones, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
            }

            if (progress > 0f) {
                Box(
                    modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth(progress).height(4.dp).background(MaterialTheme.colorScheme.tertiary)
                )
            }
        }

        Spacer(Modifier.height(8.dp))
        Text(book.title, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(if (book.series.isNotEmpty()) "${book.series} • ${book.author}" else book.author, style = MaterialTheme.typography.bodySmall, color = Color.Gray, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (subtitleText.isNotEmpty()) {
            Text(
                text = subtitleText,
                style = MaterialTheme.typography.bodySmall,
                color = if (progress == 1f || isSelected) MaterialTheme.colorScheme.primary else Color.Gray,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ListBookCard(
    book: Audiobook,
    coverArt: ImageBitmap?,
    progress: Float,
    subtitleText: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLongClick),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface
        ),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier.size(64.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center
            ) {
                if (coverArt != null) {
                    Image(bitmap = coverArt, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                } else {
                    Icon(Icons.Default.Book, contentDescription = null, tint = Color.Black)
                }

                if (progress > 0f) {
                    Box(
                        modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth(progress).height(4.dp).background(MaterialTheme.colorScheme.tertiary)
                    )
                }
            }

            Spacer(Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(book.title, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(if (book.series.isNotEmpty()) "${book.series} • ${book.author}" else book.author, style = MaterialTheme.typography.bodySmall, color = Color.Gray, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (subtitleText.isNotEmpty()) {
                    Text(
                        text = subtitleText,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (progress == 1f || isSelected) MaterialTheme.colorScheme.primary else Color.Gray,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Icon(Icons.Default.Headphones, contentDescription = null, tint = if (isSelected) MaterialTheme.colorScheme.primary else Color.DarkGray)
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
    onVolumeChange: (Float) -> Unit,
    chapterStartMs: Long = 0L,
    chapterDurationMs: Long = 0L
) {
    val percentRead = if (duration > 0L) ((currentPosition.toDouble() / duration.toDouble()) * 100).toInt() else 0

    val useChapterScrubber = chapterDurationMs > 0L
    val sliderPositionMs = if (useChapterScrubber) {
        (currentPosition - chapterStartMs).coerceIn(0L, chapterDurationMs)
    } else {
        currentPosition.coerceAtLeast(0L)
    }
    val sliderDurationMs = if (useChapterScrubber) {
        chapterDurationMs.coerceAtLeast(1L)
    } else {
        duration.coerceAtLeast(1L)
    }

    Column(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp),
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
            modifier = Modifier.size(220.dp).clip(RoundedCornerShape(24.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            if (coverArt != null) {
                Image(bitmap = coverArt, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else {
                Icon(Icons.Default.Headphones, contentDescription = null, modifier = Modifier.size(96.dp), tint = MaterialTheme.colorScheme.primary)
            }
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Total Book: ${formatTime(duration)}", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
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
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.clickable { onOpenChapters() }
                )

                Spacer(Modifier.height(6.dp))

                Slider(
                    value = sliderPositionMs.toFloat(),
                    onValueChange = { newSliderVal ->
                        val targetMs = if (useChapterScrubber) {
                            chapterStartMs + newSliderVal.toLong()
                        } else {
                            newSliderVal.toLong()
                        }
                        onSeek(targetMs)
                    },
                    valueRange = 0f..sliderDurationMs.toFloat(),
                    colors = SliderDefaults.colors(
                        thumbColor = MaterialTheme.colorScheme.primary,
                        activeTrackColor = MaterialTheme.colorScheme.primary,
                        inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(formatTime(sliderPositionMs), style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                    Text("-${formatTime((sliderDurationMs - sliderPositionMs).coerceAtLeast(0L))}", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
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
                            Text(text = "$skipIntervalSeconds", fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 2.dp))
                        }
                    }

                    IconButton(onClick = onPlayPause, modifier = Modifier.size(60.dp)) {
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
                            Text(text = "$skipIntervalSeconds", fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 2.dp))
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
                        modifier = Modifier.clip(CircleShape).background(if (silenceTrimEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
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
                        ContrastThemeIcon(tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                    }

                    IconButton(onClick = onOpenAudioFX) {
                        Icon(Icons.Default.GraphicEq, contentDescription = "Equalizer", tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
}
// END OF FILE