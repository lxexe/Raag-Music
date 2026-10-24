package com.raagmusic.app.data.youtube

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.exceptions.SignInConfirmNotBotException
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory
import org.schabi.newpipe.extractor.playlist.PlaylistInfoItem
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.AudioTrackType
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem

class NewPipeExtractionEngine(private val fastAudio: Boolean = true, sessionDirectory: java.io.File? = null) : ExtractionEngine {
    init { Runtime.initialize() }
    private var countryCode = "US"
    private val rejectedStreams = RejectedStreams()
    private val resetSession = java.util.concurrent.atomic.AtomicBoolean(false)
    private val audioPlayer = AudioPlayerClient(sessionDirectory)
    override fun onAudioStreamRejected(trackId: String) {
        resetSession.set(true)
    }
    override fun onStreamRejected(trackId: String, url: String?) {
        if (url != null) rejectedStreams.reject(trackId, url)
        onAudioStreamRejected(trackId)
    }
    override fun onPlaybackRetry(trackId: String) {
        rejectedStreams.reset(trackId)
        resetSession.set(true)
    }
    override fun setCountry(code: String) { countryCode = com.raagmusic.app.domain.validCountryCode(code) ?: "US" }
    // Per-extractor configuration avoids racing NewPipe's process-wide defaults.
    private fun <T : org.schabi.newpipe.extractor.Extractor> T.regional(): T = apply {
        forceLocalization(Localization("en", countryCode))
        forceContentCountry(ContentCountry(countryCode))
    }
    private fun streamInfo(trackId: String): StreamInfo = StreamInfo.getInfo(
        ServiceList.YouTube.getStreamExtractor("https://www.youtube.com/watch?v=$trackId").regional())

    override fun artistProfileByName(name: String): ArtistProfile? = withProviderErrors {
        val search = ServiceList.YouTube.getSearchExtractor(name,
            listOf(YoutubeSearchQueryHandlerFactory.MUSIC_ARTISTS), "").regional().also { it.fetchPage() }
        val candidates = search.initialPage.items.filterIsInstance<org.schabi.newpipe.extractor.channel.ChannelInfoItem>()
        // Do not substitute a record label or an unrelated first search result.
        val artist = candidates.firstOrNull { ArtistNames.same(it.name, name) } ?: return@withProviderErrors null
        val portrait = artist.thumbnails.maxByOrNull { it.height }?.url.orEmpty()
        val fallback = ArtistProfile(artist.name, imageUrl = portrait, description = artist.description.orEmpty(),
            channelUrl = artist.url)
        fallback
    }

    override fun artistSongs(profile: ArtistProfile): List<Track> = withProviderErrors {
        if (profile.popularSongs.isNotEmpty()) return@withProviderErrors profile.popularSongs
        val id = profile.channelUrl.substringAfterLast('/').substringBefore('?')
        require(Regex("UC[A-Za-z0-9_-]{22}").matches(id))
        val extractor = ServiceList.YouTube.getChannelTabExtractorFromId(id, "videos").regional().also { it.fetchPage() }
        extractor.initialPage.items.filterIsInstance<StreamInfoItem>().map(::mapSong).distinctBy { it.id }
    }

    override fun artistDetails(profile: ArtistProfile): ArtistProfile {
        var details = profile
        val id = runCatching { ServiceList.YouTube.channelLHFactory.fromUrl(profile.channelUrl).id.substringAfterLast('/') }
            .getOrElse { profile.channelUrl.substringAfterLast('/').substringBefore('?') }
        try {
            details = ArtistProfileParser.musicHeader(musicRequest("browse", org.json.JSONObject().put("browseId", id)), profile)
        } catch (e: Exception) { if (Thread.currentThread().isInterrupted) throw e }
        // A Music header failure must not prevent fetching the real YouTube channel.
        val channelId = details.channelUrl.substringAfterLast('/').substringBefore('?')
        if (Regex("UC[A-Za-z0-9_-]{22}").matches(channelId)) try {
            val extractor = ServiceList.YouTube.getChannelExtractor("https://www.youtube.com/channel/$channelId")
                .regional().also { it.fetchPage() }
            val channel = org.schabi.newpipe.extractor.channel.ChannelInfo.getInfo(extractor)
            if (ArtistNames.same(channel.name, profile.name)) details = details.copy(
                imageUrl = details.imageUrl.ifBlank { channel.avatars.maxByOrNull { it.height }?.url.orEmpty() },
                description = details.description.ifBlank { channel.description.orEmpty() },
                subscribers = details.subscribers ?: channel.subscriberCount.takeIf { it >= 0 }, verified = channel.isVerified)
        } catch (e: Exception) { if (Thread.currentThread().isInterrupted) throw e }
        return details
    }

