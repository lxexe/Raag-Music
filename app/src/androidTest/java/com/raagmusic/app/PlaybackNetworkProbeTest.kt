package com.raagmusic.app

import android.util.Log
import androidx.core.net.toUri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.grack.nanojson.JsonWriter
import com.raagmusic.app.data.youtube.*
import com.raagmusic.app.playback.*
import okhttp3.OkHttpClient
import org.junit.*
import org.junit.runner.RunWith
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.services.youtube.InnertubeClientRequestInfo
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper
import java.util.concurrent.TimeUnit

/** Opt-in transport experiment. Logs timings and protocol only, never URLs or visitor data. */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class PlaybackNetworkProbeTest {
    @Test fun compareBootstrapAndCdnTransports() {
        Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("liveProviders") == "true")
        val client = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).build()
        NewPipeExtractionEngine() // initialize the runtime before installing the probe downloader
        NewPipe.init(NewPipeDownloader(client), Localization("en", "US"))
        try {
            val ids = listOf("5NV6Rdv1a3I", "dQw4w9WgXcQ", "kJQP7kiw5Fk")
            ids.forEachIndexed { index, id ->
                val started = System.nanoTime()
                val nonce = YoutubeParsingHelper.generateContentPlaybackNonce()
                val info = InnertubeClientRequestInfo.ofVisionOsClient()
                val localization = Localization("en", "US")
                val body = JsonWriter.string(YoutubeParsingHelper.prepareJsonBuilder(localization, ContentCountry("US"), info, null)
                    .value("videoId", id).value("cpn", nonce).value("contentCheckOk", true).value("racyCheckOk", true).done())
                val response = NewPipe.getDownloader().postWithContentTypeJson(
                    YoutubeParsingHelper.YOUTUBEI_V1_GAPIS_URL + "player?prettyPrint=false&t=" + YoutubeParsingHelper.generateTParameter() + "&id=" + id,
                    mapOf("User-Agent" to listOf(YoutubeParsingHelper.getVisionOsUserAgent(localization)), "X-Goog-Api-Format-Version" to listOf("2")), body.toByteArray(), localization).responseBody()
                val withoutVisitor = AudioOnlyPlayer.select(response, id, 0, nonce)
                Log.i("RaagNetworkProbe", "RESULT id=$id noVisitorSupported=${withoutVisitor != null} status=" + org.json.JSONObject(response).optJSONObject("playabilityStatus")?.optString("status"))
                val audio = withoutVisitor ?: AudioOnlyPlayer.select(AudioPlayerClient().player(id, "US", nonce), id, 0, nonce)
                Assert.assertNotNull("Session-backed native player must provide supported audio", audio)
                val url = org.schabi.newpipe.extractor.services.youtube.YoutubeJavaScriptPlayerManager
                    .getUrlWithThrottlingParameterDeobfuscated(id, audio!!.url)
                Log.i("RaagNetworkProbe", "RESULT id=$id resolveIncludingFallbackMs=" + (System.nanoTime() - started) / 1_000_000)
                val spec = DataSpec.Builder().setUri(url.toUri()).setHttpMethod(DataSpec.HTTP_METHOD_POST)
                    .setHttpBody(youtubeStreamBody).setHttpRequestHeaders(youtubeStreamHeaders(url)).setLength(32 * 1024).build()
                val factories = listOf("platform" to DefaultHttpDataSource.Factory().setUserAgent("Raag/1.0"),
                    "okhttp" to OkHttpDataSource.Factory(client).setUserAgent("Raag/1.0"))
                (if (index % 2 == 0) factories else factories.reversed()).forEach { (name, factory) ->
                    val source = factory.createDataSource()
                    val at = System.nanoTime()
                    try {
                        source.open(spec)
                        val opened = System.nanoTime()
                        Assert.assertTrue(source.read(ByteArray(4096), 0, 4096) > 0)
                        Log.i("RaagNetworkProbe", "RESULT id=$id transport=$name openMs=${(opened-at)/1_000_000} firstByteMs=${(System.nanoTime()-at)/1_000_000}")
                    } finally { source.close() }
                }
                val sizes = listOf(-1L, audio.contentLength!!, 256 * 1024L)
                (sizes.drop(index) + sizes.take(index)).forEach { size ->
                    val source = DefaultHttpDataSource.Factory().setUserAgent("Raag/1.0").createDataSource()
                    val at = System.nanoTime()
                    try {
                        source.open(spec.buildUpon().setLength(size).build())
                        val opened = System.nanoTime()
                        Assert.assertTrue(source.read(ByteArray(4096), 0, 4096) > 0)
                        Log.i("RaagNetworkProbe", "RESULT id=$id rangeBytes=$size openMs=${(opened-at)/1_000_000} firstByteMs=${(System.nanoTime()-at)/1_000_000}")
                    } finally { source.close() }
                }
            }
        } finally { NewPipe.init(NewPipeDownloader(), Localization("en", "US")) }
    }
}
