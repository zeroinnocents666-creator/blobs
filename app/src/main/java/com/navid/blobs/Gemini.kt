package com.navid.blobs

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
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

    /** Builds "contents": last [MAX_TURNS] turns, starting with a user turn, same-role neighbours merged. */
    fun contents(history: List<Msg>): JSONArray {
        var msgs = history.filter { it.text.isNotBlank() }
        var users = 0; var start = 0
        for (i in msgs.indices.reversed()) {
            if (msgs[i].role == "user") { users++; start = i; if (users >= MAX_TURNS) break }
        }
        msgs = msgs.drop(start)
        val arr = JSONArray()
        var lastRole = ""; var parts = JSONArray()
        for (m in msgs) {
            val role = if (m.role == "model") "model" else "user"
            if (role != lastRole && parts.length() > 0) {
                arr.put(JSONObject().put("role", lastRole).put("parts", parts)); parts = JSONArray()
            }
            parts.put(JSONObject().put("text", m.text)); lastRole = role
        }
        if (parts.length() > 0) arr.put(JSONObject().put("role", lastRole).put("parts", parts))
        return arr
    }

    // ---------- failures ----------

    private enum class Kind { FATAL, QUOTA_DAY, QUOTA_SHORT, UNAVAILABLE, SERVER, BAD_THINKING, BAD_SEARCH }
    private class Fail(val kind: Kind, val msg: String, val retryMs: Long = 0L) : Exception(msg)
    private class MidStream(msg: String) : Exception(msg)
    private class Result(val sources: List<Pair<String, String>>)

    /**
     * Streams one reply, walking [attempts] in order. Quota / unavailable / overloaded errors before the first token
     * move on to the next attempt and are remembered in [Limits] so later messages skip that model straight away.
     */
    fun stream(
        context: Context, key: String, attempts: List<Router.Attempt>, system: String, history: List<Msg>,
        fast: Boolean, l: Listener
    ): Call {
        val ctx = context.applicationContext
        val call = Call()
        Thread {
            var lastMsg = "Gemini did not answer. Try again."
            var allQuota = true
            var migrated = false
            try {
                if (key.isBlank()) throw Fail(Kind.FATAL, "No API key yet. Add your Gemini API key in Settings.")
                val contents = contents(history)
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
                        val note = if (a.searchFallback) "search unavailable" else ""
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
                    }
                    if (f.kind != Kind.QUOTA_DAY && f.kind != Kind.QUOTA_SHORT) allQuota = false
                    lastMsg = f.msg
                    if (a.migrate && !migrated) {
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
        call: Call, key: String, a: Router.Attempt, system: String, contents: JSONArray, thinking: Boolean, l: Listener
    ): Result {
        val body = JSONObject()
            .put("contents", contents)
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
            c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = c.responseCode
            if (code !in 200..299) throw classify(code, readErr(c), a)
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
                        val f = classify(it.optInt("code", 500), data, a)
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

    private fun classify(code: Int, body: String, a: Router.Attempt): Fail {
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
