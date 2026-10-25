package dev.alder

import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import androidx.media3.common.MimeTypes
import java.io.IOException
import java.util.Locale

/**
 * One installed addon, spoken to with the Stremio addon protocol: plain GETs for catalogs, metadata and streams.
 * [descriptor] is the account's entry for it as Stremio stores it, kept whole so it can be copied to another profile.
 */
class Addon(val descriptor: JSONObject) {
    private val manifest: JSONObject = descriptor.getJSONObject("manifest")
    val id: String = manifest.optString("id")
    val name: String = manifest.optString("name")
    val transportUrl: String = descriptor.getString("transportUrl")
    /** Stremio's built-ins (Cinemeta, Local Files), which every account already has. */
    val official = descriptor.optJSONObject("flags")?.optBoolean("official") == true
    /** Stremio's own that can't be removed (Cinemeta, Local Files); they can still be moved. */
    val locked = descriptor.optJSONObject("flags")?.optBoolean("protected") == true
    private val base = transportUrl.removeSuffix("/manifest.json")

    /** [pages]: the catalog takes Stremio's `skip` extra, so a row can ask for the titles after the first page. */
    class Catalog(val addon: Addon, val type: String, val id: String, val name: String, val pages: Boolean, val genres: List<String>)

    /** Catalogs Home can show: the ones that need no required input such as a search query. */
    val catalogs: List<Catalog> = manifest.optJSONArray("catalogs").objects()
        .filter { c ->
            (c.optJSONArray("extraRequired")?.length() ?: 0) == 0 &&
                c.optJSONArray("extra").objects().none { it.optBoolean("isRequired") }
        }
        .map { c ->
            val extras = c.optJSONArray("extraSupported").strings() + c.optJSONArray("extra").objects().map { it.optString("name") }
            // The genres it can filter by: the protocol's `genre` extra options, or the older `genres` list.
            val genres = c.optJSONArray("extra").objects().firstOrNull { it.optString("name") == "genre" }?.optJSONArray("options").strings()
                .ifEmpty { c.optJSONArray("genres").strings() }
            Catalog(this, c.getString("type"), c.getString("id"), c.optString("name").ifEmpty { c.getString("id") }, "skip" in extras, genres)
        }

    /** Whether this addon answers [resource] ("meta", "stream", …) for this type and id. */
    fun serves(resource: String, type: String, id: String): Boolean {
        val entry = manifest.optJSONArray("resources").items()
            .firstOrNull { (it as? String ?: (it as? JSONObject)?.optString("name")) == resource } ?: return false
        val own = entry as? JSONObject
        val types = (own?.optJSONArray("types") ?: manifest.optJSONArray("types")).strings()
        val prefixes = (own?.optJSONArray("idPrefixes") ?: manifest.optJSONArray("idPrefixes"))?.strings()
        return type in types && (prefixes == null || prefixes.any { id.startsWith(it) })
    }

    // Catalogs (one [genre] of them with Stremio's `genre` extra) and details are kept on disk as they arrive; the saved*
    // versions read last time's answer, no network.
    fun catalog(c: Catalog, genre: String? = null): List<Meta> = get(true, *catalogPath(c, genre)).optJSONArray("metas").objects().map(::Meta)

    /** The titles after the first [skip] (Stremio's paging extra); not kept on disk. */
    fun catalog(c: Catalog, skip: Int): List<Meta> = get(false, "catalog", c.type, c.id, "skip=$skip").optJSONArray("metas").objects().map(::Meta)

    fun savedCatalog(c: Catalog, genre: String? = null): List<Meta>? = saved(*catalogPath(c, genre))?.optJSONArray("metas")?.objects()?.map(::Meta)

    private fun catalogPath(c: Catalog, genre: String?) = listOfNotNull("catalog", c.type, c.id, genre?.let { "genre=$it" }).toTypedArray()

