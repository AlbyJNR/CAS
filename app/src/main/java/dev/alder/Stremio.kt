package dev.alder

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder

/**
 * Thin client for Stremio's own services (request shapes from stremio-core). Every call blocks,
 * so run them off the main thread.
 */
object Stremio {
    private const val API = "https://api.strem.io/api/"
    private const val LINK = "https://link.stremio.com/api/v2/"

    class Link(val code: String, val url: String)
    class ApiException(val code: Int, message: String) : IOException(message)

    /** Error code for a session that no longer exists (signed out elsewhere). */
    const val NO_SESSION = 1

    private val addonsCache = java.util.concurrent.ConcurrentHashMap<String, List<Addon>>() // per profile's auth key

    /**
     * TV sign-in, the same flow as Stremio's own TV app: show [Link.url] or the code, the person approves
     * it on their phone, and [readLink] then returns the account's auth key. The app never sees a password.
     */
    fun createLink(): Link = result(Http.get(LINK + "create?type=Create"))
        .let { Link(it.getString("code"), it.getString("link")) }

    /** Auth key once the code is approved, null until then. Stremio answers 101 for both "waiting" and "expired". */
    fun readLink(code: String): String? = try {
        result(Http.get(LINK + "read?type=Read&code=" + URLEncoder.encode(code, "UTF-8"))).getString("authKey")
    } catch (e: ApiException) {
        if (e.code == 101) null else throw e
    }

    /** The addons installed on the account, in the account's order. Kept in memory until [refresh]. */
    fun addons(authKey: String, refresh: Boolean = false): List<Addon> {
        if (!refresh) addonsCache[authKey]?.let { return it }
        val body = addonsBody(authKey)
        val answer = Http.post(API + "addonCollectionGet", body)
        return readAddons(authKey, result(answer))
            .also { addonsCache[authKey] = it; loaded += authKey; Http.keep(API + "addonCollectionGet", body, answer) }
    }

    /** The addon list as it was last time, from disk (null if never fetched), so Home can start before the network answers. */
    fun savedAddons(authKey: String): List<Addon>? = Http.savedAnswer(API + "addonCollectionGet", addonsBody(authKey))
        ?.let { runCatching { readAddons(authKey, result(it)) }.getOrNull() }
        ?.also { addonsCache.putIfAbsent(authKey, it) } // until the fresh list arrives, details are asked of these

    private fun addonsBody(authKey: String) =
        JSONObject().put("type", "AddonCollectionGet").put("authKey", authKey).put("update", true).toString()

    /** Replaces the account's addon list (and order) with [addons]. */
    // Add-ons whose entry could not be read, by auth key; and the keys whose list came fresh from Stremio in this run.
    private val unreadable = java.util.concurrent.ConcurrentHashMap<String, Int>()
    private val loaded = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /**
     * The account's add-ons, each read on its own: one entry CAS cannot read (a manifest missing, a field of the
     * wrong kind) is left out and counted, where before it failed the whole list and Home was empty.
     */
    private fun readAddons(authKey: String, json: JSONObject): List<Addon> {
        val entries = json.getJSONArray("addons").objects()
        val read = entries.mapNotNull { e -> runCatching { Addon(e) }.onFailure { android.util.Log.w("CAS", "an add-on could not be read: $it") }.getOrNull() }
        unreadable[authKey] = entries.size - read.size
        return read
    }

    /** Why [next] may not be saved as the account's whole add-on list, or null when it may: see [setAddons]. */
    internal fun unsafeToSave(fresh: Boolean, unreadable: Int, keptByStremio: List<String>?, next: List<String>): String? = when {
        !fresh || keptByStremio == null -> "the add-ons have not been read from Stremio yet"
        unreadable > 0 -> "one of the add-ons could not be read, and a save would remove it"
        keptByStremio.any { it !in next } -> "the change would remove an add-on Stremio keeps"
        else -> null
    }

