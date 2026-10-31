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

    @Test fun imdbTrailerPicksTheTrailerAndOrdersQualities() {
        val json = JSONObject("""{"data":{"title":{"primaryVideos":{"edges":[
            {"node":{"id":"vi1","name":{"value":"Clip: Opening"},"contentType":{"displayName":{"value":"Clip"}},
             "playbackURLs":[{"displayName":{"value":"720p"},"url":"https://c/clip720.mp4","videoMimeType":"MP4"}]}},
            {"node":{"id":"vi2","name":{"value":"Official Trailer"},"contentType":{"displayName":{"value":"Trailer"}},
             "playbackURLs":[{"displayName":{"value":"480p"},"url":"https://c/t480.mp4","videoMimeType":"MP4"},
                             {"displayName":{"value":"AUTO"},"url":"https://c/t.m3u8","videoMimeType":"M3U8"},
                             {"displayName":{"value":"1080p"},"url":"https://c/t1080.mp4","videoMimeType":"MP4"}]}}
        ]}}}}""")
        val streams = parseImdbTrailerStreams(json)
        assertEquals(listOf("https://c/t1080.mp4", "https://c/t.m3u8", "https://c/t480.mp4"), streams.map { it.url })
        assertEquals("Official Trailer (1080p)", streams[0].title)
        assertEquals("Official Trailer", streams[1].title)
    }

    @Test fun imdbTrailerEmptyWhenNoVideos() {
        assertEquals(emptyList<Stream>(), parseImdbTrailerStreams(JSONObject("""{"data":{"title":{"primaryVideos":{"edges":[]}}}}""")))
    }

    @Test fun trailerFilesComeFromTrailerStreamUrls() {
        val m = meta("""{"id":"tt1","name":"X","trailerStreams":[{"title":"Teaser","url":"https://a/teaser.mp4"},{"title":"Y","ytId":"dQw4w9WgXcQ"}]}""")
        assertEquals(listOf("https://a/teaser.mp4"), m.trailerFiles.map { it.url })
        assertEquals(listOf("dQw4w9WgXcQ"), m.trailers)
    }
}
