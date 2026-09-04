package app.gamenative.utils

import android.content.Context
import com.winlator.core.FileUtils
import com.winlator.xenvironment.ImageFs
import java.io.File
import timber.log.Timber

/**
 * Deduplicates prefix content that is byte-identical in every container.
 *
 * Some things a container installs are the same everywhere: Wine-Mono comes from one fixed MSI, and
 * `windows/Fonts` is static content shipped in the container pattern. Together they are ~390 MB per
 * container. This replaces those files with symlinks into a single shared store under
 * `imagefs_shared/prefix_cache`.
 *
 * The install itself is never skipped -- Mono's msiexec still runs per container so the registry
 * keys it writes stay correct. Only the resulting files are collapsed afterwards.
 *
 * Safety rests on the same mechanism as the shared Wine DLLs: `libcowbase.so` is preloaded into the
 * Wine process for shared-base containers and copies a file back into the container before anything
 * writes to it, so the shared store is never mutated. Only *regular files* are linked -- directories
 * stay real directories, so a newly created file lands in the container rather than in the store,
 * which is a case copy-on-write could not catch.
 */
object PrefixDedupe {

    /** Files smaller than this are compared byte-for-byte rather than by size alone. */
    private const val FULL_COMPARE_LIMIT = 1L * 1024 * 1024

    private const val COPY_BUFFER = 128 * 1024

    data class Result(val linked: Int, val seeded: Int, val skipped: Int, val bytesSaved: Long)

    /**
     * Points every regular file under [dir] at a shared copy, seeding the store from this container
     * if it is the first one to get here.
     *
     * [key] identifies the content, not the container -- e.g. `mono-wine-mono-11.0.0-x86` or
     * `fonts-proton-10.0-arm64ec`. Anything whose content could differ must use a different key,
     * because files are matched by relative path within a key.
     *
     * Idempotent: entries that are already symlinks are left alone, so this can run again when a
     * container's variant changes and the install is repeated.
     */
    fun shareDirectory(context: Context, key: String, dir: File): Result {
        if (!dir.isDirectory) return Result(0, 0, 0, 0)

        val store = File(ImageFs.getPrefixCacheDir(context), key)
        var seeded = 0
        if (!store.isDirectory) {
            seeded = seedStore(dir, store)
            if (seeded < 0) return Result(0, 0, 0, 0)
        }

        var linked = 0
        var skipped = 0
        var bytesSaved = 0L

        // walkTopDown is Kotlin's recursive directory iterator. isFile is false for a dangling or
        // directory symlink, and FileUtils.isSymlink filters out links we (or anything else) made
        // earlier, which is what makes repeat runs cheap.
        dir.walkTopDown().forEach { local ->
            if (!local.isFile || FileUtils.isSymlink(local)) return@forEach

            val relative = local.relativeTo(dir).path
            val shared = File(store, relative)
            if (!shared.isFile) {
                skipped++
                return@forEach
            }
            if (!sameContent(shared, local)) {
                // Content diverged from what the store holds. Leave the container's own copy in
                // place -- linking here would silently give this container different bytes.
                Timber.tag("PrefixDedupe").w("Content differs, keeping local copy: %s", relative)
                skipped++
                return@forEach
            }

            val size = local.length()
            if (replaceWithSymlink(local, shared)) {
                linked++
                bytesSaved += size
            } else {
                skipped++
            }
        }

        Timber.tag("PrefixDedupe").i(
            "%s: linked %d file(s), %.1f MB saved, %d seeded, %d skipped",
            key, linked, bytesSaved / (1024.0 * 1024.0), seeded, skipped,
        )
        return Result(linked, seeded, skipped, bytesSaved)
    }

    /**
     * Replaces cached copies of [sourceMsi] in a prefix's `windows/Installer` with symlinks to it.
     *
     * msiexec caches the package it installed under a generated name so it can repair or uninstall
     * later. For Wine-Mono that is an ~80 MB verbatim copy of an MSI already sitting in imagefs, so
     * the cache entry can point at the original instead. Reads through the link work normally; only
     * a write would trigger copy-on-write.
     */
    fun shareInstallerMsiCache(installerDir: File, sourceMsi: File): Int {
        if (!installerDir.isDirectory || !sourceMsi.isFile) return 0

        var linked = 0
        installerDir.listFiles { file -> file.isFile && file.name.endsWith(".msi", ignoreCase = true) }
            ?.forEach { cached ->
                if (FileUtils.isSymlink(cached)) return@forEach
                if (!contentEqualsFast(sourceMsi, cached)) return@forEach
                if (replaceWithSymlink(cached, sourceMsi)) linked++
            }

        if (linked > 0) {
            Timber.tag("PrefixDedupe").i("Linked %d cached MSI(s) to %s", linked, sourceMsi.name)
        }
        return linked
    }

