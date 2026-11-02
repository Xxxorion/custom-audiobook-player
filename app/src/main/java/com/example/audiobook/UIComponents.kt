package com.example.audiobook

import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.LinearGradient
import android.graphics.Matrix as AndroidMatrix
import android.graphics.Paint as AndroidPaint
import android.graphics.Path as AndroidPath
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.Layout
import android.text.Spannable
import android.text.SpannableString
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.ForegroundColorSpan
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
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

private data class PageLinkHotspot(
    val rect: RectF,
    val targetChapterIndex: Int,
    val label: String
)

private data class RenderedBookPage(
    val bitmap: Bitmap,
    val hotspots: List<PageLinkHotspot>
)

// Normalizes chapter titles using the first heading line of each chapter when needed,
// and strips duplicate leading title lines from the body text so headings don't print twice.
private fun resolveSmartChapterTitles(
    rawChapters: List<EbookChapter>,
    bookTitle: String
): List<EbookChapter> {
    if (rawChapters.isEmpty()) return rawChapters
    val cleanBook = bookTitle.trim().lowercase(Locale.ROOT)

    return rawChapters.map { chap ->
        val nonBlankLines = chap.bodyText.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val firstLine = nonBlankLines.firstOrNull() ?: ""
        val rawTitle = chap.title.trim()

        val isGenericTitle = rawTitle.isBlank() ||
            rawTitle.lowercase(Locale.ROOT) == cleanBook ||
            rawTitle.matches(Regex("(?i)^chapter\\s+\\d+$"))

        val looksLikeHeading = firstLine.length in 2..65 &&
            !firstLine.endsWith(".") &&
            !firstLine.endsWith("?”") &&
            !firstLine.endsWith("!”")

        val resolvedTitle = if (isGenericTitle && looksLikeHeading) {
            firstLine
        } else {
            rawTitle.ifBlank { "Chapter ${chap.index + 1}" }
        }

        // Strip leading line if it duplicates the resolved chapter title
        val rawLines = chap.bodyText.lines()
        val firstNonBlankIdx = rawLines.indexOfFirst { it.trim().isNotEmpty() }
        val cleanedBody = if (firstNonBlankIdx != -1 &&
            rawLines[firstNonBlankIdx].trim().equals(resolvedTitle.trim(), ignoreCase = true)
        ) {
            rawLines.drop(firstNonBlankIdx + 1).joinToString("\n").trim()
        } else {
            chap.bodyText.trim()
        }

        chap.copy(title = resolvedTitle, bodyText = cleanedBody)
    }
}

// Matches a Table of Contents line (e.g., "Cover", "Dedication", "Prologue", "Chapter 1") to its target chapter index
private fun matchLineToTargetChapterIndex(
    rawLine: String,
    chapters: List<EbookChapter>,
    currentChapterIndex: Int,
    isTocChapter: Boolean
): Int? {
    if (chapters.size <= 1) return null

    // Clean leader dots and trailing page numbers (e.g. "Chapter 1 ....... 14" -> "Chapter 1")
    val cleaned = rawLine.trim()
        .replace(Regex("[.…_\\s]+\\d+\\s*$"), "")
        .replace(Regex("^\\d+\\.\\s+"), "")
        .trim()

    if (cleaned.length !in 2..68) return null

    // Avoid turning normal story sentences into links on non-TOC pages
    val looksLikeTocOrHeadingLink = cleaned.matches(
        Regex("(?i)^(cover|title\\s*page|dedication|epigraph|contents|table\\s+of\\s+contents|prologue|preface|foreword|introduction|chapter\\s+[0-9ivxlcdm]+.*|part\\s+[0-9ivxlcdm]+.*|book\\s+[0-9ivxlcdm]+.*|epilogue|afterword|acknowledgm?ents|about\\s+the\\s+author|about\\s+the\\s+publisher|copyright|index|glossary|notes|appendix.*)$")
    )
    if (!isTocChapter && !looksLikeTocOrHeadingLink) return null
    if (cleaned.endsWith(".") || cleaned.endsWith(",") || cleaned.endsWith(";") || cleaned.endsWith("\"") || cleaned.endsWith("”")) {
        return null
    }

    val lower = cleaned.lowercase(Locale.ROOT)
    if (lower == "contents" || lower == "table of contents") return null

    if (lower == "cover") return 0
    if (lower == "title page") {
        val titlePageIdx = chapters.indexOfFirst {
            it.title.contains("title", ignoreCase = true) || it.index == 0
        }
        return if (titlePageIdx != -1 && titlePageIdx != currentChapterIndex) titlePageIdx else 0
    }

    // 1. Exact match on chapter title or opening line of chapter body
    for (chap in chapters) {
        if (chap.index == currentChapterIndex) continue
        val cTitle = chap.title.trim().lowercase(Locale.ROOT)
        val firstLine = chap.bodyText.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }?.lowercase(Locale.ROOT) ?: ""

        if (cTitle == lower || firstLine == lower) {
            return chap.index
        }
        if (lower.length >= 4 && (cTitle.startsWith("$lower:") || cTitle.startsWith("$lower -") || cTitle.startsWith("$lower —"))) {
            return chap.index
        }
    }

    // 2. Match "Chapter N" by chapter number
    val chapNumMatch = Regex("(?i)^chapter\\s+(\\d+)\\b").find(cleaned)
    if (chapNumMatch != null) {
        val targetNum = chapNumMatch.groupValues[1].toIntOrNull()
        if (targetNum != null && targetNum > 0) {
            // First look for a chapter whose title or opening lines explicitly say "Chapter N" or "N"
            val explicitMatch = chapters.firstOrNull { chap ->
                if (chap.index == currentChapterIndex) false
                else {
                    val t = chap.title.trim()
                    val firstLines = chap.bodyText.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.take(2).joinToString(" ")
                    t.equals("Chapter $targetNum", ignoreCase = true) ||
                        t.startsWith("Chapter $targetNum:", ignoreCase = true) ||
                        t.startsWith("Chapter $targetNum ", ignoreCase = true) ||
                        t == targetNum.toString() ||
                        Regex("(?i)\\bchapter\\s+$targetNum\\b").containsMatchIn(firstLines)
                }
            }
            if (explicitMatch != null) return explicitMatch.index

            // Fallback: offset from the first story chapter after Prologue/Contents
            val firstStoryChapIdx = chapters.indexOfFirst {
                it.index > currentChapterIndex &&
                    !it.title.contains("prologue", ignoreCase = true) &&
                    !it.title.contains("dedication", ignoreCase = true) &&
                    !it.title.contains("contents", ignoreCase = true)
            }.takeIf { it >= 0 } ?: (currentChapterIndex + 1)

            val computedIdx = firstStoryChapIdx + (targetNum - 1)
            if (computedIdx in chapters.indices && computedIdx != currentChapterIndex) {
                return computedIdx
            }
        }
    }

    // 3. Substring match on front/back matter keywords (Dedication, Prologue, Epilogue, Acknowledgments, About the Author, etc.)
    for (chap in chapters) {
        if (chap.index == currentChapterIndex) continue
        val cTitle = chap.title.trim().lowercase(Locale.ROOT)
        val headSample = chap.bodyText.take(240).lowercase(Locale.ROOT)
        if (cTitle.contains(lower) || (looksLikeTocOrHeadingLink && headSample.contains(lower))) {
            return chap.index
        }
    }

    return null
}