    override fun songArtistCredits(trackId: String): SongArtistCredits = withProviderErrors {
        YoutubeTrackIds.requireValid(trackId)
        val response = musicRequest("next", org.json.JSONObject().put("videoId", trackId)
            .put("playlistId", "RDAMVM$trackId").put("enablePersistentPlaylistPanel", true)
            .put("isAudioOnly", true).put("tunerSettingValue", "AUTOMIX_SETTING_NORMAL")
            .put("watchEndpointMusicSupportedConfigs", org.json.JSONObject().put("watchEndpointMusicConfig",
                org.json.JSONObject().put("hasPersistentPlaylistPanel", true).put("musicVideoType", "MUSIC_VIDEO_TYPE_ATV"))))
        SongArtistCredits(MusicArtistCredits.parse(response, trackId), MusicArtistCredits.related(response, trackId), MusicArtistCredits.linked(response, trackId))
    }

    override fun artistProfile(trackId: String): ArtistProfile = withProviderErrors {
        YoutubeTrackIds.requireValid(trackId)
        val song = streamInfo(trackId)
        val channelUrl = song.uploaderUrl
        val extractor = ServiceList.YouTube.getChannelExtractor(channelUrl).regional().also { it.fetchPage() }
        val info = org.schabi.newpipe.extractor.channel.ChannelInfo.getInfo(extractor)
        val profile = ArtistProfile(info.name,
            info.avatars.maxByOrNull { it.height }?.url.orEmpty(),
            info.banners.maxByOrNull { it.height }?.url.orEmpty(), info.description.orEmpty(),
            info.subscriberCount.takeIf { it >= 0 }, channelUrl = info.url, verified = info.isVerified)
        // Channel details remain useful when Music has no public artist header.
        try {
            val id = ServiceList.YouTube.channelLHFactory.fromUrl(channelUrl).id.substringAfterLast('/')
            ArtistProfileParser.musicHeader(musicRequest("browse", org.json.JSONObject().put("browseId", id)), profile)
        } catch (e: Exception) {
            if (Thread.currentThread().isInterrupted) throw e
            profile
        }
    }

    override fun song(trackId: String): Track = withProviderErrors {
        YoutubeTrackIds.requireValid(trackId)
        val info = streamInfo(trackId)
        Track(trackId, info.name, info.uploaderName.orEmpty().ifBlank { "Unknown artist" },
            info.thumbnails.maxByOrNull { it.height }?.url.orEmpty(), info.duration.coerceAtLeast(0))
    }

    override fun relatedSongs(trackId: String): List<Track> = withProviderErrors {
        YoutubeTrackIds.requireValid(trackId)
        val music = try {
            MusicRadioParser.parse(musicRequest("next", org.json.JSONObject().put("videoId", trackId)
                .put("playlistId", "RDAMVM$trackId").put("enablePersistentPlaylistPanel", true)
                .put("isAudioOnly", true).put("tunerSettingValue", "AUTOMIX_SETTING_NORMAL")))
                .filter { it.id != trackId }
        } catch (e: Exception) {
            if (Thread.currentThread().isInterrupted) throw e
            emptyList()
        }
        music.ifEmpty { streamInfo(trackId).relatedItems
            .filterIsInstance<StreamInfoItem>().map(::mapSong).distinctBy { it.id } }
    }

