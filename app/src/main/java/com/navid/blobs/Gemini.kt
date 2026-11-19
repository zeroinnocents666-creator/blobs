package com.navid.blobs

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import android.util.Base64
import android.util.Base64OutputStream
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder
import java.net.UnknownHostException
import java.text.DateFormat
import java.util.Date
import javax.net.ssl.SSLException

/**
 * Gemini REST client (v1beta, streamGenerateContent with SSE) on HttpURLConnection.
 * Connections are kept alive: responses are read to the end and closed, never disconnected (except on Stop),
 * so the platform's connection pool reuses the TLS socket for the next request.
 */
object Gemini {
    private const val HOST = "https://generativelanguage.googleapis.com/v1beta/"
    private const val BASE = HOST + "models/"
    const val MAX_TURNS = 30
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var lastWarm = 0L

    class Call {
        @Volatile var cancelled = false
        @Volatile var conn: HttpURLConnection? = null
        fun cancel() {
            cancelled = true
            val c = conn
            Thread { try { c?.disconnect() } catch (_: Exception) {} }.start()
        }
    }

    interface Listener {
        /** First text of the reply arrived from [model]. */
        fun onStart(model: String)
        fun onChunk(text: String)
        /** Finished. [note] is a short status for the reply ("" if none). */
        fun onDone(model: String, sources: List<Pair<String, String>>, note: String)
        /** Failed. Any text already streamed is kept by the caller. */
        fun onError(message: String)
        /** Settings were moved from a failing manual model to Auto. */
        fun onMigrated()
    }

    /** Request contents plus the attachment files that are streamed (base64) in place of their placeholder tokens. */
    class Payload(val contents: JSONArray, val blobs: Map<String, File>, val hasMedia: Boolean)

    private val TOKEN = Regex("@@BLOBSATT_[0-9a-f]+_[0-9]+@@")

    /**
     * Builds "contents": last [MAX_TURNS] turns, starting with a user turn, same-role neighbours merged.
     * Attachments are sent in full only for the newest [Attach.MEDIA_TURNS] user turns that have them (and within
     * [Attach.MAX_MESSAGE_BYTES] together); older ones become a short text placeholder such as "[image: name]".
     */
    fun contents(ctx: Context, botId: String, history: List<Msg>): Payload {
        var msgs = history.filter { it.text.isNotBlank() || it.atts.isNotEmpty() }
        var users = 0; var start = 0
        for (i in msgs.indices.reversed()) {
            if (msgs[i].role == "user") { users++; start = i; if (users >= MAX_TURNS) break }
        }
        msgs = msgs.drop(start)

        val full = java.util.IdentityHashMap<Msg, Boolean>()
        var budget = 0L; var turns = 0
        for (m in msgs.reversed()) {
            if (m.role != "user" || m.atts.isEmpty()) continue
            if (turns >= Attach.MEDIA_TURNS) break
            val bytes = m.atts.sumOf { Attach.file(ctx, botId, it).let { f -> if (f.exists()) f.length() else 0L } }
            if (turns == 0 && bytes > Attach.MAX_MESSAGE_BYTES)
                throw Fail(Kind.FATAL, "These attachments are too big (${Attach.human(bytes)}). One message can carry about " +
                    "${Attach.human(Attach.MAX_MESSAGE_BYTES)}. Remove a file or send a smaller PDF.")
            if (budget + bytes > Attach.MAX_MESSAGE_BYTES) break
            budget += bytes; turns++; full[m] = true
        }

        val nonce = java.lang.Long.toHexString(System.nanoTime())
        val blobs = LinkedHashMap<String, File>()
        val arr = JSONArray()
        var lastRole = ""; var parts = JSONArray()
        for (m in msgs) {
            val role = if (m.role == "model") "model" else "user"
            if (role != lastRole && parts.length() > 0) {
                arr.put(JSONObject().put("role", lastRole).put("parts", parts)); parts = JSONArray()
            }
            if (m.text.isNotBlank()) parts.put(JSONObject().put("text", m.text))
            val isFull = full.containsKey(m)
            for (a in m.atts) {
                val f = Attach.file(ctx, botId, a)
                val label = if (a.isImage()) "image" else "file"
                if (!f.exists()) { parts.put(JSONObject().put("text", "[$label no longer available: ${a.name}]")); continue }
                if (!isFull) { parts.put(JSONObject().put("text", "[$label: ${a.name}]")); continue }
                when (a.kind) {
                    "image", "pdf" -> {
                        val tok = "@@BLOBSATT_${nonce}_${blobs.size}@@"
                        blobs[tok] = f
                        parts.put(JSONObject().put("inline_data", JSONObject().put("mime_type", a.mime).put("data", tok)))
                    }
                    else -> {
                        val body = try { f.readText(Charsets.UTF_8) } catch (e: Exception) { "" }
                        parts.put(JSONObject().put("text", "File: ${a.name}\n```\n$body\n```"))
                    }
                }
            }
            if (parts.length() == 0) parts.put(JSONObject().put("text", " "))
            lastRole = role
        }
        if (parts.length() > 0) arr.put(JSONObject().put("role", lastRole).put("parts", parts))
        return Payload(arr, blobs, blobs.isNotEmpty())
    }