    /** The addon's first advertised search catalog for each title type. */
    fun search(query: String): List<Meta> {
        require(query.isNotBlank())
        val catalogs = manifest.optJSONArray("catalogs").objects()
            .filter { c ->
                c.optString("type") in listOf("movie", "series") &&
                    "search" in (c.optJSONArray("extraRequired").strings() + c.optJSONArray("extraSupported").strings() +
                        c.optJSONArray("extra").objects().map { it.optString("name") })
            }
            .distinctBy { it.optString("type") }
        fun fetch(c: JSONObject): List<Meta> {
            val path = url(arrayOf("catalog", c.getString("type"), c.getString("id"))).removeSuffix(".json")
            val searchUrl = "$path/search=${Uri.encode(query)}.json"
            val cached = Http.savedAnswer(searchUrl)?.let { runCatching { JSONObject(it).optJSONArray("metas")?.objects()?.map(::Meta) }.getOrNull() }
            if (cached != null) return cached
            val answer = JSONObject(Http.get(searchUrl, true))
            return (answer.optJSONArray("metas") ?: throw IOException("Search catalog failed")).objects().map(::Meta)
        }
        val answers = catalogs.map { c ->
                runCatching { fetch(c) }
            }
        if (answers.isNotEmpty() && answers.all { it.isFailure }) throw IOException("Search catalog unavailable")
        return answers.flatMap { it.getOrDefault(emptyList()) }
    }

    fun meta(type: String, id: String): Meta? = get(true, "meta", type, id).optJSONObject("meta")?.let(::Meta)

    fun savedMeta(type: String, id: String): Meta? = saved("meta", type, id)?.optJSONObject("meta")?.let(::Meta)

    /** A title-specific catalog, when an installed addon advertises the protocol's required `similar` extra. */
    fun similar(type: String, id: String): List<Meta>? {
        val catalog = manifest.optJSONArray("catalogs").objects().firstOrNull { c ->
            c.optString("type") == type && c.optJSONArray("extra").objects().any {
                it.optString("name") == "similar" && it.optBoolean("isRequired")
            } && c.optJSONArray("idPrefixes").strings().let { it.isEmpty() || it.any(id::startsWith) }
        } ?: return null
        val path = url(arrayOf("catalog", type, catalog.getString("id"))).removeSuffix(".json")
        return JSONObject(Http.get("$path/similar=${Uri.encode(id, ":")}.json", true))
            .optJSONArray("metas").objects().map(::Meta)
    }

    /** Playable streams only: torrent-only results (no url) need a streaming server, which this app doesn't have. */
    fun streams(type: String, videoId: String): List<Stream> =
        get(false, "stream", type, videoId).optJSONArray("streams").objects().mapNotNull { Stream.from(it) }

    /** Subtitle files for one video (OpenSubtitles and co. through Stremio's `subtitles` resource). */
    fun subtitles(type: String, videoId: String): List<Subtitle> =
        get(false, "subtitles", type, videoId).optJSONArray("subtitles").objects().mapNotNull { s ->
            Subtitle(s.text("url") ?: return@mapNotNull null, s.text("lang") ?: "und", name)
        }

    // Ids such as "tt0903747:1:2" keep their colons, like Stremio's own requests.
    private fun url(path: Array<out String>) = base + "/" + path.joinToString("/") { Uri.encode(it, ":=") } + ".json"

    private fun get(keep: Boolean, vararg path: String) = JSONObject(Http.get(url(path), keep))

    private fun saved(vararg path: String) = Http.savedAnswer(url(path))?.let { runCatching { JSONObject(it) }.getOrNull() }
}

class Meta(json: JSONObject) {
    val id: String = json.getString("id")
    val type: String = json.optString("type")
    val name: String = json.optString("name")
    val poster = json.text("poster")
    val background = json.text("background")
    /** The title as artwork (AIOMetadata and Cinemeta provide it), shown instead of plain text when present. */
    val logo = json.text("logo")
    val description = json.text("description")
    val year = json.text("releaseInfo")?.trimEnd('–', '-', ' ') // "2026–" (still running) reads as 2026 everywhere
    val runtime = json.text("runtime")
    val rating = json.text("imdbRating")
    val genres = json.optJSONArray("genres").strings().take(3)
    val info = listOfNotNull(year, runtime, genres.joinToString(" · ").ifEmpty { null }).joinToString("   ")

