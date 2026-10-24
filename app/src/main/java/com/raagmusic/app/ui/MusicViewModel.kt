package com.raagmusic.app.ui

import android.net.Uri
import coil.imageLoader
import coil.request.ImageRequest
import com.raagmusic.app.data.youtube.ArtworkUrls
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.*
import com.raagmusic.app.RaagApplication
import com.raagmusic.app.data.youtube.toLibraryTrack
import com.raagmusic.app.data.youtube.BrowseCollection
import com.raagmusic.app.data.youtube.CollectionKind
import com.raagmusic.app.domain.*
import com.raagmusic.app.playback.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.UUID
import java.time.LocalDate
import java.time.LocalDateTime
import com.raagmusic.app.data.LibraryBackup
import com.raagmusic.app.data.youtube.CollectionCursor

@UnstableApi
class MusicViewModel(private val app: RaagApplication,
    private val youtube: com.raagmusic.app.data.youtube.YoutubeMusicRepository = app.youtube,
    private val lyricsRepository: com.raagmusic.app.data.LyricsRepository = app.lyrics) : AndroidViewModel(app) {
    val library = app.library.observed.stateIn(viewModelScope, SharingStarted.Eagerly, LibraryState())
    val features = app.features.observed.stateIn(viewModelScope, SharingStarted.Eagerly, FeatureState())
    val preferences = features.map { it.preferences }.distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, ListeningPreferences())
    // Wait for the saved choice before applying a native mode: a temporary System default
    // would clear a user's persisted Dark/Light override and flash on every cold start.
    val theme = app.features.observed.map { it.theme }.distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val experience = features.map { it.experience }.distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, ExperiencePreferences())
    private val analytics = features.map { FeatureState(history = it.history, lessLikeIds = it.lessLikeIds,
        experience = ExperiencePreferences(followedArtists = it.experience.followedArtists)) }
        .distinctUntilChanged().flowOn(Dispatchers.Default)
    val insights = combine(library, analytics) { library, analytics ->
        LibraryInsights(SmartPlaylist.entries.associateWith { smartPlaylist(it, library, analytics, LocalDate.now()) },
            historyTimeline(library, analytics))
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, LibraryInsights())
    private val rankingSignals = analytics.map { state -> state.copy(history = state.history.map {
        it.copy(milliseconds = it.milliseconds / 60_000 * 60_000)
    }) }.distinctUntilChanged().flowOn(Dispatchers.Default)
    private val mixCandidates = MutableStateFlow<List<Track>>(emptyList())
    private val mutableMixLoading = MutableStateFlow(false)
    val mixLoading = mutableMixLoading.asStateFlow()
    private var mixJob: Job? = null
    private val homeTime = flow {
        while (true) { emit(java.time.LocalDateTime.now().withMinute(0).withSecond(0).withNano(0)); delay(60_000) }
    }.distinctUntilChanged().stateIn(viewModelScope, SharingStarted.Eagerly, java.time.LocalDateTime.now())
    val dailyMix = combine(library, rankingSignals, mixCandidates, homeTime) { library, features, candidates, now ->
        rankRecommendations(candidates + library.savedTracks, null, library, features, now.toLocalDate(), now.hour, limit = 30)
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    private val mutableLyrics = MutableStateFlow(LyricsState())
    val lyrics = mutableLyrics.asStateFlow()
    private var lyricsJob: Job? = null
    private val mutableUndoLabel = MutableStateFlow<String?>(null)
    val undoLabel = mutableUndoLabel.asStateFlow()
    private val mutableUndoVersion = MutableStateFlow(0L)
    val undoVersion = mutableUndoVersion.asStateFlow()
    private var undoAction: (() -> Unit)? = null
    private val mutableBackupBusy = MutableStateFlow(false)
    val backupBusy = mutableBackupBusy.asStateFlow()
    private var collectionCursor: CollectionCursor? = null
    private val seenCollectionCursors = mutableSetOf<String>()
    private val mutableHasMoreCollection = MutableStateFlow(false)
    val hasMoreCollection = mutableHasMoreCollection.asStateFlow()
    private val mutableCollectionLoadingMore = MutableStateFlow(false)
    val collectionLoadingMore = mutableCollectionLoadingMore.asStateFlow()
    private val mutableCollectionPageError = MutableStateFlow<String?>(null)
    val collectionPageError = mutableCollectionPageError.asStateFlow()
    private var pageJob: Job? = null
    private var collectionActionJob: Job? = null
    val player = PlayerConnection(app, viewModelScope)
    val playback = player.state
    val activeTrackId = playback.map { it.trackId }.distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val hasListeningSession = combine(activeTrackId, features.map {
        it.preferences.rememberQueue && it.queue.tracks.isNotEmpty()
    }.distinctUntilChanged()) { active, saved -> active != null || saved }
        .distinctUntilChanged().stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val queueView = playback.map { QueueView(it.queueIds, it.queueIndex, it.upcomingIndices) }.distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, QueueView())
    private val mutableSearchQuery = MutableStateFlow("")
    val searchQuery = mutableSearchQuery.asStateFlow()
    private val mutableSearchResults = MutableStateFlow<List<Track>>(emptyList())
    val searchResults = mutableSearchResults.asStateFlow()
    private val mutableSearchSuggestions = MutableStateFlow<List<String>>(emptyList())
    val searchSuggestions = mutableSearchSuggestions.asStateFlow()
    private val mutableSearchOrder = MutableStateFlow<List<String>>(emptyList())
    val searchOrder = mutableSearchOrder.asStateFlow()
    private var searchKey: Pair<String, String>? = null
    private val mutableSearchLoading = MutableStateFlow(false)
    val searchLoading = mutableSearchLoading.asStateFlow()
    private val mutableSearchError = MutableStateFlow<String?>(null)
    val searchError = mutableSearchError.asStateFlow()
    private val mutableDownloads = MutableStateFlow<Map<String, Download>>(emptyMap())
    private val downloadRefreshes = kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.CONFLATED)
    private var downloadPoll: Job? = null
    private var downloadManager: DownloadManager? = null
    val downloads = mutableDownloads.asStateFlow()
    private val mutableMessages = MutableSharedFlow<String>(extraBufferCapacity = 5)
    val messages = mutableMessages.asSharedFlow()
    internal val updates = app.updates.state
    private val mutableUpdateDialog = MutableStateFlow(false)
    internal val updateDialog = mutableUpdateDialog.asStateFlow()
    internal fun showAvailableUpdate() { mutableUpdateDialog.value = true }
    internal fun checkForUpdates() {
        mutableUpdateDialog.value = true
        viewModelScope.launch { app.updates.check(manual = true) }
    }
    internal fun dismissUpdate() {
        viewModelScope.launch {
            app.updates.dismiss()
            mutableUpdateDialog.value = false
        }
    }
    private val mutableSearchArtists = MutableStateFlow<List<com.raagmusic.app.data.youtube.ArtistProfile>>(emptyList())
    val searchArtists = mutableSearchArtists.asStateFlow()
    private val mutableSearchKind = MutableStateFlow("All")
    val searchKind = mutableSearchKind.asStateFlow()
    private val mutableSearchCollections = MutableStateFlow<List<BrowseCollection>>(emptyList())
    val searchCollections = mutableSearchCollections.asStateFlow()
    private val mutableHomeRegion = MutableStateFlow<MusicRegion?>(null)
    val homeRegion = mutableHomeRegion.asStateFlow()
    private val mutableHomeRefreshing = MutableStateFlow(false)
    val homeRefreshing = mutableHomeRefreshing.asStateFlow()
    val discoveryPreferences = features.map { it.discovery }.distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, DiscoveryPreferences())
    val resumeQueue = features.map { it.queue }.distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, QueueSnapshot())
    private val mutableHomeTrending = MutableStateFlow(ContentState<Track>(loading = true))
    val homeTrending = mutableHomeTrending.asStateFlow()
    private val mutableHomeNewReleases = MutableStateFlow(ContentState<Track>(loading = true))
    val homeNewReleases = mutableHomeNewReleases.asStateFlow()
    private val mutableHomeSongs = MutableStateFlow(ContentState<Track>(loading = true))
    val homeSongs = mutableHomeSongs.asStateFlow()
    private val mutableHomePlaylists = MutableStateFlow(ContentState<BrowseCollection>(loading = true))
    val homePlaylists = mutableHomePlaylists.asStateFlow()
    private val mutableHomeAlbums = MutableStateFlow(ContentState<BrowseCollection>(loading = true))
    val homeAlbums = mutableHomeAlbums.asStateFlow()
    private val mutableSelectedCollection = MutableStateFlow<BrowseCollection?>(null)
    val selectedCollection = mutableSelectedCollection.asStateFlow()
    private val mutableCollectionSongs = MutableStateFlow(ContentState<Track>())
    val collectionSongs = mutableCollectionSongs.asStateFlow()
    private val playingTracks = MutableStateFlow<List<Track>>(emptyList())
    private var searchJob: Job? = null
    private val searchGroupJobs = mutableMapOf<String, Job>()
    private val completedSearchGroups = mutableSetOf<String>()
    private val mutableSearchVideos = MutableStateFlow<List<Track>>(emptyList())
    val searchVideos = mutableSearchVideos.asStateFlow()
    private var searchGeneration = 0L
    private var searchPageJob: Job? = null
    private var searchCursor: com.raagmusic.app.data.youtube.SearchCursor? = null
    private val searchCursorsSeen = mutableSetOf<String>()
    private val mutableSearchMore = MutableStateFlow(false)
    val searchMore = mutableSearchMore.asStateFlow()
    private val mutableSearchPageLoading = MutableStateFlow(false)
    val searchPageLoading = mutableSearchPageLoading.asStateFlow()
    private val mutableSearchPageError = MutableStateFlow<String?>(null)
    val searchPageError = mutableSearchPageError.asStateFlow()
    private var homeJob: Job? = null
    private var homeShelfJob: Job? = null
    private val requestedHomeSections = linkedSetOf<String>()
    private val completedHomeSections = mutableSetOf<String>()
    private var collectionJob: Job? = null
    private var homeMood = "For you"
    private var homeGeneration = 0L
    private var homeLoadedAt = 0L
    private val mutableLinkLoading = MutableStateFlow(false)
    val linkLoading = mutableLinkLoading.asStateFlow()
    private var linkJob: Job? = null
    private val mutableBrowse = MutableStateFlow<BrowsePageState?>(null)
    val browse = mutableBrowse.asStateFlow()
    private var browseJob: Job? = null
    private val mutableArtist = MutableStateFlow<ArtistPageState?>(null)
    val artist = mutableArtist.asStateFlow()
    private var artistJob: Job? = null
    private val catalog = combine(
        playingTracks, library.map { it.savedTracks }.distinctUntilChanged(),
        features.map { it.queue.tracks }.distinctUntilChanged(), dailyMix, searchResults,
        homeSongs.map { it.items }.distinctUntilChanged(), homeTrending.map { it.items }.distinctUntilChanged(),
        homeNewReleases.map { it.items }.distinctUntilChanged(),
        browse.map { it?.let { page -> page.songs.items + page.trending.items + page.releases.items }.orEmpty() }.distinctUntilChanged(),
        collectionSongs.map { it.items }.distinctUntilChanged(), artist.map { it?.songs?.items.orEmpty() }.distinctUntilChanged(),
    ) { sources ->
        val tracks = sources.asSequence().flatten().distinctBy { it.id }.toList()
        TrackCatalog(tracks, tracks.associateBy { it.id })
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, TrackCatalog())
    private val homeSnapshot = com.raagmusic.app.data.HomeSnapshotStore(java.io.File(app.cacheDir, "home-discovery-v2.json"))
    private var homeRefreshRevision = 0
    private var refreshHomeShelves = false
    // Commit a ranked batch once. Loading other shelves or a playback tick must not reshuffle it.
    val quickPicks = combine(homeSongs, features.map { it.lessLikeIds }.distinctUntilChanged()) { home, blocked ->
        home.copy(items = home.items.filterNot { it.id in blocked }.take(20))
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ContentState(loading = true))
    private val mutablePlayerSuggestions = MutableStateFlow(ContentState<Track>())
    val playerSuggestions = mutablePlayerSuggestions.asStateFlow()
    private var suggestionsTrackId: String? = null
    private var suggestionsJob: Job? = null
    private val seenSuggestions = linkedSetOf<String>()
    fun resetPlayerSuggestions(track: Track) {
        if (suggestionsTrackId == track.id) return
        suggestionsJob?.cancel(); suggestionsTrackId = track.id; seenSuggestions.clear()
        mutablePlayerSuggestions.value = ContentState()
    }
    fun loadMorePlayerSuggestions(track: Track) {
        resetPlayerSuggestions(track)
        if (suggestionsJob?.isActive == true) return
        val previous = mutablePlayerSuggestions.value.items
        mutablePlayerSuggestions.value = ContentState(previous, loading = true)
        suggestionsJob = viewModelScope.launch {
            try {
                val seed = previous.lastOrNull() ?: track
                val excluded = seenSuggestions + playback.value.queueIds + track.id
                val batch = app.recommendations.next(seed, app.library.state.first(), app.features.state.first(), excluded)
                ensureActive()
                if (suggestionsTrackId != track.id) return@launch
                if (batch.isEmpty()) {
                    mutablePlayerSuggestions.value = ContentState(previous, error = "No fresh suggestions right now. Try again in a moment.")
                } else {
                    seenSuggestions += batch.map { it.id }
                    // Bound rendered history while retaining enough IDs to avoid nearby repeats.
                    while (seenSuggestions.size > 1_000) seenSuggestions.remove(seenSuggestions.first())
                    mutablePlayerSuggestions.value = ContentState((previous + batch).distinctBy { it.id }.takeLast(200))
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (suggestionsTrackId == track.id) mutablePlayerSuggestions.value = ContentState(previous, error = e.message ?: "Couldn't load suggestions. Try again.") }
        }
    }
    val trackIndex = catalog.map { it.byId }.distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())
    val currentTrack = combine(playback.map { it.trackId to it.metadataTrack }.distinctUntilChanged(), catalog) { (id, metadata), catalog ->
        catalog.byId[id] ?: metadata?.takeIf { it.id == id }
    }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val allTracks: List<Track> get() = catalog.value.tracks
    fun track(id: String): Track? = catalog.value.byId[id]

    private val mutablePortraits = MutableStateFlow<Map<String, String>>(emptyMap())
    val portraits = mutablePortraits.asStateFlow()
    private val portraitJobs = mutableMapOf<String, Job>()
    private val mutableSongCredits = MutableStateFlow<Map<String, List<String>>>(emptyMap())
    val songCredits = mutableSongCredits.asStateFlow()
    private val creditJobs = mutableMapOf<String, Job>()
    private val resolvedCredits = linkedSetOf<String>()
    private val listener = object : DownloadManager.Listener {
        override fun onDownloadChanged(manager: DownloadManager, download: Download, finalException: Exception?) { refreshDownloads() }
        override fun onDownloadRemoved(manager: DownloadManager, download: Download) { refreshDownloads() }
    }
    init {
        viewModelScope.launch { app.playbackMessages.collect { mutableMessages.emit(it) } }
        loadHome("For you")
        // Download cursors and progress polling never run on the main thread.
        viewModelScope.launch {
            downloadManager = withContext(Dispatchers.IO) { app.media.downloads }
            downloadManager?.addListener(listener)
            refreshDownloadsNow()
            if (downloads.value.values.any {
                    it.state == Download.STATE_QUEUED || it.state == Download.STATE_DOWNLOADING
                }) DownloadService.start(app, MusicDownloadService::class.java)
            for (ignored in downloadRefreshes) refreshDownloadsNow()
        }
        viewModelScope.launch { currentTrack.distinctUntilChangedBy { it?.id }.collect { track ->
            if (track == null) { lyricsJob?.cancel(); mutableLyrics.value = LyricsState() }
            else { requestSongArtists(track); loadLyrics(track) }
        } }
    }
    private fun refreshDownloads() { downloadRefreshes.trySend(Unit) }
    private suspend fun refreshDownloadsNow() = withContext(Dispatchers.IO) {
        val found = mutableMapOf<String, Download>()
        PlaybackTrace.mark("", "download_index_read")
        app.media.downloads.downloadIndex.getDownloads().use { while(it.moveToNext()) { val d = it.download; found[d.request.id] = d } }
        mutableDownloads.value = found
        withContext(Dispatchers.Main.immediate) {
            if (found.values.any { it.state == Download.STATE_DOWNLOADING }) {
                if (downloadPoll?.isActive != true) downloadPoll = viewModelScope.launch {
                    while (isActive) { delay(1_000); downloadRefreshes.trySend(Unit) }
                }
            } else { downloadPoll?.cancel(); downloadPoll = null }
        }
    }

    private fun edit(transform: (LibraryState) -> LibraryState) { viewModelScope.launch {
        try { app.library.update(transform) }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { mutableMessages.emit("Couldn't save your library. Please try again.") }
    } }
    private fun remember(track: Track) = edit { it.copy(savedTracks = rememberTrackMetadata(it.savedTracks, listOf(track))) }
    fun play(track: Track, queue: List<Track> = listOf(track), startPosition: Long = 0, ordered: Boolean = false, recommendationChain: Boolean = true,
        collectionToken: String? = null) {
        PlaybackTrace.mark(track.id, "ui_selection")
        app.cancelCollectionContinuation()
        searchGroupJobs.values.forEach { it.cancel() }
        // Persist every queue entry's metadata so changing the search results does not make
        // future songs disappear from the UI. These records contain IDs, never signed URLs.
        edit { it.copy(savedTracks = rememberTrackMetadata(it.savedTracks, queue + track)) }
        playingTracks.value = (queue + track).distinctBy { it.id }
        // Cancel lower-priority discovery so a Play request can acquire the extractor immediately.
        pauseHomeLoading()
        browseJob?.cancel()
        mutableBrowse.update { it?.copy(songs = it.songs.copy(loading = false), trending = it.trending.copy(loading = false),
            releases = it.releases.copy(loading = false), playlists = it.playlists.copy(loading = false), albums = it.albums.copy(loading = false)) }
        mixJob?.cancel()
        artistJob?.cancel()
        mutableArtist.value = mutableArtist.value?.let { it.copy(profileLoading = false, songs = pausedArtistSection(it.songs), albums = pausedArtistSection(it.albums), related = pausedArtistSection(it.related)) }
        searchJob?.cancel()
        searchKey = null
        mutableSearchLoading.value = false
        collectionJob?.cancel(); pageJob?.cancel()
        mutableCollectionSongs.value = mutableCollectionSongs.value.copy(loading = false)
        player.play(track, queue, startPosition, ordered, recommendationChain, collectionToken)
    }
    fun favorite(track: Track) { remember(track); edit { state ->
        val removing = track.id in state.favorites
        state.copy(favorites = if (removing) state.favorites - track.id else state.favorites + track.id,
            likedAt = if (removing) state.likedAt - track.id else state.likedAt + (track.id to System.currentTimeMillis()))
    } }
    fun createPlaylist(name: String, initialTrack: Track? = null) {
        if (name.trim().isNotEmpty()) edit { state -> state.copy(
            playlists = state.playlists + Playlist(UUID.randomUUID().toString(), name.trim().take(80), listOfNotNull(initialTrack?.id)),
            savedTracks = (state.savedTracks + listOfNotNull(initialTrack)).distinctBy { it.id },
        ) }
    }
    fun renamePlaylist(id: String, name: String) { if (name.isNotBlank()) edit { it.copy(playlists = it.playlists.map { p -> if(p.id == id) p.copy(name = name.trim().take(80)) else p }) } }
    fun deletePlaylist(id: String) {
        val index = library.value.playlists.indexOfFirst { it.id == id }
        val removed = library.value.playlists.getOrNull(index) ?: return
        edit { it.copy(playlists = it.playlists.filterNot { p -> p.id == id }) }
        offerUndo("Deleted ${removed.name}") {
            edit { state -> if (state.playlists.any { it.id == id }) state else {
                val playlists = state.playlists.toMutableList(); playlists.add(index.coerceAtMost(playlists.size), removed)
                state.copy(playlists = playlists)
            } }
        }
    }
    fun addToPlaylist(id: String, track: Track) { remember(track); edit { it.copy(playlists = it.playlists.map { p -> if(p.id == id) p.add(track.id) else p }) }; mutableMessages.tryEmit("Added to playlist") }
    fun removeFromPlaylist(id: String, trackId: String) = removePlaylistTracks(id, setOf(trackId))
    private fun offerUndo(label: String, action: () -> Unit) {
        undoAction = action; mutableUndoLabel.value = label; mutableUndoVersion.value++
    }
    fun undoRemoval() { val action = undoAction; undoAction = null; mutableUndoLabel.value = null; action?.invoke() }
    fun removePlaylistTracks(id: String, ids: Set<String>) {
        val original = library.value.playlists.find { it.id == id }?.trackIds.orEmpty()
        if (ids.isEmpty()) return
        edit { it.copy(playlists = it.playlists.map { p -> if (p.id == id) p.copy(trackIds = p.trackIds.filterNot { t -> t in ids }) else p }) }
        offerUndo("Removed ${ids.size} song(s)") {
            edit { it.copy(playlists = it.playlists.map { p -> if (p.id != id) p else {
                val restored = p.trackIds.toMutableList()
                original.forEachIndexed { index, t -> if (t in ids && t !in restored) restored.add(index.coerceAtMost(restored.size), t) }
                p.copy(trackIds = restored)
            } }) }
        }
    }
    fun movePlaylistTrack(id: String, fromId: String, toId: String) = edit { it.copy(playlists = it.playlists.map { p ->
        if (p.id != id) p else {
            val tracks = p.trackIds.toMutableList(); val from = tracks.indexOf(fromId); val to = tracks.indexOf(toId)
            if (from >= 0 && to >= 0) tracks.add(to, tracks.removeAt(from))
            p.copy(trackIds = tracks)
        }
    }) }
    fun enqueue(track: Track, next: Boolean = false) {
        remember(track)
        playingTracks.value = (playingTracks.value + track).distinctBy { it.id }
        player.enqueue(track, next) { mutableMessages.tryEmit(if (next) "Added to play next: ${track.title}" else "Added to queue: ${track.title}") }
    }
    fun removeQueue(indices: Set<Int>) {
        val original = playback.value.queueIds
        val removed = indices.sorted().mapNotNull { index -> original.getOrNull(index)?.let { id -> track(id)?.let { index to it } } }
        if (removed.isEmpty()) return
        indices.sortedDescending().forEach(player::removeQueue)
        offerUndo("Removed ${removed.size} song(s)") { removed.forEach { (index, track) -> player.insertQueue(index, track) } }
    }
    fun updateTheme(transform: (ThemePreferences) -> ThemePreferences) = editFeatures({ it.copy(theme = transform(it.theme)) })
    fun updateExperience(transform: (ExperiencePreferences) -> ExperiencePreferences) = editFeatures({ it.copy(experience = transform(it.experience).sanitized()) })
    fun removeRecentSearch(query: String) = editFeatures({ it.copy(discovery = it.discovery.copy(recentSearches = it.discovery.recentSearches.filterNot { saved -> saved.equals(query, true) })) })
    fun followArtist(name: String) = updateExperience { it.copy(followedArtists = if (name in it.followedArtists) it.followedArtists - name else it.followedArtists + name) }
    fun pinPlaylist(id: String) = updateExperience { it.copy(pinnedPlaylists = if (id in it.pinnedPlaylists) it.pinnedPlaylists - id else it.pinnedPlaylists + id) }
    fun editPlaylistDetails(id: String, description: String, folder: String, coverUri: String? = null) = edit { state ->
        state.copy(playlists = state.playlists.map { if (it.id == id) it.copy(description = description.trim().take(2000), folder = folder.trim().take(80), coverUri = coverUri ?: it.coverUri) else it })
    }
    fun importLocalAudio(uris: List<Uri>) { viewModelScope.launch {
        val imported = mutableListOf<Track>()
        var failed = 0
        withContext(Dispatchers.IO) {
            val importer = com.raagmusic.app.data.LocalAudioImporter(app)
            uris.take(500).forEach { uri -> try { imported += importer.read(uri) } catch (e: CancellationException) { throw e } catch (_: Exception) { failed++ } }
        }
        if (imported.isNotEmpty()) app.library.update { it.copy(savedTracks = rememberTrackMetadata(it.savedTracks, imported)) }
        mutableMessages.emit("Imported ${imported.size} audio files${if (failed > 0) " · $failed couldn't be read" else ""}")
    } }
    fun updatePreferences(transform: (ListeningPreferences) -> ListeningPreferences) { viewModelScope.launch {
        try { app.features.update { it.copy(preferences = transform(it.preferences)) } }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { mutableMessages.emit("Couldn't save listening preferences.") }
    } }
    private fun editFeatures(transform: (FeatureState) -> FeatureState, message: String? = null) { viewModelScope.launch {
        try { app.features.update(transform); message?.let { mutableMessages.emit(it) } }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { mutableMessages.emit("Couldn't save your listening preferences. Please try again.") }
    } }
    fun lessLike(track: Track) = editFeatures({ it.copy(lessLikeIds = it.lessLikeIds + track.id) }, "This song will stay out of your daily mixes")
    fun resetFeedback() = editFeatures({ it.copy(lessLikeIds = emptySet()) })
    fun clearHistory() { viewModelScope.launch {
        try {
            app.features.update { it.copy(history = emptyList(), historyEpoch = it.historyEpoch + 1) }
            app.library.update { it.copy(recentIds = emptyList()) }
            mutableMessages.emit("Listening history cleared")
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { mutableMessages.emit("Couldn't clear all history. Please try again.") }
    } }
    fun refreshDailyMix() {
        mixJob?.cancel()
        mixJob = viewModelScope.launch {
            mutableMixLoading.value = true
            try {
                val saved = app.library.state.first()
                val f = app.features.state.first()
                val ranked = com.raagmusic.app.domain.dailyMix(saved.savedTracks, saved, f, LocalDate.now())
                val artists = ranked.filter { it.source == Source.YOUTUBE }.map { it.artist }.distinct().take(3)
                val candidates = mutableListOf<Track>()
                for (artist in artists) candidates += youtube.searchTracks(artist).first().map { it.toLibraryTrack() }
                if (candidates.isEmpty()) candidates += mutableHomeSongs.value.items
                mixCandidates.value = candidates.distinctBy { it.id }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { mutableMessages.emit("Couldn't refresh your mix. Your saved songs are still available.") }
            finally { mutableMixLoading.value = false }
        }
    }
    fun loadLyrics(track: Track, retry: Boolean = false) {
        if (!retry && mutableLyrics.value.trackId == track.id && (mutableLyrics.value.loading || mutableLyrics.value.lyrics != null || mutableLyrics.value.error != null)) return
        lyricsJob?.cancel()
        mutableLyrics.value = LyricsState(track.id, loading = true)
        lyricsJob = viewModelScope.launch {
            try { mutableLyrics.value = LyricsState(track.id, lyrics = lyricsRepository.get(track, forceRefresh = retry)) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { mutableLyrics.value = LyricsState(track.id, error = e.message ?: "Couldn't load lyrics.") }
        }
    }

    fun exportLibrary(uri: Uri) { viewModelScope.launch {
        if (mutableBackupBusy.value) return@launch
        mutableBackupBusy.value = true
        try {
            withContext(Dispatchers.IO) {
                val raw = LibraryBackup.export(app.library.state.first())
                require(raw.toByteArray().size <= LibraryBackup.MAX_BYTES) { "Library exceeds the 5 MB backup limit." }
                requireNotNull(app.contentResolver.openOutputStream(uri, "wt")) { "Couldn't open the backup file." }.bufferedWriter(Charsets.UTF_8).use { it.write(raw) }
            }
            mutableMessages.emit("Library backup saved")
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { mutableMessages.emit(e.message ?: "Couldn't export your library.") }
        finally { mutableBackupBusy.value = false }
    } }
    fun importLibrary(uri: Uri) { viewModelScope.launch {
        if (mutableBackupBusy.value) return@launch
        mutableBackupBusy.value = true
        try {
            val imported = withContext(Dispatchers.IO) {
                val bytes = requireNotNull(app.contentResolver.openInputStream(uri)) { "Couldn't open the backup." }.use { input ->
                    val output = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    var n = input.read(buffer)
                    while (n >= 0) { require(output.size() + n <= LibraryBackup.MAX_BYTES) { "Backup is too large (maximum 5 MB)." }; output.write(buffer, 0, n); n = input.read(buffer) }
                    output.toByteArray()
                }
                LibraryBackup.parse(bytes.toString(Charsets.UTF_8))
            }
            app.library.update { LibraryBackup.merge(it, imported) }
            mutableMessages.emit("Restored ${imported.playlists.size} playlists and ${imported.favorites.size} favorites")
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { mutableMessages.emit(e.message ?: "Invalid backup. Your library has not changed.") }
        finally { mutableBackupBusy.value = false }
    } }

    fun openSharedLink(text: String) {
        val link = SharedMusicLink.parse(text)
        if (link == null) { mutableMessages.tryEmit("Share a supported YouTube song, album or playlist link."); return }
        linkJob?.cancel(); pauseHomeLoading(); clearSearch(); artistJob?.cancel()
        linkJob = viewModelScope.launch {
            mutableLinkLoading.value = true
            try {
                when (link) {
                    is SharedMusicLink.Song -> {
                        val track = youtube.getSong(link.id).toLibraryTrack()
                        closeCollection(); closeArtist(); play(track, startPosition = link.positionMs)
                    }
                    is SharedMusicLink.Collection -> {
                        val url = if (link.id.startsWith("MPRE")) "https://music.youtube.com/browse/${link.id}" else "https://www.youtube.com/playlist?list=${link.id}"
                        val collection = youtube.getCollection(url, if (link.album) CollectionKind.ALBUM else CollectionKind.PLAYLIST)
                        openCollection(collection)
                    }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { mutableMessages.emit(e.message ?: "Couldn't open that music link. Try again.") }
            finally { mutableLinkLoading.value = false }
        }
    }
    fun openBrowse(title: String, query: String, mood: Boolean = false, refresh: Boolean = false) {
        clearSearch(); closeArtist(); pauseHomeLoading(); browseJob?.cancel()
        val previous = mutableBrowse.value?.takeIf { it.title == title }
        fun <T> starting(section: ContentState<T>?) = ContentState(section?.items.orEmpty(), loading = true)
        mutableBrowse.value = BrowsePageState(title, query, mood, starting(previous?.songs), starting(previous?.trending),
            starting(previous?.releases), starting(previous?.playlists), starting(previous?.albums))
        browseJob = viewModelScope.launch {
            if (refresh) youtube.clearMetadataCache()
            val place = homeRegion.value?.queryName.orEmpty()
            fun List<Track>.tagged() = map { if (mood) it.copy(mood = title) else it.copy(genre = title) }
            val songs = homeContent(previous?.songs ?: ContentState()) {
                youtube.searchTracks("$query $place").first().map { it.toLibraryTrack() }.musicPicks().tagged()
            }
            mutableBrowse.update { it?.copy(songs = songs) }
            val playlists = homeContent(previous?.playlists ?: ContentState()) {
                youtube.searchCollections("$query playlists $place", CollectionKind.PLAYLIST).first().take(12)
            }
            mutableBrowse.update { it?.copy(playlists = playlists) }
            val albums = homeContent(previous?.albums ?: ContentState()) {
                youtube.searchCollections("$query albums", CollectionKind.ALBUM).first().take(12)
            }
            mutableBrowse.update { it?.copy(albums = albums) }
            val trending = homeContent(previous?.trending ?: ContentState()) {
                youtube.searchTracks("popular $query $place").first().map { it.toLibraryTrack() }.musicPicks().tagged()
            }
            mutableBrowse.update { it?.copy(trending = trending) }
            val releases = homeContent(previous?.releases ?: ContentState()) {
                youtube.searchTracks("new $query ${LocalDate.now().year} $place").first().map { it.toLibraryTrack() }.musicPicks().tagged()
            }
            mutableBrowse.update { it?.copy(releases = releases) }
        }
    }
    fun retryBrowse() { browse.value?.let { openBrowse(it.title, it.query, it.mood, refresh = true) } }
    fun closeBrowse() { browseJob?.cancel(); mutableBrowse.value = null }

    private fun <T> pausedArtistSection(state: ContentState<T>): ContentState<T> = state.copy(loading = false,
        error = state.error ?: if (state.loading && state.items.isEmpty()) "This section is paused while your song starts." else null)
    fun openArtist(name: String, seed: Track? = null, profile: com.raagmusic.app.data.youtube.ArtistProfile? = null) {
        if (name.isBlank()) return
        closeCollection()
        pauseHomeLoading(); clearSearch(); artistJob?.cancel()
        val identityBound = seed?.source == Source.YOUTUBE || !profile?.channelUrl.isNullOrBlank()
        val cachedProfile = profile ?: if (identityBound) null else youtube.cachedArtistProfile(name)
        mutableArtist.value = ArtistPageState(name, identityBound = identityBound, imageUrl = cachedProfile?.imageUrl.orEmpty(), profile = cachedProfile, profileLoading = true, songs = ContentState(loading = true), albums = ContentState(loading = true), related = ContentState(loading = true))
        artistJob = viewModelScope.launch {
            try {
                val portrait = cachedProfile ?: if (seed?.source == Source.YOUTUBE) youtube.getSongArtistProfile(seed.uri, name) else youtube.getArtistProfileByName(name)
                mutableArtist.value = mutableArtist.value?.copy(imageUrl = portrait?.imageUrl.orEmpty(), profile = portrait,
                    profileError = if (identityBound && portrait == null) "The artist link for this song is unavailable." else null)
                portrait?.let { mutablePortraits.update { values -> values + (name to it.imageUrl) } }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { }
            try {
                val details = mutableArtist.value?.profile?.let { youtube.getArtistDetails(it) }
                mutableArtist.value = mutableArtist.value?.copy(profile = details, profileLoading = false,
                    imageUrl = details?.imageUrl.orEmpty())
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { mutableArtist.value = mutableArtist.value?.copy(profileLoading = false, profileError = "Artist details aren't available right now.") }
            val selectedProfile = mutableArtist.value?.profile
            val songs = content {
                if (selectedProfile != null) youtube.getArtistSongs(selectedProfile).map { it.toLibraryTrack() }
                else if (identityBound) emptyList() else youtube.searchTracks(name).first().map { it.toLibraryTrack() }
            }
            mutableArtist.value = mutableArtist.value?.copy(songs = songs)
            val albums = if (selectedProfile != null) ContentState(selectedProfile.albums)
                else if (identityBound) ContentState<BrowseCollection>() else content { youtube.searchCollections(name, CollectionKind.ALBUM).first() }
            mutableArtist.value = mutableArtist.value?.copy(albums = albums)
            val related = content {
                val anchor = seed?.takeIf { it.source == Source.YOUTUBE } ?: songs.items.firstOrNull()
                if (anchor == null) emptyList() else youtube.getRelatedArtistNames(anchor.id)
                    .filterNot { it.equals(name, true) }.distinctBy { it.lowercase(java.util.Locale.ROOT) }.take(10)
            }
            related.items.forEach(::requestPortrait)
            mutableArtist.value = mutableArtist.value?.copy(related = related)
        }
    }
    fun cachedPortrait(name: String) = youtube.cachedArtistProfile(name)?.imageUrl.orEmpty()
    fun cachedArtists(track: Track): List<String> = if (track.source == Source.YOUTUBE)
        youtube.cachedSongArtists(track.id)?.takeIf { it.isNotEmpty() } ?: com.raagmusic.app.data.youtube.ArtistNames.fallback(track.artist) else com.raagmusic.app.data.youtube.ArtistNames.fallback(track.artist)
    fun requestPortrait(name: String) {
        if (!mutablePortraits.value[name].isNullOrBlank() || portraitJobs[name]?.isActive == true) return
        val cached = cachedPortrait(name)
        fun prefetch(url: String) {
            if (url.isNotBlank()) app.imageLoader.enqueue(ImageRequest.Builder(app)
                .data(ArtworkUrls.highResolution(url, 512)).size(336).build())
        }
        if (cached.isNotBlank()) { prefetch(cached); mutablePortraits.update { it + (name to cached) }; return }
        portraitJobs[name] = viewModelScope.launch {
            try {
                while (playback.value.buffering) delay(250)
                val url = youtube.getArtistProfileByName(name)?.imageUrl.orEmpty()
                prefetch(url)
                mutablePortraits.update { (it + (name to url)).entries.toList().takeLast(128).associate { entry -> entry.key to entry.value } }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { mutablePortraits.update { it + (name to "") } }
            finally { portraitJobs.remove(name) }
        }
    }
    fun requestSongArtists(track: Track) {
        if (track.id in resolvedCredits || creditJobs[track.id]?.isActive == true) return
        val cached = if (track.source == Source.YOUTUBE) youtube.cachedSongArtists(track.id) else com.raagmusic.app.data.youtube.ArtistNames.fallback(track.artist)
        if (!cached.isNullOrEmpty()) {
            mutableSongCredits.update { it + (track.id to cached) }; resolvedCredits += track.id; return
        }
        val fallback = cachedArtists(track)
        if (fallback.isNotEmpty()) mutableSongCredits.update { it + (track.id to fallback) }
        if (track.source != Source.YOUTUBE) return
        creditJobs[track.id] = viewModelScope.launch {
            try {
                while (playback.value.buffering) delay(250)
                val names = youtube.getSongArtists(track.id)
                if (names.isNotEmpty()) mutableSongCredits.update { values -> (values + (track.id to names)).entries.toList().takeLast(128).associate { it.key to it.value } }
                resolvedCredits += track.id
                while (resolvedCredits.size > 128) resolvedCredits.remove(resolvedCredits.first())
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { /* Retain the byline until this song's exact credits are available. */ }
            finally { creditJobs.remove(track.id) }
        }
    }
    suspend fun songArtists(track: Track): List<String> = cachedArtists(track).ifEmpty { if (track.source == Source.YOUTUBE) youtube.getSongArtists(track.id) else emptyList() }
    fun closeArtist() { artistJob?.cancel(); mutableArtist.value = null }
    fun saveTracksAsPlaylist(name: String, tracks: List<Track>) {
        if (tracks.isEmpty()) return
        edit { it.copy(playlists = it.playlists + Playlist(UUID.randomUUID().toString(), name.take(80), tracks.map { t -> t.id }.distinct()),
            savedTracks = (it.savedTracks + tracks).distinctBy { t -> t.id }) }
        mutableMessages.tryEmit("Created $name")
    }

    fun search(query: String, force: Boolean = false) {
        mutableSearchQuery.value = query
        val cleaned = query.trim().replace(Regex("\\s+"), " ").take(120)
        val kind = mutableSearchKind.value
        val key = cleaned.lowercase(java.util.Locale.ROOT) to kind
        if (!force && key == searchKey) return
        searchKey = key
        val generation = ++searchGeneration
        searchJob?.cancel()
        resetSearchPages()
        mutableSearchError.value = null
        mutableSearchSuggestions.value = emptyList()
        mutableSearchOrder.value = emptyList()
        mutableSearchArtists.value = emptyList()
        mutableSearchVideos.value = emptyList()
        mutableSearchCollections.value = emptyList()
        mutableSearchResults.value = emptyList()
        mutableSearchLoading.value = cleaned.length >= 2
        if (cleaned.length < 2) {
            mutableSearchResults.value = emptyList()
            mutableSearchCollections.value = emptyList()
            return
        }
        pauseHomeLoading()
        searchJob = viewModelScope.launch {
            val local = withContext(Dispatchers.Default) {
                val tracks = (library.value.savedTracks + allTracks).distinctBy { it.id }
                val matches = if (kind in listOf("All", "Songs")) rankSearchMatches(
                    tracks.filter { searchMatchScore(cleaned, it.title, it.artist) > 0 }, cleaned, { it.title }, { it.artist }).take(20) else emptyList()
                matches to rankedSearchSuggestions(cleaned, discoveryPreferences.value.recentSearches, tracks,
                    experience.value.followedArtists)
            }
            if (generation != searchGeneration) return@launch
            mutableSearchResults.value = local.first
            mutableSearchSuggestions.value = local.second
            val cached = if (force) null else youtube.cachedSearchPage(cleaned, kind)
            if (cached != null) {
                mutableSearchResults.value = withContext(Dispatchers.Default) {
                    rankSearchMatches(cached.tracks.map { it.toLibraryTrack() }, cleaned, { it.title }, { it.artist })
                }
                mutableSearchOrder.value = cached.order
                mutableSearchVideos.value = cached.videos.map { it.toLibraryTrack() }
                mutableSearchArtists.value = cached.artists
                mutableSearchCollections.value = cached.collections
                searchCursor = cached.next
                mutableSearchMore.value = cached.next != null
                mutableSearchLoading.value = false
            }
            delay(160)
            launch {
            // Suggestions are optional: a provider failure must not prevent song results.
            try {
                val suggestions = withTimeoutOrNull(1_500) { youtube.searchSuggestions(cleaned) }.orEmpty()
                val ranked = withContext(Dispatchers.Default) { rankedSearchSuggestions(cleaned,
                    discoveryPreferences.value.recentSearches, allTracks, experience.value.followedArtists, suggestions) }
                if (generation == searchGeneration) mutableSearchSuggestions.value = ranked
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { /* Local history still supplies suggestions. */ }
            }
            try {
                val page = withTimeoutOrNull(6_000) { youtube.searchPage(cleaned, kind, foreground = true, refresh = force) }
                    ?: throw java.net.SocketTimeoutException("Search is taking too long. Your local matches are still available. Try again.")
                val ranked = withContext(Dispatchers.Default) {
                    rankSearchMatches(page.tracks.map { it.toLibraryTrack() }, cleaned, { it.title }, { it.artist })
                }
                if (generation == searchGeneration) {
                    mutableSearchOrder.value = page.order
                    mutableSearchVideos.value = page.videos.map { it.toLibraryTrack() }
                    mutableSearchResults.value = ranked
                    mutableSearchArtists.value = rankSearchMatches(page.artists, cleaned, { it.name })
                    mutableSearchCollections.value = rankSearchMatches(page.collections, cleaned, { it.title }, { it.artist })
                    searchCursor = page.next
                    mutableSearchMore.value = page.next != null
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (generation == searchGeneration) mutableSearchError.value = e.message ?: "Search failed. Try again." }
            finally { if (generation == searchGeneration) mutableSearchLoading.value = false }
            if (generation == searchGeneration && mutableSearchError.value == null &&
                (mutableSearchResults.value.isNotEmpty() || mutableSearchCollections.value.isNotEmpty() || mutableSearchArtists.value.isNotEmpty() || mutableSearchVideos.value.isNotEmpty())) {
                delay(1_200)
                if (generation == searchGeneration) rememberSearch(cleaned)
            }
        }
    }

    /** Optional All groups load only as their section is reached, never for every keystroke. */
    fun requestSearchGroup(group: String) {
        if (mutableSearchKind.value != "All" || mutableSearchQuery.value.trim().length < 2 ||
            group !in setOf("Artists", "Videos", "Albums", "Playlists") || group in completedSearchGroups ||
            searchGroupJobs[group]?.isActive == true) return
        val generation = searchGeneration
        val query = mutableSearchQuery.value.trim().take(120)
        searchGroupJobs[group] = viewModelScope.launch {
            try {
                val page = withTimeout(4_000) { youtube.searchPage(query, group, foreground = true) }
                if (generation != searchGeneration) return@launch
                when (group) {
                    "Artists" -> mutableSearchArtists.value = rankSearchMatches(page.artists, query, { it.name }).take(6)
                    "Videos" -> mutableSearchVideos.value = rankSearchMatches(page.tracks.map { it.toLibraryTrack() }, query, { it.title }, { it.artist }).take(4)
                    else -> mutableSearchCollections.value = (mutableSearchCollections.value +
                        rankSearchMatches(page.collections, query, { it.title }, { it.artist }).take(4))
                        .distinctBy { "${it.kind}:${it.id}" }
                }
                completedSearchGroups += group
            } catch (e: TimeoutCancellationException) { /* The dedicated tab remains retryable. */ }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { /* Optional groups never replace playable song matches with an error. */ }
        }
    }

    private fun resetSearchPages() {
        searchGroupJobs.values.forEach { it.cancel() }
        searchGroupJobs.clear(); completedSearchGroups.clear()
        searchPageJob?.cancel()
        searchCursor = null
        searchCursorsSeen.clear()
        mutableSearchMore.value = false
        mutableSearchPageLoading.value = false
        mutableSearchPageError.value = null
    }

    fun loadMoreSearch() {
        val cursor = searchCursor ?: return
        if (mutableSearchLoading.value || mutableSearchPageLoading.value) return
        val generation = searchGeneration
        val query = mutableSearchQuery.value
        val kind = mutableSearchKind.value
        mutableSearchPageLoading.value = true
        mutableSearchPageError.value = null
        searchPageJob = viewModelScope.launch {
            try {
                val page = youtube.searchPage(query, kind, cursor, foreground = true)
                if (generation != searchGeneration) return@launch
                val addsResults = page.tracks.any { track -> mutableSearchResults.value.none { it.id == track.id } } ||
                    page.artists.any { artist -> mutableSearchArtists.value.none { it.channelUrl == artist.channelUrl } } ||
                    page.collections.any { collection -> mutableSearchCollections.value.none { it.kind == collection.kind && it.id == collection.id } }
                mutableSearchResults.value = (mutableSearchResults.value + page.tracks.map { it.toLibraryTrack() }).distinctBy { it.id }
                mutableSearchArtists.value = (mutableSearchArtists.value + page.artists).distinctBy { it.channelUrl }
                mutableSearchCollections.value = (mutableSearchCollections.value + page.collections).distinctBy { "${it.kind}:${it.id}" }
                searchCursorsSeen += cursor.fingerprint
                searchCursor = page.next?.takeIf { addsResults }?.takeUnless { it.fingerprint in searchCursorsSeen }
                mutableSearchMore.value = searchCursor != null
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (generation == searchGeneration) mutableSearchPageError.value = e.message ?: "Couldn't load more results." }
            finally { if (generation == searchGeneration) mutableSearchPageLoading.value = false }
        }
    }

    fun clearSearch() {
        ++searchGeneration
        searchJob?.cancel()
        resetSearchPages()
        searchKey = null
        mutableSearchQuery.value = ""
        mutableSearchKind.value = "All"
        mutableSearchSuggestions.value = emptyList()
        mutableSearchOrder.value = emptyList()
        mutableSearchArtists.value = emptyList()
        mutableSearchVideos.value = emptyList()
        mutableSearchResults.value = emptyList()
        mutableSearchCollections.value = emptyList()
        mutableSearchError.value = null
        mutableSearchLoading.value = false
    }

    fun playSearchVideo(track: Track) {
        if (searchLoading.value || searchVideos.value.none { it.id == track.id }) return
        rememberSearch()
        play(track, recommendationChain = true)
    }

    fun playSearchResult(track: Track) {
        if (searchResults.value.none { it.id == track.id }) return
        rememberSearch()
        // Search selects the seed only; the service builds successors from each song.
        play(track, listOf(track), ordered = true, recommendationChain = true)
    }

    fun saveListenerName(name: String) = editFeatures({
        it.copy(profile = ListenerProfile(name, namePromptSeen = true).sanitized())
    })

    val profile = app.features.observed.map { it.profile }.distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun playFromSearch(query: String) {
        if (query.isBlank()) { player.toggle(); return }
        viewModelScope.launch {
            try {
                val local = app.library.state.first().savedTracks.filter { it.title.contains(query, true) || it.artist.contains(query, true) }
                val tracks = local.ifEmpty { youtube.searchTracks(query).first().map { it.toLibraryTrack() } }
                tracks.firstOrNull()?.let { play(it, recommendationChain = true, ordered = true) } ?: mutableMessages.emit("No songs found for that search")
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { mutableMessages.emit("Couldn't play that search. Please try again.") }
        }
    }

    fun changeSearchKind(kind: String) {
        if (kind == mutableSearchKind.value) return
        mutableSearchResults.value = emptyList()
        mutableSearchCollections.value = emptyList()
        mutableSearchArtists.value = emptyList()
        mutableSearchVideos.value = emptyList()
        mutableSearchKind.value = kind
        search(mutableSearchQuery.value)
    }

    private var homeInterrupted = false
    fun pauseHomeLoading() {
        homeInterrupted = homeInterrupted || homeJob?.isActive == true
        homeGeneration++
        homeJob?.cancel()
        homeShelfJob?.cancel()
        mutableHomeRefreshing.value = false
        mutableHomeTrending.value = mutableHomeTrending.value.copy(loading = false)
        mutableHomeNewReleases.value = mutableHomeNewReleases.value.copy(loading = false)
        mutableHomeSongs.value = mutableHomeSongs.value.copy(loading = false)
        mutableHomePlaylists.value = mutableHomePlaylists.value.copy(loading = false)
        mutableHomeAlbums.value = mutableHomeAlbums.value.copy(loading = false)
    }

    fun resumeHomeIfNeeded() {
        if (homeJob?.isActive == true || homeShelfJob?.isActive == true) return
        if (homeInterrupted || (mutableHomeSongs.value.items.isEmpty() && mutableHomeSongs.value.error == null) ||
            (homeLoadedAt > 0 && android.os.SystemClock.elapsedRealtime() - homeLoadedAt >= 5 * 60_000)) loadHome()
        else if (requestedHomeSections.any { it !in completedHomeSections }) homeShelfJob = viewModelScope.launch { loadRequestedHomeSections() }
    }
    fun rememberSearch(query: String = searchQuery.value) = editFeatures({ it.copy(discovery = it.discovery.withSearch(query)) })
    fun searchFromHome(query: String) {
        mutableSearchKind.value = "All"
        search(query)
        if (query.isNotBlank()) rememberSearch(query)
    }
    fun clearRecentSearches() = editFeatures({ it.copy(discovery = it.discovery.copy(recentSearches = emptyList())) })
    fun selectHomeCountry(code: String?) {
        if (code != null && validCountryCode(code) == null) return
        viewModelScope.launch {
            try {
                app.features.update { it.copy(discovery = it.discovery.copy(countryOverride = validCountryCode(code))) }
                loadHome(refresh = true)
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { mutableMessages.emit("Couldn't save your country. Please try again.") }
        }
    }

    fun loadHome(mood: String = homeMood, refresh: Boolean = false) {
        if (refresh && mutableHomeRefreshing.value) return
        homeInterrupted = false
        homeJob?.cancel()
        homeShelfJob?.cancel()
        refreshHomeShelves = refresh
        completedHomeSections.clear()
        val generation = ++homeGeneration
        val changed = mood != homeMood
        if (changed) homeRefreshRevision = 0
        homeMood = mood
        mutableHomeRefreshing.value = refresh
        fun <T> starting(state: ContentState<T>) = ContentState(if (changed) emptyList() else state.items, loading = true)
        mutableHomeTrending.value = starting(mutableHomeTrending.value)
        mutableHomeNewReleases.value = starting(mutableHomeNewReleases.value)
        mutableHomeSongs.value = starting(mutableHomeSongs.value)
        mutableHomePlaylists.value = starting(mutableHomePlaylists.value)
        mutableHomeAlbums.value = starting(mutableHomeAlbums.value)
        homeJob = viewModelScope.launch {
            try {
                val saved = app.library.state.first()
                val feedback = app.features.state.first()
                val seeds = withContext(Dispatchers.Default) { quickPickSeeds(saved, feedback) }
                val preferred = withContext(Dispatchers.Default) { preferredArtists(saved, feedback) }
                val taste = (preferred + seeds.map { it.id } + saved.favorites.sorted() + feedback.lessLikeIds.sorted()).joinToString("|").hashCode().toString()
                val settings = feedback.discovery
                val region = settings.countryOverride?.let { MusicRegion(it, CountrySource.MANUAL) }
                    ?: withContext(Dispatchers.IO) { com.raagmusic.app.data.DeviceCountryResolver.detect(app) }
                if (mutableHomeRegion.value != null && region.code != mutableHomeRegion.value?.code) {
                    mutableHomeTrending.value = ContentState(loading = true)
                    mutableHomeNewReleases.value = ContentState(loading = true)
                    mutableHomeSongs.value = ContentState(loading = true)
                    mutableHomePlaylists.value = ContentState(loading = true)
                    mutableHomeAlbums.value = ContentState(loading = true)
                    mixCandidates.value = emptyList()
                }
                mutableHomeRegion.value = region
                if (mutableHomeSongs.value.items.isEmpty()) {
                    val cached = withContext(Dispatchers.IO) { homeSnapshot.read(region.providerCode, mood, taste) }
                    val allowed = cached.filterNot { it.id in feedback.lessLikeIds }
                    if (allowed.isNotEmpty()) {
                        mutableHomeSongs.value = ContentState(allowed, loading = true)
                        mixCandidates.value = allowed
                    }
                }
                if (mutableHomeSongs.value.items.isEmpty() && mood.equals("For you", true)) {
                    val local = withContext(Dispatchers.Default) { rankRecommendations(saved.savedTracks,
                        null, saved, feedback, limit = 20) }
                    if (local.isNotEmpty()) mutableHomeSongs.value = ContentState(local, loading = true)
                }
                withContext(Dispatchers.IO) { youtube.setCountry(region.providerCode) }
                val queries = homeQueries(region, mood, LocalDate.now())
                val savedById = withContext(Dispatchers.Default) { saved.savedTracks.associateBy { it.id } }
                val recentArtist = saved.recentIds.firstNotNullOfOrNull { id ->
                    savedById[id]?.takeIf { it.source == Source.YOUTUBE }?.artist
                }
                val retrievalArtists = (preferred + feedback.experience.followedArtists.sorted())
                    .distinct().ifEmpty { listOfNotNull(recentArtist) }
                val personalCandidates = if (mood.equals("For you", true)) withContext(Dispatchers.Default) {
                    val positiveIds = saved.favorites + feedback.history.filter { it.milliseconds >= 30_000 }.map { it.trackId }
                    val familiar = saved.savedTracks.filter { it.id in positiveIds && it.id !in feedback.lessLikeIds }.takeLast(40)
                    val related = familiar.filter { it.source == Source.YOUTUBE }.takeLast(3)
                        .flatMap { youtube.cachedRelatedSongs(it.id) }.map { it.toLibraryTrack() }
                    (related + familiar).distinctBy { it.id }
                } else emptyList()
                val timeQuery = timeOfDayMusicQuery(region, java.time.LocalDateTime.now().hour)
                val genre = saved.savedTracks.filter { it.id in saved.favorites && it.genre.isNotBlank() }
                    .groupingBy { it.genre }.eachCount().maxByOrNull { it.value }?.key
                val revision = homeRefreshRevision + if (refresh) 1 else 0
                val day = LocalDate.now()
                val discoveryQueries = listOf(queries.trending, timeQuery, queries.newReleases,
                    "${regionalAlbumQuery(region)} songs ${region.queryName}")
                val turn = ((day.toEpochDay() + revision) % discoveryQueries.size).toInt()
                val quickQueries = if (!mood.equals("For you", true)) listOf(queries.trending) else buildList {
                    if (retrievalArtists.isNotEmpty()) {
                        val rotated = retrievalArtists.drop(revision % retrievalArtists.size) + retrievalArtists.take(revision % retrievalArtists.size)
                        rotated.take(3).forEach { add("$it songs") }
                    } else {
                        add(discoveryQueries[turn])
                        add(genre?.let { "$it songs ${region.queryName}" } ?: discoveryQueries[(turn + 1) % discoveryQueries.size])
                    }
                }.distinct()
                val previous = if (refresh && !changed) mutableHomeSongs.value.items.map { it.id }.toSet() else emptySet()
                val loaded = homeContent(mutableHomeSongs.value) {
                    val candidates = mutableListOf<Track>()
                    val relatedIds = mutableSetOf<String>()
                    var failure: Exception? = null
                    // The overall budget includes lock waits, retries and continuation requests.
                    // Keep partial results if a later source is slow.
                    withTimeoutOrNull(8_000) {
                        if (mood.equals("For you", true)) withTimeoutOrNull(3_500) {
                            for (seed in seeds.take(2)) {
                                try {
                                    val related = youtube.getRelatedSongs(seed.id).map { it.toLibraryTrack() }
                                    candidates += related
                                    relatedIds += related.map { it.id }
                                } catch (e: CancellationException) { throw e }
                                catch (e: Exception) { failure = e }
                            }
                        }
                        for ((queryIndex, query) in quickQueries.withIndex()) {
                            try {
                                val page = youtube.searchPage(query, "Songs", refresh = refresh)
                                candidates += page.tracks.map { it.toLibraryTrack() }
                                // Follow sparse pages only; source mixing comes before filling a single source.
                                var cursor = page.next
                                val seen = mutableSetOf<String>()
                                var pages = 0
                                while (quickQueries.size == 1 && candidates.distinctBy { it.id }.size < 20 && cursor != null &&
                                    seen.add(cursor.fingerprint) && pages++ < 2) {
                                    val next = youtube.searchPage(query, "Songs", cursor)
                                    candidates += next.tracks.map { it.toLibraryTrack() }
                                    cursor = next.next
                                }
                            } catch (e: CancellationException) { throw e }
                            catch (e: Exception) { failure = e }
                            // Anonymous initial discovery may already be diverse. Personalization and pulls mix sources.
                            if (!refresh && preferred.isEmpty() && !needsRecommendationVariety(candidates)) break
                            if (queryIndex == 0 && quickQueries.size > 2) {
                                // Bootstrap the provider once, then retrieve the two independent
                                // discovery sources concurrently. Results arrive on Main and survive
                                // a timeout of the other source; playback cancels the whole home job.
                                val pages = arrayOfNulls<List<Track>>(2)
                                try { coroutineScope {
                                    quickQueries.drop(1).take(2).mapIndexed { index, discoveryQuery -> launch {
                                        try {
                                            val next = if (index == 0) youtube.searchPage(discoveryQuery, "Songs", refresh = refresh)
                                                else youtube.searchDiscoveryPage(discoveryQuery, refresh)
                                            pages[index] = next.tracks.map { it.toLibraryTrack() }
                                        } catch (e: CancellationException) { throw e }
                                        catch (e: Exception) { failure = e }
                                    } }.joinAll()
                                } } finally { pages.filterNotNull().forEach { candidates += it } }
                                break
                            }
                        }
                    }
                    if (candidates.isEmpty() && personalCandidates.isEmpty()) {
                        throw failure ?: java.io.IOException("Discovery is taking too long. Pull to retry.")
                    }
                    val now = LocalDateTime.now()
                    withContext(Dispatchers.Default) {
                        rankRecommendations(candidates + personalCandidates, null, saved, feedback, now.toLocalDate(), now.hour,
                            relatedIds = relatedIds, limit = 20, previousIds = previous, variation = revision)
                    }
                }
                if (homeGeneration != generation) return@launch
                mutableHomeSongs.value = loaded
                // End the refresh as soon as this single batch is ready, before lower shelves load.
                mutableHomeRefreshing.value = false
                if (loaded.error == null) {
                    homeLoadedAt = android.os.SystemClock.elapsedRealtime()
                    homeRefreshRevision = revision
                    mixCandidates.value = loaded.items
                    withContext(Dispatchers.IO) { homeSnapshot.write(region.providerCode, mood, loaded.items, taste) }
                }
                loadRequestedHomeSections()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                val message = e.message ?: "Couldn't load discovery. Pull down to try again."
                mutableHomeTrending.value = mutableHomeTrending.value.copy(loading = false, error = message)
                mutableHomeNewReleases.value = mutableHomeNewReleases.value.copy(loading = false, error = message)
                mutableHomeSongs.value = mutableHomeSongs.value.copy(loading = false, error = message)
                mutableHomePlaylists.value = mutableHomePlaylists.value.copy(loading = false, error = message)
                mutableHomeAlbums.value = mutableHomeAlbums.value.copy(loading = false, error = message)
            } finally { if (homeGeneration == generation) mutableHomeRefreshing.value = false }
        }
    }

    fun requestHomeSection(section: String) {
        val key = when (section) { "spotlight", "artists" -> "trending"; else -> section }
        if (key !in setOf("trending", "releases", "playlists", "albums")) return
        requestedHomeSections.add(key)
        if (key in completedHomeSections) return
        if (homeJob?.isActive != true && homeShelfJob?.isActive != true) homeShelfJob = viewModelScope.launch { loadRequestedHomeSections() }
    }
    private suspend fun loadRequestedHomeSections() {
        val region = homeRegion.value ?: return
        val queries = homeQueries(region, homeMood, LocalDate.now())
        // A snapshot is retried if another shelf becomes visible during a provider request.
        while (true) {
            val key = requestedHomeSections.firstOrNull { it !in completedHomeSections } ?: break
            when (key) {
                "trending" -> mutableHomeTrending.value = homeContent(mutableHomeTrending.value) { youtube.searchTracks(queries.trending, refreshHomeShelves).first().map { it.toLibraryTrack() }.musicPicks() }
                "releases" -> mutableHomeNewReleases.value = homeContent(mutableHomeNewReleases.value) { youtube.searchTracks(queries.newReleases, refreshHomeShelves).first().map { it.toLibraryTrack() }.musicPicks() }
                "playlists" -> mutableHomePlaylists.value = homeContent(mutableHomePlaylists.value) { youtube.searchCollections(queries.playlists, CollectionKind.PLAYLIST, refreshHomeShelves).first().take(12) }
                "albums" -> mutableHomeAlbums.value = homeContent(mutableHomeAlbums.value) { youtube.searchCollections(regionalAlbumQuery(region), CollectionKind.ALBUM, refreshHomeShelves).first().take(12) }
            }
            completedHomeSections += key
        }
        mixCandidates.value = (mixCandidates.value + mutableHomeTrending.value.items + mutableHomeNewReleases.value.items).distinctBy { it.id }
    }

    private fun List<Track>.musicPicks() = distinctBy { it.id }
        .filter { it.durationSeconds == 0L || it.durationSeconds in 30..720 }.take(20)

    private suspend fun <T> homeContent(previous: ContentState<T>, load: suspend () -> List<T>): ContentState<T> {
        val loaded = withTimeoutOrNull(10_000) { content(load) }
            ?: ContentState(error = "This section is taking too long. Pull to retry.")
        return if (loaded.error != null) loaded.copy(items = previous.items) else loaded
    }

    private suspend fun <T> content(load: suspend () -> List<T>): ContentState<T> = try {
        ContentState(load())
    } catch (e: CancellationException) { throw e }
    catch (e: Exception) { ContentState(error = e.message ?: "Couldn't load this section.") }

    fun openCollection(collection: BrowseCollection) {
        clearSearch()
        pauseHomeLoading()
        collectionJob?.cancel(); pageJob?.cancel(); collectionActionJob?.cancel()
        collectionCursor = null; seenCollectionCursors.clear()
        mutableHasMoreCollection.value = false; mutableCollectionLoadingMore.value = false; mutableCollectionPageError.value = null
        mutableSelectedCollection.value = collection
        mutableCollectionSongs.value = ContentState(loading = true)
        collectionJob = viewModelScope.launch {
            try {
                val page = withTimeoutOrNull(8_000) { youtube.getCollectionPage(collection) }
                    ?: throw java.io.IOException("This collection is taking too long. Reopen it to retry.")
                collectionCursor = page.next
                mutableHasMoreCollection.value = page.next != null
                mutableCollectionSongs.value = ContentState(page.tracks.map { it.toLibraryTrack() })
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { mutableCollectionSongs.value = ContentState(error = e.message ?: "Couldn't load this collection.") }
        }
    }
    private suspend fun appendCollectionPage(): Boolean {
        val collection = mutableSelectedCollection.value ?: return false
        val cursor = collectionCursor ?: return false
        mutableCollectionLoadingMore.value = true
        mutableCollectionPageError.value = null
        try {
            val page = youtube.getCollectionPage(collection, cursor)
            if (page.next?.fingerprint == cursor.fingerprint || page.next?.fingerprint in seenCollectionCursors) {
                throw IllegalStateException("The provider repeated a page. Please reopen the collection.")
            }
            seenCollectionCursors += cursor.fingerprint
            mutableCollectionSongs.value = ContentState((mutableCollectionSongs.value.items + page.tracks.map { it.toLibraryTrack() }).distinctBy { it.id })
            collectionCursor = page.next
            mutableHasMoreCollection.value = page.next != null
            return true
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { mutableCollectionPageError.value = e.message ?: "Couldn't load more songs."; return false }
        finally { mutableCollectionLoadingMore.value = false }
    }
    fun loadMoreCollection() {
        if (pageJob?.isActive == true || collectionActionJob?.isActive == true || mutableCollectionSongs.value.loading) return
        pageJob = viewModelScope.launch { appendCollectionPage() }
    }
    fun useCompleteCollection(shuffle: Boolean = false, save: Boolean = false) {
        if (collectionActionJob?.isActive == true) return
        collectionActionJob = viewModelScope.launch {
            pageJob?.join(); collectionJob?.join()
            // Ordered playback needs the first page, not the entire remote playlist.
            // Full shuffle and Save still need the complete catalog.
            if (!save && !shuffle) {
                val collection = mutableSelectedCollection.value ?: return@launch
                val songs = mutableCollectionSongs.value.items
                val first = songs.firstOrNull() ?: return@launch
                val cursor = collectionCursor
                val token = (if (collection.kind == CollectionKind.ALBUM) "album:" else "playlist:") + UUID.randomUUID()
                play(first, songs, ordered = true, recommendationChain = false, collectionToken = token)
                if (cursor != null) app.continueCollection(collection, cursor, songs.map { it.id }.toSet(), token, youtube)
                closeCollection()
                return@launch
            }
            while (collectionCursor != null) if (!appendCollectionPage()) return@launch
            val collection = mutableSelectedCollection.value ?: return@launch
            val songs = mutableCollectionSongs.value.items.let { if (shuffle) it.shuffled() else it }
            if (save) saveCollection(collection)
            else songs.firstOrNull()?.let { play(it, songs, ordered = true, recommendationChain = false); closeCollection() }
        }
    }
    fun closeCollection() {
        collectionJob?.cancel(); pageJob?.cancel(); collectionActionJob?.cancel()
        mutableSelectedCollection.value = null
        collectionCursor = null
        mutableHasMoreCollection.value = false
    }

    fun saveCollection(collection: BrowseCollection) {
        val songs = mutableCollectionSongs.value.items
        if (songs.isEmpty()) return
        edit { it.copy(
            playlists = if (it.playlists.any { playlist -> playlist.id == "yt-${collection.id}" }) it.playlists else
                it.playlists + Playlist("yt-${collection.id}", collection.title, songs.map { song -> song.id }),
            savedTracks = (it.savedTracks + songs).distinctBy { song -> song.id },
        ) }
        mutableMessages.tryEmit("Saved to your library")
    }

    fun startRadio(track: Track) {
        play(track, listOf(track), playback.value.position.takeIf { playback.value.trackId == track.id } ?: 0)
        mutableMessages.tryEmit("Radio will keep adding music for you")
    }

    fun download(track: Track) {
        if (!track.canDownload) { mutableMessages.tryEmit("This song is already on your device."); return }
        remember(track)
        try {
            val uri = if (track.source == Source.YOUTUBE) com.raagmusic.app.playback.YoutubeStreamResolver.mediaUri(track.uri) else Uri.parse(track.uri)
            val request = DownloadRequest.Builder(track.id, uri).setCustomCacheKey(track.id)
                .setMimeType(if (track.source == Source.YOUTUBE) "audio/webm" else null).build()
            if (downloads.value[track.id]?.state == Download.STATE_FAILED) {
                DownloadService.sendRemoveDownload(app, MusicDownloadService::class.java, track.id, true)
            }
            DownloadService.sendAddDownload(app, MusicDownloadService::class.java, request, true)
            DownloadService.sendResumeDownloads(app, MusicDownloadService::class.java, true)
            mutableMessages.tryEmit("Downloading ${track.title}")
        } catch (_: Exception) { mutableMessages.tryEmit("Couldn't start the download. Please try again.") }
    }
    fun removeDownload(id: String) { DownloadService.sendRemoveDownload(app, MusicDownloadService::class.java, id, false) }
    fun retryDownloads() { DownloadService.sendResumeDownloads(app, MusicDownloadService::class.java, false) }
    fun sleep(minutes: Int) = editFeatures({ it.copy(sleepTimer = if (minutes > 0)
        SleepTimerSpec(System.currentTimeMillis() + minutes.coerceAtMost(180) * 60_000L) else SleepTimerSpec()) })
    fun sleepAtEndOfTrack() {
        val id = playback.value.trackId ?: return
        editFeatures({ it.copy(sleepTimer = SleepTimerSpec(finishTrackId = id)) })
    }
    override fun onCleared() { downloadManager?.removeListener(listener); downloadRefreshes.close(); downloadPoll?.cancel(); player.close(); super.onCleared() }
}

data class LyricsState(val trackId: String? = null, val loading: Boolean = false, val lyrics: Lyrics? = null, val error: String? = null)

data class ArtistPageState(val name: String, val imageUrl: String = "",
    val profile: com.raagmusic.app.data.youtube.ArtistProfile? = null, val profileLoading: Boolean = false, val profileError: String? = null, val songs: ContentState<Track> = ContentState(), val albums: ContentState<BrowseCollection> = ContentState(), val related: ContentState<String> = ContentState(), val identityBound: Boolean = false)
