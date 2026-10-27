package com.example.audiobook

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.runtime.getValue
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

    // Revision counters so inactive shelf cards only reload when cover or saved progress changes
    var coverRevision by mutableIntStateOf(0)
    var progressRevision by mutableIntStateOf(0)

    private var chapterJob: Job? = null
    private var coverJob: Job? = null
    private var durationsJob: Job? = null

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
                coverRevision++
                onResult(true)
            } else {
                onResult(false)
            }
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