package com.example.audiobook

import android.graphics.Bitmap
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sin

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

// Splits a chapter's body text into screen-fitted pages so every swipe turns a single page
fun paginateChapterText(
    bodyText: String,
    charsPerLine: Int,
    linesPerPage: Int
): List<String> {
    val clean = bodyText.trim()
    if (clean.isEmpty()) return listOf("")

    val safeCharsPerLine = charsPerLine.coerceIn(24, 90)
    val safeLinesPerPage = linesPerPage.coerceIn(10, 48)

    val pages = mutableListOf<String>()
    val currentPage = StringBuilder()
    var currentLines = 0

    val paragraphs = clean.split(Regex("\\n\\s*\\n"))
    for (para in paragraphs) {
        val trimmedPara = para.trim()
        if (trimmedPara.isEmpty()) continue

        val words = trimmedPara.split(Regex("\\s+"))
        val paraBuf = StringBuilder()
        var lineChars = 0

        for (word in words) {
            val wordLen = word.length + if (lineChars > 0) 1 else 0
            if (lineChars + wordLen > safeCharsPerLine) {
                currentLines++
                lineChars = word.length
                if (currentLines >= safeLinesPerPage && paraBuf.isNotEmpty()) {
                    if (currentPage.isNotEmpty()) currentPage.append("\n\n")
                    currentPage.append(paraBuf.toString().trim())
                    pages.add(currentPage.toString().trim())
                    currentPage.clear()
                    paraBuf.clear()
                    currentLines = 1
                }
            } else {
                lineChars += wordLen
            }
            if (paraBuf.isNotEmpty()) paraBuf.append(' ')
            paraBuf.append(word)
        }

        if (paraBuf.isNotEmpty()) {
            val addedLines = 1 + if (currentPage.isNotEmpty()) 1 else 0
            if (currentLines + addedLines > safeLinesPerPage && currentPage.isNotEmpty()) {
                pages.add(currentPage.toString().trim())
                currentPage.clear()
                currentPage.append(paraBuf.toString().trim())
                currentLines = (paraBuf.length / safeCharsPerLine) + 1
            } else {
                if (currentPage.isNotEmpty()) currentPage.append("\n\n")
                currentPage.append(paraBuf.toString().trim())
                currentLines += addedLines
            }
        }
    }

    if (currentPage.isNotEmpty()) {
        pages.add(currentPage.toString().trim())
    }

    return if (pages.isEmpty()) listOf(clean) else pages
}

// =========================================================================================
// 2D/3D GEOMETRIC PAGE-CURL ENGINE (Perpendicular-Bisector Fold + Mirrored Backside Bleed)
// =========================================================================================

private data class PageCurlGeometry(
    val foldOrigin: Offset,
    val normalX: Float,
    val normalY: Float,
    val flatPoly: List<Offset>,
    val peeledPoly: List<Offset>,
    val reflectedFlapPoly: List<Offset>,
    val foldEdgeStart: Offset,
    val foldEdgeEnd: Offset,
    val maxPeeledDistance: Float,
    val flapScale: Float
)

private fun clipPolygonHalfPlane(
    polygon: List<Offset>,
    fx: Float,
    fy: Float,
    nx: Float,
    ny: Float,
    keepNegativeSide: Boolean
): List<Offset> {
    if (polygon.isEmpty()) return emptyList()
    val output = ArrayList<Offset>(polygon.size + 2)
    val sign = if (keepNegativeSide) 1f else -1f

    fun signedDist(pt: Offset): Float = ((pt.x - fx) * nx + (pt.y - fy) * ny) * sign

    var prev = polygon.last()
    var prevDist = signedDist(prev)

    for (curr in polygon) {
        val currDist = signedDist(curr)
        val prevInside = prevDist <= 0.001f
        val currInside = currDist <= 0.001f

        if (prevInside != currInside) {
            val denom = prevDist - currDist
            val t = if (abs(denom) > 1e-5f) (prevDist / denom).coerceIn(0f, 1f) else 0.5f
            output.add(
                Offset(
                    x = prev.x + (curr.x - prev.x) * t,
                    y = prev.y + (curr.y - prev.y) * t
                )
            )
        }
        if (currInside) {
            output.add(curr)
        }
        prev = curr
        prevDist = currDist
    }
    return output
}