    override fun collection(url: String, kind: CollectionKind): BrowseCollection = withProviderErrors {
        val resolved = if (url.startsWith("https://music.youtube.com/browse/MPRE")) {
            val id = url.substringAfterLast('/')
            require(Regex("MPRE[A-Za-z0-9_-]{6,196}").matches(id))
            val response = musicRequest("browse", org.json.JSONObject().put("browseId", id))
            val playlistId = Regex("\"playlistId\"\\s*:\\s*\"(OLAK5uy_[A-Za-z0-9_-]+)\"").find(response)?.groupValues?.get(1)
                ?: throw YoutubeExtractionException("YouTube couldn't provide this album's track list.")
            "https://www.youtube.com/playlist?list=$playlistId"
        } else url
        val extractor = ServiceList.YouTube.getPlaylistExtractor(resolved).regional().also { it.fetchPage() }
        BrowseCollection(extractor.id, extractor.name, runCatching { extractor.uploaderName }.getOrNull().orEmpty().ifBlank { "YouTube Music" },
            runCatching { extractor.thumbnails.maxByOrNull { it.height }?.url }.getOrNull().orEmpty(), extractor.url,
            runCatching { extractor.streamCount.coerceAtLeast(0) }.getOrDefault(0), kind)
    }

    override fun searchSuggestions(query: String): List<String> = withProviderErrors {
        // Music completions avoid generic video/news suggestions from YouTube's default endpoint.
        val music = try { MusicSearchSuggestions.parse(musicRequest("music/get_search_suggestions",
            org.json.JSONObject().put("input", query.take(120)))) }
        catch (e: Exception) { if (Thread.currentThread().isInterrupted) throw e; emptyList() }
        if (music.isNotEmpty()) return@withProviderErrors music
        ServiceList.YouTube.suggestionExtractor.apply {
            forceLocalization(Localization("en", countryCode))
            forceContentCountry(ContentCountry(countryCode))
        }.suggestionList(query)
    }

    override fun searchSongs(query: String): List<Track> = withProviderErrors {
        val service = ServiceList.YouTube
        // Songs and music videos both report VIDEO_STREAM in NewPipe. The SEARCH FILTER,
        // not StreamType, is what excludes the Music Videos, Albums and Playlists tabs.
        val search = service.getSearchExtractor(
            query, listOf(YoutubeSearchQueryHandlerFactory.MUSIC_SONGS), "",
        ).regional()
        search.fetchPage()
        val page = search.initialPage
        if (page.items.isEmpty() && page.errors.isNotEmpty()) throw page.errors.first()
        page.items.filterIsInstance<StreamInfoItem>().map(::mapSong)
    }

    override fun searchPage(query: String, kind: String, cursor: SearchCursor?): SearchPage = withProviderErrors {
        if (kind == "All") return@withProviderErrors MusicSearchParser.parse(musicRequest("search", org.json.JSONObject().put("query", query)))
        val filter = when (kind) {
            "Videos" -> YoutubeSearchQueryHandlerFactory.MUSIC_VIDEOS
            "Artists" -> YoutubeSearchQueryHandlerFactory.MUSIC_ARTISTS
            "Albums" -> YoutubeSearchQueryHandlerFactory.MUSIC_ALBUMS
            "Playlists" -> YoutubeSearchQueryHandlerFactory.MUSIC_PLAYLISTS
            else -> YoutubeSearchQueryHandlerFactory.MUSIC_SONGS
        }
        val search = cursor?.extractor ?: ServiceList.YouTube.getSearchExtractor(query, listOf(filter), "").regional().also { it.fetchPage() }
        val page = if (cursor == null) search.initialPage else search.getPage(cursor.page)
        if (page.items.isEmpty() && page.errors.isNotEmpty()) {
            // Retain established compatibility fallbacks for older Music collection renderers.
            if (cursor == null && kind in listOf("Albums", "Playlists")) return@withProviderErrors SearchPage(
                collections = searchCollections(query, if (kind == "Albums") CollectionKind.ALBUM else CollectionKind.PLAYLIST))
            throw page.errors.first()
        }
        SearchPage(
            tracks = page.items.filterIsInstance<StreamInfoItem>().map(::mapSong),
            artists = page.items.filterIsInstance<org.schabi.newpipe.extractor.channel.ChannelInfoItem>().map { item ->
                ArtistProfile(item.name, imageUrl = item.thumbnails.maxByOrNull { it.height }?.url.orEmpty(),
                    description = item.description.orEmpty(), channelUrl = item.url)
            },
            collections = page.items.filterIsInstance<PlaylistInfoItem>().map { item ->
                BrowseCollection(id = ServiceList.YouTube.playlistLHFactory.fromUrl(item.url).id,
                    title = item.name, artist = item.uploaderName.orEmpty().ifBlank { "YouTube Music" },
                    thumbnailUrl = item.thumbnails.maxByOrNull { it.height.coerceAtLeast(0) }?.url.orEmpty(), url = item.url,
                    trackCount = item.streamCount.coerceAtLeast(0), kind = if (kind == "Albums") CollectionKind.ALBUM else CollectionKind.PLAYLIST)
            }, next = page.nextPage?.takeIf { org.schabi.newpipe.extractor.Page.isValid(it) }?.let { SearchCursor(it, search) })
    }