// Preserves single line breaks (so Table of Contents lists stay on separate lines)
// and splits chapters into screen-fitted pages for the 3D page-curl engine.
fun paginateChapterText(
    bodyText: String,
    charsPerLine: Int,
    linesPerPage: Int
): List<String> {
    val clean = bodyText.replace("\r\n", "\n").trim()
    if (clean.isEmpty()) return listOf("")

    val safeCharsPerLine = charsPerLine.coerceIn(24, 90)
    val safeLinesPerPage = linesPerPage.coerceIn(8, 42)

    val pages = mutableListOf<String>()
    val currentPage = StringBuilder()
    var currentLines = 0

    val rawLines = clean.lines()
    var blankPending = false

    for (rawLine in rawLines) {
        val trimmedLine = rawLine.trim()
        if (trimmedLine.isEmpty()) {
            if (currentPage.isNotEmpty()) {
                blankPending = true
            }
            continue
        }

        if (blankPending) {
            if (currentLines + 1 >= safeLinesPerPage && currentPage.isNotEmpty()) {
                pages.add(currentPage.toString().trimEnd())
                currentPage.clear()
                currentLines = 0
            } else if (currentPage.isNotEmpty()) {
                currentPage.append("\n\n")
                currentLines++
            }
            blankPending = false
        } else if (currentPage.isNotEmpty()) {
            if (currentLines + 1 >= safeLinesPerPage) {
                pages.add(currentPage.toString().trimEnd())
                currentPage.clear()
                currentLines = 0
            } else {
                currentPage.append('\n')
            }
        }

        val words = trimmedLine.split(Regex("[ \\t]+"))
        var lineChars = 0
        val lineBuf = StringBuilder()

        for (word in words) {
            val wordLen = word.length + if (lineChars > 0) 1 else 0
            if (lineChars + wordLen > safeCharsPerLine && lineBuf.isNotEmpty()) {
                currentPage.append(lineBuf.toString())
                lineBuf.clear()
                currentLines++
                lineChars = 0

                if (currentLines >= safeLinesPerPage) {
                    pages.add(currentPage.toString().trimEnd())
                    currentPage.clear()
                    currentLines = 0
                } else {
                    currentPage.append('\n')
                }
            }
            if (lineBuf.isNotEmpty()) lineBuf.append(' ')
            lineBuf.append(word)
            lineChars += wordLen
        }

        if (lineBuf.isNotEmpty()) {
            currentPage.append(lineBuf.toString())
            currentLines++
            if (currentLines >= safeLinesPerPage) {
                pages.add(currentPage.toString().trimEnd())
                currentPage.clear()
                currentLines = 0
            }
        }
    }

    if (currentPage.isNotEmpty()) {
        pages.add(currentPage.toString().trimEnd())
    }

    return if (pages.isEmpty()) listOf(clean) else pages
}