private fun computePageCurlGeometry(
    width: Float,
    height: Float,
    peelProgress: Float,
    touchYNorm: Float
): PageCurlGeometry? {
    if (width <= 1f || height <= 1f) return null
    val p = peelProgress.coerceIn(0.002f, 0.998f)

    // Determine whether the user is lifting from the bottom-right, top-right, or middle-right edge
    val rawBias = (touchYNorm.coerceIn(0.05f, 0.95f) - 0.5f) * 2f
    val effectiveBias = if (abs(rawBias) < 0.16f) {
        // Default natural lower-hand corner lift when swiping near the vertical center
        0.30f
    } else {
        rawBias
    }

    val cornerX = width
    val cornerY = if (effectiveBias >= 0f) height else 0f

    // As p goes 0 -> 1, the lifted corner tip P sweeps from x = W across to x = -W
    val tipX = width * (1f - 2f * p)
    val archFactor = sin(p * Math.PI).toFloat() * (1f - p).pow(0.55f)
    val verticalLift = height * (0.16f + 0.34f * abs(effectiveBias)) * archFactor
    val tipY = if (effectiveBias >= 0f) {
        (cornerY - verticalLift).coerceIn(height * 0.05f, height)
    } else {
        (cornerY + verticalLift).coerceIn(0f, height * 0.95f)
    }

    val dx = cornerX - tipX
    val dy = cornerY - tipY
    val len = hypot(dx, dy).coerceAtLeast(1e-3f)
    val nx = dx / len
    val ny = dy / len

    // Midpoint M of (Corner, Tip) defines the perpendicular-bisector crease line
    val foldX = (cornerX + tipX) * 0.5f
    val foldY = (cornerY + tipY) * 0.5f

    val pageRect = listOf(
        Offset(0f, 0f),
        Offset(width, 0f),
        Offset(width, height),
        Offset(0f, height)
    )

    val flatPoly = clipPolygonHalfPlane(pageRect, foldX, foldY, nx, ny, keepNegativeSide = true)
    val peeledPoly = clipPolygonHalfPlane(pageRect, foldX, foldY, nx, ny, keepNegativeSide = false)
    if (peeledPoly.size < 3 || flatPoly.size < 3) return null

    // Foreshortening scale simulates the paper wrapping over a 3D curved cylinder before extending flat
    val flapScale = (0.76f + 0.22f * p).coerceIn(0.74f, 0.98f)
    var maxDist = 1f
    val reflectedPoly = peeledPoly.map { pt ->
        val d = ((pt.x - foldX) * nx + (pt.y - foldY) * ny).coerceAtLeast(0f)
        if (d > maxDist) maxDist = d
        Offset(
            x = pt.x - (1f + flapScale) * d * nx,
            y = pt.y - (1f + flapScale) * d * ny
        )
    }

    // Identify the two intersection endpoints lying directly on the fold line (d ~ 0)
    val foldEndpoints = peeledPoly.filter { pt ->
        abs((pt.x - foldX) * nx + (pt.y - foldY) * ny) <= 2.5f
    }
    val edgeStart = foldEndpoints.firstOrNull() ?: Offset(foldX, 0f)
    val edgeEnd = foldEndpoints.lastOrNull() ?: Offset(foldX, height)

    return PageCurlGeometry(
        foldOrigin = Offset(foldX, foldY),
        normalX = nx,
        normalY = ny,
        flatPoly = flatPoly,
        peeledPoly = peeledPoly,
        reflectedFlapPoly = reflectedPoly,
        foldEdgeStart = edgeStart,
        foldEdgeEnd = edgeEnd,
        maxPeeledDistance = maxDist * flapScale,
        flapScale = flapScale
    )
}

