package com.example.audiobook

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class LibraryViewModel(val repository: AudiobookRepository) : ViewModel() {
    var books by mutableStateOf<List<Audiobook>>(repository.loadBooksCache())
    var currentBookId by mutableStateOf("")

    // Top-Center Shelf Toggle: "AUDIO" or "EBOOK"
    var shelfMode by mutableStateOf(repository.getPreferences().getString("shelf_mode", "AUDIO") ?: "AUDIO")

    // eBook Shelf & Reader State
    var ebooks by mutableStateOf<List<Ebook>>(repository.loadEbooksCache())
    var currentEbookId by mutableStateOf(repository.getLastEbookId() ?: "")
    var currentEbookChapters by mutableStateOf<List<EbookChapter>>(emptyList())
    var currentEbookChapterIndex by mutableIntStateOf(0)
    var isLoadingEbook by mutableStateOf(false)
    var isEbookReaderFullScreen by mutableStateOf(false)
    var ebookFontSizeSp by mutableFloatStateOf(repository.getPreferences().getFloat("ebook_font_sp", 18f))
    var ebookReaderTheme by mutableStateOf(repository.getPreferences().getString("ebook_reader_theme", "FOREST") ?: "FOREST")

    // Phase 3: Book Discovery & Affiliate Hub State
    var discoveryQuery by mutableStateOf("")
    var discoveryBooks by mutableStateOf<List<DiscoveryBook>>(emptyList())
    var isSearchingDiscovery by mutableStateOf(false)

    var isPlaying by mutableStateOf(false)
    var currentTrackIndex by mutableIntStateOf(0)
    var currentPosition by mutableLongStateOf(0L)
    var duration by mutableLongStateOf(0L)
    var currentBookTrackDurations by mutableStateOf<List<Long>>(emptyList())
    var totalListenMs by mutableLongStateOf(repository.getPreferences().getLong("total_listen_ms", 0L))

    var isPlayerFullScreen by mutableStateOf(false)
    var isCarModeActive by mutableStateOf(false)

    var chaptersList by mutableStateOf<List<Chapter>>(emptyList())
    var currentBookBookmarks by mutableStateOf<List<Bookmark>>(emptyList())

    var searchQuery by mutableStateOf("")
    var isSearching by mutableStateOf(false)

    var selectedFolderUri by mutableStateOf(repository.getSelectedFolderUri() ?: "")
    var viewMode by mutableStateOf(repository.getPreferences().getString("view_mode", "GRID") ?: "GRID")
    var sortBy by mutableStateOf(repository.getPreferences().getString("sort_by", "RECENT") ?: "RECENT")

    var playbackSpeed by mutableStateOf(1.0f)
    var playbackPitch by mutableStateOf(repository.loadPlaybackPitch())
    var playerVolume by mutableStateOf(1.0f)

    var skipIntervalSeconds by mutableIntStateOf(repository.getPreferences().getInt("skip_interval", 15))
    var silenceTrimEnabled by mutableStateOf(repository.getPreferences().getBoolean("silence_trim", false))
    var volumeBoostEnabled by mutableStateOf(repository.getPreferences().getBoolean("volume_boost", false))

    var sleepTimerActive by mutableStateOf(false)
    var sleepTimeRemaining by mutableLongStateOf(0L)
    var sleepEndAtChapter by mutableStateOf(false)
    var sleepTimerVersion by mutableIntStateOf(0)

    var completedBooksCount by mutableIntStateOf(repository.loadCompletedBookIds().size)
    var currentCoverArt by mutableStateOf<Bitmap?>(null)
    var currentEbookCoverArt by mutableStateOf<Bitmap?>(null)

    var coverRevision by mutableIntStateOf(0)
    var progressRevision by mutableIntStateOf(0)

    var coverSearchCandidates by mutableStateOf<List<CoverSearchCandidate>>(emptyList())
    var isSearchingCovers by mutableStateOf(false)
    var isBatchFetchingCovers by mutableStateOf(false)

    private var chapterJob: Job? = null
    private var coverJob: Job? = null
    private var durationsJob: Job? = null
    private var coverSearchJob: Job? = null
    private var ebookLoadJob: Job? = null
    private var discoveryJob: Job? = null

    fun searchDiscoveryCatalog(query: String) {
        val effectiveQuery = query.trim().ifBlank {
            val activeAuthor = books.find { it.id == currentBookId }?.author
                ?: ebooks.find { it.id == currentEbookId }?.author
                ?: ""
            if (activeAuthor.isNotBlank() && !activeAuthor.equals("Unknown Author", ignoreCase = true)) {
                activeAuthor
            } else {
                "New York Times Bestseller Fiction"
            }
        }
        discoveryQuery = effectiveQuery
        discoveryJob?.cancel()
        isSearchingDiscovery = true
        discoveryJob = viewModelScope.launch {
            discoveryBooks = repository.searchDiscoveryBooksOnline(effectiveQuery)
            isSearchingDiscovery = false
        }
    }

    fun updateShelfMode(mode: String) {
        shelfMode = mode
        repository.getPreferences().edit().putString("shelf_mode", mode).apply()
    }

    fun selectEbook(ebook: Ebook, openFullScreen: Boolean) {
        currentEbookId = ebook.id
        repository.touchEbookLastRead(ebook.id)
        if (openFullScreen) {
            isEbookReaderFullScreen = true
        }
        ebookLoadJob?.cancel()
        isLoadingEbook = true
        ebookLoadJob = viewModelScope.launch {
            val cover = repository.getOrFetchEbookCoverArt(ebook)
            if (currentEbookId == ebook.id) {
                currentEbookCoverArt = cover
            }
            val loadedChapters = repository.loadEpubChapters(ebook)
            currentEbookChapters = loadedChapters
            val savedIdx = repository.getEbookChapterIndex(ebook.id).coerceIn(0, (loadedChapters.size - 1).coerceAtLeast(0))
            currentEbookChapterIndex = savedIdx
            repository.saveEbookProgress(ebook.id, savedIdx, loadedChapters.size)
            isLoadingEbook = false
            progressRevision++
        }
    }

    fun goToEbookChapter(index: Int) {
        if (currentEbookChapters.isEmpty()) return
        val clamped = index.coerceIn(0, currentEbookChapters.lastIndex)
        currentEbookChapterIndex = clamped
        if (currentEbookId.isNotEmpty()) {
            repository.saveEbookProgress(currentEbookId, clamped, currentEbookChapters.size)
            progressRevision++
        }
    }

    fun updateEbookFontSize(newSizeSp: Float) {
        val clamped = newSizeSp.coerceIn(13f, 30f)
        ebookFontSizeSp = clamped
        repository.getPreferences().edit().putFloat("ebook_font_sp", clamped).apply()
    }

    fun updateEbookReaderTheme(theme: String) {
        ebookReaderTheme = theme
        repository.getPreferences().edit().putString("ebook_reader_theme", theme).apply()
    }

    fun selectBookMetadata(book: Audiobook, isDifferentBook: Boolean) {
        currentBookId = book.id
        applyBookSpecificSpeed(book.id)
        refreshBookmarks()
        if (isDifferentBook) {
            currentCoverArt = null
            chaptersList = emptyList()
            currentBookTrackDurations = repository.getCachedTrackDurations(book.id)
        }
        loadChapters(book)
        updateCoverArt(book)
        loadTrackDurations(book)
    }

    fun applyBookSpecificSpeed(bookId: String) {
        playbackSpeed = repository.loadBookSpeed(bookId)
    }

    fun updateSpeed(speed: Float) {
        val clamped = speed.coerceIn(0.5f, 3.0f)
        playbackSpeed = clamped
        if (currentBookId.isNotEmpty()) {
            repository.saveBookSpeed(currentBookId, clamped)
        }
    }

    fun updatePitch(pitch: Float) {
        val clamped = pitch.coerceIn(0.75f, 1.25f)
        playbackPitch = clamped
        repository.savePlaybackPitch(clamped)
    }

    fun updateViewMode(mode: String) {
        viewMode = mode
        repository.getPreferences().edit().putString("view_mode", mode).apply()
    }

    fun updateSortBy(sortKey: String) {
        sortBy = sortKey
        repository.getPreferences().edit().putString("sort_by", sortKey).apply()
    }

    fun updateSkipInterval(seconds: Int) {
        skipIntervalSeconds = seconds
        repository.getPreferences().edit().putInt("skip_interval", seconds).apply()
    }

    fun setSilenceTrim(enabled: Boolean) {
        silenceTrimEnabled = enabled
        repository.getPreferences().edit().putBoolean("silence_trim", enabled).apply()
    }

    fun setVolumeBoost(enabled: Boolean) {
        volumeBoostEnabled = enabled
        repository.getPreferences().edit().putBoolean("volume_boost", enabled).apply()
    }

    fun startTimedSleep(minutes: Int) {
        sleepTimeRemaining = minutes * 60 * 1000L
        sleepEndAtChapter = false
        sleepTimerActive = true
        sleepTimerVersion++
    }

    fun startEndOfChapterSleep() {
        sleepEndAtChapter = true
        sleepTimeRemaining = 0L
        sleepTimerActive = true
        sleepTimerVersion++
    }

    fun cancelSleepTimer() {
        sleepTimerActive = false
        sleepEndAtChapter = false
        sleepTimeRemaining = 0L
    }

    fun loadChapters(book: Audiobook) {
        val targetId = book.id
        chapterJob?.cancel()
        chapterJob = viewModelScope.launch {
            val loaded = repository.getBookChapters(book)
            if (currentBookId == targetId) {
                chaptersList = loaded
            }
        }
    }

    fun loadTrackDurations(book: Audiobook) {
        val targetId = book.id
        durationsJob?.cancel()
        durationsJob = viewModelScope.launch {
            val durations = repository.getOrComputeTrackDurations(book)
            if (currentBookId == targetId) {
                currentBookTrackDurations = durations
                progressRevision++
            }
        }
    }

    fun updateCoverArt(book: Audiobook) {
        val targetId = book.id
        coverJob?.cancel()
        coverJob = viewModelScope.launch {
            val bmp = repository.getOrFetchCoverArt(book)
            if (currentBookId == targetId) {
                currentCoverArt = bmp
            }
        }
    }

    fun setCustomCover(bookId: String, uri: Uri, onResult: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            val bmp = repository.saveCustomCoverArt(bookId, uri)
            if (bmp != null) {
                if (currentBookId == bookId) {
                    currentCoverArt = bmp
                }
                if (currentEbookId == bookId) {
                    currentEbookCoverArt = bmp
                }
                coverRevision++
                onResult(true)
            } else {
                onResult(false)
            }
        }
    }

    fun searchOnlineCoverCandidates(query: String) {
        coverSearchJob?.cancel()
        isSearchingCovers = true
        coverSearchCandidates = emptyList()
        coverSearchJob = viewModelScope.launch {
            val results = repository.searchCoverCandidatesOnline(query)
            coverSearchCandidates = results
            isSearchingCovers = false
        }
    }

    fun selectOnlineCoverCandidate(bookId: String, candidate: CoverSearchCandidate, onDone: () -> Unit = {}) {
        viewModelScope.launch {
            val bmp = repository.applyCoverSearchCandidate(bookId, candidate)
            if (currentBookId == bookId) {
                currentCoverArt = bmp
            }
            if (currentEbookId == bookId) {
                currentEbookCoverArt = bmp
            }
            coverRevision++
            onDone()
        }
    }

    fun searchOnlineCover(book: Audiobook, onResult: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            val bmp = repository.fetchAndSaveOnlineCoverArt(book)
            if (bmp != null) {
                if (currentBookId == book.id) {
                    currentCoverArt = bmp
                }
                coverRevision++
                onResult(true)
            } else {
                onResult(false)
            }
        }
    }

    fun autoFillMissingCovers(onComplete: (Int) -> Unit = {}) {
        if (isBatchFetchingCovers) return
        isBatchFetchingCovers = true
        viewModelScope.launch {
            var updatedCount = 0
            val audioSnapshot = books.toList()
            for (book in audioSnapshot) {
                val existing = repository.getOrFetchCoverArt(book)
                if (existing == null) {
                    val fetched = repository.fetchAndSaveOnlineCoverArt(book)
                    if (fetched != null) {
                        updatedCount++
                        if (currentBookId == book.id) {
                            currentCoverArt = fetched
                        }
                        coverRevision++
                    }
                }
            }
            val ebookSnapshot = ebooks.toList()
            for (ebook in ebookSnapshot) {
                val existing = repository.getOrFetchEbookCoverArt(ebook)
                if (existing == null) {
                    val fetched = repository.fetchAndSaveOnlineEbookCoverArt(ebook)
                    if (fetched != null) {
                        updatedCount++
                        if (currentEbookId == ebook.id) {
                            currentEbookCoverArt = fetched
                        }
                        coverRevision++
                    }
                }
            }
            isBatchFetchingCovers = false
            onComplete(updatedCount)
        }
    }

    fun addBookmark(trackIndex: Int, positionMs: Long, chapterTitle: String, note: String) {
        if (currentBookId.isEmpty()) return
        val bm = Bookmark(
            trackIndex = trackIndex,
            positionMs = positionMs,
            chapterTitle = chapterTitle,
            note = note
        )
        repository.saveBookBookmark(currentBookId, bm)
        refreshBookmarks()
    }

    fun deleteBookmark(bookmarkId: String) {
        if (currentBookId.isEmpty()) return
        repository.deleteBookBookmark(currentBookId, bookmarkId)
        refreshBookmarks()
    }

    fun refreshBookmarks() {
        if (currentBookId.isNotEmpty()) {
            currentBookBookmarks = repository.loadBookBookmarks(currentBookId)
        }
    }

    fun saveProgressNow(bookId: String = currentBookId, trackIndex: Int = currentTrackIndex, positionMs: Long = currentPosition) {
        if (bookId.isEmpty()) return
        repository.saveBookProgress(bookId, trackIndex, positionMs, totalListenMs, immediate = true)
        progressRevision++
    }
}
// END OF FILE