// Renders a book page onto an offscreen Bitmap AND computes clickable chapter link hotspots!
private fun renderBookPageToBitmap(
    widthPx: Int,
    heightPx: Int,
    bgColorArgb: Int,
    textColorArgb: Int,
    accentColorArgb: Int,
    linkColorArgb: Int,
    headingText: String,
    showHeading: Boolean,
    bodyText: String,
    fontSizePx: Float,
    visualBitmap: Bitmap?,
    pageFooterLabel: String,
    allChapters: List<EbookChapter>,
    currentChapterIndex: Int,
    isTocChapter: Boolean
): RenderedBookPage {
    val w = widthPx.coerceAtLeast(64)
    val h = heightPx.coerceAtLeast(64)
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = AndroidCanvas(bmp)
    canvas.drawColor(bgColorArgb)
    val hotspots = mutableListOf<PageLinkHotspot>()

    val padH = (w * 0.068f).coerceAtLeast(28f)
    val padTop = (h * 0.045f).coerceAtLeast(24f)
    val padBottom = (h * 0.065f).coerceAtLeast(36f)
    val contentWidth = (w - (padH * 2f)).toInt().coerceAtLeast(40)

    if (visualBitmap != null && bodyText.isBlank()) {
        val availH = (h - padTop - padBottom).coerceAtLeast(40f)
        val scale = min(contentWidth.toFloat() / visualBitmap.width.coerceAtLeast(1), availH / visualBitmap.height.coerceAtLeast(1))
        val drawW = visualBitmap.width * scale
        val drawH = visualBitmap.height * scale
        val left = (w - drawW) * 0.5f
        val top = padTop + (availH - drawH) * 0.5f
        val matrix = AndroidMatrix().apply {
            postScale(scale, scale)
            postTranslate(left, top)
        }
        val paint = AndroidPaint(AndroidPaint.FILTER_BITMAP_FLAG or AndroidPaint.ANTI_ALIAS_FLAG)
        canvas.drawBitmap(visualBitmap, matrix, paint)
    } else {
        var currentY = padTop
        val serifBold = Typeface.create(Typeface.SERIF, Typeface.BOLD)
        val serifRegular = Typeface.create(Typeface.SERIF, Typeface.NORMAL)

        if (showHeading && headingText.isNotBlank()) {
            val headingPaint = TextPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply {
                color = accentColorArgb
                textSize = fontSizePx * 1.32f
                typeface = serifBold
            }
            val headingLayout = StaticLayout.Builder
                .obtain(headingText, 0, headingText.length, headingPaint, contentWidth)
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .setLineSpacing(0f, 1.15f)
                .setIncludePad(false)
                .build()

            canvas.save()
            canvas.translate(padH, currentY)
            headingLayout.draw(canvas)
            canvas.restore()
            currentY += headingLayout.height + (fontSizePx * 1.1f)
        }

        if (bodyText.isNotBlank()) {
            val bodyPaint = TextPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply {
                color = textColorArgb
                textSize = fontSizePx
                typeface = serifRegular
            }

            // Identify clickable chapter link lines and color them with ForegroundColorSpan
            val spannable = SpannableString(bodyText)
            data class MatchedSpan(val start: Int, val end: Int, val targetIdx: Int, val label: String)
            val matchedSpans = mutableListOf<MatchedSpan>()

            var searchOffset = 0
            for (line in bodyText.split('\n')) {
                val lineStart = searchOffset
                val lineEnd = lineStart + line.length
                searchOffset = lineEnd + 1

                val targetChapIdx = matchLineToTargetChapterIndex(
                    rawLine = line,
                    chapters = allChapters,
                    currentChapterIndex = currentChapterIndex,
                    isTocChapter = isTocChapter
                )
                if (targetChapIdx != null && line.isNotBlank()) {
                    spannable.setSpan(
                        ForegroundColorSpan(linkColorArgb),
                        lineStart,
                        lineEnd,
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                    matchedSpans.add(MatchedSpan(lineStart, lineEnd, targetChapIdx, line.trim()))
                }
            }

            val bodyLayout = StaticLayout.Builder
                .obtain(spannable, 0, spannable.length, bodyPaint, contentWidth)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(0f, 1.44f)
                .setIncludePad(false)
                .build()

            val maxBodyHeight = (h - padBottom - currentY).toInt().coerceAtLeast(20)
            canvas.save()
            canvas.clipRect(padH, currentY, padH + contentWidth, currentY + maxBodyHeight)
            canvas.translate(padH, currentY)
            bodyLayout.draw(canvas)
            canvas.restore()

            // Compute exact canvas touch bounding boxes for each matched chapter link
            for (ms in matchedSpans) {
                val startLine = bodyLayout.getLineForOffset(ms.start)
                val endLine = bodyLayout.getLineForOffset(ms.end.coerceAtLeast(ms.start))
                val topPx = currentY + bodyLayout.getLineTop(startLine) - 8f
                val bottomPx = currentY + bodyLayout.getLineBottom(endLine) + 8f
                if (topPx < currentY + maxBodyHeight) {
                    hotspots.add(
                        PageLinkHotspot(
                            rect = RectF(padH - 12f, topPx, padH + contentWidth + 12f, bottomPx),
                            targetChapterIndex = ms.targetIdx,
                            label = ms.label
                        )
                    )
                }
            }
        }
    }

    if (pageFooterLabel.isNotBlank()) {
        val footerPaint = TextPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply {
            color = textColorArgb
            alpha = 150
            textSize = (fontSizePx * 0.68f).coerceIn(22f, 34f)
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        }
        canvas.drawText(pageFooterLabel, padH, h - (padBottom * 0.35f), footerPaint)
    }

    return RenderedBookPage(bitmap = bmp, hotspots = hotspots)
}

// Draws a 3D cylindrically-warped page mesh using Android's native drawBitmapMesh.
private fun draw3DCurledPageMesh(
    nativeCanvas: AndroidCanvas,
    pageBitmap: Bitmap,
    width: Float,
    height: Float,
    curlProgress: Float,
    touchYNorm: Float,
    bgColorArgb: Int
) {
    val p = curlProgress.coerceIn(0f, 1f)
    if (p <= 0.002f) {
        nativeCanvas.drawBitmap(pageBitmap, 0f, 0f, null)
        return
    }

    val meshW = 28
    val meshH = 22
    val vertCount = (meshW + 1) * (meshH + 1)
    val frontVerts = FloatArray(vertCount * 2)
    val frontColors = IntArray(vertCount)
    val backVerts = FloatArray(vertCount * 2)
    val backColors = IntArray(vertCount)

    val radius = (width * (0.14f + 0.10f * sin(p * PI).toFloat())).coerceAtLeast(32f)
    val baseFoldX = width * (1f - p * 1.08f)
    val tiltFactor = (touchYNorm - 0.5f) * 0.28f * sin(p * PI).toFloat()

    var maxTurnedX = baseFoldX
    var minSpineX = baseFoldX

    var idx = 0
    for (iy in 0..meshH) {
        val yn = iy.toFloat() / meshH.toFloat()
        val y0 = yn * height
        val archBow = sin(yn * PI).toFloat() * (width * 0.045f * sin(p * PI).toFloat())
        val localFoldX = baseFoldX + ((yn - 0.5f) * width * tiltFactor) - archBow
        if (localFoldX < minSpineX) minSpineX = localFoldX

        for (ix in 0..meshW) {
            val xn = ix.toFloat() / meshW.toFloat()
            val x0 = xn * width

            if (x0 <= localFoldX) {
                frontVerts[idx * 2] = x0
                frontVerts[idx * 2 + 1] = y0
                frontColors[idx] = 0xFFFFFFFF.toInt()

                backVerts[idx * 2] = localFoldX
                backVerts[idx * 2 + 1] = y0
                backColors[idx] = 0x00FFFFFF
            } else {
                val dist = x0 - localFoldX
                val theta = dist / radius

                val frontTheta = theta.coerceAtMost((PI * 0.5).toFloat())
                val fx = localFoldX + radius * sin(frontTheta)
                val fz = radius * (1f - cos(frontTheta))
                val perspectiveScale = 1f + (fz / (width * 1.45f)) * 0.26f
                val anchorY = height * touchYNorm.coerceIn(0.2f, 0.8f)
                val fy = anchorY + (y0 - anchorY) / perspectiveScale - (fz * 0.11f * (yn - 0.5f))

                frontVerts[idx * 2] = fx
                frontVerts[idx * 2 + 1] = fy

                val shade = (1f - 0.34f * sin(frontTheta)).coerceIn(0.55f, 1f)
                val channel = (shade * 255).toInt().coerceIn(0, 255)
                frontColors[idx] = (0xFF shl 24) or (channel shl 16) or (channel shl 8) or channel

                if (theta > (PI * 0.5).toFloat()) {
                    val backTheta = theta.coerceAtMost(PI.toFloat() * 1.12f)
                    val bx = localFoldX + radius * sin(backTheta)
                    val bz = radius * (1f - cos(backTheta))
                    val bScale = 1f + (bz / (width * 1.25f)) * 0.34f
                    val by = anchorY + (y0 - anchorY) / bScale - (bz * 0.14f * (yn - 0.5f))

                    backVerts[idx * 2] = bx
                    backVerts[idx * 2 + 1] = by
                    if (bx + radius > maxTurnedX) maxTurnedX = bx + radius

                    val backLight = (0.72f + 0.28f * sin(backTheta - (PI * 0.5).toFloat())).coerceIn(0.55f, 1f)
                    val bChan = (backLight * 255).toInt().coerceIn(0, 255)
                    backColors[idx] = (0xFF shl 24) or (bChan shl 16) or (bChan shl 8) or bChan
                } else {
                    val edgeX = localFoldX + radius
                    val edgeZ = radius
                    val eScale = 1f + (edgeZ / (width * 1.45f)) * 0.26f
                    val edgeY = anchorY + (y0 - anchorY) / eScale - (edgeZ * 0.11f * (yn - 0.5f))
                    backVerts[idx * 2] = edgeX
                    backVerts[idx * 2 + 1] = edgeY
                    backColors[idx] = 0x00FFFFFF
                }
            }
            idx++
        }
    }

    val shadowLeft = (minSpineX - 12f).coerceAtLeast(0f)
    val shadowRight = (maxTurnedX + radius * 1.45f).coerceAtMost(width)
    if (shadowRight > shadowLeft + 4f) {
        val shadowPaint = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                shadowLeft,
                0f,
                shadowRight,
                0f,
                intArrayOf(
                    0x00000000,
                    0x88000000.toInt(),
                    0x44000000,
                    0x00000000
                ),
                floatArrayOf(0f, 0.22f, 0.65f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        nativeCanvas.drawRect(shadowLeft, 0f, shadowRight, height, shadowPaint)
    }

    nativeCanvas.drawBitmapMesh(pageBitmap, meshW, meshH, frontVerts, 0, frontColors, 0, null)

    val backPaperPaint = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply {
        color = bgColorArgb
        alpha = 235
    }
    val flapOutline = AndroidPath()
    var hasFlap = false
    for (iy in 0..meshH) {
        val rowStart = iy * (meshW + 1)
        val xEdge = frontVerts[(rowStart + meshW) * 2]
        val yEdge = frontVerts[(rowStart + meshW) * 2 + 1]
        if (iy == 0) flapOutline.moveTo(xEdge, yEdge) else flapOutline.lineTo(xEdge, yEdge)
    }
    for (iy in meshH downTo 0) {
        val rowStart = iy * (meshW + 1)
        val bx = backVerts[(rowStart + meshW) * 2]
        val by = backVerts[(rowStart + meshW) * 2 + 1]
        if (abs(bx - frontVerts[(rowStart + meshW) * 2]) > 2f) hasFlap = true
        flapOutline.lineTo(bx, by)
    }
    flapOutline.close()

    if (hasFlap) {
        nativeCanvas.drawPath(flapOutline, backPaperPaint)
        val bleedPaint = AndroidPaint(AndroidPaint.FILTER_BITMAP_FLAG).apply {
            alpha = 95
        }
        nativeCanvas.drawBitmapMesh(pageBitmap, meshW, meshH, backVerts, 0, backColors, 0, bleedPaint)
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
    val resolvedChapters = remember(chapters, ebook.title) {
        resolveSmartChapterTitles(chapters, ebook.title)
    }
    val currentChapter = resolvedChapters.getOrNull(currentChapterIndex)
    val isVisualPageMode = (fmtUpper in VISUAL_PAGE_EBOOK_FORMATS) && (currentChapter?.bodyText.isNullOrBlank())

    // Detect if the active chapter is a Table of Contents / Contents chapter
    val isTocChapter = remember(currentChapter, resolvedChapters) {
        val t = currentChapter?.title?.trim() ?: ""
        t.equals("Contents", ignoreCase = true) ||
            t.equals("Table of Contents", ignoreCase = true) ||
            (currentChapterIndex <= 4 && (currentChapter?.bodyText?.lines()?.count {
                it.trim().startsWith("Chapter ", ignoreCase = true)
            } ?: 0) >= 3)
    }

    val bgColor = when (readerTheme) {
        "SEPIA" -> Color(0xFFF4ECD8)
        "LIGHT" -> Color(0xFFFAFAFA)
        else -> Color(0xFF060E0A)
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
    // Google Play Books style chapter link color
    val linkColor = when (readerTheme) {
        "SEPIA" -> Color(0xFF1B5E91)
        "LIGHT" -> Color(0xFF1558D6)
        else -> Color(0xFF58A6FF)
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
    val curlAnim = remember { Animatable(0f) }
    var touchYNorm by remember { mutableFloatStateOf(0.55f) }

    LaunchedEffect(currentChapterIndex, ebook.id, isVisualPageMode, readerTheme) {
        if (isVisualPageMode) {
            isRenderingVisualPage = true
            renderedPageBitmap = repository.renderPdfPageBitmap(ebook, currentChapterIndex, 1400, readerTheme)
            isRenderingVisualPage = false
            if (currentChapterIndex + 1 < resolvedChapters.size) {
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

        HorizontalDivider(color = accentColor.copy(alpha = 0.18f))

        // Main Interactive 3D Mesh Page-Curl Reading Area
        BoxWithConstraints(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(bgColor)
        ) {
            val density = LocalDensity.current
            val canvasWidthPx = with(density) { maxWidth.toPx().toInt().coerceAtLeast(100) }
            val canvasHeightPx = with(density) { (maxHeight - 48.dp).toPx().toInt().coerceAtLeast(100) }
            val fontSizePx = with(density) { fontSizeSp.sp.toPx() }

            val availWidthDp = maxWidth.value
            val availHeightDp = (maxHeight - 48.dp).value

            val charsPerLine = ((availWidthDp - 48f) / (fontSizeSp * 0.56f)).toInt().coerceIn(26, 85)
            val lineHeightSp = fontSizeSp * 1.46f
            val linesPerPage = ((availHeightDp - 64f) / (lineHeightSp * 1.08f)).toInt().coerceIn(8, 38)

            val subPages = remember(currentChapter?.bodyText, charsPerLine, linesPerPage, isVisualPageMode) {
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

            val canGoNext = (subPageIndex + 1 < subPages.size) || (currentChapterIndex + 1 < resolvedChapters.size)
            val canGoPrev = (subPageIndex > 0) || (currentChapterIndex > 0)

            fun commitNextPage() {
                if (subPageIndex + 1 < subPages.size) {
                    subPageIndex++
                } else if (currentChapterIndex + 1 < resolvedChapters.size) {
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

            fun animatePageTurnForward() {
                if (!canGoNext || curlAnim.isRunning) return
                coroutineScope.launch {
                    touchYNorm = 0.62f
                    curlAnim.snapTo(0.03f)
                    curlAnim.animateTo(1f, tween(durationMillis = 420, easing = FastOutSlowInEasing))
                    commitNextPage()
                    curlAnim.snapTo(0f)
                }
            }

            fun animatePageTurnBackward() {
                if (!canGoPrev || curlAnim.isRunning) return
                coroutineScope.launch {
                    touchYNorm = 0.62f
                    curlAnim.snapTo(-0.03f)
                    curlAnim.animateTo(-1f, tween(durationMillis = 420, easing = FastOutSlowInEasing))
                    commitPrevPage()
                    curlAnim.snapTo(0f)
                }
            }

            fun animateJumpToChapter(targetIdx: Int) {
                if (targetIdx !in resolvedChapters.indices || curlAnim.isRunning) return
                coroutineScope.launch {
                    touchYNorm = 0.55f
                    if (targetIdx >= currentChapterIndex) {
                        curlAnim.snapTo(0.04f)
                        curlAnim.animateTo(1f, tween(durationMillis = 380, easing = FastOutSlowInEasing))
                    } else {
                        curlAnim.snapTo(-0.04f)
                        curlAnim.animateTo(-1f, tween(durationMillis = 380, easing = FastOutSlowInEasing))
                    }
                    startAtLastSubPage = false
                    subPageIndex = 0
                    onSelectChapter(targetIdx)
                    curlAnim.snapTo(0f)
                }
            }

            val currSubPageText = subPages.getOrElse(subPageIndex) { "" }
            val nextPreviewText = remember(subPageIndex, subPages, currentChapterIndex, resolvedChapters) {
                if (subPageIndex + 1 < subPages.size) {
                    subPages[subPageIndex + 1]
                } else if (currentChapterIndex + 1 < resolvedChapters.size) {
                    val nextChap = resolvedChapters[currentChapterIndex + 1]
                    paginateChapterText(nextChap.bodyText, charsPerLine, linesPerPage).firstOrNull() ?: ""
                } else ""
            }
            val prevPreviewText = remember(subPageIndex, subPages, currentChapterIndex, resolvedChapters) {
                if (subPageIndex > 0) {
                    subPages[subPageIndex - 1]
                } else if (currentChapterIndex > 0) {
                    val prevChap = resolvedChapters[currentChapterIndex - 1]
                    paginateChapterText(prevChap.bodyText, charsPerLine, linesPerPage).lastOrNull() ?: ""
                } else ""
            }

            val bgArgb = bgColor.toArgb()
            val textArgb = textColor.toArgb()
            val accentArgb = accentColor.toArgb()
            val linkArgb = linkColor.toArgb()

            val currentFooter = if (isVisualPageMode) {
                "Page ${currentChapterIndex + 1} of ${resolvedChapters.size}"
            } else {
                "Page ${subPageIndex + 1} of ${subPages.size}  •  Ch ${currentChapterIndex + 1}/${resolvedChapters.size}"
            }

            val currentRenderedPage = remember(
                canvasWidthPx, canvasHeightPx, bgArgb, textArgb, accentArgb, linkArgb,
                currentChapter?.title, subPageIndex, currSubPageText, fontSizePx, renderedPageBitmap, currentFooter,
                resolvedChapters, currentChapterIndex, isTocChapter
            ) {
                renderBookPageToBitmap(
                    widthPx = canvasWidthPx,
                    heightPx = canvasHeightPx,
                    bgColorArgb = bgArgb,
                    textColorArgb = textArgb,
                    accentColorArgb = accentArgb,
                    linkColorArgb = linkArgb,
                    headingText = currentChapter?.title ?: "",
                    showHeading = subPageIndex == 0,
                    bodyText = currSubPageText,
                    fontSizePx = fontSizePx,
                    visualBitmap = if (isVisualPageMode) renderedPageBitmap else null,
                    pageFooterLabel = currentFooter,
                    allChapters = resolvedChapters,
                    currentChapterIndex = currentChapterIndex,
                    isTocChapter = isTocChapter
                )
            }

            val nextRenderedPage = remember(
                canvasWidthPx, canvasHeightPx, bgArgb, textArgb, accentArgb, linkArgb,
                currentChapterIndex, subPageIndex, nextPreviewText, fontSizePx, nextPageBitmap, resolvedChapters, isTocChapter
            ) {
                val isNextNewChapter = subPageIndex + 1 >= subPages.size
                val nextHeading = if (isNextNewChapter && currentChapterIndex + 1 < resolvedChapters.size) {
                    resolvedChapters[currentChapterIndex + 1].title
                } else ""
                renderBookPageToBitmap(
                    widthPx = canvasWidthPx,
                    heightPx = canvasHeightPx,
                    bgColorArgb = bgArgb,
                    textColorArgb = textArgb,
                    accentColorArgb = accentArgb,
                    linkColorArgb = linkArgb,
                    headingText = nextHeading,
                    showHeading = isNextNewChapter,
                    bodyText = nextPreviewText,
                    fontSizePx = fontSizePx,
                    visualBitmap = if (isVisualPageMode) (nextPageBitmap ?: renderedPageBitmap) else null,
                    pageFooterLabel = "",
                    allChapters = resolvedChapters,
                    currentChapterIndex = if (isNextNewChapter) currentChapterIndex + 1 else currentChapterIndex,
                    isTocChapter = isTocChapter && !isNextNewChapter
                )
            }

            val prevRenderedPage = remember(
                canvasWidthPx, canvasHeightPx, bgArgb, textArgb, accentArgb, linkArgb,
                currentChapterIndex, subPageIndex, prevPreviewText, fontSizePx, prevPageBitmap, resolvedChapters, isTocChapter
            ) {
                val isPrevFirstSubPage = subPageIndex == 1
                renderBookPageToBitmap(
                    widthPx = canvasWidthPx,
                    heightPx = canvasHeightPx,
                    bgColorArgb = bgArgb,
                    textColorArgb = textArgb,
                    accentColorArgb = accentArgb,
                    linkColorArgb = linkArgb,
                    headingText = currentChapter?.title ?: "",
                    showHeading = isPrevFirstSubPage,
                    bodyText = prevPreviewText,
                    fontSizePx = fontSizePx,
                    visualBitmap = if (isVisualPageMode) (prevPageBitmap ?: renderedPageBitmap) else null,
                    pageFooterLabel = "",
                    allChapters = resolvedChapters,
                    currentChapterIndex = currentChapterIndex,
                    isTocChapter = isTocChapter
                )
            }

            if (isLoading || (isVisualPageMode && isRenderingVisualPage && renderedPageBitmap == null)) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = accentColor)
                }
            } else {
                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(with(density) { canvasHeightPx.toDp() })
                        .pointerInput(currentChapterIndex, subPageIndex, subPages.size, resolvedChapters.size, currentRenderedPage.hotspots) {
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                val w = size.width.toFloat().coerceAtLeast(1f)
                                val h = size.height.toFloat().coerceAtLeast(1f)
                                val startX = down.position.x
                                val startY = down.position.y
                                touchYNorm = (startY / h).coerceIn(0.1f, 0.9f)

                                var totalDragX = 0f
                                var totalDragY = 0f
                                var dragDirection = 0
                                var lastDx = 0f

                                while (true) {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.firstOrNull() ?: break
                                    if (!change.pressed) break

                                    val delta = change.positionChange()
                                    val dx = delta.x
                                    val dy = delta.y
                                    if (abs(dx) > 0.1f) lastDx = dx
                                    totalDragX += dx
                                    totalDragY += dy
                                    touchYNorm = (change.position.y / h).coerceIn(0.1f, 0.9f)

                                    if (dragDirection == 0 && abs(totalDragX) > 10f) {
                                        dragDirection = if (totalDragX < 0f && canGoNext) {
                                            1
                                        } else if (totalDragX > 0f && canGoPrev) {
                                            -1
                                        } else {
                                            0
                                        }
                                    }

                                    if (dragDirection == 1 && canGoNext) {
                                        change.consume()
                                        val fingerProgress = ((w - change.position.x) / (w * 0.92f)).coerceIn(0.01f, 1f)
                                        val dragProgress = (-totalDragX / (w * 0.85f)).coerceIn(0.01f, 1f)
                                        val combined = if (startX > w * 0.65f) max(fingerProgress, dragProgress) else dragProgress
                                        coroutineScope.launch { curlAnim.snapTo(combined) }
                                    } else if (dragDirection == -1 && canGoPrev) {
                                        change.consume()
                                        val dragProgress = (totalDragX / (w * 0.85f)).coerceIn(0.01f, 1f)
                                        coroutineScope.launch { curlAnim.snapTo(-dragProgress) }
                                    }
                                }

                                // Finger released: Check if user tapped an in-book Chapter Link first!
                                if (dragDirection == 0 && abs(totalDragX) < 14f && abs(totalDragY) < 18f) {
                                    val tappedHotspot = currentRenderedPage.hotspots.firstOrNull { spot ->
                                        spot.rect.contains(startX, startY)
                                    }
                                    if (tappedHotspot != null) {
                                        animateJumpToChapter(tappedHotspot.targetChapterIndex)
                                    } else if (startX > w * 0.72f && canGoNext) {
                                        animatePageTurnForward()
                                    } else if (startX < w * 0.28f && canGoPrev) {
                                        animatePageTurnBackward()
                                    }
                                } else {
                                    val c = curlAnim.value
                                    coroutineScope.launch {
                                        if (c > 0f) {
                                            val shouldComplete = c > 0.22f || lastDx < -6f
                                            if (shouldComplete && canGoNext) {
                                                curlAnim.animateTo(1f, tween(280, easing = FastOutSlowInEasing))
                                                commitNextPage()
                                                curlAnim.snapTo(0f)
                                            } else {
                                                curlAnim.animateTo(0f, tween(240, easing = FastOutSlowInEasing))
                                            }
                                        } else if (c < 0f) {
                                            val shouldComplete = abs(c) > 0.22f || lastDx > 6f
                                            if (shouldComplete && canGoPrev) {
                                                curlAnim.animateTo(-1f, tween(280, easing = FastOutSlowInEasing))
                                                commitPrevPage()
                                                curlAnim.snapTo(0f)
                                            } else {
                                                curlAnim.animateTo(0f, tween(240, easing = FastOutSlowInEasing))
                                            }
                                        }
                                    }
                                }
                            }
                        }
                ) {
                    val c = curlAnim.value
                    drawIntoCanvas { composeCanvas ->
                        val native = composeCanvas.nativeCanvas
                        val w = size.width
                        val h = size.height

                        when {
                            abs(c) < 0.003f -> {
                                native.drawBitmap(currentRenderedPage.bitmap, 0f, 0f, null)
                            }
                            c > 0f -> {
                                native.drawBitmap(nextRenderedPage.bitmap, 0f, 0f, null)
                                draw3DCurledPageMesh(
                                    nativeCanvas = native,
                                    pageBitmap = currentRenderedPage.bitmap,
                                    width = w,
                                    height = h,
                                    curlProgress = c,
                                    touchYNorm = touchYNorm,
                                    bgColorArgb = bgArgb
                                )
                            }
                            else -> {
                                native.drawBitmap(currentRenderedPage.bitmap, 0f, 0f, null)
                                val uncurlProgress = (1f - abs(c)).coerceIn(0.01f, 0.99f)
                                draw3DCurledPageMesh(
                                    nativeCanvas = native,
                                    pageBitmap = prevRenderedPage.bitmap,
                                    width = w,
                                    height = h,
                                    curlProgress = uncurlProgress,
                                    touchYNorm = touchYNorm,
                                    bgColorArgb = bgArgb
                                )
                            }
                        }
                    }
                }
            }

            // Bottom Navigation Bar
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(bgColor)
            ) {
                HorizontalDivider(color = accentColor.copy(alpha = 0.18f))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(
                        onClick = { animatePageTurnBackward() },
                        enabled = canGoPrev
                    ) {
                        Icon(Icons.Default.ChevronLeft, contentDescription = null, tint = if (canGoPrev) accentColor else textColor.copy(alpha = 0.3f))
                        Text("Prev", color = if (canGoPrev) accentColor else textColor.copy(alpha = 0.3f))
                    }

                    val pct = if (resolvedChapters.isNotEmpty()) {
                        (((currentChapterIndex + 1).toFloat() / resolvedChapters.size) * 100).toInt().coerceIn(1, 100)
                    } else 0

                    Text(
                        text = "$currentFooter ($pct%)",
                        fontSize = 12.sp,
                        color = textColor.copy(alpha = 0.85f),
                        modifier = Modifier.clickable { showTocDialog = true }
                    )

                    TextButton(
                        onClick = { animatePageTurnForward() },
                        enabled = canGoNext
                    ) {
                        Text("Next", color = if (canGoNext) accentColor else textColor.copy(alpha = 0.3f))
                        Icon(Icons.Default.ChevronRight, contentDescription = null, tint = if (canGoNext) accentColor else textColor.copy(alpha = 0.3f))
                    }
                }
            }
        }
    }

    if (showTocDialog) {
        val listHeader = if (isVisualPageMode) "Pages (${resolvedChapters.size})" else "Chapters (${resolvedChapters.size})"
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
                    resolvedChapters.forEachIndexed { idx, chap ->
                        val isCurr = idx == currentChapterIndex
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    subPageIndex = 0
                                    onSelectChapter(idx)
                                    showTocDialog = false
                                }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = if (isVisualPageMode) chap.title else "${idx + 1}. ${chap.title}",
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
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedButton(onClick = { onUpdateFontSize(fontSizeSp - 2f) }) { Text("A-") }
                        Text("${fontSizeSp.toInt()} pt", fontWeight = FontWeight.Bold)
                        OutlinedButton(onClick = { onUpdateFontSize(fontSizeSp + 2f) }) { Text("A+") }
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

                    if (fmtUpper == "PDF") {
                        HorizontalDivider()
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Reflow PDF like ePub", fontWeight = FontWeight.Bold)
                                Text(
                                    "Extract text for adjustable font sizes, or switch off for auto-cropped page view",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = !pdfOriginalPageMode,
                                onCheckedChange = { reflowEnabled ->
                                    pdfOriginalPageMode = !reflowEnabled
                                    repository.getPreferences().edit()
                                        .putBoolean("pdf_original_page_mode", pdfOriginalPageMode)
                                        .apply()
                                    onReloadEbook()
                                }
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