private fun buildCurvedFlapPath(geom: PageCurlGeometry): Path {
    val path = Path()
    val pts = geom.reflectedFlapPoly
    if (pts.isEmpty()) return path

    val fx = geom.foldOrigin.x
    val fy = geom.foldOrigin.y
    val nx = geom.normalX
    val ny = geom.normalY
    val bowAmount = (geom.maxPeeledDistance * 0.08f).coerceIn(4f, 28f)

    path.moveTo(pts[0].x, pts[0].y)
    for (i in pts.indices) {
        val curr = pts[i]
        val next = pts[(i + 1) % pts.size]
        val currOnFold = abs((curr.x - fx) * nx + (curr.y - fy) * ny) <= 3f
        val nextOnFold = abs((next.x - fx) * nx + (next.y - fy) * ny) <= 3f

        if (currOnFold != nextOnFold) {
            // Add a subtle outward S-curve where the paper bends off the fold crease
            val midX = (curr.x + next.x) * 0.5f - nx * bowAmount
            val midY = (curr.y + next.y) * 0.5f - ny * bowAmount
            path.quadraticBezierTo(midX, midY, next.x, next.y)
        } else {
            path.lineTo(next.x, next.y)
        }
    }
    path.close()
    return path
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
          fset(foldX, height)

    return PageCurlGeometry(
        foldOrigin = Offset(foldX, foldY),
        normalX = nx,
        normalY = ny,
        flatPoly = flatPoly,
        peeledPoly = peeledPoly,
        reflectedFlapPoly = reflectedPoly,
        foldEdgeStart = edgeStart,
        foldEdgeEnd = edgeEnd,
        maxPeeledDistance = maxDist * flapScale,
        flapScale = flapScale
    )
}

