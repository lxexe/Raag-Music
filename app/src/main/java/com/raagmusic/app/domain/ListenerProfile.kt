package com.raagmusic.app.domain

import java.time.DayOfWeek
import java.time.LocalDateTime

data class ListenerProfile(val name: String = "", val namePromptSeen: Boolean = false) {
    fun sanitized() = copy(name = name.replace(Regex("\\s+"), " ").replace(Regex("[\\p{Cc}\\p{Cf}]"), "").trim().take(40))
    val displayName: String get() = sanitized().name
}

data class HomeGreeting(val title: String, val message: String)

/** Local device time, with daily variety that stays calm while the user browses. */
fun homeGreeting(profile: ListenerProfile, now: LocalDateTime): HomeGreeting {
    val period = when (now.hour) {
        in 5..11 -> "Good morning"
        in 12..16 -> "Good afternoon"
        in 17..21 -> "Good evening"
        else -> "Hello, night owl"
    }
    val messages = when {
        now.hour < 5 || now.hour >= 22 -> listOf("One more song before the world wakes.", "A little soundtrack for the quiet hours.", "Let the night find its rhythm.")
        now.dayOfWeek == DayOfWeek.FRIDAY -> listOf("Friday feels better with a soundtrack.", "Find your weekend's opening track.", "Wrap up the week. Turn up the music.")
        now.dayOfWeek in listOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY) -> listOf("Less rush. More music. It's the weekend.", "Make room for a new weekend favorite.", "Your day off deserves a great soundtrack.")
        now.dayOfWeek == DayOfWeek.MONDAY -> listOf("A fresh week. A fresh favorite.", "Start your week on a good note.", "Find the rhythm for what's ahead.")
        now.hour < 12 -> listOf("Ease into the day with something you love.", "New day, new sounds to discover.", "Give your morning a little melody.")
        now.hour < 17 -> listOf("A little music for your afternoon reset.", "Find your flow, one song at a time.", "Your next favorite is waiting.")
        else -> listOf("Wind down with the sounds you love.", "Let the evening play out beautifully.", "The day is yours. Give it a soundtrack.")
    }
    val variation = ((now.toLocalDate().toEpochDay() / 7) + now.dayOfWeek.value).toInt()
    val title = profile.displayName.takeIf { it.isNotBlank() }?.let { "$period, $it" } ?: period
    return HomeGreeting(title, messages[Math.floorMod(variation, messages.size)])
}
