package com.navid.blobs

import android.content.Context
import org.json.JSONObject
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Model catalogue (IDs checked against ai.google.dev/gemini-api/docs/models and /pricing, Nov 2026).
 * All of these are free-tier text models. Free Google Search grounding is only offered on the 2.5 Flash family.
 */
object Models {
    class Info(val id: String, val label: String, val note: String)

    val ALL = listOf(
        Info("gemini-4-hydrogen", "Hydrogen", "Gemini 4 Hydrogen. Best free model, small daily quota"),
        Info("gemini-3.8-flash", "3.8 Flash", "Gemini 3.8 Flash. Strong and fast"),
        Info("gemini-3.6-flash", "3.6 Flash", "Gemini 3.6 Flash. Balanced"),
        Info("gemini-3.5-flash-lite", "3.5 Flash-Lite", "Gemini 3.5 Flash-Lite. Fastest, high quota"),
        Info("gemini-3.1-flash-lite", "3.1 Flash-Lite", "Gemini 3.1 Flash-Lite. Fast, high quota"),
        Info("gemini-2.5-flash", "2.5 Flash", "Gemini 2.5 Flash. Existing keys only, deprecated"),
        Info("gemini-2.5-flash-lite", "2.5 Flash-Lite", "Gemini 2.5 Flash-Lite. Existing keys only")
    )

    /** Auto: best first, then fall down the list on quota / unavailable errors. */
    val AUTO_CHAIN = listOf(
        "gemini-4-hydrogen", "gemini-3.8-flash", "gemini-3.6-flash", "gemini-3.5-flash-lite",
        "gemini-3.1-flash-lite", "gemini-2.5-flash", "gemini-2.5-flash-lite"
    )

    /** Models with free Grounding with Google Search (pooled 500 requests/day on the free tier). */
    val SEARCH_CHAIN = listOf("gemini-2.5-flash", "gemini-2.5-flash-lite")

    /** Manual models that are migrated to Auto when they fail (v1.0 default and other deprecated 2.5 models). */
    val MIGRATE_ON_FAIL = setOf("gemini-2.5-flash", "gemini-2.5-pro")

    fun label(id: String): String = ALL.firstOrNull { it.id == id }?.label ?: id.removePrefix("models/")

    /**
     * generationConfig.thinkingConfig for the fast path, or null to leave the model default.
     * Gemini 3+ / 4 take thinkingLevel (2.5 rejects it); 2.5 takes thinkingBudget.
     */
    fun thinking(id: String, fast: Boolean): JSONObject? {
        if (!fast) return null
        val m = id.removePrefix("models/")
        return when {
            m.startsWith("gemini-4") -> JSONObject().put("thinkingLevel", "low")          // minimal not supported on 4
            m.startsWith("gemini-3.8") -> JSONObject().put("thinkingLevel", "low")        // minimal not supported on 3.8
            m.contains("flash-lite") && m.startsWith("gemini-3") -> null                  // default is already minimal
            m.startsWith("gemini-3") -> JSONObject().put("thinkingLevel", "low")
            m == "gemini-2.5-flash" -> JSONObject().put("thinkingBudget", 0)               // thinking off: fastest first token
            else -> null                                                                  // 2.5 Flash-Lite: off by default
        }
    }
}

/** Remembers which models are out of quota / unavailable so they are skipped until they reset. */
object Limits {
    private val PT: ZoneId = ZoneId.of("America/Los_Angeles")
    private fun p(ctx: Context) = ctx.getSharedPreferences("limits", Context.MODE_PRIVATE)

    fun key(model: String, search: Boolean) = if (search) "$model|search" else model

    /** Free-tier daily quotas reset at midnight Pacific time. */
    fun nextPacificMidnight(): Long =
        ZonedDateTime.now(PT).toLocalDate().plusDays(1).atStartOfDay(PT).toInstant().toEpochMilli()

    fun mark(ctx: Context, model: String, search: Boolean, until: Long, why: String) {
        p(ctx).edit().putString(key(model, search), "$until|$why").apply()
    }

    fun blocked(ctx: Context, model: String, search: Boolean): Boolean {
        val v = p(ctx).getString(key(model, search), null) ?: return false
        val until = v.substringBefore('|').toLongOrNull() ?: 0L
        if (until <= System.currentTimeMillis()) { p(ctx).edit().remove(key(model, search)).apply(); return false }
        return true
    }

    /** Active blocks: key -> (until, reason). */
    fun active(ctx: Context): List<Triple<String, Long, String>> {
        val now = System.currentTimeMillis()
        return p(ctx).all.mapNotNull { (k, v) ->
            val s = v as? String ?: return@mapNotNull null
            val until = s.substringBefore('|').toLongOrNull() ?: 0L
            if (until <= now) null else Triple(k, until, s.substringAfter('|', ""))
        }.sortedBy { it.second }
    }

    fun clear(ctx: Context) { p(ctx).edit().clear().apply() }
}

/** Builds the ordered list of attempts for one reply. */
object Router {
    class Attempt(
        val model: String,
        val search: Boolean,
        /** Set when the user asked for search but this attempt runs without it. */
        val searchFallback: Boolean = false,
        /** A failure of this attempt moves the settings to Auto (v1.0 migration). */
        val migrate: Boolean = false
    )

    fun plan(ctx: Context, search: Boolean): List<Attempt> {
        val auto = Prefs.auto(ctx)
        val manual = Prefs.model(ctx)
        val out = ArrayList<Attempt>()
        fun usable(m: String, s: Boolean) = !Limits.blocked(ctx, m, s)

        val base: List<String> = if (auto) Models.AUTO_CHAIN.filter { usable(it, false) }.ifEmpty { Models.AUTO_CHAIN }
                                 else listOf(manual)
        if (search) {
            // Free grounding only works on the 2.5 Flash family; a manual 2.5 model is tried first.
            val chain = (if (!auto && manual.startsWith("gemini-2.5")) listOf(manual) else emptyList()) + Models.SEARCH_CHAIN
            chain.distinct().filter { usable(it, true) }.forEach { out.add(Attempt(it, true)) }
            base.forEach { out.add(Attempt(it, false, searchFallback = true, migrate = !auto && it in Models.MIGRATE_ON_FAIL)) }
        } else {
            base.forEach { out.add(Attempt(it, false, migrate = !auto && it in Models.MIGRATE_ON_FAIL)) }
        }
        // Manual model in the v1.0 migration set: if it fails, continue down the Auto chain.
        if (!auto && manual in Models.MIGRATE_ON_FAIL) {
            Models.AUTO_CHAIN.filter { it != manual && usable(it, false) }
                .forEach { out.add(Attempt(it, false, searchFallback = search)) }
        }
        return out
    }
}
