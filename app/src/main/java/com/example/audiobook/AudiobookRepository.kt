package com.example.audiobook

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import android.util.LruCache
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

data class AudioTrack(
    val name: String,
    val uri: Uri,
    val isM4b: Boolean = false
)

data class Audiobook(
    val id: String,
    val title: String,
    val author: String,
    val series: String = "",
    val tracks: List<AudioTrack>
)

data class Chapter(
    val title: String,
    val startMs: Long,
    val durationMs: Long = 0L,
    val trackIndex: Int = 0
)

data class Bookmark(
    val id: String = UUID.randomUUID().toString(),
    val trackIndex: Int = 0,
    val positionMs: Long,
    val chapterTitle: String,
    val note: String,
    val timestampStr: String = SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault()).format(Date())
)

// Pure functions exposed for JVM Unit Testing
fun compareNatural(a: String, b: String): Int {
    var ia = 0
    var ib = 0
    val na = a.length
    val nb = b.length
    while (ia < na && ib < nb) {
        val ca = a[ia]
        val cb = b[ib]
        if (ca.isDigit() && cb.isDigit()) {
            var za = ia
            while (za < na && a[za] == '0') za++
            var zb = ib
            while (zb < nb && b[zb] == '0') zb++

            var ea = za
            while (ea < na && a[ea].isDigit()) ea++
            var eb = zb
            while (eb < nb && b[eb].isDigit()) eb++

            val lenA = ea - za
            val lenB = eb - zb
            if (lenA != lenB) return lenA.compareTo(lenB)
            for (k in 0 until lenA) {
                val diff = a[za + k].compareTo(b[zb + k])
                if (diff != 0) return diff
            }
            val rawLenDiff = (ea - ia).compareTo(eb - ib)
            if (rawLenDiff != 0) return rawLenDiff
            ia = ea
            ib = eb
        } else {
            val ua = ca.lowercaseChar()
            val ub = cb.lowercaseChar()
            if (ua != ub) return ua.compareTo(ub)
            ia++
            ib++
        }
    }
    return (na - ia).compareTo(nb - ib)
}

fun bookDurationMs(trackDurations: List<Long>): Long {
    var sum = 0L
    for (d in trackDurations) {
        if (d > 0L) sum += d
    }
    return sum
}

fun bookOffsetMs(trackDurations: List<Long>, trackIndex: Int, positionInTrack: Long): Long {
    val safePos = positionInTrack.coerceAtLeast(0L)
    if (trackDurations.isEmpty() || trackIndex <= 0) return safePos
    val limit = trackIndex.coerceAtMost(trackDurations.size)
    var offset = 0L
    for (i in 0 until limit) {
        val d = trackDurations[i]
        if (d > 0L) offset += d
    }
    return offset + safePos
}

fun parseChplBytes(payload: ByteArray, trackIndex: Int = 0): List<Chapter> {
    if (payload.size < 5) return emptyList()
    val buf = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)
    val version = buf.get().toInt() and 0xFF
    buf.get(); buf.get(); buf.get() // 24-bit flags
    if (version == 1) {
        if (buf.remaining() < 4) return emptyList()
        buf.int // skip 32-bit reserved
    }
    if (buf.remaining() < 1) return emptyList()
    val count = buf.get().toInt() and 0xFF
    if (count <= 0) return emptyList()

    val result = mutableListOf<Chapter>()
    for (i in 0 until count) {
        if (buf.remaining() < 9) break
        val startTicks = buf.long
        val startMs = (startTicks / 10_000L).coerceAtLeast(0L)
        val titleLen = buf.get().toInt() and 0xFF
        if (buf.remaining() < titleLen) break
        val strBytes = ByteArray(titleLen)
        buf.get(strBytes)
        val title = String(strBytes, Charsets.UTF_8).trim().ifBlank { "Chapter ${i + 1}" }
        result.add(Chapter(title, startMs, 0L, trackIndex))
    }
    return result
}

fun decodeTx3gTitle(bytes: ByteArray, sampleSize: Int, chapterNumber: Int): String {
    var title = "Chapter $chapterNumber"
    try {
        if (sampleSize >= 2) {
            val len = ((bytes[0].toInt() and 0xFF) shl 8) or (bytes[1].toInt() and 0xFF)
            if (len in 1..(sampleSize - 2)) {
                val charset = if (len >= 2 &&
                    ((bytes[2] == 0xFE.toByte() && bytes[3] == 0xFF.toByte()) ||
                        (bytes[2] == 0xFF.toByte() && bytes[3] == 0xFE.toByte()))
                ) {
                    Charsets.UTF_16
                } else {
                    Charsets.UTF_8
                }
                title = String(bytes, 2, len, charset).trim()
            } else {
                title = String(bytes, 0, sampleSize, Charsets.UTF_8)
                    .replace(Regex("[^\\x20-\\x7E]"), "")
                    .trim()
            }
        }
    } catch (_: Throwable) {}
    return title.ifBlank { "Chapter $chapterNumber" }
}

private object CoverMemoryCache {
    private val maxMemory = (Runtime.getRuntime().maxMemory() / 1024).toInt()
    private val cacheSize = (maxMemory / 8).coerceAtLeast(1024)
    val cache = object : LruCache<String, Bitmap>(cacheSize) {
        override fun sizeOf(key: String, bitmap: Bitmap): Int {
            return bitmap.byteCount / 1024
        }
    }
}

class AudiobookRepository(context: Context) {
    private val appContext: Context = context.applicationContext
    private val prefs: SharedPreferences = appContext.getSharedPreferences("FablewoodPreferences", Context.MODE_PRIVATE)
    private val legacyPrefs: SharedPreferences = appContext.getSharedPreferences("SirinAudioPreferences", Context.MODE_PRIVATE)
    private val libraryFile = File(appContext.filesDir, "library.json")
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writeMutex = Mutex()

    companion object {
        private const val TAG = "Fablewood"
        private val SUPPORTED_EXTENSIONS = setOf(
            "mp3", "m4a", "m4b", "m4p", "mp4", "aac", "ogg", "opus", "flac", "wav"
        )
        private val CONTAINER_EXTENSIONS = setOf("m4a", "m4b", "m4p", "mp4")
        private val DISC_FOLDER_REGEX = Regex("^(disc|cd|part|disk)\\s*\\d+.*$", RegexOption.IGNORE_CASE)

        @Volatile
        private var memoryStore: JSONObject? = null
        private val stateLock = Any()
    }

    init {
        ensureStoreLoaded()
    }

    private fun ensureStoreLoaded(): JSONObject {
        synchronized(stateLock) {
            memoryStore?.let { return it }
            val loaded = if (libraryFile.exists()) {
                try {
                    JSONObject(libraryFile.readText(Charsets.UTF_8))
                } catch (t: Throwable) {
                    Log.e(TAG, "Failed reading library.json, starting fresh", t)
                    JSONObject()
                }
            } else {
                val migrated = migrateFromPreferences()
                writeStoreSync(migrated)
                migrated
            }
            memoryStore = loaded
            return loaded
        }
    }

