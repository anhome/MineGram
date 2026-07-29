package desu.mintgram.helpers.speech

import android.util.Log
import desu.mintgram.InuConfig
import desu.mintgram.helpers.ai.AiClient
import desu.mintgram.helpers.ai.AiConfigHelper
import desu.mintgram.helpers.ai.AiGenerationCallback
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.FileLoader
import org.telegram.messenger.FileLog
import org.telegram.messenger.MessageObject
import org.telegram.messenger.NotificationCenter
import org.telegram.tgnet.RequestDelegate
import org.telegram.tgnet.TLRPC
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechStreamService
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Offline (Vosk) voice-message transcription, wired as a drop-in replacement for stock's
 * `TL_messages_transcribeAudio` network call in `TranscribeButton.transcribePressed` — see
 * `worktree/.../Components/TranscribeButton.java`. It synthesizes the exact same
 * `TL_messages_transcribedAudio` response shape into the same [RequestDelegate] stock already
 * wires up, so every downstream consumer (bubble spinner, storage, notifications) needs no
 * further changes.
 */
object SpeechRecognitionHelper {
    private const val UNLOAD_IDLE_MS = 600_000L
    private const val AI_POSTPROCESS_PROMPT = "You are an experienced linguist and editor " +
        "specializing in processing transcribed voice messages. Improve the text obtained after " +
        "automatic transcription so it reads naturally:\n" +
        "1. Correct spelling and grammatical errors.\n" +
        "2. Add missing punctuation.\n" +
        "3. Break the text into logical sentences/paragraphs.\n" +
        "4. Restore words that may have been misrecognized, based on context.\n" +
        "5. Preserve the original meaning without adding new information.\n" +
        "6. Do not censor profanity, just fix its spelling and surrounding punctuation.\n" +
        "Reply with only the improved text, in the same language it was provided in."

    private val executor = Executors.newCachedThreadPool()
    private val loadedModels = ConcurrentHashMap<String, Model>()
    private var unloadRunnable: Runnable? = null

    init {
        LibVosk.setLogLevel(LogLevel.INFO)
    }

    @JvmStatic
    fun isCustomRecognitionEnabled(): Boolean = InuConfig.SPEECH_RECOGNITION_LANGUAGE.value != "none"

    @JvmStatic
    fun recognize(messageObject: MessageObject, callback: RequestDelegate) {
        val language = InuConfig.SPEECH_RECOGNITION_LANGUAGE.value
        executor.execute {
            try {
                recognizeBlocking(messageObject, language, callback)
            } catch (e: Exception) {
                FileLog.e(e)
                callback.run(null, buildError("RECOGNIZE_FAILED"))
            }
        }
    }

    private fun recognizeBlocking(messageObject: MessageObject, language: String, callback: RequestDelegate) {
        val path = awaitLocalFile(messageObject)
        if (path == null) {
            callback.run(null, buildError("RECOGNIZE_FAILED"))
            return
        }

        val model = loadModel(language)
        scheduleUnload()

        val sampleRate = FormatConverter.getSampleRate(path)
        val pcmStream = FormatConverter.extractAndConvertToPcm(path)
        val recognizer = Recognizer(model, sampleRate.toFloat())
        val chunks = mutableListOf<String>()
        val transcriptionId = ("${messageObject.dialogId}_${messageObject.id}").hashCode().toLong()

        SpeechStreamService(recognizer, pcmStream, sampleRate.toFloat()).start(object : RecognitionListener {
            override fun onPartialResult(hypothesis: String?) {}
            override fun onTimeout() {}

            override fun onResult(hypothesis: String?) {
                val text = extractText(hypothesis)
                if (!text.isNullOrEmpty()) chunks.add(text)
                callback.run(buildTranscribedAudio(chunks.joinToString(" "), transcriptionId, true), null)
            }

            override fun onFinalResult(hypothesis: String?) {
                val text = extractText(hypothesis)
                if (!text.isNullOrEmpty()) chunks.add(text)
                recognizer.close()
                closeQuietly(pcmStream)
                finish(chunks.joinToString(" ").trim(), transcriptionId, callback)
            }

            override fun onError(exception: Exception?) {
                FileLog.e(exception)
                recognizer.close()
                closeQuietly(pcmStream)
                callback.run(null, buildError("RECOGNIZE_FAILED"))
            }
        })
    }

