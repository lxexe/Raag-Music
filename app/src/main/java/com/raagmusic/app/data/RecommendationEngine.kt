package com.raagmusic.app.data

import com.raagmusic.app.data.youtube.YoutubeMusicRepository
import com.raagmusic.app.data.youtube.ArtistNames
import com.raagmusic.app.data.youtube.toLibraryTrack
import com.raagmusic.app.domain.*
import kotlinx.coroutines.*
import java.time.LocalDateTime

/** Keep provider similarity, but explore when its radio is exhausted or dominated by one singer. */
class RecommendationEngine(private val youtube: YoutubeMusicRepository) {
    suspend fun next(seed: Track, library: LibraryState, features: FeatureState, excluded: Set<String> = emptySet()): List<Track> {
        var providerError: Exception? = null
        val related = if (seed.source == Source.YOUTUBE) try {
            withTimeoutOrNull(3_000) { youtube.getRelatedSongs(seed.id).map { it.toLibraryTrack() } }.orEmpty()
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { providerError = e; emptyList() } else emptyList()
        val now = LocalDateTime.now()
        val recent = features.history.filter {
            (it.plays > 0 || it.milliseconds >= 30_000) && it.day >= now.toLocalDate().minusDays(3).toString()
        }.map { it.trackId }.toSet()
        val candidates = (related + library.savedTracks).toMutableList()
        suspend fun ranked() = withContext(Dispatchers.Default) {
            rankRecommendations(candidates, seed, library, features, now.toLocalDate(), now.hour,
                relatedIds = related.map { it.id }.toSet(),
                similarArtists = related.take(12).flatMap { ArtistNames.fallback(it.artist) }.toSet(),
                excludedIds = excluded, limit = 20)
        }
        val initial = ranked()
        val fresh = initial.filter { it.id !in recent }
        // Eight diverse successors are already a useful runway. Don't hold them behind more
        // searches merely to fill all twenty slots; the queue refills below its low-water mark.
        if (seed.source != Source.YOUTUBE || (fresh.size >= 8 &&
            !needsRecommendationVariety(initial, limit = minOf(20, initial.size)))) return initial
        val artists = (ArtistNames.fallback(seed.artist) + related.flatMap { ArtistNames.fallback(it.artist) })
            .distinctBy { it.lowercase() }
        val region = features.discovery.countryOverride?.let { MusicRegion(it, CountrySource.MANUAL) } ?: MusicRegion(youtube.currentCountry)
        val queries = buildList {
            // Related singer first, then genre/regional discovery; never fill all twenty from the seed singer.
            artists.drop(1).getOrNull((now.dayOfYear % (artists.size - 1).coerceAtLeast(1)))?.let { add("$it songs") }
            add("${seed.genre.ifBlank { regionalAlbumQuery(region) }} songs ${region.queryName}")
            add(homeQueries(region, "For you", now.toLocalDate()).let { if (now.dayOfYear % 2 == 0) it.newReleases else it.trending })
            if (related.isEmpty()) artists.firstOrNull()?.let { add("$it songs") }
        }.distinct().take(3)
        withTimeoutOrNull(3_000) {
            for (query in queries) {
                try { candidates += youtube.searchPage(query, "Songs").tracks.map { it.toLibraryTrack() } }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { providerError = e }
                val batch = ranked()
                if (batch.count { it.id !in recent } >= 16 && !needsRecommendationVariety(batch)) return@withTimeoutOrNull batch
            }
            ranked()
        }?.takeIf { it.isNotEmpty() }?.let { return it }
        val result = ranked()
        if (result.isEmpty() && providerError != null) throw providerError
        return result
    }
}
