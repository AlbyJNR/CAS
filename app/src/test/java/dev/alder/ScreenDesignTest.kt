package dev.alder

import org.junit.Assert.assertEquals
import org.junit.Test

class ScreenDesignTest {
    @Test fun posterGlowUsesArtAndCapsBrightPosters() {
        assertEquals(0x8CFF0000.toInt(), filmGlow(intArrayOf(0xFFFF0000.toInt())))
        assertEquals(0x8CB2B2B2.toInt(), filmGlow(intArrayOf(0xFFFFFFFF.toInt())))
    }

    @Test fun shadeRespondsToBrightnessOnTheTextSide() {
        assertEquals(true, brightLeft(intArrayOf(0xFFFFFFFF.toInt(), 0xFF000000.toInt()), 2))
        assertEquals(false, brightLeft(intArrayOf(0xFF000000.toInt(), 0xFFFFFFFF.toInt()), 2))
    }

    @Test fun homeSynopsisEndsAtACompleteSentenceWhenClipped() {
        assertEquals("First sentence.", synopsisAtSentence("First sentence. The second is longer.", 23))
        assertEquals("No punctuation here", synopsisAtSentence("No punctuation here", 7))
        val city = "Sgt. Wayne Jenkins leads a unit. It goes wrong."
        assertEquals(city, synopsisAtSentence(city, 20)) // only "Sgt." fits: no sentence, Android's ellipsis instead
        assertEquals("Sgt. Wayne Jenkins leads a unit.", synopsisAtSentence(city, 40))
        assertEquals("Agents in the U.S. hunt a spy.", synopsisAtSentence("Agents in the U.S. hunt a spy. Then more.", 35))
    }

    @Test fun homeRecommendationRowHasTheShortLabel() {
        assertEquals("More like Dune", homeRowName("Because you watched Dune"))
        assertEquals("More like Crayon Shin-chan", homeRowName("Because you watched Crayon Shin-chan: Pursuit"))
        assertEquals("Popular", homeRowName("Popular"))
    }

    @Test fun seasonCountIsSingularForOne() {
        assertEquals("1 season", seasons(1))
        assertEquals("5 seasons", seasons(5))
    }

    @Test fun catalogGenresUseAvailableOptionsInScreenOrder() {
        assertEquals(listOf("Drama", "Comedy", "Sci-Fi", "Western"),
            catalogGenres(listOf("Western", "Comedy", "All", "Drama", "Sci-Fi", "drama")))
    }

    @Test fun catalogRowsHaveShortNames() {
        assertEquals("For you", shortRowName("Top Picks for You"))
        assertEquals("Popular", shortRowName("Trending Now"))
        assertEquals("Drama", shortRowName("Drama"))
        assertEquals("Recommended in India", shortRowName("Recommended in India"))
        assertEquals("Comedy Sci-Fi", shortRowName("Comedy Science Fiction"))
        assertEquals("Adventure", shortRowName("Short & Sweet Adventure", listOf("Drama", "Adventure")))
    }

    @Test fun runtimeReadsInHoursAndMinutes() {
        assertEquals("2h 27m", runtime("147 min"))
        assertEquals("1h 05m", runtime("65 min"))
        assertEquals("45 min", runtime("45 min"))
        assertEquals("1h 30min", runtime("1h 30min"))
    }

    @Test fun addonStatusIsNotATitle() {
        assertEquals(true, isAddonMessage("Trakt Authentication Required"))
        assertEquals(false, isAddonMessage("The Authentication"))
    }
}
