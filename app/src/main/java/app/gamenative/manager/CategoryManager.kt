package app.gamenative.manager

import android.content.Context
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages named game categories stored as .bin files on disk.
 *
 * Each category is a UTF-8 newline-delimited list of composite app IDs
 * (e.g. "STEAM_570", "GOG_1234") stored at <filesDir>/categories/<name>.bin.
 *
 * The in-memory cache makes reads O(1). Writes are atomic: we write to a
 * .tmp file first, then rename over the target so a crash never corrupts the
 * file. Initialize once in PluviaApp.onCreate() via CategoryManager.init(context).
 */
object CategoryManager {

    /** Magic category name: games in this category are hidden from the library by default. */
    const val HIDDEN_CATEGORY = "Hidden"

    /** Magic category name: games in this category are sorted first and highlighted in amber. */
    const val FAVORITES_CATEGORY = "Favorites"

    private lateinit var categoriesDir: File

    // Maps category name → thread-safe set of composite app IDs
    private val cache = ConcurrentHashMap<String, MutableSet<String>>()

    fun init(context: Context) {
        categoriesDir = File(context.filesDir, "categories")
        categoriesDir.mkdirs()
        loadAll()
    }

    /** Returns all known category names in alphabetical order. */
    fun getCategoryNames(): List<String> = cache.keys.sorted()

    /** Returns the set of app IDs in the given category (empty set if unknown). */
    fun getAppsInCategory(name: String): Set<String> = cache[name] ?: emptySet()

    /**
     * Adds [appId] to [name], creating the category file if it doesn't exist yet.
     * Persists the change to disk atomically.
     */
    fun addAppToCategory(appId: String, name: String) {
        // getOrPut is not atomic on ConcurrentHashMap for the inner set creation,
        // but the worst case is a harmless redundant set — safe under our usage pattern.
        cache.getOrPut(name) { ConcurrentHashMap.newKeySet() }.add(appId)
        persist(name)
    }

    /** Removes [appId] from [name] and persists the change. */
    fun removeAppFromCategory(appId: String, name: String) {
        cache[name]?.remove(appId)
        persist(name)
    }

    fun isAppInCategory(appId: String, name: String): Boolean =
        cache[name]?.contains(appId) == true

    // ── Private helpers ─────────────────────────────────────────────────────

    /** Reads all existing .bin files into the in-memory cache on first init. */
    private fun loadAll() {
        cache.clear()
        categoriesDir.listFiles { f -> f.extension == "bin" }?.forEach { file ->
            val name = file.nameWithoutExtension
            val ids = file.readLines().filter { it.isNotBlank() }
            val set = ConcurrentHashMap.newKeySet<String>()
            set.addAll(ids)
            cache[name] = set
        }
    }

    /**
     * Writes the current state of category [name] to disk atomically.
     * Synchronized on the interned category name so concurrent calls for the
     * same category are serialized without locking unrelated categories.
     */
    private fun persist(name: String) {
        val ids = cache[name] ?: return
        val target = File(categoriesDir, "$name.bin")
        val tmp = File(categoriesDir, "$name.tmp")
        // String.intern() gives us a per-category lock without a separate lock map
        synchronized(name.intern()) {
            tmp.writeText(ids.joinToString("\n"))
            tmp.renameTo(target)
        }
    }
}
