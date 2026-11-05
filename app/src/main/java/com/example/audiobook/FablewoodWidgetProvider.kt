package com.example.audiobook

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.util.Log
import android.widget.RemoteViews
import java.io.File
import java.security.MessageDigest
import java.util.Locale

class FablewoodWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        updateAllWidgets(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            AudiobookPlaybackService.ACTION_WIDGET_PLAY_PAUSE,
            AudiobookPlaybackService.ACTION_WIDGET_REWIND,
            AudiobookPlaybackService.ACTION_WIDGET_FORWARD -> {
                val serviceIntent = Intent(context, AudiobookPlaybackService::class.java).apply {
                    action = intent.action
                }
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                        intent.action == AudiobookPlaybackService.ACTION_WIDGET_PLAY_PAUSE
                    ) {
                        context.startForegroundService(serviceIntent)
                    } else {
                        context.startService(serviceIntent)
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "Could not start service from widget action", t)
                }
            }
        }
    }

    companion object {
        private const val TAG = "FablewoodWidget"

        private fun resolveLayoutId(context: Context): Int {
            val candidates = listOf(
                "fablewood_widget",
                "widget_fablewood",
                "audiobook_widget",
                "widget_player"
            )
            for (name in candidates) {
                val id = context.resources.getIdentifier(name, "layout", context.packageName)
                if (id != 0) return id
            }
            return try {
                val field = R.layout::class.java.fields.firstOrNull {
                    it.name.contains("widget", ignoreCase = true)
                } ?: R.layout::class.java.fields.firstOrNull()
                field?.getInt(null) ?: 0
            } catch (_: Throwable) {
                0
            }
        }

        private fun resolveViewId(context: Context, vararg names: String): Int {
            for (name in names) {
                val id = context.resources.getIdentifier(name, "id", context.packageName)
                if (id != 0) return id
            }
            return 0
        }

        // Renders a razor-sharp 256x256 High-DPI Play or Pause circular button bitmap for the Home Screen Widget
        private fun createCrispPlayPauseBitmap(isPlaying: Boolean): Bitmap {
            val size = 256
            val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)

            val circlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#D4AF37")
                style = Paint.Style.FILL
            }
            val cx = size / 2f
            val cy = size / 2f
            val radius = size * 0.44f
            canvas.drawCircle(cx, cy, radius, circlePaint)

            val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#08120E")
                style = Paint.Style.FILL
            }

            if (isPlaying) {
                val barW = size * 0.085f
                val barH = size * 0.34f
                val gap = size * 0.065f
                val top = cy - barH / 2f
                val bottom = cy + barH / 2f
                val leftRect = RectF(cx - gap - barW, top, cx - gap, bottom)
                val rightRect = RectF(cx + gap, top, cx + gap + barW, bottom)
                canvas.drawRoundRect(leftRect, 10f, 10f, iconPaint)
                canvas.drawRoundRect(rightRect, 10f, 10f, iconPaint)
            } else {
                val triH = size * 0.36f
                val triW = size * 0.32f
                val leftX = cx - (triW * 0.38f)
                val rightX = cx + (triW * 0.62f)
                val path = Path().apply {
                    moveTo(leftX, cy - triH / 2f)
                    lineTo(rightX, cy)
                    lineTo(leftX, cy + triH / 2f)
                    close()
                }
                canvas.drawPath(path, iconPaint)
            }
            return bmp
        }

        // Renders a razor-sharp 256x256 High-DPI Symmetrical Rewind / Fast-Forward 15s icon bitmap
        private fun createCrispSkipBitmap(seconds: Int, isForward: Boolean): Bitmap {
            val size = 256
            val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            val cx = size / 2f
            val cy = size / 2f

            val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#183225")
                style = Paint.Style.FILL
            }
            canvas.drawCircle(cx, cy, size * 0.44f, bgPaint)

            val strokeW = size * 0.068f
            val arcRadius = size * 0.29f
            val arcRect = RectF(cx - arcRadius, cy - arcRadius, cx + arcRadius, cy + arcRadius)

            val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                style = Paint.Style.STROKE
                strokeWidth = strokeW
                strokeCap = Paint.Cap.ROUND
            }

            val startAngle = if (isForward) -65f else -115f
            val sweepAngle = if (isForward) 300f else -300f
            canvas.drawArc(arcRect, startAngle, sweepAngle, false, arcPaint)

            val headPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                style = Paint.Style.FILL
            }
            val topY = cy - arcRadius
            val arrowW = size * 0.11f
            val arrowH = size * 0.095f
            val dir = if (isForward) 1f else -1f
            val tipX = cx + (dir * size * 0.04f)

            val headPath = Path().apply {
                moveTo(tipX, topY)
                lineTo(tipX - (dir * arrowW), topY - arrowH)
                lineTo(tipX - (dir * arrowW), topY + arrowH)
                close()
            }
            canvas.drawPath(headPath, headPaint)

            val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = size * 0.23f
                typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
                textAlign = Paint.Align.CENTER
            }
            val textOffset = (textPaint.descent() + textPaint.ascent()) / 2f
            canvas.drawText("$seconds", cx, cy - textOffset + (size * 0.02f), textPaint)

            return bmp
        }

        private fun sha256Hex(input: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
            val sb = StringBuilder(32)
            for (i in 0 until 16) {
                sb.append(String.format(Locale.US, "%02x", digest[i].toInt() and 0xFF))
            }
            return sb.toString()
        }

        private fun loadCachedCoverBitmap(context: Context, bookId: String): Bitmap? {
            if (bookId.isEmpty()) return null
            return try {
                val coversDir = File(context.filesDir, "covers")
                val coverFile = File(coversDir, "${sha256Hex(bookId)}.png")
                if (coverFile.exists()) {
                    BitmapFactory.decodeFile(coverFile.absolutePath)
                } else null
            } catch (_: Throwable) {
                null
            }
        }

        fun updateAllWidgets(
            context: Context,
            isPlayingOverride: Boolean? = null,
            titleOverride: String? = null,
            authorOverride: String? = null
        ) {
            try {
                val appCtx = context.applicationContext
                val appWidgetManager = AppWidgetManager.getInstance(appCtx)
                val componentName = ComponentName(appCtx, FablewoodWidgetProvider::class.java)
                val widgetIds = appWidgetManager.getAppWidgetIds(componentName)
                if (widgetIds == null || widgetIds.isEmpty()) return

                val layoutId = resolveLayoutId(appCtx)
                if (layoutId == 0) return

                val repo = AudiobookRepository(appCtx)
                val prefs = repo.getPreferences()
                val skipSecs = prefs.getInt("skip_interval", 15).coerceIn(5, 60)
                val books = repo.loadBooksCache()
                val lastBookId = repo.getLastBookId()
                val activeBook = books.find { it.id == lastBookId } ?: books.firstOrNull()

                val displayTitle = titleOverride ?: activeBook?.title ?: "Fablewood Audiobooks"
                val displayAuthor = authorOverride ?: activeBook?.author ?: "Tap to open library"
                val isPlaying = isPlayingOverride ?: false

                val openAppPendingIntent = PendingIntent.getActivity(
                    appCtx,
                    100,
                    Intent(appCtx, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    },
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )

                val playPausePendingIntent = PendingIntent.getBroadcast(
                    appCtx,
                    101,
                    Intent(appCtx, FablewoodWidgetProvider::class.java).apply {
                        action = AudiobookPlaybackService.ACTION_WIDGET_PLAY_PAUSE
                    },
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )

                val rewindPendingIntent = PendingIntent.getBroadcast(
                    appCtx,
                    102,
                    Intent(appCtx, FablewoodWidgetProvider::class.java).apply {
                        action = AudiobookPlaybackService.ACTION_WIDGET_REWIND
                    },
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )

                val forwardPendingIntent = PendingIntent.getBroadcast(
                    appCtx,
                    103,
                    Intent(appCtx, FablewoodWidgetProvider::class.java).apply {
                        action = AudiobookPlaybackService.ACTION_WIDGET_FORWARD
                    },
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )

                val playPauseBmp = createCrispPlayPauseBitmap(isPlaying)
                val rewindBmp = createCrispSkipBitmap(skipSecs, isForward = false)
                val forwardBmp = createCrispSkipBitmap(skipSecs, isForward = true)
                val coverBmp = if (activeBook != null) loadCachedCoverBitmap(appCtx, activeBook.id) else null

                val rootId = resolveViewId(appCtx, "widget_root", "widget_container", "root")
                val coverId = resolveViewId(appCtx, "widget_cover", "widget_art", "cover_art", "iv_cover")
                val titleId = resolveViewId(appCtx, "widget_title", "tv_title", "book_title", "title")
                val authorId = resolveViewId(appCtx, "widget_author", "widget_subtitle", "tv_author", "author")
                val playId = resolveViewId(appCtx, "widget_play_pause", "btn_play_pause", "widget_play", "play_pause")
                val rewindId = resolveViewId(appCtx, "widget_rewind", "btn_rewind", "rewind", "widget_prev")
                val forwardId = resolveViewId(appCtx, "widget_forward", "widget_fast_forward", "btn_forward", "forward", "widget_next")

                for (widgetId in widgetIds) {
                    val views = RemoteViews(appCtx.packageName, layoutId)

                    if (rootId != 0) views.setOnClickPendingIntent(rootId, openAppPendingIntent)
                    if (coverId != 0) {
                        views.setOnClickPendingIntent(coverId, openAppPendingIntent)
                        if (coverBmp != null) {
                            views.setImageViewBitmap(coverId, coverBmp)
                        }
                    }
                    if (titleId != 0) {
                        views.setTextViewText(titleId, displayTitle)
                        views.setOnClickPendingIntent(titleId, openAppPendingIntent)
                    }
                    if (authorId != 0) {
                        views.setTextViewText(authorId, displayAuthor)
                    }
                    if (playId != 0) {
                        views.setImageViewBitmap(playId, playPauseBmp)
                        views.setOnClickPendingIntent(playId, playPausePendingIntent)
                    }
                    if (rewindId != 0) {
                        views.setImageViewBitmap(rewindId, rewindBmp)
                        views.setOnClickPendingIntent(rewindId, rewindPendingIntent)
                    }
                    if (forwardId != 0) {
                        views.setImageViewBitmap(forwardId, forwardBmp)
                        views.setOnClickPendingIntent(forwardId, forwardPendingIntent)
                    }

                    appWidgetManager.updateAppWidget(widgetId, views)
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Failed updating Fablewood widget", t)
            }
        }
    }
}
// END OF FILE