package com.example.audiobook

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.util.Log
import android.widget.RemoteViews
import androidx.core.content.ContextCompat
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class FablewoodWidgetProvider : AppWidgetProvider() {

    companion object {
        private const val TAG = "Fablewood"
        const val ACTION_WIDGET_PLAY_PAUSE = "com.example.audiobook.ACTION_WIDGET_PLAY_PAUSE"
        const val ACTION_WIDGET_REWIND = "com.example.audiobook.ACTION_WIDGET_REWIND"
        const val ACTION_WIDGET_FORWARD = "com.example.audiobook.ACTION_WIDGET_FORWARD"

        private val widgetScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        fun updateAllWidgets(
            context: Context,
            isPlayingOverride: Boolean? = null,
            titleOverride: String? = null,
            authorOverride: String? = null
        ) {
            val appCtx = context.applicationContext
            val manager = AppWidgetManager.getInstance(appCtx)
            val component = ComponentName(appCtx, FablewoodWidgetProvider::class.java)
            val ids = manager.getAppWidgetIds(component)
            if (ids.isEmpty()) return

            widgetScope.launch {
                try {
                    val repo = AudiobookRepository(appCtx)
                    val books = repo.loadBooksCache()
                    val lastBookId = repo.getLastBookId()
                    val activeBook = books.find { it.id == lastBookId } ?: books.firstOrNull()

                    val displayTitle = titleOverride?.takeIf { it.isNotBlank() }
                        ?: activeBook?.title
                        ?: "Fablewood Audiobooks"
                    val displayAuthor = authorOverride?.takeIf { it.isNotBlank() }
                        ?: activeBook?.let { if (it.series.isNotEmpty()) "${it.series} • ${it.author}" else it.author }
                        ?: "Tap to open library"

                    val rawCover = if (activeBook != null) repo.getOrFetchCoverArt(activeBook) else null
                    val coverBitmap = if (rawCover != null) {
                        createRoundedBitmap(rawCover, 28f)
                    } else {
                        createPlaceholderCover()
                    }

                    val isPlaying = isPlayingOverride ?: false

                    val views = RemoteViews(appCtx.packageName, R.layout.fablewood_widget).apply {
                        setTextViewText(R.id.widget_title, displayTitle)
                        setTextViewText(R.id.widget_author, displayAuthor)
                        setImageViewBitmap(R.id.widget_cover, coverBitmap)
                        setImageViewResource(
                            R.id.widget_btn_play_pause,
                            if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
                        )

                        val openAppIntent = Intent(appCtx, MainActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
                        }
                        val openAppPending = PendingIntent.getActivity(
                            appCtx,
                            100,
                            openAppIntent,
                            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                        )
                        setOnClickPendingIntent(R.id.widget_root, openAppPending)
                        setOnClickPendingIntent(R.id.widget_cover, openAppPending)
                        setOnClickPendingIntent(R.id.widget_text_container, openAppPending)

                        setOnClickPendingIntent(
                            R.id.widget_btn_play_pause,
                            buildActionPendingIntent(appCtx, ACTION_WIDGET_PLAY_PAUSE, 101)
                        )
                        setOnClickPendingIntent(
                            R.id.widget_btn_rewind,
                            buildActionPendingIntent(appCtx, ACTION_WIDGET_REWIND, 102)
                        )
                        setOnClickPendingIntent(
                            R.id.widget_btn_forward,
                            buildActionPendingIntent(appCtx, ACTION_WIDGET_FORWARD, 103)
                        )
                    }

                    manager.updateAppWidget(ids, views)
                } catch (t: Throwable) {
                    Log.w(TAG, "Failed updating Fablewood widget", t)
                }
            }
        }

        private fun buildActionPendingIntent(context: Context, action: String, requestCode: Int): PendingIntent {
            val intent = Intent(context, FablewoodWidgetProvider::class.java).apply {
                this.action = action
            }
            return PendingIntent.getBroadcast(
                context,
                requestCode,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        }

        private fun createRoundedBitmap(source: Bitmap, cornerRadiusPx: Float): Bitmap {
            val size = 192
            val scaled = Bitmap.createScaledBitmap(source, size, size, true)
            val output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(output)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            val rect = Rect(0, 0, size, size)
            val rectF = RectF(rect)

            canvas.drawRoundRect(rectF, cornerRadiusPx, cornerRadiusPx, paint)
            paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
            canvas.drawBitmap(scaled, rect, rect, paint)
            return output
        }

        private fun createPlaceholderCover(): Bitmap {
            val size = 192
            val output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(output)
            val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFF0C261B.toInt()
            }
            val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFFD4AF37.toInt()
                style = Paint.Style.STROKE
                strokeWidth = 4f
            }
            val rectF = RectF(4f, 4f, size - 4f, size - 4f)
            canvas.drawRoundRect(rectF, 28f, 28f, bgPaint)
            canvas.drawRoundRect(rectF, 28f, 28f, borderPaint)
            return output
        }
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        updateAllWidgets(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        val action = intent.action ?: return
        if (action != ACTION_WIDGET_PLAY_PAUSE && action != ACTION_WIDGET_REWIND && action != ACTION_WIDGET_FORWARD) {
            return
        }

        val appCtx = context.applicationContext
        val pendingResult = goAsync()
        val sessionToken = SessionToken(appCtx, ComponentName(appCtx, AudiobookPlaybackService::class.java))
        val controllerFuture = MediaController.Builder(appCtx, sessionToken).buildAsync()

        controllerFuture.addListener({
            widgetScope.launch {
                try {
                    val controller = controllerFuture.get()
                    val repo = AudiobookRepository(appCtx)
                    val books = repo.loadBooksCache()
                    val lastBookId = repo.getLastBookId()
                    val activeBook = books.find { it.id == lastBookId } ?: books.firstOrNull()
                    val skipMs = repo.getPreferences().getInt("skip_interval", 15) * 1000L

                    CoroutineScope(Dispatchers.Main).launch {
                        try {
                            if (controller.mediaItemCount == 0 && activeBook != null) {
                                val coverBmp = repo.getOrFetchCoverArt(activeBook)
                                val artBytes = repo.bitmapToJpegBytes(coverBmp)
                                val savedTrack = repo.getBookTrackIndex(activeBook.id)
                                val savedPos = repo.getBookPosition(activeBook.id)
                                val lastPause = repo.getPreferences().getLong("last_pause_time", 0L)
                                val resumePos = calculateSmartRewindPos(savedPos, lastPause)
                                val items = buildBookMediaItems(activeBook, artBytes)
                                val validIdx = if (savedTrack in items.indices) savedTrack else 0
                                controller.setMediaItems(items, validIdx, resumePos)
                                controller.prepare()
                            }

                            when (action) {
                                ACTION_WIDGET_PLAY_PAUSE -> {
                                    if (controller.isPlaying) {
                                        controller.pause()
                                    } else {
                                        controller.play()
                                    }
                                }
                                ACTION_WIDGET_REWIND -> {
                                    val newPos = (controller.currentPosition - skipMs).coerceAtLeast(0L)
                                    controller.seekTo(newPos)
                                    activeBook?.let {
                                        repo.saveBookProgress(
                                            it.id,
                                            controller.currentMediaItemIndex,
                                            newPos,
                                            repo.getPreferences().getLong("total_listen_ms", 0L)
                                        )
                                    }
                                }
                                ACTION_WIDGET_FORWARD -> {
                                    val maxDur = if (controller.duration > 0L) controller.duration else Long.MAX_VALUE
                                    val newPos = (controller.currentPosition + skipMs).coerceAtMost(maxDur)
                                    controller.seekTo(newPos)
                                    activeBook?.let {
                                        repo.saveBookProgress(
                                            it.id,
                                            controller.currentMediaItemIndex,
                                            newPos,
                                            repo.getPreferences().getLong("total_listen_ms", 0L)
                                        )
                                    }
                                }
                            }
                            updateAllWidgets(appCtx, controller.isPlaying, activeBook?.title, activeBook?.author)
                        } finally {
                            MediaController.releaseFuture(controllerFuture)
                            pendingResult.finish()
                        }
                    }
                } catch (t: Throwable) {
                    Log.e(TAG, "Widget action failed: $action", t)
                    MediaController.releaseFuture(controllerFuture)
                    pendingResult.finish()
                }
            }
        }, ContextCompat.getMainExecutor(appCtx))
    }
}
// END OF FILE