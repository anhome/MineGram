package desu.mintgram.helpers.calls

import android.animation.ValueAnimator
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.core.widget.NestedScrollView
import desu.mintgram.InuConfig
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.messenger.voip.VoIPService
import org.telegram.ui.ActionBar.BottomSheet
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Components.LayoutHelper
import java.io.ByteArrayOutputStream
import java.io.File
import java.lang.ref.WeakReference
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/**
 * Discord-like soundpad for private and group calls. Compressed resources are decoded lazily and mixed into
 * the microphone PCM immediately before Telegram hands it to the native VoIP engine. A second
 * cursor mixes the same clip into playout so the local user hears it without changing its timing.
 */
object CallSoundpadHelper {
    private const val SOUND_SAMPLE_RATE = 48_000
    private const val MIX_GAIN = 0.88f
    private const val MIC_GAIN_WHILE_PLAYING = 0.72f

    private val stateLock = Any()
    private val decoder = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "MintgramSoundpadDecoder").apply { isDaemon = true }
    }
    private val decoded = HashMap<Int, ShortArray>()

    @Volatile
    private var playback: Playback? = null

    @Volatile
    private var requestedSound = 0

    private var requestToken = 0
    private var controlRef: WeakReference<SoundpadControl>? = null
    private var sheetRef: WeakReference<SoundpadSheet>? = null

    private val bundledSounds = arrayOf(
        Sound(R.raw.inu_soundpad_laugh, null, R.string.InuCallSoundpadLaugh, "😄"),
        Sound(R.raw.inu_soundpad_omg, null, R.string.InuCallSoundpadOmg, "😲"),
        Sound(R.raw.inu_soundpad_pampam, null, R.string.InuCallSoundpadPampam, "🥁"),
        Sound(R.raw.inu_soundpad_thanks, null, R.string.InuCallSoundpadThanks, "👍"),
    )

    data class CustomSound(val file: File, val displayName: String)

    @JvmStatic
    fun getCustomSounds(context: Context): List<CustomSound> = customDirectory(context)
        .listFiles { file -> file.isFile }
        ?.sortedBy { it.name.lowercase() }
        ?.map { CustomSound(it, it.nameWithoutExtension.replace('_', ' ')) }
        .orEmpty()

    @JvmStatic
    fun importCustomSound(context: Context, uri: Uri): Result<CustomSound> = runCatching {
        val resolver = context.contentResolver
        val originalName = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (column >= 0 && cursor.moveToFirst()) cursor.getString(column) else null
            }
            ?: uri.lastPathSegment
            ?: "sound_${System.currentTimeMillis()}.mp3"
        val extension = originalName.substringAfterLast('.', "mp3").lowercase()
        require(extension in setOf("mp3", "m4a", "aac", "ogg", "opus", "wav", "flac"))
        val stem = originalName.substringBeforeLast('.')
            .replace(Regex("""[^\p{L}\p{N} _.-]"""), "_")
            .trim().take(48).ifEmpty { "sound" }
        val directory = customDirectory(context).apply { mkdirs() }
        var target = File(directory, "${stem}.$extension")
        var suffix = 2
        while (target.exists()) target = File(directory, "${stem}_${suffix++}.$extension")
        try {
            resolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(32 * 1024)
                    var total = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= 25L * 1024 * 1024)
                        output.write(buffer, 0, count)
                    }
                }
            } ?: error("Cannot open audio")
            require(target.length() > 0)
            MediaMetadataRetriever().use { metadata ->
                metadata.setDataSource(target.absolutePath)
                require(
                    metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                        ?.toLongOrNull()?.let { it in 1..120_000 } == true
                )
            }
        } catch (error: Throwable) {
            target.delete()
            throw error
        }
        CustomSound(target, target.nameWithoutExtension.replace('_', ' '))
    }

    @JvmStatic
    fun deleteCustomSound(sound: CustomSound): Boolean {
        synchronized(stateLock) {
            val key = customKey(sound.file)
            decoded.remove(key)
            if (requestedSound == key) stopLocked()
        }
        notifyUi()
        return sound.file.delete()
    }

    @JvmStatic
    fun isPlaying(): Boolean = requestedSound != 0

    private fun customDirectory(context: Context): File = File(context.filesDir, "Mintgram Soundpad")

    private fun customKey(file: File): Int = file.absolutePath.hashCode() or Int.MIN_VALUE

    private fun allSounds(context: Context): List<Sound> = bundledSounds.toList() + getCustomSounds(context).map {
        Sound(customKey(it.file), it.file, 0, "🎵", it.displayName)
    }

    @JvmStatic
    fun attach(root: FrameLayout, avatarColor: Int, avatarColor2: Int): View {
        return attach(root, avatarColor, avatarColor2, 216f)
    }

    fun attach(
        root: FrameLayout,
        avatarColor: Int,
        avatarColor2: Int,
        bottomMarginDp: Float,
    ): View {
        val control = SoundpadControl(root.context, avatarColor, avatarColor2)
        controlRef = WeakReference(control)
        root.addView(
            control,
            FrameLayout.LayoutParams(AndroidUtilities.dp(132f), AndroidUtilities.dp(48f)).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                bottomMargin = AndroidUtilities.dp(bottomMarginDp)
            },
        )
        control.setCallActive(false)
        return control
    }

    @JvmStatic
    fun detach(view: View?) {
        if (controlRef?.get() === view) {
            controlRef?.clear()
            controlRef = null
        }
        sheetRef?.get()?.dismiss()
        sheetRef?.clear()
        sheetRef = null
        stop()
    }

    @JvmStatic
    fun onCallStateChanged(state: Int) {
        val active = state == VoIPService.STATE_ESTABLISHED || state == VoIPService.STATE_RECONNECTING
        controlRef?.get()?.setCallActive(active)
        if (state == VoIPService.STATE_ENDED) stop()
    }

    @JvmStatic
    fun mixOutgoingPcm(buffer: ByteBuffer) {
        mixByteBuffer(buffer, 960 * 2, 1, SOUND_SAMPLE_RATE, localMonitor = false)
    }

    @JvmStatic
    fun mixOutgoingWebRtcPcm(buffer: ByteBuffer, size: Int, channels: Int, sampleRate: Int) {
        mixByteBuffer(buffer, size, channels, sampleRate, localMonitor = false)
    }

    @JvmStatic
    fun mixIncomingPcm(buffer: ByteArray) {
        val active = playback ?: return
        synchronized(stateLock) {
            if (playback !== active) return
            val frames = buffer.size / 2
            var cursor = active.monitorCursor
            for (frame in 0 until frames) {
                val sourceIndex = cursor.toInt()
                if (sourceIndex >= active.samples.size) break
                val offset = frame * 2
                val original = (
                    (buffer[offset].toInt() and 0xff) or
                        (buffer[offset + 1].toInt() shl 8)
                    ).toShort().toInt()
                val mixed = saturate(original + active.samples[sourceIndex] * MIX_GAIN)
                buffer[offset] = (mixed and 0xff).toByte()
                buffer[offset + 1] = (mixed shr 8).toByte()
                cursor += 1.0
            }
            active.monitorCursor = cursor
        }
    }

    @JvmStatic
    fun mixIncomingWebRtcPcm(buffer: ByteBuffer, size: Int, channels: Int, sampleRate: Int) {
        mixByteBuffer(buffer, size, channels, sampleRate, localMonitor = true)
    }

    private fun mixByteBuffer(
        source: ByteBuffer,
        byteCount: Int,
        channels: Int,
        sampleRate: Int,
        localMonitor: Boolean,
    ) {
        if (channels <= 0 || sampleRate <= 0 || byteCount < 2) return
        val active = playback ?: return
        synchronized(stateLock) {
            if (playback !== active) return
            try {
                val buffer = source.duplicate().order(ByteOrder.LITTLE_ENDIAN)
                buffer.position(0)
                val available = minOf(byteCount, buffer.capacity())
                val frames = available / (channels * 2)
                var cursor = if (localMonitor) active.monitorCursor else active.outgoingCursor
                val cursorStep = SOUND_SAMPLE_RATE.toDouble() / sampleRate
                for (frame in 0 until frames) {
                    val sourceIndex = cursor.toInt()
                    if (sourceIndex >= active.samples.size) break
                    val sound = active.samples[sourceIndex] * MIX_GAIN
                    for (channel in 0 until channels) {
                        val offset = (frame * channels + channel) * 2
                        val original = buffer.getShort(offset).toInt()
                        val voice = if (localMonitor) original.toFloat() else original * MIC_GAIN_WHILE_PLAYING
                        buffer.putShort(offset, saturate(voice + sound).toShort())
                    }
                    cursor += cursorStep
                }
                if (localMonitor) {
                    active.monitorCursor = cursor
                } else {
                    active.outgoingCursor = cursor
                    if (cursor >= active.samples.size) finishPlayback(active)
                }
            } catch (_: Throwable) {
                // The audio callback must never fail because of the optional soundpad.
            }
        }
    }

    private fun saturate(value: Float): Int = value.roundToInt()
        .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())

    private fun requestPlay(context: Context, sound: Sound) {
        synchronized(stateLock) {
            if (requestedSound == sound.key || playback?.resourceId == sound.key) {
                stopLocked()
                notifyUi()
                return
            }
            requestToken += 1
            val token = requestToken
            requestedSound = sound.key
            playback = null
            notifyUi()
            decoded[sound.key]?.let {
                startPlaybackLocked(sound.key, it)
                return
            }
            val appContext = context.applicationContext
            decoder.execute {
                val samples = runCatching {
                    if (sound.file != null) decodeFile(sound.file) else decodeResource(appContext, sound.key)
                }.getOrNull()
                synchronized(stateLock) {
                    if (token != requestToken || requestedSound != sound.key) return@synchronized
                    if (samples == null || samples.isEmpty()) {
                        requestedSound = 0
                    } else {
                        decoded[sound.key] = samples
                        startPlaybackLocked(sound.key, samples)
                    }
                }
                notifyUi()
            }
        }
    }

    private fun startPlaybackLocked(resourceId: Int, samples: ShortArray) {
        playback = Playback(resourceId, samples)
        requestedSound = resourceId
        notifyUi()
    }

    private fun finishPlayback(active: Playback) {
        if (playback !== active) return
        playback = null
        requestedSound = 0
        notifyUi()
    }

    private fun stop() {
        synchronized(stateLock) {
            stopLocked()
        }
        notifyUi()
    }

    private fun stopLocked() {
        requestToken += 1
        playback = null
        requestedSound = 0
    }

    private fun notifyUi() {
        AndroidUtilities.runOnUIThread {
            controlRef?.get()?.sync()
            sheetRef?.get()?.sync()
        }
    }

    private fun showSheet(context: Context) {
        if (sheetRef?.get()?.isShowing == true) return
        SoundpadSheet(context).also {
            sheetRef = WeakReference(it)
            it.setOnDismissListener(Runnable {
                if (sheetRef?.get() === it) {
                    sheetRef?.clear()
                    sheetRef = null
                }
            })
            it.show()
        }
    }

    private fun decodeResource(context: Context, resourceId: Int): ShortArray {
        val extractor = MediaExtractor()
        val descriptor = context.resources.openRawResourceFd(resourceId)
        extractor.setDataSource(descriptor.fileDescriptor, descriptor.startOffset, descriptor.length)
        descriptor.close()

        return decodeExtractor(extractor)
    }

    private fun decodeFile(file: File): ShortArray {
        val extractor = MediaExtractor()
        extractor.setDataSource(file.absolutePath)
        return decodeExtractor(extractor)
    }

    private fun decodeExtractor(extractor: MediaExtractor): ShortArray {
        var track = -1
        var inputFormat: MediaFormat? = null
        for (index in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(index)
            if (format.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                track = index
                inputFormat = format
                break
            }
        }
        check(track >= 0 && inputFormat != null)
        val format = requireNotNull(inputFormat)
        extractor.selectTrack(track)
        val codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(format, null, null, 0)
        codec.start()

        var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        var pcmEncoding = AudioFormat.ENCODING_PCM_16BIT
        var inputDone = false
        var outputDone = false
        val output = ByteArrayOutputStream()
        val info = MediaCodec.BufferInfo()
        try {
            while (!outputDone) {
                if (!inputDone) {
                    val index = codec.dequeueInputBuffer(10_000)
                    if (index >= 0) {
                        val input = codec.getInputBuffer(index)!!
                        val size = extractor.readSampleData(input, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(index, 0, size, extractor.sampleTime, extractor.sampleFlags)
                            extractor.advance()
                        }
                    }
                }
                when (val index = codec.dequeueOutputBuffer(info, 10_000)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val format = codec.outputFormat
                        sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        if (format.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                            pcmEncoding = format.getInteger(MediaFormat.KEY_PCM_ENCODING)
                        }
                    }
                    else -> if (index >= 0) {
                        codec.getOutputBuffer(index)?.let { buffer ->
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            val bytes = ByteArray(info.size)
                            buffer.get(bytes)
                            output.write(bytes)
                        }
                        outputDone = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        codec.releaseOutputBuffer(index, false)
                    }
                }
            }
        } finally {
            runCatching { codec.stop() }
            codec.release()
            extractor.release()
        }
        return normalizePcm(output.toByteArray(), sampleRate, channels, pcmEncoding)
    }

    private fun normalizePcm(bytes: ByteArray, sampleRate: Int, channels: Int, encoding: Int): ShortArray {
        check(sampleRate > 0 && channels > 0)
        val inputFrames = when (encoding) {
            AudioFormat.ENCODING_PCM_FLOAT -> bytes.size / (channels * 4)
            else -> bytes.size / (channels * 2)
        }
        check(inputFrames > 0)
        val outputFrames = ((inputFrames.toLong() * SOUND_SAMPLE_RATE) / sampleRate).toInt()
        val output = ShortArray(outputFrames)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        for (outIndex in 0 until outputFrames) {
            val inputIndex = ((outIndex.toLong() * inputFrames) / outputFrames).toInt()
                .coerceAtMost(inputFrames - 1)
            var mixed = 0f
            for (channel in 0 until channels) {
                val index = inputIndex * channels + channel
                mixed += if (encoding == AudioFormat.ENCODING_PCM_FLOAT) {
                    buffer.getFloat(index * 4).coerceIn(-1f, 1f) * Short.MAX_VALUE
                } else {
                    buffer.getShort(index * 2).toFloat()
                }
            }
            output[outIndex] = (mixed / channels).roundToInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                .toShort()
        }
        return output
    }

    private data class Sound(
        val key: Int,
        val file: File?,
        val titleRes: Int,
        val emoji: String,
        val customTitle: String? = null,
    )

    private class Playback(val resourceId: Int, val samples: ShortArray) {
        var outgoingCursor = 0.0
        var monitorCursor = 0.0
    }

    private class SoundpadControl(
        context: Context,
        color1: Int,
        color2: Int,
    ) : View(context) {
        private val rect = RectF()
        private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val symbolPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            strokeWidth = AndroidUtilities.dp(2f).toFloat()
            strokeCap = Paint.Cap.ROUND
        }
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = AndroidUtilities.dp(14f).toFloat()
            typeface = AndroidUtilities.bold()
        }
        private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 780
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            addUpdateListener { invalidate() }
        }
        private var callActive = false

        init {
            backgroundPaint.shader = LinearGradient(
                0f,
                0f,
                AndroidUtilities.dp(132f).toFloat(),
                0f,
                ColorUtils.blendARGB(color1, Color.WHITE, 0.14f),
                ColorUtils.blendARGB(color2, Color.BLACK, 0.08f),
                Shader.TileMode.CLAMP,
            )
            setOnClickListener {
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                showSheet(context)
            }
            contentDescription = LocaleController.getString(R.string.InuCallSoundpadTitle)
        }

        fun setCallActive(active: Boolean) {
            callActive = active
            isEnabled = active
            visibility = if (active) VISIBLE else INVISIBLE
            alpha = if (active) 1f else 0f
            sync()
        }

        fun sync() {
            val playing = requestedSound != 0
            if (playing && !animator.isStarted) animator.start()
            if (!playing && animator.isStarted) {
                animator.cancel()
                scaleX = 1f
                scaleY = 1f
            }
            invalidate()
        }

        override fun onDetachedFromWindow() {
            animator.cancel()
            super.onDetachedFromWindow()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val pulse = if (animator.isRunning) animator.animatedValue as Float else 0f
            scaleX = 1f + pulse * 0.035f
            scaleY = scaleX
            rect.set(0f, 0f, width.toFloat(), height.toFloat())
            backgroundPaint.alpha = if (callActive) 235 else 120
            canvas.drawRoundRect(rect, height / 2f, height / 2f, backgroundPaint)

            val centerY = height / 2f
            val startX = AndroidUtilities.dp(18f).toFloat()
            for (index in 0..2) {
                val barHeight = AndroidUtilities.dp((8 + ((index + pulse * 2).toInt() % 3) * 3).toFloat())
                val x = startX + AndroidUtilities.dp(index * 5f)
                canvas.drawLine(x, centerY - barHeight / 2, x, centerY + barHeight / 2, symbolPaint)
            }
            val label = LocaleController.getString(R.string.InuCallSoundpadShort)
            val baseline = centerY - (textPaint.ascent() + textPaint.descent()) / 2
            canvas.drawText(label, AndroidUtilities.dp(42f).toFloat(), baseline, textPaint)
        }
    }

    private class SoundpadSheet(context: Context) : BottomSheet(context, false) {
        private val buttons = HashMap<Int, TextView>()

        init {
            fixNavigationBar()
            setTitle(LocaleController.getString(R.string.InuCallSoundpadTitle), true)
            val container = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(8), dp(16), dp(18))
            }
            allSounds(context).forEach { sound ->
                val button = TextView(context).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(18), 0, dp(18), 0)
                    setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16f)
                    typeface = AndroidUtilities.bold()
                    text = "${sound.emoji}   ${sound.customTitle ?: LocaleController.getString(sound.titleRes)}"
                    setOnClickListener {
                        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        requestPlay(context, sound)
                    }
                }
                buttons[sound.key] = button
                container.addView(button, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 54, 0f, 4f, 0f, 4f))
            }
            val stopButton = TextView(context).apply {
                gravity = Gravity.CENTER
                setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15f)
                typeface = AndroidUtilities.bold()
                text = LocaleController.getString(R.string.InuCallSoundpadStop)
                setTextColor(Theme.getColor(Theme.key_text_RedBold))
                background = Theme.createSimpleSelectorRoundRectDrawable(
                    dp(22),
                    ColorUtils.setAlphaComponent(Theme.getColor(Theme.key_text_RedBold), 24),
                    ColorUtils.setAlphaComponent(Theme.getColor(Theme.key_text_RedBold), 48),
                )
                setOnClickListener { stop() }
            }
            container.addView(stopButton, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 44, 0f, 10f, 0f, 0f))
            setCustomView(NestedScrollView(context).apply { addView(container) })
            sync()
        }

        fun sync() {
            buttons.forEach { (resourceId, button) ->
                val selected = requestedSound == resourceId
                button.setTextColor(
                    Theme.getColor(if (selected) Theme.key_featuredStickers_addButton else Theme.key_dialogTextBlack),
                )
                button.background = Theme.createSimpleSelectorRoundRectDrawable(
                    dp(16),
                    if (selected) {
                        ColorUtils.setAlphaComponent(Theme.getColor(Theme.key_featuredStickers_addButton), 28)
                    } else {
                        Color.TRANSPARENT
                    },
                    Theme.getColor(Theme.key_dialogButtonSelector),
                )
                button.alpha = if (selected && playback == null) 0.65f else 1f
            }
        }

        private fun dp(value: Int): Int = AndroidUtilities.dp(value.toFloat())
    }
}
