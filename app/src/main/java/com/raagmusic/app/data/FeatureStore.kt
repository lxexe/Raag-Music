package com.raagmusic.app.data

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.raagmusic.app.domain.*
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.shareIn
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

private val Context.featuresDataStore by preferencesDataStore("raag_features")
class FeatureStore(private val context: Context) {
    private val key = stringPreferencesKey("features_v1")
    // Observers share decoding. Keep state cold so state.first() reads the latest committed edit.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val state = context.featuresDataStore.data.catch {
        if (it is IOException) emit(emptyPreferences()) else throw it
    }.map { FeatureCodec.decode(it[key]) }.distinctUntilChanged().flowOn(Dispatchers.Default)
    val observed = state.shareIn(scope, SharingStarted.Eagerly, replay = 1)
    suspend fun update(transform: (FeatureState) -> FeatureState) = withContext(Dispatchers.IO) {
        context.featuresDataStore.edit {
            val previous = FeatureCodec.decode(it[key])
            val next = transform(previous)
            if (next != previous) it[key] = FeatureCodec.encode(next)
        }
    }
}
object FeatureCodec {
    fun encode(s: FeatureState): String = JSONObject().apply {
        put("preferences", JSONObject().apply {
            put("stopOnExit", s.preferences.stopOnExit); put("rememberQueue", s.preferences.rememberQueue); put("wifiQuality", s.preferences.wifiQuality.name)
            put("analyticsEnabled", s.preferences.analyticsEnabled); put("videoMode", s.preferences.videoMode.name); put("videoQuality", s.preferences.videoQuality.name)
            put("mobileQuality", s.preferences.mobileQuality.name); put("qualityDefaultsVersion", 1); put("collectHistory", s.preferences.collectHistory)
            put("transitionMode", s.preferences.transitionMode.name); put("crossfadeSeconds", s.preferences.crossfadeSeconds)
            put("librarySort", s.preferences.librarySort.name); put("libraryFilter", s.preferences.libraryFilter.name)
            put("equalizer", JSONObject().apply {
                put("enabled", s.preferences.equalizer.enabled); put("preset", s.preferences.equalizer.preset.name)
                put("gains", JSONArray(s.preferences.equalizer.customGains))
            })
        })
        put("theme", JSONObject().apply {
            put("mode", s.theme.mode.name); put("colors", s.theme.colors.name); put("accent", s.theme.accent)
            put("surfaces", s.theme.surfaces.name); put("corners", s.theme.corners.name); put("reduceMotion", s.theme.reduceMotion)
            put("motion", s.theme.motion.name); put("haptics", s.theme.haptics); put("effects", s.theme.effects)
            put("lyricStyle", s.theme.lyricStyle.name)
            put("density", s.theme.density.name); put("playerStyle", s.theme.playerStyle.name); put("miniFavorite", s.theme.miniFavorite)
        })
        put("experience", JSONObject().apply {
            put("homeMoods", JSONArray(s.experience.homeMoods))
            put("pinnedPlaylists", JSONArray(s.experience.pinnedPlaylists.toList())); put("followedArtists", JSONArray(s.experience.followedArtists.toList()))
            put("libraryGrid", s.experience.libraryGrid); put("lyricTextSize", s.experience.lyricTextSize)
            put("lyricBlur", s.experience.lyricBlur); put("lyricCentered", s.experience.lyricCentered); put("lyricOffsetMs", s.experience.lyricOffsetMs)
        })
        put("discovery", JSONObject().apply {
            put("country", s.discovery.countryOverride)
            put("recentSearches", JSONArray(s.discovery.recentSearches))
        })
        put("sleepTimer", JSONObject().apply {
            put("deadline", s.sleepTimer.deadlineEpochMs); put("finishTrackId", s.sleepTimer.finishTrackId)
        })
        put("profile", JSONObject().apply {
            put("name", s.profile.name); put("namePromptSeen", s.profile.namePromptSeen)
        })
        put("queue", JSONObject().apply {
            put("library", JSONObject(LibraryCodec.encode(LibraryState(savedTracks = s.queue.tracks.distinctBy { it.id }))))
            put("ids", JSONArray(s.queue.tracks.map { it.id }))
            put("index", s.queue.index); put("positionMs", s.queue.positionMs)
            put("shuffle", s.queue.shuffle); put("repeat", s.queue.repeat); put("recommendationChain", s.queue.recommendationChain)
        })
        put("historyEpoch", s.historyEpoch)
        put("lessLikeIds", JSONArray(s.lessLikeIds.toList()))
        put("history", JSONArray(s.history.map { JSONObject().apply {
            put("id", it.trackId); put("day", it.day); put("ms", it.milliseconds)
            put("plays", it.plays); put("skips", it.skips)
        } }))
    }.toString()
    fun decode(raw: String?): FeatureState {
        if (raw == null) return FeatureState()
        val j = JSONObject(raw)
        val p = j.optJSONObject("preferences") ?: JSONObject()
        fun quality(key: String, fallback: AudioQuality) = runCatching { AudioQuality.valueOf(p.getString(key)) }.getOrDefault(fallback)
        val eq = p.optJSONObject("equalizer") ?: JSONObject()
        val gains = eq.optJSONArray("gains") ?: JSONArray()
        val t = j.optJSONObject("theme") ?: JSONObject()
        val theme = ThemePreferences(
            runCatching { ThemeMode.valueOf(t.getString("mode")) }.getOrDefault(ThemeMode.SYSTEM)
                .let { if (it == ThemeMode.DARK || it == ThemeMode.DYNAMIC) ThemeMode.AMOLED else it },
            runCatching { ThemeColors.valueOf(t.getString("colors")) }.getOrDefault(ThemeColors.RAAG),
            (t.optLong("accent", 0xFF8B5CF6) and 0xFFFFFF) or 0xFF000000,
            runCatching { SurfaceStyle.valueOf(t.getString("surfaces")) }.getOrDefault(SurfaceStyle.TONAL),
            runCatching { CornerStyle.valueOf(t.getString("corners")) }.getOrDefault(CornerStyle.SOFT), t.optBoolean("reduceMotion"),
            runCatching { MotionLevel.valueOf(t.getString("motion")) }.getOrDefault(MotionLevel.SUBTLE),
            t.optBoolean("haptics", true), t.optBoolean("effects", true),
            runCatching { ContentDensity.valueOf(t.getString("density")) }.getOrDefault(ContentDensity.COMFORTABLE),
            runCatching { PlayerStyle.valueOf(t.getString("playerStyle")) }.getOrDefault(PlayerStyle.COMPACT)
                .let { if (it == PlayerStyle.APPLE) it else PlayerStyle.COMPACT }, t.optBoolean("miniFavorite", true),
            runCatching { LyricStyle.valueOf(t.getString("lyricStyle")) }.getOrDefault(LyricStyle.DEFAULT))
        val x = j.optJSONObject("experience") ?: JSONObject()
        fun list(key: String): List<String> = x.optJSONArray(key)?.let { a -> List(a.length()) { a.optString(it) } }.orEmpty()
        val experience = ExperiencePreferences(list("pinnedPlaylists").toSet(), list("followedArtists").toSet(), x.optBoolean("libraryGrid"),
            x.optInt("lyricTextSize", 23), x.optBoolean("lyricBlur", true), x.optBoolean("lyricCentered"), x.optLong("lyricOffsetMs"),
            homeMoods = if (x.has("homeMoods")) list("homeMoods") else defaultHomeMoods).sanitized()
        val q = j.optJSONObject("queue") ?: JSONObject()
        val library = LibraryCodec.decode(q.optJSONObject("library")?.toString())
        val ids = q.optJSONArray("ids") ?: JSONArray()
        val metadata = library.savedTracks.associateBy { it.id }
        val queueTracks = List(ids.length()) { ids.getString(it) }.mapNotNull(metadata::get)
        val h = j.optJSONArray("history") ?: JSONArray()
        val less = j.optJSONArray("lessLikeIds") ?: JSONArray()
        val discovery = j.optJSONObject("discovery") ?: JSONObject()
        val searches = discovery.optJSONArray("recentSearches") ?: JSONArray()
        val searchPreferences = (searches.length() - 1 downTo 0).fold(DiscoveryPreferences(
            countryOverride = validCountryCode(discovery.optString("country")))) { state, index ->
            state.withSearch(searches.optString(index))
        }
        return FeatureState(ListeningPreferences(p.optBoolean("rememberQueue", true), quality("wifiQuality", AudioQuality.HIGH),
            if (p.optInt("qualityDefaultsVersion", 0) < 1) AudioQuality.HIGH else quality("mobileQuality", AudioQuality.HIGH), p.optBoolean("collectHistory", true),
            runCatching { TransitionMode.valueOf(p.getString("transitionMode")) }.getOrDefault(TransitionMode.GAPLESS),
            p.optInt("crossfadeSeconds", 5).coerceIn(1, 12),
            EqualizerSettings(eq.optBoolean("enabled"), runCatching { EqualizerPreset.valueOf(eq.getString("preset")) }.getOrDefault(EqualizerPreset.FLAT),
                List(5) { gains.optInt(it, 0).coerceIn(-12, 12) }),
            runCatching { LibrarySort.valueOf(p.getString("librarySort")) }.getOrDefault(LibrarySort.RECENT),
            runCatching { LibraryFilter.valueOf(p.getString("libraryFilter")) }.getOrDefault(LibraryFilter.ALL), p.optBoolean("stopOnExit", false), p.optBoolean("analyticsEnabled", true),
            runCatching { VideoPlaybackMode.valueOf(p.getString("videoMode")) }.getOrDefault(VideoPlaybackMode.FALLBACK),
            runCatching { VideoQuality.valueOf(p.getString("videoQuality")) }.getOrDefault(VideoQuality.HIGHEST)),
            QueueSnapshot(queueTracks, q.optInt("index"), q.optLong("positionMs"), q.optBoolean("shuffle"), q.optInt("repeat"), q.optBoolean("recommendationChain")).sanitized(),
            List(h.length()) { i -> h.getJSONObject(i).let { ListenRecord(it.getString("id"), it.getString("day"),
                it.optLong("ms").coerceAtLeast(0), it.optInt("plays").coerceAtLeast(0), it.optInt("skips").coerceAtLeast(0)) } },
            List(less.length()) { less.getString(it) }.toSet(), j.optLong("historyEpoch").coerceAtLeast(0), theme, searchPreferences,
            ListenerProfile(j.optJSONObject("profile")?.optString("name").orEmpty(),
                j.optJSONObject("profile")?.optBoolean("namePromptSeen") ?: false).sanitized(),
            SleepTimerSpec(j.optJSONObject("sleepTimer")?.optLong("deadline") ?: 0,
                j.optJSONObject("sleepTimer")?.optString("finishTrackId")?.takeIf { it.isNotBlank() && it != "null" }), experience)
    }
}
