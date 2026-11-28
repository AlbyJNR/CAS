package dev.alder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.json.JSONObject
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

    private val answer = """{"current":{"temperature_2m":6.2,"weather_code":3,"is_day":0},"daily":{"time":["2026-11-24","2026-11-25","2026-11-26","2026-11-27","2026-11-28","2026-11-29","2026-11-30"],"weather_code":[3,3,0,53,71,61,95],"temperature_2m_max":[6.6,7.0,5.4,6.4,11.5,10.8,9.1],"temperature_2m_min":[2.4,4.7,1.9,1.5,6.4,7.3,-0.6]}}"""

    @Test fun weatherReportGivesNowAndTheFiveDaysAfterToday() {
        val r = report(answer, "2026-11-24")!!
        assertEquals(6, r.now); assertEquals(false, r.day)
        assertEquals(listOf("Wed", "Thu", "Fri", "Sat", "Sun"), r.days.map { it.name })
        assertEquals(7, r.days[0].high); assertEquals(5, r.days[0].low)
    }

    @Test fun weatherCopyFromBeforeMidnightDropsTheDayThatIsNowToday() {
        val r = report(answer, "2026-11-25")!!
        assertEquals("Thu", r.days.first().name); assertEquals(5, r.days.size)
        assertEquals(-1, report(answer, "2026-11-29")!!.days.single().low)
    }

    @Test fun weatherAnswerThatIsNotAReportGivesNothing() {
        assertEquals(null, report("<html>busy</html>", "2026-11-24"))
        assertEquals(null, report("""{"error":true,"reason":"x"}""", "2026-11-24"))
        assertEquals(0, places("""{"generationtime_ms":1}""").size)
    }

    @Test fun weatherCopyShowsForTwelveHoursAndAsksAgainAfterThree() {
        val h = 3600_000L
        assertEquals(true, shows(100 * h, 111 * h)); assertEquals(false, shows(100 * h, 112 * h)); assertEquals(false, shows(0, h))
        assertEquals(false, asksAgain(100 * h, 102 * h)); assertEquals(true, asksAgain(100 * h, 103 * h)); assertEquals(true, asksAgain(100 * h, 99 * h))
    }

    @Test fun weatherSkyAndPlaceAndUrl() {
        assertEquals(Sky.MOON, sky(0, day = false)); assertEquals(Sky.RAIN, sky(81)); assertEquals(Sky.SNOW, sky(86)); assertEquals(Sky.CLOUD, sky(1234))
        assertEquals("Drizzle", skyWord(53, true))
        assertEquals("London, England, United Kingdom", places("""{"results":[{"name":"London","latitude":51.5,"longitude":-0.12,"admin1":"England","country":"United Kingdom"}]}""")[0].name)
        assertEquals(true, url(51.50853, -0.12574).contains("latitude=51.51&longitude=-0.13&"))
    }

    @Test fun weatherTownFromTheInternetAddress() {
        val p = here("""{"city":"Belfast","region":"Northern Ireland","country":"United Kingdom","latitude":"54.5968","longitude":"-5.9254"}""")!!
        assertEquals("Belfast", p.name); assertEquals(true, p.auto); assertEquals(54.5968, p.lat, 0.0001)
        assertEquals("Ireland", here("""{"country":"Ireland","latitude":"53.3","longitude":"-6.2"}""")!!.name)
        assertEquals(null, here("""{"city":"X","latitude":"nil","longitude":"nil"}"""))
        assertEquals(null, here("""{"city":"X","latitude":"0","longitude":"0"}"""))
        assertEquals(null, here("<html>"))
    }

    @Test fun weatherFoundTownIsLookedForOnceADayAndSmallShiftsKeepTheReport() {
        val h = 3600_000L
        assertEquals(false, looksAgain(100 * h, 123 * h)); assertEquals(true, looksAgain(100 * h, 124 * h)); assertEquals(true, looksAgain(0, h))
        val a = Weather.Place("A", 54.60, -5.93)
        assertEquals(false, moved(a, Weather.Place("A", 54.62, -5.95))); assertEquals(true, moved(a, Weather.Place("B", 54.2, -5.9)))
    }

    private fun rated(id: String, rating: String?, votes: Int, type: String = "movie") =
        Rated(ImdbPick(id, id, type, null, "2000", null, emptyList(), rating, null, null, null, null, null), votes)

    @Test fun aPersonsBestIsByRatingThenVotesWithAFloorOfVotes() {
        val many = (1..12).map { rated("tt$it", "7.${it % 10}", 20_000 + it) }
        val list = best(many + rated("ttfans", "9.9", 900) + rated("ttnone", null, 500_000) + rated("tt9", "9.0", 1) + rated("tttv", "9.5", 3_000, "series"))
        assertEquals("tttv", list[0].id); assertEquals(1, list[0].rank) // a series needs fewer votes than a film
        assertEquals("tt9", list[1].id); assertEquals("7.9", list[1].rating); assertEquals(2, list[1].rank) // the same title twice counts once
        assertEquals(false, list.any { it.id == "ttfans" || it.id == "ttnone" })
        assertEquals(13, list.size)
        // Someone with few well-known titles: the floor drops, so the list is not bare.
        assertEquals(listOf("b", "a"), best(listOf(rated("a", "6.0", 1_500), rated("b", "8.1", 1_200), rated("c", "9.0", 40))).map { it.id })
        assertEquals(50, best((1..80).map { rated("x$it", "7.0", 50_000 + it) }).size)
    }

    @Test fun aPersonsJobAndTheQueriesForRankedLists() {
        assertEquals("director", bestJob(listOf("Producer", "Director", "Actor"))); assertEquals("actress", bestJob(listOf("Actress", "Writer")))
        assertEquals(null, bestJob(listOf("Producer", "Composer")))
        assertEquals("As director  ·  1 title", bestCaption("director", 1))
        val q = JSONObject(creditsQuery("nm1", "director", FILMS, "GB")).getString("query")
        assertTrue(q.contains("""name(id:"nm1")""")); assertTrue(q.contains("""categories:["director"],titleType:["movie"]""")); assertTrue(q.contains("voteCount"))
        val parsed = parseCredits(JSONObject("""{"data":{"name":{"credits":{"edges":[
            {"node":{"title":{"id":"tt1","titleText":{"text":"One"},"titleType":{"id":"movie"},"ratingsSummary":{"aggregateRating":8.25,"voteCount":1200}}}},
            {"node":{"title":{"id":"nm9","titleText":{"text":"Not a title"}}}}]}}}}"""))
        assertEquals(1, parsed.size); assertEquals(1200, parsed[0].votes); assertEquals("8.3", parsed[0].pick.rating)
        val crime = JSONObject(genreBestQuery("Crime", tv = false, region = "GB")).getString("query")
        assertTrue(crime.contains("""allGenreIds:["Crime"]""")); assertTrue(crime.contains("ratingsCountRange:{min:100000}")); assertTrue(crime.contains("sortBy:USER_RATING,sortOrder:DESC"))
        val allTv = JSONObject(genreBestQuery(null, tv = true, region = "GB")).getString("query")
        assertEquals(false, allTv.contains("allGenreIds")); assertTrue(allTv.contains("ratingsCountRange:{min:25000}")); assertTrue(allTv.contains(""""tvSeries","tvMiniSeries""""))
        assertEquals(20_000, genreVotes("Documentary", tv = false))
        assertEquals(listOf(3, 2, 2, 1, 0, 0), listOf("8.0", "7.9", "7.0", "6.5", "5.9", null).map(::scoreBand))
    }

    @Test fun liveListKeepsWhatItCanPlayAndTheNewerCopyWins() {
        val list = parseLive("""{"v":1,"rev":3,"rows":[
            {"name":"News","channels":[
                {"id":"a","name":"A","kind":"stream","url":"https://x.test/a.m3u8","note":"UK only"},
                {"id":"a","name":"A again","kind":"stream","url":"https://x.test/a2.m3u8"},
                {"id":"b","name":"B","kind":"stream","url":"file:///sdcard/b"},
                {"id":"c","name":"C","kind":"twitch","channel":"espn1000chicago"},
                {"id":"d","name":"D","kind":"twitch","channel":"bad name/../x"},
                {"id":"e","name":"E","kind":"app","packages":["com.dazn","not a package"]},
                {"id":"f","name":"F","kind":"hologram","url":"https://x.test/f"},
                {"name":"No id","kind":"stream","url":"https://x.test/g"}]},
            {"name":"Empty","channels":[{"id":"z","name":"Z","kind":"stream"}]}]}""")!!
        assertEquals(3, list.rev); assertEquals(1, list.rows.size)
        assertEquals(listOf("a", "c", "e"), list.rows[0].channels.map { it.id })
        assertEquals("UK only", list.rows[0].channels[0].note); assertEquals(listOf("com.dazn"), list.rows[0].channels[2].packages)
        assertNull(parseLive("<html>")); assertNull(parseLive("""{"rows":[]}"""))
        val old = parseLive("""{"rev":2,"rows":[{"name":"N","channels":[{"id":"a","name":"A","kind":"stream","url":"https://x.test/a.m3u8"}]}]}""")!!
        assertEquals(3, newer(old, list)!!.rev); assertEquals(3, newer(list, old)!!.rev); assertEquals(2, newer(old, null)!!.rev); assertNull(newer(null, null))
        assertTrue(sameLive(list, list)); assertFalse(sameLive(list, old))
    }

    @Test fun liveChannelsGoRoundAndTheListIsReadAFewTimesADay() {
        assertEquals(0, liveStep(4, 1, 5)); assertEquals(4, liveStep(0, -1, 5)); assertEquals(3, liveStep(2, 1, 5)); assertEquals(0, liveStep(0, 1, 0))
        val h = 3600_000L
        assertFalse(liveAsksAgain(100 * h, 105 * h)); assertTrue(liveAsksAgain(100 * h, 106 * h)); assertTrue(liveAsksAgain(100 * h, 99 * h)); assertTrue(liveAsksAgain(0, h * 500_000))
        val page = twitchPage("espn1000chicago", "albyjnr.github.io", clear = false)
        assertTrue(page.contains("player.twitch.tv/?channel=espn1000chicago&parent=albyjnr.github.io")); assertTrue(page.contains("background:#000"))
    }

    @Test fun theAppsOwnLiveListIsWholeAndEveryChannelPlaysSomehow() {
        val file = listOf("src/main/assets/live.json", "app/src/main/assets/live.json").map { java.io.File(it) }.first { it.exists() }
        val list = parseLive(file.readText())!!
        assertEquals(listOf("Kids", "News", "Sport"), list.rows.map { it.name })
        val all = list.rows.flatMap { it.channels }
        assertEquals(JSONObject(file.readText()).getJSONArray("rows").let { rows -> (0 until rows.length()).sumOf { rows.getJSONObject(it).getJSONArray("channels").length() } }, all.size) // none left out
        assertEquals(all.size, all.map { it.id }.distinct().size)
        assertTrue(all.none { it.name.contains("YouTube", true) || it.name.contains("Alder", true) })
    }
}
