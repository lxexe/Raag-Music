package com.raagmusic.app.domain

import java.time.LocalDateTime
import org.junit.Assert.*
import org.junit.Test

class ListenerProfileTest {
    @Test fun optionalNameIsCleanedWithoutInventingAName() {
        assertEquals("", ListenerProfile("  ").displayName)
        assertEquals("Lx", ListenerProfile("  Lx\n \u0000 ").sanitized().name)
        assertEquals(40, ListenerProfile("a".repeat(100)).sanitized().name.length)
    }

    @Test fun greetingsUseLocalTimeAndTheSavedName() {
        val profile = ListenerProfile("Lx")
        val day = LocalDateTime.of(2026, 10, 6, 9, 0)
        assertEquals("Good morning, Lx", homeGreeting(profile, day).title)
        assertEquals("Good afternoon, Lx", homeGreeting(profile, day.withHour(14)).title)
        assertEquals("Good evening, Lx", homeGreeting(profile, day.withHour(19)).title)
        assertEquals("Hello, night owl, Lx", homeGreeting(profile, day.withHour(23)).title)
        assertEquals("Good morning", homeGreeting(ListenerProfile(), day).title)
    }

    @Test fun weekAndWeekendMessagesVaryAcrossDaysAndWeeks() {
        val monday = LocalDateTime.of(2026, 10, 5, 9, 0)
        val profile = ListenerProfile()
        val friday = homeGreeting(profile, monday.plusDays(4)).message
        val weekend = homeGreeting(profile, monday.plusDays(5)).message
        assertTrue(friday.contains("Friday") || friday.contains("week"))
        assertTrue(weekend.contains("weekend") || weekend.contains("day off"))
        assertNotEquals(homeGreeting(profile, monday).message, homeGreeting(profile, monday.plusWeeks(1)).message)
        assertNotEquals(homeGreeting(profile, monday).message, homeGreeting(profile, monday.withHour(23)).message)
    }
}
