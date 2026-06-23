package app.gamenative.translation

import android.graphics.Bitmap
import android.graphics.Color
import app.gamenative.utils.ScreenshotUtils
import com.google.mlkit.common.MlKitException
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.winlator.renderer.XServerRenderer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.coroutineContext
import timber.log.Timber

/**
 * Drives on-device "screen translation": periodically grabs the rendered game frame, runs ML Kit OCR
 * on it, translates each recognized text block, and exposes the result as a [StateFlow] the UI overlays
 * on top of the game.
 *
 * Nothing here touches the rendering pipeline — frame capture reuses the existing screenshot path
 * ([ScreenshotUtils.captureFromGL]). All OCR/translation work runs on [Dispatchers.Default]; ML Kit
 * downloads its OCR models (via Google Play Services) and translation language packs on demand.
 *
 * Lifecycle: call [start] when the user enables live translation, [setConfig] when languages/cadence
 * change, and [stop] on session teardown (it closes the ML Kit clients).
 */
class ScreenTranslator {

    // DownloadingOcr = the GMS text-recognition optional module is still downloading (transient, not an
    // error). DownloadingModel = the translation language pack is downloading.
    enum class Status { Idle, DownloadingOcr, DownloadingModel, Working, Ready, Error }

    /** One recognized + translated region, in coordinates of an image [imageWidth] x [imageHeight]. */
    data class Block(
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
        val text: String,
    )

    data class OverlayState(
        val status: Status = Status.Idle,
        // Human-readable detail for the Error status (null otherwise).
        val detail: String? = null,
        val imageWidth: Int = 0,
        val imageHeight: Int = 0,
        val blocks: List<Block> = emptyList(),
    )

    private val _state = MutableStateFlow(OverlayState())
    val state: StateFlow<OverlayState> = _state.asStateFlow()

    // Overlay opacity, exposed reactively so the overlay (now hosted in a ComposeView inside the
    // Android view tree, below the on-screen controls) can observe slider changes even when the
    // translated blocks themselves are unchanged.
    private val _opacity = MutableStateFlow(0.6f)
    val opacity: StateFlow<Float> = _opacity.asStateFlow()

    // Config is read on the loop thread each iteration, so mark @Volatile for safe cross-thread reads.
    @Volatile private var sourceLang: String = "ja"
    @Volatile private var targetLang: String = "en"
    @Volatile private var intervalMs: Long = 2000L
    // Max OCR input width (px); 0 = native (no downscale). Higher = more accurate but slower.
    @Volatile private var ocrMaxWidth: Int = 1920

    private var loopJob: Job? = null

    // Cached ML Kit clients. Recreated lazily when the relevant language changes; closed in stop().
    private var recognizer: TextRecognizer? = null
    private var recognizerForLang: String? = null
    private var translator: Translator? = null
    private var translatorKey: String? = null
    private var translatorModelReady = false

    // Hash of the previous OCR frame for cheap change-detection (skip work on static screens).
    private var lastFrameHash: ByteArray? = null

    fun setConfig(source: String, target: String, intervalMillis: Int, ocrMaxWidthPx: Int, overlayOpacity: Float) {
        sourceLang = source
        targetLang = target
        intervalMs = intervalMillis.toLong().coerceAtLeast(500L)
        ocrMaxWidth = ocrMaxWidthPx
        _opacity.value = overlayOpacity
    }

    /**
     * Starts the live loop if not already running. [rendererProvider] supplies the current renderer
     * (may briefly return null during surface recreation); [viewSizeProvider] is unused here but kept
     * so callers document intent — overlay mapping happens in the composable using the view's size.
     */
    fun start(scope: CoroutineScope, rendererProvider: () -> XServerRenderer?) {
        if (loopJob?.isActive == true) return
        // Reset change-detection so a fresh start always translates the first frame.
        lastFrameHash = null
        loopJob = scope.launch(Dispatchers.Default) { loop(rendererProvider) }
    }

    fun stop() {
        loopJob?.cancel()
        loopJob = null
        closeClients()
        lastFrameHash = null
        _state.value = OverlayState()
    }

