package dev.alder

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

class Profile(val id: String, val name: String, val color: Int, val authKey: String, val pin: String? = null)

/**
 * The profiles on this TV, kept in app storage. Each one is its own Stremio account, so history, Continue Watching,
 * addons and recommendations (Watchly) all stay per person without anything of our own in the cloud.
 */
object Profiles {
    const val MAX = 5
    val COLORS = intArrayOf(0xFFF3D9B1.toInt(), 0xFFC98E6A.toInt(), 0xFF8FA39A.toInt(), 0xFFA6A2BD.toInt(), 0xFFD3A66B.toInt())

    fun all(context: Context): List<Profile> {
        val prefs = prefs(context)
        migrate(prefs)
        return JSONArray(prefs.getString("profiles", "[]")).objects()
            .map { Profile(it.getString("id"), it.getString("name"), it.getInt("color").takeIf { c -> c in COLORS } ?: COLORS[0],
                it.getString("authKey"), it.optString("pin").ifEmpty { null }) }
    }

    /** The profile in use: the one picked last, or the only one there is. */
    fun active(context: Context): Profile? {
        val list = all(context)
        return list.firstOrNull { it.id == prefs(context).getString("active", null) } ?: list.singleOrNull()
    }

    fun setActive(context: Context, id: String) = prefs(context).edit().putString("active", id).apply()

    fun add(context: Context, name: String, authKey: String, color: Int = COLORS[0]): Profile {
        val list = all(context)
        val number = (1..MAX).first { n -> list.none { it.id == "$n" } }
        val profile = Profile("$number", name, color, authKey)
        save(prefs(context), list + profile)
        setActive(context, profile.id)
        return profile
    }

    fun remove(context: Context, id: String) {
        val prefs = prefs(context)
        save(prefs, all(context).filterNot { it.id == id })
        if (prefs.getString("active", null) == id) prefs.edit().remove("active").apply()
        val edit = prefs.edit().remove("streamSource:$id").remove("streamAddon:$id")
        all(context).filter { prefs.getString("streamSource:${it.id}", null) == id }.forEach {
            edit.remove("streamSource:${it.id}").remove("streamAddon:${it.id}")
        }
        edit.apply()
        Settings.forget(context, id)
        context.getSharedPreferences("search", Context.MODE_PRIVATE).edit().remove("recent:$id").apply()
    }

    /** One owner's configured stream add-on, used only on this TV; watch progress still uses the active account. */
    fun sharedStream(context: Context): Pair<Profile, String>? {
        val current = active(context) ?: return null
        val prefs = prefs(context)
        val source = all(context).firstOrNull { it.id == prefs.getString("streamSource:${current.id}", null) } ?: return null
        return prefs.getString("streamAddon:${current.id}", null)?.let { source to it }
    }

    fun shareStream(context: Context, targetId: String, sourceId: String, addonId: String) {
        require(targetId != sourceId && all(context).any { it.id == targetId } && all(context).any { it.id == sourceId })
        prefs(context).edit().putString("streamSource:$targetId", sourceId).putString("streamAddon:$targetId", addonId).apply()
    }

    fun clearSharedStream(context: Context, targetId: String) = prefs(context).edit()
        .remove("streamSource:$targetId").remove("streamAddon:$targetId").apply()

    /** Rename or set the PIN of a profile (clearPin removes it), keeping its place in the list. */
    fun update(context: Context, id: String, name: String? = null, pin: String? = null, clearPin: Boolean = false, color: Int? = null) {
        save(prefs(context), all(context).map {
            if (it.id != id) it
            else Profile(it.id, name ?: it.name, color ?: it.color, it.authKey, if (clearPin) null else pin ?: it.pin)
        })
    }

    private fun save(prefs: SharedPreferences, list: List<Profile>) {
        val json = JSONArray(list.map {
            JSONObject().put("id", it.id).put("name", it.name).put("color", it.color).put("authKey", it.authKey).put("pin", it.pin ?: "")
        })
        prefs.edit().putString("profiles", json.toString()).apply()
    }

    // Before profiles there was one sign-in; it becomes the first profile.
    private fun migrate(prefs: SharedPreferences) {
        val key = prefs.getString("authKey", null) ?: return
        if (!prefs.contains("profiles")) save(prefs, listOf(Profile("1", "Me", COLORS[0], key)))
        prefs.edit().remove("authKey").putString("active", "1").apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences("account", Context.MODE_PRIVATE)
}
