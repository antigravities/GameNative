package app.gamenative.service.itchio

import java.io.File

enum class ItchioInstallerType {
    NSIS,
    INNO,
    SEVENZIP_SFX,
    MSI,
    UNKNOWN_EXE,
    /** ZIP archive — handling stubbed, not yet implemented. */
    ZIP,
    /** File is not a recognised installer (wrong extension, unknown format, etc.). */
    NOT_INSTALLER,
}

/**
 * Detects what kind of Windows installer a downloaded itch.io file is by reading its
 * binary content.
 *
 * We read at most [READ_BYTES] from the start of the file. This covers:
 *  - Magic-byte checks at offset 0 (OLE2/MSI, ZIP, MZ/PE)
 *  - ASCII string patterns embedded in the PE header/resources of NSIS and Inno installers
 *    (typically within the first 1–2 MB of the file)
 *  - The 7-Zip SFX module, whose stub is usually < 2 MB before the appended 7z archive
 */
object ItchioInstallerDetector {

    private const val READ_BYTES = 4 * 1024 * 1024 // 4 MB

    // OLE2 Compound File (used by .msi)
    private val OLE2_MAGIC = byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11.toByte(), 0xE0.toByte(),
        0xA1.toByte(), 0xB1.toByte(), 0x1A.toByte(), 0xE1.toByte())

    // ZIP local file header or end-of-central-directory
    private val ZIP_MAGIC = byteArrayOf(0x50, 0x4B)

    // DOS/PE executable ("MZ")
    private val MZ_MAGIC = byteArrayOf(0x4D, 0x5A)

    // 7-Zip archive signature
    private val SEVENZIP_MAGIC = byteArrayOf(0x37, 0x7A, 0xBC.toByte(), 0xAF.toByte(), 0x27, 0x1C)

    fun detect(file: File): ItchioInstallerType {
        val ext = file.extension.lowercase()

        // .msi is always an OLE2 compound file — no need to read bytes
        if (ext == "msi") return ItchioInstallerType.MSI

        // Only exe and msi are installer candidates
        if (ext != "exe") return ItchioInstallerType.NOT_INSTALLER

        // Read a chunk large enough to find embedded strings and magic sequences
        val bytes = file.inputStream().use { it.readNBytes(READ_BYTES) }
        if (bytes.isEmpty()) return ItchioInstallerType.NOT_INSTALLER

        // OLE2 header means it was mis-named — treat as MSI
        if (bytes.startsWith(OLE2_MAGIC)) return ItchioInstallerType.MSI

        // ZIP magic (self-extracting zip or accidentally named .exe)
        if (bytes.startsWith(ZIP_MAGIC)) return ItchioInstallerType.ZIP

        // Must be a PE/MZ executable for any of the below to apply
        if (!bytes.startsWith(MZ_MAGIC)) return ItchioInstallerType.UNKNOWN_EXE

        // --- String search inside the PE binary ---
        // These strings appear as ASCII in the PE's resource/data section.

        if (bytes.containsAscii("Nullsoft Install System") ||
            bytes.containsAscii("Nullsoft Scriptable Install System") ||
            bytes.containsAscii("Installer integrity check has failed")
        ) return ItchioInstallerType.NSIS

        if (bytes.containsAscii("Inno Setup Setup Player")) return ItchioInstallerType.INNO

        // 7-zip SFX: look for the archive signature appended after the stub, or the
        // copyright string embedded in the stub's resources
        if (bytes.containsBytes(SEVENZIP_MAGIC) || bytes.containsAscii("7-Zip Copyright")) {
            return ItchioInstallerType.SEVENZIP_SFX
        }

        return ItchioInstallerType.UNKNOWN_EXE
    }

    // --- Helpers ---

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean {
        if (size < prefix.size) return false
        for (i in prefix.indices) {
            if (this[i] != prefix[i]) return false
        }
        return true
    }

    /** Boyer-Moore-Horspool-style search for a byte subsequence. */
    private fun ByteArray.containsBytes(needle: ByteArray): Boolean {
        if (needle.isEmpty() || size < needle.size) return false
        outer@ for (i in 0..size - needle.size) {
            for (j in needle.indices) {
                if (this[i + j] != needle[j]) continue@outer
            }
            return true
        }
        return false
    }

    /** Searches for an ASCII string encoded as raw bytes (each char is one byte). */
    private fun ByteArray.containsAscii(text: String): Boolean =
        containsBytes(text.toByteArray(Charsets.US_ASCII))
}
