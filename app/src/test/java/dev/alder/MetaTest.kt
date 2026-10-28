package dev.alder

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class MetaTest {
    private fun meta(json: String) = Meta(JSONObject(json))

    @Test fun trailersComeFromBothCinemetaShapes() {
        val m = meta("""{"id":"tt1","name":"X","trailers":[{"source":"abc","type":"Trailer"},{"source":"clip1","type":"Clip"}],
            "trailerStreams":[{"title":"X","ytId":"def"},{"title":"again","ytId":"abc"}]}""")
        assertEquals(listOf("abc", "def"), m.trailers)
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
