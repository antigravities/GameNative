package app.gamenative.utils

import app.gamenative.PrefManager

class DownloadSpeedConfig {
    private data class Ratios(val download: Double, val decompress: Double)

    private val ratios: Ratios
        get() = when (PrefManager.downloadSpeed) {
            8 -> {
                Ratios(download = 0.6, decompress = 0.2)
            }

            16 -> {
                Ratios(download = 1.2, decompress = 0.4)
            }

            24 -> {
                Ratios(download = 1.5, decompress = 0.5)
            }

            32 -> {
                Ratios(download = 2.4, decompress = 0.8)
            }

            else -> {
                Ratios(download = 0.6, decompress = 0.2)
            }
        }

    val cpuCores: Int
        get() = Runtime.getRuntime().availableProcessors()

    val maxDownloads: Int
        // Cap at 8: CDN throughput gains plateau past ~6–8 parallel connections,
        // and more connections create OkHttp lock contention on high-core devices.
        get() = (cpuCores * ratios.download).toInt().coerceIn(1, 8)

    val maxDecompress: Int
        // Cap at 3: each decompression worker holds both a compressed input buffer
        // (~1 MB Steam chunk) and an expanded output buffer simultaneously, so the
        // per-worker memory cost is roughly double that of a plain download worker.
        get() = (cpuCores * ratios.decompress).toInt().coerceIn(1, 3)
}
