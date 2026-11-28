package world.yemyat.slicepilot.repo

import org.json.JSONArray

/**
 * Pure helper for model-browser search history: newest-first, case-insensitively deduplicated,
 * capped at [MAX_ENTRIES] (10), and serialized as a JSON string array in SharedPreferences.
 */
object SearchHistory {
    const val MAX_ENTRIES = 10
    const val PREFS_KEY = "model_search_history"

    /**
     * Records [rawQuery] at the front of [history], moving any existing case-insensitive match to
     * the top and keeping at most [limit] entries. Blank queries leave [history] unchanged.
     */
    fun record(
        history: List<String>,
        rawQuery: String,
        limit: Int = MAX_ENTRIES,
    ): List<String> {
        val q = rawQuery.trim()
        if (q.isEmpty() || limit <= 0) return history.take(limit.coerceAtLeast(0))
        return buildList(minOf(history.size + 1, limit)) {
            add(q)
            for (item in history) {
                if (size >= limit) break
                val trimmed = item.trim()
                if (trimmed.isNotEmpty() && !trimmed.equals(q, ignoreCase = true)) {
                    add(trimmed)
                }
            }
        }
    }

    /** Removes [query] (case-insensitively) from [history]. */
    fun remove(
        history: List<String>,
        query: String,
    ): List<String> {
        val target = query.trim()
        if (target.isEmpty()) return history
        return history.filterNot { it.equals(target, ignoreCase = true) }
    }

    /**
     * Filters [history] for the current search-bar text. An empty query returns the full history;
     * a non-empty query shows prefix matches first, followed by substring matches.
     */
    fun suggestions(
        history: List<String>,
        rawQuery: String,
    ): List<String> {
        val q = rawQuery.trim()
        if (q.isEmpty()) return history
        val prefixMatches = history.filter { it.startsWith(q, ignoreCase = true) }
        val otherMatches =
            history.filter {
                !it.startsWith(q, ignoreCase = true) && it.contains(q, ignoreCase = true)
            }
        return prefixMatches + otherMatches
    }

    fun encode(history: List<String>): String =
        JSONArray()
            .apply {
                history.forEach { put(it) }
            }.toString()

    fun decode(
        raw: String?,
        limit: Int = MAX_ENTRIES,
    ): List<String> {
        if (raw.isNullOrBlank() || limit <= 0) return emptyList()
        return try {
            val arr = JSONArray(raw)
            val result = ArrayList<String>(minOf(arr.length(), limit))
            for (i in 0 until arr.length()) {
                if (result.size >= limit) break
                val item = arr.optString(i, "").trim()
                if (item.isNotEmpty() && result.none { it.equals(item, ignoreCase = true) }) {
                    result.add(item)
                }
            }
            result
        } catch (_: Exception) {
            emptyList()
        }
    }
}