private fun buildCurvedFlapPath(geom: PageCurlGeometry): Path {
    val path = Path()
    val pts = geom.reflectedFlapPoly
    if (pts.isEmpty()) return path

    val fx = geom.foldOrigin.x
    val fy = geom.foldOrigin.y
    val nx = geom.normalX
    val ny = geom.normalY
    val bowAmount = (geom.maxPeeledDistance * 0.08f).coerceIn(4f, 28f)

    path.moveTo(pts[0].x, pts[0].y)
    for (i in pts.indices) {
        val curr = pts[i]
        val next = pts[(i + 1) % pts.size]
        val currOnFold = abs((curr.x - fx) * nx + (curr.y - fy) * ny) <= 3f
        val nextOnFold = abs((next.x - fx) * nx + (next.y - fy) * ny) <= 3f

        if (currOnFold != nextOnFold) {
            // Add a subtle outward S-curve where the paper bends off the fold crease
            val midX = (curr.x + next.x) * 0.5f - nx * bowAmount
            val midY = (curr.y + next.y) * 0.5f - ny * bowAmount
            path.quadraticBezierTo(midX, midY, next.x, next.y)
        } else {
            path.lineTo(next.x, next.y)
        }
    }
    path.close()
    return path
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
                    text = "Scan your Downloads & Books folders for eBooks (.epub, .azw3, .mobi, .pdf novels, .cbz & more), or import files directly.",
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
                    Text("Import eBook File(s)")
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
    val isVisualPageFormat = ebook.format.uppercase(Locale.ROOT) in VISUAL_PAGE_EBOOK_FORMATS
    val unitLabel = if (isVisualPageFormat) "Section" else "Chapter"
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
    onUpdateTheme: (String) -> Unit,
    onReloadEbook: () -> Unit = {}
) {
    val fmtUpper = ebook.format.uppercase(Locale.ROOT)
    val currentChapter = chapters.getOrNull(currentChapterIndex)
    val isVisualPageMode = (fmtUpper in VISUAL_PAGE_EBOOK_FORMATS) && (currentChapter?.bodyText.isNullOrBlank())

    val bgColor = when (readerTheme) {
        "SEPIA" -> Color(0xFFF4ECD8)
        "LIGHT" -> Color(0xFFFAFAFA)
        else -> Color(0xFF0C1A14)
    }
    val curlBackPaperBaseColor = when (readerTheme) {
        "SEPIA" -> Color(0xFFEDE1C7)
        "LIGHT" -> Color(0xFFF2F4F3)
        else -> Color(0xFF13281F)
    }
    val curlBackHighlightColor = when (readerTheme) {
        "SEPIA" -> Color(0xFFFDF9F0)
        "LIGHT" -> Color(0xFFFFFFFF)
        else -> Color(0xFF214233)
    }
    val curlBackShadeColor = when (readerTheme) {
        "SEPIA" -> Color(0xFFD6C5A3)
        "LIGHT" -> Color(0xFFD9E0DC)
        else -> Color(0xFF09130E)
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
    var pdfOriginalPageMode by remember {
        mutableStateOf(repository.getPreferences().getBoolean("pdf_original_page_mode", false))
    }

    var subPageIndex by remember(ebook.id, currentChapterIndex) { mutableIntStateOf(0) }
    var startAtLastSubPage by remember { mutableStateOf(false) }

    var renderedPageBitmap by remember(ebook.id, currentChapterIndex, readerTheme) { mutableStateOf<Bitmap?>(null) }
    var nextPageBitmap by remember(ebook.id, currentChapterIndex, readerTheme) { mutableStateOf<Bitmap?>(null) }
    var prevPageBitmap by remember(ebook.id, currentChapterIndex, readerTheme) { mutableStateOf<Bitmap?>(null) }
    var isRenderingVisualPage by remember(ebook.id, currentChapterIndex) { mutableStateOf(false) }

    val coroutineScope = rememberCoroutineScope()
    // curlAnim: 0f = flat, +0.01..+1f = curling forward (Next), -0.01..-1f = curling backward (Prev)
    val curlAnim = remember { Animatable(0f) }
    // Normalized vertical touch coordinate (0f = top, 0.5f = center, 1f = bottom) for corner-lifting tilt
    var curlTouchYNorm by remember { mutableFloatStateOf(0.78f) }

    LaunchedEffect(currentChapterIndex, ebook.id, isVisualPageMode, readerTheme) {
        if (isVisualPageMode) {
            isRenderingVisualPage = true
            renderedPageBitmap = repository.renderPdfPageBitmap(ebook, currentChapterIndex, 1400, readerTheme)
            isRenderingVisualPage = false
            if (currentChapterIndex + 1 < chapters.size) {
                nextPageBitmap = repository.renderPdfPageBitmap(ebook, currentChapterIndex + 1, 1200, readerTheme)
            }
            if (currentChapterIndex > 0) {
                prevPageBitmap = repository.renderPdfPageBitmap(ebook, currentChapterIndex - 1, 1200, readerTheme)
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(bgColor)
            .let { if (!isEmbeddedPane) it.windowInsetsPadding(WindowInsets.systemBars) else it }
    ) {
        // Top Reader Bar
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
                        text = currentChapter?.title ?: if (isVisualPageMode) "Page ${currentChapterIndex + 1}" else "Loading...",
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

        // Main Interactive 2D/3D Page-Curl Area + Bottom Navigation Bar
        BoxWithConstraints(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(bgColor)
        ) {
            val availWidthDp = maxWidth.value
            val availHeightDp = (maxHeight.value - 48f).coerceAtLeast(180f)

            val charsPerLine = ((availWidthDp - 44f) / (fontSizeSp * 0.54f)).toInt().coerceIn(26, 85)
            val lineHeightSp = fontSizeSp * 1.52f
            val linesPerPage = ((availHeightDp - 40f) / (lineHeightSp * 1.05f)).toInt().coerceIn(10, 42)

            val subPages = remember(currentChapter?.bodyText, charsPerLine, linesPerPage) {
                if (isVisualPageMode) listOf("")
                else paginateChapterText(currentChapter?.bodyText ?: "", charsPerLine, linesPerPage)
            }

            LaunchedEffect(subPages.size, currentChapterIndex) {
                if (startAtLastSubPage) {
                    subPageIndex = subPages.lastIndex.coerceAtLeast(0)
                    startAtLastSubPage = false
                } else if (subPageIndex > subPages.lastIndex) {
                    subPageIndex = subPages.lastIndex.coerceAtLeast(0)
                }
            }

            val canGoNext = (subPageIndex + 1 < subPages.size) || (currentChapterIndex + 1 < chapters.size)
            val canGoPrev = (subPageIndex > 0) || (currentChapterIndex > 0)

            fun commitNextPage() {
                if (subPageIndex + 1 < subPages.size) {
                    subPageIndex++
                } else if (currentChapterIndex + 1 < chapters.size) {
                    startAtLastSubPage = false
                    subPageIndex = 0
                    onSelectChapter(currentChapterIndex + 1)
                }
            }

            fun commitPrevPage() {
                if (subPageIndex > 0) {
                    subPageIndex--
                } else if (currentChapterIndex > 0) {
                    startAtLastSubPage = true
                    onSelectChapter(currentChapterIndex - 1)
                }
            }

            fun animatePageTurnForward(startYNorm: Float = 0.78f) {
                if (!canGoNext || curlAnim.isRunning) return
                curlTouchYNorm = startYNorm
                coroutineScope.launch {
                    curlAnim.snapTo(0.03f)
                    curlAnim.animateTo(1f, tween(durationMillis = 420, easing = FastOutSlowInEasing))
                    commitNextPage()
                    curlAnim.snapTo(0f)
                }
            }

            fun animatePageTurnBackward(startYNorm: Float = 0.78f) {
                if (!canGoPrev || curlAnim.isRunning) return
                curlTouchYNorm = startYNorm
                coroutineScope.launch {
                    curlAnim.snapTo(-0.03f)
                    curlAnim.animateTo(-1f, tween(durationMillis = 420, easing = FastOutSlowInEasing))
                    commitPrevPage()
                    curlAnim.snapTo(0f)
                }
            }

            val currSubPageText = subPages.getOrElse(subPageIndex) { "" }
            val nextPreviewText = remember(subPageIndex, subPages, currentChapterIndex, chapters, charsPerLine, linesPerPage) {
                if (subPageIndex + 1 < subPages.size) {
                    subPages[subPageIndex + 1]
                } else if (currentChapterIndex + 1 < chapters.size) {
                    val nextChap = chapters[currentChapterIndex + 1]
                    paginateChapterText(nextChap.bodyText, charsPerLine, linesPerPage).firstOrNull() ?: ""
                } else ""
            }

            val prevPreviewText = remember(subPageIndex, subPages, currentChapterIndex, chapters, charsPerLine, linesPerPage) {
                if (subPageIndex > 0) {
                    subPages[subPageIndex - 1]
                } else if (currentChapterIndex > 0) {
                    val prevChap = chapters[currentChapterIndex - 1]
                    paginateChapterText(prevChap.bodyText, charsPerLine, linesPerPage).lastOrNull() ?: ""
                } else ""
            }

            @Composable
            fun PageContentLayer(
                pageText: String,
                showChapterHeading: Boolean,
                headingText: String,
                visualBitmap: Bitmap?
            ) {
                if (isVisualPageMode) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(bgColor)
                            .padding(horizontal = 6.dp, vertical = 4.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        if (visualBitmap != null) {
                            Image(
                                bitmap = visualBitmap.asImageBitmap(),
                                contentDescription = headingText,
                                contentScale = ContentScale.Fit,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(bgColor)
                            .padding(horizontal = 22.dp, vertical = 16.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        if (showChapterHeading && headingText.isNotBlank()) {
                            Text(
                                text = headingText,
                                fontSize = (fontSizeSp + 3f).sp,
                                fontWeight = FontWeight.Bold,
                                color = accentColor,
                                fontFamily = FontFamily.Serif
                            )
                            Spacer(Modifier.height(12.dp))
                        }
                        Text(
                            text = pageText,
                            fontSize = fontSizeSp.sp,
                            lineHeight = lineHeightSp.sp,
                            color = textColor,
                            fontFamily = FontFamily.Serif
                        )
                    }
                }
            }

            Column(modifier = Modifier.fillMaxSize()) {
                // Interactive Page-Curl Viewport
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .pointerInput(currentChapterIndex, subPageIndex, subPages.size, chapters.size) {
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                val w = size.width.toFloat().coerceAtLeast(1f)
                                val h = size.height.toFloat().coerceAtLeast(1f)

                                var totalDragX = 0
