package app.gamenative.data

import kotlinx.serialization.Serializable

/** A single file to download as part of a patch (type is "executable" or "zip"). */
@Serializable
data class PatchDownloadItem(
    val type: String,
    val url: String,
    /** Explicit filename to save as in the staging directory. Falls back to the last URL path segment when null. */
    val saveTo: String? = null,
)

/**
 * A single install task within a patch.
 *
 * type = "execute" — run the file as a Wine guest program.
 * type = "unzip"   — extract the zip on the Android side.
 *
 * startIn, if set, overrides the default game data directory as the working directory /
 * extraction root. Expected format is a Windows path (e.g. C:\).
 *
 * New task types can be added here and handled in chainPatchTasks() in XServerScreen.
 */
@Serializable
data class PatchInstallTask(
    val type: String,
    val file: String = "",        // used by "execute" and "unzip"; omitted for "executeCmd"
    val startIn: String? = null,
    val cmd: String? = null,      // used by "executeCmd": raw command passed to cmd /c
)

/** A named, selectable patch offered by the patch database for a given game. */
@Serializable
data class PatchEntry(
    val name: String,
    val description: String,
    val download: List<PatchDownloadItem>,
    val install: List<PatchInstallTask>,
)

/**
 * Written to {container.rootDir}/pending_patches.json after the user confirms their
 * selection in the patch dialog. XServerScreen reads this file, executes the tasks in
 * order after prerequisites, then deletes the file.
 */
@Serializable
data class PendingPatchWork(
    /** Windows path to the game's root data directory, e.g. C:\...\common\GameName */
    val gameDataDir: String,
    /** Ordered list of install tasks to execute. */
    val tasks: List<PatchInstallTask>,
    /** Filenames (not full paths) that were staged in the patch staging directory. */
    val stagingFiles: List<String>,
    /** Download URLs for staged files; used to re-download if a prior launch failed mid-patch. */
    val stagingDownloads: List<String> = emptyList(),
)