    private fun finish(rawText: String, transcriptionId: Long, callback: RequestDelegate) {
        if (rawText.isEmpty()) {
            callback.run(buildTranscribedAudio(rawText, transcriptionId, false), null)
            return
        }
        if (InuConfig.SPEECH_RECOGNITION_AI_POSTPROCESSING.value && AiConfigHelper.canUseAi()) {
            AiClient.sendMessage(rawText, useHistory = false, systemPromptOverride = AI_POSTPROCESS_PROMPT,
                callback = object : AiGenerationCallback {
                    override fun onResponse(text: String) {
                        callback.run(buildTranscribedAudio(text, transcriptionId, false), null)
                    }

                    override fun onError(code: Int, message: String) {
                        callback.run(buildTranscribedAudio(rawText, transcriptionId, false), null)
                    }
                })
        } else {
            callback.run(buildTranscribedAudio(rawText, transcriptionId, false), null)
        }
    }

    private fun extractText(json: String?): String? {
        if (json.isNullOrEmpty()) return null
        return try {
            org.json.JSONObject(json).optString("text").takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            FileLog.e(e)
            null
        }
    }

    private fun buildTranscribedAudio(text: String, transcriptionId: Long, pending: Boolean) =
        TLRPC.TL_messages_transcribedAudio().apply {
            this.text = text
            this.pending = pending
            this.transcription_id = transcriptionId
        }

    private fun buildError(text: String) = TLRPC.TL_error().apply { this.text = text }

    /** Vosk's SpeechStreamService never closes its input stream itself — release the underlying
     *  MediaCodec/MediaExtractor ourselves once a session ends, or repeated transcriptions exhaust
     *  the device's limited concurrent-codec pool. */
    private fun closeQuietly(stream: java.io.InputStream) {
        try {
            stream.close()
        } catch (e: Exception) {
            FileLog.e(e)
        }
    }

    private fun loadModel(language: String): Model =
        loadedModels.getOrPut(language) {
            Log.d("MintgramSpeech", "Loading Vosk model: $language")
            Model(VoskModelStorage.modelDir(language).absolutePath)
        }

    private fun scheduleUnload() {
        unloadRunnable?.let { AndroidUtilities.cancelRunOnUIThread(it) }
        val runnable = Runnable {
            loadedModels.values.forEach { it.close() }
            loadedModels.clear()
            Log.d("MintgramSpeech", "Unloaded Vosk models due to inactivity")
        }
        unloadRunnable = runnable
        AndroidUtilities.runOnUIThread(runnable, UNLOAD_IDLE_MS)
    }

    /** Ensures the voice message's file exists locally, downloading it first if needed. Blocking. */
    private fun awaitLocalFile(messageObject: MessageObject): String? {
        val account = messageObject.currentAccount
        val message = messageObject.messageOwner ?: return null
        val attachPath = message.attachPath
        if (!attachPath.isNullOrEmpty() && File(attachPath).exists()) return attachPath

        val existingPath = FileLoader.getInstance(account).getPathToMessage(message)
        if (existingPath.exists()) return existingPath.absolutePath

        val document = messageObject.getDocument() ?: return null
        val latch = CountDownLatch(1)
        val targetName = FileLoader.getAttachFileName(document)
        val observer = object : NotificationCenter.NotificationCenterDelegate {
            override fun didReceivedNotification(id: Int, account: Int, vararg args: Any?) {
                if (id == NotificationCenter.fileLoaded || id == NotificationCenter.fileLoadFailed) {
                    if (args.isNotEmpty() && args[0] == targetName) latch.countDown()
                }
            }
        }
        // addObserver/removeObserver assert the calling thread is the UI thread (debug builds throw
        // otherwise), but this whole function runs on a background executor — marshal both ends.
        AndroidUtilities.runOnUIThread {
            val notificationCenter = NotificationCenter.getInstance(account)
            notificationCenter.addObserver(observer, NotificationCenter.fileLoaded)
            notificationCenter.addObserver(observer, NotificationCenter.fileLoadFailed)
            FileLoader.getInstance(account).loadFile(document, messageObject, 0, if (messageObject.shouldEncryptPhotoOrVideo()) 2 else 0)
        }
        latch.await(2, TimeUnit.MINUTES)
        AndroidUtilities.runOnUIThread {
            val notificationCenter = NotificationCenter.getInstance(account)
            notificationCenter.removeObserver(observer, NotificationCenter.fileLoaded)
            notificationCenter.removeObserver(observer, NotificationCenter.fileLoadFailed)
        }
        return existingPath.takeIf { it.exists() }?.absolutePath
    }
}