    /** Episodes in play order: by season then episode, specials (season 0) last. */
    val videos: List<Video> = json.optJSONArray("videos").objects()
        .map {
            Video(it.getString("id"), it.text("title") ?: it.text("name") ?: "", it.optInt("season"), it.optInt("episode"),
                it.text("thumbnail"), it.text("overview") ?: it.text("description"))
        }
        .sortedWith(compareBy({ if (it.season == 0) Int.MAX_VALUE else it.season }, { it.episode }))
}

/** [lang] is what the addon sends: ISO 639-2 ("eng") from OpenSubtitles, sometimes a two-letter code. */
class Subtitle(val url: String, val lang: String, val addon: String) {
    val language: String get() = Locale(lang).displayLanguage.ifBlank { lang }
    val mimeType: String get() = url.substringBefore('?').lowercase().let {
        when {
            it.endsWith(".vtt") -> MimeTypes.TEXT_VTT
            it.endsWith(".ass") || it.endsWith(".ssa") -> MimeTypes.TEXT_SSA
            else -> MimeTypes.APPLICATION_SUBRIP
        }
    }
    /** Whether it's in the TV's language (either code style). */
    val local: Boolean get() = Locale.getDefault().let { lang == it.language || runCatching { lang == it.isO3Language }.getOrDefault(false) }
}

class Video(val id: String, val title: String, val season: Int, val episode: Int, val thumbnail: String?, val overview: String?)

