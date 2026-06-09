package app.gamenative.utils

import android.icu.text.Transliterator

/**
 * Single source of truth for the library's locale-invariant sort key.
 *
 * [of] converts any-script game names to lowercase Latin so titles sort the way Steam
 * shows them regardless of script: Cyrillic "Атом" sorts next to Latin "Atom", and
 * "Café"/"Cafe" sort together. It first trims surrounding whitespace and strips leading
 * punctuation/symbols so "!AnyWay!" sorts at A and "\"Glow Ball\"" sorts at G. The ICU rule
 * chain: transliterate script → Latin, NFD-decompose, drop combining marks (diacritics),
 * NFC-recompose, lowercase.
 *
 * This key is persisted into `steam_app.name_sort_key` at PICS-write time so the library
 * pagination query can `ORDER BY` it in SQL. The in-memory path for non-Steam games (GOG /
 * Epic / Amazon / custom) must order by the SAME function, because the paginated library
 * merges SQL-ordered Steam rows with in-memory-ordered non-Steam rows — the comparison only
 * holds if both sides produce identical keys. Never fork this logic; always call [of].
 *
 * android.icu Transliterator instances are NOT safe for concurrent transliterate() calls,
 * and the PICS pipeline writes rows from several worker threads at once, so each thread gets
 * its own instance via [ThreadLocal]. (android.icu requires API 24; minSdk is 26.)
 */
object NameSortKey {
    private val transliterator = ThreadLocal.withInitial {
        Transliterator.getInstance("Any-Latin; NFD; [:Nonspacing Mark:] Remove; NFC; Lower")
    }

    fun of(name: String): String =
        transliterator.get()!!.transliterate(
            name.trim().trimStart { !it.isLetterOrDigit() },
        )
}
