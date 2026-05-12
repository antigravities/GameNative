package app.gamenative.service

import android.os.FileObserver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class LeaderboardWatcher(
    private val appId: Int,
    private val watchDirs: List<File>,
    private val configDirectory: String?,
    // The player's own Steam ID — used to find their entry in GBE Fork's binary score files.
    private val currentSteamId: Long,
) {
    private val observers = mutableListOf<FileObserver>()
    // Keyed by lowercased filename, which is exactly the leaderboard name GBE Fork uses.
    private val lastKnownScores = mutableMapOf<String, Int>()
    private val pendingUploads = mutableSetOf<String>()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var uploadJob: Job? = null

    fun start() {
        // Snapshot existing scores first so we don't re-upload scores already on Steam.
        for (dir in watchDirs) {
            val lbDir = File(dir, "leaderboard")
            if (!lbDir.isDirectory) continue
            lbDir.listFiles()?.forEach { file ->
                val score = parseUserScore(file)
                if (score != null) lastKnownScores[file.name] = score
            }
        }
        Timber.tag("leaderboards").d("LeaderboardWatcher seeded ${lastKnownScores.size} pre-existing scores")

        for (dir in watchDirs) {
            // mkdirs ensures FileObserver can attach even before the game writes its first score.
            val lbDir = File(dir, "leaderboard")
            lbDir.mkdirs()
            val observer = object : FileObserver(lbDir, CLOSE_WRITE or MOVED_TO) {
                override fun onEvent(event: Int, path: String?) {
                    if (path != null) checkForNewScore(File(lbDir, path), path)
                }
            }
            observer.startWatching()
            observers.add(observer)
        }
        Timber.tag("leaderboards").d("LeaderboardWatcher started, watching ${watchDirs.size} dirs")
    }

    fun stop() {
        observers.forEach { it.stopWatching() }
        observers.clear()
        scope.cancel()
        Timber.tag("leaderboards").d("LeaderboardWatcher stopped")
    }

    private fun checkForNewScore(file: File, filename: String) {
        val score = parseUserScore(file) ?: return
        if (score == lastKnownScores[filename]) return
        lastKnownScores[filename] = score
        pendingUploads.add(filename)
        scheduleUpload()
        Timber.tag("leaderboards").i("New score detected: leaderboard=$filename score=$score")
    }

    /**
     * Scans a GBE Fork binary leaderboard file for the current player's score entry.
     *
     * Record layout (all values little-endian):
     *   [steamid_lo: uint32][steamid_hi: uint32][score: int32][details_count: uint32][...details: uint32]
     */
    private fun parseUserScore(file: File): Int? {
        if (!file.exists() || !file.isFile) return null
        return try {
            val buf = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
            while (buf.remaining() >= 16) {
                val idLo = buf.getInt().toLong() and 0xFFFFFFFFL
                val idHi = buf.getInt().toLong() and 0xFFFFFFFFL
                val steamId = idLo or (idHi shl 32)
                val score = buf.getInt()
                val detailCount = buf.getInt()
                // Advance past detail ints before moving to the next record.
                repeat(detailCount) { if (buf.remaining() >= 4) buf.getInt() }
                if (steamId == currentSteamId) return score
            }
            null
        } catch (e: Exception) {
            Timber.tag("leaderboards").w(e, "Failed to parse leaderboard file ${file.name}")
            null
        }
    }

    /** Debounces uploads 5 s after the last score change, same pattern as AchievementWatcher. */
    private fun scheduleUpload() {
        uploadJob?.cancel()
        uploadJob = scope.launch {
            delay(UPLOAD_DEBOUNCE_MS)
            uploadToSteam()
        }
    }

    private suspend fun uploadToSteam() {
        if (configDirectory == null) {
            Timber.tag("leaderboards").w("No configDirectory, skipping upload for appId=$appId")
            return
        }
        if (!SteamService.isConnected) {
            Timber.tag("leaderboards").w("Not connected to Steam, skipping upload for appId=$appId")
            return
        }

        // Resolve leaderboard name → numeric Steam ID from the JSON cache written by
        // LeaderboardsGenerator.generateDefinitions. GBE Fork filenames are lowercased,
        // so we lowercase the JSON keys when building this map.
        val nameToId = mutableMapOf<String, Int>()
        try {
            val cacheFile = File(configDirectory, "leaderboards.json")
            if (cacheFile.exists()) {
                val json = JSONObject(cacheFile.readText(Charsets.UTF_8))
                for (key in json.keys()) {
                    val id = json.optJSONObject(key)?.optInt("id", 0) ?: 0
                    if (id != 0) nameToId[key.lowercase()] = id
                }
            }
        } catch (e: Exception) {
            Timber.tag("leaderboards").w(e, "Failed to read leaderboards.json for appId=$appId")
        }

        val toUpload = pendingUploads.toSet()
        pendingUploads.clear()

        for (filename in toUpload) {
            val lbId = nameToId[filename]
            val score = lastKnownScores[filename]
            if (lbId == null || score == null) {
                Timber.tag("leaderboards").w("No leaderboard ID for '$filename', skipping upload")
                continue
            }
            SteamService.uploadLeaderboardScore(appId, lbId, score)
        }
    }

    companion object {
        private const val UPLOAD_DEBOUNCE_MS = 5_000L
    }
}
