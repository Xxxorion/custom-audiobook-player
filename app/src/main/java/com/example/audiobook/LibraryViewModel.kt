package com.example.audiobook

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch

class LibraryViewModel(val repository: AudiobookRepository) : ViewModel() {
    
    // --- UI STATES ---
    var books by mutableStateOf<List<Audiobook>>(emptyList())
    var currentBookId by mutableStateOf("")
    var isPlaying by mutableStateOf(false)
    var currentPosition by mutableStateOf(0L)
    var duration by mutableStateOf(0L)
    
    var isPlayerFullScreen by mutableStateOf(false)
    var isCarModeActive by mutableStateOf(false)
    
    var playbackSpeed by mutableStateOf(1.0f)
    var playbackPitch by mutableStateOf(repository.getPreferences().getFloat("last_pitch", 1.0f))
    var volumeBoostEnabled by mutableStateOf(repository.getPreferences().getBoolean("volume_boost", false))
    var silenceTrimEnabled by mutableStateOf(repository.getPreferences().getBoolean("silence_trim", false))
    var skipIntervalSeconds by mutableStateOf(repository.getPreferences().getInt("skip_interval", 15))
    var playerVolume by mutableStateOf(1.0f)

    var totalListenMs by mutableStateOf(repository.getPreferences().getLong("total_listen_ms", 0L))
    var completedBooksCount by mutableStateOf(repository.loadCompletedBookIds().size)

    var viewMode by mutableStateOf(repository.getPreferences().getString("view_mode", "GRID") ?: "GRID")
    var sortBy by mutableStateOf(repository.getPreferences().getString("sort_by", "AUTHOR") ?: "AUTHOR")
    var searchQuery by mutableStateOf("")
    var isSearching by mutableStateOf(false)

    var currentCoverArt by mutableStateOf<Bitmap?>(null)
    var chaptersList by mutableStateOf<List<Chapter>>(emptyList())
    var currentBookBookmarks by mutableStateOf<List<Bookmark>>(emptyList())

    var sleepTimerActive by mutableStateOf(false)
    var sleepTimeRemaining by mutableStateOf(0L)
    var sleepEndAtChapter by mutableStateOf(false)

    fun updateCoverArt(book: Audiobook) {
        viewModelScope.launch {
            currentCoverArt = repository.getOrFetchCoverArt(book)
        }
    }

    fun loadChapters(book: Audiobook) {
        viewModelScope.launch {
            val firstTrack = book.tracks.firstOrNull()
            if (book.tracks.size == 1 && firstTrack != null) {
                val uriStr = firstTrack.uri.toString().lowercase()
                if (uriStr.endsWith(".m4b") || firstTrack.name.lowercase().endsWith(".m4b")) {
                    chaptersList = repository.extractM4bChapters(firstTrack.uri)
                } else {
                    chaptersList = emptyList()
                }
            } else {
                chaptersList = emptyList()
            }
        }
    }

    fun refreshBookmarks() {
        currentBookBookmarks = repository.loadBookBookmarks(currentBookId)
    }

    fun applyBookSpecificSpeed(bookId: String) {
        playbackSpeed = repository.loadBookSpeed(bookId)
    }

    fun updateSpeed(newSpeed: Float) {
        playbackSpeed = newSpeed
        repository.saveBookSpeed(currentBookId, newSpeed)
    }
    
    fun setCustomCover(bookId: String, uri: Uri) {
        viewModelScope.launch {
            val bitmap = repository.saveCustomCoverArt(bookId, uri)
            if (bitmap != null && currentBookId == bookId) {
                currentCoverArt = bitmap
            }
            val folderUriStr = repository.getSelectedFolderUri()
            if (!folderUriStr.isNullOrEmpty()) {
                books = repository.scanFolderForAudiobooks(Uri.parse(folderUriStr))
            }
        }
    }
}