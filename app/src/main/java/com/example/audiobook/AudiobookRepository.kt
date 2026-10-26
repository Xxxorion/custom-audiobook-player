package com.example.audiobook

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.LruCache
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
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
        } catch (_: Throwable) {}
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
        } catch (_: Throwable) {}
    }

    fun loadCompletedBookIds(): Set<String> {
        val set = mutableSetOf<String>()
        try {
            val jsonStr = prefs.getString("completed_books_set_json", "[]") ?: "[]"
            val array = JSONArray(jsonStr)
            for (i in 0 until array.length()) {
                set.add(array.getString(i))
            }
        } catch (_: Throwable) {}
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
        } catch (_: Throwable) {
            1.0f
        }
    }

    fun saveBookSpeed(bookId: String, speed: Float) {
        try {
            val jsonStr = prefs.getString("book_speeds_json", "{}") ?: "{}"
            val jsonObj = JSONObject(jsonStr)
            jsonObj.put(bookId, speed.toDouble())
            prefs.edit().putString("book_speeds_json", jsonObj.toString()).apply()
        } catch (_: Throwable) {}
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

            val allM4bInRoot = currentDir == rootDir && audioFiles.size > 1 && audioFiles.all {
                it.name?.endsWith(".m4b", ignoreCase = true) == true
            }

            if (allM4bInRoot) {
                for (file in audioFiles) {
                    val rawName = file.name ?: "Audiobook.m4b"
                    val cleanName = rawName.substringBeforeLast(".")
                    val track = AudioTrack(name = cleanName, uri = file.uri, isM4b = true)
                    bookList.add(
                        Audiobook(
                            id = file.uri.toString(),
                            title = cleanName,
                            author = authorName,
                            series = seriesName,
                            tracks = listOf(track)
                        )
                    )
                }
            } else {
                val sortedTracks = audioFiles.map { file ->
                    val rawName = file.name ?: "Track"
                    val isM4bFile = rawName.endsWith(".m4b", ignoreCase = true) ||
                        rawName.endsWith(".m4a", ignoreCase = true)
                    AudioTrack(
                        name = rawName.substringBeforeLast("."),
                        uri = file.uri,
                        isM4b = isM4bFile
                    )
                }.sortedWith(Comparator { a, b -> a.name.compareTo(b.name, ignoreCase = true) })

                val bookTitle = if (currentDir.name != null && currentDir != rootDir) {
                    currentDir.name!!
                } else {
                    sortedTracks.first().name
                }

                if (sortedTracks.size == 1 && sortedTracks.first().isM4b) {
                    bookList.add(
                        Audiobook(
                            id = sortedTracks.first().uri.toString(),
                            title = bookTitle,
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
        }

        for (dir in subDirs) {
            traverseDirectoryForBooks(dir, rootDir, bookList)
        }
    }

    suspend fun getBookChapters(book: Audiobook): List<Chapter> = withContext(Dispatchers.IO) {
        if (book.tracks.size == 1 || book.tracks.any { it.isM4b }) {
            val embeddedChapters = mutableListOf<Chapter>()
            for ((index, track) in book.tracks.withIndex()) {
                val extracted = extractM4bChapters(track.uri, index)
                if (extracted.isNotEmpty()) {
                    embeddedChapters.addAll(extracted)
                } else {
                    var duration = 0L
                    var title = track.name
                    val retriever = MediaMetadataRetriever()
                    try {
                        retriever.setDataSource(context, track.uri)
                        duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                        val metaTitle = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                        if (!metaTitle.isNullOrBlank()) title = metaTitle
                    } catch (_: Throwable) {
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

        // Multi-file MP3 fallback logic
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
            } catch (_: Throwable) {}
            chapters.add(Chapter(title = title, startMs = 0L, durationMs = duration, trackIndex = index))
        }
        try { retriever.release() } catch (_: Throwable) {}

        if (chapters.isEmpty()) chapters.add(Chapter("Chapter 1", 0L, 0L, 0))
        chapters
    }

    // PURE KOTLIN MP4/M4B CHAPTER EXTRACTOR (Zero native C libraries, crash-proof)
    private fun extractM4bChapters(uri: Uri, trackIndex: Int = 0): List<Chapter> {
        val chapters = mutableListOf<Chapter>()

        // METHOD 1: Direct MP4/M4B Atom Parser (Reads Audible/Nero 'chpl' and QuickTime 'tx3g'/'text' chapter tracks)
        try {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                FileInputStream(pfd.fileDescriptor).use { fis ->
                    val parsed = parseMp4ChaptersFromChannel(fis.channel, trackIndex)
                    chapters.addAll(parsed)
                }
            }
        } catch (_: Throwable) {}

        // METHOD 2: Fallback to Android Native MediaExtractor
        if (chapters.isEmpty()) {
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(context, uri, null)
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
            } catch (_: Throwable) {
            } finally {
                try { extractor.release() } catch (_: Throwable) {}
            }
        }

        // Calculate exact duration of each chapter
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
                retriever.setDataSource(context, uri)
                val totalDuration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                if (totalDuration > chapters.last().startMs) {
                    chapters[chapters.size - 1] = chapters.last().copy(
                        durationMs = totalDuration - chapters.last().startMs
                    )
                }
            } catch (_: Throwable) {
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

        // 1. Check Nero chapters inside moov -> udta -> chpl (Used by Audible, Libation, OpenAudible, FFmpeg)
        val udta = moovChildren.firstOrNull { it.type == "udta" }
        if (udta != null) {
            val udtaChildren = listChildBoxes(channel, udta.payloadOffset, udta.endOffset)
            val chpl = udtaChildren.firstOrNull { it.type == "chpl" }
            if (chpl != null) {
                val neroChapters = parseChplBox(channel, chpl, trackIndex)
                if (neroChapters.isNotEmpty()) return neroChapters
            }
        }

        // 2. Check QuickTime chapter tracks inside moov -> trak
        val traks = moovChildren.filter { it.type == "trak" }
        val referencedChapterTrackIds = findReferencedChapterTrackIds(channel, traks)

        for (trak in traks) {
            val trakChapters = parseQuickTimeTextTrak(channel, trak, trackIndex, referencedChapterTrackIds)
            if (trakChapters.isNotEmpty()) return trakChapters
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

    private fun parseChplBox(channel: FileChannel, chpl: Mp4Box, trackIndex: Int): List<Chapter> {
        val buf = readBoxPayload(channel, chpl, 5) ?: return emptyList()
        val version = buf.get().toInt() and 0xFF
        if (buf.remaining() < 3) return emptyList()
        buf.get(); buf.get(); buf.get() // 3 bytes flags

        val count: Int = if (version == 0) {
            if (buf.remaining() < 1) return emptyList()
            buf.get().toInt() and 0xFF
        } else {
            // Version 1 has 1 reserved byte + 4-byte big-endian chapter count (or 4 reserved bytes + 1-byte count)
            if (buf.remaining() < 5) return emptyList()
            buf.get() // 1 byte reserved
            buf.int
        }

        if (count <= 0 || count > 5000) return emptyList()

        val result = mutableListOf<Chapter>()
        for (i in 0 until count) {
            if (buf.remaining() < 9) break
            val startTicks = buf.long // 100-nanosecond units
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

        // Read timescale from mdhd safely
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

        // 1. Read sample deltas from stts
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

        // 2. Read sample sizes from stsz
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

        // 3. Read chunk offsets from stco or co64
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

        // 4. Read sample-to-chunk mapping from stsc
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
            stscBuf.int // sampleDescriptionIndex
            if (firstChunk > 0 && samplesPerChunk > 0) {
                stscEntries.add(StscEntry(firstChunk, samplesPerChunk))
            }
        }
        if (stscEntries.isEmpty()) return emptyList()

        // 5. Resolve each sample's file offset and decode the chapter title
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

    private fun decodeTx3gTitle(bytes: ByteArray, sampleSize: Int, chapterNumber: Int): String {
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
        } catch (_: Throwable) {}
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
        } catch (_: Throwable) { null }
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
        } catch (_: Throwable) {
            try { retriever.release() } catch (_: Throwable) {}
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
        } catch (_: Throwable) {}
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
// END OF FILE