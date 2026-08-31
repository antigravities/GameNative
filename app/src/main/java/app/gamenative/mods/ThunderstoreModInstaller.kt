package app.gamenative.mods

import android.content.Context
import android.net.Uri
import app.gamenative.utils.ContainerUtils
import com.winlator.core.envvars.EnvVars
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID

sealed class ThunderstoreInstallResult {
    // unityNoBepInExWarning: the game looks like a Unity title without BepInEx installed,
    // and this particular archive did not install a BepInExPack itself.
    data class Success(val unityNoBepInExWarning: Boolean) : ThunderstoreInstallResult()
    data class Error(val message: String) : ThunderstoreInstallResult()
}

/**
 * Installs a Thunderstore/r2modman-formatted mod ZIP into a game's install directory,
 * following the BepInEx package-layout conventions those tools produce. Unlike the
 * Nexus Mods import pipeline, this is a one-shot filesystem copy with no DB/profile tracking.
 */
object ThunderstoreModInstaller {
    private const val TAG = "ThunderstoreModInstaller"

    // BepInEx's doorstop hook loads through winhttp.dll; native-then-builtin ensures the
    // dropped-in override is preferred over Wine's built-in stub.
    private const val WINHTTP_OVERRIDE = "winhttp=n,b"

    suspend fun install(
        context: Context,
        appId: String,
        gameRootDir: File,
        zipUri: Uri,
        zipDisplayName: String,
    ): ThunderstoreInstallResult = withContext(Dispatchers.IO) {
        val importDir = File(context.cacheDir, "thunderstore_import")
        val tempZip = File(importDir, "${UUID.randomUUID()}.zip")
        val tempExtractDir = File(importDir, "${UUID.randomUUID()}_extracted")
        try {
            importDir.mkdirs()
            copyUriToFile(context, zipUri, tempZip)

            val zipBaseName = sanitizeBaseName(zipDisplayName)
            val extraction = ModArchiveExtractor.extract(tempZip, tempExtractDir)
            val extractedRoot = extraction.destination

            val hadBepInExBefore = File(gameRootDir, "BepInEx").isDirectory
            val isUnity = isUnityGame(gameRootDir)

            // Rule (c): a "config" or "patchers" folder anywhere in the archive (root or
            // nested) routes straight to BepInEx/config or BepInEx/patchers, and is excluded
            // from wherever its parent would otherwise land.
            val configPatchersMatches = mutableListOf<Pair<File, String>>()
            findConfigPatchersDirs(extractedRoot, configPatchersMatches)
            val excludedPaths = configPatchersMatches.map { it.first.canonicalPath }.toSet()

            for ((dir, kind) in configPatchersMatches) {
                val target = File(gameRootDir, "BepInEx/$kind")
                target.mkdirs()
                copyTreeMerging(dir, target, excludedPaths)
            }

            var installedBepInExPack = false
            val topLevel = extractedRoot.listFiles()?.toList().orEmpty()
            for (entry in topLevel) {
                if (entry.canonicalPath in excludedPaths) continue

                when {
                    // (a) BepInExPack* root -> its contents merge directly into the game root.
                    entry.isDirectory && entry.name.startsWith("BepInExPack", ignoreCase = true) -> {
                        copyTreeMerging(entry, gameRootDir, excludedPaths)
                        installedBepInExPack = true
                    }
                    // (b) a root-level .dll -> BepInEx/plugins/<zipBaseName>/
                    entry.isFile && entry.extension.equals("dll", ignoreCase = true) -> {
                        copyFile(entry, File(gameRootDir, "BepInEx/plugins/$zipBaseName/${entry.name}"))
                    }
                    // (d) any other unrecognized subdirectory -> BepInEx/plugins/<zipBaseName>/<dirName>/
                    entry.isDirectory -> {
                        val target = File(gameRootDir, "BepInEx/plugins/$zipBaseName/${entry.name}")
                        target.mkdirs()
                        copyTreeMerging(entry, target, excludedPaths)
                    }
                    // Unrecognized root-level files (manifest.json, README.md, icon.png, ...) are skipped.
                    else -> Unit
                }
            }

            if (installedBepInExPack) {
                applyWinHttpOverride(context, appId)
            }

            ThunderstoreInstallResult.Success(
                unityNoBepInExWarning = isUnity && !hadBepInExBefore && !installedBepInExPack,
            )
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Thunderstore mod install failed")
            ThunderstoreInstallResult.Error(e.message ?: "Unknown error")
        } finally {
            tempZip.delete()
            tempExtractDir.deleteRecursively()
        }
    }