    private fun migrateFromPreferences(): JSONObject {
        val root = JSONObject()
        try {
            val sourcePrefs = if (prefs.contains("cached_books_list") || prefs.contains("selected_folder")) prefs else legacyPrefs
            if (!prefs.contains("selected_folder") && legacyPrefs.contains("selected_folder")) {
                prefs.edit()
                    .putString("selected_folder", legacyPrefs.getString("selected_folder", ""))
                    .putString("last_book_id", legacyPrefs.getString("last_book_id", ""))
                    .putLong("total_listen_ms", legacyPrefs.getLong("total_listen_ms", 0L))
                    .putBoolean("is_dark_theme", legacyPrefs.getBoolean("is_dark_theme", true))
                    .putString("view_mode", legacyPrefs.getString("view_mode", "GRID"))
                    .putString("sort_by", legacyPrefs.getString("sort_by", "RECENT"))
                    .putInt("skip_interval", legacyPrefs.getInt("skip_interval", 15))
                    .putBoolean("silence_trim", legacyPrefs.getBoolean("silence_trim", false))
                    .putBoolean("volume_boost", legacyPrefs.getBoolean("volume_boost", false))
                    .apply()
            }

            val booksStr = sourcePrefs.getString("cached_books_list", "[]") ?: "[]"
            root.put("books", JSONArray(booksStr))
            val bmStr = sourcePrefs.getString("book_bookmarks_json", "{}") ?: "{}"
            root.put("bookmarks", JSONObject(bmStr))
            val compStr = sourcePrefs.getString("completed_books_set_json", "[]") ?: "[]"
            root.put("completed", JSONArray(compStr))
            val speedsStr = sourcePrefs.getString("book_speeds_json", "{}") ?: "{}"
            root.put("speeds", JSONObject(speedsStr))

            val progressObj = JSONObject()
            for ((key, value) in sourcePrefs.all) {
                if (key.startsWith("book_pos_") && value is Long) {
                    val bookId = key.removePrefix("book_pos_")
                    val entry = progressObj.optJSONObject(bookId) ?: JSONObject()
                    entry.put("positionMs", value)
                    progressObj.put(bookId, entry)
                } else if (key.startsWith("book_track_") && value is Int) {
                    val bookId = key.removePrefix("book_track_")
                    val entry = progressObj.optJSONObject(bookId) ?: JSONObject()
                    entry.put("trackIndex", value)
                    progressObj.put(bookId, entry)
                } else if (key.startsWith("book_dur_") && value is Long) {
                    val bookId = key.removePrefix("book_dur_")
                    val entry = progressObj.optJSONObject(bookId) ?: JSONObject()
                    entry.put("bookDurationMs", value)
                    progressObj.put(bookId, entry)
                }
            }
            root.put("progress", progressObj)
            root.put("metaCache", JSONObject())
        } catch (t: Throwable) {
            Log.w(TAG, "Preference migration encountered non-fatal error", t)
        }
        return root
    }

    private fun scheduleStoreFlush(immediate: Boolean = false) {
        val snapshot = synchronized(stateLock) {
            memoryStore?.toString() ?: return
        }
        ioScope.launch {
            if (!immediate) delay(350L)
            writeMutex.withLock {
                val latest = synchronized(stateLock) { memoryStore?.toString() ?: snapshot }
                writeStoreStringAtomic(latest)
            }
        }
    }

    private fun writeStoreSync(jsonObj: JSONObject) {
        writeStoreStringAtomic(jsonObj.toString())
    }

