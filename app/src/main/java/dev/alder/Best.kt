package dev.alder

import dev.alder.Screen.Companion.DOT
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

// ---------- Ranked lists (Alan, 2026-11-28): a person's best work, and the best of a genre ----------
//
// Both are IMDb's ratings, which CAS reads already. A person's list is what they made in their own job (a director's
// films, an actor's parts), not every title that thanks them. A genre's list is IMDb's own order with a floor of
// votes. Nothing here is fetched until its screen is opened.

/** A title and how many people rated it: a rating means little without the number behind it. */
internal class Rated(val pick: ImdbPick, val votes: Int)

internal const val BEST_SHOWN = 50
internal const val CREDITS_ASKED = 250 // the most IMDb gives in one answer; a long career fits
internal const val BEST_FEW = 10 // fewer well-known titles than this: the floor of votes drops, so the list is not bare
internal const val BEST_LOW_VOTES = 1000
internal const val GENRE_FILM_VOTES = 100_000
internal const val GENRE_TV_VOTES = 25_000

/** The jobs that make a title someone's own work, as IMDb names them. */
internal val BEST_JOBS = listOf("director", "actor", "actress", "writer")

/** The job a person's ranked list is of: the first of their professions ([jobs], IMDb's order) that is one of [BEST_JOBS]. */
internal fun bestJob(jobs: List<String>): String? = jobs.map { it.trim().lowercase(Locale.ROOT) }.firstOrNull { it in BEST_JOBS }

/** What IMDb needs for one line of a ranked list: far less than a row's card asks for. */
internal const val RANK_FIELDS = "id titleText{text}titleType{id}primaryImage{url}releaseYear{year}ratingsSummary{aggregateRating voteCount}"

private fun typeIds(kind: Int) = when (kind) { FILMS -> """["movie"]"""; TV -> """["tvSeries","tvMiniSeries"]"""; else -> """["movie","tvSeries","tvMiniSeries"]""" }

/** Every film or series (by [kind]) that [nameId] made as [job], in one answer. */
internal fun creditsQuery(nameId: String, job: String, kind: Int, region: String): String = JSONObject().put("query",
    "query CAS_$region{name(id:\"$nameId\"){credits(first:$CREDITS_ASKED,filter:{categories:[\"$job\"],titleType:${typeIds(kind)}}){edges{node{title{$RANK_FIELDS}}}}}}").toString()

internal fun parseCredits(json: JSONObject): List<Rated> =
    json.optJSONObject("data")?.optJSONObject("name")?.optJSONObject("credits")?.optJSONArray("edges").objects()
        .mapNotNull { it.optJSONObject("node")?.optJSONObject("title") }
        .mapNotNull { t -> imdbPick(t)?.let { Rated(it, t.optJSONObject("ratingsSummary")?.optInt("voteCount") ?: 0) } }

/**
 * The best of [all], numbered from one: the highest rating first, the more votes first between equals. A title needs
 * enough votes for its rating to mean something (a film more than a series); someone with few such titles gets a
 * lower floor. A title with no rating has no place.
 */
internal fun best(all: List<Rated>, limit: Int = BEST_SHOWN): List<ImdbPick> {
    val known = all.distinctBy { it.pick.id }.filter { it.pick.rating?.toDoubleOrNull() != null }
    val solid = known.filter { it.votes >= if (it.pick.type == "series") BEST_TV_VOTES else BEST_FILM_VOTES }
    val pool = if (solid.size >= BEST_FEW) solid else known.filter { it.votes >= BEST_LOW_VOTES }
    return pool.sortedWith(compareByDescending<Rated> { it.pick.rating!!.toDouble() }.thenByDescending { it.votes })
        .take(limit).mapIndexed { i, r -> r.pick.ranked(i + 1) }
}

/** A person's best films, series or both as [job], from IMDb. Blocks. */
internal fun imdbBestOf(nameId: String, job: String, kind: Int, country: String): List<ImdbPick> =
    best(parseCredits(imdbAsk(creditsQuery(nameId, job, kind, country), country)))

/** The line over a person's ranked list: whose work it is, and how much of it. */
internal fun bestCaption(job: String, shown: Int): String =
    "As ${job}$DOT${if (shown == 1) "1 title" else "$shown titles"}"

// ---------- A genre's best ----------

/** The genres a ranked list can be of, as IMDb spells them. */
internal val RANK_GENRES = listOf("Action", "Adventure", "Animation", "Comedy", "Crime", "Documentary", "Drama", "Family", "Fantasy",
    "History", "Horror", "Mystery", "Romance", "Sci-Fi", "Thriller", "War", "Western")

/** Not what "the best TV" means: chat, quizzes, news, shorts. */
private val RANK_EXCLUDED = listOf("Reality-TV", "Talk-Show", "Game-Show", "News", "Short")

/** The floor of votes for a genre's list. The small genres have few titles that a hundred thousand people rated. */
internal fun genreVotes(genre: String?, tv: Boolean): Int = when (genre) {
    "Documentary" -> if (tv) 10_000 else 20_000
    "Western", "War", "History" -> if (tv) 10_000 else 30_000
    else -> if (tv) GENRE_TV_VOTES else GENRE_FILM_VOTES
}

/** IMDb's highest rated films or series, of [genre] or of all (null), with a floor of votes and of rating. */
internal fun genreBestQuery(genre: String?, tv: Boolean, region: String): String {
    val only = genre?.let { "allGenreIds:[${JSONObject.quote(it)}]," }.orEmpty()
    return JSONObject().put("query", "query CAS_$region{advancedTitleSearch(first:$BEST_SHOWN,sort:{sortBy:USER_RATING,sortOrder:DESC},constraints:{" +
        "titleTypeConstraint:{anyTitleTypeIds:${typeIds(if (tv) TV else FILMS)}}," +
        "userRatingsConstraint:{aggregateRatingRange:{min:$BEST_RATING},ratingsCountRange:{min:${genreVotes(genre, tv)}}}," +
        "genreConstraint:{${only}excludeGenreIds:${JSONArray(RANK_EXCLUDED)}},explicitContentConstraint:{explicitContentFilter:EXCLUDE_ADULT}})" +
        "{edges{node{title{$RANK_FIELDS}}}}}").toString()
}

/** A genre's best from IMDb, kept on disk for a time with no connection. Blocks. */
internal fun genreBest(genre: String?, tv: Boolean, country: String): List<ImdbPick> =
    parseBest(imdbAsk(genreBestQuery(genre, tv, country), country))

/** The list from the last run's answer on disk, or null. */
internal fun savedGenreBest(genre: String?, tv: Boolean, country: String): List<ImdbPick>? =
    Http.savedAnswer(IMDB_URL, genreBestQuery(genre, tv, country))?.let { runCatching { parseBest(JSONObject(it)) }.getOrNull() }?.ifEmpty { null }

/** A rating's band, for the colour of its badge: 3 for 8.0 and up, 2 for 7.0, 1 for 6.0, else 0. */
internal fun scoreBand(rating: String?): Int {
    val r = rating?.toDoubleOrNull() ?: return 0
    return when { r >= 8.0 -> 3; r >= 7.0 -> 2; r >= 6.0 -> 1; else -> 0 }
}
