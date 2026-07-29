package desu.mintgram.helpers.speech

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.InputStream
import java.nio.ByteBuffer

/**
 * Decodes a compressed audio file (voice messages are opus/ogg) to raw 16-bit PCM, lazily, via
 * [android.media.MediaCodec] — Vosk's recognizer needs raw PCM, not the original container.
 */
object FormatConverter {
    fun getSampleRate(path: String): Int {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(path)
            var rate = -1
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME)
                if (mime != null && mime.startsWith("audio/") && format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                    rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    break
                }
            }
            if (rate != -1) rate else 48000
        } catch (_: Exception) {
            48000
        } finally {
            extractor.release()
        }
    }

    fun extractAndConvertToPcm(path: String): InputStream = LazyPcmInputStream(path)

    private class LazyPcmInputStream(path: String) : InputStream() {
        private val extractor = MediaExtractor()
        private val codec: MediaCodec
        private val bufferInfo = MediaCodec.BufferInfo()
        private var currentOutputBuffer: ByteBuffer? = null
        private var isEos = false

        init {
            extractor.setDataSource(path)
            val format = extractor.getTrackFormat(0)
            val mime = format.getString(MediaFormat.KEY_MIME)
                ?: throw java.io.IOException("Not an audio file")
            if (!mime.startsWith("audio/")) throw java.io.IOException("Not an audio file")
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()
            extractor.selectTrack(0)
        }

        override fun read(): Int {
            val single = ByteArray(1)
            return if (read(single, 0, 1) == -1) -1 else single[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (isEos) return -1
            var written = 0
            while (written < len && !isEos) {
                val current = currentOutputBuffer
                if (current == null || !current.hasRemaining()) {
                    currentOutputBuffer = nextOutputBuffer()
                    if (currentOutputBuffer == null) break
                }
                val buffer = currentOutputBuffer ?: break
                val toCopy = minOf(len - written, buffer.remaining())
                buffer.get(b, off + written, toCopy)
                written += toCopy
            }
            return if (written > 0) written else -1
        }

        private fun nextOutputBuffer(): ByteBuffer? {
            while (!isEos) {
                val inputIndex = codec.dequeueInputBuffer(10_000)
                if (inputIndex >= 0) {
                    val inputBuffer = codec.getInputBuffer(inputIndex)
                    val sampleSize = inputBuffer?.let { extractor.readSampleData(it, 0) } ?: -1
                    if (sampleSize < 0) {
                        codec.queueInputBuffer(inputIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        isEos = true
                    } else {
                        codec.queueInputBuffer(inputIndex, 0, sampleSize, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
                val outputIndex = codec.dequeueOutputBuffer(bufferInfo, 10_000)
                if (outputIndex >= 0) {
                    val outputBuffer = codec.getOutputBuffer(outputIndex) ?: continue
                    val copy = ByteBuffer.allocate(bufferInfo.size)
                    copy.put(outputBuffer)
                    copy.flip()
                    codec.releaseOutputBuffer(outputIndex, false)
                    return copy
                }
            }
            return null
        }

        override fun close() {
            codec.stop()
            codec.release()
            extractor.release()
            super.close()
        }
    }
}
