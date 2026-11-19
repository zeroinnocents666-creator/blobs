package com.navid.blobs

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class Bot(
    val id: String,
    var name: String,
    var role: String,
    var system: String,
    var shape: Int,
    var color: Int,
    val created: Long
) {
    /** The system instruction actually sent: custom text, or one built from the role. */
    fun prompt(): String = system.trim().ifEmpty { Store.defaultPrompt(name, role) }

    fun json(): JSONObject = JSONObject()
        .put("id", id).put("name", name).put("role", role).put("system", system)
        .put("shape", shape).put("color", color).put("created", created)

    companion object {
        fun from(o: JSONObject) = Bot(
            o.getString("id"), o.optString("name", "Bot"), o.optString("role", ""),
            o.optString("system", ""), o.optInt("shape", 0), o.optInt("color", C.SWATCHES[0]),
            o.optLong("created", 0L)
        )
    }
}

/** role is "user" or "model". For bot replies, [model] is the model that answered, [sources] are Google Search links
 *  (uri to title) and [note] is a small status shown under the reply (for example "search unavailable"). */
class Msg(val role: String, var text: String, val time: Long) {
    var model: String = ""
    var note: String = ""
    var sources: List<Pair<String, String>> = emptyList()

    fun json(): JSONObject {
        val o = JSONObject().put("r", role).put("t", text).put("at", time)
        if (model.isNotEmpty()) o.put("m", model)
        if (note.isNotEmpty()) o.put("n", note)
        if (sources.isNotEmpty()) {
            val a = JSONArray(); sources.forEach { a.put(JSONObject().put("u", it.first).put("t", it.second)) }
            o.put("src", a)
        }
        return o
    }
    companion object {
        fun from(o: JSONObject) = Msg(o.optString("r", "user"), o.optString("t", ""), o.optLong("at", 0L)).apply {
            model = o.optString("m", ""); note = o.optString("n", "")
            val a = o.optJSONArray("src")
            if (a != null) sources = (0 until a.length()).mapNotNull { i ->
                val x = a.optJSONObject(i) ?: return@mapNotNull null
                val u = x.optString("u"); if (u.isEmpty()) null else u to x.optString("t", u)
            }
        }
    }
}

/** Local storage: bots.json + chats/<botId>.json in the app's private files dir. */
object Store {
    private fun botsFile(ctx: Context) = File(ctx.filesDir, "bots.json")
    private fun chatFile(ctx: Context, id: String) = File(File(ctx.filesDir, "chats").apply { mkdirs() }, "$id.json")

    fun defaultPrompt(name: String, role: String): String {
        val r = role.trim().ifEmpty { "a helpful general assistant" }
        return "You are $name, $r. Stay in this role. Be friendly, clear and concise, and give practical, accurate answers. " +
            "Use Markdown (bold, lists, code blocks) when it helps readability. Do not use emoji. " +
            "Reply in the language the user writes in unless asked otherwise."
    }

    /** Added after every bot's instructions (v1.1): accuracy, brevity, language, and today's date. */
    fun accuracyRules(search: Boolean): String {
        val today = java.text.SimpleDateFormat("EEEE, d MMMM yyyy", java.util.Locale.ENGLISH).format(java.util.Date())
        return "\n\nGeneral rules (always follow): Answer correctly and concisely; lead with the direct answer. " +
            "If you are not sure, or the information may be out of date, say so plainly instead of guessing. " +
            "Never invent facts, numbers, names, quotes or links. " +
            "Reply in the same language and script the user writes in: if they write Hinglish (Hindi in Latin letters), reply in Hinglish; " +
            "Hindi in Devanagari gets Devanagari; English gets English. Today's date is $today." +
            (if (search) " Use Google Search for current or factual information and base the answer on what you find." else "")
    }

    private fun seed(): MutableList<Bot> {
        val now = System.currentTimeMillis()
        return mutableListOf(
            Bot(UUID.randomUUID().toString(), "Helper", "General assistant", "", Shapes.CIRCLE, C.SWATCHES[0], now),
            Bot(UUID.randomUUID().toString(), "Translator", "Hindi, English and Hinglish translator",
                "You are Translator, an expert translator between Hindi, English and Hinglish (Hindi written in Latin letters). " +
                    "When the user sends text, detect its language and translate it: Hindi or Hinglish to natural English, English to natural Hinglish " +
                    "unless the user asks for Hindi in Devanagari or another target. Keep tone, slang and meaning. Give the translation first; " +
                    "add a short note only for idioms or ambiguous words. If the user asks a question about language, answer it briefly. Do not use emoji.",
                Shapes.CLOUD, C.SWATCHES[6], now - 1),
            Bot(UUID.randomUUID().toString(), "Coder", "Android and Kotlin coding helper",
                "You are Coder, a senior Android engineer. Help with Kotlin, Android SDK, Jetpack, Gradle, ADB and sideloading. " +
                    "Give working, modern code in fenced code blocks with the language tag, explain briefly, point out pitfalls and API-level differences. " +
                    "Prefer concise answers. Do not use emoji.",
                Shapes.SQUIRCLE, C.SWATCHES[9], now - 2)
        )
    }

    fun bots(ctx: Context): MutableList<Bot> {
        val f = botsFile(ctx)
        if (!f.exists()) { val s = seed(); saveBots(ctx, s); return s }
        return try {
            val a = JSONArray(f.readText())
            MutableList(a.length()) { Bot.from(a.getJSONObject(it)) }
        } catch (e: Exception) { mutableListOf() }
    }

    fun bot(ctx: Context, id: String): Bot? = bots(ctx).firstOrNull { it.id == id }

