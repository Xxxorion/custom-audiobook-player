package com.example.audiobook

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Color as AndroidColor
import android.graphics.LinearGradient
import android.graphics.Matrix as AndroidMatrix
import android.graphics.Paint as AndroidPaint
import android.graphics.Path as AndroidPath
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.media.AudioAttributes
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.text.Layout
import android.text.Spannable
import android.text.SpannableString
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.widget.Toast
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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
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

@Composable
fun SymmetricalSkipIcon(
    seconds: Int,
    isForward: Boolean,
    tint: Color = MaterialTheme.colorScheme.onSurface,
    iconSize: Dp = 40.dp,
    fontSize: TextUnit = 10.5.sp
) {
    Box(
        modifier = Modifier.size(iconSize),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val strokePx = 2.4.dp.toPx()
            val pad = strokePx * 1.4f
            val arcDiameter = size.minDimension - (pad * 2f)
            val topLeft = Offset(pad, pad)
            val arcSize = Size(arcDiameter, arcDiameter)

            val startAngle = if (isForward) -65f else -115f
            val sweepAngle = if (isForward) 300f else -300f

            drawArc(
                color = tint,
                startAngle = startAngle,
                sweepAngle = sweepAngle,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = strokePx, cap = StrokeCap.Round)
            )

            val cx = size.width / 2f
            val topY = pad
            val arrowW = 5.5.dp.toPx()
            val arrowH = 4.8.dp.toPx()
            val dir = if (isForward) 1f else -1f
            val tipX = cx + (dir * 2.5.dp.toPx())

            val headPath = Path().apply {
                moveTo(tipX, topY)
                lineTo(tipX - (dir * arrowW), topY - arrowH)
                lineTo(tipX - (dir * arrowW), topY + arrowH)
                close()
            }
            drawPath(path = headPath, color = tint)
        }
        Text(
            text = "$seconds",
            color = tint,
            fontSize = fontSize,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 2.dp)
        )
    }
}

data class ReadingStreakSnapshot(
    val streakDays: Int,
    val bestStreakDays: Int,
    val todayMinutes: Int,
    val todayPages: Int,
    val totalPagesRead: Int,
    val totalEbookMinutes: Int
)

private fun currentDayKey(cal: Calendar = Calendar.getInstance()): String {
    return String.format(
        Locale.US,
        "%04d-%02d-%02d",
        cal.get(Calendar.YEAR),
        cal.get(Calendar.MONTH) + 1,
        cal.get(Calendar.DAY_OF_MONTH)
    )
}

private fun yesterdayDayKey(): String {
    val cal = Calendar.getInstance()
    cal.add(Calendar.DAY_OF_YEAR, -1)
    return currentDayKey(cal)
}

fun loadReadingStreakSnapshot(prefs: SharedPreferences): ReadingStreakSnapshot {
    val today = currentDayKey()
    val yesterday = yesterdayDayKey()
    val lastDay = prefs.getString("stats_last_read_day", "") ?: ""

    var streak = prefs.getInt("stats_current_streak_days", 0)
    if (lastDay.isNotEmpty() && lastDay != today && lastDay != yesterday) {
        streak = 0
    }
    val best = prefs.getInt("stats_best_streak_days", streak).coerceAtLeast(streak)
    val todayMins = if (lastDay == today) prefs.getInt("stats_today_minutes", 0) else 0
    val todayPgs = if (lastDay == today) prefs.getInt("stats_today_pages", 0) else 0
    val totalPgs = prefs.getInt("stats_total_pages", 0)
    val totalMins = prefs.getInt("stats_total_ebook_minutes", 0)

    return ReadingStreakSnapshot(
        streakDays = streak,
        bestStreakDays = best,
        todayMinutes = todayMins,
        todayPages = todayPgs,
        totalPagesRead = totalPgs,
        totalEbookMinutes = totalMins
    )
}

fun recordReadingActivity(
    prefs: SharedPreferences,
    pagesAdded: Int = 0,
    minutesAdded: Int = 0
): ReadingStreakSnapshot {
    val today = currentDayKey()
    val yesterday = yesterdayDayKey()
    val lastDay = prefs.getString("stats_last_read_day", "") ?: ""

    var streak = prefs.getInt("stats_current_streak_days", 0)
    var todayMins = if (lastDay == today) prefs.getInt("stats_today_minutes", 0) else 0
    var todayPgs = if (lastDay == today) prefs.getInt("stats_today_pages", 0) else 0

    if (lastDay != today) {
        streak = if (lastDay == yesterday) streak + 1 else 1
    } else if (streak <= 0) {
        streak = 1
    }

    todayMins = (todayMins + minutesAdded).coerceAtLeast(0)
    todayPgs = (todayPgs + pagesAdded).coerceAtLeast(0)
    val totalPgs = (prefs.getInt("stats_total_pages", 0) + pagesAdded).coerceAtLeast(0)
    val totalMins = (prefs.getInt("stats_total_ebook_minutes", 0) + minutesAdded).coerceAtLeast(0)
    val best = max(prefs.getInt("stats_best_streak_days", 1), streak)

    prefs.edit()
        .putString("stats_last_read_day", today)
        .putInt("stats_current_streak_days", streak)
        .putInt("stats_best_streak_days", best)
        .putInt("stats_today_minutes", todayMins)
        .putInt("stats_today_pages", todayPgs)
        .putInt("stats_total_pages", totalPgs)
        .putInt("stats_total_ebook_minutes", totalMins)
        .apply()

    return ReadingStreakSnapshot(
        streakDays = streak,
        bestStreakDays = best,
        todayMinutes = todayMins,
        todayPages = todayPgs,
        totalPagesRead = totalPgs,
        totalEbookMinutes = totalMins
    )
}

@Composable
fun ReadingStreakStatsCard(
    snapshot: ReadingStreakSnapshot,
    totalAudioListenMs: Long,
    completedAudioBooks: Int,
    onClick: () -> Unit
) {
    val totalCombinedMinutes = snapshot.totalEbookMinutes + (totalAudioListenMs / 60_000L).toInt()
    val activeStreak = snapshot.streakDays.coerceAtLeast(1)

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f),
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.25f),
                shape = RoundedCornerShape(18.dp)
            )
            .clickable { onClick() }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.LocalFireDepartment,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp)
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(
                        text = "$activeStreak Day Reading Streak",
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Today: ${snapshot.todayMinutes}m • ${snapshot.todayPages} pgs  |  Total: ${totalCombinedMinutes}m",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = "Reading Insights",
                tint = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
fun ReadingInsightsDialog(
    snapshot: ReadingStreakSnapshot,
    totalAudioListenMs: Long,
    completedAudioBooks: Int,
    ebooksCount: Int,
    onDismiss: () -> Unit
) {
    val audioMins = (totalAudioListenMs / 60_000L).toInt()
    val combinedMins = snapshot.totalEbookMinutes + audioMins

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.LocalFireDepartment, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text("Reading Streak & Insights", fontWeight = FontWeight.Bold)
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "${snapshot.streakDays.coerceAtLeast(1)} Days",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text("Current Streak", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "${snapshot.bestStreakDays.coerceAtLeast(1)} Days",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text("Best Streak", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }

                HorizontalDivider()

                Text("Today's Activity", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                Text("• Reading Time Today: ${snapshot.todayMinutes} min")
                Text("• Pages Turned Today: ${snapshot.todayPages} pages")

                HorizontalDivider()

                Text("All-Time Library Totals", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                Text("• Combined Reading & Listening: $combinedMins min")
                Text("• Total eBook Pages Turned: ${snapshot.totalPagesRead} pages")
                Text("• Audiobook Listen Time: ${formatTime(totalAudioListenMs)}")
                Text("• Completed Audiobooks: $completedAudioBooks  •  eBooks on Shelf: $ebooksCount")
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Done", fontWeight = FontWeight.Bold)
            }
        }
    )
}

private fun extractSentencesFromPage(leftText: String, rightText: String = ""): List<String> {
    val combined = if (rightText.isNotBlank()) "$leftText\n$rightText" else leftText
    if (combined.isBlank()) return emptyList()
    return combined
        .split(Regex("(?<=[.!?…])\\s+|\\n+"))
        .map { it.trim() }
        .filter { it.length >= 2 }
}

data class EbookHighlight(
    val id: String = UUID.randomUUID().toString(),
    val ebookId: String,
    val bookTitle: String,
    val bookAuthor: String,
    val chapterIndex: Int,
    val chapterTitle: String,
    val text: String,
    val colorHex: String = "#F5C542",
    val note: String = "",
    val timestampStr: String = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault()).format(Date())
)

val HIGHLIGHT_COLOR_PALETTE = listOf(
    "#F5C542" to "Amber Gold",
    "#52B788" to "Sage Mint",
    "#4EA8DE" to "Sky Blue",
    "#F28482" to "Rose Coral"
)

data class QuoteCardTemplate(
    val id: String,
    val name: String,
    val bgTopArgb: Int,
    val bgBottomArgb: Int,
    val textArgb: Int,
    val accentArgb: Int,
    val useSerif: Boolean
)

val QUOTE_CARD_TEMPLATES = listOf(
    QuoteCardTemplate(
        id = "FOREST",
        name = "Fablewood",
        bgTopArgb = 0xFF0C1A14.toInt(),
        bgBottomArgb = 0xFF183528.toInt(),
        textArgb = 0xFFF2F6F4.toInt(),
        accentArgb = 0xFFD4AF37.toInt(),
        useSerif = true
    ),
    QuoteCardTemplate(
        id = "EDITORIAL",
        name = "Editorial",
        bgTopArgb = 0xFFF6EFE2.toInt(),
        bgBottomArgb = 0xFFE8DEC8.toInt(),
        textArgb = 0xFF2B221E.toInt(),
        accentArgb = 0xFF8B5A2B.toInt(),
        useSerif = true
    ),
    QuoteCardTemplate(
        id = "MIDNIGHT",
        name = "Midnight",
        bgTopArgb = 0xFF08090C.toInt(),
        bgBottomArgb = 0xFF161B22.toInt(),
        textArgb = 0xFFE6EDF3.toInt(),
        accentArgb = 0xFF58A6FF.toInt(),
        useSerif = false
    ),
    QuoteCardTemplate(
        id = "SUNSET",
        name = "Terracotta",
        bgTopArgb = 0xFF3D1E18.toInt(),
        bgBottomArgb = 0xFF1F110E.toInt(),
        textArgb = 0xFFFDF0E6.toInt(),
        accentArgb = 0xFFF4A261.toInt(),
        useSerif = true
    )
)

// Fix 13: Read & write eBook highlights directly via library.json in AudiobookRepository
private fun loadAllEbookHighlights(repository: AudiobookRepository): List<EbookHighlight> {
    val list = mutableListOf<EbookHighlight>()
    try {
        val arr = repository.loadHighlightsJsonArray()
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            list.add(
                EbookHighlight(
                    id = obj.optString("id", UUID.randomUUID().toString()),
                    ebookId = obj.optString("ebookId", ""),
                    bookTitle = obj.optString("bookTitle", ""),
                    bookAuthor = obj.optString("bookAuthor", ""),
                    chapterIndex = obj.optInt("chapterIndex", 0),
                    chapterTitle = obj.optString("chapterTitle", ""),
                    text = obj.optString("text", ""),
                    colorHex = obj.optString("colorHex", "#F5C542"),
                    note = obj.optString("note", ""),
                    timestampStr = obj.optString("timestampStr", "")
                )
            )
        }
    } catch (_: Throwable) {}
    return list
}

private fun saveAllEbookHighlights(repository: AudiobookRepository, list: List<EbookHighlight>) {
    try {
        val arr = JSONArray()
        for (item in list) {
            arr.put(
                JSONObject().apply {
                    put("id", item.id)
                    put("ebookId", item.ebookId)
                    put("bookTitle", item.bookTitle)
                    put("bookAuthor", item.bookAuthor)
                    put("chapterIndex", item.chapterIndex)
                    put("chapterTitle", item.chapterTitle)
                    put("text", item.text)
                    put("colorHex", item.colorHex)
                    put("note", item.note)
                    put("timestampStr", item.timestampStr)
                }
            )
        }
        repository.saveHighlightsJsonArray(arr)
    } catch (_: Throwable) {}
}

private fun renderShareableQuoteCardBitmap(
    quoteText: String,
    bookTitle: String,
    bookAuthor: String,
    template: QuoteCardTemplate
): Bitmap {
    val w = 1080
    val h = 1350
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = AndroidCanvas(bmp)

    val bgPaint = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply {
        shader = LinearGradient(
            0f, 0f, w.toFloat(), h.toFloat(),
            template.bgTopArgb, template.bgBottomArgb,
            Shader.TileMode.CLAMP
        )
    }
    canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), bgPaint)

    val borderPaint = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply {
        color = template.accentArgb
        alpha = 90
        style = AndroidPaint.Style.STROKE
        strokeWidth = 3f
    }
    canvas.drawRoundRect(RectF(56f, 56f, w - 56f, h - 56f), 36f, 36f, borderPaint)

    val quoteMarkPaint = TextPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply {
        color = template.accentArgb
        alpha = 190
        textSize = 180f
        typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
    }
    canvas.drawText("“", 110f, 270f, quoteMarkPaint)

    val cleanQuote = quoteText.trim().take(520)
    val dynamicFontSize = when {
        cleanQuote.length < 110 -> 54f
        cleanQuote.length < 220 -> 46f
        cleanQuote.length < 340 -> 39f
        else -> 34f
    }
    val bodyTypeface = if (template.useSerif) {
        Typeface.create(Typeface.SERIF, Typeface.ITALIC)
    } else {
        Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
    }
    val bodyPaint = TextPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply {
        color = template.textArgb
        textSize = dynamicFontSize
        typeface = bodyTypeface
    }
    val contentWidth = w - 240
    val quoteLayout = StaticLayout.Builder
        .obtain(cleanQuote, 0, cleanQuote.length, bodyPaint, contentWidth)
        .setAlignment(Layout.Alignment.ALIGN_NORMAL)
        .setLineSpacing(0f, 1.42f)
        .setIncludePad(true)
        .build()

    val quoteTopY = ((h - quoteLayout.height) * 0.44f).coerceIn(280f, 540f)
    canvas.save()
    canvas.translate(120f, quoteTopY)
    quoteLayout.draw(canvas)
    canvas.restore()

    val footerY = (quoteTopY + quoteLayout.height + 90f).coerceAtMost(h - 220f)
    val accentLinePaint = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply {
        color = template.accentArgb
        strokeWidth = 5f
    }
    canvas.drawLine(120f, footerY, 240f, footerY, accentLinePaint)

    val titlePaint = TextPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply {
        color = template.textArgb
        textSize = 36f
        typeface = Typeface.create(if (template.useSerif) Typeface.SERIF else Typeface.SANS_SERIF, Typeface.BOLD)
    }
    val cleanTitle = if (bookTitle.length > 42) bookTitle.take(40) + "…" else bookTitle
    canvas.drawText(cleanTitle, 120f, footerY + 64f, titlePaint)

    val authorPaint = TextPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply {
        color = template.accentArgb
        textSize = 30f
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
    }
    canvas.drawText(bookAuthor, 120f, footerY + 112f, authorPaint)

    val brandPaint = TextPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply {
        color = template.textArgb
        alpha = 120
        textSize = 24f
        letterSpacing = 0.15f
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    }
    canvas.drawText("FABLEWOOD", 120f, h - 100f, brandPaint)

    return bmp
}

private fun saveAndShareQuoteBitmap(context: Context, bitmap: Bitmap, shareImmediately: Boolean) {
    try {
        val filename = "Fablewood_Quote_${System.currentTimeMillis()}.png"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, filename)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Fablewood")
            }
        }
        val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        if (uri != null) {
            context.contentResolver.openOutputStream(uri)?.use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            if (shareImmediately) {
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "image/png"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(Intent.createChooser(shareIntent, "Share Quote Card"))
            } else {
                Toast.makeText(context, "Quote Card saved to Pictures/Fablewood!", Toast.LENGTH_SHORT).show()
            }
        }
    } catch (t: Throwable) {
        Toast.makeText(context, "Could not save image: ${t.localizedMessage}", Toast.LENGTH_SHORT).show()
    }
}

private data class PageLinkHotspot(
    val rect: RectF,
    val targetChapterIndex: Int? = null,
    val label: String,
    val footnoteTitle: String? = null,
    val footnoteBody: String? = null
)

private data class PageParagraphBlock(
    val rect: RectF,
    val text: String
)

private data class RenderedBookPage(
    val bitmap: Bitmap,
    val hotspots: List<PageLinkHotspot>,
    val paragraphs: List<PageParagraphBlock> = emptyList()
)

private data class ReaderReturnBookmark(
    val chapterIndex: Int,
    val subPageIndex: Int,
    val label: String,
    val timestampMs: Long = System.currentTimeMillis()
)

