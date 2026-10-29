package com.example.audiobook

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale
import java.util.concurrent.TimeUnit

fun formatTime(ms: Long): String {
    val totalSeconds = ms.coerceAtLeast(0L) / 1000L
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    val hours = minutes / 60L
    val remainingMinutes = minutes % 60L

    return if (hours > 0L) {
        String.format(Locale.US, "%d:%02d:%02d", hours, remainingMinutes, seconds)
    } else {
        String.format(Locale.US, "%02d:%02d", remainingMinutes, seconds)
    }
}

@Composable
fun ShelfModeTogglePill(
    shelfMode: String,
    onSelectMode: (String) -> Unit
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f), RoundedCornerShape(50))
            .padding(3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val isAudio = shelfMode == "AUDIO"
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(if (isAudio) MaterialTheme.colorScheme.primary else Color.Transparent)
                .clickable { onSelectMode("AUDIO") }
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Headphones,
                contentDescription = null,
                tint = if (isAudio) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp)
            )
            Spacer(Modifier.width(5.dp))
            Text(
                text = "Audio",
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = if (isAudio) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(if (!isAudio) MaterialTheme.colorScheme.primary else Color.Transparent)
                .clickable { onSelectMode("EBOOK") }
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.MenuBook,
                contentDescription = null,
                tint = if (!isAudio) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp)
            )
            Spacer(Modifier.width(5.dp))
            Text(
                text = "eBooks",
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = if (!isAudio) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun FoldableSpineDivider() {
    Box(
        modifier = Modifier
            .fillMaxHeight()
            .width(10.dp)
            .background(
                Brush.horizontalGradient(
                    colors = listOf(
                        Color.Black.copy(alpha = 0.35f),
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.28f),
                        Color.Black.copy(alpha = 0.35f)
                    )
                )
            )
    )
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
fun MiniPlayerBar(
    book: Audiobook,
    coverArt: ImageBitmap?,
    chapterTitle: String,
    isPlaying: Boolean,
    onPlayPause: () -> Unit,
    onOpenPlayer: () -> Unit
) {
    Surface(
        tonalElevation = 6.dp,
        shadowElevation = 8.dp,
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.navigationBars)
            .clickable { onOpenPlayer() }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surface),
                contentAlignment = Alignment.Center
            ) {
                if (coverArt != null) {
                    Image(
                        bitmap = coverArt,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.Headphones,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = book.title,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = chapterTitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            IconButton(onClick = onPlayPause) {
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

@Composable
fun CarModeScreen(
    book: Audiobook,
    chapterTitle: String,
    isPlaying: Boolean,
    skipIntervalSeconds: Int,
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
            .windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.displayCutout))
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
                Text(stringResource(R.string.car_mode), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Color.White)
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
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Replay, contentDescription = "Rewind", tint = Color.White, modifier = Modifier.size(48.dp))
                    Text(
                        text = "$skipIntervalSeconds",
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 3.dp)
                    )
                }
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
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.RotateRight, contentDescription = "Forward", tint = Color.White, modifier = Modifier.size(48.dp))
                    Text(
                        text = "$skipIntervalSeconds",
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 3.dp)
                    )
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
            Text(stringResource(R.string.drop_bookmark_pin), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Color.White)
        }
    }
}

