package com.navid.blobs

import android.app.Dialog
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.RectF
import android.media.ExifInterface
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.util.LruCache
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.MimeTypeMap
import android.widget.FrameLayout
import android.widget.ImageView
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale
import java.util.UUID

/**
 * One attachment on a user message (v1.2). The file lives in files/attachments/<botId>/<file>.
 * [kind] is "image", "pdf" or "text". [size] is the stored file size in bytes.
 */
class Att(val kind: String, val name: String, val mime: String, val file: String, val size: Long, val w: Int = 0, val h: Int = 0) {
    fun json(): JSONObject = JSONObject().put("k", kind).put("n", name).put("mt", mime).put("f", file).put("s", size)
        .apply { if (w > 0) put("w", w).put("h", h) }

    fun isImage() = kind == "image"

    companion object {
        fun from(o: JSONObject) = Att(
            o.optString("k", "text"), o.optString("n", "file"), o.optString("mt", "application/octet-stream"),
            o.optString("f", ""), o.optLong("s", 0L), o.optInt("w", 0), o.optInt("h", 0)
        )
        fun listJson(l: List<Att>): String = JSONArray().apply { l.forEach { put(it.json()) } }.toString()
        fun parseList(s: String?): List<Att> = try {
            val a = JSONArray(s ?: "[]"); (0 until a.length()).map { from(a.getJSONObject(it)) }
        } catch (e: Exception) { emptyList() }
    }
}

object Attach {
    const val MAX_PER_MESSAGE = 5
    /** Raw bytes allowed per message. Inline data is base64 (+33%) and the whole request must stay under 20 MB. */
    const val MAX_MESSAGE_BYTES = 14L * 1024 * 1024
    const val MAX_TEXT_BYTES = 200 * 1024
    const val MAX_IMAGE_SIDE = 1600
    /** User turns (newest first) whose images / files are sent in full; older ones become "[image: name]". */
    const val MEDIA_TURNS = 3

    class Err(msg: String) : Exception(msg)

    fun root(ctx: Context) = File(ctx.filesDir, "attachments")
    fun dir(ctx: Context, botId: String) = File(root(ctx), botId).apply { mkdirs() }
    fun file(ctx: Context, botId: String, a: Att) = File(File(root(ctx), botId), a.file)

    fun deleteBot(ctx: Context, botId: String) { File(root(ctx), botId).deleteRecursively() }
    fun deleteAll(ctx: Context) { root(ctx).deleteRecursively() }

    /** Removes files in the bot's folder that no message refers to (staged but never sent), older than a day. */
    fun sweep(ctx: Context, botId: String, msgs: List<Msg>) {
        val used = msgs.flatMap { m -> m.atts.map { it.file } }.toHashSet()
        val cutoff = System.currentTimeMillis() - 24 * 3600_000L
        File(root(ctx), botId).listFiles()?.forEach { if (it.name !in used && it.lastModified() < cutoff) it.delete() }
    }

    fun human(n: Long): String = when {
        n >= 1024 * 1024 -> String.format(Locale.US, "%.1f MB", n / 1048576.0)
        n >= 1024 -> "${n / 1024} KB"
        else -> "$n B"
    }

    private val TEXT_EXT = setOf(
        "txt", "md", "markdown", "csv", "tsv", "json", "jsonl", "xml", "html", "htm", "css", "js", "mjs", "ts", "tsx", "jsx",
        "kt", "kts", "java", "py", "rb", "go", "rs", "c", "h", "cpp", "cc", "hpp", "cs", "swift", "dart", "php", "sh", "bash",
        "zsh", "bat", "ps1", "sql", "yaml", "yml", "toml", "ini", "cfg", "conf", "properties", "gradle", "log", "env", "srt",
        "vtt", "tex", "r", "lua", "pl", "smali", "gitignore", "dockerfile", "makefile"
    )
    private val TEXT_MIME = setOf(
        "application/json", "application/xml", "application/javascript", "application/x-javascript", "application/x-sh",
        "application/x-yaml", "application/yaml", "application/toml", "application/sql", "application/x-python",
        "application/x-kotlin", "application/x-java", "application/csv", "application/x-subrip", "application/ld+json"
    )

    private fun ext(name: String) = name.substringAfterLast('.', name).lowercase(Locale.US)

