package com.example.audiobook

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.LruCache
import androidx.documentfile.provider.DocumentFile
import com.arthenica.ffmpegkit.FFprobeKit
import com.arthenica.ffmpegkit.FFmpegKitConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

data class AudioTrack(val name: String, val uri: Uri)
data class Audiobook(
    val id: String,
    val title: String,
    val author: String,
    val series: String = "",
    val tracks: List<AudioTrack>
)
data class Chapter(val title: String, val startMs: Long, val durationMs: Long = 0L, val trackIndex: Int = 0)

data class Bookmark(
    val id: String = UUID.randomUUID().toString(),
    val positionMs: Long,
    val chapterTitle: String,
    val note: String,
    val timestampStr: String = SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault()).format(Date())
)

private object CoverMemoryCache {
    private val maxMemory = (Runtime.getRuntime().maxMemory() / 1024).toInt()
    private val cacheSize = (maxMemory / 8).coerceAtLeast(1024)
    val cache = object : LruCache<String, Bitmap>(cacheSize) {
        override fun sizeOf(key: String, bitmap: Bitmap): Int {
            return bitmap.byteCount / 1024
        }
    }
}

class AudiobookRepository(private val context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("SirinAudioPreferences", Context.MODE_PRIVATE)

    fun getPreferences() = prefs
    fun getSelectedFolderUri(): String? = prefs.getString("selected_folder", "")
    fun saveSelectedFolderUri(uriStr: String) = prefs.edit().putString("selected_folder", uriStr).apply()
    fun getLastBookId(): String? = prefs.getString("last_book_id", "")
    
    fun saveBookProgress(bookId: String, trackIndex: Int, positionMs: Long, totalListenMs: Long) {
        prefs.edit().apply {
            putString("last_book_id", bookId)
            putInt("book_track_$bookId", trackIndex)
            putLong("book_pos_$bookId", positionMs)
            putLong("total_listen_ms", totalListenMs)
            apply()
        }
    }

    fun getBookTrackIndex(bookId: String): Int = prefs.getInt("book_track_$bookId", 0)
    fun getBookPosition(bookId: String): Long = prefs.getLong("book_pos_$bookId", 0L)
    fun markPauseTime() = prefs.edit().putLong("last_pause_time", System.currentTimeMillis()).apply()

    fun loadBookBookmarks(bookId: String): List<Bookmark> {
        val list = mutableListOf<Bookmark>()
        try {
            val jsonStr = prefs.getString("book_bookmarks_json", "{}") ?: "{}"
            val jsonObj = JSONObject(jsonStr)
            val array = jsonObj.optJSONArray(bookId) ?: JSONArray()
            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                list.add(
                    Bookmark(
                        id = item.optString("id", UUID.randomUUID().toString()),
                        positionMs = item.optLong("positionMs", 0L),
                        chapterTitle = item.optString("chapterTitle", "Chapter"),
                        note = item.optString("note", ""),
                        timestampStr = item.optString("timestampStr", "")
                    )
                )
            }
        } catch (_: Exception) {}
        return list.sortedBy { v -> v.positionMs }
    }

    fun saveBookBookmark(bookId: String, bookmark: Bookmark) {
        try {
            val jsonStr = prefs.getString("book_bookmarks_json", "{}") ?: "{}"
            val jsonObj = JSONObject(jsonStr)
            val array = jsonObj.optJSONArray(bookId) ?: JSONArray()
            val newObj = JSONObject().apply {
                put("id", bookmark.id)
                put("positionMs", bookmark.positionMs)
                put("chapterTitle", bookmark.chapterTitle)
                put("note", bookmark.note)
                put("timestampStr", bookmark.timestampStr)
            }
            array.put(newObj)
            jsonObj.put(bookId, array)
            prefs.edit().putString("book_bookmarks_json", jsonObj.toString()).apply()
        } catch (_: Exception) {}
    }

    fun loadCompletedBookIds(): Set<String> {
        val set = mutableSetOf<String>()
        try {
            val jsonStr = prefs.getString("completed_books_set_json", "[]") ?: "[]"
            val array = JSONArray(jsonStr)
            for (i in 0 until array.length()) {
                set.add(array.getString(i))
            }
        } catch (_: Exception) {}
        return set
    }

    fun markBookCompleted(bookId: String): Boolean {
        val set = loadCompletedBookIds().toMutableSet()
        if (!set.contains(bookId)) {
            set.add(bookId)
            val array = JSONArray()
            set.forEach { array.put(it) }
            prefs.edit().putString("completed_books_set_json", array.toString()).apply()
            return true
        }
        return false
    }

    fun loadBookSpeed(bookId: String): Float {
        return try {
            val jsonStr = prefs.getString("book_speeds_json", "{}") ?: "{}"
            val jsonObj = JSONObject(jsonStr)
            if (jsonObj.has(bookId)) jsonObj.getDouble(bookId).toFloat() else 1.0f
        } catch (_: Exception) {
            1.0f
        }
    }

    fun saveBookSpeed(bookId: String, speed: Float) {
        try {
            val jsonStr = prefs.getString("book_speeds_json", "{}") ?: "{}"
            val jsonObj = JSONObject(jsonStr)
            jsonObj.put(bookId, speed.toDouble())
            prefs.edit().putString("book_speeds_json", jsonObj.toString()).apply()
        } catch (_: Exception) {}
    }

    suspend fun scanFolderForAudiobooks(folderUri: Uri): List<Audiobook> = withContext(Dispatchers.IO) {
        val bookList = mutableListOf<Audiobook>()
        val rootDocument = DocumentFile.fromTreeUri(context, folderUri) ?: return@withContext emptyList()
        traverseDirectoryForBooks(rootDocument, rootDocument, bookList)
        bookList
    }

    private fun traverseDirectoryForBooks(currentDir: DocumentFile, rootDir: DocumentFile, bookList: MutableList<Audiobook>) {
        val files = currentDir.listFiles()
        val audioFiles = mutableListOf<DocumentFile>()
        val subDirs = mutableListOf<DocumentFile>()

        for (file in files) {
            if (file.isDirectory) subDirs.add(file)
            else if (file.isFile) {
                val name = file.name ?: continue
                if (name.endsWith(".mp3", ignoreCase = true) || name.endsWith(".m4a", ignoreCase = true) || name.endsWith(".m4b", ignoreCase = true)) {
                    audioFiles.add(file)
                }
            }
        }

        if (audioFiles.isNotEmpty()) {
            val sortedTracks = audioFiles.map { AudioTrack(name = it.name?.substringBeforeLast(".") ?: "Track", uri = it.uri) }
                .sortedWith(Comparator { a, b -> a.name.compareTo(b.name, ignoreCase = true) })
            val parentDir = currentDir.parentFile
            val grandParentDir = parentDir?.parentFile
            var authorName = "Unknown Author"
            var seriesName = ""
            if (currentDir != rootDir && parentDir != null && parentDir != rootDir) {
                if (grandParentDir != null && grandParentDir != rootDir && grandParentDir.name != null) {
                    authorName = grandParentDir.name!!
                    seriesName = parentDir.name!!
                } else if (parentDir.name != null) authorName = parentDir.name!!
            }
            val bookTitle = if (currentDir.name != null && currentDir != rootDir) currentDir.name!! else sortedTracks.first().name

            if (audioFiles.size == 1 && sortedTracks.first().name.endsWith(".m4b", ignoreCase = true)) {
                bookList.add(Audiobook(id = sortedTracks.first().uri.toString(), title = sortedTracks.first().name, author = authorName, series = seriesName, tracks = sortedTracks))
            } else {
                bookList.add(Audiobook(id = currentDir.uri.toString(), title = bookTitle, author = authorName, series = seriesName, tracks = sortedTracks))
            }
        }
        for (dir in subDirs) traverseDirectoryForBooks(dir, rootDir, bookList)
    }

    suspend fun getBookChapters(book: Audiobook): List<Chapter> = withContext(Dispatchers.IO) {
        // Direct routing for single M4B files
        if (book.tracks.size == 1 && book.tracks.first().name.endsWith(".m4b", ignoreCase = true)) {
            return@withContext extractM4bChapters(book.tracks.first().uri)
        }
        
        // Multi-file MP3 logic
        val chapters = mutableListOf<Chapter>()
        val retriever = MediaMetadataRetriever()
        for ((index, track) in book.tracks.withIndex()) {
            var duration = 0L
            var title = track.name
            try {
                retriever.setDataSource(context, track.uri)
                duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                val metaTitle = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                if (!metaTitle.isNullOrBlank()) title = metaTitle
            } catch (e: Exception) {}
            chapters.add(Chapter(title = title, startMs = 0L, durationMs = duration, trackIndex = index))
        }
        try { retriever.release() } catch (e: Exception) {}
        
        if (chapters.isEmpty()) chapters.add(Chapter("Chapter 1", 0L, 0L, 0))
        chapters
    }

    // INDUSTRY STANDARD FFPROBE CHAPTER EXTRACTOR
    private fun extractM4bChapters(uri: Uri): List<Chapter> {
        val chapters = mutableListOf<Chapter>()
        
        // METHOD 1: FFprobeKit (Bulletproof parsing for Audible, Nero, and QuickTime tags)
        try {
            // The magic SAF wrapper that lets FFmpeg securely read Android Content URIs
            val safUrl = FFmpegKitConfig.getSafParameterForRead(context, uri)
            val session = FFprobeKit.getMediaInformation(safUrl)
            val info = session.mediaInformation
            
            if (info != null && info.chapters != null) {
                for ((index, ch) in info.chapters.withIndex()) {
                    val tags = ch.allProperties?.optJSONObject("tags")
                    val title = tags?.optString("title")?.takeIf { it.isNotBlank() } ?: "Chapter ${index + 1}"
                    val startMs = (ch.startTime?.toDoubleOrNull()?.times(1000))?.toLong() ?: 0L
                    chapters.add(Chapter(title, startMs, 0L, 0))
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // METHOD 2: Fallback to Android Native Extractor if FFprobe unexpectedly fails
        if (chapters.isEmpty()) {
            val extractor = android.media.MediaExtractor()
            try {
                extractor.setDataSource(context, uri, null)
                var textTrackIndex = -1
                
                for (i in 0 until extractor.trackCount) {
                    val format = extractor.getTrackFormat(i)
                    val mime = format.getString(android.media.MediaFormat.KEY_MIME) ?: ""
                    if (mime.startsWith("text/") || mime.contains("tx3g") || mime.contains("3gpp")) {
                        textTrackIndex = i
                        break
                    }
                }

                if (textTrackIndex != -1) {
                    extractor.selectTrack(textTrackIndex)
                    val buffer = java.nio.ByteBuffer.allocate(8192)
                    var chapterIndex = 1
                    
                    while (true) {
                        val sampleSize = extractor.readSampleData(buffer, 0)
                        if (sampleSize < 0) break
                        
                        val timeMs = extractor.sampleTime / 1000
                        val titleBytes = ByteArray(sampleSize)
                        buffer.get(titleBytes)
                        
                        var title = "Chapter $chapterIndex"
                        if (sampleSize >= 2) {
                            val len = ((titleBytes[0].toInt() and 0xFF) shl 8) or (titleBytes[1].toInt() and 0xFF)
                            if (len in 1..sampleSize - 2) {
                                title = String(titleBytes, 2, len, Charsets.UTF_8).trim()
                            } else {
                                title = String(titleBytes, Charsets.UTF_8).replace(Regex("[^\\x20-\\x7E]"), "").trim()
                            }
                        }
                        if (title.isBlank()) title = "Chapter $chapterIndex"
                        
                        chapters.add(Chapter(title, timeMs, 0L, 0))
                        chapterIndex++
                        extractor.advance()
                        buffer.clear()
                    }
                }
            } catch (e: Exception) {} finally {
                try { extractor.release() } catch (e: Exception) {}
            }
        }

        // Calculate exact duration of each extracted chapter
        if (chapters.isNotEmpty()) {
            chapters.sortBy { it.startMs }
            for (i in 0 until chapters.size - 1) {
                chapters[i] = chapters[i].copy(durationMs = chapters[i+1].startMs - chapters[i].startMs)
            }
            try {
                val retriever = MediaMetadataRetriever()
                retriever.setDataSource(context, uri)
                val totalDuration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: chapters.last().startMs
                chapters[chapters.size - 1] = chapters.last().copy(durationMs = totalDuration - chapters.last().startMs)
                retriever.release()
            } catch (e: Exception) {}
        }

        return chapters.ifEmpty { listOf(Chapter("Chapter 1", 0L, 0L, 0)) }
    }

    suspend fun getOrFetchCoverArt(book: Audiobook): Bitmap? = withContext(Dispatchers.IO) {
        CoverMemoryCache.cache.get(book.id)?.let { return@withContext it }
        val cacheFile = File(context.filesDir, "covers/${book.id.hashCode()}.png")
        if (cacheFile.exists()) {
            val bmp = decodeSampledBitmapFromFile(cacheFile.absolutePath, 350, 350)
            if (bmp != null) {
                CoverMemoryCache.cache.put(book.id, bmp)
                return@withContext bmp
            }
        }
        val embedded = loadEmbeddedCoverArt(book.tracks.first().uri)
        if (embedded != null) {
            saveBitmapToCache(book.id, embedded)
            CoverMemoryCache.cache.put(book.id, embedded)
            return@withContext embedded
        }
        val onlineBmp = fetchOnlineCoverArt(book.title, book.author)
        if (onlineBmp != null) {
            saveBitmapToCache(book.id, onlineBmp)
            CoverMemoryCache.cache.put(book.id, onlineBmp)
            return@withContext onlineBmp
        }
        null
    }

    suspend fun saveCustomCoverArt(bookId: String, uri: Uri): Bitmap? = withContext(Dispatchers.IO) {
        try {
            val stream = context.contentResolver.openInputStream(uri)
            if (stream != null) {
                val bitmap = decodeSampledBitmapFromStream(stream, 350, 350)
                stream.close()
                if (bitmap != null) {
                    saveBitmapToCache(bookId, bitmap)
                    CoverMemoryCache.cache.put(bookId, bitmap)
                    return@withContext bitmap
                }
            }
        } catch (_: Exception) {}
        null
    }

    private fun fetchOnlineCoverArt(title: String, author: String): Bitmap? {
        return try {
            val query = URLEncoder.encode("$title $author", "UTF-8")
            val url = URL("https://openlibrary.org/search.json?q=$query&limit=1")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 3000
            conn.readTimeout = 3000
            val jsonText = conn.inputStream.bufferedReader().use { it.readText() }
            val docs = JSONObject(jsonText).optJSONArray("docs")
            if (docs != null && docs.length() > 0) {
                val coverI = docs.getJSONObject(0).optInt("cover_i", -1)
                if (coverI != -1) {
                    val imgUrl = URL("https://covers.openlibrary.org/b/id/$coverI-M.jpg")
                    val stream = imgUrl.openStream()
                    val bitmap = decodeSampledBitmapFromStream(stream, 350, 350)
                    stream.close()
                    return bitmap
                }
            }
            null
        } catch (_: Exception) { null }
    }

    private fun loadEmbeddedCoverArt(uri: Uri): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            val artBytes = retriever.embeddedPicture
            retriever.release()
            if (artBytes != null) {
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(artBytes, 0, artBytes.size, options)
                options.inSampleSize = calculateInSampleSize(options, 350, 350)
                options.inJustDecodeBounds = false
                BitmapFactory.decodeByteArray(artBytes, 0, artBytes.size, options)
            } else null
        } catch (e: Exception) {
            try { retriever.release() } catch (_: Exception) {}
            null
        }
    }

    private fun saveBitmapToCache(bookId: String, bitmap: Bitmap) {
        try {
            val coversDir = File(context.filesDir, "covers")
            if (!coversDir.exists()) coversDir.mkdirs()
            val cacheFile = File(coversDir, "${bookId.hashCode()}.png")
            FileOutputStream(cacheFile).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 90, out)
            }
        } catch (_: Exception) {}
    }

    private fun decodeSampledBitmapFromFile(path: String, reqWidth: Int, reqHeight: Int): Bitmap? {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, options)
        options.inSampleSize = calculateInSampleSize(options, reqWidth, reqHeight)
        options.inJustDecodeBounds = false
        return BitmapFactory.decodeFile(path, options)
    }

    private fun decodeSampledBitmapFromStream(stream: InputStream, reqWidth: Int, reqHeight: Int): Bitmap? {
        val bytes = stream.readBytes()
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        options.inSampleSize = calculateInSampleSize(options, reqWidth, reqHeight)
        options.inJustDecodeBounds = false
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }

    private fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
        val (height: Int, width: Int) = options.outHeight to options.outWidth
        var inSampleSize = 1
        if (height > reqHeight || width > reqWidth) {
            val halfHeight = height / 2
            val halfWidth = width / 2
            while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }
}