    fun saveBots(ctx: Context, list: List<Bot>) {
        val a = JSONArray(); list.forEach { a.put(it.json()) }
        write(botsFile(ctx), a.toString())
    }

    fun upsert(ctx: Context, b: Bot) {
        val l = bots(ctx)
        val i = l.indexOfFirst { it.id == b.id }
        if (i >= 0) l[i] = b else l.add(0, b)
        saveBots(ctx, l)
    }

    fun delete(ctx: Context, id: String) {
        saveBots(ctx, bots(ctx).filter { it.id != id })
        chatFile(ctx, id).delete()
    }

    fun chat(ctx: Context, id: String): MutableList<Msg> {
        val f = chatFile(ctx, id)
        if (!f.exists()) return mutableListOf()
        return try {
            val a = JSONArray(f.readText())
            MutableList(a.length()) { Msg.from(a.getJSONObject(it)) }
        } catch (e: Exception) { mutableListOf() }
    }

    fun saveChat(ctx: Context, id: String, msgs: List<Msg>) {
        val a = JSONArray(); msgs.forEach { a.put(it.json()) }
        write(chatFile(ctx, id), a.toString())
    }

    fun clearChat(ctx: Context, id: String) { chatFile(ctx, id).delete() }

    fun clearAll(ctx: Context) { File(ctx.filesDir, "chats").listFiles()?.forEach { it.delete() } }

    private fun write(f: File, s: String) {
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(s)
        if (!tmp.renameTo(f)) { f.writeText(s); tmp.delete() }
    }
}

object Prefs {
    /** Model used in Manual mode when nothing is saved. */
    const val DEFAULT_MODEL = "gemini-3.8-flash"
    private fun p(ctx: Context) = ctx.getSharedPreferences("blobs", Context.MODE_PRIVATE)

    /** Manual model id (only used when [auto] is false). */
    fun model(ctx: Context): String = p(ctx).getString("model", null)?.trim()?.ifEmpty { null } ?: DEFAULT_MODEL
    fun setModel(ctx: Context, m: String) = p(ctx).edit().putString("model", m.trim()).apply()

    /** Auto mode (default). v1.0 users who were on the old default (gemini-2.5-flash) or never picked a model
     *  are moved to Auto; anyone who typed another model keeps Manual with that model. */
    fun auto(ctx: Context): Boolean {
        val sp = p(ctx)
        if (!sp.contains("mode")) {
            val old = sp.getString("model", null)?.trim().orEmpty()
            val auto = old.isEmpty() || old == "gemini-2.5-flash"
            sp.edit().putString("mode", if (auto) "auto" else "manual").apply()
            return auto
        }
        return sp.getString("mode", "auto") != "manual"
    }
    fun setAuto(ctx: Context, on: Boolean) = p(ctx).edit().putString("mode", if (on) "auto" else "manual").apply()

    /** "fast" (default: low thinking) or "smart" (model default thinking). */
    fun fast(ctx: Context): Boolean = p(ctx).getString("speed", "fast") != "smart"
    fun setFast(ctx: Context, on: Boolean) = p(ctx).edit().putString("speed", if (on) "fast" else "smart").apply()

    /** Google Search: global default, and per-chat override. */
    fun searchDefault(ctx: Context): Boolean = p(ctx).getBoolean("search_default", false)
    fun setSearchDefault(ctx: Context, on: Boolean) = p(ctx).edit().putBoolean("search_default", on).apply()
    fun search(ctx: Context, botId: String): Boolean =
        p(ctx).let { if (it.contains("search_$botId")) it.getBoolean("search_$botId", false) else searchDefault(ctx) }
    fun setSearch(ctx: Context, botId: String, on: Boolean) = p(ctx).edit().putBoolean("search_$botId", on).apply()

    fun accent(ctx: Context): Int = p(ctx).getInt("accent", C.SWATCHES[0])
    fun setAccent(ctx: Context, c: Int) = p(ctx).edit().putInt("accent", c).apply()

    fun onboarded(ctx: Context) = p(ctx).getBoolean("onboarded", false)
    fun setOnboarded(ctx: Context) = p(ctx).edit().putBoolean("onboarded", true).apply()

    fun apiKey(ctx: Context): String {
        val enc = p(ctx).getString("key_enc", null) ?: return p(ctx).getString("key_plain", "") ?: ""
        return SecureKey.decrypt(enc) ?: ""
    }

    fun setApiKey(ctx: Context, key: String) {
        val k = key.trim()
        if (k != apiKey(ctx)) Limits.clear(ctx) // limits belong to the old key's project
        val e = p(ctx).edit().remove("key_enc").remove("key_plain")
        if (k.isNotEmpty()) {
            val enc = SecureKey.encrypt(k)
            if (enc != null) e.putString("key_enc", enc) else e.putString("key_plain", k)
        }
        e.apply()
    }

    fun masked(key: String): String =
        if (key.length <= 8) "Not set" else key.take(4) + "\u2022".repeat(10) + key.takeLast(4)
}

/** AES-GCM with a key kept in the Android Keystore (never leaves secure hardware). */
object SecureKey {
    private const val ALIAS = "blobs_api_key"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        g.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return g.generateKey()
    }

    fun encrypt(plain: String): String? = try {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        val out = c.doFinal(plain.toByteArray(Charsets.UTF_8))
        Base64.encodeToString(c.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(out, Base64.NO_WRAP)
    } catch (e: Exception) { null }

    fun decrypt(s: String): String? = try {
        val (iv, data) = s.split(":", limit = 2)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
        String(c.doFinal(Base64.decode(data, Base64.NO_WRAP)), Charsets.UTF_8)
    } catch (e: Exception) { null }
}
