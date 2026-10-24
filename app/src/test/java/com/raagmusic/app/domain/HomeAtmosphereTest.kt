package com.raagmusic.app.domain

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDateTime

class HomeAtmosphereTest {
    @Test fun presetIsStableWithinPeriodAndChangesAtEveryTimeBoundary() {
        val date = LocalDateTime.of(2026, 10, 3, 5, 0)
        assertEquals(homeAtmosphere(date), homeAtmosphere(date.withHour(10).withMinute(59)))
        listOf(5, 11, 17, 22).forEach { hour ->
            val boundary = date.withHour(hour)
            assertNotEquals(homeAtmosphere(boundary.minusMinutes(1)), homeAtmosphere(boundary))
        }
    }
    @Test fun differentDatesVaryPresetsWithoutRecompositionRandomness() {
        val date = LocalDateTime.of(2026, 10, 3, 12, 0)
        assertTrue((0L..30L).map { homeAtmosphere(date.plusDays(it)) }.distinct().size > 1)
        assertEquals(homeAtmosphere(date), homeAtmosphere(date))
    }
    @Test fun audienceCountsUseReadableUnits() {
        assertEquals("13M monthly listeners", compactMonthlyAudience("13,000,000 monthly listeners"))
        assertEquals("4.2M monthly audience", compactMonthlyAudience("4.2M monthly audience"))
        assertEquals("0", compactAudienceCount(0))
        assertEquals("999", compactAudienceCount(999))
        assertEquals("1K", compactAudienceCount(1000))
        assertEquals("12.5K", compactAudienceCount(12_500))
        assertEquals("4.2M", compactAudienceCount(4_200_000))
        assertEquals("1.2B", compactAudienceCount(1_200_000_000))
    }
}
