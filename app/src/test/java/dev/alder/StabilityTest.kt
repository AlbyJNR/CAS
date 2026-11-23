package dev.alder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The rules that keep CAS steady: the row band holding its row, memory given back, a failing site left alone. */
class StabilityTest {
    @Test fun bandJumpsOnlyWhenItsRowMoved() {
        assertNull(heldScroll(null, 0)) // no row to hold
        assertNull(heldScroll(424, 424)) // where snapTo sent it: a smooth scroll on its way is left alone
        assertEquals(636, heldScroll(424 + 212, 424)) // a row arrived above: follow the row down
        assertEquals(212, heldScroll(212, 424)) // a row above went away: follow it up
        assertEquals(0, heldScroll(-3, 424))
    }

    @Test fun memoryIsGivenBackByHowHardAndroidAsks() {
        assertNull(trimShares(5)) // TRIM_MEMORY_RUNNING_MODERATE: nothing yet
        assertEquals(0.5f to 0.5f, trimShares(10))
        assertEquals(0.5f to 0.5f, trimShares(15))
        assertEquals(0.5f to 0f, trimShares(20)) // off the screen: no full-screen art is needed
        assertEquals(0f to 0f, trimShares(40))
        assertEquals(0f to 0f, trimShares(80))
    }

    @Test fun seriesPageSaysWhichEpisodeAndWhy() {
        assertEquals("Continue", whereLead(series = true, started = true, pastFirst = true))
        assertEquals("Up next", whereLead(series = true, started = false, pastFirst = true))
        assertNull(whereLead(series = true, started = false, pastFirst = false))
        assertNull(whereLead(series = false, started = true, pastFirst = false))
    }

    @Test fun realDebridAddonsAreKnownByTheirAddress() {
        assertTrue(carriesRealDebrid("https://torrentio.strem.fun/sort=qualitysize|realdebrid=XXXX/manifest.json"))
        assertTrue(carriesRealDebrid("https://example.org/Real-Debrid/abc/manifest.json"))
        assertFalse(carriesRealDebrid("https://v3-cinemeta.strem.io/manifest.json"))
    }

    @Test fun finishedMeansPastTheCreditsForLongAndShortTitles() {
        val min = 60_000L
        assertTrue(Library.finished(109 * min, 120 * min)) // a film, past nine tenths
        assertFalse(Library.finished(100 * min, 120 * min))
        assertTrue(Library.finished(9 * min + 30_000, 11 * min)) // a cartoon stopped as its credits start (86%)
        assertFalse(Library.finished(5 * min, 11 * min)) // little left only counts once it is watched
        assertFalse(Library.finished(50 * min, 0))
    }

    @Test fun aFinishedEpisodeWaitsOnlyForAnEpisodeThatIsOut() {
        fun item() = org.json.JSONObject().put("state", org.json.JSONObject().put("timeOffset", 58 * 60_000L).put("duration", 60 * 60_000L).put("video_id", "tt1:1:3"))
        val out = item().also { Library.stopped(it, "tt1:1:4") }
        assertEquals(1L, out.getJSONObject("state").getLong("timeOffset")) // stays in Continue Watching for episode 4
        val notOut = item().also { Library.stopped(it, "tt1:2:1", nextAired = false) }
        assertEquals(0L, notOut.getJSONObject("state").getLong("timeOffset")) // leaves the row
        assertEquals("tt1:2:1", notOut.getJSONObject("state").getString("video_id")) // and the page still knows what is next
        val last = item().also { Library.stopped(it, null) }
        assertEquals(0L, last.getJSONObject("state").getLong("timeOffset"))
        assertTrue(Library.continueWatching(listOf(notOut.put("type", "series"), last.put("type", "series"))).isEmpty())
    }

    @Test fun aPersonAndTheirTitlesParse() {
        val who = parseImdbPerson(org.json.JSONObject("""{"data":{"advancedNameSearch":{"edges":[{"node":{"name":{"id":"nm0000198","nameText":{"text":"Gary Oldman"},
            "primaryImage":{"url":"https://x/a._V1_.jpg"},"primaryProfessions":[{"category":{"text":"Actor"}},{"category":{"text":"Producer"}}]}}}]}}}"""))!!
        assertEquals("nm0000198", who.id)
        assertEquals(listOf("Actor", "Producer"), who.jobs)
        assertNull(parseImdbPerson(org.json.JSONObject("""{"data":{"advancedNameSearch":{"edges":[]}}}""")))
        assertTrue(imdbPersonQuery("Gary \"O\" Oldman").contains("searchTerm"))
        assertTrue(imdbPersonTitlesQuery("nm0000198").contains("anyNameIds:[\\\"nm0000198\\\"]"))
        val titles = parseImdbPersonTitles(org.json.JSONObject("""{"data":{"advancedTitleSearch":{"edges":[{"node":{"title":{"id":"tt5875444","titleText":{"text":"Slow Horses"},"titleType":{"id":"tvSeries"}}}}]}}}"""))
        assertEquals("series", titles[0].type)
        assertEquals("Slow Horses", titles[0].meta().name)
        assertEquals(3, titles[0].ranked(3).rank)
    }