    override fun searchCollections(query: String, kind: CollectionKind): List<BrowseCollection> = withProviderErrors {
        val filter = if (kind == CollectionKind.ALBUM) YoutubeSearchQueryHandlerFactory.MUSIC_ALBUMS
            else YoutubeSearchQueryHandlerFactory.MUSIC_PLAYLISTS
        val search = ServiceList.YouTube.getSearchExtractor(query, listOf(filter), "").regional()
        search.fetchPage()
        var page = search.initialPage
        if (kind == CollectionKind.ALBUM && page.items.isEmpty() && page.errors.any { it is org.schabi.newpipe.extractor.exceptions.ParsingException }) {
            val response = musicRequest("search", org.json.JSONObject().put("query", query)
                .put("params", "Eg-KAQwIABAAGAEgACgAMABqChAEEAUQAxAKEAk%3D"))
            val albums = MusicAlbumParser.parse(response)
            if (albums.isNotEmpty()) return@withProviderErrors albums
        }
        if (kind == CollectionKind.PLAYLIST && page.items.isEmpty() && page.errors.any { it is org.schabi.newpipe.extractor.exceptions.ParsingException }) {
            // Some Music playlist renderers omit the endpoint expected by this
            // extractor version. Public YouTube playlist search provides a fallback.
            val fallback = ServiceList.YouTube.getSearchExtractor("$query music", listOf(YoutubeSearchQueryHandlerFactory.PLAYLISTS), "").regional()
            fallback.fetchPage()
            page = fallback.initialPage
        }
        if (page.items.isEmpty() && page.errors.isNotEmpty()) throw page.errors.first()
        page.items.filterIsInstance<PlaylistInfoItem>().map { item ->
            BrowseCollection(
                id = ServiceList.YouTube.playlistLHFactory.fromUrl(item.url).id,
                title = item.name,
                artist = item.uploaderName.orEmpty().ifBlank { "YouTube Music" },
                thumbnailUrl = item.thumbnails.maxByOrNull { it.height.coerceAtLeast(0) }?.url.orEmpty(),
                url = item.url,
                trackCount = item.streamCount.coerceAtLeast(0),
                kind = kind,
            )
        }.distinctBy { it.id }
    }

    private fun musicRequest(endpoint: String, payload: org.json.JSONObject): String {
        val clientVersion = org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.getYoutubeMusicClientVersion()
        payload.put("context", org.json.JSONObject().put("client", org.json.JSONObject().put("clientName", "WEB_REMIX")
            .put("clientVersion", clientVersion).put("hl", "en").put("gl", countryCode).put("platform", "DESKTOP")))
        return NewPipe.getDownloader().post("https://music.youtube.com/youtubei/v1/$endpoint?prettyPrint=false",
            mapOf("Content-Type" to listOf("application/json"), "Origin" to listOf("https://music.youtube.com")),
            payload.toString().toByteArray(Charsets.UTF_8)).responseBody()
    }

    override fun collectionTracks(url: String): List<Track> = collectionPage(url).tracks

    override fun collectionPage(url: String, cursor: CollectionCursor?): CollectionPage = withProviderErrors {
        val canonical = cursor?.extractor?.url ?: if (url.startsWith("https://music.youtube.com/browse/MPRE")) collection(url, CollectionKind.ALBUM).url else url
        val reference = ServiceList.YouTube.playlistLHFactory.fromUrl(canonical)
        // getPage() needs the header initialized by fetchPage() (notably for
        // course playlists). NewPipe's static getMoreItems() creates an unfetched
        // extractor in 0.26.3, so keep the initialized extractor with the cursor.
        val extractor = cursor?.extractor ?: ServiceList.YouTube.getPlaylistExtractor(reference.url).regional().also { it.fetchPage() }
        val page = if (cursor == null) extractor.initialPage else extractor.getPage(cursor.page)
        if (page.items.isEmpty() && page.errors.isNotEmpty()) throw page.errors.first()
        CollectionPage(page.items.map(::mapSong).distinctBy { it.id },
            page.nextPage?.takeIf { org.schabi.newpipe.extractor.Page.isValid(it) }?.let { CollectionCursor(it, extractor) })
    }

