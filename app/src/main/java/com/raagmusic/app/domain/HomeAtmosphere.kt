package com.raagmusic.app.domain

import java.time.LocalDateTime
import kotlin.random.Random

/** Stable within a time period, with a fresh preset each day. Unrelated to playback. */
fun homeAtmosphere(now: LocalDateTime): Pair<Double, Double> {
    val period = when (now.hour) { in 5..10 -> 0; in 11..16 -> 1; in 17..21 -> 2; else -> 3 }
    val presets = listOf(
        listOf(35.0 to 175.0, 320.0 to 38.0, 185.0 to 55.0),
        listOf(165.0 to 215.0, 205.0 to 265.0, 135.0 to 190.0),
        listOf(285.0 to 340.0, 335.0 to 30.0, 265.0 to 195.0),
        listOf(235.0 to 285.0, 275.0 to 185.0, 210.0 to 315.0),
    )
    return presets[period][Random(now.toLocalDate().toEpochDay() * 4 + period).nextInt(3)]
}