    /** Writes [json] with every placeholder token replaced by the base64 of its file, streamed (no big strings in memory). */
    private fun writeBody(c: HttpURLConnection, json: String, blobs: Map<String, File>) {
        if (blobs.isEmpty()) {
            val b = json.toByteArray(Charsets.UTF_8)
            c.setFixedLengthStreamingMode(b.size)
            c.outputStream.use { it.write(b) }
            return
        }
        val pieces = ArrayList<Any>()
        var i = 0
        for (mt in TOKEN.findAll(json)) {
            val f = blobs[mt.value] ?: continue
            pieces.add(json.substring(i, mt.range.first).toByteArray(Charsets.UTF_8)); pieces.add(f)
            i = mt.range.last + 1
        }
        pieces.add(json.substring(i).toByteArray(Charsets.UTF_8))
        var total = 0L
        for (p in pieces) total += if (p is ByteArray) p.size.toLong() else ((p as File).length() + 2) / 3 * 4
        c.setFixedLengthStreamingMode(total)
        c.outputStream.use { os ->
            val keep = object : java.io.FilterOutputStream(os) {
                override fun write(b: ByteArray, off: Int, len: Int) { out.write(b, off, len) }
                override fun close() { flush() }
            }
            for (p in pieces) {
                if (p is ByteArray) os.write(p)
                else {
                    val b64 = Base64OutputStream(keep, Base64.NO_WRAP)
                    (p as File).inputStream().use { it.copyTo(b64, 64 * 1024) }
                    b64.close()
                }
            }
        }
    }

    // ---------- failures ----------

    private enum class Kind { FATAL, QUOTA_DAY, QUOTA_SHORT, UNAVAILABLE, SERVER, BAD_THINKING, BAD_SEARCH, BAD_MEDIA }
    private class Fail(val kind: Kind, val msg: String, val retryMs: Long = 0L) : Exception(msg)
    private class MidStream(msg: String) : Exception(msg)
    private class Result(val sources: List<Pair<String, String>>)