    private fun writeStoreStringAtomic(content: String) {
        try {
            val tmpFile = File(appContext.filesDir, "library.json.tmp")
            FileOutputStream(tmpFile).use { fos ->
                fos.write(content.toByteArray(Charsets.UTF_8))
                fos.fd.sync()
            }
            if (!tmpFile.renameTo(libraryFile)) {
                tmpFile.copyTo(libraryFile, overwrite = true)
                tmpFile.delete()
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Failed writing library.json atomically", t)
        }
    }

    fun getPreferences(): SharedPreferences = prefs
    fun getSelectedFolderUri(): String? = prefs.getString("selected_folder", "")
    fun saveSelectedFolderUri(uriStr: String) = prefs.edit().putString("selected_folder", uriStr).apply()
    fun getLastBookId(): String? = prefs.getString("last_book_id", "")

    fun loadPlaybackPitch(): Float = prefs.getFloat("playback_pitch", 1.0f).coerceIn(0.75f, 1.25f)
    fun savePlaybackPitch(pitch: Float) = prefs.edit().putFloat("playback_pitch", pitch.coerceIn(0.75f, 1.25f)).apply()

    fun saveBooksCache(books: List<Audiobook>) {
        synchronized(stateLock) {
            val store = ensureStoreLoaded()
            val jsonArray = JSONArray()
            for (book in books) {
                val bookObj = JSONObject().apply {
                    put("id", book.id)
                    put("title", book.title)
                    put("author", book.author)
                    put("series", book.series)
                    val tracksArray = JSONArray()
                    for (track in book.tracks) {
                        tracksArray.put(
                            JSONObject().apply {
                                put("name", track.name)
                                put("uri", track.uri.toString())
                                put("isM4b", track.isM4b)
                            }
                        )
                    }
                    put("tracks", tracksArray)
                }
                jsonArray.put(bookObj)
            }
            store.put("books", jsonArray)
        }
        scheduleStoreFlush(immediate = true)
    }

    fun loadBooksCache(): List<Audiobook> {
        val list = mutableListOf<Audiobook>()
        synchronized(stateLock) {
            try {
                val store = ensureStoreLoaded()
                val array = store.optJSONArray("books") ?: JSONArray()
                for (i in 0 until array.length()) {
                    val bookObj = array.getJSONObject(i)
                    val tracksArray = bookObj.optJSONArray("tracks") ?: JSONArray()
                    val tracks = mutableListOf<AudioTrack>()
                    for (j in 0 until tracksArray.length()) {
                        val trackObj = tracksArray.getJSONObject(j)
                        tracks.add(
                            AudioTrack(
                                name = trackObj.optString("name", "Track"),
                                uri = Uri.parse(trackObj.optString("uri", "")),
                                isM4b = trackObj.optBoolean("isM4b", false)
                            )
                        )
                    }
                    list.add(
                        Audiobook(
                            id = bookObj.optString("id", ""),
                            title = bookObj.optString("title", "Unknown"),
                            author = bookObj.optString("author", "Unknown Author"),
                            series = bookObj.optString("series", ""),
                            tracks = tracks
                        )
                    )
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Failed loading cached books from library.json", t)
            }
        }
        return list
    }

    fun saveBookProgress(
        bookId: String,
        trackIndex: Int,
        positionMs: Long,
        totalListenMs: Long,
        immediate: Boolean = true
    ) {
        if (bookId.isEmpty()) return
        prefs.edit()
            .putString("last_book_id", bookId)
            .putLong("total_listen_ms", totalListenMs)
            .apply()

        synchronized(stateLock) {
            val store = ensureStoreLoaded()
            val progressObj = store.optJSONObject("progress") ?: JSONObject().also { store.put("progress", it) }
            val entry = progressObj.optJSONObject(bookId) ?: JSONObject()
            entry.put("trackIndex", trackIndex.coerceAtLeast(0))
            entry.put("positionMs", positionMs.coerceAtLeast(0L))
            progressObj.put(bookId, entry)
        }
        scheduleStoreFlush(immediate = immediate)
    }

    fun getBookTrackIndex(bookId: String): Int = synchronized(stateLock) {
        val store = ensureStoreLoaded()
        store.optJSONObject("progress")?.optJSONObject(bookId)?.optInt("trackIndex", 0) ?: 0
    }

    fun getBookPosition(bookId: String): Long = synchronized(stateLock) {
        val store = ensureStoreLoaded()
        store.optJSONObject("progress")?.optJSONObject(bookId)?.optLong("positionMs", 0L) ?: 0L
    }

    fun getCachedTrackDurations(bookId: String): List<Long> = synchronized(stateLock) {
        val store = ensureStoreLoaded()
        val arr = store.optJSONObject("progress")?.optJSONObject(bookId)?.optJSONArray("trackDurations")
            ?: return@synchronized emptyList()
        val list = ArrayList<Long>(arr.length())
        for (i in 0 until arr.length()) {
            list.add(arr.optLong(i, 0L))
        }
        list
    }

    fun getCachedBookDuration(bookId: String): Long = synchronized(stateLock) {
        val store = ensureStoreLoaded()
        store.optJSONObject("progress")?.optJSONObject(bookId)?.optLong("bookDurationMs", 0L) ?: 0L
    }

    fun saveBookDurations(bookId: String, trackDurations: List<Long>) {
        val total = bookDurationMs(trackDurations)
        synchronized(stateLock) {
            val store = ensureStoreLoaded()
            val progressObj = store.optJSONObject("progress") ?: JSONObject().also { store.put("progress", it) }
            val entry = progressObj.optJSONObject(bookId) ?: JSONObject()
            val arr = JSONArray()
            trackDurations.forEach { arr.put(it) }
            entry.put("trackDurations", arr)
            entry.put("bookDurationMs", total)
            progressObj.put(bookId, entry)
        }
        scheduleStoreFlush(immediate = false)
    }

    suspend fun getOrComputeTrackDurations(book: Audiobook): List<Long> = withContext(Dispatchers.IO) {
        val cached = getCachedTrackDurations(book.id)
        if (cached.size == book.tracks.size && cached.all { it > 0L }) {
            return@withContext cached
        }
        val durations = MutableList(book.tracks.size) { idx -> cached.getOrNull(idx) ?: 0L }
        val retriever = MediaMetadataRetriever()
        var updated = false
        try {
            for (i in book.tracks.indices) {
                if (durations[i] <= 0L) {
                    try {
                        retriever.setDataSource(appContext, book.tracks[i].uri)
                        val dur = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                        if (dur > 0L) {
                            durations[i] = dur
                            updated = true
                        }
                    } catch (t: Throwable) {
                        Log.w(TAG, "Could not read duration for track ${book.tracks[i].name}", t)
                    }
                }
            }
        } finally {
            try { retriever.release() } catch (_: Throwable) {}
        }
        if (updated || cached.size != durations.size) {
            saveBookDurations(book.id, durations)
        }
        durations
    }

    fun markPauseTime() = prefs.edit().putLong("last_pause_time", System.currentTimeMillis()).apply()

    fun loadBookBookmarks(bookId: String): List<Bookmark> {
        val list = mutableListOf<Bookmark>()
        synchronized(stateLock) {
            try {
                val store = ensureStoreLoaded()
                val bookmarksObj = store.optJSONObject("bookmarks") ?: return emptyList()
                val array = bookmarksObj.optJSONArray(bookId) ?: return emptyList()
                for (i in 0 until array.length()) {
                    val item = array.getJSONObject(i)
                    list.add(
                        Bookmark(
                            id = item.optString("id", UUID.randomUUID().toString()),
                            trackIndex = item.optInt("trackIndex", 0),
                            positionMs = item.optLong("positionMs", 0L),
                            chapterTitle = item.optString("chapterTitle", "Chapter"),
                            note = item.optString("note", ""),
                            timestampStr = item.optString("timestampStr", "")
                        )
                    )
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Failed loading bookmarks for $bookId", t)
            }
        }
        return list.sortedWith(compareBy<Bookmark> { it.trackIndex }.thenBy { it.positionMs })
    }

    fun saveBookBookmark(bookId: String, bookmark: Bookmark) {
        synchronized(stateLock) {
            try {
                val store = ensureStoreLoaded()
                val jsonObj = store.optJSONObject("bookmarks") ?: JSONObject().also { store.put("bookmarks", it) }
                val array = jsonObj.optJSONArray(bookId) ?: JSONArray()
                val newObj = JSONObject().apply {
                    put("id", bookmark.id)
                    put("trackIndex", bookmark.trackIndex)
                    put("positionMs", bookmark.positionMs)
                    put("chapterTitle", bookmark.chapterTitle)
                    put("note", bookmark.note)
                    put("timestampStr", bookmark.timestampStr)
                }
                array.put(newObj)
                jsonObj.put(bookId, array)
            } catch (t: Throwable) {
                Log.w(TAG, "Failed saving bookmark for $bookId", t)
            }
        }
        scheduleStoreFlush(immediate = true)
    }

    fun deleteBookBookmark(bookId: String, bookmarkId: String) {
        synchronized(stateLock) {
            try {
                val store = ensureStoreLoaded()
                val jsonObj = store.optJSONObject("bookmarks") ?: return
                val array = jsonObj.optJSONArray(bookId) ?: return
                val newArray = JSONArray()
                for (i in 0 until array.length()) {
                    val item = array.getJSONObject(i)
                    if (item.optString("id") != bookmarkId) {
                        newArray.put(item)
                    }
                }
                jsonObj.put(bookId, newArray)
            } catch (t: Throwable) {
                Log.w(TAG, "Failed deleting bookmark $bookmarkId", t)
            }
        }
        scheduleStoreFlush(immediate = true)
    }

    fun loadCompletedBookIds(): Set<String> = synchronized(stateLock) {
        val set = mutableSetOf<String>()
        try {
            val store = ensureStoreLoaded()
            val array = store.optJSONArray("completed") ?: JSONArray()
            for (i in 0 until array.length()) {
                set.add(array.getString(i))
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Failed loading completed book IDs", t)
        }
        set
    }

    fun markBookCompleted(bookId: String): Boolean {
        var added = false
        synchronized(stateLock) {
            val store = ensureStoreLoaded()
            val set = loadCompletedBookIds().toMutableSet()
            if (set.add(bookId)) {
                val array = JSONArray()
                set.forEach { array.put(it) }
                store.put("completed", array)
                added = true
            }
        }
        if (added) scheduleStoreFlush(immediate = true)
        return added
    }

    fun loadBookSpeed(bookId: String): Float = synchronized(stateLock) {
        try {
            val store = ensureStoreLoaded()
            val speeds = store.optJSONObject("speeds") ?: return 1.0f
            if (speeds.has(bookId)) speeds.getDouble(bookId).toFloat() else 1.0f
        } catch (_: Throwable) {
            1.0f
        }
    }

    fun saveBookSpeed(bookId: String, speed: Float) {
        synchronized(stateLock) {
            try {
                val store = ensureStoreLoaded()
                val speeds = store.optJSONObject("speeds") ?: JSONObject().also { store.put("speeds", it) }
                speeds.put(bookId, speed.toDouble())
            } catch (t: Throwable) {
                Log.w(TAG, "Failed saving speed for $bookId", t)
            }
        }
        scheduleStoreFlush(immediate = true)
    }

    // PHASE 5: Fast DocumentsContract Tree Scanner
    suspend fun scanFolderForAudiobooks(folderUri: Uri): List<Audiobook> = withContext(Dispatchers.IO) {
        val bookList = mutableListOf<Audiobook>()
        val rootDocId = DocumentsContract.getTreeDocumentId(folderUri)
        traverseDocumentsTree(
            treeUri = folderUri,
            currentDocId = rootDocId,
            relativeSegments = emptyList(),
            bookList = bookList
        )
        bookList
    }

    private data class DocEntry(
        val docId: String,
        val name: String,
        val mimeType: String,
        val uri: Uri
    ) {
        val isDirectory: Boolean get() = mimeType == DocumentsContract.Document.MIME_TYPE_DIR
        val extension: String get() = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
    }

    private fun queryChildDocuments(treeUri: Uri, parentDocId: String): List<DocEntry> {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE
        )
        val entries = mutableListOf<DocEntry>()
        try {
            appContext.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
                while (cursor.moveToNext()) {
                    val id = cursor.getString(idCol) ?: continue
                    val name = cursor.getString(nameCol) ?: continue
                    val mime = cursor.getString(mimeCol) ?: ""
                    val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, id)
                    entries.add(DocEntry(id, name, mime, docUri))
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Failed querying directory $parentDocId", t)
        }
        return entries
    }

    private fun traverseDocumentsTree(
        treeUri: Uri,
        currentDocId: String,
        relativeSegments: List<String>,
        bookList: MutableList<Audiobook>
    ) {
        val children = queryChildDocuments(treeUri, currentDocId)
        val audioFiles = mutableListOf<DocEntry>()
        val subDirs = mutableListOf<DocEntry>()

        for (child in children) {
            if (child.isDirectory) {
                subDirs.add(child)
            } else if (child.extension in SUPPORTED_EXTENSIONS) {
                audioFiles.add(child)
            }
        }

        if (audioFiles.isNotEmpty()) {
            val (folderAuthor, folderSeries, folderBookTitle) = deriveHierarchyLabels(relativeSegments)

            val isRootMultiContainer = relativeSegments.isEmpty() &&
                audioFiles.size > 1 &&
                audioFiles.all { it.extension in CONTAINER_EXTENSIONS }

            if (isRootMultiContainer) {
                val sortedContainers = audioFiles.sortedWith { a, b -> compareNatural(a.name, b.name) }
                for (file in sortedContainers) {
                    val cleanName = file.name.substringBeforeLast(".")
                    val (metaTitle, metaAuthor) = extractEmbeddedBookMetadata(
                        uri = file.uri,
                        fallbackTitle = cleanName,
                        fallbackAuthor = folderAuthor,
                        isSingleContainer = true
                    )
                    val track = AudioTrack(name = metaTitle, uri = file.uri, isM4b = true)
                    bookList.add(
                        Audiobook(
                            id = file.uri.toString(),
                            title = metaTitle,
                            author = metaAuthor,
                            series = folderSeries,
                            tracks = listOf(track)
                        )
                    )
                }
            } else {
                val sortedTracks = audioFiles.map { file ->
                    AudioTrack(
                        name = file.name.substringBeforeLast("."),
                        uri = file.uri,
                        isM4b = file.extension in CONTAINER_EXTENSIONS
                    )
                }.sortedWith { a, b -> compareNatural(a.name, b.name) }

                val fallbackTitle = folderBookTitle.ifBlank { sortedTracks.first().name }
                val isSingleContainer = sortedTracks.size == 1 && sortedTracks.first().isM4b
                val (resolvedTitle, resolvedAuthor) = extractEmbeddedBookMetadata(
                    uri = sortedTracks.first().uri,
                    fallbackTitle = fallbackTitle,
                    fallbackAuthor = folderAuthor,
                    isSingleContainer = isSingleContainer
                )

                val currentDirUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, currentDocId)
                val bookId = if (isSingleContainer) sortedTracks.first().uri.toString() else currentDirUri.toString()

                bookList.add(
                    Audiobook(
                        id = bookId,
                        title = resolvedTitle,
                        author = resolvedAuthor,
                        series = folderSeries,
                        tracks = sortedTracks
                    )
                )
            }
        }

        val sortedSubDirs = subDirs.sortedWith { a, b -> compareNatural(a.name, b.name) }
        for (dir in sortedSubDirs) {
            traverseDocumentsTree(treeUri, dir.docId, relativeSegments + dir.name, bookList)
        }
    }

    // Properly handles Author/Book, Author/Series/Book, and Author/Series/Book/Disc 1
    private fun deriveHierarchyLabels(segments: List<String>): Triple<String, String, String> {
        if (segments.isEmpty()) return Triple("Unknown Author", "", "")
        if (segments.size == 1) return Triple("Unknown Author", "", segments[0])
        if (segments.size == 2) return Triple(segments[0], "", segments[1])

        val author = segments[0]
        val series = segments[1]
        val leaf = segments.last()
        val bookTitle = if (DISC_FOLDER_REGEX.matches(leaf.trim()) && segments.size >= 4) {
            "${segments[segments.size - 2]} - $leaf"
        } else {
            leaf
        }
        return Triple(author, series, bookTitle)
    }

    private fun extractEmbeddedBookMetadata(
        uri: Uri,
        fallbackTitle: String,
        fallbackAuthor: String,
        isSingleContainer: Boolean
    ): Pair<String, String> {
        val cacheKey = sha256Hex(uri.toString())
        synchronized(stateLock) {
            val store = ensureStoreLoaded()
            val metaCache = store.optJSONObject("metaCache")
            val cached = metaCache?.optJSONObject(cacheKey)
            if (cached != null) {
                val cTitle = cached.optString("title", "")
                val cAuthor = cached.optString("author", "")
                if (cTitle.isNotBlank() && cAuthor.isNotBlank()) {
                    val finalTitle = if (isSingleContainer || fallbackTitle.isBlank()) cTitle else fallbackTitle
                    val finalAuthor = if (isSingleContainer || fallbackAuthor == "Unknown Author") cAuthor else fallbackAuthor
                    return Pair(finalTitle, finalAuthor)
                }
            }
        }

        var embeddedAlbum: String? = null
        var embeddedTitle: String? = null
        var embeddedAuthor: String? = null

        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(appContext, uri)
            val rawTitle = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)?.trim()
            val rawAlbum = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)?.trim()
            val rawAlbumArtist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST)?.trim()
            val rawArtist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)?.trim()
            val rawAuthor = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_AUTHOR)?.trim()

            if (!isGenericTitle(rawAlbum)) embeddedAlbum = rawAlbum
            if (!isGenericTitle(rawTitle)) embeddedTitle = rawTitle

            // Prefer album-artist -> artist -> author
            embeddedAuthor = listOf(rawAlbumArtist, rawArtist, rawAuthor).firstOrNull { !it.isNullOrBlank() }
        } catch (t: Throwable) {
            Log.w(TAG, "Failed extracting metadata from $uri", t)
        } finally {
            try { retriever.release() } catch (_: Throwable) {}
        }

        val bestEmbeddedTitle = embeddedAlbum ?: embeddedTitle
        val resolvedTitle = when {
            isSingleContainer && !bestEmbeddedTitle.isNullOrBlank() -> bestEmbeddedTitle
            !embeddedAlbum.isNullOrBlank() && fallbackTitle.isBlank() -> embeddedAlbum
            else -> fallbackTitle
        }

        val resolvedAuthor = if (!embeddedAuthor.isNullOrBlank() && (fallbackAuthor == "Unknown Author" || isSingleContainer)) {
            embeddedAuthor
        } else {
            fallbackAuthor
        }

        synchronized(stateLock) {
            val store = ensureStoreLoaded()
            val metaCache = store.optJSONObject("metaCache") ?: JSONObject().also { store.put("metaCache", it) }
            metaCache.put(
                cacheKey,
                JSONObject().apply {
                    put("title", resolvedTitle)
                    put("author", resolvedAuthor)
                }
            )
        }
        scheduleStoreFlush(immediate = false)
        return Pair(resolvedTitle, resolvedAuthor)
    }

    private fun isGenericTitle(value: String?): Boolean {
        if (value.isNullOrBlank()) return true
        val trimmed = value.trim()
        return trimmed.startsWith("Track", ignoreCase = true) ||
            trimmed.startsWith("Chapter", ignoreCase = true) ||
            trimmed.startsWith("Part", ignoreCase = true)
    }

    suspend fun getBookChapters(book: Audiobook): List<Chapter> = withContext(Dispatchers.IO) {
        if (book.tracks.any { it.isM4b }) {
            val embeddedChapters = mutableListOf<Chapter>()
            for ((index, track) in book.tracks.withIndex()) {
                val extracted = if (track.isM4b) extractM4bChapters(track.uri, index) else emptyList()
                if (extracted.isNotEmpty()) {
                    embeddedChapters.addAll(extracted)
                } else {
                    var duration = 0L
                    var title = track.name
                    val retriever = MediaMetadataRetriever()
                    try {
                        retriever.setDataSource(appContext, track.uri)
                        duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                        val metaTitle = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                        if (!metaTitle.isNullOrBlank()) title = metaTitle
                    } catch (t: Throwable) {
                        Log.w(TAG, "Metadata chapter fallback failed for ${track.uri}", t)
                    } finally {
                        try { retriever.release() } catch (_: Throwable) {}
                    }
                    embeddedChapters.add(Chapter(title = title, startMs = 0L, durationMs = duration, trackIndex = index))
                }
            }
            if (embeddedChapters.isNotEmpty()) {
                return@withContext embeddedChapters
            }
        }

        val chapters = mutableListOf<Chapter>()
        val cachedDurations = getCachedTrackDurations(book.id)
        val updatedDurations = MutableList(book.tracks.size) { idx -> cachedDurations.getOrNull(idx) ?: 0L }
        var durationsChanged = false
        val retriever = MediaMetadataRetriever()
        for ((index, track) in book.tracks.withIndex()) {
            var duration = updatedDurations[index]
            var title = track.name
            try {
                retriever.setDataSource(appContext, track.uri)
                if (duration <= 0L) {
                    duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                    if (duration > 0L) {
                        updatedDurations[index] = duration
                        durationsChanged = true
                    }
                }
                val metaTitle = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                if (!metaTitle.isNullOrBlank()) title = metaTitle
            } catch (t: Throwable) {
                Log.w(TAG, "Failed reading MP3 chapter metadata for ${track.name}", t)
            }
            chapters.add(Chapter(title = title, startMs = 0L, durationMs = duration, trackIndex = index))
        }
        try { retriever.release() } catch (_: Throwable) {}
        if (durationsChanged) {
            saveBookDurations(book.id, updatedDurations)
        }

        if (chapters.isEmpty()) chapters.add(Chapter("Chapter 1", 0L, 0L, 0))
        chapters
    }

    private fun extractM4bChapters(uri: Uri, trackIndex: Int = 0): List<Chapter> {
        val chapters = mutableListOf<Chapter>()

        try {
            appContext.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                FileInputStream(pfd.fileDescriptor).use { fis ->
                    val parsed = parseMp4ChaptersFromChannel(fis.channel, trackIndex)
                    chapters.addAll(parsed)
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "MP4 atom chapter parse failed for $uri", t)
        }

        if (chapters.isEmpty()) {
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(appContext, uri, null)
                var textTrackIndex = -1

                for (i in 0 until extractor.trackCount) {
                    val format = extractor.getTrackFormat(i)
                    val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                    if (mime.startsWith("text/") || mime.contains("tx3g") || mime.contains("3gpp") || mime.contains("application/x-quicktime")) {
                        textTrackIndex = i
                        break
                    }
                }

                if (textTrackIndex != -1) {
                    extractor.selectTrack(textTrackIndex)
                    val buffer = ByteBuffer.allocate(8192)
                    var chapterIndex = 1

                    while (true) {
                        buffer.clear()
                        val sampleSize = extractor.readSampleData(buffer, 0)
                        if (sampleSize < 0) break

                        val timeMs = (extractor.sampleTime / 1000L).coerceAtLeast(0L)
                        val titleBytes = ByteArray(sampleSize)
                        buffer.get(titleBytes)

                        val title = decodeTx3gTitle(titleBytes, sampleSize, chapterIndex)
                        chapters.add(Chapter(title, timeMs, 0L, trackIndex))
                        chapterIndex++
                        if (!extractor.advance()) break
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "MediaExtractor chapter fallback failed for $uri", t)
            } finally {
                try { extractor.release() } catch (_: Throwable) {}
            }
        }

        if (chapters.isNotEmpty()) {
            chapters.sortBy { it.startMs }
            val deduplicated = mutableListOf<Chapter>()
            for (ch in chapters) {
                if (deduplicated.isEmpty() || ch.startMs - deduplicated.last().startMs >= 500L) {
                    deduplicated.add(ch)
                }
            }
            chapters.clear()
            chapters.addAll(deduplicated)

            for (i in 0 until chapters.size - 1) {
                val calcDuration = (chapters[i + 1].startMs - chapters[i].startMs).coerceAtLeast(0L)
                chapters[i] = chapters[i].copy(durationMs = calcDuration)
            }
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(appContext, uri)
                val totalDuration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                if (totalDuration > chapters.last().startMs) {
                    chapters[chapters.size - 1] = chapters.last().copy(
                        durationMs = totalDuration - chapters.last().startMs
                    )
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Failed calculating final chapter duration for $uri", t)
            } finally {
                try { retriever.release() } catch (_: Throwable) {}
            }
        }

        return chapters
    }

    private data class Mp4Box(
        val type: String,
        val offset: Long,
        val size: Long,
        val headerSize: Int
    ) {
        val payloadOffset: Long get() = offset + headerSize
        val endOffset: Long get() = offset + size
    }

    private fun readBoxHeader(channel: FileChannel, position: Long, limit: Long): Mp4Box? {
        if (position < 0L || position + 8L > limit) return null
        val buf = ByteBuffer.allocate(16).order(ByteOrder.BIG_ENDIAN)
        val read = channel.read(buf, position)
        if (read < 8) return null
        buf.flip()
        val size32 = buf.int.toLong() and 0xFFFFFFFFL
        val typeBytes = ByteArray(4)
        buf.get(typeBytes)
        val type = String(typeBytes, Charsets.ISO_8859_1)

        var boxSize = size32
        var headerSize = 8
        if (size32 == 1L) {
            if (read < 16) return null
            boxSize = buf.long
            headerSize = 16
        } else if (size32 == 0L) {
            boxSize = limit - position
        }
        if (boxSize < headerSize || position + boxSize > limit) return null
        return Mp4Box(type, position, boxSize, headerSize)
    }

    private fun listChildBoxes(channel: FileChannel, start: Long, end: Long): List<Mp4Box> {
        val list = mutableListOf<Mp4Box>()
        var pos = start
        var safetyCounter = 0
        while (pos + 8L <= end && safetyCounter < 10000) {
            val box = readBoxHeader(channel, pos, end) ?: break
            list.add(box)
            if (box.endOffset <= pos) break
            pos = box.endOffset
            safetyCounter++
        }
        return list
    }

    private fun parseMp4ChaptersFromChannel(channel: FileChannel, trackIndex: Int): List<Chapter> {
        val fileSize = channel.size()
        if (fileSize <= 8L) return emptyList()

        val topBoxes = listChildBoxes(channel, 0L, fileSize)
        val moov = topBoxes.firstOrNull { it.type == "moov" } ?: return emptyList()
        val moovChildren = listChildBoxes(channel, moov.payloadOffset, moov.endOffset)

        val udta = moovChildren.firstOrNull { it.type == "udta" }
        if (udta != null) {
            val udtaChildren = listChildBoxes(channel, udta.payloadOffset, udta.endOffset)
            val chpl = udtaChildren.firstOrNull { it.type == "chpl" }
            if (chpl != null) {
                try {
                    val buf = readBoxPayload(channel, chpl, 5)
                    if (buf != null) {
                        val bytes = ByteArray(buf.remaining())
                        buf.get(bytes)
                        val neroChapters = parseChplBytes(bytes, trackIndex)
                        if (neroChapters.isNotEmpty()) return neroChapters
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "Failed parsing box type 'chpl'", t)
                }
            }
        }

        val traks = moovChildren.filter { it.type == "trak" }
        val referencedChapterTrackIds = findReferencedChapterTrackIds(channel, traks)

        for (trak in traks) {
            try {
                val trakChapters = parseQuickTimeTextTrak(channel, trak, trackIndex, referencedChapterTrackIds)
                if (trakChapters.isNotEmpty()) return trakChapters
            } catch (t: Throwable) {
                Log.w(TAG, "Failed parsing QuickTime box type 'trak'", t)
            }
        }

        return emptyList()
    }

    private fun findReferencedChapterTrackIds(channel: FileChannel, traks: List<Mp4Box>): Set<Int> {
        val ids = mutableSetOf<Int>()
        for (trak in traks) {
            val trakChildren = listChildBoxes(channel, trak.payloadOffset, trak.endOffset)
            val tref = trakChildren.firstOrNull { it.type == "tref" } ?: continue
            val trefChildren = listChildBoxes(channel, tref.payloadOffset, tref.endOffset)
            val chap = trefChildren.firstOrNull { it.type == "chap" } ?: continue
            val buf = readBoxPayload(channel, chap, 4) ?: continue
            while (buf.remaining() >= 4) {
                ids.add(buf.int)
            }
        }
        return ids
    }

    private fun readTrackId(channel: FileChannel, trakChildren: List<Mp4Box>): Int {
        val tkhd = trakChildren.firstOrNull { it.type == "tkhd" } ?: return -1
        val buf = readBoxPayload(channel, tkhd, 24) ?: return -1
        val version = buf.get().toInt() and 0xFF
        val trackIdOffset = if (version == 1) 20 else 12
        if (buf.limit() < trackIdOffset + 4) return -1
        buf.position(trackIdOffset)
        return buf.int
    }

    private fun parseQuickTimeTextTrak(
        channel: FileChannel,
        trak: Mp4Box,
        trackIndex: Int,
        referencedChapterTrackIds: Set<Int>
    ): List<Chapter> {
        val trakChildren = listChildBoxes(channel, trak.payloadOffset, trak.endOffset)
        val tkhdId = readTrackId(channel, trakChildren)
        val isReferencedChapTrack = tkhdId != -1 && referencedChapterTrackIds.contains(tkhdId)

        val mdia = trakChildren.firstOrNull { it.type == "mdia" } ?: return emptyList()
        val mdiaChildren = listChildBoxes(channel, mdia.payloadOffset, mdia.endOffset)

        val mdhd = mdiaChildren.firstOrNull { it.type == "mdhd" } ?: return emptyList()
        val hdlr = mdiaChildren.firstOrNull { it.type == "hdlr" }
        val minf = mdiaChildren.firstOrNull { it.type == "minf" } ?: return emptyList()

        val mdhdBuf = readBoxPayload(channel, mdhd, 16) ?: return emptyList()
        val mdhdVersion = mdhdBuf.get().toInt() and 0xFF
        val timescalePos = if (mdhdVersion == 1) 20 else 12
        if (mdhdBuf.limit() < timescalePos + 4) return emptyList()
        mdhdBuf.position(timescalePos)
        val timescale = (mdhdBuf.int.toLong() and 0xFFFFFFFFL).coerceAtLeast(1L)

        var isTextTrack = isReferencedChapTrack
        if (!isTextTrack && hdlr != null) {
            val hdlrBuf = readBoxPayload(channel, hdlr, 12)
            if (hdlrBuf != null && hdlrBuf.limit() >= 12) {
                val hdlrType = String(hdlrBuf.array(), 8, 4, Charsets.ISO_8859_1)
                if (hdlrType == "text" || hdlrType == "sbtl" || hdlrType == "subt" || hdlrType == "chap") {
                    isTextTrack = true
                }
            }
        }

        val minfChildren = listChildBoxes(channel, minf.payloadOffset, minf.endOffset)
        val stbl = minfChildren.firstOrNull { it.type == "stbl" } ?: return emptyList()
        val stblChildren = listChildBoxes(channel, stbl.payloadOffset, stbl.endOffset)

        val stsd = stblChildren.firstOrNull { it.type == "stsd" }
        if (!isTextTrack && stsd != null) {
            val stsdBuf = readBoxPayload(channel, stsd, 16)
            if (stsdBuf != null && stsdBuf.limit() >= 16) {
                val sampleFmt = String(stsdBuf.array(), 12, 4, Charsets.ISO_8859_1)
                if (sampleFmt == "tx3g" || sampleFmt == "text" || sampleFmt == "c608") {
                    isTextTrack = true
                }
            }
        }
        if (!isTextTrack) return emptyList()

        val stts = stblChildren.firstOrNull { it.type == "stts" } ?: return emptyList()
        val stsc = stblChildren.firstOrNull { it.type == "stsc" } ?: return emptyList()
        val stsz = stblChildren.firstOrNull { it.type == "stsz" } ?: return emptyList()
        val stco = stblChildren.firstOrNull { it.type == "stco" }
        val co64 = stblChildren.firstOrNull { it.type == "co64" }
        if (stco == null && co64 == null) return emptyList()

        val sttsBuf = readBoxPayload(channel, stts, 8) ?: return emptyList()
        sttsBuf.position(4)
        val sttsCount = sttsBuf.int
        if (sttsCount <= 0 || sttsCount > 10000) return emptyList()
        val sampleDeltas = mutableListOf<Long>()
        for (i in 0 until sttsCount) {
            if (sttsBuf.remaining() < 8) break
            val sCount = sttsBuf.int
            val sDelta = sttsBuf.int.toLong() and 0xFFFFFFFFL
            if (sCount <= 0 || sCount > 10000) break
            for (j in 0 until sCount) {
                if (sampleDeltas.size >= 5000) break
                sampleDeltas.add(sDelta)
            }
        }
        if (sampleDeltas.isEmpty()) return emptyList()

        val stszBuf = readBoxPayload(channel, stsz, 12) ?: return emptyList()
        stszBuf.position(4)
        val defaultSize = stszBuf.int
        val sampleCount = stszBuf.int
        if (sampleCount <= 0 || sampleCount > 5000) return emptyList()
        val sampleSizes = IntArray(sampleCount)
        for (i in 0 until sampleCount) {
            sampleSizes[i] = if (defaultSize != 0) {
                defaultSize
            } else if (stszBuf.remaining() >= 4) {
                stszBuf.int
            } else {
                0
            }
        }

        val chunkOffsets: LongArray
        if (stco != null) {
            val stcoBuf = readBoxPayload(channel, stco, 8) ?: return emptyList()
            stcoBuf.position(4)
            val chunkCount = stcoBuf.int
            if (chunkCount <= 0 || chunkCount > 10000) return emptyList()
            chunkOffsets = LongArray(chunkCount)
            for (i in 0 until chunkCount) {
                if (stcoBuf.remaining() < 4) break
                chunkOffsets[i] = stcoBuf.int.toLong() and 0xFFFFFFFFL
            }
        } else {
            val co64Buf = readBoxPayload(channel, co64!!, 8) ?: return emptyList()
            co64Buf.position(4)
            val chunkCount = co64Buf.int
            if (chunkCount <= 0 || chunkCount > 10000) return emptyList()
            chunkOffsets = LongArray(chunkCount)
            for (i in 0 until chunkCount) {
                if (co64Buf.remaining() < 8) break
                chunkOffsets[i] = co64Buf.long
            }
        }

        val stscBuf = readBoxPayload(channel, stsc, 8) ?: return emptyList()
        stscBuf.position(4)
        val stscCount = stscBuf.int
        if (stscCount <= 0 || stscCount > 10000) return emptyList()
        data class StscEntry(val firstChunk: Int, val samplesPerChunk: Int)
        val stscEntries = mutableListOf<StscEntry>()
        for (i in 0 until stscCount) {
            if (stscBuf.remaining() < 12) break
            val firstChunk = stscBuf.int
            val samplesPerChunk = stscBuf.int
            stscBuf.int
            if (firstChunk > 0 && samplesPerChunk > 0) {
                stscEntries.add(StscEntry(firstChunk, samplesPerChunk))
            }
        }
        if (stscEntries.isEmpty()) return emptyList()

        val chapters = mutableListOf<Chapter>()
        var sampleIdx = 0
        var accumulatedTicks = 0L
        val fileSize = channel.size()

        for (chunkIdx in chunkOffsets.indices) {
            val chunkNum1Based = chunkIdx + 1
            val stscEntry = stscEntries.lastOrNull { it.firstChunk <= chunkNum1Based } ?: stscEntries.first()
            var currentOffset = chunkOffsets[chunkIdx]

            for (s in 0 until stscEntry.samplesPerChunk) {
                if (sampleIdx >= sampleCount) break
                val size = sampleSizes[sampleIdx]
                val delta = if (sampleIdx < sampleDeltas.size) sampleDeltas[sampleIdx] else 0L
                val startMs = (accumulatedTicks * 1000L) / timescale
                val durationMs = (delta * 1000L) / timescale

                if (size in 2..8192 && currentOffset >= 0L && currentOffset + size <= fileSize) {
                    val sampleBuf = ByteBuffer.allocate(size)
                    val readBytes = channel.read(sampleBuf, currentOffset)
                    if (readBytes >= 2) {
                        val title = decodeTx3gTitle(sampleBuf.array(), readBytes, sampleIdx + 1)
                        chapters.add(Chapter(title, startMs, durationMs, trackIndex))
                    }
                }
                accumulatedTicks += delta
                currentOffset += size.coerceAtLeast(0)
                sampleIdx++
            }
            if (sampleIdx >= sampleCount) break
        }

        return chapters
    }

    private fun readBoxPayload(channel: FileChannel, box: Mp4Box, minBytes: Int): ByteBuffer? {
        val rawSize = box.endOffset - box.payloadOffset
        if (rawSize < minBytes || rawSize > 5 * 1024 * 1024) return null
        val size = rawSize.toInt()
        val buf = ByteBuffer.allocate(size).order(ByteOrder.BIG_ENDIAN)
        val read = channel.read(buf, box.payloadOffset)
        if (read < minBytes) return null
        buf.flip()
        return buf
    }

    private fun sha256Hex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(32)
        for (i in 0 until 16) {
            sb.append(String.format(Locale.US, "%02x", digest[i].toInt() and 0xFF))
        }
        return sb.toString()
    }

    private fun coverFileFor(bookId: String): File {
        val coversDir = File(appContext.filesDir, "covers")
        if (!coversDir.exists()) coversDir.mkdirs()
        return File(coversDir, "${sha256Hex(bookId)}.png")
    }

    // PHASE 4: Memory -> SHA-256 Disk (with legacy hashCode migration) -> Embedded Art ONLY
    suspend fun getOrFetchCoverArt(book: Audiobook): Bitmap? = withContext(Dispatchers.IO) {
        CoverMemoryCache.cache.get(book.id)?.let { return@withContext it }

        val shaFile = coverFileFor(book.id)
        if (shaFile.exists()) {
            val bmp = decodeSampledBitmapFromFile(shaFile.absolutePath, 350, 350)
            if (bmp != null) {
                CoverMemoryCache.cache.put(book.id, bmp)
                return@withContext bmp
            }
        } else {
            val legacyFile = File(appContext.filesDir, "covers/${book.id.hashCode()}.png")
            if (legacyFile.exists()) {
                val bmp = decodeSampledBitmapFromFile(legacyFile.absolutePath, 350, 350)
                if (bmp != null) {
                    saveBitmapToCache(book.id, bmp)
                    try { legacyFile.delete() } catch (_: Throwable) {}
                    CoverMemoryCache.cache.put(book.id, bmp)
                    return@withContext bmp
                }
            }
        }

        val firstUri = book.tracks.firstOrNull()?.uri ?: return@withContext null
        val embedded = loadEmbeddedCoverArt(firstUri)
        if (embedded != null) {
            saveBitmapToCache(book.id, embedded)
            CoverMemoryCache.cache.put(book.id, embedded)
            return@withContext embedded
        }
        null
    }

    // Explicit user-triggered online cover search
    suspend fun fetchAndSaveOnlineCoverArt(book: Audiobook): Bitmap? = withContext(Dispatchers.IO) {
        val onlineBmp = fetchOnlineCoverArt(book.title, book.author)
        if (onlineBmp != null) {
            saveBitmapToCache(book.id, onlineBmp)
            CoverMemoryCache.cache.put(book.id, onlineBmp)
        }
        onlineBmp
    }

    suspend fun saveCustomCoverArt(bookId: String, uri: Uri): Bitmap? = withContext(Dispatchers.IO) {
        try {
            val stream = appContext.contentResolver.openInputStream(uri)
            if (stream != null) {
                val bitmap = decodeSampledBitmapFromStreamWithExif(stream, 350, 350)
                stream.close()
                if (bitmap != null) {
                    saveBitmapToCache(bookId, bitmap)
                    CoverMemoryCache.cache.put(bookId, bitmap)
                    return@withContext bitmap
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Failed saving custom cover art for $bookId", t)
        }
        null
    }

    fun bitmapToJpegBytes(bitmap: Bitmap?): ByteArray? {
        if (bitmap == null) return null
        return try {
            val out = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
            out.toByteArray()
        } catch (t: Throwable) {
            Log.w(TAG, "Failed compressing cover art to JPEG", t)
            null
        }
    }

    private fun fetchOnlineCoverArt(title: String, author: String): Bitmap? {
        return try {
            val query = URLEncoder.encode("$title $author", "UTF-8")
            val url = URL("https://openlibrary.org/search.json?q=$query&limit=1")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            val jsonText = conn.inputStream.bufferedReader().use { it.readText() }
            val docs = JSONObject(jsonText).optJSONArray("docs")
            if (docs != null && docs.length() > 0) {
                val coverI = docs.getJSONObject(0).optInt("cover_i", -1)
                if (coverI != -1) {
                    val imgUrl = URL("https://covers.openlibrary.org/b/id/$coverI-M.jpg")
                    imgUrl.openStream().use { stream ->
                        return decodeSampledBitmapFromStreamWithExif(stream, 350, 350)
                    }
                }
            }
            null
        } catch (t: Throwable) {
            Log.w(TAG, "Open Library cover search failed for '$title'", t)
            null
        }
    }

    private fun loadEmbeddedCoverArt(uri: Uri): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(appContext, uri)
            val artBytes = retriever.embeddedPicture
            if (artBytes != null) {
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(artBytes, 0, artBytes.size, options)
                options.inSampleSize = calculateInSampleSize(options, 350, 350)
                options.inJustDecodeBounds = false
                BitmapFactory.decodeByteArray(artBytes, 0, artBytes.size, options)
            } else null
        } catch (t: Throwable) {
            Log.w(TAG, "No embedded cover art or decode failed for $uri", t)
            null
        } finally {
            try { retriever.release() } catch (_: Throwable) {}
        }
    }

    private fun saveBitmapToCache(bookId: String, bitmap: Bitmap) {
        try {
            val cacheFile = coverFileFor(bookId)
            FileOutputStream(cacheFile).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 90, out)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Failed writing cover cache file for $bookId", t)
        }
    }

    private fun decodeSampledBitmapFromFile(path: String, reqWidth: Int, reqHeight: Int): Bitmap? {
        return try {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, options)
            options.inSampleSize = calculateInSampleSize(options, reqWidth, reqHeight)
            options.inJustDecodeBounds = false
            BitmapFactory.decodeFile(path, options)
        } catch (t: Throwable) {
            Log.w(TAG, "Failed decoding cover file $path", t)
            null
        }
    }

    private fun decodeSampledBitmapFromStreamWithExif(stream: InputStream, reqWidth: Int, reqHeight: Int): Bitmap? {
        val bytes = stream.readBytes()
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        options.inSampleSize = calculateInSampleSize(options, reqWidth, reqHeight)
        options.inJustDecodeBounds = false
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null

        return try {
            val exif = ExifInterface(ByteArrayInputStream(bytes))
            val orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            val matrix = Matrix()
            when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
                ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
                ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.preScale(-1f, 1f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.preScale(1f, -1f)
                else -> return decoded
            }
            val rotated = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
            if (rotated != decoded) decoded.recycle()
            rotated
        } catch (t: Throwable) {
            Log.w(TAG, "Failed applying EXIF rotation to cover image", t)
            decoded
        }
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
// END OF FILE