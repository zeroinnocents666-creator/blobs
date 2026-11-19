package com.navid.blobs

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Toast

/**
 * Share target (v1.2): photos, screenshots, PDFs or text shared from another app. Asks which bot gets them, copies
 * the files into that bot's private folder, and opens the chat with them staged above the input bar.
 */
class ShareActivity : Activity() {
    private val uris = ArrayList<Uri>()
    private var sharedText: String? = null
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        readIntent(intent)
        if (uris.isEmpty() && sharedText.isNullOrBlank()) {
            Toast.makeText(this, "Nothing to share", Toast.LENGTH_SHORT).show(); finish(); return
        }
        build()
    }

    @Suppress("DEPRECATION")
    private fun readIntent(i: Intent) {
        when (i.action) {
            Intent.ACTION_SEND -> {
                val u: Uri? = if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                              else i.getParcelableExtra(Intent.EXTRA_STREAM)
                if (u != null) uris.add(u)
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                val l: ArrayList<Uri>? = if (Build.VERSION.SDK_INT >= 33) i.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
                                         else i.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
                l?.let { uris.addAll(it) }
            }
        }
        if (uris.isEmpty()) i.clipData?.let { c -> for (k in 0 until c.itemCount) c.getItemAt(k).uri?.let { uris.add(it) } }
        sharedText = (i.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString())?.takeIf { it.isNotBlank() }
        // text/plain shares from browsers also carry a subject; keep just the text
    }

    private fun build() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(C.BG) }
        applyInsets(root)
        root.addView(topBar("Send to a bot", { finish() }))
        val what = when {
            uris.isEmpty() -> "Text"
            uris.size == 1 -> "1 item"
            else -> "${uris.size} items" + if (uris.size > Attach.MAX_PER_MESSAGE) " (first ${Attach.MAX_PER_MESSAGE} will be added)" else ""
        }
        root.addView(text(what, 14f, C.SUB).apply { setPadding(dp(20), 0, dp(20), dp(10)) })
        val scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false }
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(4), dp(12), dp(24)) }
        scroll.addView(list, FrameLayout.LayoutParams(MATCH, WRAP))
        root.addView(scroll, lp(MATCH, 0, 1f))
        val bots = Store.bots(this)
        if (bots.isEmpty()) {
            list.addView(text("No bots yet. Open Blobs and create one first.", 15f, C.SUB).apply {
                gravity = Gravity.CENTER; setPadding(0, dp(40), 0, dp(20))
            }, lp(MATCH, WRAP))
            list.addView(pillButton("Open Blobs", Prefs.accent(this)) {
                startActivity(Intent(this, WelcomeActivity::class.java)); finish()
            }, lp(MATCH, WRAP))
        }
        val accent = Prefs.accent(this)
        bots.forEach { b ->
            list.addView(LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(12), dp(14), dp(12))
                background = ripple(roundBg(C.SURF, dpf(22f)), dpf(22f))
                isClickable = true
                addView(BlobView(this@ShareActivity).apply { shape = b.shape; color = b.color }, lp(dp(46), dp(46)))
                val col = LinearLayout(this@ShareActivity).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), 0, dp(8), 0) }
                col.addView(text(b.name, 16.5f, C.TEXT, true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
                col.addView(text(b.role.ifEmpty { "Assistant" }, 13f, accent).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
                addView(col, lp(0, WRAP, 1f))
                setOnClickListener { pick(b, this) }
            }, lp(MATCH, WRAP).margins(b = dp(6)))
        }
        setContentView(root)
    }

    private fun pick(b: Bot, row: LinearLayout) {
        if (busy) return
        busy = true
        row.addView(ProgressBar(this).apply {
            indeterminateTintList = android.content.res.ColorStateList.valueOf(Prefs.accent(this@ShareActivity))
        }, lp(dp(24), dp(24)))
        val app = applicationContext
        val take = uris.take(Attach.MAX_PER_MESSAGE)
        Thread {
            val out = ArrayList<Att>(); val errs = ArrayList<String>()
            var total = 0L
            for (u in take) {
                try {
                    val a = Attach.import(app, b.id, u, intent.type?.takeIf { it.contains('/') && !it.endsWith("/*") })
                    if (total + a.size > Attach.MAX_MESSAGE_BYTES) {
                        Attach.file(app, b.id, a).delete()
                        errs.add("Some files were left out (max ${Attach.human(Attach.MAX_MESSAGE_BYTES)} per message)")
                    } else { total += a.size; out.add(a) }
                } catch (e: Attach.Err) { errs.add(e.message ?: "Couldn't add a file") }
                catch (e: OutOfMemoryError) { errs.add("An image was too large to open") }
                catch (e: Exception) { errs.add("Couldn't add a file (${e.javaClass.simpleName})") }
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                errs.distinct().firstOrNull()?.let { Toast.makeText(this, it, Toast.LENGTH_LONG).show() }
                if (out.isEmpty() && sharedText.isNullOrBlank()) { busy = false; finish(); return@runOnUiThread }
                val i = Intent(this, ChatActivity::class.java).putExtra("id", b.id).putExtra("staged", Att.listJson(out))
                // a shared link / text goes into the input box (only when it isn't just the file's caption)
                sharedText?.let { i.putExtra("text", it) }
                startActivity(i)
                finish()
            }
        }.start()
    }

    @Suppress("unused") private val keep = View.GONE
}
