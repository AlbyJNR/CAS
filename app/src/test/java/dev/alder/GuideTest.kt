package dev.alder

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GuideTest {
    private val now = "2026-10-31T13:00:00.000Z"

    @Test fun anEpisodeStillToComeSaysWhen() {
        assertEquals("Airs 7 Nov", airs("2026-11-07T00:00:00.000Z", now))
        assertEquals("Airs 7 Nov", airs("2026-11-07", now))
        assertEquals("Airs 14 Jan 2027", airs("2027-01-14T00:00:00.000Z", now)) // another year: say which
    }

    @Test fun anEpisodeOutAlreadySaysNothing() {
        assertNull(airs("2026-10-31T00:00:00.000Z", now)) // out this morning
        assertNull(airs("2026-10-31", now))
        assertNull(airs("2026-10-30T00:00:00.000Z", now))
        assertNull(airs(null, now))
        assertNull(airs("soon", now))
    }

    @Test fun imdbCertificateIsTheRatingText() {
        assertEquals("TV-MA", parseImdbCertificate(JSONObject("""{"data":{"title":{"certificate":{"rating":"TV-MA"}}}}""")))
        assertNull(parseImdbCertificate(JSONObject("""{"data":{"title":{"certificate":null}}}""")))
        assertNull(parseImdbCertificate(JSONObject("""{"data":{"title":null}}""")))
        assertNull(parseImdbCertificate(JSONObject("""{"data":{"title":{"certificate":{"rating":"Not Rated For Anyone"}}}}"""))) // a sentence is not a rating
    }

    @Test fun certificationComesFromEitherField() {
        assertEquals("PG-13", Meta(JSONObject("""{"id":"tt1","name":"X","certification":"PG-13"}""")).certification)
        assertEquals("15", Meta(JSONObject("""{"id":"tt1","name":"X","contentRating":" 15 "}""")).certification)
        assertNull(Meta(JSONObject("""{"id":"tt1","name":"X"}""")).certification)
    }
}
