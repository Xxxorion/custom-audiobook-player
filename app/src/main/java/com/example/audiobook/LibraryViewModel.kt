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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class ProFeature(val title: String, val description: String) {
    DUAL_PAGE_FOLDABLE("Foldable Dual-Page Spread", "Read across a 2-column open-book layout on foldables and tablets."),
    CUSTOM_THEMES("Custom Theme Builder", "Design your own custom background and ink hex color palettes."),
    QUOTE_CARDS("Shareable Quote Cards", "Turn favorite passages into high-res, stylized social quote cards."),
    QUOTES_HUB("Global Quotes & Notes Hub", "Search and filter every highlight and note across your entire library."),
    TTS_READ_ALOUD("AI Read-Aloud (TTS)", "Listen to any eBook with live sentence highlighting and background playback."),
    READING_STATS("Reading Streaks & Insights", "Track daily reading minutes, pages turned, and long-term streaks.")
}

/**
 * Centralized Paywall-Ready State Manager.
 * Currently unlocked (`true`) so you can test the full Pro experience.
 * Flip ` MutableStateFlow(false)` when ready to gate Pro features behind billing.
 */
object ProFeatureManager {
    private val _isProUser = MutableStateFlow(true)
    val isProUser: StateFlow<Boolean> = _isProUser.asStateFlow()

    var activePaywallPromptFeature by mutableStateOf<ProFeature?>(null)

    fun setProStatus(unlocked: Boolean) {
        _isProUser.value = unlocked
    }

    fun isUnlocked(feature: ProFeature): Boolean {
        return _isProUser.value
    }

    fun runIfPro(feature: ProFeature, action: () -> Unit) {
        if (isUnlocked(feature)) {
            action()
        } else {
            activePaywallPromptFeature = feature
        }
    }
}

data class ReaderPreset(
    val id: String,
    val name: String,
    val subtitle: String,
    val themeKey: String,
    val fontFamilyKey: String,
    val fontSizeSp: Float,
    val lineHeightMultiplier: Float,
    val paragraphSpacingDp: Float,
    val marginScale: Float,
    val isJustified: Boolean
)

