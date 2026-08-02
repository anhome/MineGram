package desu.mintgram.helpers.calls

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaScannerConnection
import android.media.MediaMuxer
import android.os.Build
import android.os.SystemClock
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.Toast
import androidx.core.graphics.ColorUtils
import desu.mintgram.InuConfig
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.FileLoader
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.messenger.voip.VoIPService
import org.telegram.ui.Components.CubicBezierInterpolator
import java.io.File
import java.io.RandomAccessFile
import java.lang.ref.WeakReference
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToLong
import kotlin.math.sqrt

/**
 * Records private or group calls where Telegram's VoIP engine exchanges raw PCM with Android.
 * The left channel is the local microphone and the right channel is the remote mixed playout.
 *
 * Audio callbacks only copy one 20 ms frame into a bounded queue. File I/O, stereo interleaving
 * and audio encoding happen on a dedicated thread, so a slow disk can never block VoIP.
 */
object CallRecordingHelper {
    private const val SAMPLE_RATE = 48_000
    private const val SAMPLES_PER_FRAME = 960
    private const val MONO_FRAME_BYTES = SAMPLES_PER_FRAME * 2
    private const val FRAME_NS = 20_000_000L

    private val stateLock = Any()

    @Volatile
    private var session: RecordingSession? = null

    @Volatile
    private var localLevel = 0f

    @Volatile
    private var remoteLevel = 0f

    private var controlRef: WeakReference<CallRecordingControl>? = null
    private var peerName = ""

    @JvmStatic
    fun attach(root: FrameLayout, name: String, avatarColor: Int, avatarColor2: Int): View {
        return attach(root, name, avatarColor, avatarColor2, 150f)
    }