    /**
     * Copies [dir] into the store through a staging directory, then moves it into place in one
     * atomic rename.
     *
     * The staging step matters: a half-populated store would be silently wrong for every container
     * that linked against it afterwards. Two containers racing here both succeed -- whoever renames
     * first wins and the loser discards its staging copy.
     *
     * @return number of files seeded, or -1 on failure.
     */
    private fun seedStore(dir: File, store: File): Int {
        val staging = File(store.parentFile, ".staging-${store.name}-${android.os.Process.myPid()}")
        FileUtils.delete(staging)
        if (!staging.mkdirs()) {
            Timber.tag("PrefixDedupe").w("Could not create staging dir %s", staging)
            return -1
        }

        var count = 0
        try {
            dir.walkTopDown().forEach { src ->
                val dst = File(staging, src.relativeTo(dir).path)
                when {
                    src.isDirectory -> dst.mkdirs()
                    // Skip symlinks: a container that was already deduped has nothing to donate.
                    FileUtils.isSymlink(src) -> Unit
                    src.isFile -> {
                        dst.parentFile?.mkdirs()
                        if (FileUtils.copy(src, dst)) count++
                    }
                }
            }
        } catch (e: Exception) {
            Timber.tag("PrefixDedupe").e(e, "Failed while seeding %s", store.name)
            FileUtils.delete(staging)
            return -1
        }

        if (staging.renameTo(store)) {
            Timber.tag("PrefixDedupe").i("Seeded shared store %s with %d file(s)", store.name, count)
            return count
        }

        // Another container got there first; its copy is just as good as ours.
        FileUtils.delete(staging)
        return if (store.isDirectory) 0 else -1
    }

    /**
     * Swaps [file] for a symlink to [target], without a window where the file is missing.
     *
     * FileUtils.symlink deletes the destination before calling Os.symlink and swallows failures, so
     * using it directly on the real file would destroy it if the link could not be created. Building
     * the link under a temporary name and renaming over the original avoids that: rename is atomic,
     * and replaces the directory entry rather than following it.
     */
    private fun replaceWithSymlink(file: File, target: File): Boolean {
        val tmp = File(file.parentFile, ".dedupe-${file.name}")
        FileUtils.symlink(target.absolutePath, tmp.absolutePath)
        if (!FileUtils.isSymlink(tmp)) {
            Timber.tag("PrefixDedupe").w("Could not create symlink for %s", file.path)
            tmp.delete()
            return false
        }
        if (!tmp.renameTo(file)) {
            Timber.tag("PrefixDedupe").w("Could not swap in symlink for %s", file.path)
            tmp.delete()
            return false
        }
        return true
    }

    /**
     * Identity check used during the tree walk.
     *
     * Files are only ever matched within a store key that already pins their provenance (one fixed
     * MSI, or one Proton version's pattern), so above [FULL_COMPARE_LIMIT] equal size is taken as
     * equal content -- comparing every byte of a few hundred MB on every container creation would
     * cost more than the space it protects. Smaller files, which are the vast majority, are compared
     * in full.
     */
    private fun sameContent(a: File, b: File): Boolean {
        if (a.length() != b.length()) return false
        if (a.length() > FULL_COMPARE_LIMIT) return true
        return contentEqualsFast(a, b)
    }

    /**
     * Byte-for-byte comparison in chunks.
     *
     * FileUtils.contentEquals is deliberately not used: it reads and compares one byte at a time,
     * which for an 80 MB MSI means 80 million calls.
     */
    private fun contentEqualsFast(a: File, b: File): Boolean {
        if (a.length() != b.length()) return false

        return a.inputStream().use { sa ->
            b.inputStream().use { sb ->
                val bufA = ByteArray(COPY_BUFFER)
                val bufB = ByteArray(COPY_BUFFER)
                var equal = true
                while (equal) {
                    val n = sa.fill(bufA)
                    // Lengths already match, so a short read on one side means the same on the
                    // other; fill() loops so this cannot desynchronise mid-file.
                    if (n != sb.fill(bufB)) {
                        equal = false
                    } else if (n == 0) {
                        break
                    } else {
                        for (i in 0 until n) {
                            if (bufA[i] != bufB[i]) {
                                equal = false
                                break
                            }
                        }
                    }
                }
                equal
            }
        }
    }

    /** Reads until [buf] is full or the stream ends, returning the number of bytes read. */
    private fun java.io.InputStream.fill(buf: ByteArray): Int {
        var offset = 0
        while (offset < buf.size) {
            val read = read(buf, offset, buf.size - offset)
            if (read < 0) break
            offset += read
        }
        return offset
    }
}
