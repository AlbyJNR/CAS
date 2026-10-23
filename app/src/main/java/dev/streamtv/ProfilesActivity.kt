package dev.streamtv

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView

// "Who's watching?", plus adding a profile: name, Stremio sign-in, copying addons, and setting up Watchly.
// The app starts here; with a single profile it goes straight on to Home.
class ProfilesActivity : Screen() {
    // The sign-in code being waited on; anything else stops its polling loop.
    private var waitingCode: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val profiles = Profiles.all(this)
        when {
            profiles.isEmpty() -> showName()
            profiles.size == 1 && !intent.getBooleanExtra("pick", false) -> openHome(profiles[0])
            else -> showPicker()
        }
    }

    override fun onDestroy() {
        waitingCode = null
        super.onDestroy()
    }

    private fun openHome(profile: Profile) {
        Profiles.setActive(this, profile.id)
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    // ---------- who's watching ----------

    private fun showPicker() {
        val profiles = Profiles.all(this)
        // Padding leaves room for the focused tile to grow without its outline being cut off.
        val tiles = LinearLayout(this).apply { gravity = Gravity.CENTER; setPadding(dp(24), dp(24), dp(24), dp(24)) }
        profiles.forEach { p ->
            tiles.addView(tile(p.name, p.color, p.name.take(1).uppercase()).apply {
                setOnClickListener { openHome(p) }
                setOnLongClickListener { confirmRemove(p); true }
            })
        }
        if (profiles.size < Profiles.MAX) {
            tiles.addView(tile("Add profile", GLASS_LOW, "+").apply { setOnClickListener { showName() } })
        }
        setWindowed(column(Gravity.CENTER,
            text("Who's watching?", DISPLAY, bold = true),
            tiles.marginTop(48),
            text("Hold OK on a profile to remove it", BODY, color = MUTED).marginTop(40),
        ))
        val active = profiles.indexOfFirst { it.id == Profiles.active(this)?.id }
        tiles.getChildAt(maxOf(active, 0)).requestFocus()
    }

    private fun tile(label: String, color: Int, initial: String): View {
        val circle = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }
        val avatar = text(initial, 56f, bold = true).apply {
            gravity = Gravity.CENTER
            background = circle
            layoutParams = LinearLayout.LayoutParams(dp(140), dp(140))
        }
        return column(Gravity.CENTER_HORIZONTAL, avatar, text(label, BODY).marginTop(16)).apply {
            isFocusable = true
            setPadding(dp(16), dp(16), dp(16), dp(16))
            layoutParams = LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(16); marginEnd = dp(16) }
            // Focus: a white ring round the avatar and a slight grow, like Netflix's picker.
            setOnFocusChangeListener { v, has ->
                circle.setStroke(if (has) dp(5) else 0, Color.WHITE)
                v.animate().scaleX(if (has) 1.08f else 1f).scaleY(if (has) 1.08f else 1f).setDuration(120).start()
            }
        }
    }

    private fun confirmRemove(p: Profile) {
        AlertDialog.Builder(this)
            .setTitle("Remove ${p.name}?")
            .setMessage("This signs ${p.name} out on this TV. Their Stremio account, history and addons stay as they are.")
            .setPositiveButton("Remove") { _, _ ->
                Profiles.remove(this, p.id)
                background({ Stremio.logout(p.authKey) }) {} // best effort; the TV forgets the profile either way
                if (Profiles.all(this).isEmpty()) showName() else showPicker()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---------- add a profile ----------

    private fun showName() {
        val first = Profiles.all(this).isEmpty()
        val name = EditText(this).apply {
            hint = "Name"
            textSize = 24f
            setSingleLine()
            imeOptions = EditorInfo.IME_ACTION_DONE
            layoutParams = LinearLayout.LayoutParams(dp(480), -2).apply { topMargin = dp(32) }
        }
        val next = Button(this).apply { text = "Continue" }
        val go = { name.text.toString().trim().ifEmpty { null }?.let { showSignIn(it) } }
        name.setOnEditorActionListener { _, _, _ -> go(); true }
        next.setOnClickListener { go() }
        setWindowed(column(Gravity.CENTER,
            text(if (first) "Welcome" else "New profile", DISPLAY, bold = true),
            text("Each profile is its own Stremio account, so everyone gets their own history and recommendations.",
                BODY, color = MUTED).apply { maxWidth = dp(700); gravity = Gravity.CENTER }.marginTop(12),
            name,
            next.marginTop(24),
        ))
        name.requestFocus()
    }

    private fun showSignIn(name: String) {
        val qr = ImageView(this)
        val code = text("", 64f, bold = true)
        val status = text("Getting a sign-in code…", BODY, color = MUTED)
        val steps = column(Gravity.START,
            text("Sign in $name with Stremio", DISPLAY, bold = true),
            text("Scan the code with $name's phone, or go to\nlink.stremio.com and enter:", BODY, color = MUTED).marginTop(16),
            code.marginTop(12),
            text("No Stremio account? Make a free one at stremio.com first.", BODY, color = MUTED).marginTop(16),
            status.marginTop(16),
        )
        setWindowed(LinearLayout(this).apply {
            gravity = Gravity.CENTER
            addView(qr, LinearLayout.LayoutParams(dp(260), dp(260)).apply { marginEnd = dp(56) })
            addView(steps)
        })

        waitingCode = null
        background({ Stremio.createLink() }) { result ->
            result.onSuccess { link ->
                qr.setImageBitmap(qrBitmap(link.url, dp(260)))
                code.text = link.code
                status.text = "Waiting for you to approve it…"
                waitingCode = link.code
                poll(name, link.code, SystemClock.elapsedRealtime())
            }.onFailure {
                status.text = "Can't reach Stremio. Trying again…"
                main.postDelayed({ showSignIn(name) }, 5_000)
            }
        }
    }

    private fun poll(name: String, code: String, startedAt: Long) {
        main.postDelayed({
            if (waitingCode != code) return@postDelayed
            background({ Stremio.readLink(code) }) { result ->
                if (waitingCode != code) return@background
                val key = result.getOrNull()
                when {
                    key != null -> {
                        waitingCode = null
                        val others = Profiles.all(this)
                        val profile = Profiles.add(this, name, key)
                        // The first profile already has its addons; later ones can copy them from it.
                        if (others.isEmpty()) openHome(profile) else showCopyAddons(profile, others.first())
                    }
                    // ponytail: Stremio doesn't say when a code expires (waiting and expired look the same),
                    // so a fresh code every 5 minutes. Read the expiry if their API ever exposes it.
                    SystemClock.elapsedRealtime() - startedAt > 5 * 60_000 -> showSignIn(name)
                    else -> poll(name, code, startedAt) // not approved yet, or a network blip
                }
            }
        }, 3_000)
    }

    // ---------- copy addons ----------

    private fun showCopyAddons(profile: Profile, from: Profile) {
        val list = column(Gravity.START).apply { setPadding(dp(PAD), dp(48), dp(PAD), dp(48)) }
        list.addView(text("Copy ${from.name}'s addons to ${profile.name}?", DISPLAY, bold = true))
        list.addView(text("Personal ones (recommendations, Trakt, lists) are unticked: they're tied to ${from.name}'s history.",
            BODY, color = MUTED).marginTop(12))
        setWindowed(ScrollView(this).apply { addView(list) })

        background({ Stremio.addons(from.authKey, refresh = true) to Stremio.addons(profile.authKey, refresh = true) }) { result ->
            val (theirs, mine) = result.getOrElse {
                list.addView(text("Couldn't load addons: ${it.message}", BODY).marginTop(24))
                list.addView(Button(this).apply { text = "Skip"; setOnClickListener { openHome(profile) } }.also { it.requestFocus() })
                return@background
            }
            // Stremio's built-ins (Cinemeta and co.) are on every account already, so they aren't offered.
            val boxes = theirs.filterNot { it.official }.map { addon ->
                CheckBox(this).apply {
                    text = addon.name
                    textSize = BODY
                    setTextColor(Color.WHITE)
                    isChecked = !isPersonal(addon)
                    tag = addon
                }
            }
            boxes.forEach { list.addView(it, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(8) }) }
            val copy = Button(this).apply { text = "Copy addons" }
            val skip = Button(this).apply { text = "Skip" }
            list.addView(LinearLayout(this).apply { addView(copy); addView(skip) }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(32) })
            copy.requestFocus()
            skip.setOnClickListener { showWatchly(profile, theirs) }
            copy.setOnClickListener {
                copy.isEnabled = false
                val picked = boxes.filter { it.isChecked }.map { it.tag as Addon }.map { it.id }.toSet()
                // In the source profile's order (it decides which addon answers first), with the account's
                // own built-ins in their places, then anything else the account already had.
                val merged = theirs.mapNotNull { a ->
                    if (a.official) mine.firstOrNull { it.id == a.id } ?: a else a.takeIf { it.id in picked }
                }
                val addons = merged + mine.filter { m -> merged.none { it.id == m.id } }
                background({ Stremio.setAddons(profile.authKey, addons) }) { saved ->
                    saved.onSuccess { showWatchly(profile, theirs) }
                        .onFailure { copy.isEnabled = true; copy.text = "Couldn't copy, try again" }
                }
            }
        }
    }

    // Addons that read one person's history or lists; copying them would give the new profile someone else's.
    private fun isPersonal(addon: Addon) =
        listOf("watchly", "trakt", "simkl", "syncribullet", "aiolists").any { addon.name.contains(it, ignoreCase = true) }

    // ---------- recommendations ----------

    /** Watchly is per person: the new profile sets up its own, connected to its own Stremio account. */
    private fun showWatchly(profile: Profile, sourceAddons: List<Addon>) {
        val watchly = sourceAddons.firstOrNull { it.name.contains("watchly", ignoreCase = true) }
            ?: return openHome(profile)
        val configure = Uri.parse(watchly.transportUrl).let { "${it.scheme}://${it.authority}/configure" }
        val qr = ImageView(this).apply { setImageBitmap(qrBitmap(configure, dp(260))) }
        val done = Button(this).apply { text = "Done"; setOnClickListener { openHome(profile) } }
        val steps = column(Gravity.START,
            text("Recommendations for ${profile.name}", DISPLAY, bold = true),
            text("1.  Scan with ${profile.name}'s phone to open Watchly\n" +
                "2.  Connect ${profile.name}'s Stremio account there\n" +
                "3.  Install it to ${profile.name}'s Stremio account\n\n" +
                "${profile.name}'s own \"Top Picks\" then show up on Home.", BODY, color = MUTED).marginTop(16),
            done.marginTop(32),
        )
        setWindowed(LinearLayout(this).apply {
            gravity = Gravity.CENTER
            addView(qr, LinearLayout.LayoutParams(dp(260), dp(260)).apply { marginEnd = dp(56) })
            addView(steps)
        })
        done.requestFocus()
    }

    /** Black on white with the standard 4-module quiet zone, whole pixels per module so it stays sharp. */
    private fun qrBitmap(text: String, maxPx: Int): Bitmap {
        val m = Qr.encode(text)
        val n = m.size + 8
        val px = maxOf(1, maxPx / n)
        val bmp = Bitmap.createBitmap(n * px, n * px, Bitmap.Config.RGB_565)
        bmp.eraseColor(Color.WHITE)
        val canvas = Canvas(bmp)
        val paint = Paint().apply { color = Color.BLACK }
        for (r in m.indices) for (c in m.indices) if (m[r][c]) {
            canvas.drawRect(((c + 4) * px).toFloat(), ((r + 4) * px).toFloat(), ((c + 5) * px).toFloat(), ((r + 5) * px).toFloat(), paint)
        }
        return bmp
    }
}
