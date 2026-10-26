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
        Settings.forget(context, id)
    }

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