    @Test fun aFilmographyComesInPagesInTheOrderAsked() {
        val newestTv = imdbFilmographyQuery("nm1", "GB", newest = true, kind = TV, after = "abc")
        assertTrue(newestTv.contains("sortBy:RELEASE_DATE,sortOrder:DESC"))
        assertTrue(newestTv.contains("after:\\\"abc\\\""))
        assertFalse(newestTv.contains("\\\"movie\\\""))
        assertTrue(imdbFilmographyQuery("nm1", "GB", newest = false, kind = ALL, after = null).contains("sortBy:POPULARITY"))
        val page = parseFilmography(org.json.JSONObject("""{"data":{"advancedTitleSearch":{"total":233,"pageInfo":{"endCursor":"next","hasNextPage":true},
            "edges":[{"node":{"title":{"id":"tt1","titleText":{"text":"A"},"titleType":{"id":"movie"}}}}]}}}"""))
        assertEquals(233, page.total)
        assertEquals("next", page.cursor)
        assertNull(parseFilmography(org.json.JSONObject("""{"data":{"advancedTitleSearch":{"total":1,"pageInfo":{"endCursor":"x","hasNextPage":false},"edges":[]}}}""")).cursor)
        assertEquals("21 March 1958", longDay("1958-03-21"))
        assertEquals("1958", longDay("1958"))
    }

    @Test fun anAnswerHasALimitOnTheWholeCall() {
        assertEquals(30_000, callLimit(20_000))
        assertEquals(18_000, callLimit(12_000))
        val cache = java.util.concurrent.ConcurrentHashMap<String, String>()
        repeat(4) { cache.capped(2)["k$it"] = "v" }
        assertEquals(1, cache.size) // past the limit it starts again, with the newest
    }

    @Test fun ageRatingsTakeTheirBandsColour() {
        assertEquals(certColour("U"), certColour("TV-G"))
        assertEquals(certColour("PG"), certColour("tv-pg"))
        assertEquals(certColour("12A"), certColour("PG-13"))
        assertEquals(certColour("15"), certColour("R"))
        assertEquals(certColour("18"), certColour("TV-MA"))
        assertTrue(setOf("U", "PG", "12", "15", "18", "Unrated").map(::certColour).toSet().size == 6)
    }

    @Test fun nothingToPlayIsSaidOnlyWhenEveryAddonAnsweredWithNone() {
        val none = Result.success(Addon.Streams(emptyList(), 2))
        assertTrue(noSources(listOf(none, none)))
        assertFalse(noSources(emptyList())) // no stream add-ons: not known
        assertFalse(noSources(listOf(none, Result.failure(java.io.IOException("down"))))) // one failed: it may have had one
    }

    @Test fun namesLoseTheirHtmlCodes() {
        assertEquals("Catherine O'Hara", plain("Catherine O&apos;Hara"))
        assertEquals("Tom & Jerry", plain("Tom &amp; Jerry"))
        assertEquals("Danny Elfman", plain("Danny Elfman"))
    }

    @Test fun metacriticListsAndScoresParse() {
        val list = org.json.JSONObject("""{"data":{"items":[
            {"title":"Slow Horses","premiereYear":2022,"criticScoreSummary":{"score":82,"reviewCount":30}},
            {"title":"Barely Seen","premiereYear":2026,"criticScoreSummary":{"score":99,"reviewCount":3}},
            {"title":"No Score","premiereYear":2026,"criticScoreSummary":{"score":0,"reviewCount":40}}]}}""")
        assertEquals(listOf("Slow Horses"), parseMcList(list).map { it.title }) // too few reviews, or no score, is left out
        assertEquals(82, mcMatch(list, "slow horses!", 2023))
        assertNull(mcMatch(list, "Slow Horses", 2010)) // a different title of the same name
        assertNull(mcMatch(list, "Fast Horses", null))
        assertTrue(mcListUrl(McList.BEST_TV, 2025).contains("productType=tv&page=1&releaseYearMin=2025"))
        assertTrue(mcSearchUrl("The Batman", false).endsWith("/search/The%20Batman/web?mcoTypeId=2&limit=5"))
        assertTrue(setOf(metaColour(80), metaColour(50), metaColour(20)).size == 3)
        assertEquals("Slow Horses", McPick.of(parseMcList(list)[0].json()).title)
    }

