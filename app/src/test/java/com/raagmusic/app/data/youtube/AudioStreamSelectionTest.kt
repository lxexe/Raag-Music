package com.raagmusic.app.data.youtube

import org.junit.Assert.*
import org.junit.Test
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.stream.*

class AudioStreamSelectionTest {
    @Test fun opusWinsAnEqualBitrateTie() {
        val opus = stream(MediaFormat.WEBMA_OPUS, 128)
        assertSame(opus, selectAudioStream(listOf(stream(MediaFormat.M4A, 128), opus)))
    }
    private fun stream(format: MediaFormat, bitrate: Int, delivery: DeliveryMethod = DeliveryMethod.PROGRESSIVE_HTTP,
                       url: Boolean = true, type: AudioTrackType? = null) = AudioStream.Builder()
        .setId("$format-$bitrate").setContent("https://media.example.com/audio", url)
        .setMediaFormat(format).setAverageBitrate(bitrate).setDeliveryMethod(delivery).setAudioTrackType(type).build()

    @Test fun choosesTheHighestBitrateAcrossContainers() {
        val best = stream(MediaFormat.WEBMA_OPUS, 256)
        assertSame(best, selectAudioStream(listOf(stream(MediaFormat.M4A, 192), stream(MediaFormat.M4A, 128), best)))
    }
    @Test fun fallsBackToWebmWhenM4aIsUnavailable() {
        val webm = stream(MediaFormat.WEBMA_OPUS, 160)
        assertSame(webm, selectAudioStream(listOf(webm)))
    }
    @Test fun manifestsAndNonProgressiveStreamsAreExcluded() {
        assertNull(selectAudioStream(listOf(stream(MediaFormat.M4A, 192, url = false), stream(MediaFormat.M4A, 192, delivery = DeliveryMethod.DASH))))
    }
    @Test fun originalAudioIsPreferredOverUnspecifiedAudio() {
        val original = stream(MediaFormat.WEBMA_OPUS, 128, type = AudioTrackType.ORIGINAL)
        assertSame(original, selectAudioStream(listOf(stream(MediaFormat.M4A, 192), original)))
    }

    @Test fun dataSaverChoosesBestStreamWithinLimit() {
        val low = stream(MediaFormat.WEBMA_OPUS, 48)
        assertSame(low, selectAudioStream(listOf(stream(MediaFormat.M4A, 128), low, stream(MediaFormat.WEBMA_OPUS, 32)), 64_000))
    }
    @Test fun unavailableLimitFallsBackToLowestSupportedRate() {
        val lowest = stream(MediaFormat.M4A, 128)
        assertSame(lowest, selectAudioStream(listOf(stream(MediaFormat.WEBMA_OPUS, 256), lowest), 64_000))
    }
    @Test fun qualityLimitsNeverSelectAlternateDubs() {
        val original = stream(MediaFormat.M4A, 128, type = AudioTrackType.ORIGINAL)
        assertSame(original, selectAudioStream(listOf(original, stream(MediaFormat.WEBMA_OPUS, 32, type = AudioTrackType.DUBBED)), 64_000))
    }
}
