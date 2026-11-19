package com.navid.blobs

import android.app.Activity
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.UUID

class EditBotActivity : Activity() {
    private var editing: Bot? = null
    private var shape = Shapes.BLOB
    private var color = C.SWATCHES[0]
    private lateinit var preview: BlobView
    private lateinit var previewName: TextView
    private lateinit var previewRole: TextView
    private val shapeCells = mutableListOf<FrameLayout>()
    private val colorCells = mutableListOf<FrameLayout>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        editing = intent.getStringExtra("id")?.let { Store.bot(this, it) }
        editing?.let { shape = it.shape; color = it.color }
        if (editing == null) {
            shape = (0 until Shapes.COUNT).random(); color = C.SWATCHES.random()
        }
        val accent = Prefs.accent(this)

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(C.BG) }
        applyInsets(root)
        root.addView(topBar(if (editing == null) "Create bot" else "Edit bot", { closeWithAnim() }))

        val scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(4), dp(18), dp(24))
        }
        scroll.addView(body, FrameLayout.LayoutParams(MATCH, WRAP))
        root.addView(scroll, lp(MATCH, 0, 1f))

        // live preview
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = roundBg(C.SURF, dpf(28f))
            setPadding(dp(16), dp(26), dp(16), dp(22))
        }
        preview = BlobView(this).apply { shape = this@EditBotActivity.shape; color = this@EditBotActivity.color }
        card.addView(preview, lp(dp(150), dp(150)))
        previewName = text("New bot", 22f, C.TEXT, true).apply { gravity = Gravity.CENTER; setPadding(0, dp(14), 0, 0) }
        previewRole = text("Role", 14f, C.SUB).apply { gravity = Gravity.CENTER }
        card.addView(previewName, lp(MATCH, WRAP)); card.addView(previewRole, lp(MATCH, WRAP))
        body.addView(card, lp(MATCH, WRAP))

        body.addView(label("NAME"))
        val name = field("e.g. Shopper").apply { setText(editing?.name ?: "") }
        body.addView(name, lp(MATCH, WRAP))
        body.addView(label("ROLE"))
        val role = field("e.g. Personal shopper").apply { setText(editing?.role ?: "") }
        body.addView(role, lp(MATCH, WRAP))
        body.addView(label("SYSTEM INSTRUCTIONS (OPTIONAL)"))
        val sys = field("Leave empty to build them from the name and role", multi = true).apply { setText(editing?.system ?: "") }
        body.addView(sys, lp(MATCH, WRAP))
        val sysHint = text("", 12.5f, C.SUB).apply { setPadding(dp(4), dp(8), dp(4), 0); setLineSpacing(0f, 1.15f) }
        body.addView(sysHint)

        fun refresh() {
            val n = name.text.toString().trim(); val r = role.text.toString().trim()
            previewName.text = n.ifEmpty { "New bot" }
            previewRole.text = r.ifEmpty { "Role" }
            sysHint.text = if (sys.text.isBlank()) "Default: " + Store.defaultPrompt(n.ifEmpty { "Bot" }, r) else ""
            sysHint.visibility = if (sys.text.isBlank()) View.VISIBLE else View.GONE
        }
        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { refresh() }
        }
        name.addTextChangedListener(watcher); role.addTextChangedListener(watcher); sys.addTextChangedListener(watcher)
        refresh()

        // shape picker
        body.addView(label("SHAPE"))
        val shapes = GridLayout(this).apply { columnCount = 4 }
        for (s in 0 until Shapes.COUNT) {
            val cell = FrameLayout(this).apply {
                isClickable = true
                setOnClickListener { pickShape(s) }
                contentDescription = Shapes.NAMES[s]
                addView(BlobView(this@EditBotActivity).apply {
                    shape = s; this.color = C.SUB; blinks = false
                }, FrameLayout.LayoutParams(dp(44), dp(44), Gravity.CENTER))
            }
            shapes.addView(cell, GridLayout.LayoutParams(GridLayout.spec(s / 4, 1f), GridLayout.spec(s % 4, 1f)).apply {
                width = 0; height = dp(72); setMargins(dp(4), dp(4), dp(4), dp(4))
            })
            shapeCells.add(cell)
        }
        body.addView(shapes, lp(MATCH, WRAP))

        // colour picker
        body.addView(label("COLOUR"))
        val colors = GridLayout(this).apply { columnCount = 7 }
        C.SWATCHES.forEachIndexed { i, c ->
            val cell = FrameLayout(this).apply {
                isClickable = true
                setOnClickListener { pickColor(c) }
                addView(View(this@EditBotActivity).apply { background = roundBg(c, dpf(20f)) },
                    FrameLayout.LayoutParams(dp(36), dp(36), Gravity.CENTER))
                addView(ImageView(this@EditBotActivity).apply {
                    setImageResource(R.drawable.ms_check)
                    imageTintList = android.content.res.ColorStateList.valueOf(C.on(c))
                    visibility = View.INVISIBLE; tag = "check"
                }, FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER))
            }
            colors.addView(cell, GridLayout.LayoutParams(GridLayout.spec(i / 7, 1f), GridLayout.spec(i % 7, 1f)).apply {
                width = 0; height = dp(48)
            })
            colorCells.add(cell)
        }
        body.addView(colors, lp(MATCH, WRAP))
        updatePickers(false)

        // save button
        val bar = LinearLayout(this).apply { setPadding(dp(18), dp(10), dp(18), dp(14)) }
        bar.addView(pillButton(if (editing == null) "Create" else "Save", accent) {
            val n = name.text.toString().trim()
            if (n.isEmpty()) { name.error = "Give your bot a name"; name.requestFocus(); return@pillButton }
            val b = editing?.apply {
                this.name = n; this.role = role.text.toString().trim(); this.system = sys.text.toString().trim()
                this.shape = this@EditBotActivity.shape; this.color = this@EditBotActivity.color
            } ?: Bot(UUID.randomUUID().toString(), n, role.text.toString().trim(), sys.text.toString().trim(),
                shape, color, System.currentTimeMillis())
            Store.upsert(this, b)
            hideKeyboard(name)
            closeWithAnim()
        }, lp(MATCH, WRAP))
        root.addView(bar, lp(MATCH, WRAP))
        setContentView(root)
    }

    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun onBackPressed() { closeWithAnim() }

    private fun pickShape(s: Int) { shape = s; preview.shape = s; updatePickers(true) }
    private fun pickColor(c: Int) { color = c; preview.color = c; updatePickers(true) }

    private fun updatePickers(bounce: Boolean) {
        shapeCells.forEachIndexed { i, cell ->
            val sel = i == shape
            cell.background = roundBg(if (sel) C.SURF2 else C.SURF, dpf(18f), color, if (sel) dp(2) else 0)
            (cell.getChildAt(0) as BlobView).color = if (sel) color else C.SUB
        }
        colorCells.forEachIndexed { i, cell ->
            val sel = C.SWATCHES[i] == color
            cell.findViewWithTag<View>("check").visibility = if (sel) View.VISIBLE else View.INVISIBLE
            cell.getChildAt(0).animate().scaleX(if (sel) 1.15f else 1f).scaleY(if (sel) 1.15f else 1f).setDuration(180).start()
        }
        if (bounce) {
            preview.scaleX = 0.86f; preview.scaleY = 0.86f
            preview.animate().scaleX(1f).scaleY(1f).setDuration(420).setInterpolator(OvershootInterpolator(3f)).start()
            preview.doBlink()
        }
    }
}
