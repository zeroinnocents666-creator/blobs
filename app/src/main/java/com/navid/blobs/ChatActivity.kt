package com.navid.blobs

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class ChatActivity : Activity() {
    private lateinit var bot: Bot
    private lateinit var msgs: MutableList<Msg>
    private lateinit var scroll: ScrollView
    private lateinit var list: LinearLayout
    private lateinit var input: EditText
    private lateinit var sendBtn: FrameLayout
    private lateinit var sendIcon: ImageView
    private lateinit var headBlob: BlobView
    private lateinit var headName: TextView
    private lateinit var headRole: TextView
    private var empty: View? = null
    private var accent = 0
    private val ui = Handler(Looper.getMainLooper())

    // current generation
    private var call: Gemini.Call? = null
    private var streamText = StringBuilder()
    private var streamView: TextView? = null
    private var streamHolder: LinearLayout? = null
    private var lastRender = 0L
    private var renderQueued = false
    private var errorView: View? = null
    private var streamModel = ""
    private var searchOn = false
    private lateinit var globeBtn: FrameLayout
    private lateinit var globeIcon: ImageView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val b = intent.getStringExtra("id")?.let { Store.bot(this, it) }
        if (b == null) { finish(); return }
        bot = b
        msgs = Store.chat(this, bot.id)
        accent = Prefs.accent(this)

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(C.BG) }
        applyInsets(root)

        // header
        val head = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(8), dp(6), dp(8))
        }
        head.addView(iconButton(R.drawable.ic_back) { closeWithAnim() })
        headBlob = BlobView(this)
        head.addView(headBlob, lp(dp(40), dp(40)).margins(l = dp(4)))
        val names = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, 0, 0) }
        headName = text("", 17f, C.TEXT, true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        headRole = text("", 13f, C.SUB).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        names.addView(headName); names.addView(headRole)
        head.addView(names, lp(0, WRAP, 1f))
        head.addView(iconButton(R.drawable.ic_edit, C.SUB) {
            open(Intent(this, EditBotActivity::class.java).putExtra("id", bot.id))
        })
        root.addView(head)
        root.addView(View(this).apply { setBackgroundColor(C.SURF) }, lp(MATCH, dp(1)))

        scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false; isFillViewport = true }
        list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        scroll.addView(list, FrameLayout.LayoutParams(MATCH, WRAP))
        root.addView(scroll, lp(MATCH, 0, 1f))

        // input bar
        val bar = LinearLayout(this).apply {
            gravity = Gravity.BOTTOM
            setPadding(dp(10), dp(8), dp(10), dp(10))
        }
        bar.addView(iconButton(R.drawable.ic_add, C.TEXT, C.SURF2, 46) { plusMenu(it) })
        searchOn = Prefs.search(this, bot.id)
        globeBtn = FrameLayout(this).apply {
            isClickable = true; isFocusable = true
            setOnClickListener {
                it.squish()
                searchOn = !searchOn
                Prefs.setSearch(this@ChatActivity, bot.id, searchOn)
                updateGlobe()
                Toast.makeText(this@ChatActivity,
                    if (searchOn) "Google Search on for this chat" else "Google Search off", Toast.LENGTH_SHORT).show()
            }
        }
        globeIcon = ImageView(this).apply { setImageResource(R.drawable.ic_globe) }
        globeBtn.addView(globeIcon, FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER))
        bar.addView(globeBtn, lp(dp(46), dp(46)).margins(l = dp(6)))
        input = EditText(this).apply {
            setTextColor(C.TEXT); setHintTextColor(C.SUB)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            background = roundBg(C.SURF2, dpf(23f))
            setPadding(dp(18), dp(12), dp(18), dp(12))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            maxLines = 6; minHeight = dp(46)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) { updateSend() }
            })
        }
        bar.addView(input, lp(0, WRAP, 1f).margins(dp(8), 0, dp(8), 0))
        sendBtn = FrameLayout(this).apply {
            isClickable = true
            setOnClickListener { it.squish(); if (call != null) stop() else send() }
        }
        sendIcon = ImageView(this)
        sendBtn.addView(sendIcon, FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER))
        bar.addView(sendBtn, lp(dp(46), dp(46)))
        root.addView(bar, lp(MATCH, WRAP))
        setContentView(root)

        bindBot()
        updateGlobe()
        Gemini.warm(Prefs.apiKey(this))
        msgs.forEach { addMsgView(it, false) }
        refreshEmpty()
        updateSend()
        scrollToEnd(false)
    }

    override fun onResume() {
        super.onResume()
        if (!::bot.isInitialized) return
        Store.bot(this, bot.id)?.let { bot = it; bindBot() }
    }

    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun onBackPressed() { closeWithAnim() }

    override fun onDestroy() {
        call?.cancel(); call = null
        super.onDestroy()
    }

    private fun bindBot() {
        headBlob.shape = bot.shape; headBlob.color = bot.color
        headName.text = bot.name
        headRole.text = bot.role.ifEmpty { "Assistant" }
        input.hint = "Ask ${bot.name}"
        for (i in 0 until list.childCount) {
            val v = list.getChildAt(i)
            (v.findViewWithTag<View>("avatar") as? BlobView)?.let { it.shape = bot.shape; it.color = bot.color }
        }
        (empty?.findViewWithTag<View>("avatar") as? BlobView)?.let { it.shape = bot.shape; it.color = bot.color }
    }

    private fun updateGlobe() {
        globeBtn.background = ripple(roundBg(if (searchOn) accent else C.SURF2, dpf(23f)), dpf(23f))
        globeIcon.imageTintList = android.content.res.ColorStateList.valueOf(if (searchOn) C.on(accent) else C.SUB)
        globeBtn.contentDescription = if (searchOn) "Google Search on" else "Google Search off"
    }

    private fun updateSend() {
        val busy = call != null
        val active = busy || input.text.isNotBlank()
        sendBtn.background = ripple(roundBg(if (active) accent else C.SURF2, dpf(23f)), dpf(23f))
        sendIcon.setImageResource(if (busy) R.drawable.ic_stop else R.drawable.ic_send)
        sendIcon.imageTintList = android.content.res.ColorStateList.valueOf(if (active) C.on(accent) else C.SUB)
        sendBtn.contentDescription = if (busy) "Stop" else "Send"
    }

    private fun plusMenu(anchor: View) {
        val pm = PopupMenu(this, anchor)
        pm.menu.add(0, 1, 0, "Edit bot")
        pm.menu.add(0, 2, 1, "Clear chat")
        pm.menu.add(0, 3, 2, "Copy whole chat")
        pm.setOnMenuItemClickListener {
            when (it.itemId) {
                1 -> open(Intent(this, EditBotActivity::class.java).putExtra("id", bot.id))
                2 -> dialog().setTitle("Clear this chat?").setMessage("All messages with ${bot.name} will be deleted.")
                    .setPositiveButton("Clear") { _, _ ->
                        stop(); msgs.clear(); Store.clearChat(this, bot.id)
                        list.removeAllViews(); empty = null; errorView = null; refreshEmpty()
                    }.setNegativeButton("Cancel", null).show()
                3 -> copy(msgs.joinToString("\n\n") { m -> (if (m.role == "user") "You: " else "${bot.name}: ") + m.text })
            }
            true
        }
        pm.show()
    }

    // ---------- views ----------

    private fun refreshEmpty() {
        if (msgs.isEmpty() && empty == null && call == null) {
            val e = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(24), dp(60), dp(24), dp(24))
                addView(BlobView(this@ChatActivity).apply { shape = bot.shape; color = bot.color; tag = "avatar" }, lp(dp(110), dp(110)))
                addView(text("Say hi to ${bot.name}", 20f, C.TEXT, true).apply { gravity = Gravity.CENTER; setPadding(0, dp(16), 0, dp(4)) })
                addView(text(bot.role.ifEmpty { "Ask anything" }, 14.5f, C.SUB).apply { gravity = Gravity.CENTER })
            }
            empty = e
            list.addView(e, lp(MATCH, WRAP))
        } else if ((msgs.isNotEmpty() || call != null) && empty != null) {
            list.removeView(empty); empty = null
        }
    }

    private fun addMsgView(m: Msg, animate: Boolean): View {
        val v = if (m.role == "user") userBubble(m.text) else botBubble(m.text)
        if (m.role != "user") v.findViewWithTag<LinearLayout>("card")?.let { addFooter(it, m) }
        list.addView(v)
        if (animate) appear(v)
        return v
    }

    private fun appear(v: View) {
        v.alpha = 0f; v.translationY = dpf(14f)
        v.animate().alpha(1f).translationY(0f).setDuration(240).setInterpolator(DecelerateInterpolator(2f)).start()
    }

    private fun userBubble(s: String): View = LinearLayout(this).apply {
        gravity = Gravity.END
        layoutParams = lp(MATCH, WRAP).margins(dp(48), dp(6), 0, dp(6))
        val tv = text(s, 15.5f, C.on(accent)).apply {
            setPadding(dp(16), dp(11), dp(16), dp(11))
            setLineSpacing(0f, 1.15f)
            background = roundBg(accent, dpf(20f)).apply {
                val r = dpf(20f); cornerRadii = floatArrayOf(r, r, r, r, dpf(6f), dpf(6f), r, r)
            }
            setOnLongClickListener { copy(s); true }
        }
        addView(tv, LinearLayout.LayoutParams(WRAP, WRAP))
    }

    /** Bot row: small avatar + dark card. Content is a TextView (markdown) or the typing dots. */
    private fun botBubble(s: String?): LinearLayout = LinearLayout(this).apply {
        gravity = Gravity.TOP
        layoutParams = lp(MATCH, WRAP).margins(0, dp(6), dp(28), dp(6))
        addView(BlobView(this@ChatActivity).apply { shape = bot.shape; color = bot.color; tag = "avatar" },
            lp(dp(30), dp(30)).margins(t = dp(4), r = dp(8)))
        val card = LinearLayout(this@ChatActivity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = roundBg(C.SURF, dpf(20f)).apply {
                val r = dpf(20f); cornerRadii = floatArrayOf(dpf(6f), dpf(6f), r, r, r, r, r, r)
            }
            tag = "card"
        }
        if (s == null) card.addView(DotsView(this@ChatActivity))
        else card.addView(mdText(s))
        addView(card, LinearLayout.LayoutParams(WRAP, WRAP))
    }

    private fun mdText(s: String): TextView = text(Md.render(s, accent, resources.displayMetrics.density), 15.5f, C.TEXT).apply {
        setLineSpacing(0f, 1.2f)
        tag = "md"
        movementMethod = Md.Touch
        // links are tapped, not focused: keeps the ScrollView from jumping while text streams in
        isFocusable = false; isFocusableInTouchMode = false
        highlightColor = 0x33FFFFFF
        setOnLongClickListener { copy(streamTextFor(this) ?: s); true }
    }

    /** Source chips (Google Search) and a tiny model tag under a bot reply. */
    private fun addFooter(card: LinearLayout, m: Msg) {
        if (m.sources.isNotEmpty()) {
            val hs = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER }
            val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
            m.sources.take(8).forEachIndexed { i, (uri, title) ->
                row.addView(text("${i + 1}  ${title.removePrefix("www.")}", 12.5f, C.TEXT).apply {
                    maxLines = 1; ellipsize = TextUtils.TruncateAt.END; maxWidth = dp(190)
                    setPadding(dp(10), dp(6), dp(10), dp(6))
                    background = ripple(roundBg(C.SURF2, dpf(12f), C.LINE, dp(1)), dpf(12f))
                    isClickable = true
                    contentDescription = "Source: $title"
                    setOnClickListener { Md.open(this@ChatActivity, uri) }
                    setOnLongClickListener { copy(uri); true }
                }, lp(WRAP, WRAP).margins(r = dp(6)))
            }
            hs.addView(row)
            card.addView(hs, lp(WRAP, WRAP).margins(t = dp(10)))
        }
        val parts = ArrayList<String>()
        if (m.model.isNotEmpty()) parts.add(Models.label(m.model))
        if (m.sources.isNotEmpty()) parts.add("Google Search")
        if (m.note.isNotEmpty()) parts.add(m.note)
        if (parts.isNotEmpty()) card.addView(text(parts.joinToString("  \u00B7  "), 11.5f, C.SUB).apply {
            setPadding(0, dp(8), 0, 0); tag = "meta"
        })
    }

    private fun streamTextFor(tv: TextView): String? = if (tv === streamView) streamText.toString() else null

    private fun errorCard(msg: String): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = lp(MATCH, WRAP).margins(dp(38), dp(6), dp(28), dp(6))
        background = roundBg(0xFF2A1618.toInt(), dpf(18f), 0xFF5A2A2E.toInt(), dp(1))
        setPadding(dp(14), dp(12), dp(14), dp(10))
        val row = LinearLayout(this@ChatActivity).apply { gravity = Gravity.TOP }
        row.addView(icon(R.drawable.ms_error, C.ERR, 20))
        row.addView(text(msg, 14.5f, 0xFFFFD0D0.toInt()).apply { setPadding(dp(10), 0, 0, 0); setLineSpacing(0f, 1.15f) }, lp(0, WRAP, 1f))
        addView(row)
        val actions = LinearLayout(this@ChatActivity).apply { gravity = Gravity.END; setPadding(0, dp(8), 0, 0) }
        if (msg.contains("Settings")) actions.addView(text("Settings", 14f, C.TEXT, true).apply {
            setPadding(dp(12), dp(8), dp(12), dp(8)); background = ripple(null, dpf(14f))
            setOnClickListener { open(Intent(this@ChatActivity, SettingsActivity::class.java)) }
        })
        actions.addView(LinearLayout(this@ChatActivity).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8)); background = ripple(null, dpf(14f))
            addView(icon(R.drawable.ic_refresh, accent, 18))
            addView(text("Retry", 14f, accent, true).apply { setPadding(dp(6), 0, 0, 0) })
            setOnClickListener { retry() }
        })
        addView(actions, lp(MATCH, WRAP))
    }

    private fun copy(s: String) {
        (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Blobs", s))
        if (android.os.Build.VERSION.SDK_INT < 33) Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
    }

    private fun nearBottom(): Boolean {
        val child = scroll.getChildAt(0) ?: return true
        return child.bottom - (scroll.height + scroll.scrollY) < dp(160)
    }

    private fun scrollToEnd(smooth: Boolean) {
        scroll.post {
            val y = (scroll.getChildAt(0)?.bottom ?: 0) - scroll.height
            if (smooth) scroll.smoothScrollTo(0, maxOf(0, y)) else scroll.scrollTo(0, maxOf(0, y))
        }
    }

    // ---------- generation ----------

    private fun clearError() { errorView?.let { list.removeView(it) }; errorView = null }

    private fun send() {
        val s = input.text.toString().trim()
        if (s.isEmpty() || call != null) return
        clearError()
        input.setText("")
        val m = Msg("user", s, System.currentTimeMillis())
        msgs.add(m); Store.saveChat(this, bot.id, msgs)
        refreshEmpty()
        addMsgView(m, true)
        generate()
    }

    private fun retry() {
        if (call != null) return
        clearError()
        if (msgs.isEmpty() || msgs.last().role != "user") return
        generate()
    }

    private fun generate() {
        val key = Prefs.apiKey(this)
        val holder = botBubble(null)
        list.addView(holder); appear(holder)
        streamHolder = holder; streamView = null; streamText = StringBuilder()
        scrollToEnd(true)
        if (key.isEmpty()) {
            finishStream(error = "No API key yet. Add your Gemini API key in Settings.")
            return
        }
        streamModel = ""
        val plan = Router.plan(this, searchOn)
        call = Gemini.stream(this, key, plan, bot.prompt(), msgs.toList(), Prefs.fast(this), object : Gemini.Listener {
            override fun onStart(model: String) { streamModel = model }
            override fun onChunk(text: String) { this@ChatActivity.onChunk(text) }
            override fun onDone(model: String, sources: List<Pair<String, String>>, note: String) {
                finishStream(null, model, sources, note)
            }
            override fun onError(message: String) { finishStream(message, streamModel) }
            override fun onMigrated() {
                Toast.makeText(this@ChatActivity, "Old model failed. Switched to Auto.", Toast.LENGTH_SHORT).show()
            }
        })
        updateSend()
    }

    private fun onChunk(chunk: String) {
        val holder = streamHolder ?: return
        streamText.append(chunk)
        if (streamView == null) {
            val card = holder.findViewWithTag<LinearLayout>("card")
            card.removeAllViews()
            val tv = mdText(streamText.toString())
            card.addView(tv); streamView = tv
        }
        scheduleRender()
    }

    private fun scheduleRender() {
        val now = SystemClock.uptimeMillis()
        if (now - lastRender >= 60) renderStream()
        else if (!renderQueued) {
            renderQueued = true
            ui.postDelayed({ renderQueued = false; renderStream() }, 60 - (now - lastRender))
        }
    }

    private fun renderStream() {
        val tv = streamView ?: return
        lastRender = SystemClock.uptimeMillis()
        val stick = nearBottom()
        tv.text = Md.render(streamText.toString(), accent, resources.displayMetrics.density)
        if (stick) scrollToEnd(false)
    }

    /** Ends the current generation: keeps any text produced, shows [error] if given. */
    private fun finishStream(
        error: String?, model: String = streamModel,
        sources: List<Pair<String, String>> = emptyList(), note: String = ""
    ) {
        call = null
        val holder = streamHolder
        val txt = streamText.toString()
        if (txt.isNotBlank()) {
            renderStream()
            val m = Msg("model", txt, System.currentTimeMillis())
            m.model = model; m.sources = sources; m.note = note
            msgs.add(m)
            Store.saveChat(this, bot.id, msgs)
            holder?.findViewWithTag<LinearLayout>("card")?.let { addFooter(it, m) }
            if (nearBottom()) scrollToEnd(true)
            // detach so long-press copies the final text
            streamView?.setOnLongClickListener { copy(txt); true }
        } else if (holder != null) {
            list.removeView(holder)
        }
        streamHolder = null; streamView = null
        if (error != null) {
            val e = errorCard(error); errorView = e
            list.addView(e); appear(e)
            scrollToEnd(true)
        }
        updateSend()
    }

    private fun stop() {
        val c = call ?: return
        c.cancel()
        finishStream(null)
    }

    @Suppress("unused") private val keep = ViewGroup.LayoutParams.WRAP_CONTENT
}
