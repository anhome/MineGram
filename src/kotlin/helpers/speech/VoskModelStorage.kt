package desu.mintgram.helpers.speech

import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.FileLog
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.zip.ZipInputStream

/** Downloads, unzips and deletes Vosk "small" models under `filesDir/vosk_models/<lang>/`. */
object VoskModelStorage {
    private val executor = Executors.newSingleThreadExecutor()

    private fun rootDir() = File(ApplicationLoader.applicationContext.filesDir, "vosk_models")

    fun modelDir(language: String) = File(rootDir(), language)

    fun isDownloaded(language: String): Boolean {
        val dir = modelDir(language)
        return dir.exists() && !dir.list().isNullOrEmpty()
    }

    fun listDownloaded(): List<RecognitionModel> = SpeechModels.ALL.filter { isDownloaded(it.language) }

    fun download(language: String, onProgress: (Float) -> Unit, onComplete: () -> Unit, onError: (Exception) -> Unit) {
        val model = SpeechModels.find(language) ?: run {
            onErrorOnUi(onError, IllegalArgumentException("Unknown language: $language"))
            return
        }
        executor.execute {
            try {
                downloadBlocking(model, onProgress)
                AndroidUtilities.runOnUIThread(onComplete)
            } catch (e: Exception) {
                FileLog.e(e)
                onErrorOnUi(onError, e)
            }
        }
    }

    private fun onErrorOnUi(onError: (Exception) -> Unit, e: Exception) {
        AndroidUtilities.runOnUIThread { onError(e) }
    }

    private fun downloadBlocking(model: RecognitionModel, onProgress: (Float) -> Unit) {
        val dir = modelDir(model.language)
        if (dir.exists()) dir.deleteRecursively()
        dir.mkdirs()
        val zipFile = File(dir, "model.zip")

        val connection = URL(model.url).openConnection() as HttpURLConnection
        connection.connectTimeout = 30_000
        connection.readTimeout = 60_000
        try {
            val contentLength = connection.contentLengthLong.takeIf { it > 0 } ?: model.sizeBytes
            connection.inputStream.use { input ->
                FileOutputStream(zipFile).use { output ->
                    val buffer = ByteArray(8192)
                    var totalRead = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                        totalRead += read
                        val progress = (totalRead.toFloat() / contentLength).coerceIn(0f, 1f)
                        AndroidUtilities.runOnUIThread { onProgress(progress) }
                    }
                }
            }
        } finally {
            connection.disconnect()
        }

        unzip(zipFile, dir)
        zipFile.delete()
    }

    /** Vosk zips have a single top-level folder; strip it so files land directly in [destDir]. */
    private fun unzip(zipFile: File, destDir: File) {
        ZipInputStream(zipFile.inputStream()).use { zip ->
            var entry = zip.nextEntry
            var rootPrefix: String? = null
            val buffer = ByteArray(8192)
            while (entry != null) {
                val name = entry.name
                if (rootPrefix == null) rootPrefix = name.substringBefore('/', "")
                val relative = if (rootPrefix.isNotEmpty() && name.startsWith("$rootPrefix/")) {
                    name.substring(rootPrefix.length + 1)
                } else {
                    name
                }
                if (relative.isNotEmpty()) {
                    val outFile = File(destDir, relative)
                    if (entry.isDirectory) {
                        outFile.mkdirs()
                    } else {
                        outFile.parentFile?.mkdirs()
                        FileOutputStream(outFile).use { out ->
                            while (true) {
                                val read = zip.read(buffer)
                                if (read <= 0) break
                                out.write(buffer, 0, read)
                            }
                        }
                    }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
    }

    fun delete(language: String): Boolean {
        val dir = modelDir(language)
        return !dir.exists() || dir.deleteRecursively()
    }
}
