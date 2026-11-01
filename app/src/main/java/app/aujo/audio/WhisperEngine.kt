package app.aujo.audio

import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * One transcription engine over the vendored whisper.cpp library.
 *
 * The model context is loaded lazily (first transcribe after the model
 * download lands) and kept for the app's lifetime — it holds CPU-side
 * weights, roughly the size of the model file.
 *
 * All transcription reads from WAV files on disk; the microphone is never
 * involved after a recording stops.
 */
class WhisperEngine(private val modelFile: File) {

    var handle: Long = 0L
        private set

    /** Loads the model. Returns false when the file is unusable. */
    fun load(): Boolean {
        if (handle != 0L) return true
        handle = WhisperJni.loadModel(modelFile.absolutePath)
        return handle != 0L
    }

    fun close() {
        if (handle != 0L) {
            WhisperJni.freeModel(handle)
            handle = 0
        }
    }

    /**
     * Transcribes a 16 kHz mono PCM16 WAV. Returns null on engine failure;
     * an empty string means the model found no speech.
     */
    fun transcribe(wav: File, isoLang: String): String? {
        if (!load()) return null
        val samples = try {
            readSamples(wav)
        } catch (e: Exception) {
            Log.w("Aujo", "unable to read ${wav.name}", e)
            return null
        }
        if (samples.isEmpty()) return ""
        return WhisperJni.transcribe(handle, samples, isoLang)
    }

    /** Reads RIFF/WAVE data chunk (PCM16) and converts to normalized floats. */
    private fun readSamples(wav: File): FloatArray {
        RandomAccessFile(wav, "r").use { raf ->
            val header = ByteArray(12)
            raf.readFully(header)
            val riffOk = String(header, 0, 4) == "RIFF" && String(header, 8, 4) == "WAVE"
            if (!riffOk) throw IllegalArgumentException("not a WAV file")

            while (true) {
                val chunkHeader = ByteArray(8)
                raf.readFully(chunkHeader)
                val chunkId = String(chunkHeader, 0, 4)
                val chunkLen = ByteBuffer.wrap(chunkHeader, 4, 4)
                    .order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL
                if (chunkId == "data") {
                    val data = ByteArray(chunkLen.toInt())
                    raf.readFully(data)
                    val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
                    val out = FloatArray(data.size / 2)
                    for (i in out.indices) {
                        out[i] = buffer.short.toFloat() / 32768f
                    }
                    return out
                }
                raf.seek(raf.filePointer + chunkLen + (chunkLen and 1)) // chunks are 2-aligned
                if (raf.filePointer >= raf.length()) break
            }
        }
        throw IllegalArgumentException("WAV data chunk not found")
    }
}
