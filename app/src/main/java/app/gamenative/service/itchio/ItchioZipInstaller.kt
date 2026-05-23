package app.gamenative.service.itchio

import timber.log.Timber
import java.io.File
import java.util.zip.ZipFile

/**
 * Handles itch.io ZIP archive installs.
 *
 * Extracts the ZIP to the game directory on the Android side (no Wine needed),
 * deletes the ZIP after extraction, then selects the best launch executable using
 * itch.io-specific priority rules:
 *
 *  1. If there is exactly one .exe at the top level, use it.
 *  2. If there are multiple .exe files at the top level, prefer "game.exe",
 *     then "launch.exe", then the first alphabetically.
 *  3. If there are no .exe files at the top level, scan each subdirectory
 *     in alphabetical order and apply rules 1 and 2 to each.
 */
object ItchioZipInstaller {

    private const val TAG = "ItchioZipInstaller"

    /**
     * Extracts [zipFile] into [destDir], deletes the ZIP, then finds the best
     * launch executable per the priority rules above.
     *
     * @return relative path of the chosen exe (forward slashes, e.g. "game.exe"
     *         or "subdir/game.exe"), or null if no .exe was found after extraction.
     */
    fun extractAndFindExe(zipFile: File, destDir: File): String? {
        Timber.tag(TAG).i("Extracting ZIP ${zipFile.name} into ${destDir.path}")
        extractZip(zipFile, destDir)
        zipFile.delete()
        Timber.tag(TAG).d("Deleted ZIP file ${zipFile.name}")
        return findBestExe(destDir)
    }

    // ---

    private fun extractZip(zipFile: File, destDir: File) {
        val destPath = destDir.toPath().normalize()
        var extractedCount = 0

        // ZipFile reads the central directory at the end of the file, which is the
        // authoritative index and handles ZIP64 correctly. ZipInputStream reads local
        // file headers forward from byte 0; many itch.io packers produce non-standard
        // local headers that cause ZipInputStream.nextEntry() to return null immediately.
        ZipFile(zipFile).use { zf ->
            Timber.tag(TAG).d("ZIP has ${zf.size()} entries")
            val entries = zf.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (entry.isDirectory) continue

                // Strip leading slashes: some packers write entries like "/Celeste.exe".
                // Java's File(parent, child) ignores parent when child is absolute.
                val safeName = entry.name.trimStart('/', '\\')
                if (safeName.isEmpty()) continue

                val outFile = File(destDir, safeName)
                // Zip-slip guard: reject entries with "../" that escape destDir.
                val outPath = outFile.toPath().normalize()
                if (!outPath.startsWith(destPath)) {
                    Timber.tag(TAG).w("Skipping suspicious ZIP entry: ${entry.name}")
                    continue
                }
                outFile.parentFile?.mkdirs()
                zf.getInputStream(entry).use { input ->
                    outFile.outputStream().use { input.copyTo(it) }
                }
                extractedCount++
            }
        }

        if (extractedCount == 0) {
            Timber.tag(TAG).w("ZIP extraction wrote 0 files — archive may be empty or all entries were rejected")
        } else {
            Timber.tag(TAG).d("Extracted $extractedCount file(s) from ZIP")
        }
    }

    private fun findBestExe(dir: File): String? {
        // Step 1 & 2: check top-level exes
        val topExes = dir.listFiles { f ->
            f.isFile && f.name.endsWith(".exe", ignoreCase = true)
        }?.map { it.name }?.sortedBy { it.lowercase() } ?: emptyList()

        val topResult = selectExe(topExes)
        if (topResult != null) return topResult

        // Step 3: scan subdirectories alphabetically, applying the same rules
        val subDirs = dir.listFiles { f -> f.isDirectory }
            ?.sortedBy { it.name.lowercase() }
            ?: return null

        for (subDir in subDirs) {
            val subExes = subDir.listFiles { f ->
                f.isFile && f.name.endsWith(".exe", ignoreCase = true)
            }?.map { it.name }?.sortedBy { it.lowercase() } ?: continue

            val result = selectExe(subExes) ?: continue
            return "${subDir.name}/$result"
        }

        return null
    }

    /**
     * Picks one exe from a non-empty list.
     * Single entry → that entry.
     * Multiple → "game.exe" wins, then "launch.exe", then first alphabetically.
     */
    private fun selectExe(exes: List<String>): String? {
        if (exes.isEmpty()) return null
        if (exes.size == 1) return exes.first()
        return exes.firstOrNull { it.equals("game.exe", ignoreCase = true) }
            ?: exes.firstOrNull { it.equals("launch.exe", ignoreCase = true) }
            ?: exes.first()
    }
}