    private fun mapSong(song: StreamInfoItem) = Track(
        id = YoutubeTrackIds.requireValid(ServiceList.YouTube.streamLHFactory.fromUrl(song.url).id),
        title = song.name,
        artist = song.uploaderName.orEmpty().ifBlank { "Unknown artist" },
        thumbnailUrl = song.thumbnails.maxByOrNull { it.height.coerceAtLeast(0) }?.url.orEmpty(),
        durationSeconds = song.duration.coerceAtLeast(0),
    )

    override fun audioStreamUrl(trackId: String): String = audioStreamUrl(trackId, 0)

    override fun audioStreamUrl(trackId: String, maxBitrate: Int): String = audioStream(trackId, maxBitrate).url

    override fun audioStream(trackId: String, maxBitrate: Int): ResolvedAudioStream = withProviderErrors {
        YoutubeTrackIds.requireValid(trackId)
        if (resetSession.getAndSet(false)) audioPlayer.reset()
        if (fastAudio) try {
            // The full extractor fetches Android, visionOS, WEB metadata AND /next sequentially.
            // Audio only needs this native player's visitor + player response. Keep NewPipe's
            // maintained request builder and n decoding; don't invent signature logic.
            val nonce = org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.generateContentPlaybackNonce()
            val response = NewPipeDownloader.withDeadline(3_000) {
                audioPlayer.player(trackId, countryCode, nonce)
            }
            AudioOnlyPlayer.select(response, trackId, maxBitrate, nonce) { rejectedStreams.accepts(trackId, it) }?.let { stream ->
                val decoded = org.schabi.newpipe.extractor.services.youtube.YoutubeJavaScriptPlayerManager
                    .getUrlWithThrottlingParameterDeobfuscated(trackId, stream.url)
                com.raagmusic.app.playback.PlaybackTrace.mark(trackId, "audio_only_resolved")
                return@withProviderErrors stream.copy(url = decoded)
            }
        } catch (e: Exception) {
            if (Thread.currentThread().isInterrupted) throw e
            audioPlayer.reset()
            // A slow/offline transport won't improve by adding eight more requests. Let the
            // repository's bounded network retry handle it; fallback is for incompatible data.
            if (e is java.io.IOException || e is ReCaptchaException || e is SignInConfirmNotBotException) throw e
        }
        com.raagmusic.app.playback.PlaybackTrace.mark(trackId, "full_extractor_fallback")
        // Compatibility fallback for unsupported native responses and rejected stream clients.
        val extractor = ServiceList.YouTube.getStreamExtractor("https://www.youtube.com/watch?v=$trackId")
            .regional().also { it.fetchPage() }
        val stream = selectAudioStream(extractor.audioStreams.filter { rejectedStreams.accepts(trackId, it.content) }, maxBitrate)
            ?: throw AudioUnavailableException()
        val url = stream.content.toHttpUrlOrNull()
        if (url == null || !url.isHttps) throw YoutubeExtractionException("YouTube returned an invalid audio stream.")
        ResolvedAudioStream(url.toString(), stream.bitrate.takeIf { it > 0 } ?: (stream.averageBitrate * 1000).takeIf { it > 0 },
            formatIdentity = listOf(stream.itag, stream.audioTrackId.orEmpty(), url.queryParameter("clen").orEmpty(),
                url.queryParameter("lmt").orEmpty()).joinToString("|"),
            expiresAtEpochMillis = url.queryParameter("expire")?.toLongOrNull()?.takeIf { it in 1..Long.MAX_VALUE / 1000 }?.times(1000),
            contentLength = url.queryParameter("clen")?.toLongOrNull()?.takeIf { it > 0 },
            mimeType = if (stream.format == MediaFormat.M4A) "audio/mp4" else "audio/webm")
    }