    /**
     * Streams one reply, walking [attempts] in order. Quota / unavailable / overloaded errors before the first token
     * move on to the next attempt and are remembered in [Limits] so later messages skip that model straight away.
     */
    fun stream(
        context: Context, key: String, attempts: List<Router.Attempt>, system: String, history: List<Msg>,
        botId: String, fast: Boolean, l: Listener
    ): Call {
        val ctx = context.applicationContext
        val call = Call()
        Thread {
            var lastMsg = "Gemini did not answer. Try again."
            var allQuota = true
            var migrated = false
            var searchMediaFail = false
            try {
                if (key.isBlank()) throw Fail(Kind.FATAL, "No API key yet. Add your Gemini API key in Settings.")
                val contents = contents(ctx, botId, history)
                for (a in attempts) {
                    if (call.cancelled) return@Thread
                    var thinking = true
                    var res: Result? = null
                    var fail: Fail? = null
                    for (round in 0..1) {
                        try { res = once(call, key, a, system, contents, fast && thinking, l); fail = null; break }
                        catch (f: Fail) {
                            fail = f
                            if (f.kind == Kind.BAD_THINKING && thinking) { thinking = false; continue }
                            break
                        }
                    }
                    if (call.cancelled) return@Thread
                    val done = res
                    if (done != null) {
                        val note = if (!a.searchFallback) "" else if (searchMediaFail) "no search with files" else "search unavailable"
                        main.post { if (!call.cancelled) l.onDone(a.model, done.sources, note) }
                        return@Thread
                    }
                    val f = fail ?: continue
                    val now = System.currentTimeMillis()
                    when (f.kind) {
                        Kind.FATAL -> throw f
                        Kind.QUOTA_DAY -> Limits.mark(ctx, a.model, a.search, Limits.nextPacificMidnight(), "daily limit")
                        Kind.QUOTA_SHORT -> Limits.mark(ctx, a.model, a.search, now + f.retryMs.coerceIn(15_000L, 600_000L), "rate limit")
                        Kind.UNAVAILABLE -> Limits.mark(ctx, a.model, a.search, Limits.nextPacificMidnight(), "not available for this key")
                        Kind.SERVER -> Limits.mark(ctx, a.model, a.search, now + 120_000L, "busy")
                        Kind.BAD_SEARCH -> Limits.mark(ctx, a.model, true, Limits.nextPacificMidnight(), "search not available")
                        Kind.BAD_THINKING -> Limits.mark(ctx, a.model, a.search, now + 600_000L, "rejected request")
                        // A model that can't read these files is skipped for this reply only (text still works).
                        Kind.BAD_MEDIA -> if (a.search) searchMediaFail = true
                    }
                    if (f.kind != Kind.QUOTA_DAY && f.kind != Kind.QUOTA_SHORT) allQuota = false
                    lastMsg = f.msg
                    if (a.migrate && !migrated && f.kind != Kind.BAD_MEDIA) {
                        migrated = true; Prefs.setAuto(ctx, true)
                        main.post { if (!call.cancelled) l.onMigrated() }
                    }
                }
                if (call.cancelled) return@Thread
                val msg = if (allQuota && attempts.size > 1) {
                    val t = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(Limits.nextPacificMidnight()))
                    "All free models have hit their limit for now. Daily limits reset at $t (your time). Try again later."
                } else lastMsg
                main.post { if (!call.cancelled) l.onError(msg) }
            } catch (e: Exception) {
                if (call.cancelled) return@Thread
                val msg = friendly(e)
                main.post { if (!call.cancelled) l.onError(msg) }
            }
        }.start()
        return call
    }

    /** One request to one model. Throws [Fail] before the first token, [MidStream] after it. */
    private fun once(
        call: Call, key: String, a: Router.Attempt, system: String, payload: Payload, thinking: Boolean, l: Listener
    ): Result {
        val body = JSONObject()
            .put("contents", payload.contents)
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system + Store.accuracyRules(a.search)))))
        val tc = if (thinking) Models.thinking(a.model, true) else null
        if (tc != null) body.put("generationConfig", JSONObject().put("thinkingConfig", tc))
        if (a.search) body.put("tools", JSONArray().put(JSONObject().put("google_search", JSONObject())))

        var gotText = false
        val sources = LinkedHashMap<String, String>()
        try {
            val url = URL(BASE + URLEncoder.encode(a.model.trim().removePrefix("models/"), "UTF-8") + ":streamGenerateContent?alt=sse")
            val c = url.openConnection() as HttpURLConnection
            call.conn = c
            c.requestMethod = "POST"
            c.connectTimeout = 15000; c.readTimeout = 60000
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            c.setRequestProperty("Accept", "text/event-stream")
            c.setRequestProperty("Connection", "keep-alive")
            c.setRequestProperty("x-goog-api-key", key.trim())
            writeBody(c, body.toString(), payload.blobs)
            val code = c.responseCode
            if (code !in 200..299) throw classify(code, readErr(c), a, payload.hasMedia)
            var blockReason: String? = null
            var finish: String? = null
            BufferedReader(InputStreamReader(c.inputStream, Charsets.UTF_8), 8192).use { r ->
                while (!call.cancelled) {
                    val line = r.readLine() ?: break
                    if (!line.startsWith("data:")) continue
                    val data = line.substring(5).trim()
                    if (data.isEmpty() || data == "[DONE]") continue
                    val o = try { JSONObject(data) } catch (e: Exception) { continue }
                    o.optJSONObject("error")?.let {
                        val f = classify(it.optInt("code", 500), data, a, payload.hasMedia)
                        if (gotText) throw MidStream(f.msg) else throw f
                    }
                    o.optJSONObject("promptFeedback")?.optString("blockReason")?.takeIf { it.isNotEmpty() }?.let { blockReason = it }
                    val cand = o.optJSONArray("candidates")?.optJSONObject(0) ?: continue
                    cand.optString("finishReason").takeIf { it.isNotEmpty() }?.let { finish = it }
                    cand.optJSONObject("groundingMetadata")?.optJSONArray("groundingChunks")?.let { ch ->
                        for (i in 0 until ch.length()) {
                            val w = ch.optJSONObject(i)?.optJSONObject("web") ?: continue
                            val u = w.optString("uri"); if (u.isEmpty()) continue
                            if (!sources.containsKey(u)) sources[u] = w.optString("title").ifEmpty { u }
                        }
                    }
                    val parts = cand.optJSONObject("content")?.optJSONArray("parts") ?: continue
                    val sb = StringBuilder()
                    for (i in 0 until parts.length()) {
                        val p = parts.optJSONObject(i) ?: continue
                        if (p.optBoolean("thought", false)) continue
                        sb.append(p.optString("text", ""))
                    }
                    if (sb.isNotEmpty()) {
                        if (!gotText) { gotText = true; main.post { if (!call.cancelled) l.onStart(a.model) } }
                        val s = sb.toString()
                        main.post { if (!call.cancelled) l.onChunk(s) }
                    }
                }
            }
            if (call.cancelled) return Result(emptyList())
            if (!gotText) {
                val msg = when {
                    blockReason != null -> "Gemini blocked this request ($blockReason). Try rephrasing."
                    finish == "SAFETY" -> "The reply was blocked by safety filters. Try rephrasing."
                    finish == "MAX_TOKENS" -> "The reply hit the token limit before any text was produced."
                    else -> "Gemini returned an empty reply. Try again."
                }
                // An empty reply from one model is worth one more model; a block is not.
                throw Fail(if (blockReason != null || finish == "SAFETY") Kind.FATAL else Kind.SERVER, msg)
            }
            return Result(sources.map { it.key to it.value })
        } catch (f: Fail) { throw f }
        catch (m: MidStream) { throw Fail(Kind.FATAL, m.message ?: "The reply was interrupted.") }
        catch (e: Exception) {
            if (call.cancelled) return Result(emptyList())
            if (gotText) throw Fail(Kind.FATAL, "The reply was interrupted. " + friendly(e))
            if (e is SocketTimeoutException) throw Fail(Kind.SERVER, friendly(e))
            throw Fail(Kind.FATAL, friendly(e))
        } finally {
            if (call.cancelled) try { call.conn?.disconnect() } catch (_: Exception) {}
        }
    }

    /** Opens (and pools) the TLS connection ahead of the first message. Cheap: lists one model. */
    fun warm(key: String) {
        if (key.isBlank() || System.currentTimeMillis() - lastWarm < 60_000) return
        lastWarm = System.currentTimeMillis()
        Thread {
            try {
                val c = URL(HOST + "models?pageSize=1").openConnection() as HttpURLConnection
                c.connectTimeout = 10000; c.readTimeout = 10000
                c.setRequestProperty("x-goog-api-key", key.trim())
                val s = if (c.responseCode in 200..299) c.inputStream else c.errorStream
                s?.use { it.readBytes() }
            } catch (_: Exception) {}
        }.start()
    }

    /** Checks the key. With a [model] it looks that model up; without, it lists models. Callback gets null on success. */
    fun test(key: String, model: String?, cb: (String?) -> Unit) {
        Thread {
            val res: String? = try {
                if (key.isBlank()) throw Fail(Kind.FATAL, "Paste a key first.")
                val u = if (model.isNullOrBlank()) HOST + "models?pageSize=1"
                        else BASE + URLEncoder.encode(model.trim().removePrefix("models/"), "UTF-8")
                val c = URL(u).openConnection() as HttpURLConnection
                c.connectTimeout = 15000; c.readTimeout = 20000
                c.setRequestProperty("x-goog-api-key", key.trim())
                val code = c.responseCode
                if (code in 200..299) { c.inputStream.use { it.readBytes() }; null }
                else classify(code, readErr(c), Router.Attempt(model ?: "", false)).msg
            } catch (e: Exception) { friendly(e) }
            main.post { cb(res) }
        }.start()
    }

    private fun readErr(c: HttpURLConnection): String =
        try { c.errorStream?.bufferedReader()?.use { it.readText() } ?: "" } catch (e: Exception) { "" }

    private fun friendly(e: Exception): String = when (e) {
        is Fail -> e.msg
        is UnknownHostException, is ConnectException -> "No internet connection. Check your network and try again."
        is SocketTimeoutException -> "The request timed out. Check your connection and try again."
        is SSLException -> "Secure connection failed. Check your network (public Wi-Fi can block this)."
        else -> "Connection problem: ${e.message ?: e.javaClass.simpleName}"
    }

    private fun parseDelay(s: String): Long {
        // "37s" or "1.5s"
        val v = s.trim().removeSuffix("s").toDoubleOrNull() ?: return 0L
        return (v * 1000).toLong()
    }

    private fun classify(code: Int, body: String, a: Router.Attempt, media: Boolean = false): Fail {
        val model = a.model
        var message = ""; var status = ""; var reason = ""
        var perDay = false; var retry = 0L
        try {
            val err = JSONObject(body).optJSONObject("error")
            if (err != null) {
                message = err.optString("message"); status = err.optString("status")
                val d = err.optJSONArray("details")
                if (d != null) for (i in 0 until d.length()) {
                    val x = d.optJSONObject(i) ?: continue
                    x.optString("reason").takeIf { it.isNotEmpty() && reason.isEmpty() }?.let { reason = it }
                    x.optString("retryDelay").takeIf { it.isNotEmpty() }?.let { retry = parseDelay(it) }
                    val v = x.optJSONArray("violations")
                    if (v != null) for (j in 0 until v.length()) {
                        val q = v.optJSONObject(j)?.optString("quotaId").orEmpty().lowercase()
                        if (q.contains("perday")) perDay = true
                    }
                }
            }
        } catch (_: Exception) {}
        val low = (message + " " + reason).lowercase()
        val name = Models.label(model)
        return when {
            reason == "API_KEY_INVALID" || low.contains("api key not valid") || low.contains("api_key_invalid") ->
                Fail(Kind.FATAL, "Your API key is not valid. Check it in Settings (get one at aistudio.google.com/apikey).")
            code == 429 || status == "RESOURCE_EXHAUSTED" -> when {
                low.contains("limit: 0") -> Fail(Kind.UNAVAILABLE, "$name is not included in this key's free tier. Pick another model in Settings.")
                perDay || low.contains("per day") -> Fail(Kind.QUOTA_DAY, "$name has hit its daily limit. Pick another model in Settings or use Auto.")
                else -> Fail(Kind.QUOTA_SHORT, "$name is rate limited right now. Wait a minute, or use Auto in Settings.",
                    if (retry > 0) retry else 60_000L)
            }
            code == 404 || status == "NOT_FOUND" ->
                Fail(Kind.UNAVAILABLE, "Model \"$model\" is not available for this key. Pick another model in Settings.")
            code == 403 || status == "PERMISSION_DENIED" ->
                if (low.contains("api key") || low.contains("service_disabled") || low.contains("has not been used") || low.contains("api_key"))
                    Fail(Kind.FATAL, "This key is not allowed to use Gemini" + (if (message.isNotEmpty()) ": $message" else "."))
                else Fail(Kind.UNAVAILABLE, "$name is not available for this key" + (if (message.isNotEmpty()) ": $message" else "."))
            code == 400 && low.contains("location") ->
                Fail(Kind.FATAL, "Gemini API is not available in your region for this key.")
            code == 413 || low.contains("payload size") || low.contains("request too large") || low.contains("exceeds the maximum") ->
                Fail(Kind.FATAL, "This message is too big for Gemini. Send fewer or smaller files (about ${Attach.human(Attach.MAX_MESSAGE_BYTES)} per message).")
            code == 400 && media && listOf("mime", "image", "inline", "media", "pdf", "document", "modality", "unsupported file", "file type")
                .any { low.contains(it) } ->
                Fail(Kind.BAD_MEDIA, "$name can't read this file type. " + message.ifEmpty { "" }.take(160))
            code == 400 && low.contains("thinking") ->
                Fail(Kind.BAD_THINKING, "Request rejected: " + message.ifEmpty { "thinking settings" })
            code == 400 && a.search && (low.contains("search") || low.contains("grounding") || low.contains("tool")) ->
                Fail(Kind.BAD_SEARCH, "Google Search is not available for $name with this key.")
            code == 400 && (low.contains("not supported") || low.contains("not available") || low.contains("model")) ->
                Fail(Kind.UNAVAILABLE, "Request rejected by $name: " + message.ifEmpty { "not supported" })
            code == 400 -> Fail(Kind.FATAL, "Request rejected: " + message.ifEmpty { "bad request" })
            code >= 500 -> Fail(Kind.SERVER, "Gemini is busy right now ($code). Try again in a moment.")
            else -> Fail(Kind.FATAL, "Error $code" + (if (message.isNotEmpty()) ": $message" else ""))
        }
    }
}