    fun attach(
        root: FrameLayout,
        name: String,
        avatarColor: Int,
        avatarColor2: Int,
        bottomMarginDp: Float,
    ): View {
        peerName = name
        val control = CallRecordingControl(root.context, avatarColor, avatarColor2)
        controlRef = WeakReference(control)
        root.addView(
            control,
            FrameLayout.LayoutParams(AndroidUtilities.dp(296f), AndroidUtilities.dp(64f)).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                bottomMargin = AndroidUtilities.dp(bottomMarginDp)
            },
        )
        control.sync(false)
        return control
    }

    @JvmStatic
    fun detach(view: View?) {
        if (controlRef?.get() === view) {
            controlRef?.clear()
            controlRef = null
        }
    }

    @JvmStatic
    fun onCallStateChanged(state: Int) {
        controlRef?.get()?.setCallActive(
            state == VoIPService.STATE_ESTABLISHED || state == VoIPService.STATE_RECONNECTING,
        )
        if (state == VoIPService.STATE_ENDED && isRecording()) finishOnCallEnd()
    }

    @JvmStatic
    fun isRecording(): Boolean = session != null

    @JvmStatic
    fun getSavedRecordings(context: Context): List<File> {
        val activePath = session?.file?.absolutePath
        val directories = listOfNotNull(
            FileLoader.checkDirectory(FileLoader.MEDIA_DIR_AUDIO),
            context.getExternalFilesDir(android.os.Environment.DIRECTORY_MUSIC),
            context.filesDir,
        ).map { File(it, "Mintgram Calls") }
            .distinctBy { it.absolutePath }

        return directories
            .flatMap { directory ->
                directory.listFiles { file ->
                    file.isFile &&
                        file.absolutePath != activePath &&
                        file.extension.lowercase(Locale.US) in setOf("ogg", "m4a", "wav")
                }?.asList().orEmpty()
            }
            .distinctBy { it.absolutePath }
            .sortedByDescending { it.lastModified() }
    }

    @JvmStatic
    fun finishOnCallEnd() {
        stop()
    }

    @JvmStatic
    fun onOutgoingPcm(buffer: ByteBuffer) {
        val active = session ?: return
        if (!active.claim(Pipeline.LEGACY)) return
        val bytes = copyFrame(buffer) ?: return
        if (VoIPService.getSharedInstance()?.isMicMute == true) {
            bytes.fill(0)
        }
        localLevel = level(bytes)
        active.offer(Channel.LOCAL, bytes)
    }

    @JvmStatic
    fun onIncomingPcm(buffer: ByteArray) {
        val active = session ?: return
        if (!active.claim(Pipeline.LEGACY)) return
        val bytes = if (buffer.size == MONO_FRAME_BYTES) buffer.copyOf() else buffer.copyOf(MONO_FRAME_BYTES)
        remoteLevel = level(bytes)
        active.offer(Channel.REMOTE, bytes)
    }

    @JvmStatic
    fun onOutgoingWebRtcPcm(buffer: ByteBuffer, size: Int, channels: Int, sampleRate: Int) {
        val active = session ?: return
        if (!active.claim(Pipeline.WEBRTC)) return
        val bytes = normalizeWebRtcFrame(buffer, size, channels, sampleRate) ?: return
        if (VoIPService.getSharedInstance()?.isMicMute == true) {
            bytes.fill(0)
        }
        localLevel = level(bytes)
        active.offerTenMs(Channel.LOCAL, bytes)
    }

    @JvmStatic
    fun onIncomingWebRtcPcm(buffer: ByteBuffer, size: Int, channels: Int, sampleRate: Int) {
        val active = session ?: return
        if (!active.claim(Pipeline.WEBRTC)) return
        val bytes = normalizeWebRtcFrame(buffer, size, channels, sampleRate) ?: return
        remoteLevel = level(bytes)
        active.offerTenMs(Channel.REMOTE, bytes)
    }

    private fun start(context: Context): Boolean {
        synchronized(stateLock) {
            if (session != null || !InuConfig.CALL_RECORDING.value) return false
            return try {
                val base = FileLoader.checkDirectory(FileLoader.MEDIA_DIR_AUDIO)
                    ?: context.getExternalFilesDir(android.os.Environment.DIRECTORY_MUSIC)
                    ?: context.filesDir
                val dir = File(base, "Mintgram Calls")
                if (!dir.exists() && !dir.mkdirs()) error("Cannot create ${dir.absolutePath}")

                val date = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
                val safePeer = peerName
                    .replace(Regex("""[\\/:*?"<>|\p{Cntrl}]"""), "_")
                    .trim()
                    .take(48)
                    .ifEmpty { "Telegram" }
                val requestedFormat = RecordingFormat.fromConfig(InuConfig.CALL_RECORDING_FORMAT.value)
                val format = requestedFormat.supportedOrFallback()
                val target = uniqueFile(
                    dir,
                    "Mintgram_Call_${safePeer}_$date.${format.extension}",
                    format.extension,
                )
                session = RecordingSession(
                    target,
                    context.applicationContext,
                    format,
                    InuConfig.CALL_RECORDING_BITRATE.value.coerceIn(64, 96) * 1_000,
                )
                localLevel = 0f
                remoteLevel = 0f
                notifyControl()
                true
            } catch (_: Throwable) {
                Toast.makeText(
                    context,
                    LocaleController.getString(R.string.InuCallRecordingFailed),
                    Toast.LENGTH_LONG,
                ).show()
                false
            }
        }
    }

    private fun stop() {
        val finished = synchronized(stateLock) {
            val current = session ?: return
            session = null
            current
        }
        localLevel = 0f
        remoteLevel = 0f
        finished.finish { file, ok ->
            AndroidUtilities.runOnUIThread {
                notifyControl()
                val context = controlRef?.get()?.context
                if (context != null) {
                    val text = if (ok) {
                        LocaleController.formatString(
                            R.string.InuCallRecordingSaved,
                            file.absolutePath,
                        )
                    } else {
                        LocaleController.getString(R.string.InuCallRecordingFailed)
                    }
                    Toast.makeText(context, text, Toast.LENGTH_LONG).show()
                }
            }
        }
        notifyControl()
    }

    private fun toggle(context: Context) {
        if (isRecording()) stop() else start(context)
    }

    private fun notifyControl() {
        AndroidUtilities.runOnUIThread { controlRef?.get()?.sync(true) }
    }

    private fun elapsedMs(): Long = session?.elapsedMs() ?: 0L

    private fun combinedLevel(): Float = max(localLevel, remoteLevel)

    private fun uniqueFile(dir: File, initialName: String, extension: String): File {
        var file = File(dir, initialName)
        var index = 2
        while (file.exists()) {
            file = File(dir, initialName.removeSuffix(".$extension") + "_${index++}.$extension")
        }
        return file
    }

    private fun copyFrame(source: ByteBuffer): ByteArray? {
        return try {
            val duplicate = source.duplicate()
            duplicate.rewind()
            val result = ByteArray(MONO_FRAME_BYTES)
            duplicate.get(result, 0, minOf(duplicate.remaining(), result.size))
            result
        } catch (_: Throwable) {
            null
        }
    }

    /** Downmixes and linearly resamples one WebRTC callback (normally 10 ms) to 48 kHz mono PCM. */
    private fun normalizeWebRtcFrame(
        source: ByteBuffer,
        byteCount: Int,
        channels: Int,
        sampleRate: Int,
    ): ByteArray? {
        if (byteCount <= 0 || channels <= 0 || sampleRate <= 0) return null
        return try {
            val duplicate = source.duplicate()
            duplicate.rewind()
            val available = minOf(byteCount, duplicate.remaining())
            val raw = ByteArray(available)
            duplicate.get(raw)
            val inputFrames = raw.size / (channels * 2)
            if (inputFrames == 0) return null

            val outputFrames = ((inputFrames.toLong() * SAMPLE_RATE) / sampleRate)
                .toInt()
                .coerceAtLeast(1)
            val out = ByteArray(outputFrames * 2)
            for (outputIndex in 0 until outputFrames) {
                val inputIndex = ((outputIndex.toLong() * inputFrames) / outputFrames)
                    .toInt()
                    .coerceAtMost(inputFrames - 1)
                var mixed = 0
                for (channel in 0 until channels) {
                    val offset = (inputIndex * channels + channel) * 2
                    mixed += (
                        (raw[offset].toInt() and 0xff) or
                            (raw[offset + 1].toInt() shl 8)
                        ).toShort().toInt()
                }
                val sample = (mixed / channels).coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                out[outputIndex * 2] = (sample and 0xff).toByte()
                out[outputIndex * 2 + 1] = (sample shr 8).toByte()
            }
            out
        } catch (_: Throwable) {
            null
        }
    }

    private fun level(bytes: ByteArray): Float {
        var sum = 0.0
        var i = 0
        while (i + 1 < bytes.size) {
            val sample = ((bytes[i].toInt() and 0xff) or (bytes[i + 1].toInt() shl 8)).toShort().toInt()
            sum += sample.toDouble() * sample
            i += 2
        }
        val rms = sqrt(sum / max(1, bytes.size / 2)) / Short.MAX_VALUE
        return (rms * 5.5).coerceIn(0.04, 1.0).toFloat()
    }

    private enum class Channel { LOCAL, REMOTE }
    private enum class Pipeline { LEGACY, WEBRTC }

    private enum class RecordingFormat(
        val extension: String,
        val scanMime: String,
        val codecMime: String?,
    ) {
        OPUS("ogg", "audio/ogg", MediaFormat.MIMETYPE_AUDIO_OPUS),
        AAC("m4a", "audio/mp4", MediaFormat.MIMETYPE_AUDIO_AAC),
        WAV("wav", "audio/wav", null);

        fun supportedOrFallback(): RecordingFormat {
            if (this != OPUS) return this
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return AAC
            return if (hasEncoder(codecMime!!)) this else AAC
        }

        companion object {
            fun fromConfig(value: Int): RecordingFormat = when (value) {
                InuConfig.CallRecordingFormatItem.AAC -> AAC
                InuConfig.CallRecordingFormatItem.WAV -> WAV
                else -> OPUS
            }

            private fun hasEncoder(mime: String): Boolean = try {
                val format = MediaFormat.createAudioFormat(mime, SAMPLE_RATE, 2)
                MediaCodecList(MediaCodecList.REGULAR_CODECS).findEncoderForFormat(format) != null
            } catch (_: Throwable) {
                false
            }
        }
    }

    private data class Packet(
        val channel: Channel,
        val timestampNs: Long,
        val bytes: ByteArray,
    )

    private class RecordingSession(
        val file: File,
        private val context: Context,
        private val format: RecordingFormat,
        private val bitrate: Int,
    ) {
        private val startedAtMs = SystemClock.elapsedRealtime()
        private val queue = LinkedBlockingQueue<Packet>(512)
        private val pendingLock = Any()
        private var pendingLocal: ByteArray? = null
        private var pendingRemote: ByteArray? = null
        private var pipeline: Pipeline? = null
        private val sink: AudioSink = if (format == RecordingFormat.WAV) {
            WavAudioSink(file)
        } else {
            CodecAudioSink(file, format, bitrate)
        }

        @Volatile
        private var stopping = false

        @Volatile
        private var writeFailed = false

        private val writer = Thread({ writeLoop() }, "MintgramCallRecorder").apply {
            priority = Thread.NORM_PRIORITY - 1
            start()
        }

        fun elapsedMs(): Long = SystemClock.elapsedRealtime() - startedAtMs

        fun claim(candidate: Pipeline): Boolean = synchronized(pendingLock) {
            if (pipeline == null) pipeline = candidate
            pipeline == candidate
        }

        fun offer(channel: Channel, bytes: ByteArray) {
            val packet = Packet(channel, SystemClock.elapsedRealtimeNanos(), bytes)
            if (!queue.offer(packet)) {
                queue.poll()
                queue.offer(packet)
            }
        }

        fun offerTenMs(channel: Channel, bytes: ByteArray) {
            val ready = synchronized(pendingLock) {
                val pending = if (channel == Channel.LOCAL) pendingLocal else pendingRemote
                if (pending == null) {
                    if (channel == Channel.LOCAL) pendingLocal = bytes else pendingRemote = bytes
                    null
                } else {
                    val combined = ByteArray(MONO_FRAME_BYTES)
                    pending.copyInto(combined, 0, 0, minOf(pending.size, MONO_FRAME_BYTES / 2))
                    bytes.copyInto(
                        combined,
                        MONO_FRAME_BYTES / 2,
                        0,
                        minOf(bytes.size, MONO_FRAME_BYTES / 2),
                    )
                    if (channel == Channel.LOCAL) pendingLocal = null else pendingRemote = null
                    combined
                }
            }
            if (ready != null) offer(channel, ready)
        }

        fun finish(callback: (File, Boolean) -> Unit) {
            val tails = synchronized(pendingLock) {
                val out = ArrayList<Pair<Channel, ByteArray>>(2)
                pendingLocal?.let {
                    out.add(Channel.LOCAL to ByteArray(MONO_FRAME_BYTES).also { target ->
                        it.copyInto(target, 0, 0, minOf(it.size, MONO_FRAME_BYTES / 2))
                    })
                }
                pendingRemote?.let {
                    out.add(Channel.REMOTE to ByteArray(MONO_FRAME_BYTES).also { target ->
                        it.copyInto(target, 0, 0, minOf(it.size, MONO_FRAME_BYTES / 2))
                    })
                }
                pendingLocal = null
                pendingRemote = null
                out
            }
            for ((channel, bytes) in tails) offer(channel, bytes)
            stopping = true
            writer.interrupt()
            Thread {
                var ok = true
                try {
                    writer.join(8_000)
                    if (writer.isAlive) ok = false
                } catch (_: InterruptedException) {
                    ok = false
                }
                val minimumLength = if (format == RecordingFormat.WAV) 44L else 0L
                val valid = ok && !writeFailed && file.length() > minimumLength
                if (valid) {
                    MediaScannerConnection.scanFile(
                        context,
                        arrayOf(file.absolutePath),
                        arrayOf(format.scanMime),
                        null,
                    )
                }
                callback(file, valid)
            }.start()
        }

        private fun writeLoop() {
            try {
                var localOrigin = 0L
                var remoteOrigin = 0L
                var nextSlot = 0L
                val local = HashMap<Long, ByteArray>()
                val remote = HashMap<Long, ByteArray>()

                while (!stopping || queue.isNotEmpty()) {
                    val packet = try {
                        queue.poll(40, TimeUnit.MILLISECONDS)
                    } catch (_: InterruptedException) {
                        null
                    }
                    if (packet != null) {
                        if (packet.channel == Channel.LOCAL && localOrigin == 0L) {
                            localOrigin = packet.timestampNs
                        } else if (packet.channel == Channel.REMOTE && remoteOrigin == 0L) {
                            remoteOrigin = packet.timestampNs
                        }
                        val origin = if (packet.channel == Channel.LOCAL) localOrigin else remoteOrigin
                        val slot = ((packet.timestampNs - origin).toDouble() / FRAME_NS).roundToLong()
                        if (slot >= nextSlot) {
                            (if (packet.channel == Channel.LOCAL) local else remote)[slot] = packet.bytes
                        }
                    }

                    if (localOrigin != 0L && remoteOrigin != 0L) {
                        val now = SystemClock.elapsedRealtimeNanos()
                        val localSafe = (now - localOrigin) / FRAME_NS - 4
                        val remoteSafe = (now - remoteOrigin) / FRAME_NS - 4
                        val safeThrough = minOf(localSafe, remoteSafe)
                        while (nextSlot <= safeThrough) {
                            sink.write(stereoFrame(local.remove(nextSlot), remote.remove(nextSlot)))
                            nextSlot++
                        }
                    }
                }

                val finalSlot = maxOf(
                    local.keys.maxOrNull() ?: -1,
                    remote.keys.maxOrNull() ?: -1,
                )
                while (nextSlot <= finalSlot) {
                    sink.write(stereoFrame(local.remove(nextSlot), remote.remove(nextSlot)))
                    nextSlot++
                }
                sink.finish()
            } catch (_: Throwable) {
                writeFailed = true
            } finally {
                try {
                    sink.close()
                } catch (_: Throwable) {
                }
            }
        }

        private fun stereoFrame(
            local: ByteArray?,
            remote: ByteArray?,
        ): ByteArray {
            val out = ByteArray(SAMPLES_PER_FRAME * 4)
            for (sample in 0 until SAMPLES_PER_FRAME) {
                val monoOffset = sample * 2
                val stereoOffset = sample * 4
                if (local != null && monoOffset + 1 < local.size) {
                    out[stereoOffset] = local[monoOffset]
                    out[stereoOffset + 1] = local[monoOffset + 1]
                }
                if (remote != null && monoOffset + 1 < remote.size) {
                    out[stereoOffset + 2] = remote[monoOffset]
                    out[stereoOffset + 3] = remote[monoOffset + 1]
                }
            }
            return out
        }
    }

    private interface AudioSink {
        fun write(pcm: ByteArray)
        fun finish()
        fun close()
    }

    private class WavAudioSink(file: File) : AudioSink {
        private val raf = RandomAccessFile(file, "rw").apply {
            setLength(0)
            write(ByteArray(44))
        }
        private var finished = false

        override fun write(pcm: ByteArray) {
            raf.write(pcm)
        }

        override fun finish() {
            if (finished) return
            val dataLength = (raf.length() - 44).coerceAtLeast(0)
            raf.seek(0)
            raf.writeBytes("RIFF")
            writeLeInt((36L + dataLength).coerceAtMost(0xffffffffL).toInt())
            raf.writeBytes("WAVE")
            raf.writeBytes("fmt ")
            writeLeInt(16)
            writeLeShort(1)
            writeLeShort(2)
            writeLeInt(SAMPLE_RATE)
            writeLeInt(SAMPLE_RATE * 2 * 2)
            writeLeShort(4)
            writeLeShort(16)
            raf.writeBytes("data")
            writeLeInt(dataLength.coerceAtMost(0xffffffffL).toInt())
            finished = true
        }

        override fun close() {
            if (!finished) finish()
            raf.close()
        }

        private fun writeLeInt(value: Int) {
            raf.write(value and 0xff)
            raf.write(value ushr 8 and 0xff)
            raf.write(value ushr 16 and 0xff)
            raf.write(value ushr 24 and 0xff)
        }

        private fun writeLeShort(value: Int) {
            raf.write(value and 0xff)
            raf.write(value ushr 8 and 0xff)
        }
    }

    private class CodecAudioSink(
        file: File,
        private val format: RecordingFormat,
        bitrate: Int,
    ) : AudioSink {
        private val codec: MediaCodec
        private val muxer: MediaMuxer
        private val bufferInfo = MediaCodec.BufferInfo()
        private var trackIndex = -1
        private var muxerStarted = false
        private var presentationTimeUs = 0L
        private var finished = false

        init {
            val mime = requireNotNull(format.codecMime)
            val mediaFormat = MediaFormat.createAudioFormat(mime, SAMPLE_RATE, 2).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, SAMPLES_PER_FRAME * 4 * 4)
                if (format == RecordingFormat.AAC) {
                    setInteger(
                        MediaFormat.KEY_AAC_PROFILE,
                        MediaCodecInfo.CodecProfileLevel.AACObjectLC,
                    )
                }
            }
            codec = MediaCodec.createEncoderByType(mime)
            codec.configure(mediaFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            muxer = MediaMuxer(
                file.absolutePath,
                if (format == RecordingFormat.OPUS) {
                    MediaMuxer.OutputFormat.MUXER_OUTPUT_OGG
                } else {
                    MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
                },
            )
        }

        override fun write(pcm: ByteArray) {
            queueInput(pcm, false)
            drain(false)
        }

        override fun finish() {
            if (finished) return
            queueInput(ByteArray(0), true)
            drain(true)
            if (muxerStarted) muxer.stop()
            codec.stop()
            finished = true
        }

        override fun close() {
            if (!finished) {
                try {
                    codec.stop()
                } catch (_: Throwable) {
                }
                if (muxerStarted) {
                    try {
                        muxer.stop()
                    } catch (_: Throwable) {
                    }
                }
            }
            codec.release()
            muxer.release()
        }

        private fun queueInput(pcm: ByteArray, endOfStream: Boolean) {
            var attempts = 0
            while (attempts++ < 100) {
                val index = codec.dequeueInputBuffer(10_000)
                if (index >= 0) {
                    val input = requireNotNull(codec.getInputBuffer(index))
                    input.clear()
                    if (pcm.isNotEmpty()) input.put(pcm)
                    codec.queueInputBuffer(
                        index,
                        0,
                        pcm.size,
                        presentationTimeUs,
                        if (endOfStream) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0,
                    )
                    if (!endOfStream) {
                        presentationTimeUs += SAMPLES_PER_FRAME * 1_000_000L / SAMPLE_RATE
                    }
                    return
                }
                drain(false)
            }
            error("Audio encoder did not accept input")
        }

        private fun drain(endOfStream: Boolean) {
            var idleCount = 0
            while (true) {
                val index = codec.dequeueOutputBuffer(bufferInfo, if (endOfStream) 10_000 else 0)
                when {
                    index == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        if (!endOfStream || idleCount++ >= 100) return
                    }

                    index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        check(!muxerStarted)
                        trackIndex = muxer.addTrack(codec.outputFormat)
                        muxer.start()
                        muxerStarted = true
                    }

                    index >= 0 -> {
                        val output = requireNotNull(codec.getOutputBuffer(index))
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                            bufferInfo.size = 0
                        }
                        if (bufferInfo.size > 0) {
                            check(muxerStarted)
                            output.position(bufferInfo.offset)
                            output.limit(bufferInfo.offset + bufferInfo.size)
                            muxer.writeSampleData(trackIndex, output, bufferInfo)
                        }
                        val eos = bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        codec.releaseOutputBuffer(index, false)
                        idleCount = 0
                        if (eos) return
                    }
                }
            }
        }
    }

    private class CallRecordingControl(
        context: Context,
        private val avatarColor: Int,
        private val avatarColor2: Int,
    ) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val bounds = RectF()
        private val stopBounds = RectF()
        private val wave = FloatArray(18) { 0.08f }
        private var lastWaveAt = 0L
        private var expanded = if (isRecording()) 1f else 0f
        private var expandAnimator: ValueAnimator? = null
        private var callActive = false

        init {
            isClickable = true
            isFocusable = true
            contentDescription = LocaleController.getString(R.string.InuCallRecord)
            alpha = 0f
            scaleX = 0.85f
            scaleY = 0.85f
            visibility = INVISIBLE
        }

        fun setCallActive(active: Boolean) {
            if (callActive == active) return
            callActive = active
            animate().cancel()
            if (active) {
                visibility = VISIBLE
                animate()
                    .alpha(1f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(220)
                    .setInterpolator(CubicBezierInterpolator.DEFAULT)
                    .start()
            } else {
                animate()
                    .alpha(0f)
                    .scaleX(0.85f)
                    .scaleY(0.85f)
                    .setDuration(180)
                    .setInterpolator(CubicBezierInterpolator.DEFAULT)
                    .withEndAction { if (!callActive) visibility = INVISIBLE }
                    .start()
            }
        }

        fun sync(animated: Boolean) {
            val target = if (isRecording()) 1f else 0f
            contentDescription = LocaleController.getString(
                if (target == 1f) R.string.InuCallRecordingStop else R.string.InuCallRecord,
            )
            if (!animated) {
                expanded = target
                invalidate()
                return
            }
            expandAnimator?.cancel()
            expandAnimator = ValueAnimator.ofFloat(expanded, target).apply {
                duration = 280
                interpolator = CubicBezierInterpolator.EASE_OUT_QUINT
                addUpdateListener {
                    expanded = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        }

        override fun performClick(): Boolean {
            super.performClick()
            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            toggle(context)
            return true
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (!isEnabled || !callActive) return false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    animate().scaleX(0.96f).scaleY(0.96f).setDuration(80).start()
                    return true
                }

                MotionEvent.ACTION_UP -> {
                    animate().scaleX(1f).scaleY(1f).setDuration(120).start()
                    val hit = if (isRecording()) {
                        stopBounds.contains(event.x, event.y)
                    } else {
                        bounds.contains(event.x, event.y)
                    }
                    if (hit) {
                        performClick()
                    }
                    return true
                }

                MotionEvent.ACTION_CANCEL -> {
                    animate().scaleX(1f).scaleY(1f).setDuration(120).start()
                    return true
                }
            }
            return super.onTouchEvent(event)
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val compactWidth = AndroidUtilities.dp(132f)
            val fullWidth = width - AndroidUtilities.dp(8f)
            val pillWidth = compactWidth + (fullWidth - compactWidth) * expanded
            val pillHeight = AndroidUtilities.dp(52f)
            val left = (width - pillWidth) / 2f
            val top = (height - pillHeight) / 2f
            bounds.set(left, top, left + pillWidth, top + pillHeight)

            val shade = 0.48f - expanded * 0.08f
            val surfaceStart = ColorUtils.blendARGB(avatarColor, Color.BLACK, shade)
            val surfaceEnd = ColorUtils.blendARGB(avatarColor2, Color.BLACK, shade + 0.06f)
            paint.shader = LinearGradient(
                bounds.left,
                bounds.top,
                bounds.right,
                bounds.bottom,
                surfaceStart,
                surfaceEnd,
                Shader.TileMode.CLAMP,
            )
            paint.alpha = (205 + expanded * 28).toInt()
            canvas.drawRoundRect(bounds, pillHeight / 2f, pillHeight / 2f, paint)
            paint.shader = null
            paint.alpha = 255
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = AndroidUtilities.dpf2(1f)
            paint.color = ColorUtils.setAlphaComponent(
                ColorUtils.blendARGB(avatarColor2, Color.WHITE, 0.28f),
                110,
            )
            canvas.drawRoundRect(bounds, pillHeight / 2f, pillHeight / 2f, paint)
            paint.style = Paint.Style.FILL

            val red = 0xfff23b4d.toInt()
            val cy = bounds.centerY()
            val dotX = bounds.left + AndroidUtilities.dp(25f)
            paint.color = red
            val pulse = if (isRecording()) {
                0.82f + 0.18f * abs(kotlin.math.sin(SystemClock.elapsedRealtime() / 320.0)).toFloat()
            } else {
                1f
            }
            canvas.drawCircle(dotX, cy, AndroidUtilities.dp(7f) * pulse, paint)

            paint.typeface = Typeface.DEFAULT_BOLD
            paint.textSize = AndroidUtilities.dpf2(14f)
            paint.color = Color.WHITE
            paint.textAlign = Paint.Align.LEFT

            if (expanded < 0.55f) {
                paint.alpha = ((1f - expanded * 1.8f).coerceIn(0f, 1f) * 255).toInt()
                canvas.drawText(
                    LocaleController.getString(R.string.InuCallRecord),
                    dotX + AndroidUtilities.dp(15f),
                    cy - (paint.ascent() + paint.descent()) / 2f,
                    paint,
                )
            }

            if (expanded > 0.2f) {
                paint.alpha = (((expanded - 0.2f) / 0.8f).coerceIn(0f, 1f) * 255).toInt()
                val totalSeconds = elapsedMs() / 1000
                val timer = String.format(
                    Locale.US,
                    "%02d:%02d",
                    totalSeconds / 60,
                    totalSeconds % 60,
                )
                canvas.drawText(
                    timer,
                    dotX + AndroidUtilities.dp(14f),
                    cy - (paint.ascent() + paint.descent()) / 2f,
                    paint,
                )

                updateWave()
                val waveLeft = bounds.centerX() - AndroidUtilities.dp(34f)
                val gap = AndroidUtilities.dp(4f)
                paint.color = Color.WHITE
                paint.strokeWidth = AndroidUtilities.dpf2(2.2f)
                paint.strokeCap = Paint.Cap.ROUND
                for (i in wave.indices) {
                    val x = waveLeft + i * gap
                    val half = AndroidUtilities.dp(2f + 10f * wave[i])
                    canvas.drawLine(x, cy - half, x, cy + half, paint)
                }

                val stopX = bounds.right - AndroidUtilities.dp(26f)
                stopBounds.set(
                    stopX - AndroidUtilities.dp(23f),
                    cy - AndroidUtilities.dp(23f),
                    stopX + AndroidUtilities.dp(23f),
                    cy + AndroidUtilities.dp(23f),
                )
                paint.color = 0x33ffffff
                canvas.drawCircle(stopX, cy, AndroidUtilities.dpf2(19f), paint)
                paint.color = Color.WHITE
                val square = AndroidUtilities.dp(7f)
                canvas.drawRoundRect(
                    stopX - square,
                    cy - square,
                    stopX + square,
                    cy + square,
                    AndroidUtilities.dpf2(2f),
                    AndroidUtilities.dpf2(2f),
                    paint,
                )
            }
            paint.alpha = 255

            if (isRecording() || expandAnimator?.isRunning == true) {
                postInvalidateOnAnimation()
            }
        }

        private fun updateWave() {
            val now = SystemClock.elapsedRealtime()
            if (now - lastWaveAt < 70) return
            lastWaveAt = now
            wave.copyInto(wave, 0, 1, wave.size)
            wave[wave.lastIndex] = combinedLevel()
        }
    }
}