private fun parseSafeHexColor(hexStr: String, fallback: Color): Color {
    return try {
        val cleaned = hexStr.trim().let { if (it.startsWith("#")) it else "#$it" }
        Color(AndroidColor.parseColor(cleaned))
    } catch (_: Throwable) {
        fallback
    }
}

private fun parseHighlightSpanArgb(hexStr: String, isDarkReader: Boolean): Int {
    return try {
        val cleaned = hexStr.trim().let { if (it.startsWith("#")) it else "#$it" }
        val base = AndroidColor.parseColor(cleaned)
        val alpha = if (isDarkReader) 0x66 else 0x88
        (alpha shl 24) or (base and 0x00FFFFFF)
    } catch (_: Throwable) {
        0x77F5C542
    }
}

private fun resolveNativeTypeface(fontFamilyKey: String, bold: Boolean): Typeface {
    val style = if (bold) Typeface.BOLD else Typeface.NORMAL
    return when (fontFamilyKey.uppercase(Locale.ROOT)) {
        "SANS" -> Typeface.create(Typeface.SANS_SERIF, style)
        "MONO" -> Typeface.create(Typeface.MONOSPACE, style)
        "BOOK" -> Typeface.create("serif", style)
        else -> Typeface.create(Typeface.SERIF, style)
    }
}

private fun extractHeroTintFromBitmap(bitmap: Bitmap?): Color? {
    val pal = extractCoverPaletteFromBitmap(bitmap) ?: return null
    val argb = AndroidColor.HSVToColor(
        floatArrayOf(
            pal.dominantHue.coerceIn(0f, 360f),
            (pal.dominantSat * 0.65f).coerceIn(0.25f, 0.72f),
            0.28f
        )
    )
    return Color(argb)
}

private fun deriveSeriesName(rawSeries: String, rawTitle: String): String {
    if (rawSeries.isNotBlank()) return rawSeries.trim()
    val cleanTitle = rawTitle.trim()

    val bookNumMatch = Regex("(?i)^(.+?)(?:[:,\\-—]|\\s+)\\s*(?:book|vol\\.?|volume|part|#)\\s*\\d+").find(cleanTitle)
    if (bookNumMatch != null) {
        val prefix = bookNumMatch.groupValues[1].trim()
        if (prefix.length in 3..45) return prefix
    }

    val colonPrefix = cleanTitle.substringBefore(':', "").trim()
    if (colonPrefix.length in 3..38 && colonPrefix != cleanTitle) {
        return colonPrefix
    }
    val dashPrefix = cleanTitle.substringBefore(" - ", "").trim()
    if (dashPrefix.length in 3..38 && dashPrefix != cleanTitle) {
        return dashPrefix
    }
    return ""
}

private fun findFootnoteDefinition(
    markerNumOrSymbol: String,
    currentChapter: EbookChapter?,
    allChapters: List<EbookChapter>
): String {
    val cleanKey = markerNumOrSymbol.trim('[', ']', '(', ')', ' ')
    val escaped = Regex.escape(cleanKey)
    val linePattern = Regex("(?m)^\\s*(?:\\[$escaped\\]|$escaped[.)]|\\*$escaped)\\s+(.+)$")

    if (currentChapter != null) {
        val lines = currentChapter.bodyText.lines().map { it.trim() }.filter { it.isNotEmpty() }
        for (i in lines.indices.reversed()) {
            val line = lines[i]
            val match = linePattern.find(line)
            if (match != null) {
                val nextLine = lines.getOrNull(i + 1) ?: ""
                return if (nextLine.isNotEmpty() && !nextLine.startsWith("[")) {
                    "${match.groupValues[1]} $nextLine".trim()
                } else {
                    match.groupValues[1].trim()
                }
            }
        }
    }

    val notesChapters = allChapters.filter {
        it.title.contains("note", ignoreCase = true) ||
            it.title.contains("reference", ignoreCase = true) ||
            it.title.contains("glossary", ignoreCase = true)
    }
    for (chap in notesChapters) {
        val m = linePattern.find(chap.bodyText)
        if (m != null) {
            return m.groupValues[1].trim()
        }
    }

    return "Footnote [$cleanKey] referenced in ${currentChapter?.title ?: "this chapter"}."
}

@Composable
fun TactileBookCover(
    coverArt: ImageBitmap?,
    fallbackIcon: ImageVector,
    progress: Float,
    badgeText: String? = null,
    badgeIcon: ImageVector? = null,
    cornerRadius: Dp = 12.dp,
    shadowElevation: Dp = 8.dp,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(
        topStart = 4.dp,
        bottomStart = 4.dp,
        topEnd = cornerRadius,
        bottomEnd = cornerRadius
    )
    Box(
        modifier = modifier
            .shadow(
                elevation = shadowElevation,
                shape = shape,
                ambientColor = Color.Black.copy(alpha = 0.65f),
                spotColor = Color.Black.copy(alpha = 0.75f)
            )
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant),
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
                imageVector = fallbackIcon,
                contentDescription = null,
                modifier = Modifier.size(44.dp),
                tint = MaterialTheme.colorScheme.primary
            )
        }

        Box(
            modifier = Modifier
                .fillMaxHeight()
                .width(16.dp)
                .align(Alignment.CenterStart)
                .background(
                    Brush.horizontalGradient(
                        colors = listOf(
                            Color.Black.copy(alpha = 0.42f),
                            Color.White.copy(alpha = 0.22f),
                            Color.Black.copy(alpha = 0.26f),
                            Color.Transparent
                        )
                    )
                )
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .border(
                    width = 0.75.dp,
                    color = Color.White.copy(alpha = 0.14f),
                    shape = shape
                )
        )

        if (!badgeText.isNullOrBlank()) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 10.dp, bottom = 8.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color.Black.copy(alpha = 0.68f))
                    .padding(horizontal = 6.dp, vertical = 3.dp)
            ) {
                Text(badgeText, color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
        } else if (badgeIcon != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 10.dp, bottom = 8.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color.Black.copy(alpha = 0.65f))
                    .padding(4.dp)
            ) {
                Icon(badgeIcon, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
            }
        }

        if (progress > 0f) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .height(4.dp)
                    .background(Color.Black.copy(alpha = 0.45f))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(progress.coerceIn(0.02f, 1f))
                        .background(MaterialTheme.colorScheme.primary)
                )
            }
        }
    }
}

