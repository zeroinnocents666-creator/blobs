package com.navid.blobs

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class HomeActivity : Activity() {
    private lateinit var list: LinearLayout
    private lateinit var search: EditText
    private lateinit var fab: LinearLayout
    private lateinit var banner: LinearLayout
    private var query = ""
    private var firstShow = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = FrameLayout(this).apply { setBackgroundColor(C.BG) }
        applyInsets(root)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(col, FrameLayout.LayoutParams(MATCH, MATCH))

        // header
        val head = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(14), dp(8), dp(6))
        }
        val titles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titles.addView(text("Blobs", 30f, C.TEXT, true))
        titles.addView(text("Your team of AI bots", 14f, C.SUB))
        head.addView(titles, lp(0, WRAP, 1f))
        head.addView(iconButton(R.drawable.ic_search) { toggleSearch() })
        head.addView(iconButton(R.drawable.ic_settings) { open(Intent(this, SettingsActivity::class.java)) })
        col.addView(head)

        search = field("Search bots").apply {
            visibility = View.GONE
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) { query = s.toString().trim(); render(false) }
            })
        }
        col.addView(search, lp(MATCH, WRAP).margins(dp(16), dp(6), dp(16), dp(4)))

        banner = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = ripple(roundBg(C.SURF2, dpf(20f)), dpf(20f))
            isClickable = true
            setOnClickListener { open(Intent(this@HomeActivity, SettingsActivity::class.java)) }
            addView(icon(R.drawable.ic_key, C.SWATCHES[3], 22))
            addView(text("Add your Gemini API key in Settings to start chatting", 14f, C.TEXT).apply {
                setPadding(dp(12), 0, 0, 0)
            }, lp(0, WRAP, 1f))
        }
        col.addView(banner, lp(MATCH, WRAP).margins(dp(16), dp(8), dp(16), dp(4)))

        val scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false; clipToPadding = false }
        list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(6), dp(12), dp(110))
        }
        scroll.addView(list, FrameLayout.LayoutParams(MATCH, WRAP))
        col.addView(scroll, lp(MATCH, 0, 1f))

        fab = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(15), dp(22), dp(15))
            elevation = dpf(8f)
            isClickable = true
            setOnClickListener { it.squish(); open(Intent(this@HomeActivity, EditBotActivity::class.java)) }
        }
        root.addView(fab, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.BOTTOM or Gravity.END).apply {
            rightMargin = dp(20); bottomMargin = dp(22)
        })
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        val accent = Prefs.accent(this)
        val on = C.on(accent)
        fab.background = ripple(roundBg(accent, dpf(28f)), dpf(28f))
        fab.removeAllViews()
        fab.addView(icon(R.drawable.ic_add, on, 22))
        fab.addView(text("Create bot", 15.5f, on, true).apply { setPadding(dp(8), 0, 0, 0) })
        banner.visibility = if (Prefs.apiKey(this).isEmpty()) View.VISIBLE else View.GONE
        render(firstShow)
        firstShow = false
    }

    private fun toggleSearch() {
        if (search.visibility == View.VISIBLE) {
            search.setText(""); search.visibility = View.GONE; hideKeyboard(search)
        } else {
            search.visibility = View.VISIBLE; search.requestFocus()
            (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                .showSoftInput(search, 0)
        }
    }

    private class Row(val bot: Bot, val last: Msg?)

    private fun render(animate: Boolean) {
        list.removeAllViews()
        val rows = Store.bots(this).map { Row(it, Store.chat(this, it.id).lastOrNull()) }
            .filter {
                query.isEmpty() || it.bot.name.contains(query, true) || it.bot.role.contains(query, true)
            }
            .sortedByDescending { it.last?.time ?: it.bot.created }
        if (rows.isEmpty()) {
            list.addView(text(if (query.isEmpty()) "No bots yet. Create one." else "No bots match \"$query\".", 15f, C.SUB).apply {
                gravity = Gravity.CENTER; setPadding(0, dp(60), 0, 0)
            }, lp(MATCH, WRAP))
            return
        }
        rows.forEachIndexed { i, r ->
            val v = rowView(r)
            list.addView(v, lp(MATCH, WRAP).margins(b = dp(6)))
            if (animate) {
                v.alpha = 0f; v.translationY = dpf(18f)
                v.animate().alpha(1f).translationY(0f).setStartDelay(50L * i).setDuration(360)
                    .setInterpolator(DecelerateInterpolator(2f)).start()
            }
        }
    }

    private fun rowView(r: Row): View = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(12), dp(12), dp(14), dp(12))
        background = ripple(roundBg(C.SURF, dpf(22f)), dpf(22f))
        isClickable = true; isLongClickable = true
        addView(BlobView(this@HomeActivity).apply { shape = r.bot.shape; color = r.bot.color }, lp(dp(52), dp(52)))
        val mid = LinearLayout(this@HomeActivity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), 0, dp(8), 0)
        }
        val top = LinearLayout(this@HomeActivity).apply { gravity = Gravity.CENTER_VERTICAL }
        top.addView(text(r.bot.name, 16.5f, C.TEXT, true).apply {
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        }, lp(0, WRAP, 1f))
        if (r.last != null) top.addView(text(time(r.last.time), 12.5f, C.SUB))
        mid.addView(top, lp(MATCH, WRAP))
        mid.addView(text(r.bot.role.ifEmpty { "Assistant" }, 13f, Prefs.accent(this@HomeActivity)).apply {
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END; setPadding(0, dp(1), 0, dp(2))
        })
        val preview = r.last?.let { (if (it.role == "user") "You: " else "") + it.text.replace(Regex("[`*#_>]"), "").replace('\n', ' ').trim() }
            ?: "Say hi to ${r.bot.name}"
        mid.addView(text(preview, 14f, C.SUB).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
        addView(mid, lp(0, WRAP, 1f))
        setOnClickListener {
            open(Intent(this@HomeActivity, ChatActivity::class.java).putExtra("id", r.bot.id))
        }
        setOnLongClickListener { botMenu(r.bot); true }
    }

    private fun botMenu(b: Bot) {
        dialog().setTitle(b.name)
            .setItems(arrayOf("Edit bot", "Clear chat", "Delete bot")) { _, which ->
                when (which) {
                    0 -> open(Intent(this, EditBotActivity::class.java).putExtra("id", b.id))
                    1 -> { Store.clearChat(this, b.id); render(false) }
                    2 -> dialog().setTitle("Delete ${b.name}?")
                        .setMessage("The bot and its chat will be removed from this phone.")
                        .setPositiveButton("Delete") { _, _ -> Store.delete(this, b.id); render(false) }
                        .setNegativeButton("Cancel", null).show()
                }
            }.show()
    }

    companion object {
        fun time(t: Long): String {
            val now = Calendar.getInstance(); val c = Calendar.getInstance().apply { timeInMillis = t }
            val sameDay = now.get(Calendar.YEAR) == c.get(Calendar.YEAR) && now.get(Calendar.DAY_OF_YEAR) == c.get(Calendar.DAY_OF_YEAR)
            val fmt = if (sameDay) "h:mm a" else if (now.get(Calendar.YEAR) == c.get(Calendar.YEAR)) "d MMM" else "d MMM yyyy"
            return SimpleDateFormat(fmt, Locale.getDefault()).format(Date(t))
        }
    }
}