    /**
     * Saves the account's add-on list. The list replaces the account's on every device, so it is refused unless it
     * was made from a list read fresh from Stremio in this run, whole: a save from a list that failed to load (an
     * empty one, plus the add-on just added) would wipe every add-on the account has.
     */
    fun setAddons(authKey: String, addons: List<Addon>) {
        unsafeToSave(authKey in loaded, unreadable[authKey] ?: 0, addonsCache[authKey]?.filter { it.locked }?.map { it.transportUrl }, addons.map { it.transportUrl })?.let { throw IllegalStateException("Add-ons not saved: $it") }
        val body = JSONObject().put("type", "AddonCollectionSet").put("authKey", authKey)
            .put("addons", JSONArray(addons.map { it.descriptor }))
        result(post("addonCollectionSet", body))
        addonsCache[authKey] = addons
    }

    /** Library items (watch history included): the given ids, or every item when [ids] is empty. */
    fun library(authKey: String, ids: List<String> = emptyList()): List<JSONObject> {
        val body = libraryBody(authKey, ids)
        val answer = Http.post(API + "datastoreGet", body)
        // Stremio is reachable: what could not be saved earlier (the TV was offline as a film stopped) goes up now,
        // where it is newer than what Stremio has, and shows in this answer.
        val items = newest(authKey, withUnsaved(authKey, libraryItems(answer), ids.isEmpty()))
        if (ids.isEmpty()) { Http.keep(API + "datastoreGet", body, answer); known[authKey] = items }
        return items
    }

    // The whole library as last fetched or read from disk, per auth key: a title page opened from Home reads its item
    // here instead of parsing the saved library (megabytes) again before its first block.
    private val known = java.util.concurrent.ConcurrentHashMap<String, List<JSONObject>>()

    /** The library as this process last had it, or null before any fetch. */
    fun knownLibrary(authKey: String): List<JSONObject>? = known[authKey]

    // Items saved here whose save may not have reached Stremio yet: the player saves as it closes, and the page behind
    // it asks Stremio at the same moment, so without this it showed the progress from before.
    private val saving = java.util.concurrent.ConcurrentHashMap<String, JSONObject>()

    /** The copy of [id] saved from here that Stremio may not have answered with yet, or null. */
    fun savingItem(authKey: String, id: String): JSONObject? = saving["$authKey/$id"]

    /** [items] with any newer copy saved from here in place of Stremio's: what a screen may draw before Stremio answers. */
    internal fun newest(authKey: String, items: List<JSONObject>) = items.map { item ->
        saving["$authKey/${item.optString("_id")}"]?.takeIf { it.optString("_mtime") > item.optString("_mtime") } ?: item
    }

    /** The whole library as it was last time, from disk, or null. */
    fun savedLibrary(authKey: String): List<JSONObject>? = Http.savedAnswer(API + "datastoreGet", libraryBody(authKey, emptyList()))
        ?.let { runCatching { newest(authKey, libraryItems(it)) }.getOrNull() }?.also { known.putIfAbsent(authKey, it) }

    private fun libraryBody(authKey: String, ids: List<String>) = JSONObject().put("authKey", authKey)
        .put("collection", "libraryItem").put("ids", JSONArray(ids)).put("all", ids.isEmpty()).toString()

    private fun libraryItems(answer: String): List<JSONObject> {
        val json = JSONObject(answer)
        json.optJSONObject("error")?.let { throw ApiException(it.optInt("code"), it.optString("message")) }
        val list = json.getJSONArray("result")
        return (0 until list.length()).map { list.getJSONObject(it) }
    }

    private fun putBody(authKey: String, items: Collection<JSONObject>) =
        JSONObject().put("authKey", authKey).put("collection", "libraryItem").put("changes", JSONArray(items))

    fun saveLibrary(authKey: String, items: List<JSONObject>) {
        items.forEach { saving["$authKey/${it.optString("_id")}"] = it }
        try {
            result(post("datastorePut", putBody(authKey, items)))
            unsaved(authKey).let { waiting -> if (items.any { it.optString("_id") in waiting }) keepUnsaved(authKey, (waiting - items.map { it.optString("_id") }.toSet()).values) }
        } catch (error: Throwable) {
            // Not saved (no internet as the film stopped): kept on disk, and sent the next time Stremio answers
            // ([withUnsaved]). It stays in [saving] too, so this run's screens show the progress made.
            keepUnsaved(authKey, (unsaved(authKey) + items.associateBy { it.optString("_id") }).values)
            throw error
        }
    }

