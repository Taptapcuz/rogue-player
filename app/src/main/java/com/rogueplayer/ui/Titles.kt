package com.rogueplayer.ui

import java.util.Locale

/**
 * Provider VOD names are dirty: `[EN] US| The Matrix (1999) 4K`, `EN - Alien`,
 * `FR| Amelie`. Strip the noise for display, and build a sort key that ignores
 * the leading region/bracket tags and articles so A–Z actually means A–Z.
 */
object Titles {

    /** A leading bracketed tag: "[EN]", "(4K)", "{Multi}". */
    private val leadTag = Regex("^\\s*[\\[(][^\\])]{1,28}[\\])]\\s*")

    /** A leading region/source marker: "US| ", "EN - ", "FR: ", "4K| ". */
    private val leadRegion = Regex("^\\s*[A-Za-z0-9]{2,4}\\s*[-|•:]\\s*")

    private val article = Regex("^(the|a|an)\\s+", RegexOption.IGNORE_CASE)
    private val trailingYear = Regex("\\s*[\\[(]\\d{4}[\\])]\\s*$")
    private val anyYear = Regex("\\b(19[0-9]{2}|20[0-9]{2})\\b")

    /** Display title: tags/regions/trailing year removed. */
    fun clean(name: String): String {
        var s = name.trim()
        repeat(3) {
            val next = s.replace(leadTag, "").replace(leadRegion, "")
            if (next == s) return@repeat
            s = next
        }
        s = s.replace(trailingYear, "").trim().trimEnd('-', '|', ':').trim()
        return s.ifBlank { name.trim() }
    }

    /** Case-insensitive key for A–Z ordering, with articles pushed aside. */
    private val keyCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    fun sortKey(name: String): String {
        keyCache[name]?.let { return it }
        val key = clean(name).replaceFirst(article, "").trim().lowercase(Locale.US)
        // Providers can ship tens of thousands of titles; bound the memo.
        if (keyCache.size > 80_000) keyCache.clear()
        keyCache[name] = key
        return key
    }

    /** Release year found in the raw name, if any ("1999"). */
    fun year(name: String): String? = anyYear.findAll(name).lastOrNull()?.value

    /** 12345 -> "12,345" */
    fun count(n: Int): String = String.format(Locale.US, "%,d", n)
}
