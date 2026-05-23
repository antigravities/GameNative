package app.gamenative.utils

import app.gamenative.data.GameSource
import app.gamenative.enums.Marker
import app.gamenative.service.itchio.ItchioInstallerDetector
import app.gamenative.service.itchio.ItchioInstallerType
import com.winlator.container.Container
import java.io.File

/**
 * Pre-install step for itch.io games that were downloaded as a Windows installer
 * (NSIS, Inno Setup, 7-Zip SFX, or MSI).
 *
 * When active, this step runs the installer inside Wine before the first game launch,
 * then deletes the installer file to reclaim space.  A [Marker.ITCHIO_INSTALLER_RAN]
 * marker file in the download directory prevents re-running on subsequent launches.
 *
 * Unknown executables require the user to have explicitly consented via the in-app
 * dialog (written as a [Marker.ITCHIO_INSTALLER_CONSENT] marker) before this step
 * activates.  If the user chose to skip, a [Marker.ITCHIO_INSTALLER_SKIP] marker is
 * present and this step is bypassed permanently.
 *
 * ZIP files are detected but not handled here — [appliesTo] returns false for them
 * so the stub remains until ZIP support is added.
 */
object ItchioInstallerStep : PreInstallStep {
    override val marker: Marker = Marker.ITCHIO_INSTALLER_RAN

    override fun appliesTo(
        container: Container,
        gameSource: GameSource,
        gameDirPath: String,
    ): Boolean {
        if (gameSource != GameSource.ITCHIO) return false

        // Already ran — clean up any leftover installer file and skip
        if (MarkerUtils.hasMarker(gameDirPath, Marker.ITCHIO_INSTALLER_RAN)) {
            cleanupInstallerFiles(File(gameDirPath))
            return false
        }

        // User explicitly skipped this installer
        if (MarkerUtils.hasMarker(gameDirPath, Marker.ITCHIO_INSTALLER_SKIP)) return false

        val installerFile = findInstallerFile(File(gameDirPath)) ?: return false
        return when (ItchioInstallerDetector.detect(installerFile)) {
            ItchioInstallerType.NSIS,
            ItchioInstallerType.INNO,
            ItchioInstallerType.SEVENZIP_SFX,
            ItchioInstallerType.MSI,
            -> true

            // Unknown exe: only run if the user consented via the dialog
            ItchioInstallerType.UNKNOWN_EXE ->
                MarkerUtils.hasMarker(gameDirPath, Marker.ITCHIO_INSTALLER_CONSENT)

            // ZIP is not handled yet; NOT_INSTALLER is not an installer
            ItchioInstallerType.ZIP,
            ItchioInstallerType.NOT_INSTALLER,
            -> false
        }
    }

    override fun buildCommand(
        container: Container,
        appId: String,
        gameSource: GameSource,
        gameDir: File,
        gameDirPath: String,
    ): String? {
        val installerFile = findInstallerFile(gameDir) ?: return null
        val winName = installerFile.name
        // Windows path on the A: drive, which is bound to the itch.io download directory
        val winPath = "A:\\$winName"

        val installCmd = when (ItchioInstallerDetector.detect(installerFile)) {
            ItchioInstallerType.NSIS ->
                "$winPath /S"

            ItchioInstallerType.INNO ->
                "$winPath /VERYSILENT /NORESTART"

            ItchioInstallerType.SEVENZIP_SFX ->
                // -y accepts all prompts; no target specified so the archive's
                // own directory structure is preserved under whichever drive it extracts to
                "$winPath -y"

            ItchioInstallerType.MSI ->
                "msiexec /i $winPath /qn"

            ItchioInstallerType.UNKNOWN_EXE ->
                winPath  // user consented — run with no arguments

            else -> return null
        }

        // "&&" makes del run only when the installer exited with code 0.
        // XServerScreen checks for file presence after Wine exits to detect failure:
        // file gone → success; file still present → installer failed.
        return "$installCmd && del $winPath"
    }

    // --- Helpers ---

    /** Returns the first .exe or .msi file found in [dir], or null if none exists. */
    fun findInstallerFile(dir: File): File? =
        dir.listFiles()?.firstOrNull { it.isFile && it.extension.lowercase() in setOf("exe", "msi") }

    /** Deletes any remaining .exe/.msi files from [dir] (called after the step has already run). */
    private fun cleanupInstallerFiles(dir: File) {
        dir.listFiles()
            ?.filter { it.isFile && it.extension.lowercase() in setOf("exe", "msi") }
            ?.forEach { it.delete() }
    }
}