val DESIGNER_READER_PRESETS = listOf(
    ReaderPreset(
        id = "EDITORIAL",
        name = "Editorial",
        subtitle = "Classic Serif • Warm Paper",
        themeKey = "SEPIA",
        fontFamilyKey = "SERIF",
        fontSizeSp = 19f,
        lineHeightMultiplier = 1.48f,
        paragraphSpacingDp = 14f,
        marginScale = 1.20f,
        isJustified = true
    ),
    ReaderPreset(
        id = "MODERN_CLEAN",
        name = "Modern Clean",
        subtitle = "Crisp Sans • Day White",
        themeKey = "LIGHT",
        fontFamilyKey = "SANS",
        fontSizeSp = 17.5f,
        lineHeightMultiplier = 1.42f,
        paragraphSpacingDp = 12f,
        marginScale = 1.0f,
        isJustified = false
    ),
    ReaderPreset(
        id = "COZY_PAPERBACK",
        name = "Cozy Paperback",
        subtitle = "Book Serif • Soft Sepia",
        themeKey = "SEPIA",
        fontFamilyKey = "BOOK",
        fontSizeSp = 18f,
        lineHeightMultiplier = 1.36f,
        paragraphSpacingDp = 8f,
        marginScale = 0.85f,
        isJustified = true
    ),
    ReaderPreset(
        id = "NIGHT_OWL",
        name = "Night Owl",
        subtitle = "Dimmed Serif • True OLED",
        themeKey = "OLED",
        fontFamilyKey = "SERIF",
        fontSizeSp = 18.5f,
        lineHeightMultiplier = 1.46f,
        paragraphSpacingDp = 12f,
        marginScale = 1.05f,
        isJustified = false
    )
)

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

    // Module A: Advanced Typography, Presets & Custom Theme State
    var ebookFontSizeSp by mutableFloatStateOf(repository.getPreferences().getFloat("ebook_font_sp", 18f))
    var ebookReaderTheme by mutableStateOf(repository.getPreferences().getString("ebook_reader_theme", "SEPIA") ?: "SEPIA")
    var ebookPresetId by mutableStateOf(repository.getPreferences().getString("ebook_preset_id", "EDITORIAL") ?: "EDITORIAL")
    var ebookFontFamily by mutableStateOf(repository.getPreferences().getString("ebook_font_family", "SERIF") ?: "SERIF")
    var ebookLineHeightMultiplier by mutableFloatStateOf(repository.getPreferences().getFloat("ebook_line_height_mult", 1.42f))
    var ebookParagraphSpacingDp by mutableFloatStateOf(repository.getPreferences().getFloat("ebook_para_spacing_dp", 12f))
    var ebookMarginScale by mutableFloatStateOf(repository.getPreferences().getFloat("ebook_margin_scale", 1.0f))
    var ebookTextJustified by mutableStateOf(repository.getPreferences().getBoolean("ebook_text_justified", true))
    var ebookRespectPublisherCss by mutableStateOf(repository.getPreferences().getBoolean("ebook_respect_css", false))
    var ebookCustomBgHex by mutableStateOf(repository.getPreferences().getString("ebook_custom_bg_hex", "#121E18") ?: "#121E18")
    var ebookCustomTextHex by mutableStateOf(repository.getPreferences().getString("ebook_custom_text_hex", "#E6EFEA") ?: "#E6EFEA")

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

    fun applyReaderPreset(preset: ReaderPreset) {
        ebookPresetId = preset.id
        ebookReaderTheme = preset.themeKey
        ebookFontFamily = preset.fontFamilyKey
        ebookFontSizeSp = preset.fontSizeSp
        ebookLineHeightMultiplier = preset.lineHeightMultiplier
        ebookParagraphSpacingDp = preset.paragraphSpacingDp
        ebookMarginScale = preset.marginScale
        ebookTextJustified = preset.isJustified

        repository.getPreferences().edit()
            .putString("ebook_preset_id", preset.id)
            .putString("ebook_reader_theme", preset.themeKey)
            .putString("ebook_font_family", preset.fontFamilyKey)
            .putFloat("ebook_font_sp", preset.fontSizeSp)
            .putFloat("ebook_line_height_mult", preset.lineHeightMultiplier)
            .putFloat("ebook_para_spacing_dp", preset.paragraphSpacingDp)
            .putFloat("ebook_margin_scale", preset.marginScale)
            .putBoolean("ebook_text_justified", preset.isJustified)
            .apply()
    }

    fun updateEbookFontFamily(familyKey: String) {
        ebookFontFamily = familyKey
        ebookPresetId = "CUSTOM"
        repository.getPreferences().edit()
            .putString("ebook_font_family", familyKey)
            .putString("ebook_preset_id", "CUSTOM")
            .apply()
    }

    fun updateEbookLineHeight(multiplier: Float) {
        val clamped = multiplier.coerceIn(1.15f, 2.0f)
        ebookLineHeightMultiplier = clamped
        ebookPresetId = "CUSTOM"
        repository.getPreferences().edit()
            .putFloat("ebook_line_height_mult", clamped)
            .putString("ebook_preset_id", "CUSTOM")
            .apply()
    }

    fun updateEbookParagraphSpacing(spacingDp: Float) {
        val clamped = spacingDp.coerceIn(0f, 28f)
        ebookParagraphSpacingDp = clamped
        ebookPresetId = "CUSTOM"
        repository.getPreferences().edit()
            .putFloat("ebook_para_spacing_dp", clamped)
            .putString("ebook_preset_id", "CUSTOM")
            .apply()
    }

    fun updateEbookMarginScale(scale: Float) {
        val clamped = scale.coerceIn(0.60f, 1.60f)
        ebookMarginScale = clamped
        ebookPresetId = "CUSTOM"
        repository.getPreferences().edit()
            .putFloat("ebook_margin_scale", clamped)
            .putString("ebook_preset_id", "CUSTOM")
            .apply()
    }

    fun updateEbookTextJustified(justified: Boolean) {
        ebookTextJustified = justified
        ebookPresetId = "CUSTOM"
        repository.getPreferences().edit()
            .putBoolean("ebook_text_justified", justified)
            .putString("ebook_preset_id", "CUSTOM")
            .apply()
    }

    fun updateEbookRespectCss(respect: Boolean) {
        ebookRespectPublisherCss = respect
        if (respect) {
            ebookLineHeightMultiplier = 1.38f
            ebookParagraphSpacingDp = 10f
            ebookMarginScale = 1.0f
        }
        repository.getPreferences().edit()
            .putBoolean("ebook_respect_css", respect)
            .apply()
    }

    fun updateCustomThemeHex(bgHex: String, textHex: String) {
        ProFeatureManager.runIfPro(ProFeature.CUSTOM_THEMES) {
            ebookCustomBgHex = bgHex
            ebookCustomTextHex = textHex
            ebookReaderTheme = "CUSTOM"
            ebookPresetId = "CUSTOM"
            repository.getPreferences().edit()
                .putString("ebook_custom_bg_hex", bgHex)
                .putString("ebook_custom_text_hex", textHex)
                .putString("ebook_reader_theme", "CUSTOM")
                .putString("ebook_preset_id", "CUSTOM")
                .apply()
        }
    }

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
        ebookPresetId = "CUSTOM"
        repository.getPreferences().edit()
            .putFloat("ebook_font_sp", clamped)
            .putString("ebook_preset_id", "CUSTOM")
            .apply()
    }

    fun updateEbookReaderTheme(theme: String) {
        ebookReaderTheme = theme
        ebookPresetId = "CUSTOM"
        repository.getPreferences().edit()
            .putString("ebook_reader_theme", theme)
            .putString("ebook_preset_id", "CUSTOM")
            .apply()
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