package dev.alder

import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerBadgesTest {
    @Test fun fullRemuxReadsInOrder() {
        assertEquals(listOf("4K", "Dolby Vision", "Atmos 7.1", "English", "Spanish subtitles", "Real-Debrid"),
            playerBadges(2160, "audio/eac3-joc", 8, "English", dolbyVision = true, hdr = true, subtitles = "Spanish", debrid = "Real-Debrid"))
    }

    @Test fun plainWebFileSaysOnlyWhatIsKnown() {
        assertEquals(listOf("1080p", "AAC", "English"),
            playerBadges(1080, "audio/mp4a-latm", 2, "English", dolbyVision = false, hdr = false, subtitles = null, debrid = null))
    }

    @Test fun hdrWithoutDolbyVisionAndDtsLayouts() {
        assertEquals(listOf("720p", "HDR", "DTS-HD 5.1"),
            playerBadges(720, "audio/vnd.dts.hd", 6, null, dolbyVision = false, hdr = true, subtitles = "", debrid = ""))
    }

    @Test fun nothingKnownIsNoBadges() {
        assertEquals(emptyList<String>(), playerBadges(0, null, 0, null, dolbyVision = false, hdr = false, subtitles = null, debrid = null))
        assertEquals(listOf("576p"), playerBadges(576, "audio/opus", 2, null, dolbyVision = false, hdr = false, subtitles = null, debrid = null))
    }
}
