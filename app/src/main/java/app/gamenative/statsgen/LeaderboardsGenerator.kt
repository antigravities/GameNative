package app.gamenative.statsgen

import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Writes GBE Fork leaderboard files from data already fetched by [SteamService].
 * All methods are pure file I/O — no network calls happen here.
 *
 * GBE Fork reads two separate files:
 *   - steam_settings/leaderboards.txt  — leaderboard definitions (NAME=sort_method=display_type)
 *   - {save_dir}/{appId}/leaderboard/{name_lowercase} — binary score records per leaderboard
 *
 * We also maintain a private leaderboards.json cache in steam_settings/ so that
 * SteamService can skip the web API on subsequent syncs (GBE Fork ignores this file).
 */
class LeaderboardsGenerator {

    /**
     * Merges [definitions] into [configDirectory]/leaderboards.json (our private ID cache)
     * and writes [configDirectory]/leaderboards.txt for GBE Fork to consume.
     *
     * The JSON merge is additive: entries whose names are NOT in [definitions] are kept unchanged
     * (they may have been added by GBE Fork during gameplay). Entries that ARE in [definitions]
     * are updated or added with fresh metadata and the numeric [id] we use to skip the web API.
     *
     * The TXT file is always rewritten from [definitions] only — we don't preserve stale entries.
     *
     * leaderboards.txt format (one line per leaderboard):
     *   NAME=sort_method=display_type
     */
    fun generateDefinitions(definitions: List<LeaderboardDefinition>, configDirectory: String) {
        val configDir = File(configDirectory)
        if (!configDir.exists()) configDir.mkdirs()

        // --- Private ID cache (leaderboards.json) — read back by SteamService to skip web API ---
        val defsFile = File(configDir, "leaderboards.json")
        val json = if (defsFile.exists()) {
            try { JSONObject(defsFile.readText(Charsets.UTF_8)) }
            catch (e: Exception) { JSONObject() }
        } else {
            JSONObject()
        }
        for (def in definitions) {
            val entry = JSONObject()
            entry.put("sort_method", def.sortMethod)
            entry.put("display_type", def.displayType)
            // "id" is our own caching field — GBE Fork ignores unknown keys.
            entry.put("id", def.id)
            json.put(def.name, entry)
        }
        defsFile.writeText(json.toString(2), Charsets.UTF_8)

        // --- GBE Fork config (leaderboards.txt) — parsed by settings_parser::parse_leaderboards ---
        // Only write entries from the incoming list; don't merge with existing file so stale
        // entries don't persist and confuse GBE Fork.
        if (definitions.isNotEmpty()) {
            val txtFile = File(configDir, "leaderboards.txt")
            val sb = StringBuilder()
            for (def in definitions) {
                sb.append("${def.name}=${def.sortMethod}=${def.displayType}\n")
            }
            txtFile.writeText(sb.toString(), Charsets.UTF_8)
        }
    }

    /**
     * Writes binary leaderboard score files into [saveDirectory]/leaderboard/{name_lowercase}.
     *
     * Only leaderboards present in [scores] are touched; any other binary files already written
     * by GBE Fork in the same directory are left intact.
     *
     * Binary record format per entry (all values little-endian uint32 / int32):
     *   [steamid_lo][steamid_hi][score][details_count][...details]
     *
     * This matches Steam_User_Stats::load_leaderboard_entries in GBE Fork. The subfolder name
     * is "leaderboard" (no trailing 's') to match Local_Storage::leaderboard_storage_folder.
     *
     * NOTE: GameNative configures GBE Fork with local_save_path pointing to the Steam userdata
     * directory (not GSE Saves), so the files GBE Fork actually reads will be in the second path
     * returned by getGseSaveDirs. Both paths receive these files since generateSaveFile is called
     * for each directory.
     */
    fun generateSaveFile(scores: Map<String, List<LeaderboardScoreEntry>>, saveDirectory: String) {
        if (scores.isEmpty()) return

        // GBE Fork stores scores at {save_dir}/{appId}/leaderboard/{name_lowercase}.
        // saveDirectory already includes {appId} (supplied by getGseSaveDirs).
        // "leaderboard" (no 's') matches Local_Storage::leaderboard_storage_folder.
        val leaderboardDir = File(saveDirectory, "leaderboard")
        if (!leaderboardDir.exists()) leaderboardDir.mkdirs()

        for ((lbName, entries) in scores) {
            if (entries.isEmpty()) continue

            // Allocate buffer: 4 fixed uint32 fields per entry + one per detail value.
            val intCount = entries.sumOf { 4 + it.details.size }
            val buf = ByteBuffer.allocate(intCount * Int.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)

            for (entry in entries) {
                // SteamID64 split into two little-endian uint32 halves.
                buf.putInt((entry.steamId and 0xFFFFFFFFL).toInt())
                buf.putInt(((entry.steamId ushr 32) and 0xFFFFFFFFL).toInt())
                buf.putInt(entry.score)
                buf.putInt(entry.details.size)
                for (d in entry.details) buf.putInt(d)
            }

            // Filename is the leaderboard name lowercased — GBE Fork uses to_lower() before lookup.
            File(leaderboardDir, lbName.lowercase()).writeBytes(buf.array())
        }
    }
}