    private suspend fun loop(rendererProvider: () -> XServerRenderer?) {
        while (true) {
            // Cooperative cancellation check — throws CancellationException if the loop was stopped.
            coroutineContext.ensureActive()
            try {
                val renderer = rendererProvider()
                if (renderer != null) {
                    runOnce(renderer)
                }
            } catch (e: Exception) {
                // ML Kit job timeouts surface as a bare CancellationException; ensureActive() rethrows
                // only a genuine loop cancellation, otherwise we log and keep the loop alive.
                coroutineContext.ensureActive()
                Timber.w(e, "Screen translation pass failed")
                _state.value = _state.value.copy(status = Status.Error, detail = e.localizedMessage)
            }
            delay(intervalMs)
        }
    }

    private suspend fun runOnce(renderer: XServerRenderer) {
        val captured = captureFrame(renderer) ?: return

        // Downscale large frames for OCR speed; keep the scaled bitmap's dimensions as the coordinate
        // space the overlay maps from.
        val ocrBitmap = downscaleForOcr(captured)
        if (ocrBitmap !== captured) captured.recycle()

        // Change detection: if the frame is identical to the previous one, skip OCR/translation.
        val hash = frameHash(ocrBitmap)
        if (lastFrameHash != null && hash.contentEquals(lastFrameHash)) {
            ocrBitmap.recycle()
            return
        }
        lastFrameHash = hash

        _state.value = _state.value.copy(status = Status.Working)

        val recognizer = recognizerFor(sourceLang)
        val image = InputImage.fromBitmap(ocrBitmap, 0)
        val visionText = try {
            recognizer.process(image).await()
        } catch (e: Exception) {
            coroutineContext.ensureActive()
            ocrBitmap.recycle()
            if (isModuleDownloading(e)) {
                // The GMS text-recognition optional module is still downloading — transient, not an
                // error. Surface it as a distinct status and retry this frame next pass.
                _state.value = _state.value.copy(status = Status.DownloadingOcr, detail = null)
                lastFrameHash = null
                return
            }
            throw e
        }
        ocrBitmap.recycle()

        val translator = translatorFor(sourceLang, targetLang)
        ensureTranslatorModel(translator)

        val blocks = ArrayList<Block>(visionText.textBlocks.size)
        for (block in visionText.textBlocks) {
            val box = block.boundingBox ?: continue
            val source = block.text.trim()
            if (source.isEmpty()) continue
            val translated = try {
                translator.translate(source).await()
            } catch (e: Exception) {
                coroutineContext.ensureActive()
                source // fall back to original text if this block fails to translate
            }
            // Skip regions where ML Kit returned essentially the same text it was given — the region
            // was already in the target language (e.g. an English UI string), so overlaying it with
            // an identical box just covers readable text. This also drops the failure fallback above.
            if (isSameText(source, translated)) continue
            blocks.add(Block(box.left, box.top, box.right, box.bottom, translated))
        }

        _state.value = OverlayState(
            status = Status.Ready,
            imageWidth = ocrWidth,
            imageHeight = ocrHeight,
            blocks = blocks,
        )
    }

    // --- Frame capture --------------------------------------------------------------------------

    private suspend fun captureFrame(renderer: XServerRenderer): Bitmap? {
        // captureFromGL invokes the callback on the GL thread (GLRenderer) or main thread (Vulkan).
        // Bridge it into the coroutine; bound the wait so a missed callback can't stall the loop.
        val deferred = CompletableDeferred<Bitmap?>()
        ScreenshotUtils.captureFromGL(renderer, postEffects = true) { bmp ->
            deferred.complete(bmp)
        }
        return withTimeoutOrNull(3000L) { deferred.await() }
    }

    // Dimensions of the most recent OCR bitmap, recorded so runOnce can report them after recycling.
    private var ocrWidth = 0
    private var ocrHeight = 0

