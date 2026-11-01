package app.aujo.audio

import android.content.Context
import android.net.ConnectivityManager
import android.text.format.Formatter
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Owns the on-device Whisper model file:
 * - knows the exact model we want (quantized multilingual tiny from
 *   ggml-org/whisper.cpp's model repo),
 * - downloads it on explicit user consent (never bundled, all inference
 *   stays on-device),
 * - keeps it in app-private storage and can delete it on request.
 */
class WhisperModelManager(private val context: Context) {

    data class ModelState(
        val status: Status,
        /** bytes downloaded / total when @see [Status.DOWNLOADING]. */
        val bytesDone: Long = 0L,
        val bytesTotal: Long = 0L,
        val message: String = "",
    ) {
        val downloadedFraction: Float
            get() = if (bytesTotal > 0) bytesDone.toFloat() / bytesTotal else 0f
    }

    enum class Status { NOT_DOWNLOADED, DOWNLOADING, READY, ERROR }

    companion object {
        // ggml-tiny-q5_1.bin — quantized multilingual tiny (~31 MB),
        // English + Bahasa Indonesia, from the whisper.cpp model repo.
        const val MODEL_URL = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny-q5_1.bin"
        const val MODEL_NAME = "ggml-tiny-q5_1.bin"
        const val MODEL_BYTES = 32_152_673L // published file size, sanity check
        val MODEL_SHA256: String? = null // TODO: pin once verified end-to-end

        fun modelDir(context: Context): File = File(context.filesDir, "whisper")
        fun modelFile(context: Context): File = File(modelDir(context), MODEL_NAME)
    }

    private val _state = MutableStateFlow(
        ModelState(if (modelFile(context).exists()) Status.READY else Status.NOT_DOWNLOADED)
    )
    val state: StateFlow<ModelState> = _state.asStateFlow()

    fun isReady(): Boolean = _state.value.status == Status.READY

    fun bytesRemaining(): String {
        val left = MODEL_BYTES - _state.value.bytesDone
        return Formatter.formatShortFileSize(context, left.coerceAtLeast(0L))
    }

    /**
     * Streams the model to app-private storage. Idempotent: if already
     * present (or being downloaded) does nothing. The download itself is
     * never wired to the microphone — this app's privacy story holds.
     */
    suspend fun download(force: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        val current = _state.value
        if (current.status == Status.DOWNLOADING) return@withContext false
        val target = modelFile(context)
        if (target.exists() && !force) {
            _state.value = ModelState(Status.READY)
            return@withContext true
        }
        modelDir(context).mkdirs()
        val partial = File(target.parent, "$MODEL_NAME.part")
        if (!force) partial.delete()

        val meteredNote = if (isActiveNetworkMetered()) " — you're on mobile data" else ""
        _state.value = ModelState(Status.DOWNLOADING, 0, MODEL_BYTES, "Contacting server$meteredNote")
        try {
            val conn = URL(MODEL_URL).openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = true
            conn.connectTimeout = 30_000
            conn.readTimeout = 60_000
            try {
                conn.connect()
                if (conn.responseCode !in 200..299) {
                    throw IOException("HTTP ${conn.responseCode} fetching model")
                }
                val total = conn.contentLengthLong.takeIf { it > 0 } ?: MODEL_BYTES
                conn.inputStream.use { input ->
                    partial.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        var lastLog = System.currentTimeMillis()
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            val now = System.currentTimeMillis()
                            if (now - lastLog > 250) {
                                lastLog = now
                                _state.value = ModelState(Status.DOWNLOADING, done, total)
                            }
                        }
                        _state.value = ModelState(Status.DOWNLOADING, done, done)
                        if (total > 0 && done != total) {
                            throw IOException("Incomplete download: got $done of $total bytes")
                        }
                    }
                }
                if (!partial.renameTo(target)) {
                    // rename within the same dir should always succeed, but be safe
                    partial.copyTo(target, overwrite = true)
                    partial.delete()
                }
                if (MODEL_SHA256 != null) verifySha256(target)
            } finally {
                conn.disconnect()
            }
            _state.value = ModelState(Status.READY)
            Log.i("Aujo", "whisper model ready at ${target.path}")
            true
        } catch (e: Exception) {
            Log.w("Aujo", "model download failed", e)
            partial.delete()
            _state.value = ModelState(Status.ERROR, message = e.message ?: "download failed")
            false
        }
    }

    /** Deletes the cached model; entries can be re-transcribed after a re-download. */
    fun delete() {
        val target = modelFile(context)
        File(target.parent, "$MODEL_NAME.part").delete()
        if (target.delete()) {
            _state.value = ModelState(Status.NOT_DOWNLOADED)
        }
    }

    private fun verifySha256(file: File) {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        val hex = md.digest().joinToString("") { "%02x".format(it) }
        if (!hex.equals(MODEL_SHA256, ignoreCase = true)) {
            throw IOException("Model checksum mismatch")
        }
    }

    private fun isActiveNetworkMetered(): Boolean = try {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager?
        cm?.isActiveNetworkMetered ?: false
    } catch (_: Exception) {
        false // permission-less ROMs shouldn't break the download
    }
}
