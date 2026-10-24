package com.raagmusic.app.playback

import android.media.audiofx.Equalizer
import com.raagmusic.app.domain.EqualizerSettings
import kotlin.math.ln

/** Effects belong to a player's audio session, never the global device mix. */
class PlaybackEqualizer(private val report: (Boolean, String) -> Unit = { _, _ -> }) {
    private var effect: Equalizer? = null
    private var sessionId = 0
    private var settings = EqualizerSettings()
    fun attach(id: Int) {
        if (id == sessionId && effect != null) return
        release(); sessionId = id
        if (id <= 0) { report(false, "Start playback to check audio effects."); return }
        try {
            effect = Equalizer(0, id)
            apply(settings)
        } catch (_: RuntimeException) {
            release(); report(false, "Equalizer isn't available on this audio output.")
        }
    }
    fun apply(value: EqualizerSettings) {
        settings = value
        val eq = effect ?: return
        try {
            if (!eq.hasControl()) { report(false, "Another audio effect currently controls this output."); return }
            val range = eq.bandLevelRange
            for (band in 0 until eq.numberOfBands.toInt()) {
                val frequency = eq.getCenterFreq(band.toShort()) / 1000.0
                val anchors = listOf(60.0, 230.0, 910.0, 3600.0, 14000.0)
                val nearest = anchors.indices.minBy { kotlin.math.abs(ln(frequency.coerceAtLeast(1.0) / anchors[it])) }
                eq.setBandLevel(band.toShort(), (value.gains[nearest] * 100).coerceIn(range[0].toInt(), range[1].toInt()).toShort())
            }
            eq.enabled = value.enabled
            report(true, if (value.enabled) "${value.preset.label} preset active" else "Equalizer off")
        } catch (_: RuntimeException) { report(false, "Equalizer isn't available on this audio output.") }
    }
    fun release() { runCatching { effect?.release() }; effect = null; sessionId = 0 }
}