    private fun downscaleForOcr(src: Bitmap): Bitmap {
        val maxWidth = ocrMaxWidth
        // maxWidth <= 0 means "OCR at native resolution" (Accurate preset) — no downscale.
        return if (maxWidth <= 0 || src.width <= maxWidth) {
            ocrWidth = src.width
            ocrHeight = src.height
            src
        } else {
            val scale = maxWidth.toFloat() / src.width
            val w = maxWidth
            val h = (src.height * scale).toInt().coerceAtLeast(1)
            ocrWidth = w
            ocrHeight = h
            Bitmap.createScaledBitmap(src, w, h, /* filter = */ true)
        }
    }

    /**
     * Cheap average-style frame fingerprint: downsample to 32x32 grayscale and keep the raw bytes.
     * Exact byte equality means "screen unchanged" → skip retranslation. Minor sprite animation will
     * change the hash and cause a re-pass, which is the safe direction (better to retranslate than miss
     * a text update).
     */
    private fun frameHash(bitmap: Bitmap): ByteArray {
        val n = 32
        val small = Bitmap.createScaledBitmap(bitmap, n, n, true)
        val pixels = IntArray(n * n)
        small.getPixels(pixels, 0, n, 0, 0, n, n)
        small.recycle()
        val out = ByteArray(n * n)
        for (i in pixels.indices) {
            val p = pixels[i]
            // Luminance approximation (no need for exact Rec.709 weights for a fingerprint).
            val lum = (Color.red(p) * 77 + Color.green(p) * 150 + Color.blue(p) * 29) shr 8
            out[i] = lum.toByte()
        }
        return out
    }

    /**
     * True when ML Kit returned essentially the same text it was given. Normalizes away the trivial
     * differences ML Kit may introduce (surrounding/collapsed whitespace, casing) so an "English ->
     * English" passthrough is reliably detected as a no-op translation.
     */
    private fun isSameText(source: String, translated: String): Boolean {
        fun norm(s: String) = s.trim().replace(Regex("\\s+"), " ").lowercase()
        return norm(source) == norm(translated)
    }

    // --- ML Kit client management ---------------------------------------------------------------

    private fun recognizerFor(lang: String): TextRecognizer {
        // The recognizer is script-specific; pick by the chosen source language. Japanese/Chinese/Korean
        // recognizers also read embedded Latin text.
        if (recognizer != null && recognizerForLang == lang) return recognizer!!
        recognizer?.close()
        recognizer = when (lang) {
            TranslateLanguage.JAPANESE -> TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
            TranslateLanguage.CHINESE -> TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
            TranslateLanguage.KOREAN -> TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
            else -> TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        }
        recognizerForLang = lang
        return recognizer!!
    }

    private fun translatorFor(source: String, target: String): Translator {
        val key = "$source>$target"
        if (translator != null && translatorKey == key) return translator!!
        translator?.close()
        val options = TranslatorOptions.Builder()
            .setSourceLanguage(TranslateLanguage.fromLanguageTag(source) ?: source)
            .setTargetLanguage(TranslateLanguage.fromLanguageTag(target) ?: target)
            .build()
        translator = Translation.getClient(options)
        translatorKey = key
        translatorModelReady = false
        return translator!!
    }

    private suspend fun ensureTranslatorModel(translator: Translator) {
        if (translatorModelReady) return
        _state.value = _state.value.copy(status = Status.DownloadingModel)
        // Default conditions allow downloading over any network — the user explicitly opted in.
        translator.downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
        translatorModelReady = true
    }

    /**
     * True when [e] indicates the GMS text-recognition optional module is still downloading (thrown as
     * `MlKitException: Waiting for the text optional module to be downloaded`). Detect by error code,
     * with a message fallback for robustness across ML Kit versions.
     */
    private fun isModuleDownloading(e: Exception): Boolean {
        if (e is MlKitException && e.errorCode == MlKitException.UNAVAILABLE) {
            return true
        }
        val msg = e.message ?: return false
        return msg.contains("module", ignoreCase = true) && msg.contains("download", ignoreCase = true)
    }

    private fun closeClients() {
        recognizer?.close()
        recognizer = null
        recognizerForLang = null
        translator?.close()
        translator = null
        translatorKey = null
        translatorModelReady = false
    }
}