    private fun unsavedKey(authKey: String) = "cas://unsaved/${authKey.hashCode()}"
    private fun unsaved(authKey: String): Map<String, JSONObject> =
        Http.savedAnswer(unsavedKey(authKey))?.let { runCatching { JSONArray(it).objects().associateBy { o -> o.optString("_id") } }.getOrNull() } ?: emptyMap()
    private fun keepUnsaved(authKey: String, items: Collection<JSONObject>) = Http.keep(unsavedKey(authKey), null, JSONArray(items).toString())

    /** [fetched] with the items waiting to be saved in place of Stremio's older copies; with [send], they are sent now. */
    private fun withUnsaved(authKey: String, fetched: List<JSONObject>, send: Boolean): List<JSONObject> {
        val waiting = unsaved(authKey)
        if (waiting.isEmpty()) return fetched
        val newer = newerThan(fetched, waiting.values)
        if (send) {
            if (newer.isEmpty()) keepUnsaved(authKey, emptyList())
            else runCatching { result(post("datastorePut", putBody(authKey, newer))) }.onSuccess { keepUnsaved(authKey, emptyList()) }
        }
        val byId = newer.associateBy { it.optString("_id") }
        return fetched.map { byId[it.optString("_id")] ?: it } + (if (send) newer.filter { n -> fetched.none { it.optString("_id") == n.optString("_id") } } else emptyList())
    }

    /** Of [waiting], the items changed later than Stremio's copy in [fetched] (or that Stremio does not have): watching on a phone since wins. */
    internal fun newerThan(fetched: List<JSONObject>, waiting: Collection<JSONObject>): List<JSONObject> {
        val have = fetched.associate { it.optString("_id") to it.optString("_mtime") }
        return waiting.filter { w -> (have[w.optString("_id")] ?: "") < w.optString("_mtime") }
    }

    /**
     * A profile's lists and settings, kept as one hidden item in its own Stremio library ([COPY_ID]): written from
     * one TV and read on another signed in to the same account. Stremio's apps do not show it (it is "removed", of
     * type "other"). Stremio keeps only the fields it knows and drops any other, so the copy travels in the item's
     * poster address, which nothing shows, squeezed and written as plain letters (checked whole at 60,000
     * characters by CI's screens job). Blocks.
     */
    fun saveCopy(authKey: String, copy: String) {
        val now = Library.now()
        val item = Library.newItem(COPY_ID, "other", "CAS: lists and settings", COPY_MARK + packed(copy)).put("removed", true).put("temp", false).put("_mtime", now)
        result(post("datastorePut", putBody(authKey, listOf(item))))
    }

    /** The copy saved with this account, or null when there is none (or it is not one CAS wrote). Blocks. */
    fun readCopy(authKey: String): String? =
        libraryItems(Http.post(API + "datastoreGet", libraryBody(authKey, listOf(COPY_ID)))).firstOrNull()?.optString("poster")
            ?.takeIf { it.startsWith(COPY_MARK) }?.let { runCatching { unpacked(it.removePrefix(COPY_MARK)) }.getOrNull() }

    private const val COPY_MARK = "cas1:"
    private fun packed(text: String): String = android.util.Base64.encodeToString(
        java.io.ByteArrayOutputStream().also { out -> java.util.zip.GZIPOutputStream(out).use { it.write(text.toByteArray()) } }.toByteArray(), android.util.Base64.NO_WRAP or android.util.Base64.URL_SAFE)
    private fun unpacked(text: String): String =
        java.util.zip.GZIPInputStream(android.util.Base64.decode(text, android.util.Base64.URL_SAFE).inputStream()).use { String(it.readBytes()) }

    const val COPY_ID = "cas:copy"

    /** The account behind a sign-in: each sign-in has its own auth key, so two keys can be the same account. */
    fun userId(authKey: String): String =
        result(post("getUser", JSONObject().put("type", "GetUser").put("authKey", authKey))).getString("_id")

    fun logout(authKey: String) {
        addonsCache.remove(authKey)
        post("logout", JSONObject().put("type", "Logout").put("authKey", authKey))
    }

    // Stremio wraps every answer as {"result": …} or {"error": {"code", "message"}}.
    private fun result(body: String): JSONObject {
        val json = JSONObject(body)
        json.optJSONObject("error")?.let { throw ApiException(it.optInt("code"), it.optString("message")) }
        return json.optJSONObject("result") ?: JSONObject()
    }

    private fun post(method: String, body: JSONObject) = Http.post(API + method, body.toString())
}