    override fun videoStream(trackId: String, maxHeight: Int): ResolvedAudioStream = withProviderErrors {
        YoutubeTrackIds.requireValid(trackId)
        if (resetSession.getAndSet(false)) audioPlayer.reset()
        if (fastAudio) try {
            val nonce = org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.generateContentPlaybackNonce()
            val response = NewPipeDownloader.withDeadline(3_000) { audioPlayer.player(trackId, countryCode, nonce) }
            ProgressiveVideoPlayer.select(response, trackId, maxHeight, nonce) { rejectedStreams.accepts(trackId, it) }?.let { stream ->
                val decoded = org.schabi.newpipe.extractor.services.youtube.YoutubeJavaScriptPlayerManager
                    .getUrlWithThrottlingParameterDeobfuscated(trackId, stream.url)
                com.raagmusic.app.playback.PlaybackTrace.mark(trackId, "native_video_resolved")
                return@withProviderErrors stream.copy(url = decoded)
            }
        } catch (e: Exception) {
            if (Thread.currentThread().isInterrupted) throw e
            audioPlayer.reset()
            if (e is java.io.IOException || e is ReCaptchaException || e is SignInConfirmNotBotException) throw e
        }
        val extractor = ServiceList.YouTube.getStreamExtractor("https://www.youtube.com/watch?v=$trackId")
            .regional().also { it.fetchPage() }
        fun height(stream: org.schabi.newpipe.extractor.stream.VideoStream) = stream.height.takeIf { it > 0 }
            ?: stream.resolution.takeWhile { it.isDigit() }.toIntOrNull() ?: 0
        // Progressive streams contain both tracks and need one source, player and decoder set.
        val streams = extractor.videoStreams.filter { !it.isVideoOnly && it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP &&
            it.content.toHttpUrlOrNull()?.isHttps == true && rejectedStreams.accepts(trackId, it.content) }
        val stream = streams.filter { height(it) <= maxHeight }.maxByOrNull(::height)
            ?: streams.minByOrNull(::height) ?: throw YoutubeExtractionException("This song has no playable video.")
        val url = stream.content.toHttpUrlOrNull()!!
        ResolvedAudioStream(url.toString(), formatIdentity = "video|${stream.itag}|${url.queryParameter("clen")}|${url.queryParameter("lmt")}",
            expiresAtEpochMillis = url.queryParameter("expire")?.toLongOrNull()?.times(1000),
            contentLength = url.queryParameter("clen")?.toLongOrNull()?.takeIf { it > 0 },
            mimeType = "video/mp4", hasVideo = true, videoHeight = height(stream))
    }

    private fun <T> withProviderErrors(block: () -> T): T = try {
        block()
    } catch (e: ReCaptchaException) {
        throw YoutubeVerificationRequiredException(e)
    } catch (e: SignInConfirmNotBotException) {
        throw YoutubeVerificationRequiredException(e)
    } catch (e: ContentNotAvailableException) {
        throw YoutubeExtractionException("This track is unavailable in your region or requires account access.", e)
    }

    private object Runtime {
        private var initialized = false
        @Synchronized fun initialize() {
            if (!initialized) {
                NewPipe.init(NewPipeDownloader(), Localization("en", "US"), ContentCountry("US"))
                initialized = true
            }
        }
    }
}

/** Never select muxed video, a manifest string, a live/DASH stream, or an alternate dub. */
internal fun selectAudioStream(streams: List<AudioStream>, maxBitrate: Int = 0): AudioStream? {
    val supported = streams.filter { it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP &&
        it.format in setOf(MediaFormat.M4A, MediaFormat.WEBMA, MediaFormat.WEBMA_OPUS) &&
        (it.audioTrackType == null || it.audioTrackType == AudioTrackType.ORIGINAL) }
    val original = supported.filter { it.audioTrackType == AudioTrackType.ORIGINAL }.ifEmpty { supported }
    fun rate(s: AudioStream) = s.bitrate.takeIf { it > 0 } ?: (s.averageBitrate.coerceAtLeast(0) * 1_000)
    val capped = if (maxBitrate <= 0) original else original.filter { rate(it) in 1..maxBitrate }
    if (maxBitrate > 0 && capped.isEmpty()) return original.filter { rate(it) > 0 }.minByOrNull(::rate) ?: original.firstOrNull()
    return capped.maxWithOrNull(compareBy<AudioStream>({ rate(it) }, { if (it.format == MediaFormat.WEBMA_OPUS) 1 else 0 }))
}