    private fun applyWinHttpOverride(context: Context, appId: String) {
        val container = ContainerUtils.getContainer(context, appId)
        val envVars = EnvVars(container.envVars)
        val current = envVars.get("WINEDLLOVERRIDES")
        val updated = ensureWinHttpOverride(current)
        if (updated != current) {
            envVars.put("WINEDLLOVERRIDES", updated)
            container.envVars = envVars.toString()
            container.saveData()
        }
    }

    private fun ensureWinHttpOverride(existing: String): String {
        if (existing.isBlank()) return WINHTTP_OVERRIDE
        val parts = existing.split(';').map { it.trim() }.filter { it.isNotBlank() }
        if (parts.any { it.equals(WINHTTP_OVERRIDE, ignoreCase = true) }) return existing
        val withoutExistingWinHttp = parts.filterNot { part ->
            part.substringBefore('=').split(',').any { it.trim().equals("winhttp", ignoreCase = true) }
        }
        return (withoutExistingWinHttp + WINHTTP_OVERRIDE).joinToString(";")
    }

    private fun isUnityGame(gameRootDir: File): Boolean {
        val hasDataDir = gameRootDir.listFiles()
            ?.any { it.isDirectory && it.name.endsWith("_Data", ignoreCase = true) } == true
        val hasUnityMarkerFile = File(gameRootDir, "UnityPlayer.dll").isFile ||
            File(gameRootDir, "UnityCrashHandler64.exe").isFile
        return hasDataDir || hasUnityMarkerFile
    }

    // Finds directories literally named "config" or "patchers" anywhere under root, without
    // descending into a match (so its contents aren't also visited by the generic copy rules).
    private fun findConfigPatchersDirs(root: File, acc: MutableList<Pair<File, String>>) {
        val children = root.listFiles() ?: return
        for (child in children) {
            if (!child.isDirectory) continue
            val nameLower = child.name.lowercase()
            if (nameLower == "config" || nameLower == "patchers") {
                acc += child to nameLower
            } else {
                findConfigPatchersDirs(child, acc)
            }
        }
    }

    // Mirrors src's children into dst, skipping any path present in `excluded` (already routed elsewhere).
    private fun copyTreeMerging(src: File, dst: File, excluded: Set<String>) {
        val children = src.listFiles() ?: return
        for (child in children) {
            if (child.canonicalPath in excluded) continue
            val target = File(dst, child.name)
            if (child.isDirectory) {
                target.mkdirs()
                copyTreeMerging(child, target, excluded)
            } else {
                copyFile(child, target)
            }
        }
    }

    private fun copyFile(src: File, dst: File) {
        dst.parentFile?.mkdirs()
        src.inputStream().use { input ->
            FileOutputStream(dst).use { output -> input.copyTo(output) }
        }
    }

    private fun copyUriToFile(context: Context, uri: Uri, dst: File) {
        val input = context.contentResolver.openInputStream(uri)
            ?: throw IOException("Unable to open selected file")
        input.use { inp ->
            FileOutputStream(dst).use { out -> inp.copyTo(out) }
        }
    }

    private fun sanitizeBaseName(displayName: String): String {
        val withoutExtension = displayName.substringBeforeLast('.', displayName)
        val sanitized = withoutExtension.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim()
        return sanitized.ifBlank { "mod" }
    }
}
