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
        assertEquals("1h 30m", runtime("1h 30min"))
        assertEquals("2h 35m", runtime("2h35min"))
    }

    @Test fun addonStatusIsNotATitle() {
        assertEquals(true, isAddonMessage("Trakt Authentication Required"))
        assertEquals(false, isAddonMessage("The Authentication"))
    }

    @Test fun myListKeepsTheLastPartialRow() {
        assertEquals(0, listRowCount(0, 6))
        assertEquals(1, listRowCount(6, 6))
        assertEquals(2, listRowCount(7, 6))
        assertEquals(4, listRowCount(24, 6))
    }

    @Test fun settingsChoicesWrapAndCustomStartsAtTheEndForLeft() {
        assertEquals(0, nextSettingIndex(-1, 4, 1))
        assertEquals(3, nextSettingIndex(-1, 4, -1))
        assertEquals(0, nextSettingIndex(3, 4, 1))
        assertEquals(3, nextSettingIndex(0, 4, -1))
    }

    @Test fun searchMovesExactTitlesFirstWithoutReorderingTheRest() {
        val titles = listOf("Dune Part Two", "Arrival", " DUNE ", "Dune Messiah", "dune")
        assertEquals(listOf(" DUNE ", "dune", "Dune Part Two", "Arrival", "Dune Messiah"),
            exactSearchFirst(titles, "Dune") { it })
    }

    @Test fun episodeDateUsesOnlyValidAvailableDates() {
        assertEquals("25 Sep 2013", episodeDate("2013-09-25T00:00:00.000Z"))
        assertEquals(null, episodeDate("2013-02-30"))
        assertEquals(null, episodeDate(null))
    }

    @Test fun repeatedBufferingOffersOneRealQualityStep() {
        assertEquals(1080, lowerSourceHeight(2160))
        assertEquals(720, lowerSourceHeight(1080))
        assertEquals(null, lowerSourceHeight(480))
    }

    @Test fun sourceRowsUseFileFactsWithoutShowingAddonDescriptions() {
        val stream = Stream("Torrentio (1080p)", "extra text", "https://example.invalid/video", emptyMap(),
            405_000_000L, "First.Days.1080p.BluRay.HEVC.5.1.mkv", null)
        assertEquals(1080, stream.height)
        assertEquals("BluRay · HEVC · 5.1", sourceSummary(stream))
        assertEquals("405 MB", sourceSize(stream.size))
        assertEquals(480, Stream("480p", "", "https://example.invalid/low", emptyMap(), 175_000_000L, null, null).height)
    }
}