    private fun query(ctx: Context, uri: Uri): Pair<String, Long> {
        var name = uri.lastPathSegment?.substringAfterLast('/') ?: "file"
        var size = -1L
        try {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val si = c.getColumnIndex(OpenableColumns.SIZE)
                    if (ni >= 0 && !c.isNull(ni)) name = c.getString(ni)
                    if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
                }
            }
        } catch (_: Exception) {}
        return name to size
    }

    /** Kind of a picked uri: "image", "pdf", "text" or null (unsupported). */
    fun kindOf(mime: String, name: String): String? {
        val e = ext(name)
        return when {
            mime.startsWith("image/") -> "image"
            mime == "application/pdf" || e == "pdf" -> "pdf"
            mime.startsWith("text/") || mime in TEXT_MIME || e in TEXT_EXT -> "text"
            else -> null
        }
    }

    /**
     * Copies [uri] into the bot's private attachment folder. Images are downscaled (1600 px long side) and saved as
     * JPEG q85; small PNG / JPEG / WebP images that need no resizing are kept as they are. Run off the main thread.
     */
    fun import(ctx: Context, botId: String, uri: Uri, hintMime: String? = null): Att {
        val cr = ctx.contentResolver
        val (name0, size) = query(ctx, uri)
        val name = name0.ifBlank { "file" }
        var mime = (try { cr.getType(uri) } catch (_: Exception) { null }) ?: hintMime ?: ""
        if (mime.isEmpty() || mime == "application/octet-stream") {
            mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext(name)) ?: mime
        }
        val kind = kindOf(mime, name) ?: throw Err("\"$name\" can't be sent. Photos, PDFs and text or code files work.")
        val id = UUID.randomUUID().toString().substring(0, 12)
        val d = dir(ctx, botId)
        when (kind) {
            "image" -> return importImage(ctx, uri, name, mime, size, File(d, id))
            "pdf" -> {
                if (size > MAX_MESSAGE_BYTES) throw Err("\"$name\" is ${human(size)}. PDFs up to ${human(MAX_MESSAGE_BYTES)} can be sent.")
                val out = File(d, "$id.pdf")
                var n = 0L
                (cr.openInputStream(uri) ?: throw Err("Couldn't open \"$name\".")).use { inp ->
                    out.outputStream().use { o ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val r = inp.read(buf); if (r < 0) break
                            n += r
                            if (n > MAX_MESSAGE_BYTES) { o.close(); out.delete(); throw Err("\"$name\" is too big. PDFs up to ${human(MAX_MESSAGE_BYTES)} can be sent.") }
                            o.write(buf, 0, r)
                        }
                    }
                }
                return Att("pdf", name, "application/pdf", out.name, n)
            }
            else -> {
                val bytes = (cr.openInputStream(uri) ?: throw Err("Couldn't open \"$name\".")).use { inp ->
                    val bo = ByteArrayOutputStream(); val buf = ByteArray(16 * 1024)
                    while (bo.size() < MAX_TEXT_BYTES) {
                        val r = inp.read(buf, 0, minOf(buf.size, MAX_TEXT_BYTES - bo.size())); if (r < 0) break
                        bo.write(buf, 0, r)
                    }
                    bo.toByteArray()
                }
                val probe = bytes.size.coerceAtMost(8192)
                for (i in 0 until probe) if (bytes[i] == 0.toByte()) throw Err("\"$name\" looks like a binary file. Text and code files work.")
                // cut at a clean UTF-8 boundary
                var end = bytes.size
                if (end == MAX_TEXT_BYTES) while (end > 0 && (bytes[end - 1].toInt() and 0xC0) == 0x80) end--
                if (end == MAX_TEXT_BYTES && end > 0 && (bytes[end - 1].toInt() and 0x80) != 0) end--
                val out = File(d, "$id.txt")
                out.writeBytes(bytes.copyOf(end))
                val truncated = size > MAX_TEXT_BYTES || bytes.size >= MAX_TEXT_BYTES
                return Att("text", name + (if (truncated) " (first 200 KB)" else ""), mime.ifEmpty { "text/plain" }, out.name, end.toLong())
            }
        }
    }

    private fun importImage(ctx: Context, uri: Uri, name: String, mime: String, size: Long, base: File): Att {
        val cr = ctx.contentResolver
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) }
        if (o.outWidth <= 0 || o.outHeight <= 0) throw Err("Couldn't read \"$name\" as an image.")
        val rot = try { cr.openInputStream(uri)?.use { exifRotation(ExifInterface(it)) } ?: 0 } catch (_: Exception) { 0 }
        val long = maxOf(o.outWidth, o.outHeight)
        val keepable = mime == "image/png" || mime == "image/jpeg" || mime == "image/webp"
        if (keepable && rot == 0 && long <= MAX_IMAGE_SIDE && size in 1..(1536 * 1024)) {
            val ext = when (mime) { "image/png" -> "png"; "image/webp" -> "webp"; else -> "jpg" }
            val out = File(base.parentFile, base.name + "." + ext)
            cr.openInputStream(uri)?.use { inp -> out.outputStream().use { inp.copyTo(it) } } ?: throw Err("Couldn't open \"$name\".")
            return Att("image", name, mime, out.name, out.length(), o.outWidth, o.outHeight)
        }
        var sample = 1
        while (long / (sample * 2) >= MAX_IMAGE_SIDE) sample *= 2
        val bmp0 = cr.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: throw Err("Couldn't read \"$name\" as an image.")
        var bmp = bmp0
        val l = maxOf(bmp.width, bmp.height)
        val m = Matrix()
        if (l > MAX_IMAGE_SIDE) { val s = MAX_IMAGE_SIDE.toFloat() / l; m.postScale(s, s) }
        if (rot != 0) m.postRotate(rot.toFloat())
        if (!m.isIdentity) {
            bmp = Bitmap.createBitmap(bmp0, 0, 0, bmp0.width, bmp0.height, m, true)
            if (bmp !== bmp0) bmp0.recycle()
        }
        // keep PNG when it stays small (screenshots of text stay sharp), otherwise JPEG q85
        var out: File? = null
        if (mime == "image/png") {
            val bo = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.PNG, 100, bo)
            if (bo.size() <= 1024 * 1024) { out = File(base.parentFile, base.name + ".png"); out.writeBytes(bo.toByteArray()) }
        }
        val isPng = out != null
        if (out == null) {
            out = File(base.parentFile, base.name + ".jpg")
            val flat = if (bmp.hasAlpha()) {
                val b2 = Bitmap.createBitmap(bmp.width, bmp.height, Bitmap.Config.ARGB_8888)
                android.graphics.Canvas(b2).apply { drawColor(Color.WHITE); drawBitmap(bmp, 0f, 0f, null) }
                b2
            } else bmp
            out.outputStream().use { flat.compress(Bitmap.CompressFormat.JPEG, 85, it) }
            if (flat !== bmp) flat.recycle()
        }
        val w = bmp.width; val h = bmp.height
        bmp.recycle()
        val nm = if (isPng) name else name.substringBeforeLast('.', name) + ".jpg"
        return Att("image", nm, if (isPng) "image/png" else "image/jpeg", out.name, out.length(), w, h)
    }

    private fun exifRotation(e: ExifInterface): Int = when (e.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
        ExifInterface.ORIENTATION_ROTATE_90 -> 90
        ExifInterface.ORIENTATION_ROTATE_180 -> 180
        ExifInterface.ORIENTATION_ROTATE_270 -> 270
        else -> 0
    }

    // ---------- thumbnails ----------

    private val cache = object : LruCache<String, Bitmap>(16 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    /** Decodes [f] to roughly [px] on its long side. Cached. Call off the main thread for big images. */
    fun thumb(f: File, px: Int): Bitmap? {
        val key = f.path + "@" + px
        cache.get(key)?.let { return it }
        return decode(f, px)?.also { cache.put(key, it) }
    }

    fun decode(f: File, px: Int): Bitmap? {
        if (!f.exists()) return null
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, o)
        if (o.outWidth <= 0) return null
        var s = 1
        while (maxOf(o.outWidth, o.outHeight) / (s * 2) >= px) s *= 2
        return BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = s })
    }

    /** Loads a thumbnail into [iv] in the background. */
    fun load(iv: ImageView, f: File, px: Int) {
        val key = f.path + "@" + px
        cache.get(key)?.let { iv.setImageBitmap(it); return }
        iv.tag = key
        Thread {
            val b = thumb(f, px)
            iv.post { if (iv.tag == key && b != null) iv.setImageBitmap(b) }
        }.start()
    }

    /** Full-screen image viewer with pinch-zoom, pan and double-tap. */
    fun view(ctx: Context, f: File) {
        val d = Dialog(ctx, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val root = FrameLayout(ctx).apply { setBackgroundColor(Color.BLACK) }
        val zv = ZoomImageView(ctx)
        root.addView(zv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(ctx.iconButton(R.drawable.ms_close, Color.WHITE, 0x66000000, 44) { d.dismiss() },
            FrameLayout.LayoutParams(ctx.dp(44), ctx.dp(44), Gravity.TOP or Gravity.END).apply { topMargin = ctx.dp(36); rightMargin = ctx.dp(16) })
        d.setContentView(root)
        d.window?.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
        zv.setOnClickListener { d.dismiss() }
        d.show()
        Thread {
            val b = decode(f, 2048)
            root.post { if (b != null) zv.setImageBitmap(b) }
        }.start()
    }
}

/** Minimal pinch-zoom ImageView: fit-center, pinch 1x..5x, drag, double-tap to zoom 2.5x / reset. Single tap = click. */
class ZoomImageView(ctx: Context) : ImageView(ctx) {
    private val m = Matrix()
    private var base = Matrix()
    private var scale = 1f
    private val scaleDet = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            val ns = (scale * d.scaleFactor).coerceIn(1f, 5f)
            val f = ns / scale; scale = ns
            m.postScale(f, f, d.focusX, d.focusY); clamp(); return true
        }
    })
    private val gest = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            if (scale > 1f) { m.postTranslate(-dx, -dy); clamp() }; return true
        }
        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (scale > 1.01f) { m.set(base); scale = 1f } else { m.postScale(2.5f, 2.5f, e.x, e.y); scale = 2.5f }
            clamp(); return true
        }
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean { performClick(); return true }
    })

    init { scaleType = ScaleType.MATRIX }

    override fun setImageBitmap(bm: Bitmap?) { super.setImageBitmap(bm); post { fit() } }
    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) { super.onSizeChanged(w, h, ow, oh); fit() }

    private fun fit() {
        val d = drawable ?: return
        if (width == 0 || height == 0) return
        base = Matrix().apply {
            setRectToRect(RectF(0f, 0f, d.intrinsicWidth.toFloat(), d.intrinsicHeight.toFloat()),
                RectF(0f, 0f, width.toFloat(), height.toFloat()), Matrix.ScaleToFit.CENTER)
        }
        m.set(base); scale = 1f; imageMatrix = m
    }

    private fun clamp() {
        val d = drawable ?: return
        val r = RectF(0f, 0f, d.intrinsicWidth.toFloat(), d.intrinsicHeight.toFloat()); m.mapRect(r)
        var dx = 0f; var dy = 0f
        if (r.width() <= width) dx = (width - r.width()) / 2 - r.left
        else if (r.left > 0) dx = -r.left else if (r.right < width) dx = width - r.right
        if (r.height() <= height) dy = (height - r.height()) / 2 - r.top
        else if (r.top > 0) dy = -r.top else if (r.bottom < height) dy = height - r.bottom
        m.postTranslate(dx, dy); imageMatrix = m
    }

    @android.annotation.SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        scaleDet.onTouchEvent(e); gest.onTouchEvent(e); return true
    }
}

