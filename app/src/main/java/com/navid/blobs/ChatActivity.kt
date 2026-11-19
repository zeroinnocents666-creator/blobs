package com.navid.blobs

import android.app.Activity
import android.app.Dialog
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.ViewOutlineProvider
import android.view.Window
import android.widget.ProgressBar
import java.io.File
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

    // attachments staged for the next message (v1.2)
    private val staged = ArrayList<Att>()
    private var pending = 0
    private lateinit var stagedScroll: HorizontalScrollView
    private lateinit var stagedRow: LinearLayout
    private var camFile: File? = null

    companion object {
        private const val RC_PHOTO = 11
        private const val RC_CAMERA = 12
        private const val RC_FILE = 13
        private const val INPUT_ID = 0x0B10B501
        /** Types offered in the File picker. Code files often come as octet-stream; they are checked after picking. */
        private val FILE_TYPES = arrayOf(
            "application/pdf", "text/*", "application/json", "application/xml", "application/javascript",
            "application/x-sh", "application/x-yaml", "application/octet-stream", "image/*"
        )
    }

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
        bar.addView(iconButton(R.drawable.ic_add, C.TEXT, C.SURF2, 46) { hideKeyboard(input); plusMenu() }.apply {
            contentDescription = "Attach and more"
        })
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
            id = INPUT_ID
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

        // staged attachment chips, above the input bar
        stagedScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER; visibility = View.GONE
        }
        stagedRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), dp(8), dp(12), 0) }
        stagedScroll.addView(stagedRow)
        root.addView(stagedScroll, lp(MATCH, WRAP))
        root.addView(bar, lp(MATCH, WRAP))
        setContentView(root)
        setupPaste()

        if (savedInstanceState != null) {
            staged.addAll(Att.parseList(savedInstanceState.getString("staged")))
            savedInstanceState.getString("cam")?.let { camFile = File(it) }
        } else {
            // from the share sheet (ShareActivity): attachments already copied into this bot's folder
            staged.addAll(Att.parseList(intent.getStringExtra("staged")).take(Attach.MAX_PER_MESSAGE))
            intent.getStringExtra("text")?.takeIf { it.isNotBlank() }?.let { input.setText(it); input.setSelection(input.text.length) }
        }
        renderStaged()
        val snapshot = msgs.toList()
        Thread { try { Attach.sweep(applicationContext, bot.id, snapshot) } catch (_: Exception) {} }.start()

        bindBot()
        updateGlobe()
        Gemini.warm(Prefs.apiKey(this))
        msgs.forEach { addMsgView(it, false) }
        refreshEmpty()
        updateSend()
        scrollToEnd(false)
    }

    override fun onSaveInstanceState(out: Bundle) {
        super.onSaveInstanceState(out)
        if (!::bot.isInitialized) return
        out.putString("staged", Att.listJson(staged))
        camFile?.let { out.putString("cam", it.path) }
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
        val active = busy || ((input.text.isNotBlank() || staged.isNotEmpty()) && pending == 0)
        sendBtn.background = ripple(roundBg(if (active) accent else C.SURF2, dpf(23f)), dpf(23f))
        sendIcon.setImageResource(if (busy) R.drawable.ic_stop else R.drawable.ic_send)
        sendIcon.imageTintList = android.content.res.ColorStateList.valueOf(if (active) C.on(accent) else C.SUB)
        sendBtn.contentDescription = if (busy) "Stop" else "Send"
    }

    // ---------- + sheet: attach, paste, chat actions ----------

    private fun plusMenu() {
        val d = Dialog(this)
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(20))
            background = roundBg(C.SURF, dpf(24f)).apply {
                val r = dpf(24f); cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
            }
        }
        col.addView(View(this).apply { background = roundBg(C.LINE, dpf(3f)) },
            lp(dp(36), dp(4)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(14) })
        val tiles = LinearLayout(this)
        fun tile(res: Int, label: String, act: () -> Unit) = tiles.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, dp(10))
            background = ripple(roundBg(C.SURF2, dpf(18f)), dpf(18f))
            isClickable = true; contentDescription = label
            addView(icon(res, accent, 26))
            addView(text(label, 13f, C.TEXT).apply { setPadding(0, dp(6), 0, 0); maxLines = 1 })
            setOnClickListener { d.dismiss(); act() }
        }, lp(0, WRAP, 1f).margins(dp(4), 0, dp(4), 0))
        tile(R.drawable.ic_image, "Photo") { pickPhotos() }
        tile(R.drawable.ic_camera, "Camera") { takePhoto() }
        tile(R.drawable.ic_file, "File") { pickFiles() }
        tile(R.drawable.ic_paste, "Paste image") { pasteImage() }
        col.addView(tiles, lp(MATCH, WRAP))
        col.addView(text("Photos, PDFs and text or code files. Up to ${Attach.MAX_PER_MESSAGE} per message.", 12f, C.SUB).apply {
            setPadding(dp(6), dp(10), dp(6), dp(6))
        })
        col.addView(View(this).apply { setBackgroundColor(C.LINE) }, lp(MATCH, dp(1)).margins(t = dp(4), b = dp(6)))
        fun row(res: Int, label: String, act: () -> Unit) = col.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(13), dp(12), dp(13))
            background = ripple(null, dpf(14f)); isClickable = true
            addView(icon(res, C.SUB, 22))
            addView(text(label, 15.5f, C.TEXT).apply { setPadding(dp(16), 0, 0, 0) })
            setOnClickListener { d.dismiss(); act() }
        }, lp(MATCH, WRAP))
        row(R.drawable.ic_edit, "Edit bot") { open(Intent(this, EditBotActivity::class.java).putExtra("id", bot.id)) }
        row(R.drawable.ms_delete, "Clear chat") {
            dialog().setTitle("Clear this chat?").setMessage("All messages and attachments with ${bot.name} will be deleted.")
                .setPositiveButton("Clear") { _, _ ->
                    stop(); msgs.clear(); Store.clearChat(this, bot.id)
                    staged.clear(); renderStaged()
                    list.removeAllViews(); empty = null; errorView = null; refreshEmpty()
                }.setNegativeButton("Cancel", null).show()
        }
        row(R.drawable.ic_copy, "Copy whole chat") {
            copy(msgs.joinToString("\n\n") { m ->
                val files = m.atts.joinToString("") { a -> "\n[" + (if (a.isImage()) "image" else "file") + ": " + a.name + "]" }
                (if (m.role == "user") "You: " else "${bot.name}: ") + m.text + files
            })
        }
        d.setContentView(col)
        d.window?.apply {
            setBackgroundDrawable(ColorDrawable(android.graphics.Color.TRANSPARENT))
            setGravity(Gravity.BOTTOM)
            setLayout(MATCH, WRAP)
            setWindowAnimations(android.R.style.Animation_InputMethod)
        }
        d.show()
    }

    private fun slotsLeft(): Int = Attach.MAX_PER_MESSAGE - staged.size - pending

    private fun noSlots(): Boolean {
        if (slotsLeft() > 0) return false
        Toast.makeText(this, "Up to ${Attach.MAX_PER_MESSAGE} attachments per message", Toast.LENGTH_SHORT).show()
        return true
    }

    @Suppress("DEPRECATION")
    private fun startForResult(i: Intent, rc: Int, fail: String) {
        try { startActivityForResult(i, rc) } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, fail, Toast.LENGTH_SHORT).show()
        } catch (e: SecurityException) {
            Toast.makeText(this, fail, Toast.LENGTH_SHORT).show()
        }
    }

    /** System Photo Picker (no storage permission). Android 13+, or 11-12 with the picker module; else GET_CONTENT. */
    private fun pickPhotos() {
        if (noSlots()) return
        val left = slotsLeft()
        val picker = Build.VERSION.SDK_INT >= 33 ||
            (Build.VERSION.SDK_INT >= 30 && android.os.ext.SdkExtensions.getExtensionVersion(Build.VERSION_CODES.R) >= 2)
        val i = if (picker) Intent(MediaStore.ACTION_PICK_IMAGES).apply {
            if (left > 1) putExtra(MediaStore.EXTRA_PICK_IMAGES_MAX, left)
        } else Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "image/*"; addCategory(Intent.CATEGORY_OPENABLE)
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, left > 1)
        }
        startForResult(i, RC_PHOTO, "No photo picker found")
    }

    /** Camera app via ACTION_IMAGE_CAPTURE into our provider (no CAMERA permission needed for the intent). */
    private fun takePhoto() {
        if (noSlots()) return
        val f = File(FilesProvider.camDir(this), "cam_${System.currentTimeMillis()}.jpg")
        camFile = f
        val uri = FilesProvider.camUri(f.name)
        val i = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
            putExtra(MediaStore.EXTRA_OUTPUT, uri)
            clipData = ClipData.newRawUri("photo", uri)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startForResult(i, RC_CAMERA, "No camera app found")
    }

    private fun pickFiles() {
        if (noSlots()) return
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, FILE_TYPES)
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, slotsLeft() > 1)
        }
        startForResult(i, RC_FILE, "No file picker found")
    }

    /** "Paste image" item: reads image uris from the clipboard (works on every Android version). */
    private fun pasteImage() {
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        val clip = cm.primaryClip
        val uris = ArrayList<Pair<Uri, String?>>()
        if (clip != null) for (i in 0 until clip.itemCount) {
            val u = clip.getItemAt(i).uri ?: continue
            val t = try { contentResolver.getType(u) } catch (_: Exception) { null }
                ?: (0 until clip.description.mimeTypeCount).map { clip.description.getMimeType(it) }.firstOrNull { it.startsWith("image/") }
            if (t != null && t.startsWith("image/")) uris.add(u to t)
        }
        if (uris.isEmpty()) {
            Toast.makeText(this, "No image in the clipboard. Copy an image first.", Toast.LENGTH_SHORT).show(); return
        }
        addUris(uris)
    }

    /** Images pasted into the text field or sent by the keyboard (Android 12+). Text paste keeps working. */
    private fun setupPaste() {
        if (Build.VERSION.SDK_INT < 31) return
        input.setOnReceiveContentListener(arrayOf("image/*"), object : android.view.OnReceiveContentListener {
          override fun onReceiveContent(view: View, payload: android.view.ContentInfo): android.view.ContentInfo? {
            val clip = payload.clip
            val list = ArrayList<Pair<Uri, String?>>()
            var rest: ClipData? = null
            val t: String = (0 until clip.description.mimeTypeCount).map { k -> clip.description.getMimeType(k) ?: "" }
                .firstOrNull { m -> m.startsWith("image/") } ?: "image/*"
            for (i in 0 until clip.itemCount) {
                val item = clip.getItemAt(i)
                val u = item.uri
                val ty = if (u == null) null else try { contentResolver.getType(u) } catch (_: Exception) { null }
                if (u == null || (ty != null && !ty.startsWith("image/"))) {
                    if (rest == null) rest = ClipData(clip.description, item) else rest.addItem(item)
                    continue
                }
                // keyboard / clipboard grants can end when this callback returns: copy the bytes now
                val tmp = cacheCopy(u) ?: continue
                list.add(tmp to t)
            }
            if (list.isNotEmpty()) addUris(list)
            val r = rest ?: return null
            return android.view.ContentInfo.Builder(payload).setClip(r).build()
          }
        })
    }

    private fun cacheCopy(u: Uri): Uri? = try {
        val dir = File(cacheDir, "paste").apply { mkdirs() }
        val name = (try {
            contentResolver.query(u, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else null
            }
        } catch (_: Exception) { null }) ?: "pasted_${System.currentTimeMillis()}.png"
        val f = File(dir, System.currentTimeMillis().toString() + "_" + name.replace('/', '_'))
        contentResolver.openInputStream(u)?.use { i -> f.outputStream().use { i.copyTo(it) } }
        if (f.length() > 0) Uri.fromFile(f) else null
    } catch (e: Exception) { null }

    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == RC_CAMERA) {
            val f = camFile; camFile = null
            if (resultCode == RESULT_OK && f != null && f.exists() && f.length() > 0)
                addUris(listOf(FilesProvider.camUri(f.name) to "image/jpeg")) { f.delete() }
            else f?.delete()
            return
        }
        if (resultCode != RESULT_OK || data == null) return
        if (requestCode != RC_PHOTO && requestCode != RC_FILE) return
        val uris = ArrayList<Pair<Uri, String?>>()
        val clip = data.clipData
        if (clip != null) for (i in 0 until clip.itemCount) clip.getItemAt(i).uri?.let { uris.add(it to null) }
        else data.data?.let { uris.add(it to null) }
        addUris(uris)
    }

    /** Copies picked / pasted / shared uris into the bot's folder in the background and shows them as chips. */
    private fun addUris(uris: List<Pair<Uri, String?>>, after: (() -> Unit)? = null) {
        val left = slotsLeft()
        if (left <= 0) { noSlots(); after?.invoke(); return }
        if (uris.size > left) Toast.makeText(this, "Only the first $left added (max ${Attach.MAX_PER_MESSAGE} per message)", Toast.LENGTH_SHORT).show()
        val take = uris.take(left)
        pending += take.size
        renderStaged()
        val app = applicationContext
        val botId = bot.id
        Thread {
            for ((u, hint) in take) {
                var att: Att? = null; var err: String? = null
                try { att = Attach.import(app, botId, u, hint) }
                catch (e: Attach.Err) { err = e.message }
                catch (e: OutOfMemoryError) { err = "That image is too large to open." }
                catch (e: Exception) { err = "Couldn't add that file (${e.javaClass.simpleName})." }
                if (u.scheme == "file") u.path?.let { File(it).delete() }
                ui.post {
                    if (isFinishing || isDestroyed) return@post
                    pending--
                    if (att != null) {
                        val total = staged.sumOf { it.size } + att.size
                        if (total > Attach.MAX_MESSAGE_BYTES) {
                            Attach.file(app, botId, att).delete()
                            Toast.makeText(this, "Too big together (max ${Attach.human(Attach.MAX_MESSAGE_BYTES)} per message)", Toast.LENGTH_LONG).show()
                        } else staged.add(att)
                    }
                    if (err != null) Toast.makeText(this, err, Toast.LENGTH_LONG).show()
                    renderStaged()
                }
            }
            after?.let { ui.post(it) }
        }.start()
    }

    private fun renderStaged() {
        stagedRow.removeAllViews()
        for (a in staged.toList()) stagedRow.addView(stagedChip(a), lp(WRAP, dp(64)).margins(r = dp(8)))
        repeat(pending) {
            stagedRow.addView(FrameLayout(this).apply {
                background = roundBg(C.SURF2, dpf(14f))
                addView(ProgressBar(this@ChatActivity).apply {
                    indeterminateTintList = android.content.res.ColorStateList.valueOf(accent)
                }, FrameLayout.LayoutParams(dp(26), dp(26), Gravity.CENTER))
            }, lp(dp(64), dp(64)).margins(r = dp(8)))
        }
        stagedScroll.visibility = if (staged.isEmpty() && pending == 0) View.GONE else View.VISIBLE
        if (::sendBtn.isInitialized) updateSend()
    }

    private fun removeStaged(a: Att) {
        staged.remove(a)
        Attach.file(this, bot.id, a).delete()
        renderStaged()
    }

    private fun closeBadge(onClick: () -> Unit): View = FrameLayout(this).apply {
        background = ripple(roundBg(0xCC000000.toInt(), dpf(11f), C.LINE, dp(1)), dpf(11f))
        isClickable = true; contentDescription = "Remove"
        addView(icon(R.drawable.ms_close, android.graphics.Color.WHITE, 14), FrameLayout.LayoutParams(dp(14), dp(14), Gravity.CENTER))
        setOnClickListener { onClick() }
    }

    private fun fileIcon(a: Att) = when {
        a.kind == "pdf" -> R.drawable.ic_pdf
        a.kind == "text" && !a.mime.startsWith("text/plain") -> R.drawable.ic_code
        else -> R.drawable.ic_file
    }

    private fun stagedChip(a: Att): View {
        val f = Attach.file(this, bot.id, a)
        return FrameLayout(this).apply {
            if (a.isImage()) {
                val iv = roundImage(dpf(14f)).apply { setOnClickListener { Attach.view(this@ChatActivity, f) } }
                addView(iv, FrameLayout.LayoutParams(dp(64), dp(64)))
                Attach.load(iv, f, dp(128))
            } else {
                addView(fileChip(a, C.SURF2) {}, FrameLayout.LayoutParams(WRAP, dp(64)))
            }
            addView(closeBadge { removeStaged(a) }, FrameLayout.LayoutParams(dp(22), dp(22), Gravity.TOP or Gravity.END).apply {
                topMargin = dp(3); rightMargin = dp(3)
            })
        }
    }

    private fun roundImage(r: Float): ImageView = ImageView(this).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        background = roundBg(C.SURF2, r)
        outlineProvider = ViewOutlineProvider.BACKGROUND
        clipToOutline = true
        isClickable = true
    }

    private fun fileChip(a: Att, bg: Int, onClick: () -> Unit): LinearLayout = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(12), dp(8), dp(30), dp(8))
        background = ripple(roundBg(bg, dpf(14f), C.LINE, dp(1)), dpf(14f))
        isClickable = true
        contentDescription = "File ${a.name}"
        addView(icon(fileIcon(a), accent, 26))
        val col = LinearLayout(this@ChatActivity).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(10), 0, 0, 0) }
        col.addView(text(a.name, 13.5f, C.TEXT, true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.MIDDLE; maxWidth = dp(150) })
        val kind = when (a.kind) { "pdf" -> "PDF"; "text" -> a.name.substringAfterLast('.', "text").substringBefore(' ').uppercase().take(6); else -> "File" }
        col.addView(text("$kind  \u00B7  ${Attach.human(a.size)}", 12f, C.SUB))
        addView(col)
        setOnClickListener { onClick() }
    }

    /** Opens a sent PDF / text file in another app (read-only grant through FilesProvider). */
    private fun openFile(a: Att) {
        val uri = FilesProvider.attUri(bot.id, a.file)
        val i = Intent(Intent.ACTION_VIEW).setDataAndType(uri, if (a.kind == "text") "text/plain" else a.mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try { startActivity(Intent.createChooser(i, a.name)) } catch (e: Exception) {
            Toast.makeText(this, "No app to open this file", Toast.LENGTH_SHORT).show()
        }
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
        val v = if (m.role == "user") userBubble(m) else botBubble(m.text)
        if (m.role != "user") v.findViewWithTag<LinearLayout>("card")?.let { addFooter(it, m) }
        list.addView(v)
        if (animate) appear(v)
        return v
    }

    private fun appear(v: View) {
        v.alpha = 0f; v.translationY = dpf(14f)
        v.animate().alpha(1f).translationY(0f).setDuration(240).setInterpolator(DecelerateInterpolator(2f)).start()
    }

    private fun userBubble(m: Msg): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.END
        layoutParams = lp(MATCH, WRAP).margins(dp(48), dp(6), 0, dp(6))
        val imgs = m.atts.filter { it.isImage() }
        val files = m.atts.filter { !it.isImage() }
        if (imgs.size == 1) {
            val a = imgs[0]; val f = Attach.file(this@ChatActivity, bot.id, a)
            val maxW = dp(220); val maxH = dp(280)
            var w = maxW; var h = dp(160)
            if (a.w > 0 && a.h > 0) {
                h = (maxW.toFloat() * a.h / a.w).toInt()
                if (h > maxH) { h = maxH; w = (maxH.toFloat() * a.w / a.h).toInt().coerceAtLeast(dp(90)) }
                h = h.coerceAtLeast(dp(70))
            }
            val iv = roundImage(dpf(18f)).apply { contentDescription = a.name; setOnClickListener { Attach.view(this@ChatActivity, f) } }
            addView(iv, LinearLayout.LayoutParams(w, h).margins(b = dp(4)))
            Attach.load(iv, f, maxOf(w, h))
        } else if (imgs.size > 1) {
            imgs.chunked(3).forEach { rowImgs ->
                val row = LinearLayout(this@ChatActivity).apply { gravity = Gravity.END }
                rowImgs.forEach { a ->
                    val f = Attach.file(this@ChatActivity, bot.id, a)
                    val iv = roundImage(dpf(14f)).apply { contentDescription = a.name; setOnClickListener { Attach.view(this@ChatActivity, f) } }
                    row.addView(iv, LinearLayout.LayoutParams(dp(86), dp(86)).margins(l = dp(4)))
                    Attach.load(iv, f, dp(172))
                }
                addView(row, LinearLayout.LayoutParams(WRAP, WRAP).margins(b = dp(4)))
            }
        }
        files.forEach { a ->
            addView(fileChip(a, C.SURF) { openFile(a) }.apply { setPadding(dp(12), dp(8), dp(16), dp(8)) },
                LinearLayout.LayoutParams(WRAP, WRAP).margins(b = dp(4)))
        }
        val s = m.text
        if (s.isNotBlank()) {
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
        if ((s.isEmpty() && staged.isEmpty()) || call != null) return
        if (pending > 0) { Toast.makeText(this, "Still adding attachments...", Toast.LENGTH_SHORT).show(); return }
        clearError()
        input.setText("")
        val m = Msg("user", s, System.currentTimeMillis())
        m.atts = staged.toList()
        staged.clear(); renderStaged()
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
        call = Gemini.stream(this, key, plan, bot.prompt(), msgs.toList(), bot.id, Prefs.fast(this), object : Gemini.Listener {
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
