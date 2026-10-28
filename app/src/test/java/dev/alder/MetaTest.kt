package dev.alder

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class MetaTest {
    private fun meta(json: String) = Meta(JSONObject(json))

    @Test fun trailersComeFromBothCinemetaShapes() {
        val m = meta("""{"id":"tt1","name":"X","trailers":[{"source":"dQw4w9WgXcQ","type":"Trailer"},{"source":"aaaaaaaaaaa","type":"Clip"}],
            "trailerStreams":[{"title":"X","ytId":"bbbbbbbbbbb"},{"title":"again","ytId":"dQw4w9WgXcQ"}]}""")
        assertEquals(listOf("dQw4w9WgXcQ", "bbbbbbbbbbb"), m.trailers)
    }

    @Test fun trailersThatAreNotYouTubeIdsAreDropped() {
        // An add-on's data ends up inside a script: anything but an 11-character id is refused.
        val m = meta("""{"id":"tt1","name":"X","trailers":[{"source":"abc","type":"Trailer"},{"source":"x');alert(1);('","type":"Trailer"}]}""")
        assertEquals(emptyList<String>(), m.trailers)
    }

    @Test fun trailersAbsentIsEmpty() {
        assertEquals(emptyList<String>(), meta("""{"id":"tt1","name":"X"}""").trailers)
    }

    @Test fun castAndDirectorAreLists() {
        val m = meta("""{"id":"tt1","name":"X","cast":["A","B"],"director":["D"]}""")
        assertEquals(listOf("A", "B"), m.cast)
        assertEquals(listOf("D"), m.director)
    }
}