/**
 * Tiny FileProvider replacement (no AndroidX). content://com.navid.blobs.files/cam/<name> = cache/camera (read/write,
 * for ACTION_IMAGE_CAPTURE); content://com.navid.blobs.files/att/<botId>/<name> = files/attachments (read-only, for
 * opening a sent PDF / file in another app). Not exported; access only through per-uri grants.
 */
class FilesProvider : ContentProvider() {
    companion object {
        const val AUTH = "com.navid.blobs.files"
        fun camUri(name: String): Uri = Uri.parse("content://$AUTH/cam/$name")
        fun camDir(ctx: Context) = File(ctx.cacheDir, "camera").apply { mkdirs() }
        fun attUri(botId: String, file: String): Uri = Uri.parse("content://$AUTH/att/$botId/$file")
    }

    override fun onCreate() = true

    private fun resolve(uri: Uri): Pair<File, Boolean>? {
        val ctx = context ?: return null
        val seg = uri.pathSegments
        if (seg.size < 2) return null
        val (root, writable) = when (seg[0]) {
            "cam" -> camDir(ctx) to true
            "att" -> Attach.root(ctx) to false
            else -> return null
        }
        val f = File(root, seg.drop(1).joinToString("/"))
        val canon = f.canonicalPath
        if (!canon.startsWith(root.canonicalPath + File.separator)) return null
        return f to writable
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val (f, writable) = resolve(uri) ?: throw java.io.FileNotFoundException(uri.toString())
        val wants = mode.contains('w') || mode.contains('a') || mode.contains('t')
        if (wants && !writable) throw SecurityException("read-only")
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.parseMode(mode))
    }

    override fun getType(uri: Uri): String {
        val n = uri.lastPathSegment ?: return "application/octet-stream"
        return when (n.substringAfterLast('.').lowercase(Locale.US)) {
            "jpg", "jpeg" -> "image/jpeg"; "png" -> "image/png"; "webp" -> "image/webp"
            "pdf" -> "application/pdf"; "txt" -> "text/plain"
            else -> "application/octet-stream"
        }
    }

    override fun query(uri: Uri, proj: Array<out String>?, sel: String?, args: Array<out String>?, sort: String?): Cursor? {
        val (f, _) = resolve(uri) ?: return null
        val cols = proj?.filter { it == OpenableColumns.DISPLAY_NAME || it == OpenableColumns.SIZE }?.toTypedArray()
            ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return MatrixCursor(cols, 1).apply {
            addRow(cols.map { if (it == OpenableColumns.DISPLAY_NAME) f.name else f.length() })
        }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, sel: String?, args: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, sel: String?, args: Array<out String>?) = 0
}