@Composable
fun BookshelfContent(
    books: List<Audiobook>,
    activeBookId: String,
    activeBookProgress: Float,
    activeBookSubtitle: String,
    hasSelectedFolder: Boolean,
    viewMode: String,
    viewModel: LibraryViewModel,
    onAutoScanDevice: () -> Unit,
    onChooseFolder: () -> Unit,
    onSelectBook: (Audiobook) -> Unit,
    onLongPressBook: (Audiobook) -> Unit
) {
    if (books.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    imageVector = Icons.Default.LibraryMusic,
                    contentDescription = null,
                    modifier = Modifier.size(64.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    text = if (!hasSelectedFolder) {
                        stringResource(R.string.no_folder_selected)
                    } else {
                        stringResource(R.string.no_supported_audio_files)
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(20.dp))
                Button(
                    onClick = onAutoScanDevice,
                    modifier = Modifier.fillMaxWidth(0.85f)
                ) {
                    Icon(Icons.Default.LibraryMusic, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.scan_device_audiobooks))
                }
                Spacer(Modifier.height(12.dp))
                OutlinedButton(
                    onClick = onChooseFolder,
                    modifier = Modifier.fillMaxWidth(0.85f)
                ) {
                    Icon(Icons.Default.FolderOpen, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.choose_audiobook_folder))
                }
            }
        }
    } else if (viewMode == "GRID") {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 145.dp),
            contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            items(count = books.size, key = { idx -> books[idx].id }) { index ->
                val book = books[index]
                val isSelected = book.id == activeBookId
                BookCardWrapper(
                    book = book,
                    isSelected = isSelected,
                    liveProgress = if (isSelected) activeBookProgress else null,
                    liveSubtitle = if (isSelected) activeBookSubtitle else null,
                    viewMode = "GRID",
                    viewModel = viewModel,
                    onSelectBook = onSelectBook,
                    onLongPressBook = onLongPressBook
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
                BookCardWrapper(
                    book = book,
                    isSelected = isSelected,
                    liveProgress = if (isSelected) activeBookProgress else null,
                    liveSubtitle = if (isSelected) activeBookSubtitle else null,
                    viewMode = "LIST",
                    viewModel = viewModel,
                    onSelectBook = onSelectBook,
                    onLongPressBook = onLongPressBook
                )
            }
        }
    }
}

