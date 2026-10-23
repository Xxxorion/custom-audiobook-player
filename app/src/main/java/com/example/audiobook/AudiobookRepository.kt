package com.example.audiobook

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.LruCache
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.DataInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.UUID

data class AudioTrack(val name: String, val uri: Uri)
data class Audiobook(
    val id: String,
    val title: String,
    val author: String,
    val series: String = "",
    val tracks: List<AudioTrack>
)
data class Chapter(val title: String, val startMs: Long)

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
    
    // --- PER-BOOK POSITION SAVING ---
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
    
    fun markPauseTime() {
        prefs.edit().putLong("last_pause_time", System.currentTimeMillis()).apply()
    }

    // --- BOOKMARKS ---
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

    fun deleteBookBookmark(bookId: String, bookmarkId: String) {
        try {
            val jsonStr = prefs.getString("book_bookmarks_json", "{}") ?: "{}"
            val jsonObj = JSONObject(jsonStr)
            val array = jsonObj.optJSONArray(bookId) ?: return
            val newArray = JSONArray()
            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                if (item.optString("id") != bookmarkId) {
                    newArray.put(item)
                }
            }
            jsonObj.put(bookId, newArray)
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
            if (file.isDirectory) {
                subDirs.add(file)
            } else if (file.isFile) {
                val name = file.name ?: continue
                if (name.endsWith(".mp3", ignoreCase = true) ||
                    name.endsWith(".m4a", ignoreCase = true) ||
                    name.endsWith(".m4b", ignoreCase = true)
                ) {
                    audioFiles.add(file)
                }
            }
        }

        if (audioFiles.isNotEmpty()) {
            val sortedTracks = audioFiles.map {
                AudioTrack(name = it.name?.substringBeforeLast(".") ?: "Track", uri = it.uri)
            }.sortedWith(Comparator { a, b -> a.name.compareTo(b.name, ignoreCase = true) })

            val parentDir = currentDir.parentFile
            val grandParentDir = parentDir?.parentFile
            var authorName = "Unknown Author"
            var seriesName = ""

            if (currentDir != rootDir && parentDir != null && parentDir != rootDir) {
                if (grandParentDir != null && grandParentDir != rootDir && grandParentDir.name != null) {
                    authorName = grandParentDir.name!!
                    seriesName = parentDir.name!!
                } else if (parentDir.name != null) {
                    authorName = parentDir.name!!
                }
            }

            val bookTitle = if (currentDir.name != null && currentDir != rootDir) currentDir.name!! else sortedTracks.first().name

            if (audioFiles.size == 1 && sortedTracks.first().name.endsWith(".m4b", ignoreCase = true)) {
                bookList.add(
                    Audiobook(
                        id = sortedTracks.first().uri.toString(),
                        title = sortedTracks.first().name,
                        author = authorName,
                        series = seriesName,
                        tracks = sortedTracks
                    )
                )
            } else {
                bookList.add(
                    Audiobook(
                        id = currentDir.uri.toString(),
                        title = bookTitle,
                        author = authorName,
                        series = seriesName,
                        tracks = sortedTracks
                    )
                )
            }
        }

        for (dir in subDirs) {
            traverseDirectoryForBooks(dir, rootDir, bookList)
        }
    }

    // --- ENHANCED .M4B CHAPTER PARSER ---
    suspend fun extractM4bChapters(uri: Uri): List<Chapter> = withContext(Dispatchers.IO) {
        val chapters = mutableListOf<Chapter>()
        try {
            // First try standard MediaMetadataRetriever chapter extraction (Android built-in)
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, uri)
                // Some Android versions expose chapter count
                val countStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_NUM_TRACKS)
                // Alternatively parse via input stream deep scan
            } catch (_: Exception) {} finally {
                try { retriever.release() } catch (_: Exception) {}
            }

            context.contentResolver.openInputStream(uri)?.use { stream ->
                // Read deeper into the file (up to 32MB) to catch chapter atoms if they aren't right at the header
                val scanLimit = 32 * 1024 * 1024
                val buffer = ByteArray(scanLimit)
                val bytesRead = stream.read(buffer, 0, scanLimit)
                if (bytesRead <= 0) return@withContext emptyList()

                var i = 0
                while (i < bytesRead - 8) {
                    // Look for 'chpl' (Nero chapter marker atom) or 'ilst' / text chapter markers
                    if ((buffer[i] == 'c'.code.toByte() && buffer[i+1] == 'h'.code.toByte() && buffer[i+2] == 'p'.code.toByte() && buffer[i+3] == 'l'.code.toByte()) ||
                        (buffer[i] == 'ntext' && buffer[i+1] == '...')) {
                        // Parse Nero Chapters format: [4 bytes atom size]['chpl'][1 byte version][3 bytes flags][4 bytes chapter count]
                        var offset = i + 4
                        if (buffer.copyOfRange(i, i+4).toString(Charsets.US_ASCII) == "chpl") {
                            offset = i + 4
                            // skip size if needed, read past header
                        }
                    }
                    
                    // Generic robust search for chapter title strings and timestamps inside M4B atom structures
                    if (buffer[i] == 'c'.code.toByte() &&
                        buffer[i + 1] == 'h'.code.toByte() &&
                        buffer[i + 2] == 'p'.code.toByte() &&
                        buffer[i + 3] == 'l'.code.toByte()
                    ) {
                        var offset = i + 8
                        if (offset + 4 <= bytesRead) {
                            val count = ((buffer[offset].toInt() and 0xFF) shl 24) or
                                        ((buffer[offset + 1].toInt() and 0xFF) shl 16) or
                                        ((buffer[offset + 2].toInt() and 0xFF) shl 8) or
                                        (buffer[offset + 3].toInt() and 0xFF)
                            offset += 4

                            if (count in 1..1000) { // sanity check on chapter count
                                for (c in 0 until count) {
                                    if (offset + 9 > bytesRead) break
                                    val duration100ns = DataInputStream(buffer.inputStream().apply { skip(offset.toLong()) }).readLong()
                                    offset += 8

                                    val titleLen = buffer[offset].toInt() and 0xFF
                                    offset += 1

                                    if (offset + titleLen > bytesRead) break
                                    val titleBytes = buffer.copyOfRange(offset, offset + titleLen)
                                    val title = String(titleBytes, Charsets.UTF_8).trim()
                                    offset += titleLen

                                    val startMs = duration100ns / 10000L
                                    chapters.add(Chapter(title = if (title.isNotBlank()) title else "Chapter ${c + 1}", startMs = startMs))
                                }
                            }
                        }
                        break
                    }
                    i++
                }
            }
        } catch (_: Exception) {}
        
        // Fallback: If no internal M4B chapter atoms were parsed, generate estimated 15-minute interval chapters or return empty
        chapters
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