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
import android.widget.TextView

// "Who's watching?", plus adding a profile: name, Stremio sign-in, copying addons, and setting up Watchly.
// The app starts here; with a single profile it goes straight on to Home.
class ProfilesActivity : Screen() {
    // The sign-in code being waited on; anything else stops its polling loop.
    private var waitingCode: String? = null
    private var signingIn = false
    private var draftName = ""

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

    @Deprecated("Android back callback")
    override fun onBackPressed() {
        if (signingIn) showName() else super.onBackPressed()
    }

    private fun openHome(profile: Profile) {
        Profiles.setActive(this, profile.id)
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    // ---------- who's watching ----------

    private fun showPicker() {
        val profiles = Profiles.all(this)
        val tiles = LinearLayout(this).apply { gravity = Gravity.CENTER; setPadding(dp(14), dp(14), dp(14), dp(14)) }
        profiles.forEach { p ->
            tiles.addView(tile(p.name, p.color, p.name.take(1).uppercase()).apply {
                setOnClickListener { openHome(p) }
                setOnLongClickListener { confirmRemove(p); true }
            })
        }
        if (profiles.size < Profiles.MAX) {
            tiles.addView(tile("Add profile", GLASS_LOW, "+").apply { setOnClickListener { showName() } })
        }
        val content = android.widget.FrameLayout(this).apply {
            addView(text("Who’s watching?", 36f, bold = true), android.widget.FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply {
                leftMargin = dp(48); topMargin = dp(70)
            })
            addView(tiles, android.widget.FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
            addView(text("Hold OK to remove a profile", 13f, color = TEXT_HI), android.widget.FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START).apply {
                leftMargin = dp(48); bottomMargin = dp(72)
            })
        }
        setWindowed(content, onboardingBackdrop())
        val active = profiles.indexOfFirst { it.id == Profiles.active(this)?.id }
        tiles.getChildAt(maxOf(active, 0)).requestFocus()
    }

    private fun tile(label: String, color: Int, initial: String): View {
        val circle = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }
        val avatar = text(initial, 38f, bold = true).apply {
            gravity = Gravity.CENTER
            background = circle
            layoutParams = LinearLayout.LayoutParams(dp(72), dp(72))
        }
        val marker = text("▶", 14f, bold = true).apply {
            visibility = View.INVISIBLE
            gravity = Gravity.CENTER
        }
        val name = text(label, 13f).apply {
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(4), dp(12), dp(4))
        }
        val nameRow = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            addView(marker, LinearLayout.LayoutParams(dp(18), -2).apply { marginEnd = dp(4) })
            addView(name)
        }.marginTop(2)
        return column(Gravity.CENTER_HORIZONTAL, avatar, nameRow).apply {
            isFocusable = true
            setPadding(dp(8), dp(8), dp(8), dp(8))
            layoutParams = LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(18); marginEnd = dp(18) }
            setOnFocusChangeListener { v, has ->
                marker.visibility = if (has) View.VISIBLE else View.INVISIBLE
                name.background = if (has) rounded(0x5538424D, 28) else null
                v.animate().scaleX(if (has) 1.06f else 1f).scaleY(if (has) 1.06f else 1f).setDuration(120).start()
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
        signingIn = false
        waitingCode = null
        val first = Profiles.all(this).isEmpty()
        val name = EditText(this).apply {
            hint = "Profile name"
            textSize = 22f
            setTextColor(Color.WHITE)
            setHintTextColor(0xFFB7C0CA.toInt())
            setPadding(dp(24), dp(18), dp(24), dp(18))
            background = glass(14)
            setOnFocusChangeListener { v, has -> v.background = glass(14, has) }
            setSingleLine()
            imeOptions = EditorInfo.IME_ACTION_DONE
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(22) }
        }
        name.setText(draftName)
        val next = onboardingButton("Continue    →")
        val go = { name.text.toString().trim().ifEmpty { null }?.let { draftName = it; showSignIn(it) } }
        name.setOnEditorActionListener { _, _, _ -> go(); true }
        next.setOnClickListener { go() }
        val copy = column(Gravity.START,
            text(if (first) "Welcome" else "New profile", 52f, bold = true),
            text("First, name your profile.", 27f).marginTop(8),
            text("Your watch history and recommendations will stay yours.", BODY, color = TEXT_MID).marginTop(10),
            text("PROFILE NAME", SMALL, bold = true, color = TEXT_MID).marginTop(40),
            name,
            next.marginTop(26),
        ).apply { gravity = Gravity.CENTER_VERTICAL }
        val avatar = onboardingAvatar(draftName.firstOrNull()?.uppercase() ?: "E", 96)
        val preview = column(Gravity.CENTER,
            avatar,
            text("${name.text.ifEmpty { "Your name" }}", TITLE, bold = true).marginTop(18),
        ).apply { gravity = Gravity.CENTER }
        name.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                (preview.getChildAt(1) as android.widget.TextView).text = s?.toString()?.ifBlank { "Your name" } ?: "Your name"
                avatar.text = s?.firstOrNull()?.uppercase() ?: "E"
            }
            override fun afterTextChanged(s: android.text.Editable?) = Unit
        })
        val content = onboardingFrame("1 of 2", copy, preview, dp(70), 1.15f, 0.85f)
        setWindowed(content, onboardingBackdrop())
        name.requestFocus()
    }

    private fun showSignIn(name: String) {
        signingIn = true
        val qr = ImageView(this)
        val code = text("", 64f, bold = true)
        val status = text("Getting a sign-in code…", BODY, color = MUTED)
        val avatar = onboardingAvatar(name.take(1).uppercase(), 40)
        val back = onboardingButton("←   Back")
        back.setOnClickListener { waitingCode = null; showName() }
        val left = column(Gravity.CENTER,
            qr.apply { scaleType = ImageView.ScaleType.FIT_CENTER },
            LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(avatar)
                addView(text(name, TITLE).marginTop(0).apply { setPadding(dp(14), 0, 0, 0) })
            }.marginTop(16),
            back.marginTop(28),
        ).apply { gravity = Gravity.CENTER }
        val steps = column(Gravity.START,
            text("Connect Stremio", 52f, bold = true),
            text("Scan with $name’s phone to sign in.", 27f).marginTop(8),
            text("Or visit link.stremio.com and enter this code:", BODY + 2, color = TEXT_MID).marginTop(18),
            code.marginTop(4),
            LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(View(this@ProfilesActivity).apply { background = rounded(0xFF0878FF.toInt(), 50) }, LinearLayout.LayoutParams(dp(12), dp(12)))
                addView(status.apply { setPadding(dp(16), 0, 0, 0) })
            }.marginTop(4),
            text("New to Stremio? Create an account at stremio.com", BODY + 1, color = TEXT_MID).marginTop(24),
        ).apply { gravity = Gravity.CENTER_VERTICAL }
        val content = onboardingFrame("2 of 2", left, steps, dp(36), 0f, 1f, dp(340))
        setWindowed(content, onboardingBackdrop())

        waitingCode = null
        background({ Stremio.createLink() }) { result ->
            if (!signingIn) return@background
            result.onSuccess { link ->
                qr.setImageBitmap(qrBitmap(link.url, dp(240)))
                code.text = link.code
                status.text = "Waiting for you to approve it…"
                waitingCode = link.code
                poll(name, link.code, SystemClock.elapsedRealtime())
            }.onFailure {
                status.text = "Can't reach Stremio. Trying again…"
                main.postDelayed({ if (signingIn) showSignIn(name) }, 5_000)
            }
        }
    }

    private fun onboardingBackdrop() = ImageView(this).apply {
        setImageResource(R.drawable.onboarding_scene)
        scaleType = ImageView.ScaleType.CENTER_CROP
    }

    private fun onboardingFrame(step: String, left: View, right: View, gap: Int, leftWeight: Float, rightWeight: Float, leftWidth: Int = 0) =
        android.widget.FrameLayout(this).apply {
            val body = LinearLayout(this@ProfilesActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(50), dp(86), dp(50), dp(44))
                addView(left, if (leftWidth > 0)
                    LinearLayout.LayoutParams(leftWidth, -1).apply { marginEnd = gap }
                else LinearLayout.LayoutParams(0, -1, leftWeight).apply { marginEnd = gap })
                addView(right, LinearLayout.LayoutParams(0, -1, rightWeight))
            }
            addView(body, android.widget.FrameLayout.LayoutParams(-1, -1))
            addView(LinearLayout(this@ProfilesActivity).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(40), dp(36), dp(40), 0)
                addView(text("STREAM TV", SMALL, bold = true, color = TEXT_MID), LinearLayout.LayoutParams(0, -2, 1f))
                addView(text(step, SMALL, color = TEXT_MID))
            }, android.widget.FrameLayout.LayoutParams(-1, dp(72), Gravity.TOP))
        }

    private fun onboardingButton(label: String) = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 20f
        setTextColor(Color.WHITE)
        isFocusable = true
        setPadding(dp(26), dp(14), dp(26), dp(14))
        background = glass(18)
        setOnFocusChangeListener { v, focused -> v.background = glass(18, focused) }
    }

    private fun onboardingAvatar(initial: String, size: Int) = TextView(this).apply {
        text = initial
        textSize = if (size > 100) 54f else 24f
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(0xFF0878FF.toInt())
        }
        layoutParams = LinearLayout.LayoutParams(dp(size), dp(size))
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