@Composable
fun CurrentlyReadingHeroCard(
    title: String,
    author: String,
    subtitle: String,
    progressFraction: Float,
    coverBitmap: Bitmap?,
    isAudio: Boolean,
    onContinueClick: () -> Unit
) {
    var dynamicTint by remember(coverBitmap) { mutableStateOf<Color?>(null) }

    LaunchedEffect(coverBitmap) {
        if (coverBitmap != null) {
            dynamicTint = withContext(Dispatchers.Default) {
                extractHeroTintFromBitmap(coverBitmap)
            }
        } else {
            dynamicTint = null
        }
    }

    val baseBg = dynamicTint ?: MaterialTheme.colorScheme.surfaceVariant
    val pct = (progressFraction.coerceIn(0f, 1f) * 100).roundToInt()

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onContinueClick() },
        shape = RoundedCornerShape(22.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
        colors = CardDefaults.cardColors(containerColor = baseBg)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.horizontalGradient(
                        colors = listOf(
                            baseBg,
                            MaterialTheme.colorScheme.surface.copy(alpha = 0.88f)
                        )
                    )
                )
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TactileBookCover(
                    coverArt = coverBitmap?.asImageBitmap(),
                    fallbackIcon = if (isAudio) Icons.Default.Headphones else Icons.Default.MenuBook,
                    progress = progressFraction,
                    cornerRadius = 10.dp,
                    shadowElevation = 10.dp,
                    modifier = if (isAudio) {
                        Modifier.size(88.dp)
                    } else {
                        Modifier.width(72.dp).height(108.dp)
                    }
                )

                Spacer(Modifier.width(16.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (isAudio) "CURRENTLY LISTENING" else "CURRENTLY READING",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Surface(
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f),
                            shape = RoundedCornerShape(50)
                        ) {
                            Text(
                                text = "$pct%",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                            )
                        }
                    }

                    Spacer(Modifier.height(4.dp))

                    Text(
                        text = title,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = author,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    Spacer(Modifier.height(8.dp))

                    LinearProgressIndicator(
                        progress = { progressFraction.coerceIn(0.02f, 1f) },
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(5.dp)
                            .clip(RoundedCornerShape(50))
                    )

                    Spacer(Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(Modifier.width(8.dp))
                        Surface(
                            color = MaterialTheme.colorScheme.primary,
                            shape = RoundedCornerShape(50)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = if (isAudio) Icons.Default.PlayArrow else Icons.Default.AutoStories,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onPrimary,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    text = "Continue",
                                    fontSize = 11.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onPrimary
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SmartShelfFilterRow(
    selectedFilter: String,
    onSelectFilter: (String) -> Unit,
    onOpenQuotesHub: (() -> Unit)? = null
) {
    val filters = listOf(
        "ALL" to "All",
        "READING" to "Reading",
        "UNREAD" to "Unread",
        "FINISHED" to "Finished",
        "SERIES" to "Series"
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        filters.forEach { (key, label) ->
            val isSelected = selectedFilter == key
            FilterChip(
                selected = isSelected,
                onClick = { onSelectFilter(key) },
                label = {
                    Text(
                        text = label,
                        fontSize = 12.5.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                    )
                },
                shape = RoundedCornerShape(50)
            )
        }
        if (onOpenQuotesHub != null) {
            AssistChip(
                onClick = { onOpenQuotesHub() },
                leadingIcon = {
                    Icon(
                        Icons.Default.FormatQuote,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                },
                label = {
                    Text(
                        text = "Quotes Hub",
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                },
                shape = RoundedCornerShape(50)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlobalQuotesHubSheet(
    repository: AudiobookRepository,
    filterEbookId: String? = null,
    onDismiss: () -> Unit,
    onJumpToHighlight: ((EbookHighlight) -> Unit)? = null,
    onOpenQuoteCardStudio: (EbookHighlight) -> Unit
) {
    var allHighlights by remember { mutableStateOf(loadAllEbookHighlights(repository)) }
    var searchQuery by remember { mutableStateOf("") }
    var selectedBookFilter by remember { mutableStateOf(filterEbookId ?: "ALL") }

    val distinctBooks = remember(allHighlights) {
        allHighlights.map { it.ebookId to it.bookTitle }.distinctBy { it.first }
    }

    val displayedHighlights = remember(allHighlights, searchQuery, selectedBookFilter) {
        allHighlights.filter { item ->
            val matchesBook = selectedBookFilter == "ALL" || item.ebookId == selectedBookFilter
            val matchesQuery = searchQuery.isBlank() ||
                item.text.contains(searchQuery, ignoreCase = true) ||
                item.note.contains(searchQuery, ignoreCase = true) ||
                item.bookTitle.contains(searchQuery, ignoreCase = true) ||
                item.bookAuthor.contains(searchQuery, ignoreCase = true)
            matchesBook && matchesQuery
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 380.dp, max = 640.dp)
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.FormatQuote, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Text("Quotes & Notes Hub", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }
                TextButton(onClick = onDismiss) {
                    Text("Done", fontWeight = FontWeight.Bold)
                }
            }

            Spacer(Modifier.height(8.dp))

            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Search highlights, notes, or books...") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            )

            if (distinctBooks.size > 1) {
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = selectedBookFilter == "ALL",
                        onClick = { selectedBookFilter = "ALL" },
                        label = { Text("All Books (${allHighlights.size})", fontSize = 12.sp) }
                    )
                    distinctBooks.forEach { (ebId, ebTitle) ->
                        FilterChip(
                            selected = selectedBookFilter == ebId,
                            onClick = { selectedBookFilter = ebId },
                            label = {
                                Text(
                                    text = ebTitle.take(24),
                                    fontSize = 12.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        )
                    }
                }
            }

            Spacer(Modifier.height(10.dp))

            if (displayedHighlights.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.FormatQuote,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "No highlights or notes yet.\nLong-press any paragraph while reading an eBook to select specific sentences and share a quote card.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(count = displayedHighlights.size, key = { displayedHighlights[it].id }) { idx ->
                        val item = displayedHighlights[idx]
                        val swatchColor = parseSafeHexColor(item.colorHex, Color(0xFFF5C542))

                        Card(
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    if (onJumpToHighlight != null) {
                                        onJumpToHighlight(item)
                                    }
                                }
                        ) {
                            Column(modifier = Modifier.padding(14.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(12.dp)
                                                .clip(CircleShape)
                                                .background(swatchColor)
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Text(
                                            text = "${item.bookTitle} • ${item.chapterTitle}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary,
                                            fontWeight = FontWeight.Bold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    Text(
                                        text = item.timestampStr,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }

                                Spacer(Modifier.height(8.dp))

                                Text(
                                    text = "“${item.text}”",
                                    fontFamily = FontFamily.Serif,
                                    fontStyle = FontStyle.Italic,
                                    fontSize = 14.5.sp,
                                    color = MaterialTheme.colorScheme.onSurface
                                )

                                if (item.note.isNotBlank()) {
                                    Spacer(Modifier.height(8.dp))
                                    Surface(
                                        color = MaterialTheme.colorScheme.surface,
                                        shape = RoundedCornerShape(10.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text(
                                            text = "Note: ${item.note}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.padding(10.dp)
                                        )
                                    }
                                }

                                Spacer(Modifier.height(10.dp))

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.End,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    TextButton(
                                        onClick = { onOpenQuoteCardStudio(item) }
                                    ) {
                                        Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(5.dp))
                                        Text("Quote Card", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                    }

                                    IconButton(
                                        onClick = {
                                            val updated = allHighlights.filterNot { it.id == item.id }
                                            allHighlights = updated
                                            saveAllEbookHighlights(repository, updated)
                                        }
                                    ) {
                                        Icon(
                                            Icons.Default.DeleteOutline,
                                            contentDescription = "Delete Highlight",
                                            tint = MaterialTheme.colorScheme.error,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
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
fun ShareableQuoteCardStudioDialog(
    quoteText: String,
    bookTitle: String,
    bookAuthor: String,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var selectedTemplate by remember { mutableStateOf(QUOTE_CARD_TEMPLATES.first()) }
    val previewBitmap = remember(quoteText, bookTitle, bookAuthor, selectedTemplate) {
        renderShareableQuoteCardBitmap(
            quoteText = quoteText,
            bookTitle = bookTitle,
            bookAuthor = bookAuthor,
            template = selectedTemplate
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text("Shareable Quote Card", fontWeight = FontWeight.Bold)
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Image(
                    bitmap = previewBitmap.asImageBitmap(),
                    contentDescription = "Quote Card Preview",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1080f / 1350f)
                        .clip(RoundedCornerShape(16.dp))
                        .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f), RoundedCornerShape(16.dp))
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    QUOTE_CARD_TEMPLATES.forEach { tmpl ->
                        FilterChip(
                            selected = selectedTemplate.id == tmpl.id,
                            onClick = { selectedTemplate = tmpl },
                            label = { Text(tmpl.name, fontSize = 12.sp) }
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            saveAndShareQuoteBitmap(context, previewBitmap, shareImmediately = false)
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Save Image", fontSize = 12.sp)
                    }
                    Button(
                        onClick = {
                            saveAndShareQuoteBitmap(context, previewBitmap, shareImmediately = true)
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Share Card", fontSize = 12.sp)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}

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

        var remainingLines = chap.bodyText.lines()
        while (remainingLines.isNotEmpty()) {
            val firstIdx = remainingLines.indexOfFirst { it.trim().isNotEmpty() }
            if (firstIdx != -1 && remainingLines[firstIdx].trim().equals(resolvedTitle.trim(), ignoreCase = true)) {
                remainingLines = remainingLines.drop(firstIdx + 1)
            } else {
                break
            }
        }

        chap.copy(title = resolvedTitle, bodyText = remainingLines.joinToString("\n").trim())
    }
}

private fun matchLineToTargetChapterIndex(
    rawLine: String,
    chapters: List<EbookChapter>,
    currentChapterIndex: Int,
    isTocChapter: Boolean
): Int? {
    if (chapters.size <= 1) return null

    val cleaned = rawLine.trim()
        .replace(Regex("[.…_\\s]+\\d+\\s*$"), "")
        .replace(Regex("^\\d+\\.\\s+"), "")
        .trim()

    if (cleaned.length !in 2..68) return null

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

    val chapNumMatch = Regex("(?i)^chapter\\s+(\\d+)\\b").find(cleaned)
    if (chapNumMatch != null) {
        val targetNum = chapNumMatch.groupValues[1].toIntOrNull()
        if (targetNum != null && targetNum > 0) {
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

private fun formatBodyWithParagraphSpacing(rawBody: String, paragraphSpacingDp: Float): String {
    val clean = rawBody.replace("\r\n", "\n").trim()
    if (paragraphSpacingDp <= 4f) {
        return clean.replace(Regex("\\n\\s*\\n"), "\n")
    }
    return clean
}

fun paginateChapterByMeasuredLayout(
    bodyText: String,
    headingText: String,
    widthPx: Int,
    heightPx: Int,
    fontSizePx: Float,
    fontFamilyKey: String = "SERIF",
    lineHeightMultiplier: Float = 1.42f,
    marginScale: Float = 1.0f,
    isJustified: Boolean = true,
    paragraphSpacingDp: Float = 12f
): List<String> {
    val clean = formatBodyWithParagraphSpacing(bodyText, paragraphSpacingDp)
    if (clean.isEmpty()) return listOf("")

    val w = widthPx.coerceAtLeast(120)
    val h = heightPx.coerceAtLeast(160)
    val padH = (w * 0.068f * marginScale.coerceIn(0.6f, 1.6f)).coerceAtLeast(20f)
    val padTop = (h * 0.045f).coerceAtLeast(24f)
    val padBottom = (h * 0.055f).coerceAtLeast(34f)
    val contentWidth = (w - (padH * 2f)).toInt().coerceAtLeast(60)

    val headingTypeface = resolveNativeTypeface(fontFamilyKey, bold = true)
    val bodyTypeface = resolveNativeTypeface(fontFamilyKey, bold = false)

    val headingHeightPx = if (headingText.isNotBlank()) {
        val headingPaint = TextPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply {
            textSize = fontSizePx * 1.32f
            typeface = headingTypeface
        }
        val hLayout = StaticLayout.Builder
            .obtain(headingText, 0, headingText.length, headingPaint, contentWidth)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setLineSpacing(0f, 1.15f)
            .setIncludePad(false)
            .build()
        hLayout.height + (fontSizePx * 1.1f).toInt()
    } else 0

    val bodyPaint = TextPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply {
        textSize = fontSizePx
        typeface = bodyTypeface
    }

    val extraSpacingPx = ((paragraphSpacingDp - 10f).coerceAtLeast(0f)) * 0.45f
    val fullLayoutBuilder = StaticLayout.Builder
        .obtain(clean, 0, clean.length, bodyPaint, contentWidth)
        .setAlignment(Layout.Alignment.ALIGN_NORMAL)
        .setLineSpacing(extraSpacingPx, lineHeightMultiplier.coerceIn(1.15f, 2.0f))
        .setIncludePad(true)

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && isJustified) {
        fullLayoutBuilder.setJustificationMode(Layout.JUSTIFICATION_MODE_INTER_WORD)
        fullLayoutBuilder.setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NORMAL)
    }

    val fullLayout = fullLayoutBuilder.build()
    val totalLines = fullLayout.lineCount
    if (totalLines <= 0) return listOf(clean)

    val fullAvailHeight = (h - padTop - padBottom - 10f).toInt().coerceAtLeast(80)
    val firstPageAvailHeight = (fullAvailHeight - headingHeightPx).coerceAtLeast(60)

    val pages = mutableListOf<String>()
    var startLine = 0
    var isFirstPage = true

    while (startLine < totalLines) {
        val maxPageHeight = if (isFirstPage) firstPageAvailHeight else fullAvailHeight
        val startTop = fullLayout.getLineTop(startLine)
        var endLine = startLine

        while (endLine + 1 < totalLines) {
            val nextBottom = fullLayout.getLineBottom(endLine + 1) - startTop
            if (nextBottom <= maxPageHeight) {
                endLine++
            } else {
                break
            }
        }

        val charStart = fullLayout.getLineStart(startLine)
        val charEnd = fullLayout.getLineEnd(endLine)
        val pageSlice = clean.substring(charStart, charEnd).trim()
        if (pageSlice.isNotEmpty()) {
            pages.add(pageSlice)
        }

        startLine = endLine + 1
        isFirstPage = false
    }

    return if (pages.isEmpty()) listOf(clean) else pages
}

fun paginateChapterText(
    bodyText: String,
    charsPerLine: Int,
    linesPerPage: Int
): List<String> {
    return paginateChapterByMeasuredLayout(
        bodyText = bodyText,
        headingText = "",
        widthPx = charsPerLine * 18,
        heightPx = linesPerPage * 44,
        fontSizePx = 32f
    )
}

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
    fontFamilyKey: String,
    lineHeightMultiplier: Float,
    marginScale: Float,
    isJustified: Boolean,
    paragraphSpacingDp: Float,
    visualBitmap: Bitmap?,
    allChapters: List<EbookChapter>,
    currentChapterIndex: Int,
    isTocChapter: Boolean,
    chapterHighlights: List<EbookHighlight> = emptyList(),
    isDarkReader: Boolean = true,
    activeTtsSentence: String? = null
): RenderedBookPage {
    val w = widthPx.coerceAtLeast(64)
    val h = heightPx.coerceAtLeast(64)
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = AndroidCanvas(bmp)
    canvas.drawColor(bgColorArgb)
    val hotspots = mutableListOf<PageLinkHotspot>()
    val paragraphBlocks = mutableListOf<PageParagraphBlock>()

    val padH = (w * 0.068f * marginScale.coerceIn(0.6f, 1.6f)).coerceAtLeast(20f)
    val padTop = (h * 0.045f).coerceAtLeast(24f)
    val padBottom = (h * 0.045f).coerceAtLeast(26f)
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
        val headingTypeface = resolveNativeTypeface(fontFamilyKey, bold = true)
        val bodyTypeface = resolveNativeTypeface(fontFamilyKey, bold = false)

        if (showHeading && headingText.isNotBlank()) {
            val headingPaint = TextPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply {
                color = accentColorArgb
                textSize = fontSizePx * 1.32f
                typeface = headingTypeface
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
                typeface = bodyTypeface
            }

            val spannable = SpannableString(bodyText)

            for (hl in chapterHighlights) {
                if (hl.text.length >= 4) {
                    var startIdx = bodyText.indexOf(hl.text, ignoreCase = true)
                    while (startIdx >= 0) {
                        val endIdx = (startIdx + hl.text.length).coerceAtMost(bodyText.length)
                        val spanColor = parseHighlightSpanArgb(hl.colorHex, isDarkReader)
                        spannable.setSpan(
                            BackgroundColorSpan(spanColor),
                            startIdx,
                            endIdx,
                            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                        )
                        startIdx = bodyText.indexOf(hl.text, startIdx + hl.text.length, ignoreCase = true)
                    }
                }
            }

            if (!activeTtsSentence.isNullOrBlank() && activeTtsSentence.length >= 3) {
                val ttsIdx = bodyText.indexOf(activeTtsSentence)
                if (ttsIdx >= 0) {
                    val ttsEnd = (ttsIdx + activeTtsSentence.length).coerceAtMost(bodyText.length)
                    val ttsBgColor = if (isDarkReader) 0x77D4AF37 else 0x88F5C542.toInt()
                    spannable.setSpan(
                        BackgroundColorSpan(ttsBgColor),
                        ttsIdx,
                        ttsEnd,
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                }
            }

            data class MatchedChapterSpan(val start: Int, val end: Int, val targetIdx: Int, val label: String)
            data class MatchedFootnoteSpan(val start: Int, val end: Int, val marker: String)
            val matchedChapters = mutableListOf<MatchedChapterSpan>()
            val matchedFootnotes = mutableListOf<MatchedFootnoteSpan>()

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
                    matchedChapters.add(MatchedChapterSpan(lineStart, lineEnd, targetChapIdx, line.trim()))
                }
            }

            val fnRegex = Regex("\\[(?:note\\s*)?(\\d{1,3}|[*†‡])\\]", RegexOption.IGNORE_CASE)
            for (fnMatch in fnRegex.findAll(bodyText)) {
                val s = fnMatch.range.first
                val e = fnMatch.range.last + 1
                spannable.setSpan(
                    ForegroundColorSpan(linkColorArgb),
                    s,
                    e,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                matchedFootnotes.add(MatchedFootnoteSpan(s, e, fnMatch.value))
            }

            val extraSpacingPx = ((paragraphSpacingDp - 10f).coerceAtLeast(0f)) * 0.45f
            val bodyLayoutBuilder = StaticLayout.Builder
                .obtain(spannable, 0, spannable.length, bodyPaint, contentWidth)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(extraSpacingPx, lineHeightMultiplier.coerceIn(1.15f, 2.0f))
                .setIncludePad(true)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && isJustified && !isTocChapter) {
                bodyLayoutBuilder.setJustificationMode(Layout.JUSTIFICATION_MODE_INTER_WORD)
                bodyLayoutBuilder.setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NORMAL)
            }

            val bodyLayout = bodyLayoutBuilder.build()

            canvas.save()
            canvas.translate(padH, currentY)
            bodyLayout.draw(canvas)
            canvas.restore()

            var pOffset = 0
            for (rawPara in bodyText.split('\n')) {
                val pStart = pOffset
                val pEnd = pStart + rawPara.length
                pOffset = pEnd + 1
                val trimmed = rawPara.trim()
                if (trimmed.length >= 8) {
                    val sLine = bodyLayout.getLineForOffset(pStart.coerceIn(0, bodyText.length))
                    val eLine = bodyLayout.getLineForOffset(pEnd.coerceIn(0, bodyText.length))
                    val topY = currentY + bodyLayout.getLineTop(sLine) - 6f
                    val bottomY = currentY + bodyLayout.getLineBottom(eLine) + 6f
                    paragraphBlocks.add(
                        PageParagraphBlock(
                            rect = RectF(padH - 12f, topY, padH + contentWidth + 12f, bottomY),
                            text = trimmed
                        )
                    )
                }
            }

            for (ms in matchedChapters) {
                val startLine = bodyLayout.getLineForOffset(ms.start)
                val endLine = bodyLayout.getLineForOffset(ms.end.coerceAtLeast(ms.start))
                val topPx = currentY + bodyLayout.getLineTop(startLine) - 8f
                val bottomPx = currentY + bodyLayout.getLineBottom(endLine) + 8f
                hotspots.add(
                    PageLinkHotspot(
                        rect = RectF(padH - 12f, topPx, padH + contentWidth + 12f, bottomPx),
                        targetChapterIndex = ms.targetIdx,
                        label = ms.label
                    )
                )
            }

            val currChapObj = allChapters.getOrNull(currentChapterIndex)
            for (fn in matchedFootnotes) {
                val lineIdx = bodyLayout.getLineForOffset(fn.start)
                val leftX = padH + bodyLayout.getPrimaryHorizontal(fn.start) - 18f
                val rightX = padH + bodyLayout.getPrimaryHorizontal(fn.end.coerceAtMost(spannable.length)) + 18f
                val topY = currentY + bodyLayout.getLineTop(lineIdx) - 12f
                val bottomY = currentY + bodyLayout.getLineBottom(lineIdx) + 12f
                val resolvedNote = findFootnoteDefinition(fn.marker, currChapObj, allChapters)
                hotspots.add(
                    PageLinkHotspot(
                        rect = RectF(leftX, topY, rightX.coerceAtLeast(leftX + 44f), bottomY),
                        targetChapterIndex = null,
                        label = fn.marker,
                        footnoteTitle = "Footnote ${fn.marker}",
                        footnoteBody = resolvedNote
                    )
                )
            }
        }
    }

    return RenderedBookPage(bitmap = bmp, hotspots = hotspots, paragraphs = paragraphBlocks)
}

private fun renderDualSpreadBookPageToBitmap(
    totalWidthPx: Int,
    heightPx: Int,
    bgColorArgb: Int,
    textColorArgb: Int,
    accentColorArgb: Int,
    linkColorArgb: Int,
    headingText: String,
    showHeadingOnLeft: Boolean,
    leftBodyText: String,
    rightBodyText: String,
    fontSizePx: Float,
    fontFamilyKey: String,
    lineHeightMultiplier: Float,
    marginScale: Float,
    isJustified: Boolean,
    paragraphSpacingDp: Float,
    allChapters: List<EbookChapter>,
    currentChapterIndex: Int,
    isTocChapter: Boolean,
    chapterHighlights: List<EbookHighlight> = emptyList(),
    isDarkReader: Boolean = true,
    activeTtsSentence: String? = null
): RenderedBookPage {
    val totalW = totalWidthPx.coerceAtLeast(200)
    val h = heightPx.coerceAtLeast(120)
    val spineW = 24
    val pageW = ((totalW - spineW) / 2).coerceAtLeast(80)

    val leftRendered = renderBookPageToBitmap(
        widthPx = pageW,
        heightPx = h,
        bgColorArgb = bgColorArgb,
        textColorArgb = textColorArgb,
        accentColorArgb = accentColorArgb,
        linkColorArgb = linkColorArgb,
        headingText = headingText,
        showHeading = showHeadingOnLeft,
        bodyText = leftBodyText,
        fontSizePx = fontSizePx,
        fontFamilyKey = fontFamilyKey,
        lineHeightMultiplier = lineHeightMultiplier,
        marginScale = marginScale,
        isJustified = isJustified,
        paragraphSpacingDp = paragraphSpacingDp,
        visualBitmap = null,
        allChapters = allChapters,
        currentChapterIndex = currentChapterIndex,
        isTocChapter = isTocChapter,
        chapterHighlights = chapterHighlights,
        isDarkReader = isDarkReader,
        activeTtsSentence = activeTtsSentence
    )

    val rightRendered = renderBookPageToBitmap(
        widthPx = pageW,
        heightPx = h,
        bgColorArgb = bgColorArgb,
        textColorArgb = textColorArgb,
        accentColorArgb = accentColorArgb,
        linkColorArgb = linkColorArgb,
        headingText = "",
        showHeading = false,
        bodyText = rightBodyText,
        fontSizePx = fontSizePx,
        fontFamilyKey = fontFamilyKey,
        lineHeightMultiplier = lineHeightMultiplier,
        marginScale = marginScale,
        isJustified = isJustified,
        paragraphSpacingDp = paragraphSpacingDp,
        visualBitmap = null,
        allChapters = allChapters,
        currentChapterIndex = currentChapterIndex,
        isTocChapter = isTocChapter,
        chapterHighlights = chapterHighlights,
        isDarkReader = isDarkReader,
        activeTtsSentence = activeTtsSentence
    )

    val combinedBmp = Bitmap.createBitmap(totalW, h, Bitmap.Config.ARGB_8888)
    val canvas = AndroidCanvas(combinedBmp)
    canvas.drawColor(bgColorArgb)

    val rightOffsetX = (pageW + spineW).toFloat()
    canvas.drawBitmap(leftRendered.bitmap, 0f, 0f, null)
    canvas.drawBitmap(rightRendered.bitmap, rightOffsetX, 0f, null)

    val spineLeft = (pageW - 14).toFloat()
    val spineRight = (pageW + spineW + 14).toFloat()
    val spinePaint = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply {
        shader = LinearGradient(
            spineLeft,
            0f,
            spineRight,
            0f,
            intArrayOf(
                0x00000000,
                0x44000000,
                0x77000000,
                0x44000000,
                0x00000000
            ),
            floatArrayOf(0f, 0.35f, 0.5f, 0.65f, 1f),
            Shader.TileMode.CLAMP
        )
    }
    canvas.drawRect(spineLeft, 0f, spineRight, h.toFloat(), spinePaint)

    val combinedHotspots = ArrayList<PageLinkHotspot>(leftRendered.hotspots.size + rightRendered.hotspots.size)
    combinedHotspots.addAll(leftRendered.hotspots)
    for (spot in rightRendered.hotspots) {
        val shiftedRect = RectF(
            spot.rect.left + rightOffsetX,
            spot.rect.top,
            spot.rect.right + rightOffsetX,
            spot.rect.bottom
        )
        combinedHotspots.add(spot.copy(rect = shiftedRect))
    }

    val combinedParagraphs = ArrayList<PageParagraphBlock>(leftRendered.paragraphs.size + rightRendered.paragraphs.size)
    combinedParagraphs.addAll(leftRendered.paragraphs)
    for (para in rightRendered.paragraphs) {
        val shiftedRect = RectF(
            para.rect.left + rightOffsetX,
            para.rect.top,
            para.rect.right + rightOffsetX,
            para.rect.bottom
        )
        combinedParagraphs.add(para.copy(rect = shiftedRect))
    }

    return RenderedBookPage(bitmap = combinedBmp, hotspots = combinedHotspots, paragraphs = combinedParagraphs)
}

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
            .height(36.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f), CircleShape)
            .padding(3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val isAudio = shelfMode == "AUDIO"
        Row(
            modifier = Modifier
                .fillMaxHeight()
                .clip(CircleShape)
                .background(if (isAudio) MaterialTheme.colorScheme.primary else Color.Transparent)
                .clickable { onSelectMode("AUDIO") }
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Headphones,
                contentDescription = null,
                tint = if (isAudio) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(15.dp)
            )
            Spacer(Modifier.width(4.dp))
            Text(
                text = "Audio",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                softWrap = false,
                color = if (isAudio) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Row(
            modifier = Modifier
                .fillMaxHeight()
                .clip(CircleShape)
                .background(if (!isAudio) MaterialTheme.colorScheme.primary else Color.Transparent)
                .clickable { onSelectMode("EBOOK") }
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.MenuBook,
                contentDescription = null,
                tint = if (!isAudio) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(15.dp)
            )
            Spacer(Modifier.width(4.dp))
            Text(
                text = "eBooks",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                softWrap = false,
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
            TactileBookCover(
                coverArt = coverArt,
                fallbackIcon = Icons.Default.Headphones,
                progress = 0f,
                cornerRadius = 8.dp,
                shadowElevation = 4.dp,
                modifier = Modifier.size(48.dp)
            )

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
                SymmetricalSkipIcon(
                    seconds = skipIntervalSeconds,
                    isForward = false,
                    tint = Color.White,
                    iconSize = 52.dp,
                    fontSize = 13.sp
                )
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
                SymmetricalSkipIcon(
                    seconds = skipIntervalSeconds,
                    isForward = true,
                    tint = Color.White,
                    iconSize = 52.dp,
                    fontSize = 13.sp
                )
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
    var showQuotesHub by remember { mutableStateOf(false) }
    var studioQuoteHighlight by remember { mutableStateOf<EbookHighlight?>(null) }
    var showReadingInsightsDialog by remember { mutableStateOf(false) }
    val streakSnapshot = remember(viewModel.progressRevision, viewModel.totalListenMs) {
        loadReadingStreakSnapshot(viewModel.repository.getPreferences())
    }

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
        return
    }

    val heroBook = remember(books, activeBookId) {
        books.find { it.id == activeBookId } ?: books.firstOrNull()
    }

    val filteredBooks = remember(books, viewModel.smartShelfFilter, viewModel.progressRevision, viewModel.completedBooksCount) {
        when (viewModel.smartShelfFilter) {
            "READING" -> books.filter { viewModel.getAudiobookStatus(it) == "READING" }
            "UNREAD" -> books.filter { viewModel.getAudiobookStatus(it) == "UNREAD" }
            "FINISHED" -> books.filter { viewModel.getAudiobookStatus(it) == "FINISHED" }
            "SERIES" -> {
                val authorCounts = books
                    .filter { !it.author.equals("Unknown Author", ignoreCase = true) && it.author.isNotBlank() }
                    .groupingBy { it.author.lowercase(Locale.ROOT) }
                    .eachCount()

                books.filter { b ->
                    deriveSeriesName(b.series, b.title).isNotBlank() ||
                        (authorCounts[b.author.lowercase(Locale.ROOT)] ?: 0) >= 2
                }.sortedWith(
                    compareBy<Audiobook> { deriveSeriesName(it.series, it.title).ifBlank { it.author } }
                        .thenComparator { a, b -> compareNatural(a.title, b.title) }
                )
            }
            else -> books
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        SmartShelfFilterRow(
            selectedFilter = viewModel.smartShelfFilter,
            onSelectFilter = { viewModel.updateSmartShelfFilter(it) },
            onOpenQuotesHub = { showQuotesHub = true }
        )

        if (filteredBooks.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                Text(
                    text = if (viewModel.smartShelfFilter == "SERIES") {
                        "No multi-book series found in your Audiobook library yet."
                    } else {
                        "No audiobooks match the '${viewModel.smartShelfFilter.lowercase(Locale.ROOT)}' filter."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        } else if (viewMode == "GRID") {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 145.dp),
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
                modifier = Modifier.weight(1f)
            ) {
                if (viewModel.smartShelfFilter == "ALL") {
                    if (heroBook != null) {
                        item(span = { GridItemSpan(maxLineSpan) }, key = "hero_${heroBook.id}") {
                            CurrentlyReadingHeroCard(
                                title = heroBook.title,
                                author = heroBook.author,
                                subtitle = activeBookSubtitle.ifBlank { "${heroBook.tracks.size} track(s)" },
                                progressFraction = activeBookProgress,
                                coverBitmap = viewModel.currentCoverArt,
                                isAudio = true,
                                onContinueClick = { onSelectBook(heroBook) }
                            )
                        }
                    }
                    item(span = { GridItemSpan(maxLineSpan) }, key = "streak_audio_card") {
                        ReadingStreakStatsCard(
                            snapshot = streakSnapshot,
                            totalAudioListenMs = viewModel.totalListenMs,
                            completedAudioBooks = viewModel.completedBooksCount,
                            onClick = { showReadingInsightsDialog = true }
                        )
                    }
                }

                items(count = filteredBooks.size, key = { idx -> filteredBooks[idx].id }) { index ->
                    val book = filteredBooks[index]
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
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.weight(1f)
            ) {
                if (viewModel.smartShelfFilter == "ALL") {
                    if (heroBook != null) {
                        item(key = "hero_${heroBook.id}") {
                            CurrentlyReadingHeroCard(
                                title = heroBook.title,
                                author = heroBook.author,
                                subtitle = activeBookSubtitle.ifBlank { "${heroBook.tracks.size} track(s)" },
                                progressFraction = activeBookProgress,
                                coverBitmap = viewModel.currentCoverArt,
                                isAudio = true,
                                onContinueClick = { onSelectBook(heroBook) }
                            )
                        }
                    }
                    item(key = "streak_audio_list") {
                        ReadingStreakStatsCard(
                            snapshot = streakSnapshot,
                            totalAudioListenMs = viewModel.totalListenMs,
                            completedAudioBooks = viewModel.completedBooksCount,
                            onClick = { showReadingInsightsDialog = true }
                        )
                    }
                }

                items(count = filteredBooks.size, key = { idx -> filteredBooks[idx].id }) { index ->
                    val book = filteredBooks[index]
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

    if (showReadingInsightsDialog) {
        ReadingInsightsDialog(
            snapshot = streakSnapshot,
            totalAudioListenMs = viewModel.totalListenMs,
            completedAudioBooks = viewModel.completedBooksCount,
            ebooksCount = viewModel.ebooks.size,
            onDismiss = { showReadingInsightsDialog = false }
        )
    }

    if (showQuotesHub) {
        GlobalQuotesHubSheet(
            repository = viewModel.repository,
            onDismiss = { showQuotesHub = false },
            onOpenQuoteCardStudio = { hl ->
                showQuotesHub = false
                studioQuoteHighlight = hl
            }
        )
    }

    if (studioQuoteHighlight != null) {
        val hl = studioQuoteHighlight!!
        ShareableQuoteCardStudioDialog(
            quoteText = hl.text,
            bookTitle = hl.bookTitle,
            bookAuthor = hl.bookAuthor,
            onDismiss = { studioQuoteHighlight = null }
        )
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
    var showQuotesHub by remember { mutableStateOf(false) }
    var studioQuoteHighlight by remember { mutableStateOf<EbookHighlight?>(null) }
    var showReadingInsightsDialog by remember { mutableStateOf(false) }
    val streakSnapshot = remember(viewModel.progressRevision, viewModel.totalListenMs) {
        loadReadingStreakSnapshot(viewModel.repository.getPreferences())
    }

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
        return
    }

    val heroEbook = remember(ebooks, activeEbookId) {
        ebooks.find { it.id == activeEbookId } ?: ebooks.firstOrNull()
    }

    val heroTotalChaps = remember(heroEbook?.id, viewModel.progressRevision) {
        if (heroEbook != null) viewModel.repository.getEbookTotalChapters(heroEbook.id) else 0
    }
    val heroCurrChap = remember(heroEbook?.id, viewModel.progressRevision) {
        if (heroEbook != null) viewModel.repository.getEbookChapterIndex(heroEbook.id) else 0
    }
    val heroProgress = if (heroTotalChaps > 0) ((heroCurrChap + 1).toFloat() / heroTotalChaps).coerceIn(0.02f, 1f) else 0f
    val heroSubtitle = if (heroTotalChaps > 0) "Chapter ${heroCurrChap + 1} of $heroTotalChaps" else "${heroEbook?.format ?: "EPUB"} eBook"

    val filteredEbooks = remember(ebooks, viewModel.smartShelfFilter, viewModel.progressRevision) {
        when (viewModel.smartShelfFilter) {
            "READING" -> ebooks.filter { viewModel.getEbookStatus(it) == "READING" }
            "UNREAD" -> ebooks.filter { viewModel.getEbookStatus(it) == "UNREAD" }
            "FINISHED" -> ebooks.filter { viewModel.getEbookStatus(it) == "FINISHED" }
            "SERIES" -> {
                val authorCounts = ebooks
                    .filter { !it.author.equals("Unknown Author", ignoreCase = true) && it.author.isNotBlank() }
                    .groupingBy { it.author.lowercase(Locale.ROOT) }
                    .eachCount()

                ebooks.filter { eb ->
                    deriveSeriesName("", eb.title).isNotBlank() ||
                        (authorCounts[eb.author.lowercase(Locale.ROOT)] ?: 0) >= 2
                }.sortedWith(
                    compareBy<Ebook> { deriveSeriesName("", it.title).ifBlank { it.author } }
                        .thenComparator { a, b -> compareNatural(a.title, b.title) }
                )
            }
            else -> ebooks
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        SmartShelfFilterRow(
            selectedFilter = viewModel.smartShelfFilter,
            onSelectFilter = { viewModel.updateSmartShelfFilter(it) },
            onOpenQuotesHub = { showQuotesHub = true }
        )

        if (filteredEbooks.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                Text(
                    text = if (viewModel.smartShelfFilter == "SERIES") {
                        "No multi-book series found on your eBooks shelf yet."
                    } else {
                        "No eBooks match the '${viewModel.smartShelfFilter.lowercase(Locale.ROOT)}' filter."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        } else if (viewMode == "GRID") {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 135.dp),
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
                modifier = Modifier.weight(1f)
            ) {
                if (viewModel.smartShelfFilter == "ALL") {
                    if (heroEbook != null) {
                        item(span = { GridItemSpan(maxLineSpan) }, key = "hero_eb_${heroEbook.id}") {
                            CurrentlyReadingHeroCard(
                                title = heroEbook.title,
                                author = heroEbook.author,
                                subtitle = heroSubtitle,
                                progressFraction = heroProgress,
                                coverBitmap = viewModel.currentEbookCoverArt,
                                isAudio = false,
                                onContinueClick = { onSelectEbook(heroEbook) }
                            )
                        }
                    }
                    item(span = { GridItemSpan(maxLineSpan) }, key = "streak_ebook_card") {
                        ReadingStreakStatsCard(
                            snapshot = streakSnapshot,
                            totalAudioListenMs = viewModel.totalListenMs,
                            completedAudioBooks = viewModel.completedBooksCount,
                            onClick = { showReadingInsightsDialog = true }
                        )
                    }
                }

                items(count = filteredEbooks.size, key = { idx -> filteredEbooks[idx].id }) { index ->
                    val ebook = filteredEbooks[index]
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
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.weight(1f)
            ) {
                if (viewModel.smartShelfFilter == "ALL") {
                    if (heroEbook != null) {
                        item(key = "hero_eb_${heroEbook.id}") {
                            CurrentlyReadingHeroCard(
                                title = heroEbook.title,
                                author = heroEbook.author,
                                subtitle = heroSubtitle,
                                progressFraction = heroProgress,
                                coverBitmap = viewModel.currentEbookCoverArt,
                                isAudio = false,
                                onContinueClick = { onSelectEbook(heroEbook) }
                            )
                        }
                    }
                    item(key = "streak_ebook_list") {
                        ReadingStreakStatsCard(
                            snapshot = streakSnapshot,
                            totalAudioListenMs = viewModel.totalListenMs,
                            completedAudioBooks = viewModel.completedBooksCount,
                            onClick = { showReadingInsightsDialog = true }
                        )
                    }
                }

                items(count = filteredEbooks.size, key = { idx -> filteredEbooks[idx].id }) { index ->
                    val ebook = filteredEbooks[index]
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

    if (showReadingInsightsDialog) {
        ReadingInsightsDialog(
            snapshot = streakSnapshot,
            totalAudioListenMs = viewModel.totalListenMs,
            completedAudioBooks = viewModel.completedBooksCount,
            ebooksCount = viewModel.ebooks.size,
            onDismiss = { showReadingInsightsDialog = false }
        )
    }

    if (showQuotesHub) {
        GlobalQuotesHubSheet(
            repository = viewModel.repository,
            onDismiss = { showQuotesHub = false },
            onJumpToHighlight = { hl ->
                showQuotesHub = false
                val targetEb = ebooks.find { it.id == hl.ebookId }
                if (targetEb != null) {
                    viewModel.repository.saveEbookProgress(targetEb.id, hl.chapterIndex, 100, 0)
                    onSelectEbook(targetEb)
                }
            },
            onOpenQuoteCardStudio = { hl ->
                showQuotesHub = false
                studioQuoteHighlight = hl
            }
        )
    }

    if (studioQuoteHighlight != null) {
        val hl = studioQuoteHighlight!!
        ShareableQuoteCardStudioDialog(
            quoteText = hl.text,
            bookTitle = hl.bookTitle,
            bookAuthor = hl.bookAuthor,
            onDismiss = { studioQuoteHighlight = null }
        )
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
    val seriesLabel = remember(ebook.title) { deriveSeriesName("", ebook.title) }
    val subtitle = when {
        viewModel.smartShelfFilter == "SERIES" && seriesLabel.isNotBlank() -> "Series: $seriesLabel"
        totalChaps > 0 -> "$unitLabel ${currChap + 1} of $totalChaps"
        else -> "${ebook.format} eBook"
    }

    if (viewMode == "GRID") {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = { onSelectEbook(ebook) },
                    onLongClick = { onLongPressEbook(ebook) }
                )
        ) {
            TactileBookCover(
                coverArt = cardArt,
                fallbackIcon = Icons.Default.MenuBook,
                progress = progress,
                badgeText = ebook.format,
                cornerRadius = 12.dp,
                shadowElevation = if (isSelected) 10.dp else 6.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(0.67f)
            )
            Spacer(Modifier.height(8.dp))
            Text(ebook.title, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(ebook.author, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = if (isSelected || viewModel.smartShelfFilter == "SERIES") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
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
                TactileBookCover(
                    coverArt = cardArt,
                    fallbackIcon = Icons.Default.MenuBook,
                    progress = progress,
                    cornerRadius = 8.dp,
                    shadowElevation = 5.dp,
                    modifier = Modifier.width(54.dp).height(80.dp)
                )
                Spacer(Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(ebook.title, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(ebook.author, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary, maxLines = 1)
                }
                Icon(Icons.Default.MenuBook, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
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
    val context = LocalContext.current
    val prefs = remember { repository.getPreferences() }
    val fmtUpper = ebook.format.uppercase(Locale.ROOT)
    val resolvedChapters = remember(chapters, ebook.title) {
        resolveSmartChapterTitles(chapters, ebook.title)
    }
    val currentChapter = resolvedChapters.getOrNull(currentChapterIndex)
    val isVisualPageMode = (fmtUpper in VISUAL_PAGE_EBOOK_FORMATS) && (currentChapter?.bodyText.isNullOrBlank())

    var activePresetId by remember { mutableStateOf(prefs.getString("ebook_preset_id", "EDITORIAL") ?: "EDITORIAL") }
    var fontFamilyKey by remember { mutableStateOf(prefs.getString("ebook_font_family", "SERIF") ?: "SERIF") }
    var lineHeightMultiplier by remember { mutableFloatStateOf(prefs.getFloat("ebook_line_height_mult", 1.42f)) }
    var paragraphSpacingDp by remember { mutableFloatStateOf(prefs.getFloat("ebook_para_spacing_dp", 12f)) }
    var marginScale by remember { mutableFloatStateOf(prefs.getFloat("ebook_margin_scale", 1.0f)) }
    var isJustified by remember { mutableStateOf(prefs.getBoolean("ebook_text_justified", true)) }
    var respectPublisherCss by remember { mutableStateOf(prefs.getBoolean("ebook_respect_css", false)) }
    var customBgHex by remember { mutableStateOf(prefs.getString("ebook_custom_bg_hex", "#121E18") ?: "#121E18") }
    var customTextHex by remember { mutableStateOf(prefs.getString("ebook_custom_text_hex", "#E6EFEA") ?: "#E6EFEA") }
    var showCustomizeLayoutDrawer by remember { mutableStateOf(false) }
    var showCustomThemeBuilder by remember { mutableStateOf(false) }

    var dualPageSpreadEnabled by remember { mutableStateOf(prefs.getBoolean("ebook_dual_page_spread", true)) }
    var edgeBrightnessEnabled by remember { mutableStateOf(prefs.getBoolean("ebook_edge_brightness", true)) }
    var readerBrightness by remember { mutableFloatStateOf(prefs.getFloat("ebook_brightness_level", 0.80f)) }
    var isDraggingBrightness by remember { mutableStateOf(false) }
    var activeFootnotePopup by remember { mutableStateOf<Pair<String, String>?>(null) }
    var returnBookmark by remember { mutableStateOf<ReaderReturnBookmark?>(null) }

    var allHighlights by remember { mutableStateOf(loadAllEbookHighlights(repository)) }
    val currentChapterHighlights = remember(allHighlights, ebook.id, currentChapterIndex) {
        allHighlights.filter { it.ebookId == ebook.id && it.chapterIndex == currentChapterIndex }
    }
    var selectedPassageText by remember { mutableStateOf<String?>(null) }
    val passageSentences = remember(selectedPassageText) {
        val raw = selectedPassageText ?: ""
        if (raw.isBlank()) emptyList()
        else raw.split(Regex("(?<=[.!?…])\\s+")).map { it.trim() }.filter { it.isNotEmpty() }
    }
    var selectedSentenceIndices by remember(selectedPassageText) {
        mutableStateOf(passageSentences.indices.toSet())
    }
    val effectiveSelectedQuoteText = remember(passageSentences, selectedSentenceIndices, selectedPassageText) {
        if (passageSentences.size <= 1 || selectedSentenceIndices.isEmpty()) {
            selectedPassageText ?: ""
        } else {
            passageSentences.filterIndexed { index, _ -> index in selectedSentenceIndices }.joinToString(" ")
        }
    }

    var showAddNoteDialogForPassage by remember { mutableStateOf(false) }
    var noteDraftText by remember { mutableStateOf("") }
    var pendingHighlightColorHex by remember { mutableStateOf("#F5C542") }
    var showQuotesHubSheet by remember { mutableStateOf(false) }
    var activeQuoteStudioPassage by remember { mutableStateOf<String?>(null) }

    var isTtsBarVisible by remember { mutableStateOf(false) }
    var isTtsPlaying by remember { mutableStateOf(false) }
    var ttsSentenceIndex by remember { mutableIntStateOf(0) }
    var ttsSpeed by remember { mutableFloatStateOf(prefs.getFloat("ebook_tts_speed", 1.0f)) }
    var ttsSleepMinutesRemaining by remember { mutableIntStateOf(0) }
    var ttsEngine by remember { mutableStateOf<TextToSpeech?>(null) }
    var isTtsReady by remember { mutableStateOf(false) }
    var ttsAutoAdvancePageTrigger by remember { mutableIntStateOf(0) }
    var availableTtsVoices by remember { mutableStateOf<List<Voice>>(emptyList()) }
    var selectedVoiceName by remember { mutableStateOf(prefs.getString("ebook_tts_voice_name", "") ?: "") }
    var showVoicePickerDialog by remember { mutableStateOf(false) }

    LaunchedEffect(ebook.id) {
        recordReadingActivity(prefs, pagesAdded = 0, minutesAdded = 0)
        while (true) {
            delay(60_000L)
            recordReadingActivity(prefs, pagesAdded = 0, minutesAdded = 1)
        }
    }

    LaunchedEffect(ttsSleepMinutesRemaining, isTtsPlaying) {
        if (isTtsPlaying && ttsSleepMinutesRemaining > 0) {
            delay(60_000L)
            ttsSleepMinutesRemaining = (ttsSleepMinutesRemaining - 1).coerceAtLeast(0)
            if (ttsSleepMinutesRemaining == 0) {
                isTtsPlaying = false
                ttsEngine?.stop()
            }
        }
    }

    val coroutineScope = rememberCoroutineScope()

    DisposableEffect(context) {
        var engineRef: TextToSpeech? = null
        val tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                engineRef?.let { eng ->
                    try {
                        eng.language = Locale.getDefault()
                        eng.setAudioAttributes(
                            AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                .build()
                        )
                        val voicesList = eng.voices?.filter { v ->
                            v.locale.language.equals(Locale.getDefault().language, ignoreCase = true) ||
                                v.locale.language.equals("en", ignoreCase = true)
                        }?.sortedWith(
                            compareByDescending<Voice> { !it.isNetworkConnectionRequired }
                                .thenBy { it.name }
                        )?.take(18) ?: emptyList()

                        availableTtsVoices = voicesList
                        if (selectedVoiceName.isNotBlank()) {
                            voicesList.firstOrNull { it.name == selectedVoiceName }?.let { eng.voice = it }
                        }

                        eng.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                            override fun onStart(utteranceId: String?) {}
                            override fun onError(utteranceId: String?) {}
                            override fun onDone(utteranceId: String?) {
                                coroutineScope.launch(Dispatchers.Main) {
                                    if (isTtsPlaying) {
                                        ttsAutoAdvancePageTrigger++
                                    }
                                }
                            }
                        })
                        isTtsReady = true
                    } catch (_: Throwable) {}
                }
            }
        }
        engineRef = tts
        ttsEngine = tts
        onDispose {
            try {
                tts.stop()
                tts.shutdown()
            } catch (_: Throwable) {}
        }
    }

    LaunchedEffect(returnBookmark) {
        val currentBm = returnBookmark ?: return@LaunchedEffect
        delay(8000L)
        if (returnBookmark == currentBm) {
            returnBookmark = null
        }
    }

    LaunchedEffect(readerBrightness, isDraggingBrightness) {
        if (isDraggingBrightness) {
            val activity = context as? Activity
            activity?.window?.let { win ->
                val lp = win.attributes
                lp.screenBrightness = readerBrightness.coerceIn(0.05f, 1.0f)
                win.attributes = lp
            }
        }
    }

    val isTocChapter = remember(currentChapter, resolvedChapters) {
        val t = currentChapter?.title?.trim() ?: ""
        t.equals("Contents", ignoreCase = true) ||
            t.equals("Table of Contents", ignoreCase = true) ||
            (currentChapterIndex <= 4 && (currentChapter?.bodyText?.lines()?.count {
                it.trim().startsWith("Chapter ", ignoreCase = true)
            } ?: 0) >= 3)
    }

    val isDarkReader = readerTheme != "SEPIA" && readerTheme != "LIGHT"
    val bgColor = when (readerTheme) {
        "SEPIA" -> Color(0xFFF4ECD8)
        "LIGHT" -> Color(0xFFFAFAFA)
        "DUSK" -> Color(0xFF22272E)
        "OLED" -> Color(0xFF000000)
        "CUSTOM" -> parseSafeHexColor(customBgHex, Color(0xFF121E18))
        else -> Color(0xFF0A140F)
    }
    val textColor = when (readerTheme) {
        "SEPIA" -> Color(0xFF3B2F2F)
        "LIGHT" -> Color(0xFF1C2420)
        "DUSK" -> Color(0xFFD5DCE4)
        "OLED" -> Color(0xFFB5BDC4)
        "CUSTOM" -> parseSafeHexColor(customTextHex, Color(0xFFE6EFEA))
        else -> Color(0xFFE8F0EC)
    }
    val accentColor = when (readerTheme) {
        "SEPIA" -> Color(0xFF8B5A2B)
        "LIGHT" -> Color(0xFF2D5A27)
        "DUSK" -> Color(0xFF79C0FF)
        "OLED" -> Color(0xFFD4AF37)
        else -> Color(0xFFD4AF37)
    }
    val linkColor = when (readerTheme) {
        "SEPIA" -> Color(0xFF1B5E91)
        "LIGHT" -> Color(0xFF1558D6)
        else -> Color(0xFF58A6FF)
    }

    var showTocDialog by remember { mutableStateOf(false) }
    var showReaderSettings by remember { mutableStateOf(false) }
    var pdfOriginalPageMode by remember {
        mutableStateOf(prefs.getBoolean("pdf_original_page_mode", false))
    }

    // Fix 3: Track both subPageIndex and character offset inside the chapter so resume & font changes preserve position
    var subPageIndex by remember(ebook.id, currentChapterIndex) { mutableIntStateOf(0) }
    var activeCharOffset by remember(ebook.id, currentChapterIndex) {
        mutableIntStateOf(repository.getEbookCharOffset(ebook.id))
    }
    var startAtLastSubPage by remember { mutableStateOf(false) }
    var pendingReturnSubPage by remember { mutableIntStateOf(-1) }

    var renderedPageBitmap by remember(ebook.id, currentChapterIndex, readerTheme) { mutableStateOf<Bitmap?>(null) }
    var nextPageBitmap by remember(ebook.id, currentChapterIndex, readerTheme) { mutableStateOf<Bitmap?>(null) }
    var prevPageBitmap by remember(ebook.id, currentChapterIndex, readerTheme) { mutableStateOf<Bitmap?>(null) }
    var isRenderingVisualPage by remember(ebook.id, currentChapterIndex) { mutableStateOf(false) }

    val curlAnim = remember { Animatable(0f) }
    var touchYNorm by remember { mutableFloatStateOf(0.55f) }

    fun saveHighlightForPassage(passage: String, colorHex: String, noteText: String = "") {
        val clean = passage.trim()
        if (clean.isEmpty()) return
        val existingFiltered = allHighlights.filterNot {
            it.ebookId == ebook.id && it.chapterIndex == currentChapterIndex && it.text.equals(clean, ignoreCase = true)
        }
        val newEntry = EbookHighlight(
            ebookId = ebook.id,
            bookTitle = ebook.title,
            bookAuthor = ebook.author,
            chapterIndex = currentChapterIndex,
            chapterTitle = currentChapter?.title ?: "Chapter ${currentChapterIndex + 1}",
            text = clean,
            colorHex = colorHex,
            note = noteText.trim()
        )
        val updated = listOf(newEntry) + existingFiltered
        allHighlights = updated
        saveAllEbookHighlights(repository, updated)
    }

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

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(bgColor)
            .let { if (!isEmbeddedPane) it.windowInsetsPadding(WindowInsets.systemBars) else it }
    ) {
        val isWideScreen = maxWidth >= 580.dp
        val useDualSpread = isWideScreen && dualPageSpreadEnabled && !isVisualPageMode
        val pageStep = if (useDualSpread) 2 else 1

        Column(modifier = Modifier.fillMaxSize()) {
            Surface(
                color = bgColor,
                tonalElevation = 0.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 8.dp),
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
                            .padding(horizontal = 4.dp)
                            .clickable { showTocDialog = true },
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = ebook.title,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 14.sp,
                            color = textColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = currentChapter?.title ?: if (isVisualPageMode) "Page ${currentChapterIndex + 1}" else "Loading...",
                            fontSize = 11.sp,
                            color = accentColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (isWideScreen && !isVisualPageMode) {
                            IconButton(
                                onClick = {
                                    dualPageSpreadEnabled = !dualPageSpreadEnabled
                                    prefs.edit().putBoolean("ebook_dual_page_spread", dualPageSpreadEnabled).apply()
                                }
                            ) {
                                Icon(
                                    imageVector = if (useDualSpread) Icons.Default.MenuBook else Icons.Default.Article,
                                    contentDescription = "Toggle Dual-Page Spread",
                                    tint = accentColor
                                )
                            }
                        }
                        if (!isVisualPageMode) {
                            IconButton(
                                onClick = {
                                    isTtsBarVisible = !isTtsBarVisible
                                    if (!isTtsBarVisible) {
                                        isTtsPlaying = false
                                        ttsEngine?.stop()
                                    }
                                }
                            ) {
                                Icon(
                                    imageVector = Icons.Default.VolumeUp,
                                    contentDescription = "Read Aloud (TTS)",
                                    tint = if (isTtsPlaying) MaterialTheme.colorScheme.primary else accentColor
                                )
                            }
                        }
                        IconButton(
                            onClick = { showQuotesHubSheet = true }
                        ) {
                            Icon(Icons.Default.FormatQuote, contentDescription = "Quotes & Highlights Hub", tint = accentColor)
                        }
                        IconButton(onClick = { showTocDialog = true }) {
                            Icon(Icons.Default.FormatListNumbered, contentDescription = "Chapters", tint = accentColor)
                        }
                        TextButton(
                            onClick = { showReaderSettings = true },
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = "Aa",
                                fontSize = 17.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Serif,
                                color = accentColor
                            )
                        }
                    }
                }
            }

            HorizontalDivider(color = accentColor.copy(alpha = 0.14f))

            BoxWithConstraints(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(bgColor)
            ) {
                val density = LocalDensity.current
                val bottomBarHeightDp = 48.dp
                val canvasWidthPx = with(density) { maxWidth.toPx().toInt().coerceAtLeast(100) }
                val canvasHeightPx = with(density) { (maxHeight - bottomBarHeightDp).toPx().toInt().coerceAtLeast(100) }
                val fontSizePx = with(density) { fontSizeSp.sp.toPx() }
                val singleColumnWidthPx = if (useDualSpread) ((canvasWidthPx - 24) / 2).coerceAtLeast(100) else canvasWidthPx

                val subPages = remember(
                    currentChapter?.bodyText,
                    currentChapter?.title,
                    singleColumnWidthPx,
                    canvasHeightPx,
                    fontSizePx,
                    fontFamilyKey,
                    lineHeightMultiplier,
                    marginScale,
                    isJustified,
                    paragraphSpacingDp,
                    isVisualPageMode
                ) {
                    if (isVisualPageMode) listOf("")
                    else paginateChapterByMeasuredLayout(
                        bodyText = currentChapter?.bodyText ?: "",
                        headingText = currentChapter?.title ?: "",
                        widthPx = singleColumnWidthPx,
                        heightPx = canvasHeightPx,
                        fontSizePx = fontSizePx,
                        fontFamilyKey = fontFamilyKey,
                        lineHeightMultiplier = lineHeightMultiplier,
                        marginScale = marginScale,
                        isJustified = isJustified,
                        paragraphSpacingDp = paragraphSpacingDp
                    )
                }

                // Fix 3: Compute starting character offset of each subPage inside the chapter
                val subPageCharOffsets = remember(subPages) {
                    val offsets = IntArray(subPages.size)
                    var running = 0
                    for (i in subPages.indices) {
                        offsets[i] = running
                        running += subPages[i].length + 1
                    }
                    offsets.toList()
                }

                // Fix 3: Restore subPageIndex from saved character offset on open or font/layout reflow
                LaunchedEffect(subPages, subPageCharOffsets, currentChapterIndex, useDualSpread) {
                    if (pendingReturnSubPage >= 0) {
                        val target = pendingReturnSubPage.coerceIn(0, subPages.lastIndex.coerceAtLeast(0))
                        subPageIndex = if (useDualSpread) (target / 2) * 2 else target
                        pendingReturnSubPage = -1
                        val charOff = subPageCharOffsets.getOrElse(subPageIndex) { 0 }
                        activeCharOffset = charOff
                        repository.saveEbookCharOffset(ebook.id, charOff)
                    } else if (startAtLastSubPage) {
                        val lastIdx = subPages.lastIndex.coerceAtLeast(0)
                        subPageIndex = if (useDualSpread) (lastIdx / 2) * 2 else lastIdx
                        startAtLastSubPage = false
                        val charOff = subPageCharOffsets.getOrElse(subPageIndex) { 0 }
                        activeCharOffset = charOff
                        repository.saveEbookCharOffset(ebook.id, charOff)
                    } else if (!isVisualPageMode && subPageCharOffsets.isNotEmpty()) {
                        val savedOffset = activeCharOffset.coerceAtLeast(0)
                        val matchedIdx = subPageCharOffsets.indexOfLast { it <= savedOffset }.coerceAtLeast(0)
                        val alignedIdx = if (useDualSpread) (matchedIdx / 2) * 2 else matchedIdx
                        subPageIndex = alignedIdx.coerceIn(0, subPages.lastIndex.coerceAtLeast(0))
                    } else if (subPageIndex > subPages.lastIndex) {
                        subPageIndex = subPages.lastIndex.coerceAtLeast(0)
                    }
                }

                val canGoNext = (subPageIndex + pageStep < subPages.size) || (currentChapterIndex + 1 < resolvedChapters.size)
                val canGoPrev = (subPageIndex > 0) || (currentChapterIndex > 0)

                fun commitNextPage() {
                    selectedPassageText = null
                    ttsSentenceIndex = 0
                    recordReadingActivity(prefs, pagesAdded = pageStep, minutesAdded = 0)
                    if (subPageIndex + pageStep < subPages.size) {
                        subPageIndex += pageStep
                        val newOffset = subPageCharOffsets.getOrElse(subPageIndex) { 0 }
                        activeCharOffset = newOffset
                        repository.saveEbookProgress(ebook.id, currentChapterIndex, resolvedChapters.size.coerceAtLeast(1), newOffset)
                    } else if (currentChapterIndex + 1 < resolvedChapters.size) {
                        startAtLastSubPage = false
                        subPageIndex = 0
                        activeCharOffset = 0
                        repository.saveEbookCharOffset(ebook.id, 0)
                        onSelectChapter(currentChapterIndex + 1)
                    } else {
                        isTtsPlaying = false
                        ttsEngine?.stop()
                    }
                }

                fun commitPrevPage() {
                    selectedPassageText = null
                    ttsSentenceIndex = 0
                    if (subPageIndex > 0) {
                        subPageIndex = (subPageIndex - pageStep).coerceAtLeast(0)
                        val newOffset = subPageCharOffsets.getOrElse(subPageIndex) { 0 }
                        activeCharOffset = newOffset
                        repository.saveEbookProgress(ebook.id, currentChapterIndex, resolvedChapters.size.coerceAtLeast(1), newOffset)
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

                fun animateJumpToChapter(targetIdx: Int, targetSubPage: Int = 0, recordReturnPill: Boolean = true) {
                    if (targetIdx !in resolvedChapters.indices || curlAnim.isRunning) return
                    selectedPassageText = null
                    ttsSentenceIndex = 0
                    if (recordReturnPill && abs(targetIdx - currentChapterIndex) >= 1) {
                        returnBookmark = ReaderReturnBookmark(
                            chapterIndex = currentChapterIndex,
                            subPageIndex = subPageIndex,
                            label = "Ch ${currentChapterIndex + 1} • Pg ${subPageIndex + 1}"
                        )
                    }
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
                        pendingReturnSubPage = targetSubPage
                        subPageIndex = targetSubPage
                        activeCharOffset = 0
                        repository.saveEbookCharOffset(ebook.id, 0)
                        onSelectChapter(targetIdx)
                        curlAnim.snapTo(0f)
                    }
                }

                val currLeftText = subPages.getOrElse(subPageIndex) { "" }
                val currRightText = if (useDualSpread) subPages.getOrElse(subPageIndex + 1) { "" } else ""

                val pageSentences = remember(currLeftText, currRightText) {
                    extractSentencesFromPage(currLeftText, currRightText)
                }
                val activeTtsSentence = if (isTtsBarVisible && isTtsPlaying) {
                    pageSentences.getOrNull(ttsSentenceIndex)
                } else null

                LaunchedEffect(ttsAutoAdvancePageTrigger) {
                    if (ttsAutoAdvancePageTrigger > 0 && isTtsPlaying) {
                        if (ttsSentenceIndex + 1 < pageSentences.size) {
                            ttsSentenceIndex++
                        } else if (canGoNext) {
                            commitNextPage()
                        } else {
                            isTtsPlaying = false
                        }
                    }
                }

                LaunchedEffect(isTtsPlaying, ttsSentenceIndex, subPageIndex, currentChapterIndex, ttsSpeed, selectedVoiceName, isTtsReady) {
                    val eng = ttsEngine ?: return@LaunchedEffect
                    if (!isTtsReady) return@LaunchedEffect
                    if (isTtsPlaying && pageSentences.isNotEmpty()) {
                        val safeIdx = ttsSentenceIndex.coerceIn(0, pageSentences.lastIndex)
                        if (safeIdx != ttsSentenceIndex) ttsSentenceIndex = safeIdx
                        val sentenceToSpeak = pageSentences[safeIdx]
                        if (selectedVoiceName.isNotBlank()) {
                            availableTtsVoices.firstOrNull { it.name == selectedVoiceName }?.let { eng.voice = it }
                        }
                        eng.setSpeechRate(ttsSpeed.coerceIn(0.75f, 2.0f))
                        val params = Bundle().apply {
                            putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "fablewood_tts_$safeIdx")
                        }
                        eng.speak(sentenceToSpeak, TextToSpeech.QUEUE_FLUSH, params, "fablewood_tts_$safeIdx")
                    } else if (!isTtsPlaying) {
                        eng.stop()
                    }
                }

                val nextChapSubPages = remember(
                    currentChapterIndex, resolvedChapters, singleColumnWidthPx, canvasHeightPx,
                    fontSizePx, fontFamilyKey, lineHeightMultiplier, marginScale, isJustified, paragraphSpacingDp
                ) {
                    if (currentChapterIndex + 1 < resolvedChapters.size) {
                        val nextChap = resolvedChapters[currentChapterIndex + 1]
                        paginateChapterByMeasuredLayout(
                            bodyText = nextChap.bodyText,
                            headingText = nextChap.title,
                            widthPx = singleColumnWidthPx,
                            heightPx = canvasHeightPx,
                            fontSizePx = fontSizePx,
                            fontFamilyKey = fontFamilyKey,
                            lineHeightMultiplier = lineHeightMultiplier,
                            marginScale = marginScale,
                            isJustified = isJustified,
                            paragraphSpacingDp = paragraphSpacingDp
                        )
                    } else emptyList()
                }

                val prevChapSubPages = remember(
                    currentChapterIndex, resolvedChapters, singleColumnWidthPx, canvasHeightPx,
                    fontSizePx, fontFamilyKey, lineHeightMultiplier, marginScale, isJustified, paragraphSpacingDp
                ) {
                    if (currentChapterIndex > 0) {
                        val prevChap = resolvedChapters[currentChapterIndex - 1]
                        paginateChapterByMeasuredLayout(
                            bodyText = prevChap.bodyText,
                            headingText = prevChap.title,
                            widthPx = singleColumnWidthPx,
                            heightPx = canvasHeightPx,
                            fontSizePx = fontSizePx,
                            fontFamilyKey = fontFamilyKey,
                            lineHeightMultiplier = lineHeightMultiplier,
                            marginScale = marginScale,
                            isJustified = isJustified,
                            paragraphSpacingDp = paragraphSpacingDp
                        )
                    } else emptyList()
                }

                val bgArgb = bgColor.toArgb()
                val textArgb = textColor.toArgb()
                val accentArgb = accentColor.toArgb()
                val linkArgb = linkColor.toArgb()

                val currentFooter = if (isVisualPageMode) {
                    "Page ${currentChapterIndex + 1} of ${resolvedChapters.size}"
                } else {
                    val pagesLeftInChap = (subPages.size - 1 - (subPageIndex + pageStep - 1)).coerceAtLeast(0)
                    if (pagesLeftInChap == 0) {
                        "Last page of chapter  •  Ch ${currentChapterIndex + 1}/${resolvedChapters.size}"
                    } else {
                        "$pagesLeftInChap pg left in chapter  •  Ch ${currentChapterIndex + 1}/${resolvedChapters.size}"
                    }
                }

                val currentRenderedPage = remember(
                    canvasWidthPx, canvasHeightPx, bgArgb, textArgb, accentArgb, linkArgb,
                    currentChapter?.title, subPageIndex, currLeftText, currRightText, useDualSpread,
                    fontSizePx, fontFamilyKey, lineHeightMultiplier, marginScale, isJustified, paragraphSpacingDp,
                    renderedPageBitmap, resolvedChapters, currentChapterIndex, isTocChapter,
                    currentChapterHighlights, isDarkReader, activeTtsSentence
                ) {
                    if (useDualSpread) {
                        renderDualSpreadBookPageToBitmap(
                            totalWidthPx = canvasWidthPx,
                            heightPx = canvasHeightPx,
                            bgColorArgb = bgArgb,
                            textColorArgb = textArgb,
                            accentColorArgb = accentArgb,
                            linkColorArgb = linkArgb,
                            headingText = currentChapter?.title ?: "",
                            showHeadingOnLeft = subPageIndex == 0,
                            leftBodyText = currLeftText,
                            rightBodyText = currRightText,
                            fontSizePx = fontSizePx,
                            fontFamilyKey = fontFamilyKey,
                            lineHeightMultiplier = lineHeightMultiplier,
                            marginScale = marginScale,
                            isJustified = isJustified,
                            paragraphSpacingDp = paragraphSpacingDp,
                            allChapters = resolvedChapters,
                            currentChapterIndex = currentChapterIndex,
                            isTocChapter = isTocChapter,
                            chapterHighlights = currentChapterHighlights,
                            isDarkReader = isDarkReader,
                            activeTtsSentence = activeTtsSentence
                        )
                    } else {
                        renderBookPageToBitmap(
                            widthPx = canvasWidthPx,
                            heightPx = canvasHeightPx,
                            bgColorArgb = bgArgb,
                            textColorArgb = textArgb,
                            accentColorArgb = accentArgb,
                            linkColorArgb = linkArgb,
                            headingText = currentChapter?.title ?: "",
                            showHeading = subPageIndex == 0,
                            bodyText = currLeftText,
                            fontSizePx = fontSizePx,
                            fontFamilyKey = fontFamilyKey,
                            lineHeightMultiplier = lineHeightMultiplier,
                            marginScale = marginScale,
                            isJustified = isJustified,
                            paragraphSpacingDp = paragraphSpacingDp,
                            visualBitmap = if (isVisualPageMode) renderedPageBitmap else null,
                            allChapters = resolvedChapters,
                            currentChapterIndex = currentChapterIndex,
                            isTocChapter = isTocChapter,
                            chapterHighlights = currentChapterHighlights,
                            isDarkReader = isDarkReader,
                            activeTtsSentence = activeTtsSentence
                        )
                    }
                }

                val nextRenderedPage = remember(
                    canvasWidthPx, canvasHeightPx, bgArgb, textArgb, accentArgb, linkArgb,
                    currentChapterIndex, subPageIndex, subPages, nextChapSubPages, useDualSpread,
                    fontSizePx, fontFamilyKey, lineHeightMultiplier, marginScale, isJustified, paragraphSpacingDp,
                    nextPageBitmap, resolvedChapters, isTocChapter, currentChapterHighlights, isDarkReader
                ) {
                    val isNextNewChapter = subPageIndex + pageStep >= subPages.size
                    val nextHeading = if (isNextNewChapter && currentChapterIndex + 1 < resolvedChapters.size) {
                        resolvedChapters[currentChapterIndex + 1].title
                    } else ""
                    val nextLeft = if (!isNextNewChapter) {
                        subPages.getOrElse(subPageIndex + pageStep) { "" }
                    } else {
                        nextChapSubPages.firstOrNull() ?: ""
                    }
                    val nextRight = if (useDualSpread) {
                        if (!isNextNewChapter) subPages.getOrElse(subPageIndex + pageStep + 1) { "" }
                        else nextChapSubPages.getOrElse(1) { "" }
                    } else ""

                    if (useDualSpread) {
                        renderDualSpreadBookPageToBitmap(
                            totalWidthPx = canvasWidthPx,
                            heightPx = canvasHeightPx,
                            bgColorArgb = bgArgb,
                            textColorArgb = textArgb,
                            accentColorArgb = accentArgb,
                            linkColorArgb = linkArgb,
                            headingText = nextHeading,
                            showHeadingOnLeft = isNextNewChapter,
                            leftBodyText = nextLeft,
                            rightBodyText = nextRight,
                            fontSizePx = fontSizePx,
                            fontFamilyKey = fontFamilyKey,
                            lineHeightMultiplier = lineHeightMultiplier,
                            marginScale = marginScale,
                            isJustified = isJustified,
                            paragraphSpacingDp = paragraphSpacingDp,
                            allChapters = resolvedChapters,
                            currentChapterIndex = if (isNextNewChapter) currentChapterIndex + 1 else currentChapterIndex,
                            isTocChapter = isTocChapter && !isNextNewChapter,
                            chapterHighlights = if (isNextNewChapter) emptyList() else currentChapterHighlights,
                            isDarkReader = isDarkReader
                        )
                    } else {
                        renderBookPageToBitmap(
                            widthPx = canvasWidthPx,
                            heightPx = canvasHeightPx,
                            bgColorArgb = bgArgb,
                            textColorArgb = textArgb,
                            accentColorArgb = accentArgb,
                            linkColorArgb = linkArgb,
                            headingText = nextHeading,
                            showHeading = isNextNewChapter,
                            bodyText = nextLeft,
                            fontSizePx = fontSizePx,
                            fontFamilyKey = fontFamilyKey,
                            lineHeightMultiplier = lineHeightMultiplier,
                            marginScale = marginScale,
                            isJustified = isJustified,
                            paragraphSpacingDp = paragraphSpacingDp,
                            visualBitmap = if (isVisualPageMode) (nextPageBitmap ?: renderedPageBitmap) else null,
                            allChapters = resolvedChapters,
                            currentChapterIndex = if (isNextNewChapter) currentChapterIndex + 1 else currentChapterIndex,
                            isTocChapter = isTocChapter && !isNextNewChapter,
                            chapterHighlights = if (isNextNewChapter) emptyList() else currentChapterHighlights,
                            isDarkReader = isDarkReader
                        )
                    }
                }

                val prevRenderedPage = remember(
                    canvasWidthPx, canvasHeightPx, bgArgb, textArgb, accentArgb, linkArgb,
                    currentChapterIndex, subPageIndex, subPages, prevChapSubPages, useDualSpread,
                    fontSizePx, fontFamilyKey, lineHeightMultiplier, marginScale, isJustified, paragraphSpacingDp,
                    prevPageBitmap, resolvedChapters, isTocChapter, currentChapterHighlights, isDarkReader
                ) {
                    val isPrevChap = subPageIndex == 0
                    val prevStartIdx = if (!isPrevChap) {
                        (subPageIndex - pageStep).coerceAtLeast(0)
                    } else {
                        val lastIdx = (prevChapSubPages.size - 1).coerceAtLeast(0)
                        if (useDualSpread) (lastIdx / 2) * 2 else lastIdx
                    }
                    val prevHeading = if (!isPrevChap) {
                        currentChapter?.title ?: ""
                    } else if (currentChapterIndex > 0) {
                        resolvedChapters[currentChapterIndex - 1].title
                    } else ""
                    val prevLeft = if (!isPrevChap) {
                        subPages.getOrElse(prevStartIdx) { "" }
                    } else {
                        prevChapSubPages.getOrElse(prevStartIdx) { "" }
                    }
                    val prevRight = if (useDualSpread) {
                        if (!isPrevChap) subPages.getOrElse(prevStartIdx + 1) { "" }
                        else prevChapSubPages.getOrElse(prevStartIdx + 1) { "" }
                    } else ""

                    if (useDualSpread) {
                        renderDualSpreadBookPageToBitmap(
                            totalWidthPx = canvasWidthPx,
                            heightPx = canvasHeightPx,
                            bgColorArgb = bgArgb,
                            textColorArgb = textArgb,
                            accentColorArgb = accentArgb,
                            linkColorArgb = linkArgb,
                            headingText = prevHeading,
                            showHeadingOnLeft = prevStartIdx == 0,
                            leftBodyText = prevLeft,
                            rightBodyText = prevRight,
                            fontSizePx = fontSizePx,
                            fontFamilyKey = fontFamilyKey,
                            lineHeightMultiplier = lineHeightMultiplier,
                            marginScale = marginScale,
                            isJustified = isJustified,
                            paragraphSpacingDp = paragraphSpacingDp,
                            allChapters = resolvedChapters,
                            currentChapterIndex = if (isPrevChap) (currentChapterIndex - 1).coerceAtLeast(0) else currentChapterIndex,
                            isTocChapter = isTocChapter,
                            chapterHighlights = if (isPrevChap) emptyList() else currentChapterHighlights,
                            isDarkReader = isDarkReader
                        )
                    } else {
                        renderBookPageToBitmap(
                            widthPx = canvasWidthPx,
                            heightPx = canvasHeightPx,
                            bgColorArgb = bgArgb,
                            textColorArgb = textArgb,
                            accentColorArgb = accentArgb,
                            linkColorArgb = linkArgb,
                            headingText = prevHeading,
                            showHeading = prevStartIdx == 0,
                            bodyText = prevLeft,
                            fontSizePx = fontSizePx,
                            fontFamilyKey = fontFamilyKey,
                            lineHeightMultiplier = lineHeightMultiplier,
                            marginScale = marginScale,
                            isJustified = isJustified,
                            paragraphSpacingDp = paragraphSpacingDp,
                            visualBitmap = if (isVisualPageMode) (prevPageBitmap ?: renderedPageBitmap) else null,
                            allChapters = resolvedChapters,
                            currentChapterIndex = currentChapterIndex,
                            isTocChapter = isTocChapter,
                            chapterHighlights = if (isPrevChap) emptyList() else currentChapterHighlights,
                            isDarkReader = isDarkReader
                        )
                    }
                }

                if (isLoading || (isVisualPageMode && isRenderingVisualPage && renderedPageBitmap == null)) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = accentColor)
                    }
                } else {
                    val leftEdgeThresholdPx = with(density) { 42.dp.toPx() }
                    Canvas(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(with(density) { canvasHeightPx.toDp() })
                            .pointerInput(
                                currentChapterIndex, subPageIndex, subPages.size,
                                resolvedChapters.size, currentRenderedPage.hotspots,
                                currentRenderedPage.paragraphs, edgeBrightnessEnabled, useDualSpread
                            ) {
                                awaitEachGesture {
                                    val down = awaitFirstDown(requireUnconsumed = false)
                                    val downTimeMs = System.currentTimeMillis()
                                    val w = size.width.toFloat().coerceAtLeast(1f)
                                    val h = size.height.toFloat().coerceAtLeast(1f)
                                    val startX = down.position.x
                                    val startY = down.position.y
                                    touchYNorm = (startY / h).coerceIn(0.1f, 0.9f)

                                    var totalDragX = 0f
                                    var totalDragY = 0f
                                    var gestureMode = 0
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

                                        if (gestureMode == 0) {
                                            if (edgeBrightnessEnabled && startX <= leftEdgeThresholdPx && abs(totalDragY) > 12f && abs(totalDragY) > abs(totalDragX) * 1.25f) {
                                                gestureMode = 2
                                                isDraggingBrightness = true
                                            } else if (abs(totalDragX) > 10f && abs(totalDragX) > abs(totalDragY)) {
                                                gestureMode = if (totalDragX < 0f && canGoNext) {
                                                    1
                                                } else if (totalDragX > 0f && canGoPrev) {
                                                    -1
                                                } else {
                                                    0
                                                }
                                            }
                                        }

                                        when (gestureMode) {
                                            2 -> {
                                                change.consume()
                                                val deltaBrightness = -dy / (h * 0.75f)
                                                readerBrightness = (readerBrightness + deltaBrightness).coerceIn(0.05f, 1.0f)
                                            }
                                            1 -> if (canGoNext) {
                                                change.consume()
                                                val fingerProgress = ((w - change.position.x) / (w * 0.92f)).coerceIn(0.01f, 1f)
                                                val dragProgress = (-totalDragX / (w * 0.85f)).coerceIn(0.01f, 1f)
                                                val combined = if (startX > w * 0.65f) max(fingerProgress, dragProgress) else dragProgress
                                                coroutineScope.launch { curlAnim.snapTo(combined) }
                                            }
                                            -1 -> if (canGoPrev) {
                                                change.consume()
                                                val dragProgress = (totalDragX / (w * 0.85f)).coerceIn(0.01f, 1f)
                                                coroutineScope.launch { curlAnim.snapTo(-dragProgress) }
                                            }
                                        }
                                    }

                                    val pressDurationMs = System.currentTimeMillis() - downTimeMs

                                    if (gestureMode == 2) {
                                        prefs.edit().putFloat("ebook_brightness_level", readerBrightness).apply()
                                        coroutineScope.launch {
                                            delay(650L)
                                            isDraggingBrightness = false
                                        }
                                    } else if (gestureMode == 0 && abs(totalDragX) < 16f && abs(totalDragY) < 18f) {
                                        val tappedHotspot = currentRenderedPage.hotspots.firstOrNull { spot ->
                                            spot.rect.contains(startX, startY)
                                        }
                                        val tappedPara = currentRenderedPage.paragraphs.firstOrNull { para ->
                                            para.rect.contains(startX, startY)
                                        }

                                        if (pressDurationMs >= 360L && tappedPara != null) {
                                            selectedPassageText = tappedPara.text
                                        } else if (tappedHotspot != null) {
                                            if (tappedHotspot.footnoteBody != null) {
                                                activeFootnotePopup = Pair(
                                                    tappedHotspot.footnoteTitle ?: "Footnote",
                                                    tappedHotspot.footnoteBody
                                                )
                                            } else if (tappedHotspot.targetChapterIndex != null) {
                                                animateJumpToChapter(tappedHotspot.targetChapterIndex, 0, recordReturnPill = true)
                                            }
                                        } else if (selectedPassageText != null) {
                                            selectedPassageText = null
                                        } else if (startX > w * 0.70f && canGoNext) {
                                            animatePageTurnForward()
                                        } else if (startX < w * 0.30f && canGoPrev) {
                                            animatePageTurnBackward()
                                        } else if (tappedPara != null) {
                                            selectedPassageText = tappedPara.text
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

                if (readerBrightness < 0.25f) {
                    val dimAlpha = ((0.25f - readerBrightness) / 0.20f * 0.62f).coerceIn(0f, 0.65f)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(with(density) { canvasHeightPx.toDp() })
                            .background(Color.Black.copy(alpha = dimAlpha))
                    )
                }

                if (isDraggingBrightness) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 18.dp)
                    ) {
                        Surface(
                            color = Color.Black.copy(alpha = 0.82f),
                            shape = RoundedCornerShape(50),
                            tonalElevation = 8.dp
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = if (readerBrightness < 0.3f) Icons.Default.BrightnessLow else Icons.Default.BrightnessHigh,
                                    contentDescription = null,
                                    tint = Color(0xFFD4AF37),
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = "Brightness ${(readerBrightness * 100).roundToInt()}%",
                                    color = Color.White,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }

                if (isTtsBarVisible && selectedPassageText == null) {
                    val currentSpokenLine = pageSentences.getOrNull(ttsSentenceIndex) ?: "Ready to read aloud"
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(horizontal = 14.dp)
                            .padding(bottom = bottomBarHeightDp + 10.dp)
                    ) {
                        Surface(
                            color = MaterialTheme.colorScheme.surface,
                            shape = RoundedCornerShape(22.dp),
                            tonalElevation = 10.dp,
                            shadowElevation = 12.dp,
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f), RoundedCornerShape(22.dp))
                        ) {
                            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Surface(
                                            color = MaterialTheme.colorScheme.primary,
                                            shape = RoundedCornerShape(4.dp)
                                        ) {
                                            Text(
                                                text = "READ ALOUD",
                                                color = MaterialTheme.colorScheme.onPrimary,
                                                fontSize = 9.sp,
                                                fontWeight = FontWeight.Bold,
                                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                                            )
                                        }
                                        Spacer(Modifier.width(8.dp))
                                        Text(
                                            text = currentSpokenLine,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    IconButton(
                                        onClick = {
                                            isTtsPlaying = false
                                            isTtsBarVisible = false
                                            ttsEngine?.stop()
                                        },
                                        modifier = Modifier.size(24.dp)
                                    ) {
                                        Icon(Icons.Default.Close, contentDescription = "Close TTS", modifier = Modifier.size(16.dp))
                                    }
                                }

                                Spacer(Modifier.height(6.dp))

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        AssistChip(
                                            onClick = {
                                                val speeds = listOf(0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f)
                                                val nextIdx = (speeds.indexOfFirst { abs(it - ttsSpeed) < 0.05f } + 1) % speeds.size
                                                ttsSpeed = speeds[nextIdx]
                                                prefs.edit().putFloat("ebook_tts_speed", ttsSpeed).apply()
                                            },
                                            label = {
                                                Text(
                                                    text = String.format(Locale.US, "%.2gx", ttsSpeed),
                                                    fontSize = 11.5.sp,
                                                    fontWeight = FontWeight.Bold
                                                )
                                            }
                                        )

                                        AssistChip(
                                            onClick = { showVoicePickerDialog = true },
                                            leadingIcon = {
                                                Icon(
                                                    Icons.Default.RecordVoiceOver,
                                                    contentDescription = "Choose Voice",
                                                    modifier = Modifier.size(14.dp)
                                                )
                                            },
                                            label = {
                                                Text(
                                                    text = "Voice",
                                                    fontSize = 11.5.sp,
                                                    fontWeight = FontWeight.Bold
                                                )
                                            }
                                        )
                                    }

                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        IconButton(
                                            onClick = {
                                                if (ttsSentenceIndex > 0) {
                                                    ttsSentenceIndex--
                                                } else if (canGoPrev) {
                                                    commitPrevPage()
                                                }
                                            }
                                        ) {
                                            Icon(Icons.Default.SkipPrevious, contentDescription = "Previous Sentence")
                                        }

                                        FilledIconButton(
                                            onClick = { isTtsPlaying = !isTtsPlaying },
                                            modifier = Modifier.size(40.dp)
                                        ) {
                                            Icon(
                                                imageVector = if (isTtsPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                                contentDescription = "Play/Pause Read Aloud"
                                            )
                                        }

                                        IconButton(
                                            onClick = {
                                                if (ttsSentenceIndex + 1 < pageSentences.size) {
                                                    ttsSentenceIndex++
                                                } else if (canGoNext) {
                                                    commitNextPage()
                                                }
                                            }
                                        ) {
                                            Icon(Icons.Default.SkipNext, contentDescription = "Next Sentence")
                                        }
                                    }

                                    AssistChip(
                                        onClick = {
                                            ttsSleepMinutesRemaining = when (ttsSleepMinutesRemaining) {
                                                0 -> 15
                                                15 -> 30
                                                30 -> 45
                                                else -> 0
                                            }
                                        },
                                        leadingIcon = {
                                            Icon(
                                                Icons.Default.Alarm,
                                                contentDescription = null,
                                                modifier = Modifier.size(14.dp)
                                            )
                                        },
                                        label = {
                                            Text(
                                                text = if (ttsSleepMinutesRemaining > 0) "${ttsSleepMinutesRemaining}m" else "Sleep",
                                                fontSize = 11.sp
                                            )
                                        }
                                    )
                                }
                            }
                        }
                    }
                }

                val activePassage = selectedPassageText
                if (activePassage != null) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(horizontal = 14.dp)
                            .padding(bottom = bottomBarHeightDp + 10.dp)
                    ) {
                        Surface(
                            color = MaterialTheme.colorScheme.surface,
                            shape = RoundedCornerShape(22.dp),
                            tonalElevation = 10.dp,
                            shadowElevation = 14.dp,
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f), RoundedCornerShape(22.dp))
                        ) {
                            Column(modifier = Modifier.padding(14.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = if (passageSentences.size > 1) {
                                            "Tap sentences below to select exact quote (${selectedSentenceIndices.size}/${passageSentences.size})"
                                        } else {
                                            "Selected Quote"
                                        },
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        if (passageSentences.size > 1) {
                                            TextButton(
                                                onClick = {
                                                    selectedSentenceIndices = if (selectedSentenceIndices.size == passageSentences.size) {
                                                        setOf(0)
                                                    } else {
                                                        passageSentences.indices.toSet()
                                                    }
                                                },
                                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                                                modifier = Modifier.height(26.dp)
                                            ) {
                                                Text(
                                                    text = if (selectedSentenceIndices.size == passageSentences.size) "1st Only" else "Select All",
                                                    fontSize = 11.sp
                                                )
                                            }
                                        }
                                        IconButton(
                                            onClick = { selectedPassageText = null },
                                            modifier = Modifier.size(26.dp)
                                        ) {
                                            Icon(Icons.Default.Close, contentDescription = "Dismiss", modifier = Modifier.size(16.dp))
                                        }
                                    }
                                }

                                Spacer(Modifier.height(6.dp))

                                if (passageSentences.size > 1) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .heightIn(max = 150.dp)
                                            .verticalScroll(rememberScrollState()),
                                        verticalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        passageSentences.forEachIndexed { idx, sentence ->
                                            val isSelectedSent = idx in selectedSentenceIndices
                                            Surface(
                                                color = if (isSelectedSent) {
                                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
                                                } else {
                                                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                                                },
                                                shape = RoundedCornerShape(10.dp),
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .border(
                                                        width = 1.dp,
                                                        color = if (isSelectedSent) MaterialTheme.colorScheme.primary else Color.Transparent,
                                                        shape = RoundedCornerShape(10.dp)
                                                    )
                                                    .clickable {
                                                        val mutable = selectedSentenceIndices.toMutableSet()
                                                        if (mutable.contains(idx)) {
                                                            if (mutable.size > 1) mutable.remove(idx)
                                                        } else {
                                                            mutable.add(idx)
                                                        }
                                                        selectedSentenceIndices = mutable.sorted().toSet()
                                                    }
                                            ) {
                                                Row(
                                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Icon(
                                                        imageVector = if (isSelectedSent) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                                                        contentDescription = null,
                                                        tint = if (isSelectedSent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                                        modifier = Modifier.size(16.dp)
                                                    )
                                                    Spacer(Modifier.width(8.dp))
                                                    Text(
                                                        text = sentence,
                                                        fontSize = 12.5.sp,
                                                        color = MaterialTheme.colorScheme.onSurface,
                                                        maxLines = 2,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                }
                                            }
                                        }
                                    }
                                } else {
                                    Text(
                                        text = "“$effectiveSelectedQuoteText”",
                                        style = MaterialTheme.typography.bodySmall,
                                        fontStyle = FontStyle.Italic,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 3,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }

                                Spacer(Modifier.height(10.dp))

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                        HIGHLIGHT_COLOR_PALETTE.forEach { (hex, label) ->
                                            val swatchColor = parseSafeHexColor(hex, Color(0xFFF5C542))
                                            Box(
                                                modifier = Modifier
                                                    .size(32.dp)
                                                    .clip(CircleShape)
                                                    .background(swatchColor)
                                                    .border(1.5.dp, Color.White.copy(alpha = 0.7f), CircleShape)
                                                    .clickable {
                                                        pendingHighlightColorHex = hex
                                                        saveHighlightForPassage(effectiveSelectedQuoteText, hex)
                                                        Toast.makeText(context, "Highlighted ($label)", Toast.LENGTH_SHORT).show()
                                                        selectedPassageText = null
                                                    }
                                            )
                                        }
                                    }

                                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        FilledTonalIconButton(
                                            onClick = {
                                                noteDraftText = ""
                                                showAddNoteDialogForPassage = true
                                            },
                                            modifier = Modifier.size(34.dp)
                                        ) {
                                            Icon(Icons.Default.EditNote, contentDescription = "Add Note", modifier = Modifier.size(18.dp))
                                        }

                                        FilledTonalIconButton(
                                            onClick = {
                                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                                clipboard?.setPrimaryClip(
                                                    ClipData.newPlainText("Book Quote", "“$effectiveSelectedQuoteText” — ${ebook.title}, ${ebook.author}")
                                                )
                                                Toast.makeText(context, "Quote copied to clipboard", Toast.LENGTH_SHORT).show()
                                                selectedPassageText = null
                                            },
                                            modifier = Modifier.size(34.dp)
                                        ) {
                                            Icon(Icons.Default.ContentCopy, contentDescription = "Copy", modifier = Modifier.size(16.dp))
                                        }

                                        Button(
                                            onClick = {
                                                val passageToShare = effectiveSelectedQuoteText
                                                selectedPassageText = null
                                                saveHighlightForPassage(passageToShare, pendingHighlightColorHex)
                                                activeQuoteStudioPassage = passageToShare
                                            },
                                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                            modifier = Modifier.height(34.dp)
                                        ) {
                                            Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(15.dp))
                                            Spacer(Modifier.width(5.dp))
                                            Text("Share Quote", fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                val activeReturnBm = returnBookmark
                if (activeReturnBm != null && selectedPassageText == null && !isTtsBarVisible) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = bottomBarHeightDp + 12.dp)
                    ) {
                        Surface(
                            color = MaterialTheme.colorScheme.primary,
                            shape = RoundedCornerShape(50),
                            shadowElevation = 8.dp
                        ) {
                            Row(
                                modifier = Modifier
                                    .clickable {
                                        val targetChap = activeReturnBm.chapterIndex
                                        val targetSub = activeReturnBm.subPageIndex
                                        returnBookmark = null
                                        animateJumpToChapter(targetChap, targetSub, recordReturnPill = false)
                                    }
                                    .padding(start = 14.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Undo,
                                    contentDescription = "Return",
                                    tint = MaterialTheme.colorScheme.onPrimary,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = "Return to ${activeReturnBm.label}",
                                    color = MaterialTheme.colorScheme.onPrimary,
                                    fontSize = 12.5.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(Modifier.width(6.dp))
                                IconButton(
                                    onClick = { returnBookmark = null },
                                    modifier = Modifier.size(22.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "Dismiss",
                                        tint = MaterialTheme.colorScheme.onPrimary,
                                        modifier = Modifier.size(14.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(bottomBarHeightDp)
                        .background(bgColor),
                    verticalArrangement = Arrangement.Center
                ) {
                    HorizontalDivider(color = accentColor.copy(alpha = 0.14f))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(
                            onClick = { animatePageTurnBackward() },
                            enabled = canGoPrev,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Icon(Icons.Default.ChevronLeft, contentDescription = null, tint = if (canGoPrev) accentColor else textColor.copy(alpha = 0.3f))
                            Text("Prev", color = if (canGoPrev) accentColor else textColor.copy(alpha = 0.3f))
                        }

                        val pct = if (resolvedChapters.isNotEmpty()) {
                            (((currentChapterIndex + 1).toFloat() / resolvedChapters.size) * 100).toInt().coerceIn(1, 100)
                        } else 0

                        Text(
                            text = "$currentFooter ($pct%)",
                            fontSize = 11.5.sp,
                            color = textColor.copy(alpha = 0.78f),
                            maxLines = 1,
                            modifier = Modifier.clickable { showTocDialog = true }
                        )

                        TextButton(
                            onClick = { animatePageTurnForward() },
                            enabled = canGoNext,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text("Next", color = if (canGoNext) accentColor else textColor.copy(alpha = 0.3f))
                            Icon(Icons.Default.ChevronRight, contentDescription = null, tint = if (canGoNext) accentColor else textColor.copy(alpha = 0.3f))
                        }
                    }
                }
            }
        }
    }

    if (showVoicePickerDialog) {
        AlertDialog(
            onDismissRequest = { showVoicePickerDialog = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.RecordVoiceOver, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Text("Select Read-Aloud Voice", fontWeight = FontWeight.Bold)
                }
            },
            text = {
                if (availableTtsVoices.isEmpty()) {
                    Text(
                        text = "Using system default voice. Install additional voices in Android Settings > Accessibility > Text-to-speech output.",
                        style = MaterialTheme.typography.bodySmall
                    )
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 380.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        availableTtsVoices.forEachIndexed { idx, voiceObj ->
                            val isSelectedVoice = voiceObj.name == selectedVoiceName ||
                                (selectedVoiceName.isBlank() && idx == 0)
                            val country = voiceObj.locale.displayCountry.ifBlank { voiceObj.locale.displayLanguage }
                            val qualityTag = if (voiceObj.isNetworkConnectionRequired) "HD Network" else "Local"
                            val friendlyLabel = "Narrator ${idx + 1} ($country • $qualityTag)"

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable {
                                        selectedVoiceName = voiceObj.name
                                        ttsEngine?.voice = voiceObj
                                        prefs.edit().putString("ebook_tts_voice_name", voiceObj.name).apply()
                                        showVoicePickerDialog = false
                                    }
                                    .padding(vertical = 8.dp, horizontal = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(selected = isSelectedVoice, onClick = null)
                                Spacer(Modifier.width(10.dp))
                                Column {
                                    Text(friendlyLabel, fontWeight = if (isSelectedVoice) FontWeight.Bold else FontWeight.Normal)
                                    Text(
                                        text = voiceObj.name,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showVoicePickerDialog = false }) {
                    Text("Done", fontWeight = FontWeight.Bold)
                }
            }
        )
    }

    if (showAddNoteDialogForPassage && selectedPassageText != null) {
        val passage = effectiveSelectedQuoteText
        AlertDialog(
            onDismissRequest = { showAddNoteDialogForPassage = false },
            title = { Text("Add Note to Highlight", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "“${passage.take(120)}${if (passage.length > 120) "…" else ""}”",
                        style = MaterialTheme.typography.bodySmall,
                        fontStyle = FontStyle.Italic,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = noteDraftText,
                        onValueChange = { noteDraftText = it },
                        label = { Text("Your thoughts or note") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        saveHighlightForPassage(passage, pendingHighlightColorHex, noteDraftText)
                        showAddNoteDialogForPassage = false
                        selectedPassageText = null
                        Toast.makeText(context, "Highlight & note saved!", Toast.LENGTH_SHORT).show()
                    }
                ) {
                    Text("Save Note")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddNoteDialogForPassage = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showQuotesHubSheet) {
        GlobalQuotesHubSheet(
            repository = repository,
            filterEbookId = ebook.id,
            onDismiss = { showQuotesHubSheet = false },
            onJumpToHighlight = { hl ->
                showQuotesHubSheet = false
                if (hl.ebookId == ebook.id && hl.chapterIndex in resolvedChapters.indices) {
                    subPageIndex = 0
                    activeCharOffset = 0
                    repository.saveEbookCharOffset(ebook.id, 0)
                    onSelectChapter(hl.chapterIndex)
                }
            },
            onOpenQuoteCardStudio = { hl ->
                showQuotesHubSheet = false
                activeQuoteStudioPassage = hl.text
            }
        )
    }

    if (activeQuoteStudioPassage != null) {
        ShareableQuoteCardStudioDialog(
            quoteText = activeQuoteStudioPassage!!,
            bookTitle = ebook.title,
            bookAuthor = ebook.author,
            onDismiss = { activeQuoteStudioPassage = null }
        )
    }

    if (activeFootnotePopup != null) {
        val (fnTitle, fnBody) = activeFootnotePopup!!
        AlertDialog(
            onDismissRequest = { activeFootnotePopup = null },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Info, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Text(fnTitle, fontWeight = FontWeight.Bold)
                }
            },
            text = {
                Text(
                    text = fnBody,
                    style = MaterialTheme.typography.bodyMedium,
                    lineHeight = 22.sp
                )
            },
            confirmButton = {
                TextButton(onClick = { activeFootnotePopup = null }) {
                    Text("Close")
                }
            }
        )
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
                                    if (abs(idx - currentChapterIndex) >= 1) {
                                        returnBookmark = ReaderReturnBookmark(
                                            chapterIndex = currentChapterIndex,
                                            subPageIndex = subPageIndex,
                                            label = "Ch ${currentChapterIndex + 1} • Pg ${subPageIndex + 1}"
                                        )
                                    }
                                    subPageIndex = 0
                                    activeCharOffset = 0
                                    repository.saveEbookCharOffset(ebook.id, 0)
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
        ModalBottomSheet(
            onDismissRequest = { showReaderSettings = false },
            containerColor = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Reading Appearance",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    TextButton(onClick = { showReaderSettings = false }) {
                        Text("Done", fontWeight = FontWeight.Bold)
                    }
                }

                Text(
                    text = "DESIGNER PRESETS",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    DESIGNER_READER_PRESETS.forEach { preset ->
                        val isSelected = activePresetId == preset.id
                        val cardBg = when (preset.themeKey) {
                            "SEPIA" -> Color(0xFFF4ECD8)
                            "LIGHT" -> Color(0xFFFAFAFA)
                            "OLED" -> Color(0xFF050505)
                            else -> Color(0xFF0C1A14)
                        }
                        val cardFg = when (preset.themeKey) {
                            "SEPIA" -> Color(0xFF3B2F2F)
                            "LIGHT" -> Color(0xFF1C2420)
                            else -> Color(0xFFE0E6E3)
                        }

                        Surface(
                            color = cardBg,
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier
                                .width(142.dp)
                                .border(
                                    width = if (isSelected) 2.5.dp else 1.dp,
                                    color = if (isSelected) MaterialTheme.colorScheme.primary else Color.Gray.copy(alpha = 0.35f),
                                    shape = RoundedCornerShape(16.dp)
                                )
                                .clickable {
                                    activePresetId = preset.id
                                    fontFamilyKey = preset.fontFamilyKey
                                    lineHeightMultiplier = preset.lineHeightMultiplier
                                    paragraphSpacingDp = preset.paragraphSpacingDp
                                    marginScale = preset.marginScale
                                    isJustified = preset.isJustified
                                    onUpdateFontSize(preset.fontSizeSp)
                                    onUpdateTheme(preset.themeKey)
                                    prefs.edit()
                                        .putString("ebook_preset_id", preset.id)
                                        .putString("ebook_font_family", preset.fontFamilyKey)
                                        .putFloat("ebook_line_height_mult", preset.lineHeightMultiplier)
                                        .putFloat("ebook_para_spacing_dp", preset.paragraphSpacingDp)
                                        .putFloat("ebook_margin_scale", preset.marginScale)
                                        .putBoolean("ebook_text_justified", preset.isJustified)
                                        .apply()
                                }
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(
                                    text = "Aa",
                                    fontSize = 22.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = if (preset.fontFamilyKey == "SANS") FontFamily.SansSerif else FontFamily.Serif,
                                    color = cardFg
                                )
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    text = preset.name,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp,
                                    color = cardFg,
                                    maxLines = 1
                                )
                                Text(
                                    text = preset.subtitle,
                                    fontSize = 10.5.sp,
                                    color = cardFg.copy(alpha = 0.72f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }

                Text(
                    text = "COLOR THEME",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val coreThemes = listOf(
                        Triple("LIGHT", "Day", Color(0xFFFAFAFA) to Color(0xFF1C2420)),
                        Triple("SEPIA", "Paper", Color(0xFFF4ECD8) to Color(0xFF3B2F2F)),
                        Triple("DUSK", "Dusk", Color(0xFF22272E) to Color(0xFFD5DCE4)),
                        Triple("FOREST", "Forest", Color(0xFF0C1A14) to Color(0xFFE8F0EC)),
                        Triple("OLED", "OLED", Color(0xFF000000) to Color(0xFFB5BDC4))
                    )
                    coreThemes.forEach { (key, label, colors) ->
                        val selected = readerTheme == key
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .clickable {
                                    activePresetId = "CUSTOM"
                                    onUpdateTheme(key)
                                },
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(CircleShape)
                                    .background(colors.first)
                                    .border(
                                        width = if (selected) 2.5.dp else 1.dp,
                                        color = if (selected) MaterialTheme.colorScheme.primary else Color.Gray.copy(alpha = 0.4f),
                                        shape = CircleShape
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("Aa", color = colors.second, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            }
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = label,
                                fontSize = 11.sp,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    }
                }

                OutlinedButton(
                    onClick = { showCustomThemeBuilder = !showCustomThemeBuilder },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.Palette, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(if (readerTheme == "CUSTOM") "Custom Hex Theme Active" else "Custom Theme Builder")
                }

                if (showCustomThemeBuilder) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                OutlinedTextField(
                                    value = customBgHex,
                                    onValueChange = { customBgHex = it },
                                    label = { Text("Paper Hex") },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f)
                                )
                                OutlinedTextField(
                                    value = customTextHex,
                                    onValueChange = { customTextHex = it },
                                    label = { Text("Ink Hex") },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            Button(
                                onClick = {
                                    prefs.edit()
                                        .putString("ebook_custom_bg_hex", customBgHex)
                                        .putString("ebook_custom_text_hex", customTextHex)
                                        .apply()
                                    onUpdateTheme("CUSTOM")
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Apply Custom Palette")
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FilledTonalButton(
                        onClick = {
                            activePresetId = "CUSTOM"
                            onUpdateFontSize((fontSizeSp - 1.5f).coerceAtLeast(13f))
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("A-", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    }
                    Text(
                        text = "${fontSizeSp.roundToInt()} pt",
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 20.dp)
                    )
                    FilledTonalButton(
                        onClick = {
                            activePresetId = "CUSTOM"
                            onUpdateFontSize((fontSizeSp + 1.5f).coerceAtMost(30f))
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("A+", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    }
                }

                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { showCustomizeLayoutDrawer = !showCustomizeLayoutDrawer },
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Tune, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.width(10.dp))
                                Text("Customize Typography & Layout", fontWeight = FontWeight.Bold)
                            }
                            Icon(
                                imageVector = if (showCustomizeLayoutDrawer) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                contentDescription = null
                            )
                        }

                        if (showCustomizeLayoutDrawer) {
                            Column(
                                modifier = Modifier.padding(top = 16.dp),
                                verticalArrangement = Arrangement.spacedBy(14.dp)
                            ) {
                                Text("Typeface", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    listOf(
                                        "SERIF" to "Classic Serif",
                                        "BOOK" to "Bookerly",
                                        "SANS" to "Clean Sans"
                                    ).forEach { (key, label) ->
                                        FilterChip(
                                            selected = fontFamilyKey == key,
                                            onClick = {
                                                fontFamilyKey = key
                                                activePresetId = "CUSTOM"
                                                prefs.edit().putString("ebook_font_family", key).apply()
                                            },
                                            label = { Text(label, fontSize = 12.sp, maxLines = 1) },
                                            modifier = Modifier.weight(1f)
                                        )
                                    }
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text("2-Column Foldable Spread", fontWeight = FontWeight.SemiBold)
                                        Text(
                                            "Open-book 2-page spread when unfolded or in landscape",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Switch(
                                        checked = dualPageSpreadEnabled,
                                        onCheckedChange = { enabled ->
                                            dualPageSpreadEnabled = enabled
                                            prefs.edit().putBoolean("ebook_dual_page_spread", enabled).apply()
                                        }
                                    )
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text("Left-Edge Brightness Swipe", fontWeight = FontWeight.SemiBold)
                                        Text(
                                            "Slide up/down on the far-left edge for brightness & extra-dim",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Switch(
                                        checked = edgeBrightnessEnabled,
                                        onCheckedChange = {
                                            edgeBrightnessEnabled = it
                                            prefs.edit().putBoolean("ebook_edge_brightness", it).apply()
                                        }
                                    )
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text("Justified Book Alignment", fontWeight = FontWeight.SemiBold)
                                        Text(
                                            "Crisp right edges with automatic hyphenation",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Switch(
                                        checked = isJustified,
                                        onCheckedChange = {
                                            isJustified = it
                                            activePresetId = "CUSTOM"
                                            prefs.edit().putBoolean("ebook_text_justified", it).apply()
                                        }
                                    )
                                }

                                Column {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text("Line Spacing", fontWeight = FontWeight.SemiBold)
                                        Text(String.format(Locale.US, "%.2fx", lineHeightMultiplier), color = MaterialTheme.colorScheme.primary)
                                    }
                                    Slider(
                                        value = lineHeightMultiplier,
                                        onValueChange = {
                                            lineHeightMultiplier = it
                                            activePresetId = "CUSTOM"
                                            prefs.edit().putFloat("ebook_line_height_mult", it).apply()
                                        },
                                        valueRange = 1.15f..1.90f
                                    )
                                }

                                Column {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text("Paragraph Spacing", fontWeight = FontWeight.SemiBold)
                                        Text("${paragraphSpacingDp.roundToInt()} pt", color = MaterialTheme.colorScheme.primary)
                                    }
                                    Slider(
                                        value = paragraphSpacingDp,
                                        onValueChange = {
                                            paragraphSpacingDp = it
                                            activePresetId = "CUSTOM"
                                            prefs.edit().putFloat("ebook_para_spacing_dp", it).apply()
                                        },
                                        valueRange = 0f..24f
                                    )
                                }

                                Column {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text("Page Margins", fontWeight = FontWeight.SemiBold)
                                        Text("${(marginScale * 100).roundToInt()}%", color = MaterialTheme.colorScheme.primary)
                                    }
                                    Slider(
                                        value = marginScale,
                                        onValueChange = {
                                            marginScale = it
                                            activePresetId = "CUSTOM"
                                            prefs.edit().putFloat("ebook_margin_scale", it).apply()
                                        },
                                        valueRange = 0.65f..1.50f
                                    )
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text("Respect Publisher Styles (CSS)", fontWeight = FontWeight.SemiBold)
                                        Text(
                                            "Use book's native paragraph and spacing defaults",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Switch(
                                        checked = respectPublisherCss,
                                        onCheckedChange = {
                                            respectPublisherCss = it
                                            if (it) {
                                                lineHeightMultiplier = 1.38f
                                                paragraphSpacingDp = 10f
                                                marginScale = 1.0f
                                            }
                                            prefs.edit().putBoolean("ebook_respect_css", it).apply()
                                        }
                                    )
                                }

                                if (fmtUpper == "PDF") {
                                    HorizontalDivider()
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text("Reflow PDF like ePub", fontWeight = FontWeight.SemiBold)
                                            Text(
                                                "Extract text for custom fonts, or turn off for auto-cropped page view",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        Switch(
                                            checked = !pdfOriginalPageMode,
                                            onCheckedChange = { reflowEnabled ->
                                                pdfOriginalPageMode = !reflowEnabled
                                                prefs.edit()
                                                    .putBoolean("pdf_original_page_mode", pdfOriginalPageMode)
                                                    .apply()
                                                onReloadEbook()
                                            }
                                        )
                                    }
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

    val seriesName = remember(book.series, book.title) { deriveSeriesName(book.series, book.title) }
    val displayProgress = if (isSelected && liveProgress != null && liveProgress > 0f) liveProgress else savedProgress
    val displaySubtitle = when {
        viewModel.smartShelfFilter == "SERIES" && seriesName.isNotBlank() -> "Series: $seriesName"
        isSelected && !liveSubtitle.isNullOrEmpty() -> liveSubtitle
        else -> savedSubtitle
    }

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
        TactileBookCover(
            coverArt = coverArt,
            fallbackIcon = Icons.Default.Book,
            progress = progress,
            badgeIcon = Icons.Default.Headphones,
            cornerRadius = 12.dp,
            shadowElevation = if (isSelected) 10.dp else 6.dp,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
        )

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
                color = if (progress == 1f || isSelected || subtitleText.startsWith("Series:")) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
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
            TactileBookCover(
                coverArt = coverArt,
                fallbackIcon = Icons.Default.Book,
                progress = progress,
                cornerRadius = 10.dp,
                shadowElevation = 5.dp,
                modifier = Modifier.size(64.dp)
            )

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
                        color = if (progress == 1f || isSelected || subtitleText.startsWith("Series:")) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
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

                        IconButton(onClick = onQuickRewind, modifier = Modifier.size(48.dp)) {
                            SymmetricalSkipIcon(
                                seconds = skipIntervalSeconds,
                                isForward = false,
                                tint = MaterialTheme.colorScheme.onSurface,
                                iconSize = 38.dp,
                                fontSize = 10.sp
                            )
                        }

                        IconButton(onClick = onPlayPause, modifier = Modifier.size(64.dp)) {
                            Icon(
                                imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = "Play/Pause",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(54.dp)
                            )
                        }

                        IconButton(onClick = onQuickForward, modifier = Modifier.size(48.dp)) {
                            SymmetricalSkipIcon(
                                seconds = skipIntervalSeconds,
                                isForward = true,
                                tint = MaterialTheme.colorScheme.onSurface,
                                iconSize = 38.dp,
                                fontSize = 10.sp
                            )
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
                                    imageVector = Icons.Default.Alarm,
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

                    TactileBookCover(
                        coverArt = coverArt,
                        fallbackIcon = Icons.Default.Headphones,
                        progress = percentRead / 100f,
                        cornerRadius = 22.dp,
                        shadowElevation = 16.dp,
                        modifier = Modifier.size(280.dp)
                    )

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

                TactileBookCover(
                    coverArt = coverArt,
                    fallbackIcon = Icons.Default.Headphones,
                    progress = percentRead / 100f,
                    cornerRadius = 22.dp,
                    shadowElevation = 16.dp,
                    modifier = Modifier.size(if (isEmbeddedPane) 250.dp else 300.dp)
                )

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