package com.navid.blobs

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class SettingsActivity : Activity() {
    private lateinit var body: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(C.BG) }
        applyInsets(root)
        root.addView(topBar("Settings", { closeWithAnim() }))
        val scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false }
        body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), 0, dp(16), dp(32))
        }
        scroll.addView(body, FrameLayout.LayoutParams(MATCH, WRAP))
        root.addView(scroll, lp(MATCH, 0, 1f))
        setContentView(root)
        build()
    }

    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun onBackPressed() { closeWithAnim() }

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = roundBg(C.SURF, dpf(24f))
        setPadding(dp(6), dp(6), dp(6), dp(6))
    }

    private fun row(iconRes: Int, title: String, sub: String, tint: Int = Prefs.accent(this), onClick: (() -> Unit)?): LinearLayout =
        LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            if (onClick != null) {
                background = ripple(null, dpf(18f)); isClickable = true
                setOnClickListener { onClick() }
            }
            addView(FrameLayout(this@SettingsActivity).apply {
                background = roundBg(C.SURF2, dpf(20f))
                addView(ImageView(this@SettingsActivity).apply {
                    setImageResource(iconRes); imageTintList = android.content.res.ColorStateList.valueOf(tint)
                }, FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER))
            }, lp(dp(40), dp(40)))
            val col = LinearLayout(this@SettingsActivity).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), 0, 0, 0) }
            col.addView(text(title, 16f, C.TEXT, true))
            if (sub.isNotEmpty()) col.addView(text(sub, 13.5f, C.SUB).apply { setPadding(0, dp(2), 0, 0); setLineSpacing(0f, 1.1f) })
            addView(col, lp(0, WRAP, 1f))
        }

    private fun build() {
        body.removeAllViews()
        val accent = Prefs.accent(this)
        val key = Prefs.apiKey(this)

        body.addView(label("GEMINI"))
        val g = card()
        g.addView(row(R.drawable.ic_key, "API key", if (key.isEmpty()) "Not set. Tap to add." else Prefs.masked(key)) { keyDialog() })
        val auto = Prefs.auto(this)
        g.addView(row(R.drawable.ic_model, "Model",
            if (auto) "Auto (recommended). Best free model first, switches when a limit is hit"
            else "Manual: " + Prefs.model(this)) { modelDialog() })
        val fast = Prefs.fast(this)
        g.addView(row(R.drawable.ic_bolt, "Speed",
            if (fast) "Fast. Light thinking, quickest replies" else "Smart. Full thinking, slower replies") { speedDialog() })
        g.addView(switchRow(R.drawable.ic_globe, "Google Search by default",
            "New chats start with search on. Change it per chat with the globe button. Uses Gemini 2.5 Flash (free: 500 searches a day).",
            Prefs.searchDefault(this)) { Prefs.setSearchDefault(this, it) })
        val lim = Limits.active(this)
        val tf = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT)
        g.addView(row(R.drawable.ic_refresh, "Model limits",
            if (lim.isEmpty()) "All models available"
            else lim.joinToString("\n") { (k, until, why) ->
                val search = k.endsWith("|search")
                Models.label(k.removeSuffix("|search")) + (if (search) " (search)" else "") + ": $why, until " + tf.format(java.util.Date(until))
            } + "\nTap to forget and try them again.") {
            if (lim.isNotEmpty()) { Limits.clear(this); build() }
        })
        g.addView(row(R.drawable.ic_open, "Get a free API key", "aistudio.google.com/apikey") {
            try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://aistudio.google.com/apikey"))) } catch (_: Exception) {}
        })
        body.addView(g, lp(MATCH, WRAP))

        body.addView(label("LOOK"))
        val l = card()
        l.addView(row(R.drawable.ic_palette, "Accent colour", "Buttons and your chat bubbles", accent, null))
        val grid = GridLayout(this).apply { columnCount = 7; setPadding(dp(8), 0, dp(8), dp(10)) }
        C.SWATCHES.forEachIndexed { i, c ->
            val cell = FrameLayout(this).apply {
                isClickable = true
                setOnClickListener { Prefs.setAccent(this@SettingsActivity, c); build() }
                addView(View(this@SettingsActivity).apply { background = roundBg(c, dpf(18f)) },
                    FrameLayout.LayoutParams(dp(34), dp(34), Gravity.CENTER))
                if (c == accent) addView(ImageView(this@SettingsActivity).apply {
                    setImageResource(R.drawable.ms_check)
                    imageTintList = android.content.res.ColorStateList.valueOf(C.on(c))
                }, FrameLayout.LayoutParams(dp(18), dp(18), Gravity.CENTER))
            }
            grid.addView(cell, GridLayout.LayoutParams(GridLayout.spec(i / 7, 1f), GridLayout.spec(i % 7, 1f)).apply {
                width = 0; height = dp(46)
            })
        }
        l.addView(grid, lp(MATCH, WRAP))
        body.addView(l, lp(MATCH, WRAP))

        body.addView(label("DATA"))
        val d = card()
        d.addView(row(R.drawable.ms_delete, "Clear all chats", "Deletes every conversation. Bots are kept.", C.ERR) {
            dialog().setTitle("Clear all chats?").setMessage("This cannot be undone.")
                .setPositiveButton("Clear") { _, _ -> Store.clearAll(this); build() }
                .setNegativeButton("Cancel", null).show()
        })
        body.addView(d, lp(MATCH, WRAP))

        body.addView(label("ABOUT"))
        val a = card()
        val ver = try { packageManager.getPackageInfo(packageName, 0).versionName } catch (_: Exception) { "1.0" }
        a.addView(row(R.drawable.ms_info, "Blobs $ver",
            "Your team of AI bots, powered by the Google Gemini API. Your key is encrypted with the Android Keystore and chats are stored only on this phone. Messages you send are processed by Google to generate replies.",
            accent, null))
        body.addView(a, lp(MATCH, WRAP))
    }

    private fun switchRow(iconRes: Int, title: String, sub: String, on: Boolean, onChange: (Boolean) -> Unit): LinearLayout {
        val sw = android.widget.Switch(this).apply {
            isChecked = on
            val acc = Prefs.accent(this@SettingsActivity)
            thumbTintList = android.content.res.ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(acc, C.SUB))
            trackTintList = android.content.res.ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf((acc and 0x00FFFFFF) or 0x80000000.toInt(), C.LINE))
            setOnCheckedChangeListener { _, v -> onChange(v) }
        }
        val r = row(iconRes, title, sub) { sw.toggle() }
        r.addView(sw, lp(WRAP, WRAP).margins(l = dp(8)))
        return r
    }

    private fun speedDialog() {
        val opts = arrayOf("Fast (recommended): light thinking, quickest replies", "Smart: full thinking, slower but deeper")
        dialog().setTitle("Speed")
            .setSingleChoiceItems(opts, if (Prefs.fast(this)) 0 else 1) { d, w -> Prefs.setFast(this, w == 0); d.dismiss(); build() }
            .setNegativeButton("Cancel", null).show()
    }

    private fun keyDialog() {
        val wrap = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0) }
        val f = field("Paste API key (AIza...)").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            typeface = android.graphics.Typeface.MONOSPACE
            setText(Prefs.apiKey(this@SettingsActivity))
        }
        val status = text("", 13.5f, C.SUB).apply { setPadding(dp(4), dp(10), 0, 0) }
        wrap.addView(f, lp(MATCH, WRAP)); wrap.addView(status)
        val dlg = dialog().setTitle("Gemini API key").setView(wrap)
            .setPositiveButton("Save", null)
            .setNeutralButton("Test", null)
            .setNegativeButton("Cancel", null).create()
        dlg.setOnShowListener {
            dlg.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                Prefs.setApiKey(this, f.text.toString()); hideKeyboard(f); dlg.dismiss(); build()
            }
            dlg.getButton(android.app.AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                status.text = "Testing..."; status.setTextColor(C.SUB)
                Gemini.test(f.text.toString(), if (Prefs.auto(this)) null else Prefs.model(this)) { err ->
                    status.text = err ?: "Key works."
                    status.setTextColor(if (err == null) C.OK else C.ERR)
                }
            }
        }
        dlg.show()
    }

    private fun modelDialog() {
        val auto = Prefs.auto(this)
        val wrap = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0) }
        var dlg: android.app.AlertDialog? = null
        val autoRow = text("Auto (recommended)", 15f, if (auto) C.on(Prefs.accent(this)) else C.TEXT, true).apply {
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = ripple(roundBg(if (auto) Prefs.accent(this@SettingsActivity) else C.SURF2, dpf(14f)), dpf(14f))
            setOnClickListener { Prefs.setAuto(this@SettingsActivity, true); dlg?.dismiss(); build() }
        }
        wrap.addView(autoRow, lp(MATCH, WRAP))
        wrap.addView(text("Tries Hydrogen first, then 3.8 Flash, 3.6 Flash, 3.5 Flash-Lite, 3.1 Flash-Lite and 2.5 Flash when a model hits its free limit. Models that are out of quota are skipped until the daily reset.",
            12.5f, C.SUB).apply { setPadding(dp(4), dp(6), 0, 0); setLineSpacing(0f, 1.1f) })
        wrap.addView(text("Or pick one model", 13f, C.SUB).apply { setPadding(dp(4), dp(16), 0, dp(6)) })
        val f = field("Custom model id").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            typeface = android.graphics.Typeface.MONOSPACE
            setText(if (auto) "" else Prefs.model(this@SettingsActivity))
        }
        for (m in Models.ALL) {
            val selected = !auto && Prefs.model(this) == m.id
            val c = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), dp(9), dp(14), dp(9))
                background = ripple(roundBg(C.SURF2, dpf(14f), if (selected) Prefs.accent(this@SettingsActivity) else C.SURF2, dp(if (selected) 2 else 0)), dpf(14f))
                isClickable = true
                addView(text(m.id, 14f, C.TEXT).apply { typeface = android.graphics.Typeface.MONOSPACE })
                addView(text(m.note, 12f, C.SUB))
                setOnClickListener { f.setText(m.id); f.setSelection(m.id.length) }
            }
            wrap.addView(c, lp(MATCH, WRAP).margins(b = dp(6)))
        }
        wrap.addView(f, lp(MATCH, WRAP).margins(t = dp(4)))
        val sc = ScrollView(this).apply { addView(wrap) }
        dlg = dialog().setTitle("Model").setView(sc)
            .setPositiveButton("Use this model") { _, _ ->
                val m = f.text.toString().trim()
                if (m.isEmpty()) Prefs.setAuto(this, true) else { Prefs.setModel(this, m); Prefs.setAuto(this, false) }
                build()
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun chip(s: String, onClick: () -> Unit): TextView = text(s, 14.5f, C.TEXT).apply {
        typeface = android.graphics.Typeface.MONOSPACE
        setPadding(dp(14), dp(10), dp(14), dp(10))
        background = ripple(roundBg(C.SURF2, dpf(14f)), dpf(14f))
        setOnClickListener { onClick() }
    }
}