@Composable
fun EbookShelfContent(
    ebooks: List<Ebook>,
    activeEbookId: String,
    viewMode: String,
    viewModel: LibraryViewModel,
    onScanDeviceEbooks: () -> Unit,
    onImportEpubFiles: () -> Unit,
    onSelectEbook: (Ebook) -> Unit,
    onLongPressEbook: (Ebook) -> Unit
) {
    if (ebooks.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    imageVector = Icons.Default.MenuBook,
                    contentDescription = null,
                    modifier = Modifier.size(64.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    text = "Scan your Downloads & Books folders for .epub and .pdf files, or import files directly.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(20.dp))
                Button(
                    onClick = onScanDeviceEbooks,
                    modifier = Modifier.fillMaxWidth(0.85f)
                ) {
                    Icon(Icons.Default.Search, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Scan Device (Downloads & Books)")
                }
                Spacer(Modifier.height(12.dp))
                OutlinedButton(
                    onClick = onImportEpubFiles,
                    modifier = Modifier.fillMaxWidth(0.85f)
                ) {
                    Icon(Icons.Default.FileOpen, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Import .epub / .pdf File(s)")
                }
            }
        }
    } else if (viewMode == "GRID") {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 145.dp),
            contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            items(count = ebooks.size, key = { idx -> ebooks[idx].id }) { index ->
                val ebook = ebooks[index]
                EbookCardWrapper(
                    ebook = ebook,
                    isSelected = ebook.id == activeEbookId,
                    viewMode = "GRID",
                    viewModel = viewModel,
                    onSelectEbook = onSelectEbook,
                    onLongPressEbook = onLongPressEbook
                )
            }
        }
    } else {
        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(count = ebooks.size, key = { idx -> ebooks[idx].id }) { index ->
                val ebook = ebooks[index]
                EbookCardWrapper(
                    ebook = ebook,
                    isSelected = ebook.id == activeEbookId,
                    viewMode = "LIST",
                    viewModel = viewModel,
                    onSelectEbook = onSelectEbook,
                    onLongPressEbook = onLongPressEbook
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun EbookCardWrapper(
    ebook: Ebook,
    isSelected: Boolean,
    viewMode: String,
    viewModel: LibraryViewModel,
    onSelectEbook: (Ebook) -> Unit,
    onLongPressEbook: (Ebook) -> Unit
) {
    var cardArt by remember(ebook.id, viewModel.coverRevision) { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(ebook.id, viewModel.coverRevision) {
        val bmp = viewModel.repository.getOrFetchEbookCoverArt(ebook)
        cardArt = bmp?.asImageBitmap()
    }

    val totalChaps = remember(ebook.id, viewModel.progressRevision) {
        viewModel.repository.getEbookTotalChapters(ebook.id)
    }
    val currChap = remember(ebook.id, viewModel.progressRevision) {
        viewModel.repository.getEbookChapterIndex(ebook.id)
    }
    val progress = if (totalChaps > 0) ((currChap + 1).toFloat() / totalChaps).coerceIn(0.02f, 1f) else 0f
    val unitLabel = if (ebook.format == "PDF") "Page" else "Chapter"
    val subtitle = if (totalChaps > 0) "$unitLabel ${currChap + 1} of $totalChaps" else "${ebook.format} eBook"

    if (viewMode == "GRID") {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = { onSelectEbook(ebook) },
                    onLongClick = { onLongPressEbook(ebook) }
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
                if (cardArt != null) {
                    Image(bitmap = cardArt!!, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                } else {
                    Icon(Icons.Default.MenuBook, contentDescription = null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
                }

                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(8.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color.Black.copy(alpha = 0.65f))
                        .padding(horizontal = 6.dp, vertical = 3.dp)
                ) {
                    Text(ebook.format, color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }

                if (progress > 0f) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .fillMaxWidth(progress)
                            .height(4.dp)
                            .background(MaterialTheme.colorScheme.primary)
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(ebook.title, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(ebook.author, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }
    } else {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = { onSelectEbook(ebook) },
                    onLongClick = { onLongPressEbook(ebook) }
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
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    if (cardArt != null) {
                        Image(bitmap = cardArt!!, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    } else {
                        Icon(Icons.Default.MenuBook, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    }
                }
                Spacer(Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(ebook.title, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(ebook.author, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
                Icon(Icons.Default.MenuBook, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
fun EpubReaderScreen(
    ebook: Ebook,
    repository: AudiobookRepository,
    chapters: List<EbookChapter>,
    currentChapterIndex: Int,
    isLoading: Boolean,
    fontSizeSp: Float,
    readerTheme: String,
    isEmbeddedPane: Boolean = false,
    isFullScreen: Boolean = false,
    onToggleFullScreen: (() -> Unit)? = null,
    onBackToShelf: () -> Unit,
    onSelectChapter: (Int) -> Unit,
    onUpdateFontSize: (Float) -> Unit,
    onUpdateTheme: (String) -> Unit
) {
    val isPdf = ebook.format == "PDF" || ebook.uri.toString().lowercase(Locale.ROOT).endsWith(".pdf")

    val bgColor = when (readerTheme) {
        "SEPIA" -> Color(0xFFF4ECD8)
        "LIGHT" -> Color(0xFFFAFAFA)
        else -> Color(0xFF0C1A14)
    }
    val textColor = when (readerTheme) {
        "SEPIA" -> Color(0xFF3B2F2F)
        "LIGHT" -> Color(0xFF1C2420)
        else -> Color(0xFFE8F0EC)
    }
    val accentColor = when (readerTheme) {
        "SEPIA" -> Color(0xFF8B5A2B)
        "LIGHT" -> Color(0xFF2D5A27)
        else -> Color(0xFFD4AF37)
    }

    var showTocDialog by remember { mutableStateOf(false) }
    var showReaderSettings by remember { mutableStateOf(false) }
    val scrollState = rememberScrollState()

    val currentChapter = chapters.getOrNull(currentChapterIndex)
    var renderedPdfBitmap by remember(ebook.id, currentChapterIndex) { mutableStateOf<Bitmap?>(null) }
    var isRenderingPdfPage by remember(ebook.id, currentChapterIndex) { mutableStateOf(false) }

    LaunchedEffect(currentChapterIndex, ebook.id, isPdf) {
        scrollState.scrollTo(0)
        if (isPdf) {
            isRenderingPdfPage = true
            renderedPdfBitmap = repository.renderPdfPageBitmap(ebook, currentChapterIndex, 1400)
            isRenderingPdfPage = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(bgColor)
            .let { if (!isEmbeddedPane) it.windowInsetsPadding(WindowInsets.systemBars) else it }
    ) {
        Surface(
            color = bgColor,
            tonalElevation = 2.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                if (!isEmbeddedPane) {
                    IconButton(onClick = onBackToShelf) {
                        Icon(Icons.Default.ChevronLeft, contentDescription = "Back", tint = accentColor)
                    }
                } else if (onToggleFullScreen != null) {
                    IconButton(onClick = onToggleFullScreen) {
                        Icon(
                            imageVector = if (isFullScreen) Icons.Default.CloseFullscreen else Icons.Default.OpenInFull,
                            contentDescription = "Toggle Full Screen",
                            tint = accentColor
                        )
                    }
                }

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 8.dp)
                        .clickable { showTocDialog = true },
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = ebook.title,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        color = textColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = currentChapter?.title ?: if (isPdf) "Page ${currentChapterIndex + 1}" else "Loading...",
                        fontSize = 12.sp,
                        color = accentColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { showTocDialog = true }) {
                        Icon(Icons.Default.FormatListNumbered, contentDescription = "Chapters / Pages", tint = accentColor)
                    }
                    IconButton(onClick = { showReaderSettings = true }) {
                        Icon(Icons.Default.FormatSize, contentDescription = "Reader Appearance", tint = accentColor)
                    }
                }
            }
        }

        HorizontalDivider(color = accentColor.copy(alpha = 0.2f))

        if (isLoading || (isPdf && isRenderingPdfPage)) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = accentColor)
            }
        } else if (isPdf) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(scrollState)
                    .padding(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (renderedPdfBitmap != null) {
                    Image(
                        bitmap = renderedPdfBitmap!!.asImageBitmap(),
                        contentDescription = "PDF Page ${currentChapterIndex + 1}",
                        contentScale = ContentScale.FillWidth,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color.White)
                    )
                } else {
                    Text(
                        text = "Unable to render PDF page. Please make sure file access permission is granted.",
                        color = textColor,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(24.dp)
                    )
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(scrollState)
                    .padding(horizontal = 24.dp, vertical = 20.dp)
            ) {
                Text(
                    text = currentChapter?.title ?: "",
                    fontSize = (fontSizeSp + 4f).sp,
                    fontWeight = FontWeight.Bold,
                    color = accentColor,
                    fontFamily = FontFamily.Serif
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    text = currentChapter?.bodyText ?: "",
                    fontSize = fontSizeSp.sp,
                    lineHeight = (fontSizeSp * 1.55f).sp,
                    color = textColor,
                    fontFamily = FontFamily.Serif
                )
                Spacer(Modifier.height(36.dp))
            }
        }

        HorizontalDivider(color = accentColor.copy(alpha = 0.2f))

        val navUnit = if (isPdf) "Page" else "Chapter"
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(
                onClick = { onSelectChapter(currentChapterIndex - 1) },
                enabled = currentChapterIndex > 0
            ) {
                Icon(Icons.Default.ChevronLeft, contentDescription = null, tint = if (currentChapterIndex > 0) accentColor else textColor.copy(alpha = 0.3f))
                Text("Prev", color = if (currentChapterIndex > 0) accentColor else textColor.copy(alpha = 0.3f))
            }

            Text(
                text = if (chapters.isNotEmpty()) {
                    "$navUnit ${currentChapterIndex + 1} of ${chapters.size} (${(((currentChapterIndex + 1).toFloat() / chapters.size) * 100).toInt()}%)"
                } else "",
                fontSize = 13.sp,
                color = textColor.copy(alpha = 0.8f),
                modifier = Modifier.clickable { showTocDialog = true }
            )

            TextButton(
                onClick = { onSelectChapter(currentChapterIndex + 1) },
                enabled = currentChapterIndex + 1 < chapters.size
            ) {
                Text("Next", color = if (currentChapterIndex + 1 < chapters.size) accentColor else textColor.copy(alpha = 0.3f))
                Icon(Icons.Default.ChevronRight, contentDescription = null, tint = if (currentChapterIndex + 1 < chapters.size) accentColor else textColor.copy(alpha = 0.3f))
            }
        }
    }

    if (showTocDialog) {
        val listHeader = if (isPdf) "Pages (${chapters.size})" else "Chapters (${chapters.size})"
        AlertDialog(
            onDismissRequest = { showTocDialog = false },
            title = { Text(listHeader) },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 400.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    chapters.forEachIndexed { idx, chap ->
                        val isCurr = idx == currentChapterIndex
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onSelectChapter(idx)
                                    showTocDialog = false
                                }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = if (isPdf) chap.title else "${idx + 1}. ${chap.title}",
                                fontWeight = if (isCurr) FontWeight.Bold else FontWeight.Normal,
                                color = if (isCurr) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showTocDialog = false }) { Text("Close") }
            }
        )
    }

    if (showReaderSettings) {
        AlertDialog(
            onDismissRequest = { showReaderSettings = false },
            title = { Text("Reader Appearance") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    if (!isPdf) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedButton(onClick = { onUpdateFontSize(fontSizeSp - 2f) }) { Text("A-") }
                            Text("${fontSizeSp.toInt()} pt", fontWeight = FontWeight.Bold)
                            OutlinedButton(onClick = { onUpdateFontSize(fontSizeSp + 2f) }) { Text("A+") }
                        }
                    }

                    Text("Reading Theme", fontWeight = FontWeight.Bold)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf("FOREST" to "Forest", "SEPIA" to "Sepia", "LIGHT" to "Light").forEach { (key, label) ->
                            FilterChip(
                                selected = readerTheme == key,
                                onClick = { onUpdateTheme(key) },
                                label = { Text(label) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showReaderSettings = false }) { Text("Done") }
            }
        )
    }
}

@Composable
fun BookCardWrapper(
    book: Audiobook,
    isSelected: Boolean,
    liveProgress: Float?,
    liveSubtitle: String?,
    viewMode: String,
    viewModel: LibraryViewModel,
    onSelectBook: (Audiobook) -> Unit,
    onLongPressBook: (Audiobook) -> Unit
) {
    var cardArt by remember(book.id, viewModel.coverRevision) { mutableStateOf<ImageBitmap?>(null) }
    var savedProgress by remember(book.id, viewModel.progressRevision) { mutableFloatStateOf(0f) }
    var savedSubtitle by remember(book.id, viewModel.progressRevision) { mutableStateOf("") }

    LaunchedEffect(book.id, viewModel.coverRevision) {
        val bmp = viewModel.repository.getOrFetchCoverArt(book)
        cardArt = bmp?.asImageBitmap()
    }

    LaunchedEffect(book.id, viewModel.progressRevision) {
        val isCompleted = viewModel.repository.loadCompletedBookIds().contains(book.id)
        if (isCompleted) {
            savedProgress = 1f
            savedSubtitle = "Completed"
        } else {
            val savedPos = viewModel.repository.getBookPosition(book.id)
            val savedTrackIdx = viewModel.repository.getBookTrackIndex(book.id)
            val cachedDurations = viewModel.repository.getCachedTrackDurations(book.id)
            var totalDur = viewModel.repository.getCachedBookDuration(book.id)

            if (totalDur <= 0L && (savedPos > 0L || savedTrackIdx > 0)) {
                val computed = viewModel.repository.getOrComputeTrackDurations(book)
                totalDur = bookDurationMs(computed)
            }

            if (savedPos > 0L || savedTrackIdx > 0) {
                if (totalDur > 0L) {
                    val offsetMs = if (book.tracks.size > 1 && cachedDurations.isNotEmpty()) {
                        bookOffsetMs(cachedDurations, savedTrackIdx, savedPos)
                    } else {
                        savedPos
                    }
                    savedProgress = (offsetMs.toFloat() / totalDur).coerceIn(0.01f, 0.99f)
                    val remaining = (totalDur - offsetMs).coerceAtLeast(0L)
                    savedSubtitle = "${formatTime(remaining)} left"
                } else if (book.tracks.size > 1) {
                    savedProgress = (savedTrackIdx.toFloat() / book.tracks.size).coerceIn(0.01f, 0.99f)
                    savedSubtitle = "Part ${savedTrackIdx + 1} of ${book.tracks.size}"
                } else {
                    savedProgress = 0.05f
                    savedSubtitle = "In Progress"
                }
            } else {
                savedProgress = 0f
                savedSubtitle = if (book.tracks.size == 1 && book.tracks.first().isM4b) "M4B Audiobook" else "${book.tracks.size} tracks"
            }
        }
    }

    val displayProgress = if (isSelected && liveProgress != null && liveProgress > 0f) liveProgress else savedProgress
    val displaySubtitle = if (isSelected && !liveSubtitle.isNullOrEmpty()) liveSubtitle else savedSubtitle

    if (viewMode == "GRID") {
        GridBookCard(
            book = book,
            coverArt = cardArt,
            progress = displayProgress,
            subtitleText = displaySubtitle,
            isSelected = isSelected,
            onClick = { onSelectBook(book) },
            onLongClick = { onLongPressBook(book) }
        )
    } else {
        ListBookCard(
            book = book,
            coverArt = cardArt,
            progress = displayProgress,
            subtitleText = displaySubtitle,
            isSelected = isSelected,
            onClick = { onSelectBook(book) },
            onLongClick = { onLongPressBook(book) }
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
                Icon(Icons.Default.Book, contentDescription = null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
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
        Text(
            text = if (book.series.isNotEmpty()) "${book.series} • ${book.author}" else book.author,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (subtitleText.isNotEmpty()) {
            Text(
                text = subtitleText,
                style = MaterialTheme.typography.bodySmall,
                color = if (progress == 1f || isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
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
                Text(
                    text = if (book.series.isNotEmpty()) "${book.series} • ${book.author}" else book.author,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (subtitleText.isNotEmpty()) {
                    Text(
                        text = subtitleText,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (progress == 1f || isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Icon(
                imageVector = Icons.Default.Headphones,
                contentDescription = null,
                tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )
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
    bookPositionMs: Long,
    bookDurationMs: Long,
    skipIntervalSeconds: Int,
    silenceTrimEnabled: Boolean,
    sleepTimerActive: Boolean,
    sleepTimeRemaining: Long,
    sleepEndAtChapter: Boolean,
    isDarkTheme: Boolean,
    playerVolume: Float,
    playbackSpeed: Float,
    isEmbeddedPane: Boolean = false,
    onOpenDrawer: () -> Unit,
    onBackToBookshelf: () -> Unit,
    onPlayPause: () -> Unit,
    onSeekLive: (Long) -> Unit,
    onSeekFinished: (Long) -> Unit,
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
    val totalDur = if (bookDurationMs > 0L) bookDurationMs else duration
    val totalRead = if (bookDurationMs > 0L) bookPositionMs else currentPosition
    val percentRead = if (totalDur > 0L) ((totalRead.toDouble() / totalDur.toDouble()) * 100).toInt().coerceIn(0, 100) else 0

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

    var isDraggingSlider by remember { mutableStateOf(false) }
    var dragSliderValue by remember { mutableFloatStateOf(0f) }
    val activeSliderValue = if (isDraggingSlider) dragSliderValue else sliderPositionMs.toFloat()

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .let { if (!isEmbeddedPane) it.windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.displayCutout)) else it }
            .padding(16.dp)
    ) {
        val useBookSpread = !isEmbeddedPane && maxWidth >= 600.dp

        @Composable
        fun ControlsCard() {
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
                        value = activeSliderValue.coerceIn(0f, sliderDurationMs.toFloat()),
                        onValueChange = { newSliderVal ->
                            isDraggingSlider = true
                            dragSliderValue = newSliderVal
                            val targetMs = if (useChapterScrubber) {
                                chapterStartMs + newSliderVal.toLong()
                            } else {
                                newSliderVal.toLong()
                            }
                            onSeekLive(targetMs)
                        },
                        onValueChangeFinished = {
                            val finalMs = if (useChapterScrubber) {
                                chapterStartMs + dragSliderValue.toLong()
                            } else {
                                dragSliderValue.toLong()
                            }
                            isDraggingSlider = false
                            onSeekFinished(finalMs)
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
                        Text(formatTime(activeSliderValue.toLong()), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("-${formatTime((sliderDurationMs - activeSliderValue.toLong()).coerceAtLeast(0L))}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                                text = String.format(Locale.US, "%.1fx", playbackSpeed),
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
                                tint = if (silenceTrimEnabled) Color.Black else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        IconButton(onClick = onOpenSleepMenu) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.Bedtime,
                                    contentDescription = "Sleep Timer",
                                    tint = if (sleepTimerActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                if (sleepTimerActive) {
                                    Badge(modifier = Modifier.offset(x = 10.dp, y = (-10).dp)) {
                                        Text(if (sleepEndAtChapter) "End" else "${TimeUnit.MILLISECONDS.toMinutes(sleepTimeRemaining) + 1}m")
                                    }
                                }
                            }
                        }

                        IconButton(onClick = onToggleTheme) {
                            ContrastThemeIcon(tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                        }

                        IconButton(onClick = onOpenAudioFX) {
                            Icon(Icons.Default.GraphicEq, contentDescription = stringResource(R.string.audio_fx), tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        }

        if (useBookSpread) {
            Row(
                modifier = Modifier.fillMaxSize(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier.weight(1f).fillMaxHeight().padding(end = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = onOpenDrawer) {
                            Icon(Icons.Default.Menu, contentDescription = "Open Drawer Menu")
                        }
                        IconButton(onClick = onBackToBookshelf) {
                            Icon(Icons.Default.CloseFullscreen, contentDescription = "Split View", tint = MaterialTheme.colorScheme.primary)
                        }
                    }

                    Box(
                        modifier = Modifier
                            .size(240.dp)
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
                        Text(book.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, maxLines = 2)
                        Text(
                            text = if (book.series.isNotEmpty()) "${book.series} • ${book.author}" else book.author,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(4.dp))
                        Text("Total: ${formatTime(totalDur)} • Read: $percentRead%", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    }
                }

                FoldableSpineDivider()

                Column(
                    modifier = Modifier.weight(1f).fillMaxHeight().padding(start = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.SpaceEvenly
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        OutlinedButton(onClick = onEnterCarMode) {
                            Icon(Icons.Default.DirectionsCar, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Car")
                        }
                        OutlinedButton(onClick = onOpenChapters) {
                            Icon(Icons.Default.FormatListNumbered, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Chapters")
                        }
                        OutlinedButton(onClick = onOpenBookmarks) {
                            Icon(Icons.Default.Bookmark, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Pins")
                        }
                    }

                    ControlsCard()
                }
            }
        } else {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (!isEmbeddedPane) {
                        IconButton(onClick = onOpenDrawer) {
                            Icon(Icons.Default.Menu, contentDescription = "Open Drawer Menu")
                        }
                    } else {
                        Spacer(Modifier.width(12.dp))
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
                        Icon(
                            imageVector = if (isEmbeddedPane) Icons.Default.OpenInFull else Icons.Default.Book,
                            contentDescription = "Toggle Player View",
                            tint = MaterialTheme.colorScheme.primary
                        )
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
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Box(
                    modifier = Modifier
                        .size(if (isEmbeddedPane) 185.dp else 220.dp)
                        .clip(RoundedCornerShape(24.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    if (coverArt != null) {
                        Image(bitmap = coverArt, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    } else {
                        Icon(Icons.Default.Headphones, contentDescription = null, modifier = Modifier.size(84.dp), tint = MaterialTheme.colorScheme.primary)
                    }
                }

                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Total Book: ${formatTime(totalDur)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Read: ${formatTime(totalRead)} ( $percentRead% )", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                ControlsCard()
            }
        }
    }
}
// END OF FILE