    private fun serves(resources: String, top: String = "") = manifestServes(org.json.JSONObject(
        """{"id":"x","name":"X","types":["movie","series"],$top"resources":$resources}"""), "stream", "movie", "tt1")

    @Test fun addonsAreAskedWhenStremioWouldAskThem() {
        assertTrue(serves("""["stream"]""")) // no prefixes anywhere: every id
        assertTrue(serves("""["stream"]""", """"idPrefixes":[],""")) // an empty list is every id
        assertTrue(serves("""[{"name":"stream","types":["movie"]}]""", """"idPrefixes":["kitsu"],""")) // written in full with no prefixes: every id, not the manifest's
        assertFalse(serves("""[{"name":"stream","types":["movie"],"idPrefixes":["kitsu"]}]"""))
        assertFalse(serves("""["stream"]""", """"idPrefixes":["kitsu"],"""))
        assertFalse(serves("""["meta"]"""))
    }

    @Test fun anAddonListIsSavedOnlyWhenItWasReadWhole() {
        assertNull(Stremio.unsafeToSave(true, 0, listOf("cinemeta"), listOf("cinemeta")))
        assertNull(Stremio.unsafeToSave(true, 0, listOf("cinemeta"), listOf("cinemeta", "torrentio")))
        assertTrue(Stremio.unsafeToSave(false, 0, listOf("cinemeta"), listOf("cinemeta", "mine")) != null) // not read fresh
        assertTrue(Stremio.unsafeToSave(true, 0, null, listOf("mine")) != null) // never read: an empty list plus one
        assertTrue(Stremio.unsafeToSave(true, 1, listOf("cinemeta"), listOf("cinemeta")) != null) // one unreadable would be dropped
        assertTrue(Stremio.unsafeToSave(true, 0, listOf("cinemeta"), listOf("mine")) != null) // Stremio's own must stay
    }

    @Test fun progressMadeOfflineGoesUpUnlessStremioHasNewer() {
        fun item(id: String, at: String) = org.json.JSONObject().put("_id", id).put("_mtime", at)
        val fetched = listOf(item("tt1", "2026-11-23T10:00:00.000Z"), item("tt2", "2026-11-23T12:00:00.000Z"))
        val waiting = listOf(item("tt1", "2026-11-23T11:00:00.000Z"), item("tt2", "2026-11-23T11:00:00.000Z"), item("tt3", "2026-11-23T09:00:00.000Z"))
        assertEquals(listOf("tt1", "tt3"), Stremio.newerThan(fetched, waiting).map { it.getString("_id") }) // tt2 was watched elsewhere since
    }

    @Test fun thePlayersBufferFollowsTheMemoryAndroidGives() {
        assertNull(bufferBytesFor(192L * 1024 * 1024))
        assertEquals(200 * 1024 * 1024, bufferBytesFor(512L * 1024 * 1024))
    }

    @Test fun aNewVersionWaitsItsDays() {
        val day = 24 * 3600_000L
        assertFalse(ripe(10 * day, 11 * day, 2))
        assertTrue(ripe(10 * day, 12 * day, 2))
        assertTrue(ripe(10 * day, 10 * day, 0)) // at once
        assertTrue(ripe(0, 10 * day, 2)) // no date known: not held back for ever
    }

    @Test fun guardsCountBeforeTheyAct() {
        assertFalse(needsRecovery(1)); assertTrue(needsRecovery(2))
        assertFalse(restNow(1)); assertTrue(restNow(2))
        assertFalse(pinLocks(2)); assertTrue(pinLocks(3))
    }

    @Test fun addressesLoseEverythingAfterTheHost() {
        assertEquals("failed https://torrentio.strem.fun/… now", scrubbed("failed https://torrentio.strem.fun/realdebrid=SECRETKEY/stream/movie/tt1.json now"))
        assertFalse(scrubbed("java.io.IOException: http://192.168.1.5:7000/abc/manifest.json").contains("abc"))
        assertEquals("no address here", scrubbed("no address here"))
    }

    @Test fun aSettingKeepsItsKindInACopy() {
        assertEquals("b", profileValue(true).getString("t"))
        assertEquals(2, profileValue(2).getInt("v"))
        assertEquals("s", profileValue("auto").getString("t"))
        assertEquals("f", profileValue(1.15f).getString("t"))
    }

    @Test fun onlyARefusingOrBrokenSiteRests() {
        assertTrue(restsAfter(403))
        assertTrue(restsAfter(429))
        assertTrue(restsAfter(503))
        assertFalse(restsAfter(200))
        assertFalse(restsAfter(404)) // one page missing is not the site failing
        assertTrue("api.graphql.imdb.com" in GUEST_HOSTS)
        assertFalse("api.strem.io" in GUEST_HOSTS) // Stremio is never rested: Retry must reach it
    }
}
