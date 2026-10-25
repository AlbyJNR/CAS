package dev.alder

import android.app.AlertDialog
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import org.json.JSONObject

// The profile's addons, in the account's order (the order decides which addon answers first): add one by its
// manifest URL, and OK on an addon to move it up or down or remove it. Every change is saved to Stremio at once.
class AddonsActivity : Screen() {
    private lateinit var list: LinearLayout
    private var addons: List<Addon> = emptyList()

    private val scene by lazy { ImageView(this).apply { setImageResource(R.drawable.settings_scene); scaleType = ImageView.ScaleType.CENTER_CROP } }
    override val backdrop get() = scene

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val key = authKey ?: return finish()
        list = column(Gravity.START).apply { setPadding(dp(PAD - GLASS_INSET), dp(15), dp(PAD - GLASS_INSET), dp(30)) }
        // The list on a pane of glass inset from the edges, like Settings and the episode list.
        setWindowed(FrameLayout(this).apply {
            addView(ScrollView(context).apply { addView(list); background = glassOver(this, 14) },
                FrameLayout.LayoutParams((resources.displayMetrics.widthPixels * 0.58f).toInt(), -1, Gravity.START)
                    .apply { setMargins(dp(GLASS_INSET), dp(GLASS_INSET), 0, dp(GLASS_INSET)) })
        }, scene)
        show(null, "Loading…")
        background({ Stremio.addons(key, refresh = true) }) { result ->
            result.onSuccess { addons = it; show(null) }.onFailure { show(null, "Couldn't reach Stremio: ${it.message}") }
        }
    }

    /** Redraws the list, focusing [focus] (an addon's URL), or "Add by URL" when null. */
    private fun show(focus: String?, message: String? = null) {
        list.removeAllViews()
        list.addView(text("Addons", DISPLAY, bold = true))
        list.addView(text("The first addon that has something answers first. Select one to reorder or remove it.", BODY, color = TEXT_MID).marginTop(4))
        val add = row("+  Add by URL", "›") { askUrl() }.also { list.addView(it, LinearLayout.LayoutParams(-1, dp(40)).apply { topMargin = dp(16) }) }
        message?.let { list.addView(text(it, BODY, color = TEXT_MID).marginTop(16)) }
        var focused: View = add
        addons.forEach { a ->
            val r = row(a.name, if (a.locked) "Built-in  ›" else "›") { edit(a) }
            list.addView(r, LinearLayout.LayoutParams(-1, dp(40)))
            if (a.transportUrl == focus) focused = r
        }
        focused.post { focused.requestFocus() }
    }

    private fun row(label: String, value: String, click: () -> Unit) = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(10), 0, dp(10), 0)
        addView(text(label, BODY, color = TEXT_HI).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }, LinearLayout.LayoutParams(0, -2, 1f))
        addView(text(value, BODY, color = TEXT_MID))
        rowFocus(6)
        setOnClickListener { click() }
    }

    private fun edit(a: Addon) {
        val i = addons.indexOf(a)
        val actions = listOfNotNull(
            if (i > 0) "Move up" to { save(addons.toMutableList().apply { add(i - 1, removeAt(i)) }, a.transportUrl) } else null,
            if (i < addons.size - 1) "Move down" to { save(addons.toMutableList().apply { add(i + 1, removeAt(i)) }, a.transportUrl) } else null,
            if (!a.locked) "Remove" to {
                choose("Remove ${a.name} from this profile?", listOf("Cancel", "Remove")) { n ->
                    if (n == 1) save(addons - a, addons.getOrNull(i + 1)?.transportUrl)
                }
                Unit
            } else null,
        )
        choose(a.name, actions.map { it.first }) { n -> actions[n].second() }
    }

    private fun askUrl() {
        val field = EditText(this).apply {
            hint = "https://…/manifest.json"
            setSingleLine()
            imeOptions = EditorInfo.IME_ACTION_DONE
        }
        val dialog = AlertDialog.Builder(this).setTitle("Add an addon").setView(field)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Add") { _, _ -> install(field.text.toString().trim()) }.show()
        field.setOnEditorActionListener { _, _, _ -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); true }
    }

    // Stremio's install links are stremio://host/…/manifest.json; a bare addon address gets /manifest.json added.
    private fun install(typed: String) {
        if (typed.isEmpty()) return
        val url = typed.replaceFirst(Regex("^stremio://", RegexOption.IGNORE_CASE), "https://")
            .let { if (it.contains("://")) it else "https://$it" }
            .let { if (it.endsWith("/manifest.json")) it else it.trimEnd('/') + "/manifest.json" }
        if (addons.any { it.transportUrl == url }) return show(url, "Already installed.")
        background({
            val manifest = JSONObject(Http.get(url))
            require(manifest.has("id") && manifest.has("resources")) { "That address isn't a Stremio addon." }
            Addon(JSONObject().put("transportUrl", url).put("manifest", manifest)
                .put("flags", JSONObject().put("official", false).put("protected", false)))
        }) { result ->
            result.onSuccess { save(addons + it, url) }
                .onFailure { show(null, "Couldn't add it: ${it.message ?: "network error"}") }
        }
    }

    private fun save(next: List<Addon>, focus: String?) {
        val key = authKey ?: return
        background({ Stremio.setAddons(key, next) }) { result ->
            result.onSuccess { addons = next; show(focus) }
                .onFailure { show(focus, "Couldn't save: ${it.message ?: "network error"}") }
        }
    }
}
