package com.example.audiobook

import android.content.ContentUris
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Base64
import android.util.Log
import android.util.LruCache
import androidx.core.text.HtmlCompat
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
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.StringReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.charset.Charset
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipInputStream

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

data class Ebook(
    val id: String,
    val title: String,
    val author: String,
    val uri: Uri,
    val format: String = "EPUB"
)

data class EbookChapter(
    val index: Int,
    val title: String,
    val bodyText: String
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

data class CoverSearchCandidate(
    val title: String,
    val author: String,
    val source: String,
    val previewBitmap: Bitmap,
    val fullResUrl: String
)

// All 35 supported eBook, Comic, and Document extensions
val ALL_SUPPORTED_EBOOK_EXTENSIONS = setOf(
    "epub", "pdf", "cbz", "cbr", "cb7", "cbt", "cba", "cbc",
    "mobi", "azw", "azw3", "kfx", "prc", "azw4", "tpz", "topaz",
    "txt", "md", "rtf", "doc", "docx", "html", "htm", "htmlz",
    "fb2", "fbz", "djvu", "ibooks", "lit", "pdb", "lrf", "lrs",
    "lrx", "rb", "tcr", "chm"
)

val VISUAL_PAGE_EBOOK_FORMATS = setOf(
    "PDF", "AZW4", "CBZ", "CBT", "CBC", "CBR", "CB7", "CBA", "DJVU"
)

fun detectEbookFormatFromFileName(fileName: String): String? {
    val lower = fileName.lowercase(Locale.ROOT).trim()
    if (lower.endsWith(".fb2.zip")) return "FB2.ZIP"
    val ext = lower.substringAfterLast('.', "")
    return if (ext in ALL_SUPPORTED_EBOOK_EXTENSIONS) ext.uppercase(Locale.ROOT) else null
}

fun parseCleanTitleAndAuthorFromFilename(rawFileName: String): Pair<String, String> {
    var name = rawFileName
        .substringAfterLast('/')
        .replace(Regex("(?i)\\.fb2\\.zip$"), "")
        .replace(
            Regex("(?i)\\.(epub|pdf|cbz|cbr|cb7|cbt|cba|cbc|mobi|azw|azw3|kfx|prc|azw4|tpz|topaz|txt|md|rtf|doc|docx|html|htm|htmlz|fb2|fbz|djvu|ibooks|lit|pdb|lrf|lrs|lrx|rb|tcr|chm|kf8|m4b|mp3|m4a)$"),
            ""
        )
        .replace(Regex("(?i)^_?oceanofpdf\\.com_?"), "")
        .replace(Regex("(?i)\\b(oceanofpdf\\.com|z-lib\\.org|z-library|annas-archive|pdfdrive|libgen)\\b"), " ")
        .replace('_', ' ')
        .replace(Regex("\\[[^]]*]"), " ")
        .replace(Regex("\\([^)]*\\)"), " ")
        .replace(Regex("\\b97[89]\\d{10}\\b"), " ")
        .replace(Regex("(?i)\\b(unabridged|abridged|audiobook|ebook|epub|pdf|azw3|mobi|retail|v\\d+(\\.\\d+)?|64kbps|128kbps|disc\\s*\\d+|part\\s*\\d+|cd\\s*\\d+)\\b"), " ")
        .replace(Regex("^\\s*\\d+\\s*[-._]\\s*"), " ")

    if (!name.contains(' ') && name.count { it == '.' } >= 2) {
        name = name.replace('.', ' ')
    }

    name = name.replace(Regex("\\s+"), " ").trim()

    if (name.contains(" - ")) {
        val parts = name.split(" - ").map { it.trim() }.filter { it.isNotBlank() }
        if (parts.size >= 2) {
            return Pair(parts.first(), parts.last())
        }
    }

    return Pair(name.ifBlank { rawFileName.substringBeforeLast('.') }, "Unknown Author")
}

fun cleanAudiobookSearchQuery(rawTitle: String, rawAuthor: String = ""): String {
    val (parsedTitle, parsedAuthorFromTitle) = parseCleanTitleAndAuthorFromFilename(rawTitle)
    var cleanedTitle = parsedTitle
        .replace('.', ' ')
        .replace(Regex("(?i)\\b(book\\s*\\d+|vol\\s*\\d+|volume\\s*\\d+)\\b"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    if (cleanedTitle.isBlank()) {
        cleanedTitle = rawTitle.trim()
    }

    val candidateAuthor = if (rawAuthor.isNotBlank() && !rawAuthor.equals("Unknown Author", ignoreCase = true)) {
        rawAuthor.trim()
    } else {
        parsedAuthorFromTitle.trim()
    }

    val validAuthor = candidateAuthor.isNotBlank() &&
        !candidateAuthor.equals("Unknown Author", ignoreCase = true) &&
        !candidateAuthor.equals("Unknown", ignoreCase = true) &&
        !cleanedTitle.contains(candidateAuthor, ignoreCase = true)

    return if (validAuthor) "$cleanedTitle $candidateAuthor".trim() else cleanedTitle
}

fun normalizeChapterTitles(rawChapters: List<Chapter>, bookTitle: String = ""): List<Chapter> {
    if (rawChapters.isEmpty()) return listOf(Chapter("Chapter 1", 0L, 0L, 0))
    val cleanBookTitle = bookTitle.trim()
    val trimmedTitles = rawChapters.map { it.title.trim() }

    val meaningfulTitles = trimmedTitles.filter {
        it.isNotBlank() && !it.equals(cleanBookTitle, ignoreCase = true)
    }
    val uniqueMeaningfulCount = meaningfulTitles.map { it.lowercase(Locale.ROOT) }.distinct().size

    val relabelAll = rawChapters.size > 1 && (
        meaningfulTitles.isEmpty() ||
            uniqueMeaningfulCount <= 1 ||
            uniqueMeaningfulCount <= (rawChapters.size / 2)
        )

    if (relabelAll) {
        return rawChapters.mapIndexed { index, chapter ->
            chapter.copy(title = "Chapter ${index + 1}")
        }
    }

    val titleCounts = trimmedTitles
        .groupingBy { it.lowercase(Locale.ROOT) }
        .eachCount()

    return rawChapters.mapIndexed { index, chapter ->
        val t = chapter.title.trim()
        val isDuplicate = (titleCounts[t.lowercase(Locale.ROOT)] ?: 0) > 1
        val isBookTitleOrBlank = t.isBlank() || (cleanBookTitle.isNotBlank() && t.equals(cleanBookTitle, ignoreCase = true))
        if (isBookTitleOrBlank || isDuplicate) {
            chapter.copy(title = "Chapter ${index + 1}")
        } else {
            chapter.copy(title = t)
        }
    }
}

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
    buf.get(); buf.get(); buf.get()
    if (version == 1) {
        if (buf.remaining() < 4) return emptyList()
        buf.int
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
        const val MIN_AUDIOBOOK_DURATION_MS = 5 * 60 * 1000L

        private val SUPPORTED_EXTENSIONS = setOf(
            "mp3", "m4a", "m4b", "m4p", "mp4", "aac", "ogg", "opus", "flac", "wav"
        )
        private val KINDLE_EXTENSIONS = setOf("azw3", "azw", "mobi", "prc", "kf8", "pdb")
        private val COMIC_EXTENSIONS = setOf("cbz", "cbr", "cb7", "cbt", "cba", "cbc")
        private val CONTAINER_EXTENSIONS = setOf("m4a", "m4b", "m4p", "mp4")
        private val AUTO_SCAN_ROOT_FOLDERS = setOf(
            "download", "downloads", "audiobooks", "audiobook", "books", "book", "audible", "documents", "ebooks", "ebook", "kindle", "comics"
        )
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
            root.put("ebooks", JSONArray())
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
            root.put("ebookProgress", JSONObject())
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

    fun getScanMode(): String {
        val defaultMode = if (!getSelectedFolderUri().isNullOrEmpty()) "FOLDER" else "AUTO"
        return prefs.getString("scan_mode", defaultMode) ?: defaultMode
    }

    fun saveScanMode(mode: String) = prefs.edit().putString("scan_mode", mode).apply()

    fun getLastBookId(): String? = prefs.getString("last_book_id", "")
    fun getLastEbookId(): String? = prefs.getString("last_ebook_id", "")

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

    // eBook Persistence & Progress
    fun saveEbooksCache(ebooks: List<Ebook>) {
        synchronized(stateLock) {
            val store = ensureStoreLoaded()
            val arr = JSONArray()
            for (eb in ebooks) {
                arr.put(
                    JSONObject().apply {
                        put("id", eb.id)
                        put("title", eb.title)
                        put("author", eb.author)
                        put("uri", eb.uri.toString())
                        put("format", eb.format)
                    }
                )
            }
            store.put("ebooks", arr)
        }
        scheduleStoreFlush(immediate = true)
    }

    fun loadEbooksCache(): List<Ebook> {
        val list = mutableListOf<Ebook>()
        synchronized(stateLock) {
            try {
                val store = ensureStoreLoaded()
                val arr = store.optJSONArray("ebooks") ?: JSONArray()
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    val uriStr = obj.optString("uri", "")
                    if (uriStr.isNotBlank()) {
                        val rawTitle = obj.optString("title", "Untitled eBook")
                        val rawAuthor = obj.optString("author", "Unknown Author")
                        val (cleanedTitle, cleanedAuthor) = if (
                            rawTitle.contains('_') ||
                            detectEbookFormatFromFileName(rawTitle) != null
                        ) {
                            parseCleanTitleAndAuthorFromFilename(rawTitle)
                        } else {
                            Pair(rawTitle, rawAuthor)
                        }
                        val defaultFormat = detectEbookFormatFromFileName(uriStr) ?: "EPUB"
                        val format = obj.optString("format", defaultFormat)
                        list.add(
                            Ebook(
                                id = obj.optString("id", uriStr),
                                title = cleanedTitle,
                                author = if (rawAuthor != "Unknown Author") rawAuthor else cleanedAuthor,
                                uri = Uri.parse(uriStr),
                                format = format
                            )
                        )
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Failed loading cached ebooks", t)
            }
        }
        return list
    }

    fun touchEbookLastRead(ebookId: String, timestampMs: Long = System.currentTimeMillis()) {
        if (ebookId.isEmpty()) return
        prefs.edit().putString("last_ebook_id", ebookId).apply()
        synchronized(stateLock) {
            val store = ensureStoreLoaded()
            val ebProg = store.optJSONObject("ebookProgress") ?: JSONObject().also { store.put("ebookProgress", it) }
            val entry = ebProg.optJSONObject(ebookId) ?: JSONObject()
            entry.put("lastReadMs", timestampMs)
            ebProg.put(ebookId, entry)
        }
        scheduleStoreFlush(immediate = false)
    }

    fun saveEbookProgress(ebookId: String, chapterIndex: Int, totalChapters: Int) {
        if (ebookId.isEmpty()) return
        val now = System.currentTimeMillis()
        prefs.edit().putString("last_ebook_id", ebookId).apply()
        synchronized(stateLock) {
            val store = ensureStoreLoaded()
            val ebProg = store.optJSONObject("ebookProgress") ?: JSONObject().also { store.put("ebookProgress", it) }
            val entry = ebProg.optJSONObject(ebookId) ?: JSONObject()
            entry.put("chapterIndex", chapterIndex.coerceAtLeast(0))
            entry.put("totalChapters", totalChapters.coerceAtLeast(1))
            entry.put("lastReadMs", now)
            ebProg.put(ebookId, entry)
        }
        scheduleStoreFlush(immediate = false)
    }

    fun getEbookChapterIndex(ebookId: String): Int = synchronized(stateLock) {
        val store = ensureStoreLoaded()
        store.optJSONObject("ebookProgress")?.optJSONObject(ebookId)?.optInt("chapterIndex", 0) ?: 0
    }

    fun getEbookTotalChapters(ebookId: String): Int = synchronized(stateLock) {
        val store = ensureStoreLoaded()
        store.optJSONObject("ebookProgress")?.optJSONObject(ebookId)?.optInt("totalChapters", 0) ?: 0
    }

    fun getEbookLastReadTime(ebookId: String): Long = synchronized(stateLock) {
        val store = ensureStoreLoaded()
        store.optJSONObject("ebookProgress")?.optJSONObject(ebookId)?.optLong("lastReadMs", 0L) ?: 0L
    }

    fun touchBookLastPlayed(bookId: String, timestampMs: Long = System.currentTimeMillis()) {
        if (bookId.isEmpty()) return
        prefs.edit().putString("last_book_id", bookId).apply()
        synchronized(stateLock) {
            val store = ensureStoreLoaded()
            val progressObj = store.optJSONObject("progress") ?: JSONObject().also { store.put("progress", it) }
            val entry = progressObj.optJSONObject(bookId) ?: JSONObject()
            entry.put("lastPlayedMs", timestampMs)
            progressObj.put(bookId, entry)
        }
        scheduleStoreFlush(immediate = false)
    }

    fun getBookLastPlayedTime(bookId: String): Long = synchronized(stateLock) {
        val store = ensureStoreLoaded()
        store.optJSONObject("progress")?.optJSONObject(bookId)?.optLong("lastPlayedMs", 0L) ?: 0L
    }

    fun saveBookProgress(
        bookId: String,
        trackIndex: Int,
        positionMs: Long,
        totalListenMs: Long,
        immediate: Boolean = true
    ) {
        if (bookId.isEmpty()) return
        val now = System.currentTimeMillis()
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
            entry.put("lastPlayedMs", now)
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

    private fun openUriInputStream(uri: Uri): InputStream? {
        return try {
            if (uri.scheme == "file") {
                val path = uri.path ?: return null
                FileInputStream(File(path))
            } else {
                appContext.contentResolver.openInputStream(uri)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "openUriInputStream failed for $uri", t)
            null
        }
    }

    private fun openUriParcelFileDescriptor(uri: Uri): ParcelFileDescriptor? {
        return try {
            if (uri.scheme == "file") {
                val path = uri.path ?: return null
                ParcelFileDescriptor.open(File(path), ParcelFileDescriptor.MODE_READ_ONLY)
            } else {
                appContext.contentResolver.openFileDescriptor(uri, "r")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "openUriParcelFileDescriptor failed for $uri", t)
            null
        }
    }

    private fun readUriBytesBounded(uri: Uri, maxBytes: Int = 48 * 1024 * 1024): ByteArray? {
        return try {
            openUriInputStream(uri)?.use { stream ->
                val out = ByteArrayOutputStream()
                val buf = ByteArray(16384)
                var total = 0
                while (true) {
                    val r = stream.read(buf)
                    if (r <= 0) break
                    total += r
                    if (total > maxBytes) break
                    out.write(buf, 0, r)
                }
                out.toByteArray()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "readUriBytesBounded failed for $uri", t)
            null
        }
    }

    // Universal 35-Format eBook, Comic & Document Scanner
    suspend fun scanDeviceForEbooks(): List<Ebook> = withContext(Dispatchers.IO) {
        val discovered = linkedMapOf<String, Ebook>()

        for (cached in loadEbooksCache()) {
            if (openUriParcelFileDescriptor(cached.uri)?.use { true } == true) {
                val key = "${cached.title.lowercase(Locale.ROOT)}|${cached.format}"
                discovered[key] = cached
            }
        }

        val rootDir = Environment.getExternalStorageDirectory()
        val targetDirs = listOf(
            File(rootDir, "Download"),
            File(rootDir, "Downloads"),
            File(rootDir, "Documents"),
            File(rootDir, "Books"),
            File(rootDir, "eBooks"),
            File(rootDir, "Audiobooks"),
            File(rootDir, "Kindle"),
            File(rootDir, "Comics")
        )
        for (dir in targetDirs) {
            if (dir.exists() && dir.isDirectory && dir.canRead()) {
                scanDirectoryRecursiveForEbooks(dir, 0, discovered)
            }
        }

        try {
            val collection = MediaStore.Files.getContentUri("external")
            val projection = arrayOf(
                MediaStore.Files.FileColumns._ID,
                MediaStore.Files.FileColumns.DISPLAY_NAME
            )
            val allPatterns = ALL_SUPPORTED_EBOOK_EXTENSIONS.map { "%.$it" } + listOf("%.fb2.zip")
            val likeClauses = allPatterns.joinToString(" OR ") {
                "${MediaStore.Files.FileColumns.DISPLAY_NAME} LIKE ?"
            }
            val selectionArgs = allPatterns.toTypedArray()

            appContext.contentResolver.query(collection, projection, likeClauses, selectionArgs, null)?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val displayName = cursor.getString(nameCol) ?: continue
                    if (detectEbookFormatFromFileName(displayName) == null) continue

                    val contentUri = ContentUris.withAppendedId(collection, id)
                    val isReadable = openUriParcelFileDescriptor(contentUri)?.use { true } ?: false
                    if (!isReadable) continue

                    val ebook = buildEbookFromUri(contentUri, displayName)
                    val dedupKey = "${ebook.title.lowercase(Locale.ROOT)}|${ebook.format}"
                    if (!discovered.containsKey(dedupKey)) {
                        discovered[dedupKey] = ebook
                    }
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "MediaStore ebook scan encountered non-fatal issue", t)
        }

        val savedFolderStr = getSelectedFolderUri()
        if (!savedFolderStr.isNullOrEmpty() && savedFolderStr != "AUTO_SCAN") {
            try {
                val folderEbooks = scanFolderForEbooks(Uri.parse(savedFolderStr))
                for (eb in folderEbooks) {
                    val dedupKey = "${eb.title.lowercase(Locale.ROOT)}|${eb.format}"
                    discovered[dedupKey] = eb
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Custom folder ebook scan failed", t)
            }
        }

        val finalEbooks = discovered.values.sortedWith { a, b -> compareNatural(a.title, b.title) }
        saveEbooksCache(finalEbooks)
        finalEbooks
    }

    private fun scanDirectoryRecursiveForEbooks(
        dir: File,
        depth: Int,
        discovered: LinkedHashMap<String, Ebook>
    ) {
        if (depth > 4) return
        val files = dir.listFiles() ?: return
        for (f in files) {
            if (f.name.startsWith(".")) continue
            if (f.isDirectory) {
                scanDirectoryRecursiveForEbooks(f, depth + 1, discovered)
            } else if (f.isFile && f.canRead()) {
                if (detectEbookFormatFromFileName(f.name) != null) {
                    val fileUri = Uri.fromFile(f)
                    val ebook = buildEbookFromUri(fileUri, f.name)
                    val dedupKey = "${ebook.title.lowercase(Locale.ROOT)}|${ebook.format}"
                    if (!discovered.containsKey(dedupKey)) {
                        discovered[dedupKey] = ebook
                    }
                }
            }
        }
    }

    private fun buildEbookFromUri(uri: Uri, fileName: String): Ebook {
        val format = detectEbookFormatFromFileName(fileName) ?: "EPUB"
        val ext = format.lowercase(Locale.ROOT)
        val (cleanFallbackTitle, cleanFallbackAuthor) = parseCleanTitleAndAuthorFromFilename(fileName)
        val uriStr = uri.toString()

        return when {
            ext in setOf("epub", "ibooks", "htmlz") -> {
                val (title, author) = extractEpubMetadataAndCover(
                    uri = uri,
                    ebookId = uriStr,
                    fallbackTitle = cleanFallbackTitle,
                    fallbackAuthor = cleanFallbackAuthor
                )
                Ebook(id = uriStr, title = title, author = author, uri = uri, format = format)
            }
            ext == "pdf" || ext == "azw4" -> {
                extractPdfOrAzw4CoverAndPageCount(uri, uriStr, ext == "azw4")
                Ebook(id = uriStr, title = cleanFallbackTitle, author = cleanFallbackAuthor, uri = uri, format = format)
            }
            ext in COMIC_EXTENSIONS -> {
                extractComicCoverAndPageCount(uri, uriStr, ext)
                Ebook(id = uriStr, title = cleanFallbackTitle, author = cleanFallbackAuthor, uri = uri, format = format)
            }
            ext in KINDLE_EXTENSIONS -> {
                val (title, author) = extractKindleMetadataAndCover(
                    uri = uri,
                    ebookId = uriStr,
                    fallbackTitle = cleanFallbackTitle,
                    fallbackAuthor = cleanFallbackAuthor
                )
                Ebook(id = uriStr, title = title, author = author, uri = uri, format = format)
            }
            ext == "fb2" || ext == "fbz" || ext == "fb2.zip" -> {
                val (title, author) = extractFb2MetadataAndCover(
                    uri = uri,
                    ebookId = uriStr,
                    fallbackTitle = cleanFallbackTitle,
                    fallbackAuthor = cleanFallbackAuthor,
                    isZipped = ext != "fb2"
                )
                Ebook(id = uriStr, title = title, author = author, uri = uri, format = if (ext == "fb2.zip") "FB2" else format)
            }
            ext == "docx" -> {
                val (title, author) = extractDocxMetadataAndCover(
                    uri = uri,
                    ebookId = uriStr,
                    fallbackTitle = cleanFallbackTitle,
                    fallbackAuthor = cleanFallbackAuthor
                )
                Ebook(id = uriStr, title = title, author = author, uri = uri, format = "DOCX")
            }
            else -> {
                Ebook(id = uriStr, title = cleanFallbackTitle, author = cleanFallbackAuthor, uri = uri, format = format)
            }
        }
    }

    suspend fun scanFolderForEbooks(folderUri: Uri): List<Ebook> = withContext(Dispatchers.IO) {
        val list = mutableListOf<Ebook>()
        val rootDocId = DocumentsContract.getTreeDocumentId(folderUri)
        traverseFolderForEbooks(folderUri, rootDocId, list)
        list
    }

    private fun traverseFolderForEbooks(treeUri: Uri, currentDocId: String, ebookList: MutableList<Ebook>) {
        val children = queryChildDocuments(treeUri, currentDocId)
        for (child in children) {
            if (child.isDirectory) {
                traverseFolderForEbooks(treeUri, child.docId, ebookList)
            } else if (detectEbookFormatFromFileName(child.name) != null) {
                ebookList.add(buildEbookFromUri(child.uri, child.name))
            }
        }
    }

    suspend fun importEpubUris(uris: List<Uri>): List<Ebook> = withContext(Dispatchers.IO) {
        val existing = loadEbooksCache().associateBy { it.id }.toMutableMap()
        for (uri in uris) {
            val uriStr = uri.toString()
            var displayName = uri.lastPathSegment?.substringAfterLast('/') ?: "Imported Book.epub"
            try {
                appContext.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        displayName = c.getString(0) ?: displayName
                    }
                }
            } catch (_: Throwable) {}

            val ebook = buildEbookFromUri(uri, displayName)
            existing[uriStr] = ebook
        }
        val merged = existing.values.toList()
        saveEbooksCache(merged)
        merged
    }

    // Microsoft Word .docx OpenXML Parser & Legacy .doc Stream Extractor
    private fun extractDocxMetadataAndCover(
        uri: Uri,
        ebookId: String,
        fallbackTitle: String,
        fallbackAuthor: String
    ): Pair<String, String> {
        var resolvedTitle = fallbackTitle
        var resolvedAuthor = fallbackAuthor
        try {
            openUriInputStream(uri)?.use { raw ->
                ZipInputStream(raw).use { zis ->
                    var entry = zis.nextEntry
                    while (entry != null) {
                        if (!entry.isDirectory) {
                            val name = entry.name.replace('\\', '/').trimStart('/')
                            val lower = name.lowercase(Locale.ROOT)
                            if (lower == "docprops/core.xml") {
                                val bytes = readZipEntryBounded(zis, 512 * 1024)
                                if (bytes != null) {
                                    val xml = String(bytes, Charsets.UTF_8)
                                    val tMatch = Regex("<(?:dc:)?title[^>]*>(.*?)</(?:dc:)?title>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
                                        .find(xml)?.groupValues?.getOrNull(1)?.trim()
                                    if (!tMatch.isNullOrBlank()) resolvedTitle = tMatch
                                    val aMatch = Regex("<(?:dc:)?creator[^>]*>(.*?)</(?:dc:)?creator>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
                                        .find(xml)?.groupValues?.getOrNull(1)?.trim()
                                    if (!aMatch.isNullOrBlank()) resolvedAuthor = aMatch
                                }
                            } else if (lower.startsWith("word/media/") && (lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png"))) {
                                val coverFile = coverFileFor(ebookId)
                                if (!coverFile.exists()) {
                                    val imgBytes = readZipEntryBounded(zis, 4 * 1024 * 1024)
                                    if (imgBytes != null) {
                                        val bmp = decodeSampledBitmapFromStreamWithExif(ByteArrayInputStream(imgBytes), 380, 380)
                                        if (bmp != null) {
                                            saveBitmapToCache(ebookId, bmp)
                                            CoverMemoryCache.cache.put(ebookId, bmp)
                                        }
                                    }
                                }
                            }
                        }
                        zis.closeEntry()
                        entry = zis.nextEntry
                    }
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "DOCX metadata parse warning for $uri", t)
        }
        return Pair(resolvedTitle, resolvedAuthor)
    }

    private fun parseDocxChapters(uri: Uri, bookTitle: String): List<EbookChapter> {
        try {
            var docXmlBytes: ByteArray? = null
            openUriInputStream(uri)?.use { raw ->
                ZipInputStream(raw).use { zis ->
                    var entry = zis.nextEntry
                    while (entry != null) {
                        if (!entry.isDirectory && entry.name.replace('\\', '/').trimStart('/').equals("word/document.xml", ignoreCase = true)) {
                            docXmlBytes = readZipEntryBounded(zis, 16 * 1024 * 1024)
                            break
                        }
                        zis.closeEntry()
                        entry = zis.nextEntry
                    }
                }
            }
            if (docXmlBytes == null) return emptyList()

            val factory = XmlPullParserFactory.newInstance().apply { isNamespaceAware = false }
            val parser = factory.newPullParser()
            parser.setInput(StringReader(String(docXmlBytes!!, Charsets.UTF_8)))

            val fullTextBuilder = StringBuilder()
            val paraBuilder = StringBuilder()
            var inTextTag = false

            var eventType = parser.eventType
            while (eventType != XmlPullParser.END_DOCUMENT) {
                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        val local = (parser.name ?: "").substringAfterLast(':').lowercase(Locale.ROOT)
                        if (local == "t") inTextTag = true
                        else if (local == "tab") paraBuilder.append("    ")
                        else if (local == "br" || local == "cr") paraBuilder.append("\n")
                    }
                    XmlPullParser.TEXT -> {
                        if (inTextTag) {
                            paraBuilder.append(parser.text ?: "")
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        val local = (parser.name ?: "").substringAfterLast(':').lowercase(Locale.ROOT)
                        if (local == "t") {
                            inTextTag = false
                        } else if (local == "p") {
                            val para = paraBuilder.toString().trim()
                            if (para.isNotEmpty()) {
                                if (fullTextBuilder.isNotEmpty()) fullTextBuilder.append("\n\n")
                                fullTextBuilder.append(para)
                            }
                            paraBuilder.clear()
                        }
                    }
                }
                eventType = parser.next()
            }

            val fullText = fullTextBuilder.toString().trim()
            if (fullText.isNotEmpty()) {
                return splitLongBodyIntoChapters(bookTitle, fullText, 0)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Failed parsing DOCX chapters for $uri", t)
        }
        return emptyList()
    }

    // Comic Book Archive (.cbz, .cbt, .cbc, and ZIP-based .cbr) Page & Cover Engine
    private fun isImageFileName(name: String): Boolean {
        val lower = name.lowercase(Locale.ROOT)
        if (lower.contains("__macosx") || lower.substringAfterLast('/').startsWith(".")) return false
        return lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png") || lower.endsWith(".webp") || lower.endsWith(".bmp")
    }

    private fun listZipImageEntryNames(uri: Uri): List<String> {
        val names = mutableListOf<String>()
        try {
            openUriInputStream(uri)?.use { raw ->
                ZipInputStream(raw).use { zis ->
                    var entry = zis.nextEntry
                    while (entry != null) {
                        if (!entry.isDirectory && isImageFileName(entry.name)) {
                            names.add(entry.name)
                        }
                        zis.closeEntry()
                        entry = zis.nextEntry
                    }
                }
            }
        } catch (_: Throwable) {}
        return names.sortedWith { a, b -> compareNatural(a, b) }
    }

    private fun readZipEntryByName(uri: Uri, targetName: String, maxBytes: Int = 16 * 1024 * 1024): ByteArray? {
        try {
            openUriInputStream(uri)?.use { raw ->
                ZipInputStream(raw).use { zis ->
                    var entry = zis.nextEntry
                    while (entry != null) {
                        if (!entry.isDirectory && entry.name == targetName) {
                            return readZipEntryBounded(zis, maxBytes)
                        }
                        zis.closeEntry()
                        entry = zis.nextEntry
                    }
                }
            }
        } catch (_: Throwable) {}
        return null
    }

    private fun listTarImageEntryNames(uri: Uri): List<String> {
        val names = mutableListOf<String>()
        try {
            openUriInputStream(uri)?.use { stream ->
                val header = ByteArray(512)
                while (true) {
                    val read = readFully(stream, header)
                    if (read < 512 || header.all { it == 0.toByte() }) break
                    val name = extractNullTerminatedString(header, 0, 100)
                    val sizeOctal = extractNullTerminatedString(header, 124, 12).trim()
                    val size = sizeOctal.toLongOrNull(8) ?: 0L
                    val typeFlag = header[156].toInt().toChar()
                    if ((typeFlag == '0' || typeFlag == '\u0000') && size > 0L && isImageFileName(name)) {
                        names.add(name)
                    }
                    val paddedSkip = ((size + 511L) / 512L) * 512L
                    skipFully(stream, paddedSkip)
                }
            }
        } catch (_: Throwable) {}
        return names.sortedWith { a, b -> compareNatural(a, b) }
    }

    private fun readTarEntryByName(uri: Uri, targetName: String, maxBytes: Int = 16 * 1024 * 1024): ByteArray? {
        try {
            openUriInputStream(uri)?.use { stream ->
                val header = ByteArray(512)
                while (true) {
                    val read = readFully(stream, header)
                    if (read < 512 || header.all { it == 0.toByte() }) break
                    val name = extractNullTerminatedString(header, 0, 100)
                    val sizeOctal = extractNullTerminatedString(header, 124, 12).trim()
                    val size = sizeOctal.toLongOrNull(8) ?: 0L
                    if (name == targetName && size in 1L..maxBytes.toLong()) {
                        val data = ByteArray(size.toInt())
                        readFully(stream, data)
                        return data
                    }
                    val paddedSkip = ((size + 511L) / 512L) * 512L
                    skipFully(stream, paddedSkip)
                }
            }
        } catch (_: Throwable) {}
        return null
    }

    private fun readFully(stream: InputStream, buffer: ByteArray): Int {
        var offset = 0
        while (offset < buffer.size) {
            val r = stream.read(buffer, offset, buffer.size - offset)
            if (r <= 0) break
            offset += r
        }
        return offset
    }

    private fun skipFully(stream: InputStream, bytesToSkip: Long) {
        var remaining = bytesToSkip
        val scratch = ByteArray(4096)
        while (remaining > 0L) {
            val skipped = stream.skip(remaining)
            if (skipped > 0L) {
                remaining -= skipped
            } else {
                val r = stream.read(scratch, 0, remaining.coerceAtMost(scratch.size.toLong()).toInt())
                if (r <= 0) break
                remaining -= r
            }
        }
    }

    private fun extractNullTerminatedString(buf: ByteArray, offset: Int, maxLen: Int): String {
        var end = offset
        val limit = (offset + maxLen).coerceAtMost(buf.size)
        while (end < limit && buf[end] != 0.toByte()) end++
        return String(buf, offset, end - offset, Charsets.UTF_8)
    }

    private fun extractComicCoverAndPageCount(uri: Uri, ebookId: String, ext: String) {
        val coverFile = coverFileFor(ebookId)
        try {
            val pageNames = if (ext == "cbt") listTarImageEntryNames(uri) else listZipImageEntryNames(uri)
            if (pageNames.isNotEmpty()) {
                val savedPage = getEbookChapterIndex(ebookId).coerceIn(0, pageNames.lastIndex)
                saveEbookProgress(ebookId, savedPage, pageNames.size)
                if (!coverFile.exists()) {
                    val firstBytes = if (ext == "cbt") {
                        readTarEntryByName(uri, pageNames.first())
                    } else {
                        readZipEntryByName(uri, pageNames.first())
                    }
                    if (firstBytes != null) {
                        val bmp = decodeSampledBitmapFromStreamWithExif(ByteArrayInputStream(firstBytes), 380, 380)
                        if (bmp != null) {
                            saveBitmapToCache(ebookId, bmp)
                            CoverMemoryCache.cache.put(ebookId, bmp)
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Comic cover extraction warning for $uri", t)
        }
    }

    // Native PDF & Kindle Print Replica (.azw4) Extractor + Page Renderer
    private fun getOrExtractAzw4PdfFile(uri: Uri, ebookId: String): File? {
        val cachedPdf = File(appContext.cacheDir, "azw4_${sha256Hex(ebookId)}.pdf")
        if (cachedPdf.exists() && cachedPdf.length() > 128L) return cachedPdf

        val rawBytes = readUriBytesBounded(uri, 64 * 1024 * 1024) ?: return null
        // Locate "%PDF-" magic bytes (0x25, 0x50, 0x44, 0x46, 0x2D)
        var pdfStart = -1
        for (i in 0..(rawBytes.size - 5)) {
            if (rawBytes[i] == 0x25.toByte() &&
                rawBytes[i + 1] == 0x50.toByte() &&
                rawBytes[i + 2] == 0x44.toByte() &&
                rawBytes[i + 3] == 0x46.toByte() &&
                rawBytes[i + 4] == 0x2D.toByte()
            ) {
                pdfStart = i
                break
            }
        }
        if (pdfStart == -1) return null

        return try {
            FileOutputStream(cachedPdf).use { fos ->
                fos.write(rawBytes, pdfStart, rawBytes.size - pdfStart)
            }
            cachedPdf
        } catch (_: Throwable) {
            null
        }
    }

    private fun extractPdfOrAzw4CoverAndPageCount(uri: Uri, ebookId: String, isAzw4: Boolean) {
        val coverFile = coverFileFor(ebookId)
        try {
            val pfd = if (isAzw4) {
                val extracted = getOrExtractAzw4PdfFile(uri, ebookId) ?: return
                ParcelFileDescriptor.open(extracted, ParcelFileDescriptor.MODE_READ_ONLY)
            } else {
                openUriParcelFileDescriptor(uri) ?: return
            }

            pfd.use { descriptor ->
                PdfRenderer(descriptor).use { renderer ->
                    val count = renderer.pageCount
                    if (count > 0) {
                        val savedChap = getEbookChapterIndex(ebookId).coerceIn(0, count - 1)
                        saveEbookProgress(ebookId, savedChap, count)
                        if (!coverFile.exists()) {
                            renderer.openPage(0).use { page ->
                                val width = 360
                                val height = ((width.toFloat() / page.width.coerceAtLeast(1)) * page.height).toInt().coerceIn(360, 640)
                                val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                                val canvas = Canvas(bmp)
                                canvas.drawColor(AndroidColor.WHITE)
                                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                saveBitmapToCache(ebookId, bmp)
                                CoverMemoryCache.cache.put(ebookId, bmp)
                            }
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Failed extracting PDF/AZW4 cover/page count for $uri", t)
        }
    }

    suspend fun renderPdfPageBitmap(ebook: Ebook, pageIndex: Int, targetWidthPx: Int = 1200): Bitmap? = withContext(Dispatchers.IO) {
        val fmt = ebook.format.uppercase(Locale.ROOT)
        try {
            if (fmt in setOf("CBZ", "CBC", "CBR", "CBT")) {
                val isTar = fmt == "CBT"
                val names = if (isTar) listTarImageEntryNames(ebook.uri) else listZipImageEntryNames(ebook.uri)
                if (names.isEmpty()) return@withContext null
                val safeIdx = pageIndex.coerceIn(0, names.lastIndex)
                val bytes = if (isTar) {
                    readTarEntryByName(ebook.uri, names[safeIdx])
                } else {
                    readZipEntryByName(ebook.uri, names[safeIdx])
                } ?: return@withContext null
                return@withContext decodeSampledBitmapFromStreamWithExif(
                    ByteArrayInputStream(bytes),
                    targetWidthPx.coerceIn(600, 1800),
                    targetWidthPx.coerceIn(800, 2200)
                )
            }

            val pfd = if (fmt == "AZW4") {
                val extracted = getOrExtractAzw4PdfFile(ebook.uri, ebook.id) ?: return@withContext null
                ParcelFileDescriptor.open(extracted, ParcelFileDescriptor.MODE_READ_ONLY)
            } else {
                openUriParcelFileDescriptor(ebook.uri) ?: return@withContext null
            }

            pfd.use { descriptor ->
                PdfRenderer(descriptor).use { renderer ->
                    if (renderer.pageCount <= 0) return@withContext null
                    val safeIdx = pageIndex.coerceIn(0, renderer.pageCount - 1)
                    renderer.openPage(safeIdx).use { page ->
                        val w = targetWidthPx.coerceIn(600, 2000)
                        val h = ((w.toFloat() / page.width.coerceAtLeast(1)) * page.height).toInt().coerceAtLeast(600)
                        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                        val canvas = Canvas(bmp)
                        canvas.drawColor(AndroidColor.WHITE)
                        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        return@withContext bmp
                    }
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Failed rendering visual page $pageIndex for ${ebook.title}", t)
            null
        }
    }

    // Pure-Kotlin Psion .tcr Dictionary Decompressor & Legacy Binary Text Stream Extractor
    private fun parseTcrChapters(uri: Uri, bookTitle: String): List<EbookChapter> {
        val bytes = readUriBytesBounded(uri, 16 * 1024 * 1024) ?: return emptyList()
        if (bytes.size < 12) return emptyList()
        val magic = String(bytes, 0, 9, Charsets.ISO_8859_1)
        if (magic != "!!8-Bit!!") return emptyList()

        val dict = Array(256) { "" }
        var pos = 9
        for (i in 0 until 256) {
            if (pos >= bytes.size) break
            val len = bytes[pos].toInt() and 0xFF
            pos++
            if (pos + len > bytes.size) break
            dict[i] = String(bytes, pos, len, Charsets.ISO_8859_1)
            pos += len
        }

        val sb = StringBuilder((bytes.size - pos) * 3)
        while (pos < bytes.size) {
            val code = bytes[pos].toInt() and 0xFF
            sb.append(dict[code])
            pos++
        }
        return splitLongBodyIntoChapters(bookTitle, sb.toString().replace("\r\n", "\n").trim(), 0)
    }

    private fun extractReadableTextFromBinary(uri: Uri, format: String, bookTitle: String): List<EbookChapter> {
        val bytes = readUriBytesBounded(uri, 24 * 1024 * 1024) ?: return emptyList()
        if (bytes.isEmpty()) return emptyList()

        // Check if the file is actually a ZIP container (common with .ibooks, .htmlz, or renamed .epub)
        if (bytes.size >= 4 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte()) {
            return emptyList()
        }

        // Check for HTML blocks embedded inside .lit, .rb, .chm, or .lrf
        val rawIso = String(bytes, Charsets.ISO_8859_1)
        val htmlMatch = Regex("<html\\b[^>]*>.*?</html>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .findAll(rawIso)
            .map { it.value }
            .toList()
        if (htmlMatch.isNotEmpty()) {
            val chapters = mutableListOf<EbookChapter>()
            var num = 1
            for (h in htmlMatch) {
                val (t, b) = parseEpubHtmlToReadableText(h, num)
                if (b.length >= 60) {
                    chapters.addAll(splitLongBodyIntoChapters(t, b, chapters.size))
                    num++
                }
            }
            if (chapters.isNotEmpty()) return chapters
        }

        // Carve continuous printable UTF-8 / ASCII runs and UTF-16LE runs (for legacy MS Word .doc & binary formats)
        val extractedRuns = mutableListOf<String>()
        val asciiRun = StringBuilder()
        for (b in bytes) {
            val c = b.toInt() and 0xFF
            if (c == 0x0A || c == 0x0D || c == 0x09 || c in 0x20..0x7E) {
                asciiRun.append(c.toChar())
            } else {
                if (asciiRun.length >= 120) {
                    extractedRuns.add(asciiRun.toString().trim())
                }
                asciiRun.clear()
            }
        }
        if (asciiRun.length >= 120) extractedRuns.add(asciiRun.toString().trim())

        // Also check UTF-16LE runs (used in Microsoft Word 97-2003 .doc files)
        val utf16Run = StringBuilder()
        var j = 0
        while (j + 1 < bytes.size) {
            val lo = bytes[j].toInt() and 0xFF
            val hi = bytes[j + 1].toInt() and 0xFF
            if (hi == 0 && (lo == 0x0A || lo == 0x0D || lo == 0x09 || lo in 0x20..0x7E)) {
                utf16Run.append(lo.toChar())
            } else {
                if (utf16Run.length >= 120) {
                    extractedRuns.add(utf16Run.toString().trim())
                }
                utf16Run.clear()
            }
            j += 2
        }
        if (utf16Run.length >= 120) extractedRuns.add(utf16Run.toString().trim())

        val filteredRuns = extractedRuns.filter { run ->
            val letterCount = run.count { it.isLetter() }
            val spaceCount = run.count { it == ' ' }
            letterCount > run.length / 2 && spaceCount >= 8 &&
                !run.contains("Root Entry", ignoreCase = true) &&
                !run.contains("Microsoft Word", ignoreCase = true)
        }

        val combined = filteredRuns.joinToString("\n\n")
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()

        if (combined.length >= 200) {
            return splitLongBodyIntoChapters(bookTitle, combined, 0)
        }

        val explanation = when (format) {
            "KFX", "LRX", "TPZ", "TOPAZ" ->
                "This .$format file uses proprietary DRM or glyph encryption. Remove DRM or convert to .epub / .azw3 / .pdf in Calibre to read all chapters."
            "CBR", "CB7", "CBA", "DJVU" ->
                "This .$format archive uses proprietary RAR/7z/DjVu compression. Convert or repackage as .cbz, .pdf, or .epub for native page rendering."
            else ->
                "Could not extract readable text streams from this .$format file."
        }
        return listOf(EbookChapter(index = 0, title = bookTitle, bodyText = explanation))
    }

    // Pure-Kotlin Kindle (.azw3, .azw, .mobi, .prc, .kf8, .pdb) PalmDOC / MOBI / EXTH Parser
    private data class MobiHeaderSection(
        val recordIndex: Int,
        val compression: Int,
        val textRecordCount: Int,
        val encryptionType: Int,
        val charset: Charset,
        val fullName: String,
        val exthAuthor: String,
        val exthTitle: String,
        val firstImageRecord: Int,
        val coverRecordOffset: Int,
        val extraDataFlags: Int
    )

    private fun readUInt16BE(bytes: ByteArray, offset: Int): Int {
        if (offset < 0 || offset + 2 > bytes.size) return 0
        return ((bytes[offset].toInt() and 0xFF) shl 8) or (bytes[offset + 1].toInt() and 0xFF)
    }

    private fun readUInt32BE(bytes: ByteArray, offset: Int): Long {
        if (offset < 0 || offset + 4 > bytes.size) return 0L
        return ((bytes[offset].toLong() and 0xFF) shl 24) or
            ((bytes[offset + 1].toLong() and 0xFF) shl 16) or
            ((bytes[offset + 2].toLong() and 0xFF) shl 8) or
            (bytes[offset + 3].toLong() and 0xFF)
    }

    private fun splitPdbRecords(fileBytes: ByteArray): List<ByteArray> {
        if (fileBytes.size < 86) return emptyList()
        val numRecords = readUInt16BE(fileBytes, 76)
        if (numRecords <= 0 || 78 + (numRecords * 8) > fileBytes.size) return emptyList()

        val offsets = IntArray(numRecords)
        for (i in 0 until numRecords) {
            offsets[i] = readUInt32BE(fileBytes, 78 + (i * 8)).toInt()
        }

        val records = ArrayList<ByteArray>(numRecords)
        for (i in 0 until numRecords) {
            val start = offsets[i]
            val end = if (i + 1 < numRecords) offsets[i + 1] else fileBytes.size
            if (start in 0 until end && end <= fileBytes.size) {
                records.add(fileBytes.copyOfRange(start, end))
            } else {
                records.add(ByteArray(0))
            }
        }
        return records
    }

    private fun parseMobiHeaderRecord(recordIndex: Int, rec: ByteArray): MobiHeaderSection? {
        if (rec.size < 16) return null
        val compression = readUInt16BE(rec, 0)
        val textRecordCount = readUInt16BE(rec, 8)
        val encryptionType = readUInt16BE(rec, 12)

        val isMobi = rec.size >= 36 && String(rec, 16, 4, Charsets.ISO_8859_1) == "MOBI"
        if (!isMobi) {
            // Plain PalmDOC (.pdb) header without MOBI extension
            if (compression in setOf(1, 2) && textRecordCount in 1..10000) {
                return MobiHeaderSection(
                    recordIndex = recordIndex,
                    compression = compression,
                    textRecordCount = textRecordCount,
                    encryptionType = encryptionType,
                    charset = Charsets.UTF_8,
                    fullName = "",
                    exthAuthor = "",
                    exthTitle = "",
                    firstImageRecord = -1,
                    coverRecordOffset = -1,
                    extraDataFlags = 0
                )
            }
            return null
        }

        val mobiHeaderLen = readUInt32BE(rec, 20).toInt()
        val encodingCode = readUInt32BE(rec, 28).toInt()
        val charset = if (encodingCode == 1252) Charset.forName("windows-1252") else Charsets.UTF_8

        val fullNameOffset = if (rec.size >= 92) readUInt32BE(rec, 84).toInt() else 0
        val fullNameLength = if (rec.size >= 92) readUInt32BE(rec, 88).toInt() else 0
        val fullName = if (fullNameOffset > 0 && fullNameLength in 1..512 && fullNameOffset + fullNameLength <= rec.size) {
            String(rec, fullNameOffset, fullNameLength, charset).trim()
        } else ""

        val firstImageRecord = if (rec.size >= 112) readUInt32BE(rec, 108).toInt() else -1
        val exthFlags = if (rec.size >= 132) readUInt32BE(rec, 128).toInt() else 0
        val extraDataFlags = if (mobiHeaderLen >= 228 && rec.size >= 244) readUInt16BE(rec, 242) else 0

        var exthAuthor = ""
        var exthTitle = ""
        var coverRecordOffset = -1

        val exthStart = 16 + mobiHeaderLen
        if ((exthFlags and 0x40) != 0 && exthStart + 12 <= rec.size) {
            val exthMagic = String(rec, exthStart, 4, Charsets.ISO_8859_1)
            if (exthMagic == "EXTH") {
                val exthCount = readUInt32BE(rec, exthStart + 8).toInt()
                var pos = exthStart + 12
                for (i in 0 until exthCount) {
                    if (pos + 8 > rec.size) break
                    val rType = readUInt32BE(rec, pos).toInt()
                    val rLen = readUInt32BE(rec, pos + 4).toInt()
                    if (rLen < 8 || pos + rLen > rec.size) break
                    val dataLen = rLen - 8
                    val dataStart = pos + 8
                    when (rType) {
                        100 -> if (exthAuthor.isEmpty() && dataLen > 0) {
                            exthAuthor = String(rec, dataStart, dataLen, charset).trim()
                        }
                        503 -> if (exthTitle.isEmpty() && dataLen > 0) {
                            exthTitle = String(rec, dataStart, dataLen, charset).trim()
                        }
                        201 -> if (dataLen == 4) {
                            coverRecordOffset = readUInt32BE(rec, dataStart).toInt()
                        }
                        202 -> if (coverRecordOffset == -1 && dataLen == 4) {
                            coverRecordOffset = readUInt32BE(rec, dataStart).toInt()
                        }
                    }
                    pos += rLen
                }
            }
        }

        return MobiHeaderSection(
            recordIndex = recordIndex,
            compression = compression,
            textRecordCount = textRecordCount,
            encryptionType = encryptionType,
            charset = charset,
            fullName = fullName,
            exthAuthor = exthAuthor,
            exthTitle = exthTitle,
            firstImageRecord = firstImageRecord,
            coverRecordOffset = coverRecordOffset,
            extraDataFlags = extraDataFlags
        )
    }

    private fun trimMobiRecordTrailingBytes(record: ByteArray, extraDataFlags: Int): ByteArray {
        if (extraDataFlags == 0 || record.isEmpty()) return record
        var size = record.size

        var flags = extraDataFlags ushr 1
        while (flags != 0) {
            if ((flags and 1) != 0 && size > 0) {
                var num = 0
                var shift = 0
                var pos = size - 1
                while (pos >= 0 && shift < 28) {
                    val b = record[pos].toInt() and 0xFF
                    num = num or ((b and 0x7F) shl shift)
                    pos--
                    shift += 7
                    if ((b and 0x80) != 0) break
                }
                if (num in 1..size) {
                    size -= num
                }
            }
            flags = flags ushr 1
        }

        if ((extraDataFlags and 1) != 0 && size > 0) {
            val trail = (record[size - 1].toInt() and 0x03) + 1
            if (trail in 1..size) {
                size -= trail
            }
        }
        return if (size == record.size) record else record.copyOfRange(0, size)
    }

    private fun decompressPalmDocLz77(input: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(input.size * 2)
        val buffer = ByteArray(input.size * 4 + 8192)
        var outLen = 0
        var i = 0
        val n = input.size

        while (i < n) {
            val c = input[i].toInt() and 0xFF
            i++
            when {
                c == 0x00 || c in 0x09..0x7F -> {
                    if (outLen < buffer.size) buffer[outLen++] = c.toByte()
                }
                c in 0x01..0x08 -> {
                    val count = c.coerceAtMost(n - i)
                    for (k in 0 until count) {
                        if (outLen < buffer.size) buffer[outLen++] = input[i + k]
                    }
                    i += count
                }
                c in 0x80..0xBF -> {
                    if (i >= n) break
                    val c2 = input[i].toInt() and 0xFF
                    i++
                    val pair = ((c shl 8) or c2) and 0x3FFF
                    val dist = pair ushr 3
                    val len = (pair and 0x07) + 3
                    if (dist in 1..outLen) {
                        for (k in 0 until len) {
                            if (outLen < buffer.size) {
                                buffer[outLen] = buffer[outLen - dist]
                                outLen++
                            }
                        }
                    }
                }
                else -> {
                    if (outLen + 1 < buffer.size) {
                        buffer[outLen++] = 0x20.toByte()
                        buffer[outLen++] = (c xor 0x80).toByte()
                    }
                }
            }
        }
        out.write(buffer, 0, outLen)
        return out.toByteArray()
    }

    private fun extractKindleMetadataAndCover(
        uri: Uri,
        ebookId: String,
        fallbackTitle: String,
        fallbackAuthor: String
    ): Pair<String, String> {
        var resolvedTitle = fallbackTitle
        var resolvedAuthor = fallbackAuthor

        try {
            val fileBytes = readUriBytesBounded(uri, 32 * 1024 * 1024) ?: return Pair(resolvedTitle, resolvedAuthor)
            if (fileBytes.size >= 4 && fileBytes[0] == 0x50.toByte() && fileBytes[1] == 0x4B.toByte()) {
                return extractEpubMetadataAndCover(uri, ebookId, fallbackTitle, fallbackAuthor)
            }

            val records = splitPdbRecords(fileBytes)
            val headers = records.mapIndexedNotNull { idx, bytes -> parseMobiHeaderRecord(idx, bytes) }
            for (h in headers) {
                val candidateTitle = h.exthTitle.ifBlank { h.fullName }
                if (candidateTitle.isNotBlank() && !isGenericTitle(candidateTitle)) {
                    resolvedTitle = candidateTitle
                }
                if (h.exthAuthor.isNotBlank() && !h.exthAuthor.equals("unknown", ignoreCase = true)) {
                    resolvedAuthor = h.exthAuthor
                }
            }

            val coverFile = coverFileFor(ebookId)
            if (!coverFile.exists() && records.isNotEmpty()) {
                var coverBytes: ByteArray? = null
                val primaryHeader = headers.firstOrNull()
                if (primaryHeader != null && primaryHeader.firstImageRecord > 0 && primaryHeader.coverRecordOffset >= 0) {
                    val targetIdx = primaryHeader.firstImageRecord + primaryHeader.coverRecordOffset
                    if (targetIdx in records.indices && isImageRecord(records[targetIdx])) {
                        coverBytes = records[targetIdx]
                    }
                }
                if (coverBytes == null) {
                    coverBytes = records.firstOrNull { isImageRecord(it) && it.size >= 2048 }
                }
                if (coverBytes != null) {
                    val bmp = decodeSampledBitmapFromStreamWithExif(ByteArrayInputStream(coverBytes), 380, 380)
                    if (bmp != null) {
                        saveBitmapToCache(ebookId, bmp)
                        CoverMemoryCache.cache.put(ebookId, bmp)
                    }
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Failed extracting Kindle metadata/cover for $uri", t)
        }

        return Pair(resolvedTitle, resolvedAuthor)
    }

    private fun isImageRecord(bytes: ByteArray): Boolean {
        if (bytes.size < 12) return false
        val isJpeg = bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte()
        val isPng = bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() && bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte()
        return isJpeg || isPng
    }

    private fun parseKindleChapters(uri: Uri, bookTitle: String): List<EbookChapter> {
        val fileBytes = readUriBytesBounded(uri, 48 * 1024 * 1024) ?: return emptyList()
        if (fileBytes.size >= 4 && fileBytes[0] == 0x50.toByte() && fileBytes[1] == 0x4B.toByte()) {
            return emptyList()
        }

        val records = splitPdbRecords(fileBytes)
        if (records.isEmpty()) return emptyList()

        val headers = records.mapIndexedNotNull { idx, rec -> parseMobiHeaderRecord(idx, rec) }
        if (headers.isEmpty()) return emptyList()

        var bestHtmlText = ""
        for (header in headers) {
            if (header.encryptionType != 0) continue
            if (header.compression != 1 && header.compression != 2) continue
            if (header.textRecordCount <= 0) continue

            val combinedBytes = ByteArrayOutputStream()
            val startRec = header.recordIndex + 1
            val endRec = (startRec + header.textRecordCount - 1).coerceAtMost(records.lastIndex)

            for (rIdx in startRec..endRec) {
                val rawRec = records[rIdx]
                if (rawRec.isEmpty()) continue
                val trimmed = trimMobiRecordTrailingBytes(rawRec, header.extraDataFlags)
                val decompressed = if (header.compression == 2) {
                    decompressPalmDocLz77(trimmed)
                } else {
                    trimmed
                }
                combinedBytes.write(decompressed)
            }

            val candidateHtml = String(combinedBytes.toByteArray(), header.charset)
            if (candidateHtml.length > bestHtmlText.length) {
                bestHtmlText = candidateHtml
            }
        }

        if (bestHtmlText.isBlank()) return emptyList()

        val rawSegments = bestHtmlText.split(
            Regex("(?i)(?=<mbp:pagebreak\\b|<h[12]\\b|<html\\b|<div[^>]*class=[\"'][^\"']*chapter)")
        )

        val chapters = mutableListOf<EbookChapter>()
        var chapNum = 1
        for (segment in rawSegments) {
            if (segment.length < 40) continue
            val (title, body) = parseEpubHtmlToReadableText(segment, chapNum)
            if (body.length >= 45) {
                val subChunks = splitLongBodyIntoChapters(title, body, chapters.size)
                chapters.addAll(subChunks)
                chapNum += subChunks.size
            }
        }

        if (chapters.isEmpty()) {
            val (title, body) = parseEpubHtmlToReadableText(bestHtmlText, 1)
            if (body.isNotBlank()) {
                chapters.addAll(splitLongBodyIntoChapters(title.ifBlank { bookTitle }, body, 0))
            }
        }

        return chapters
    }

    // Pure-Kotlin FictionBook (.fb2, .fbz, .fb2.zip) XML & Plain Text Parsers
    private fun readFb2XmlString(uri: Uri, isZipped: Boolean): String? {
        return try {
            if (isZipped) {
                openUriInputStream(uri)?.use { raw ->
                    ZipInputStream(raw).use { zis ->
                        var entry = zis.nextEntry
                        while (entry != null) {
                            if (!entry.isDirectory && entry.name.lowercase(Locale.ROOT).endsWith(".fb2")) {
                                val bytes = readZipEntryBounded(zis, 24 * 1024 * 1024)
                                if (bytes != null) return String(bytes, Charsets.UTF_8)
                            }
                            zis.closeEntry()
                            entry = zis.nextEntry
                        }
                    }
                }
                null
            } else {
                val bytes = readUriBytesBounded(uri, 24 * 1024 * 1024) ?: return null
                // Auto-detect if .fb2 file is actually zipped
                if (bytes.size >= 4 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte()) {
                    ZipInputStream(ByteArrayInputStream(bytes)).use { zis ->
                        val entry = zis.nextEntry
                        if (entry != null) {
                            val unzipped = readZipEntryBounded(zis, 24 * 1024 * 1024)
                            if (unzipped != null) return String(unzipped, Charsets.UTF_8)
                        }
                    }
                }
                String(bytes, Charsets.UTF_8)
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun extractFb2MetadataAndCover(
        uri: Uri,
        ebookId: String,
        fallbackTitle: String,
        fallbackAuthor: String,
        isZipped: Boolean = false
    ): Pair<String, String> {
        var resolvedTitle = fallbackTitle
        var resolvedAuthor = fallbackAuthor
        try {
            val xml = readFb2XmlString(uri, isZipped) ?: return Pair(resolvedTitle, resolvedAuthor)

            val titleMatch = Regex("<book-title[^>]*>(.*?)</book-title>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
                .find(xml)?.groupValues?.getOrNull(1)?.trim()
            if (!titleMatch.isNullOrBlank()) {
                resolvedTitle = HtmlCompat.fromHtml(titleMatch, HtmlCompat.FROM_HTML_MODE_LEGACY).toString().trim()
            }

            val firstName = Regex("<first-name[^>]*>(.*?)</first-name>", RegexOption.IGNORE_CASE).find(xml)?.groupValues?.getOrNull(1)?.trim() ?: ""
            val lastName = Regex("<last-name[^>]*>(.*?)</last-name>", RegexOption.IGNORE_CASE).find(xml)?.groupValues?.getOrNull(1)?.trim() ?: ""
            val combinedAuthor = "$firstName $lastName".trim()
            if (combinedAuthor.isNotBlank()) {
                resolvedAuthor = combinedAuthor
            }

            val coverFile = coverFileFor(ebookId)
            if (!coverFile.exists()) {
                val binaryMatch = Regex("<binary\\b[^>]*>(.*?)</binary>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
                    .find(xml)?.groupValues?.getOrNull(1)
                if (!binaryMatch.isNullOrBlank()) {
                    val decodedBytes = Base64.decode(binaryMatch.replace(Regex("\\s+"), ""), Base64.DEFAULT)
                    val bmp = decodeSampledBitmapFromStreamWithExif(ByteArrayInputStream(decodedBytes), 380, 380)
                    if (bmp != null) {
                        saveBitmapToCache(ebookId, bmp)
                        CoverMemoryCache.cache.put(ebookId, bmp)
                    }
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Failed extracting FB2 metadata for $uri", t)
        }
        return Pair(resolvedTitle, resolvedAuthor)
    }

    private fun parseFb2Chapters(uri: Uri, bookTitle: String, isZipped: Boolean = false): List<EbookChapter> {
        val rawXml = readFb2XmlString(uri, isZipped) ?: return emptyList()
        val xml = rawXml.replace(Regex("<binary\\b[^>]*>.*?</binary>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), "")

        val sections = Regex("<section\\b[^>]*>(.*?)</section>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .findAll(xml)
            .map { it.groupValues[1] }
            .toList()

        val chapters = mutableListOf<EbookChapter>()
        if (sections.isNotEmpty()) {
            var num = 1
            for (sec in sections) {
                val titleHtml = Regex("<title[^>]*>(.*?)</title>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
                    .find(sec)?.groupValues?.getOrNull(1) ?: "Chapter $num"
                val bodyWithoutTitle = sec.replaceFirst(Regex("<title[^>]*>.*?</title>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), "")
                val cleanTitle = HtmlCompat.fromHtml(titleHtml, HtmlCompat.FROM_HTML_MODE_LEGACY).toString().replace(Regex("\\s+"), " ").trim()
                val (_, bodyText) = parseEpubHtmlToReadableText(bodyWithoutTitle, num)
                if (bodyText.length >= 40) {
                    chapters.addAll(splitLongBodyIntoChapters(cleanTitle.ifBlank { "Chapter $num" }, bodyText, chapters.size))
                    num++
                }
            }
        }

        if (chapters.isEmpty()) {
            val (t, b) = parseEpubHtmlToReadableText(xml, 1)
            if (b.isNotBlank()) {
                chapters.addAll(splitLongBodyIntoChapters(t.ifBlank { bookTitle }, b, 0))
            }
        }
        return chapters
    }

    private fun parsePlainTextOrHtmlChapters(uri: Uri, format: String, bookTitle: String): List<EbookChapter> {
        val bytes = readUriBytesBounded(uri, 24 * 1024 * 1024) ?: return emptyList()
        val rawStr = String(bytes, Charsets.UTF_8)

        if (format in setOf("HTML", "HTM", "LRS") || rawStr.contains("<body", ignoreCase = true) || rawStr.contains("<bbeb", ignoreCase = true)) {
            val (t, b) = parseEpubHtmlToReadableText(rawStr, 1)
            return splitLongBodyIntoChapters(t.ifBlank { bookTitle }, b, 0)
        }

        val cleanedText = if (format == "RTF" || rawStr.startsWith("{\\rtf")) {
            rawStr
                .replace(Regex("\\\\par\\b"), "\n\n")
                .replace(Regex("\\\\line\\b"), "\n")
                .replace(Regex("\\\\'[0-9a-fA-F]{2}"), "")
                .replace(Regex("\\\\[a-zA-Z]+\\d*\\s?"), "")
                .replace(Regex("[{}]"), "")
                .trim()
        } else {
            rawStr.replace("\r\n", "\n")
        }

        val chapterRegex = Regex("(?m)^\\s*(?:chapter|part|book|prologue|epilogue)\\s+[0-9ivxlcdmIVXLCDM]+.*$", RegexOption.IGNORE_CASE)
        val matches = chapterRegex.findAll(cleanedText).toList()

        if (matches.size >= 2) {
            val chapters = mutableListOf<EbookChapter>()
            for (i in matches.indices) {
                val startIdx = matches[i].range.first
                val endIdx = if (i + 1 < matches.size) matches[i + 1].range.first else cleanedText.length
                val heading = matches[i].value.trim().lines().firstOrNull()?.trim() ?: "Chapter ${i + 1}"
                val sectionBody = cleanedText.substring(startIdx, endIdx).removePrefix(matches[i].value).trim()
                if (sectionBody.isNotBlank()) {
                    chapters.addAll(splitLongBodyIntoChapters(heading, sectionBody, chapters.size))
                }
            }
            if (chapters.isNotEmpty()) return chapters
        }

        return splitLongBodyIntoChapters(bookTitle, cleanedText, 0)
    }

    private fun splitLongBodyIntoChapters(baseTitle: String, bodyText: String, startIndex: Int): List<EbookChapter> {
        val maxChars = 22_000
        if (bodyText.length <= maxChars) {
            return listOf(EbookChapter(index = startIndex, title = baseTitle, bodyText = bodyText))
        }

        val result = mutableListOf<EbookChapter>()
        val paragraphs = bodyText.split("\n\n")
        val currentBuf = StringBuilder()
        var partNum = 1

        for (para in paragraphs) {
            if (currentBuf.length + para.length > maxChars && currentBuf.isNotEmpty()) {
                val partTitle = if (partNum == 1) baseTitle else "$baseTitle (Part $partNum)"
                result.add(
                    EbookChapter(
                        index = startIndex + result.size,
                        title = partTitle,
                        bodyText = currentBuf.toString().trim()
                    )
                )
                currentBuf.clear()
                partNum++
            }
            if (currentBuf.isNotEmpty()) currentBuf.append("\n\n")
            currentBuf.append(para)
        }

        if (currentBuf.isNotEmpty()) {
            val partTitle = if (partNum == 1) baseTitle else "$baseTitle (Part $partNum)"
            result.add(
                EbookChapter(
                    index = startIndex + result.size,
                    title = partTitle,
                    bodyText = currentBuf.toString().trim()
                )
            )
        }
        return result
    }

    // Namespace-Aware XmlPullParser EPUB / iBooks / HTMLZ Metadata, Cover & Chapter Extractor
    private fun extractEpubMetadataAndCover(
        uri: Uri,
        ebookId: String,
        fallbackTitle: String,
        fallbackAuthor: String = "Unknown Author"
    ): Pair<String, String> {
        var resolvedTitle = fallbackTitle
        var resolvedAuthor = fallbackAuthor

        try {
            val entries = linkedMapOf<String, ByteArray>()
            openUriInputStream(uri)?.use { rawStream ->
                ZipInputStream(rawStream).use { zis ->
                    var entry = zis.nextEntry
                    while (entry != null) {
                        if (!entry.isDirectory) {
                            val name = entry.name.replace('\\', '/').trimStart('/')
                            val lower = name.lowercase(Locale.ROOT)
                            if (lower.endsWith(".opf") ||
                                lower == "meta-inf/container.xml" ||
                                lower.endsWith(".jpg") ||
                                lower.endsWith(".jpeg") ||
                                lower.endsWith(".png") ||
                                lower.endsWith(".webp")
                            ) {
                                val bytes = readZipEntryBounded(zis, 6 * 1024 * 1024)
                                if (bytes != null) {
                                    entries[name] = bytes
                                }
                            }
                        }
                        zis.closeEntry()
                        entry = zis.nextEntry
                    }
                }
            }

            val opfEntry = entries.entries.firstOrNull { it.key.endsWith(".opf", ignoreCase = true) }
            if (opfEntry != null) {
                val opfXml = String(opfEntry.value, Charsets.UTF_8)
                val opfDir = opfEntry.key.substringBeforeLast('/', "")
                val parsedMeta = parseOpfXmlWithPullParser(opfXml)

                if (parsedMeta.title.isNotBlank() && !isGenericTitle(parsedMeta.title)) {
                    resolvedTitle = parsedMeta.title
                }
                if (parsedMeta.author.isNotBlank() && !parsedMeta.author.equals("unknown", ignoreCase = true)) {
                    resolvedAuthor = parsedMeta.author
                }

                val coverFile = coverFileFor(ebookId)
                if (!coverFile.exists()) {
                    var coverBytes: ByteArray? = null
                    if (parsedMeta.coverHref != null) {
                        coverBytes = findZipEntryBytes(entries, opfDir, parsedMeta.coverHref)
                    }
                    if (coverBytes == null) {
                        coverBytes = entries.entries.firstOrNull {
                            val k = it.key.lowercase(Locale.ROOT)
                            k.contains("cover") && (k.endsWith(".jpg") || k.endsWith(".jpeg") || k.endsWith(".png") || k.endsWith(".webp"))
                        }?.value
                    }
                    if (coverBytes == null) {
                        coverBytes = entries.entries.firstOrNull {
                            val k = it.key.lowercase(Locale.ROOT)
                            k.endsWith(".jpg") || k.endsWith(".jpeg") || k.endsWith(".png")
                        }?.value
                    }

                    if (coverBytes != null) {
                        val bmp = decodeSampledBitmapFromStreamWithExif(ByteArrayInputStream(coverBytes), 380, 380)
                        if (bmp != null) {
                            saveBitmapToCache(ebookId, bmp)
                            CoverMemoryCache.cache.put(ebookId, bmp)
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Failed extracting epub metadata for $uri", t)
        }

        return Pair(resolvedTitle, resolvedAuthor)
    }

    private data class ParsedOpfData(
        val title: String,
        val author: String,
        val coverHref: String?,
        val spineHrefs: List<String>
    )

    private fun parseOpfXmlWithPullParser(opfXml: String): ParsedOpfData {
        var title = ""
        var author = ""
        var coverMetaId: String? = null
        var coverHref: String? = null
        val manifestById = linkedMapOf<String, String>()
        val manifestMediaTypeById = linkedMapOf<String, String>()
        val spineIdRefs = mutableListOf<String>()

        try {
            val factory = XmlPullParserFactory.newInstance().apply { isNamespaceAware = false }
            val parser = factory.newPullParser()
            parser.setInput(StringReader(opfXml))

            var eventType = parser.eventType
            var currentTag = ""
            while (eventType != XmlPullParser.END_DOCUMENT) {
                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        val rawName = parser.name ?: ""
                        val localName = rawName.substringAfterLast(':').lowercase(Locale.ROOT)
                        currentTag = localName

                        when (localName) {
                            "meta" -> {
                                val metaName = getXmlAttr(parser, "name")
                                val metaContent = getXmlAttr(parser, "content")
                                if (metaName.equals("cover", ignoreCase = true) && !metaContent.isNullOrBlank()) {
                                    coverMetaId = metaContent.trim()
                                }
                            }
                            "item" -> {
                                val id = getXmlAttr(parser, "id")
                                val href = getXmlAttr(parser, "href")
                                val mediaType = getXmlAttr(parser, "media-type") ?: ""
                                val properties = getXmlAttr(parser, "properties") ?: ""
                                if (!id.isNullOrBlank() && !href.isNullOrBlank()) {
                                    manifestById[id] = href
                                    manifestMediaTypeById[id] = mediaType
                                    if (properties.contains("cover-image", ignoreCase = true) ||
                                        (mediaType.startsWith("image/", true) && (id.contains("cover", true) || href.contains("cover", true)))
                                    ) {
                                        if (coverHref == null) coverHref = href
                                    }
                                }
                            }
                            "itemref" -> {
                                val idref = getXmlAttr(parser, "idref")
                                if (!idref.isNullOrBlank()) {
                                    spineIdRefs.add(idref.trim())
                                }
                            }
                        }
                    }
                    XmlPullParser.TEXT -> {
                        val text = parser.text?.trim() ?: ""
                        if (text.isNotEmpty()) {
                            if (currentTag == "title" && title.isEmpty()) {
                                title = text
                            } else if ((currentTag == "creator" || currentTag == "author") && author.isEmpty()) {
                                author = text
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        currentTag = ""
                    }
                }
                eventType = parser.next()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "XmlPullParser OPF parse warning, falling back to regex", t)
        }

        if (coverHref == null && coverMetaId != null) {
            coverHref = manifestById[coverMetaId]
        }

        val spineHrefs = mutableListOf<String>()
        for (idref in spineIdRefs) {
            val href = manifestById[idref]
            val mime = manifestMediaTypeById[idref] ?: ""
            if (href != null && !mime.startsWith("image/", ignoreCase = true) && !href.endsWith(".ncx", ignoreCase = true)) {
                spineHrefs.add(href)
            }
        }

        if (spineHrefs.isEmpty()) {
            for ((id, href) in manifestById) {
                val mime = manifestMediaTypeById[id] ?: ""
                val lowerHref = href.substringBefore('#').lowercase(Locale.ROOT)
                if (mime.contains("html", ignoreCase = true) ||
                    lowerHref.endsWith(".xhtml") ||
                    lowerHref.endsWith(".html") ||
                    lowerHref.endsWith(".htm")
                ) {
                    spineHrefs.add(href)
                }
            }
        }

        return ParsedOpfData(
            title = title,
            author = author,
            coverHref = coverHref,
            spineHrefs = spineHrefs
        )
    }

    private fun getXmlAttr(parser: XmlPullParser, attrName: String): String? {
        for (i in 0 until parser.attributeCount) {
            val name = parser.getAttributeName(i)?.substringAfterLast(':') ?: continue
            if (name.equals(attrName, ignoreCase = true)) {
                return parser.getAttributeValue(i)
            }
        }
        return null
    }

    private fun readZipEntryBounded(zis: ZipInputStream, maxBytes: Int): ByteArray? {
        return try {
            val out = ByteArrayOutputStream()
            val buf = ByteArray(8192)
            var total = 0
            while (true) {
                val r = zis.read(buf)
                if (r <= 0) break
                total += r
                if (total > maxBytes) return null
                out.write(buf, 0, r)
            }
            out.toByteArray()
        } catch (_: Throwable) {
            null
        }
    }

    private fun findZipEntryBytes(
        zipMap: Map<String, ByteArray>,
        opfDir: String,
        rawHref: String
    ): ByteArray? {
        val cleanHref = rawHref.substringBefore('#').trim()
        val decodedHref = try { URLDecoder.decode(cleanHref, "UTF-8") } catch (_: Throwable) { cleanHref }
        val resolved = resolveZipRelativePath(opfDir, decodedHref)

        zipMap[resolved]?.let { return it }
        zipMap[decodedHref.trimStart('/')]?.let { return it }

        zipMap.entries.firstOrNull {
            it.key.equals(resolved, ignoreCase = true) ||
                it.key.equals(decodedHref.trimStart('/'), ignoreCase = true)
        }?.value?.let { return it }

        val fileNameOnly = decodedHref.substringAfterLast('/')
        if (fileNameOnly.isNotBlank()) {
            zipMap.entries.firstOrNull {
                it.key.equals(fileNameOnly, ignoreCase = true) ||
                    it.key.endsWith("/$fileNameOnly", ignoreCase = true)
            }?.value?.let { return it }
        }
        return null
    }

    suspend fun getOrFetchEbookCoverArt(ebook: Ebook): Bitmap? = withContext(Dispatchers.IO) {
        CoverMemoryCache.cache.get(ebook.id)?.let { return@withContext it }

        val shaFile = coverFileFor(ebook.id)
        if (shaFile.exists()) {
            val bmp = decodeSampledBitmapFromFile(shaFile.absolutePath, 380, 380)
            if (bmp != null) {
                CoverMemoryCache.cache.put(ebook.id, bmp)
                return@withContext bmp
            }
        }

        val fmt = ebook.format.uppercase(Locale.ROOT)
        val extLower = fmt.lowercase(Locale.ROOT)
        when {
            fmt == "PDF" || fmt == "AZW4" -> extractPdfOrAzw4CoverAndPageCount(ebook.uri, ebook.id, fmt == "AZW4")
            extLower in COMIC_EXTENSIONS -> extractComicCoverAndPageCount(ebook.uri, ebook.id, extLower)
            fmt in setOf("EPUB", "IBOOKS", "HTMLZ") -> extractEpubMetadataAndCover(ebook.uri, ebook.id, ebook.title, ebook.author)
            extLower in KINDLE_EXTENSIONS -> extractKindleMetadataAndCover(ebook.uri, ebook.id, ebook.title, ebook.author)
            fmt in setOf("FB2", "FBZ", "FB2.ZIP") -> extractFb2MetadataAndCover(ebook.uri, ebook.id, ebook.title, ebook.author, fmt != "FB2")
            fmt == "DOCX" -> extractDocxMetadataAndCover(ebook.uri, ebook.id, ebook.title, ebook.author)
        }
        CoverMemoryCache.cache.get(ebook.id)
    }

    suspend fun loadEpubChapters(ebook: Ebook): List<EbookChapter> = withContext(Dispatchers.IO) {
        val fmt = ebook.format.uppercase(Locale.ROOT)
        val extLower = fmt.lowercase(Locale.ROOT)

        // 1. PDF & Kindle Print Replica (.azw4)
        if (fmt == "PDF" || fmt == "AZW4") {
            val pdfChapters = mutableListOf<EbookChapter>()
            try {
                val pfd = if (fmt == "AZW4") {
                    getOrExtractAzw4PdfFile(ebook.uri, ebook.id)?.let {
                        ParcelFileDescriptor.open(it, ParcelFileDescriptor.MODE_READ_ONLY)
                    }
                } else {
                    openUriParcelFileDescriptor(ebook.uri)
                }
                pfd?.use { descriptor ->
                    PdfRenderer(descriptor).use { renderer ->
                        val count = renderer.pageCount
                        for (i in 0 until count) {
                            pdfChapters.add(EbookChapter(index = i, title = "Page ${i + 1} of $count", bodyText = ""))
                        }
                    }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Failed opening PDF/AZW4 ${ebook.title}", t)
            }
            if (pdfChapters.isNotEmpty()) {
                val savedIdx = getEbookChapterIndex(ebook.id).coerceIn(0, pdfChapters.lastIndex)
                saveEbookProgress(ebook.id, savedIdx, pdfChapters.size)
                return@withContext pdfChapters
            }
        }

        // 2. Comic Book Archives (.cbz, .cbt, .cbc, and ZIP-based .cbr)
        if (extLower in COMIC_EXTENSIONS) {
            val names = if (extLower == "cbt") listTarImageEntryNames(ebook.uri) else listZipImageEntryNames(ebook.uri)
            if (names.isNotEmpty()) {
                val comicPages = names.mapIndexed { idx, _ ->
                    EbookChapter(index = idx, title = "Page ${idx + 1} of ${names.size}", bodyText = "")
                }
                val savedIdx = getEbookChapterIndex(ebook.id).coerceIn(0, comicPages.lastIndex)
                saveEbookProgress(ebook.id, savedIdx, comicPages.size)
                return@withContext comicPages
            }
        }

        // 3. Microsoft Word .docx
        if (fmt == "DOCX") {
            val docxChaps = parseDocxChapters(ebook.uri, ebook.title)
            if (docxChaps.isNotEmpty()) {
                val savedIdx = getEbookChapterIndex(ebook.id).coerceIn(0, docxChaps.lastIndex)
                saveEbookProgress(ebook.id, savedIdx, docxChaps.size)
                return@withContext docxChaps
            }
        }

        // 4. Psion .tcr Dictionary Compressed eBooks
        if (fmt == "TCR") {
            val tcrChaps = parseTcrChapters(ebook.uri, ebook.title)
            if (tcrChaps.isNotEmpty()) {
                val savedIdx = getEbookChapterIndex(ebook.id).coerceIn(0, tcrChaps.lastIndex)
                saveEbookProgress(ebook.id, savedIdx, tcrChaps.size)
                return@withContext tcrChaps
            }
        }

        // 5. Kindle / Mobipocket / PalmDOC (.azw3, .azw, .mobi, .prc, .kf8, .pdb)
        if (extLower in KINDLE_EXTENSIONS) {
            val kindleChapters = parseKindleChapters(ebook.uri, ebook.title)
            if (kindleChapters.isNotEmpty()) {
                val savedIdx = getEbookChapterIndex(ebook.id).coerceIn(0, kindleChapters.lastIndex)
                saveEbookProgress(ebook.id, savedIdx, kindleChapters.size)
                return@withContext kindleChapters
            }
        }

        // 6. FictionBook (.fb2, .fbz, .fb2.zip)
        if (fmt in setOf("FB2", "FBZ", "FB2.ZIP")) {
            val fb2Chapters = parseFb2Chapters(ebook.uri, ebook.title, isZipped = fmt != "FB2")
            if (fb2Chapters.isNotEmpty()) {
                val savedIdx = getEbookChapterIndex(ebook.id).coerceIn(0, fb2Chapters.lastIndex)
                saveEbookProgress(ebook.id, savedIdx, fb2Chapters.size)
                return@withContext fb2Chapters
            }
        }

        // 7. Plain Text / Markdown / RTF / HTML / Sony LRS (.txt, .md, .rtf, .html, .htm, .lrs)
        if (fmt in setOf("TXT", "MD", "RTF", "HTML", "HTM", "LRS")) {
            val textChapters = parsePlainTextOrHtmlChapters(ebook.uri, fmt, ebook.title)
            if (textChapters.isNotEmpty()) {
                val savedIdx = getEbookChapterIndex(ebook.id).coerceIn(0, textChapters.lastIndex)
                saveEbookProgress(ebook.id, savedIdx, textChapters.size)
                return@withContext textChapters
            }
        }

        // 8. ZIP-Based eBook Containers (.epub, .ibooks, .htmlz, or any ZIP-wrapped book)
        val chapters = mutableListOf<EbookChapter>()
        try {
            val zipMap = linkedMapOf<String, ByteArray>()
            openUriInputStream(ebook.uri)?.use { rawStream ->
                ZipInputStream(rawStream).use { zis ->
                    var entry = zis.nextEntry
                    while (entry != null) {
                        if (!entry.isDirectory) {
                            val cleanName = entry.name.replace('\\', '/').trimStart('/')
                            val lower = cleanName.lowercase(Locale.ROOT)
                            if (lower.endsWith(".opf") ||
                                lower.endsWith(".xhtml") ||
                                lower.endsWith(".html") ||
                                lower.endsWith(".htm") ||
                                lower.endsWith(".xml") ||
                                lower.endsWith(".ncx") ||
                                lower.endsWith(".txt")
                            ) {
                                val bytes = readZipEntryBounded(zis, 8 * 1024 * 1024)
                                if (bytes != null && bytes.isNotEmpty()) {
                                    zipMap[cleanName] = bytes
                                }
                            }
                        }
                        zis.closeEntry()
                        entry = zis.nextEntry
                    }
                }
            }

            val opfEntry = zipMap.entries.firstOrNull { it.key.endsWith(".opf", ignoreCase = true) }
            if (opfEntry != null) {
                val opfXml = String(opfEntry.value, Charsets.UTF_8)
                val opfDir = opfEntry.key.substringBeforeLast('/', "")
                val parsedOpf = parseOpfXmlWithPullParser(opfXml)

                var chapCounter = 1
                val usedBytesSet = mutableSetOf<ByteArray>()
                for (href in parsedOpf.spineHrefs) {
                    val htmlBytes = findZipEntryBytes(zipMap, opfDir, href) ?: continue
                    if (!usedBytesSet.add(htmlBytes)) continue
                    val rawHtml = String(htmlBytes, Charsets.UTF_8)
                    val (extractedTitle, bodyText) = parseEpubHtmlToReadableText(rawHtml, chapCounter)
                    if (bodyText.isNotBlank()) {
                        chapters.addAll(splitLongBodyIntoChapters(extractedTitle, bodyText, chapters.size))
                        chapCounter++
                    }
                }
            }

            if (chapters.isEmpty()) {
                val fallbackKeys = zipMap.keys.filter {
                    val l = it.lowercase(Locale.ROOT)
                    (l.endsWith(".xhtml") || l.endsWith(".html") || l.endsWith(".htm") || l.endsWith(".txt")) &&
                        !l.endsWith("nav.xhtml") &&
                        !l.endsWith("toc.xhtml") &&
                        !l.endsWith("toc.html")
                }.sortedWith { a, b -> compareNatural(a, b) }

                var chapCounter = 1
                for (key in fallbackKeys) {
                    val bytes = zipMap[key] ?: continue
                    val rawHtml = String(bytes, Charsets.UTF_8)
                    val (extractedTitle, bodyText) = parseEpubHtmlToReadableText(rawHtml, chapCounter)
                    if (bodyText.isNotBlank()) {
                        chapters.addAll(splitLongBodyIntoChapters(extractedTitle, bodyText, chapters.size))
                        chapCounter++
                    }
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Failed parsing ZIP/EPUB chapters for ${ebook.title}", t)
        }

        // 9. Legacy Binary & Proprietary Container Fallback (.doc, .lit, .lrf, .rb, .chm, .kfx, .tpz, .djvu, etc.)
        if (chapters.isEmpty()) {
            chapters.addAll(extractReadableTextFromBinary(ebook.uri, fmt, ebook.title))
        }

        if (chapters.isEmpty()) {
            chapters.add(
                EbookChapter(
                    index = 0,
                    title = ebook.title,
                    bodyText = "Unable to read this file. It may be encrypted with DRM or require 'All Files Access' permission."
                )
            )
        } else {
            val savedIdx = getEbookChapterIndex(ebook.id).coerceIn(0, chapters.lastIndex)
            saveEbookProgress(ebook.id, savedIdx, chapters.size)
        }
        chapters
    }

    private fun resolveZipRelativePath(baseDir: String, relativeHref: String): String {
        val cleanHref = relativeHref.trimStart('/')
        val combined = if (baseDir.isBlank()) cleanHref else "$baseDir/$cleanHref"
        val parts = combined.split('/')
        val stack = mutableListOf<String>()
        for (p in parts) {
            when (p) {
                "", "." -> {}
                ".." -> if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex)
                else -> stack.add(p)
            }
        }
        return stack.joinToString("/")
    }

    private fun parseEpubHtmlToReadableText(html: String, fallbackNum: Int): Pair<String, String> {
        val headingMatch = Regex("<h[1-3][^>]*>(.*?)</h[1-3]>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .find(html)?.groupValues?.getOrNull(1)
        val titleTagMatch = Regex("<title[^>]*>(.*?)</title>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .find(html)?.groupValues?.getOrNull(1)

        val rawTitle = (headingMatch ?: titleTagMatch ?: "").let {
            HtmlCompat.fromHtml(it, HtmlCompat.FROM_HTML_MODE_LEGACY).toString()
                .replace(Regex("\\s+"), " ")
                .trim()
        }

        val chapterTitle = if (rawTitle.isBlank() ||
            rawTitle.equals("unknown", ignoreCase = true) ||
            rawTitle.equals("untitled", ignoreCase = true)
        ) {
            "Chapter $fallbackNum"
        } else {
            rawTitle
        }

        val withoutHeadAndStyles = html
            .replace(Regex("<head[^>]*>.*?</head>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), "")
            .replace(Regex("<style[^>]*>.*?</style>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), "")
            .replace(Regex("<script[^>]*>.*?</script>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), "")

        val spanned = HtmlCompat.fromHtml(withoutHeadAndStyles, HtmlCompat.FROM_HTML_MODE_LEGACY)
        val paragraphs = spanned.toString()
            .replace('\uFFFC', ' ')
            .lines()
            .map { it.replace(Regex("[ \\t]+"), " ").trim() }
            .joinToString("\n")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()

        return Pair(chapterTitle, paragraphs)
    }

    private data class MediaStoreAudioEntry(
        val id: Long,
        val displayName: String,
        val title: String,
        val album: String,
        val artist: String,
        val durationMs: Long,
        val relativeSegments: List<String>,
        val isAudiobookFlag: Boolean,
        val uri: Uri
    ) {
        val extension: String get() = displayName.substringAfterLast('.', "").lowercase(Locale.ROOT)
        val isContainer: Boolean get() = extension in CONTAINER_EXTENSIONS
    }

    suspend fun scanDeviceForAudiobooks(): List<Audiobook> = withContext(Dispatchers.IO) {
        val entries = mutableListOf<MediaStoreAudioEntry>()
        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI

        val projection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            arrayOf(
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.DISPLAY_NAME,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ALBUM,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.DURATION,
                MediaStore.Audio.Media.RELATIVE_PATH,
                MediaStore.Audio.Media.IS_AUDIOBOOK
            )
        } else {
            @Suppress("DEPRECATION")
            arrayOf(
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.DISPLAY_NAME,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ALBUM,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.DURATION,
                MediaStore.Audio.Media.DATA
            )
        }

        try {
            appContext.contentResolver.query(collection, projection, null, null, null)?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
                val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val albumCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val durCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                val relCol = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    cursor.getColumnIndex(MediaStore.Audio.Media.RELATIVE_PATH)
                } else -1
                val isAbCol = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    cursor.getColumnIndex(MediaStore.Audio.Media.IS_AUDIOBOOK)
                } else -1
                @Suppress("DEPRECATION")
                val dataCol = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                    cursor.getColumnIndex(MediaStore.Audio.Media.DATA)
                } else -1

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val displayName = cursor.getString(nameCol) ?: continue
                    val ext = displayName.substringAfterLast('.', "").lowercase(Locale.ROOT)
                    if (ext !in SUPPORTED_EXTENSIONS) continue

                    val title = cursor.getString(titleCol)?.trim() ?: ""
                    val album = cursor.getString(albumCol)?.trim() ?: ""
                    val artist = cursor.getString(artistCol)?.trim() ?: ""
                    val durationMs = cursor.getLong(durCol).coerceAtLeast(0L)
                    val isAudiobookFlag = if (isAbCol != -1) cursor.getInt(isAbCol) != 0 else false

                    val relPath = if (relCol != -1) {
                        cursor.getString(relCol) ?: ""
                    } else if (dataCol != -1) {
                        val fullPath = cursor.getString(dataCol) ?: ""
                        extractRelativeFolderFromLegacyPath(fullPath)
                    } else ""

                    val segments = relPath.replace('\\', '/').trim('/').split('/').filter { it.isNotBlank() }
                    val topFolder = segments.firstOrNull()?.lowercase(Locale.ROOT) ?: ""

                    val isEligibleLocation = ext == "m4b" ||
                        isAudiobookFlag ||
                        topFolder in AUTO_SCAN_ROOT_FOLDERS

                    if (isEligibleLocation) {
                        val contentUri = ContentUris.withAppendedId(collection, id)
                        entries.add(
                            MediaStoreAudioEntry(
                                id = id,
                                displayName = displayName,
                                title = title,
                                album = album,
                                artist = artist,
                                durationMs = durationMs,
                                relativeSegments = segments,
                                isAudiobookFlag = isAudiobookFlag,
                                uri = contentUri
                            )
                        )
                    }
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "MediaStore device scan failed", t)
        }

        val books = mutableListOf<Audiobook>()
        val looseEntries = mutableListOf<MediaStoreAudioEntry>()
        val subfolderGroups = linkedMapOf<String, MutableList<MediaStoreAudioEntry>>()

        for (entry in entries) {
            if (entry.relativeSegments.size <= 1) {
                looseEntries.add(entry)
            } else {
                val folderKey = entry.relativeSegments.joinToString("/")
                subfolderGroups.getOrPut(folderKey) { mutableListOf() }.add(entry)
            }
        }

        val sortedLoose = looseEntries.sortedWith { a, b -> compareNatural(a.displayName, b.displayName) }
        for (file in sortedLoose) {
            if (file.extension == "m4b" || file.isAudiobookFlag || file.durationMs >= MIN_AUDIOBOOK_DURATION_MS) {
                val (cleanFileTitle, cleanFileAuthor) = parseCleanTitleAndAuthorFromFilename(file.displayName)
                val fallbackTitle = when {
                    !isGenericTitle(file.album) && !file.album.equals("Download", ignoreCase = true) -> file.album
                    !isGenericTitle(file.title) -> file.title
                    else -> cleanFileTitle
                }
                val fallbackAuthor = if (isValidMediaStoreArtist(file.artist)) file.artist else cleanFileAuthor
                val (resolvedTitle, resolvedAuthor) = extractEmbeddedBookMetadata(
                    uri = file.uri,
                    fallbackTitle = fallbackTitle,
                    fallbackAuthor = fallbackAuthor,
                    isSingleContainer = file.isContainer
                )
                val track = AudioTrack(name = resolvedTitle, uri = file.uri, isM4b = file.isContainer)
                books.add(
                    Audiobook(
                        id = file.uri.toString(),
                        title = resolvedTitle,
                        author = resolvedAuthor,
                        series = "",
                        tracks = listOf(track)
                    )
                )
                if (file.durationMs > 0L) {
                    saveBookDurations(file.uri.toString(), listOf(file.durationMs))
                }
            }
        }

        val sortedGroupKeys = subfolderGroups.keys.sortedWith { a, b -> compareNatural(a, b) }
        for (folderKey in sortedGroupKeys) {
            val group = subfolderGroups[folderKey] ?: continue
            val totalFolderDuration = group.sumOf { it.durationMs }
            val hasM4bOrFlag = group.any { it.extension == "m4b" || it.isAudiobookFlag }

            if (totalFolderDuration < MIN_AUDIOBOOK_DURATION_MS && !hasM4bOrFlag) {
                continue
            }

            val sortedFiles = group.sortedWith { a, b -> compareNatural(a.displayName, b.displayName) }
            val innerSegments = sortedFiles.first().relativeSegments.drop(1)
            val (folderAuthor, folderSeries, folderBookTitle) = deriveHierarchyLabels(innerSegments)

            val firstValidArtist = sortedFiles.firstOrNull { isValidMediaStoreArtist(it.artist) }?.artist
            val effectiveFallbackAuthor = if (folderAuthor == "Unknown Author" && !firstValidArtist.isNullOrBlank()) {
                firstValidArtist
            } else {
                folderAuthor
            }

            val tracks = sortedFiles.map { file ->
                AudioTrack(
                    name = file.displayName.substringBeforeLast('.'),
                    uri = file.uri,
                    isM4b = file.isContainer
                )
            }

            val isSingleContainer = tracks.size == 1 && tracks.first().isM4b
            val fallbackTitle = folderBookTitle.ifBlank { tracks.first().name }
            val (resolvedTitle, resolvedAuthor) = extractEmbeddedBookMetadata(
                uri = tracks.first().uri,
                fallbackTitle = fallbackTitle,
                fallbackAuthor = effectiveFallbackAuthor,
                isSingleContainer = isSingleContainer
            )

            val bookId = if (isSingleContainer) tracks.first().uri.toString() else "folder:$folderKey"
            books.add(
                Audiobook(
                    id = bookId,
                    title = resolvedTitle,
                    author = resolvedAuthor,
                    series = folderSeries,
                    tracks = tracks
                )
            )

            val durations = sortedFiles.map { it.durationMs }
            if (durations.all { it > 0L }) {
                saveBookDurations(bookId, durations)
            }
        }

        books
    }

    private fun isValidMediaStoreArtist(artist: String?): Boolean {
        if (artist.isNullOrBlank()) return false
        return !artist.equals("<unknown>", ignoreCase = true) &&
            !artist.equals("unknown", ignoreCase = true)
    }

    private fun extractRelativeFolderFromLegacyPath(fullPath: String): String {
        val normalized = fullPath.replace('\\', '/')
        val withoutFile = normalized.substringBeforeLast('/', "")
        val markers = listOf("/storage/emulated/0/", "/sdcard/", "/storage/self/primary/")
        for (marker in markers) {
            val idx = withoutFile.indexOf(marker, ignoreCase = true)
            if (idx != -1) {
                return withoutFile.substring(idx + marker.length)
            }
        }
        return withoutFile.trimStart('/')
    }

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
                return@withContext normalizeChapterTitles(embeddedChapters, book.title)
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

        normalizeChapterTitles(chapters, book.title)
    }

    private fun extractM4bChapters(uri: Uri, trackIndex: Int = 0): List<Chapter> {
        val chapters = mutableListOf<Chapter>()

        try {
            openUriParcelFileDescriptor(uri)?.use { pfd ->
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

    suspend fun searchCoverCandidatesOnline(rawQuery: String): List<CoverSearchCandidate> = withContext(Dispatchers.IO) {
        val cleanQuery = cleanAudiobookSearchQuery(rawQuery)
        if (cleanQuery.isBlank()) return@withContext emptyList()

        val candidates = mutableListOf<CoverSearchCandidate>()
        val seenUrls = mutableSetOf<String>()

        try {
            val encoded = URLEncoder.encode(cleanQuery, "UTF-8")
            val itunesUrl = URL("https://itunes.apple.com/search?term=$encoded&media=audiobook&entity=audiobook&limit=4")
            val jsonText = readHttpText(itunesUrl)
            if (jsonText != null) {
                val results = JSONObject(jsonText).optJSONArray("results")
                if (results != null) {
                    for (i in 0 until results.length()) {
                        if (candidates.size >= 8) break
                        val item = results.getJSONObject(i)
                        val art100 = item.optString("artworkUrl100", "")
                        if (art100.isNotBlank()) {
                            val previewUrl = art100.replace("100x100bb", "300x300bb")
                            val fullUrl = art100.replace("100x100bb", "600x600bb")
                            if (seenUrls.add(fullUrl)) {
                                val bmp = downloadBitmapFromUrl(previewUrl, 240, 240)
                                if (bmp != null) {
                                    candidates.add(
                                        CoverSearchCandidate(
                                            title = item.optString("collectionName", cleanQuery),
                                            author = item.optString("artistName", ""),
                                            source = "Apple Audiobook",
                                            previewBitmap = bmp,
                                            fullResUrl = fullUrl
                                        )
                                    )
                                }
                            }
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "iTunes audiobook cover search failed for '$cleanQuery'", t)
        }

        if (candidates.size < 8) {
            try {
                val encoded = URLEncoder.encode(cleanQuery, "UTF-8")
                val ibooksUrl = URL("https://itunes.apple.com/search?term=$encoded&media=ebook&entity=ebook&limit=4")
                val jsonText = readHttpText(ibooksUrl)
                if (jsonText != null) {
                    val results = JSONObject(jsonText).optJSONArray("results")
                    if (results != null) {
                        for (i in 0 until results.length()) {
                            if (candidates.size >= 8) break
                            val item = results.getJSONObject(i)
                            val art100 = item.optString("artworkUrl100", "")
                            if (art100.isNotBlank()) {
                                val previewUrl = art100.replace("100x100bb", "300x300bb")
                                val fullUrl = art100.replace("100x100bb", "600x600bb")
                                if (seenUrls.add(fullUrl)) {
                                    val bmp = downloadBitmapFromUrl(previewUrl, 240, 240)
                                    if (bmp != null) {
                                        candidates.add(
                                            CoverSearchCandidate(
                                                title = item.optString("trackName", cleanQuery),
                                                author = item.optString("artistName", ""),
                                                source = "Apple Books",
                                                previewBitmap = bmp,
                                                fullResUrl = fullUrl
                                            )
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "iTunes ebook cover search failed for '$cleanQuery'", t)
            }
        }

        if (candidates.size < 8) {
            try {
                val encoded = URLEncoder.encode(cleanQuery, "UTF-8")
                val gbooksUrl = URL("https://www.googleapis.com/books/v1/volumes?q=$encoded&maxResults=4")
                val jsonText = readHttpText(gbooksUrl)
                if (jsonText != null) {
                    val items = JSONObject(jsonText).optJSONArray("items")
                    if (items != null) {
                        for (i in 0 until items.length()) {
                            if (candidates.size >= 8) break
                            val volInfo = items.getJSONObject(i).optJSONObject("volumeInfo") ?: continue
                            val imageLinks = volInfo.optJSONObject("imageLinks") ?: continue
                            val rawThumb = imageLinks.optString("thumbnail", imageLinks.optString("smallThumbnail", ""))
                            if (rawThumb.isNotBlank()) {
                                val httpsThumb = rawThumb.replace("http://", "https://")
                                val highRes = httpsThumb.replace("zoom=1", "zoom=2")
                                if (seenUrls.add(highRes)) {
                                    val bmp = downloadBitmapFromUrl(httpsThumb, 240, 240)
                                    if (bmp != null) {
                                        val authorsArr = volInfo.optJSONArray("authors")
                                        val firstAuthor = if (authorsArr != null && authorsArr.length() > 0) authorsArr.optString(0, "") else ""
                                        candidates.add(
                                            CoverSearchCandidate(
                                                title = volInfo.optString("title", cleanQuery),
                                                author = firstAuthor,
                                                source = "Google Books",
                                                previewBitmap = bmp,
                                                fullResUrl = highRes
                                            )
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Google Books cover search failed for '$cleanQuery'", t)
            }
        }

        if (candidates.size < 8) {
            try {
                val encoded = URLEncoder.encode(cleanQuery, "UTF-8")
                val olUrl = URL("https://openlibrary.org/search.json?q=$encoded&limit=4")
                val jsonText = readHttpText(olUrl)
                if (jsonText != null) {
                    val docs = JSONObject(jsonText).optJSONArray("docs")
                    if (docs != null) {
                        for (i in 0 until docs.length()) {
                            if (candidates.size >= 8) break
                            val doc = docs.getJSONObject(i)
                            val coverI = doc.optInt("cover_i", -1)
                            if (coverI != -1) {
                                val mediumUrl = "https://covers.openlibrary.org/b/id/$coverI-M.jpg"
                                val largeUrl = "https://covers.openlibrary.org/b/id/$coverI-L.jpg"
                                if (seenUrls.add(largeUrl)) {
                                    val bmp = downloadBitmapFromUrl(mediumUrl, 240, 240)
                                    if (bmp != null) {
                                        val authorArr = doc.optJSONArray("author_name")
                                        val firstAuthor = if (authorArr != null && authorArr.length() > 0) authorArr.optString(0, "") else ""
                                        candidates.add(
                                            CoverSearchCandidate(
                                                title = doc.optString("title", cleanQuery),
                                                author = firstAuthor,
                                                source = "Open Library",
                                                previewBitmap = bmp,
                                                fullResUrl = largeUrl
                                            )
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Open Library cover search failed for '$cleanQuery'", t)
            }
        }

        candidates
    }

    suspend fun applyCoverSearchCandidate(bookId: String, candidate: CoverSearchCandidate): Bitmap = withContext(Dispatchers.IO) {
        val highResBmp = downloadBitmapFromUrl(candidate.fullResUrl, 500, 500) ?: candidate.previewBitmap
        saveBitmapToCache(bookId, highResBmp)
        CoverMemoryCache.cache.put(bookId, highResBmp)
        highResBmp
    }

    suspend fun fetchAndSaveOnlineCoverArt(book: Audiobook): Bitmap? = withContext(Dispatchers.IO) {
        val query = cleanAudiobookSearchQuery(book.title, book.author)
        val candidates = searchCoverCandidatesOnline(query)
        val best = candidates.firstOrNull() ?: return@withContext null
        applyCoverSearchCandidate(book.id, best)
    }

    suspend fun fetchAndSaveOnlineEbookCoverArt(ebook: Ebook): Bitmap? = withContext(Dispatchers.IO) {
        val query = cleanAudiobookSearchQuery(ebook.title, ebook.author)
        val candidates = searchCoverCandidatesOnline(query)
        val best = candidates.firstOrNull() ?: return@withContext null
        applyCoverSearchCandidate(ebook.id, best)
    }

    private fun readHttpText(url: URL): String? {
        val conn = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 6000
            readTimeout = 6000
            setRequestProperty("User-Agent", "FablewoodAudiobooks/1.4")
        }
        return try {
            if (conn.responseCode in 200..299) {
                conn.inputStream.bufferedReader().use { it.readText() }
            } else null
        } finally {
            conn.disconnect()
        }
    }

    private fun downloadBitmapFromUrl(urlStr: String, reqWidth: Int, reqHeight: Int): Bitmap? {
        return try {
            val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                connectTimeout = 6000
                readTimeout = 6000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "FablewoodAudiobooks/1.4")
            }
            try {
                if (conn.responseCode in 200..299) {
                    conn.inputStream.use { stream ->
                        decodeSampledBitmapFromStreamWithExif(stream, reqWidth, reqHeight)
                    }
                } else null
            } finally {
                conn.disconnect()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Failed downloading cover bitmap from $urlStr", t)
            null
        }
    }

    suspend fun saveCustomCoverArt(bookId: String, uri: Uri): Bitmap? = withContext(Dispatchers.IO) {
        try {
            val stream = appContext.contentResolver.openInputStream(uri)
            if (stream != null) {
                val bitmap = decodeSampledBitmapFromStreamWithExif(stream, 450, 450)
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