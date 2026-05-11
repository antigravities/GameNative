package app.gamenative.service

import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import app.gamenative.utils.StorageUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File

object DownloadService {
    @Volatile private var lastUpdateTime: Long = 0
    @Volatile private var downloadDirectoryApps: MutableList<String>? = null
    var baseDataDirPath: String = ""
        private set(value) {
            field = value
        }
    var baseCacheDirPath: String = ""
        private set(value) {
            field = value
        }
    // Base path to the app-specific external storage directory (Android/data/<package>)
    var baseExternalAppDirPath: String = ""
        private set(value) {
            field = value
        }

    // all mounted non-primary external volumes (SD cards, USB), discovered at init
    var externalVolumePaths: List<String> = emptyList()
        private set

    fun populateDownloadService(context: Context) {
        baseDataDirPath = context.dataDir.path
        baseCacheDirPath = context.cacheDir.path
        // Prefer the parent of external files dir (Android/data/<package>) so we can create siblings of /files
        val extFiles = context.getExternalFilesDir(null)
        baseExternalAppDirPath = extFiles?.parentFile?.path ?: ""

        val sm = context.getSystemService(StorageManager::class.java)
        val fromExternalDirs = context.getExternalFilesDirs(null)
            .filterNotNull()
            .filter { Environment.getExternalStorageState(it) == Environment.MEDIA_MOUNTED }
            .filter { sm.getStorageVolume(it)?.isPrimary != true }
            .map { it.absolutePath }

        // getExternalFilesDirs() only returns volumes where Android has pre-created the
        // app-scoped directory structure; USB OTG drives on API 29+ are mounted but excluded.
        externalVolumePaths = (fromExternalDirs + otgVolumePaths(context, sm)).distinct()
    }

    // Returns root paths for USB OTG volumes not already covered by getExternalFilesDirs().
    // Uses StorageVolume.getDirectory() on API 30+; constructs /storage/<UUID> on older APIs
    // (standard Android convention, valid with MANAGE_EXTERNAL_STORAGE).
    private fun otgVolumePaths(context: Context, sm: StorageManager): List<String> {
        val coveredUuids = context.getExternalFilesDirs(null)
            .filterNotNull()
            .mapNotNull { sm.getStorageVolume(it)?.uuid }
            .toSet()

        return sm.storageVolumes
            .filter { vol ->
                !vol.isPrimary &&
                vol.state == Environment.MEDIA_MOUNTED &&
                vol.uuid != null &&
                vol.uuid !in coveredUuids
            }
            .mapNotNull { vol ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    vol.directory?.absolutePath
                } else {
                    "/storage/${vol.uuid}"
                }
            }
    }

    @Synchronized
    fun invalidateCache() {
        lastUpdateTime = 0
    }

    @Synchronized
    fun getDownloadDirectoryApps (): MutableList<String> {
        // What apps have folders in the download area?
        // Isn't checking for "complete" marker - incomplete is accepted

        // Only update if cache is over N milliseconds old
        val time = System.currentTimeMillis()
        if (lastUpdateTime < (time - 5 * 1000) || lastUpdateTime > time) {
            lastUpdateTime = time

            // scan all install paths, deduplicate across volumes
            val dirs = mutableSetOf<String>()
            for (installPath in SteamService.allInstallPaths) {
                dirs += getSubdirectories(installPath)
            }

            downloadDirectoryApps = dirs.toMutableList()
        }

        return downloadDirectoryApps ?: mutableListOf()
    }

    private fun getSubdirectories (path: String): MutableList<String> {
        // Names of immediate subdirectories
        val subDir = File(path).list() { dir, name -> File(dir, name).isDirectory}
        if (subDir == null) {
            return emptyList<String>().toMutableList()
        }
        return subDir.toMutableList()
    }

    fun getSizeFromStoreDisplay (appId: Int, branch: String = "public"): String {
        val depots = SteamService.getDownloadableDepots(appId)
        val installBytes = depots.values.sumOf { (it.manifests[branch] ?: it.manifests["public"])?.size ?: 0L }
        return StorageUtils.formatBinarySize(installBytes)
    }

    suspend fun getSizeOnDiskDisplay (appId: Int, setResult: (String) -> Unit) {
        // Outputs "3.76GiB" etc to the result lambda without locking up the main thread
        withContext(Dispatchers.IO) {
            // Do it async
            if (SteamService.isAppInstalled(appId)) {
                val appSizeText = StorageUtils.formatBinarySize(
                    StorageUtils.getFolderSize(SteamService.getAppDirPath(appId))
                )

                Timber.d("Finding $appId size on disk $appSizeText")
                setResult(appSizeText)
            }
        }
    }
}