class Stream(
    val title: String, val description: String, val url: String, val headers: Map<String, String>,
    val size: Long, val filename: String?, val bingeGroup: String?,
) {
    /**
     * False for addon "actions" dressed up as streams, such as MyTrakt's "Mark Watched" (a 27 KB placeholder file).
     * They stay listed but are never picked or focused first. Unknown size counts as a video.
     */
    val isVideo get() = size == 0L || size >= 50L * 1024 * 1024

    /** The addon's name for it on one line: "Torrentio (2160p)" becomes "Torrentio  ·  2160p". */
    val label get() = title.lines().joinToString(" ") { it.trim() }.replace(Regex("\\s*\\((.+?)\\)\\s*$"), "  ·  $1")

    // What the release is, read from its file name and the addon's labels (AIOStreams writes "2160p", "DV", "HEVC"
    // into them; the file name says it for every addon). ponytail: name matching, not the file's real tracks.
    private val words = "$filename $title $description".lowercase()
    val height = when {
        Regex("2160|\\b4k\\b|\\buhd\\b").containsMatchIn(words) -> 2160
        "1080" in words -> 1080
        "720" in words -> 720
        else -> 0
    }
    val isHdr get() = hdr || dolbyVision
    val dolbyVision = Regex("\\b(dv|dovi|dolby.?vision)\\b").containsMatchIn(words)
    val hdr = Regex("\\bhdr|\\bp8\\b|hybrid").containsMatchIn(words) // P8 and hybrid DV carry an HDR10 base
    private val hevc = Regex("hevc|[xh].?265").containsMatchIn(words)
    private val av1 = Regex("\\bav1\\b").containsMatchIn(words)

    // The release type and extras, for Settings' stream preferences.
    val remux = Regex("\\bremux\\b").containsMatchIn(words)
    val bluray = !remux && Regex("blu.?ray|\\bbd.?rip|\\bbr.?rip").containsMatchIn(words)
    val web = Regex("web.?(dl|rip)|\\bweb\\b").containsMatchIn(words)
    /** Filmed in a cinema or a screener: never picked. */
    val cam = Regex("\\b(cam|camrip|hdcam|ts|hdts|telesync|tc|telecine|scr|screener|dvdscr)\\b").containsMatchIn(words)
    private val threeD = Regex("\\b3d\\b").containsMatchIn(words)
    private val hardSubs = Regex("\\bhc\\b|hardcoded|hardsub").containsMatchIn(words)
    /** 3D or subtitles burnt into the picture: picked last. */
    val awkward get() = threeD || hardSubs
    val bigAudio = Regex("atmos|truehd|dts.?hd|dts.?x\\b").containsMatchIn(words) // what a receiver gains from
    val multiAudio = Regex("\\b(multi|dual)\\b").containsMatchIn(words)
    val dubbed = Regex("\\bdub(bed)?\\b").containsMatchIn(words)
    /** Whether the names mention this language ("hindi" for "hi"). */
    fun mentions(language: String) = Locale(language).getDisplayLanguage(Locale.ENGLISH).lowercase().let { it.length > 2 && it in words }

    /** Short tags for the source list: "REMUX · DV · Atmos". */
    val tags get() = listOfNotNull(
        "REMUX".takeIf { remux }, "BluRay".takeIf { bluray }, "WEB".takeIf { web && !remux && !bluray },
        "DV".takeIf { dolbyVision }, "HDR".takeIf { hdr }, "Atmos".takeIf { "atmos" in words },
        "TrueHD".takeIf { "truehd" in words }, "DTS-HD".takeIf { Regex("dts.?hd").containsMatchIn(words) },
        "Multi audio".takeIf { multiAudio }, "Dub".takeIf { dubbed }, "3D".takeIf { threeD }, "Hard subs".takeIf { hardSubs },
    )

    /**
     * Whether this TV can decode it at all. Dolby Vision without an HDR10 base named (profile 5, common on web
     * releases) shows purple and green without a Dolby Vision decoder, so it's left out; DV with HDR10 plays as HDR10.
     */
    fun playableOn(tv: Device): Boolean =
        (!hevc || tv.hevc) && (!av1 || tv.av1) && (!dolbyVision || hdr || tv.dolbyVision)

    /** How well it suits this TV, higher first: nothing above the screen's resolution, HDR only on an HDR screen. */
    fun fit(tv: Device): Int = (if (height > tv.height) 0 else 2) + (if (hdr || dolbyVision) (if (tv.hdr) 0 else -1) else 0)

    fun toJson(): JSONObject = JSONObject().put("name", title).put("description", description).put("url", url)
        .put("headers", JSONObject(headers)).put("size", size).put("filename", filename).put("binge", bingeGroup)

    companion object {
        fun from(json: JSONObject): Stream? {
            val url = json.text("url") ?: return null
            val hints = json.optJSONObject("behaviorHints")
            val headers = hints?.optJSONObject("proxyHeaders")?.optJSONObject("request")
            return Stream(
                json.text("name") ?: "",
                json.text("description") ?: json.text("title") ?: "",
                url,
                headers?.keys()?.asSequence()?.associateWith { headers.getString(it) } ?: emptyMap(),
                hints?.optLong("videoSize") ?: 0L,
                hints?.text("filename"),
                hints?.text("bingeGroup"),
            )
        }

        /** Back from [toJson], for the player's list of sources to try. */
        fun read(json: JSONObject): Stream {
            val headers = json.optJSONObject("headers") ?: JSONObject()
            return Stream(json.optString("name"), json.optString("description"), json.getString("url"),
                headers.keys().asSequence().associateWith { headers.getString(it) }, json.optLong("size"),
                json.text("filename"), json.text("binge"))
        }
    }
}

fun JSONArray?.items(): List<Any> = if (this == null) emptyList() else (0 until length()).map { get(it) }

fun JSONArray?.objects(): List<JSONObject> = items().filterIsInstance<JSONObject>()

fun JSONArray?.strings(): List<String> = items().filterIsInstance<String>()

/** The string at [key], or null when it's missing, JSON null or blank (optString would return "null" for JSON null). */
fun JSONObject.text(key: String): String? = if (isNull(key)) null else optString(key).ifBlank { null }
