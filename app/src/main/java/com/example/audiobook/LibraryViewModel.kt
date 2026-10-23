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
    var books by mutableStateOf<List<Audiobook>>(emptyList())
    var currentBookId by mutableStateOf("")
    
    var isPlaying by mutableStateOf(false)
    var currentPosition by mutableStateOf(0L)
    var duration by mutableStateOf(0L)
    var totalListenMs by mutableStateOf(repository.getPreferences().getLong("total_listen_ms", 0L))
    
    var isPlayerFullScreen by mutableStateOf(false)
    var isCarModeActive by mutableStateOf(false)
    
    var chaptersList by mutableStateOf<List<Chapter>>(emptyList())
    var currentBookBookmarks by mutableStateOf<List<Bookmark>>(emptyList())
    
    var searchQuery by mutableStateOf("")
    var isSearching by mutableStateOf(false)
    
    var viewMode by mutableStateOf(repository.getPreferences().getString("view_mode", "GRID") ?: "GRID")
    var sortBy by mutableStateOf(repository.getPreferences().getString("sort_by", "RECENT") ?: "RECENT")
    
    var playbackSpeed by mutableStateOf(1.0f)
    var playbackPitch by mutableStateOf(1.0f)
    var playerVolume by mutableStateOf(1.0f)
    
    var skipIntervalSeconds by mutableStateOf(repository.getPreferences().getInt("skip_interval", 15))
    var silenceTrimEnabled by mutableStateOf(repository.getPreferences().getBoolean("silence_trim", false))
    var volumeBoostEnabled by mutableStateOf(repository.getPreferences().getBoolean("volume_boost", false))
    
    var sleepTimerActive by mutableStateOf(false)
    var sleepTimeRemaining by mutableStateOf(0L)
    var sleepEndAtChapter by mutableStateOf(false)
    
    var completedBooksCount by mutableStateOf(repository.loadCompletedBookIds().size)
    var currentCoverArt by mutableStateOf<Bitmap?>(null)

    fun applyBookSpecificSpeed(bookId: String) {
        playbackSpeed = repository.loadBookSpeed(bookId)
    }

    fun updateSpeed(speed: Float) {
        playbackSpeed = speed
        if (currentBookId.isNotEmpty()) {
            repository.saveBookSpeed(currentBookId, speed)
        }
    }

    fun loadChapters(book: Audiobook) {
        viewModelScope.launch {
            // Calls the newly updated universal chapter parser
            chaptersList = repository.getBookChapters(book)
        }
    }

    fun refreshBookmarks() {
        if (currentBookId.isNotEmpty()) {
            currentBookBookmarks = repository.loadBookBookmarks(currentBookId)
        }
    }

    fun updateCoverArt(book: Audiobook) {
        viewModelScope.launch {
            currentCoverArt = repository.getOrFetchCoverArt(book)
        }
    }

    fun setCustomCover(bookId: String, uri: Uri) {
        viewModelScope.launch {
            val bmp = repository.saveCustomCoverArt(bookId, uri)
            if (bmp != null && currentBookId == bookId) {
                currentCoverArt = bmp
            }
        }
    }
}