package desu.mintgram.helpers.speech

import org.telegram.messenger.LocaleController
import java.util.Locale

/** One downloadable Vosk "small" model. Sizes/URLs are real alphacephei.com facts, keep exact. */
data class RecognitionModel(val language: String, val url: String, val sizeBytes: Long)

object SpeechModels {
    /** Same list (language, url, size) as exteraGram's VoskRecognizer — do not reorder/rename. */
    @JvmField
    val ALL: List<RecognitionModel> = listOf(
        RecognitionModel("ca", "https://alphacephei.com/vosk/models/vosk-model-small-ca-0.4.zip", 43405881L),
        RecognitionModel("cs", "https://alphacephei.com/vosk/models/vosk-model-small-cs-0.4-rhasspy.zip", 46088666L),
        RecognitionModel("de", "https://alphacephei.com/vosk/models/vosk-model-small-de-0.15.zip", 46499967L),
        RecognitionModel("en", "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip", 41205931L),
        RecognitionModel("eo", "https://alphacephei.com/vosk/models/vosk-model-small-eo-0.42.zip", 43839401L),
        RecognitionModel("es", "https://alphacephei.com/vosk/models/vosk-model-small-es-0.42.zip", 39817833L),
        RecognitionModel("fa", "https://alphacephei.com/vosk/models/vosk-model-small-fa-0.42.zip", 53431220L),
        RecognitionModel("fr", "https://alphacephei.com/vosk/models/vosk-model-small-fr-0.22.zip", 42233323L),
        RecognitionModel("gu", "https://alphacephei.com/vosk/models/vosk-model-small-gu-0.42.zip", 108054987L),
        RecognitionModel("hi", "https://alphacephei.com/vosk/models/vosk-model-small-hi-0.22.zip", 44458845L),
        RecognitionModel("it", "https://alphacephei.com/vosk/models/vosk-model-small-it-0.22.zip", 49665141L),
        RecognitionModel("ja", "https://alphacephei.com/vosk/models/vosk-model-small-ja-0.22.zip", 49704573L),
        RecognitionModel("kk", "https://alphacephei.com/vosk/models/vosk-model-small-kz-0.42.zip", 59697294L),
        RecognitionModel("ko", "https://alphacephei.com/vosk/models/vosk-model-small-ko-0.22.zip", 86914329L),
        RecognitionModel("nl", "https://alphacephei.com/vosk/models/vosk-model-small-nl-0.22.zip", 40441176L),
        RecognitionModel("pl", "https://alphacephei.com/vosk/models/vosk-model-small-pl-0.22.zip", 52979372L),
        RecognitionModel("pt", "https://alphacephei.com/vosk/models/vosk-model-small-pt-0.3.zip", 32453112L),
        RecognitionModel("ru", "https://alphacephei.com/vosk/models/vosk-model-small-ru-0.22.zip", 46236750L),
        RecognitionModel("tg", "https://alphacephei.com/vosk/models/vosk-model-small-tg-0.22.zip", 51879043L),
        RecognitionModel("tr", "https://alphacephei.com/vosk/models/vosk-model-small-tr-0.3.zip", 36855784L),
        RecognitionModel("uk", "https://alphacephei.com/vosk/models/vosk-model-small-uk-v3-small.zip", 143914407L),
        RecognitionModel("uz", "https://alphacephei.com/vosk/models/vosk-model-small-uz-0.22.zip", 51061189L),
        RecognitionModel("vi", "https://alphacephei.com/vosk/models/vosk-model-small-vn-0.4.zip", 33656337L),
        RecognitionModel("zh", "https://alphacephei.com/vosk/models/vosk-model-small-cn-0.22.zip", 43898754L),
    )

    @JvmStatic
    fun find(language: String): RecognitionModel? = ALL.firstOrNull { it.language == language }

    /** Human-readable language name in the app's current locale, e.g. "ru" -> "Russian". */
    @JvmStatic
    fun displayName(language: String): String {
        val appLocale = LocaleController.getInstance().currentLocale ?: Locale.getDefault()
        val name = Locale(language).getDisplayLanguage(appLocale)
        return name.replaceFirstChar { it.titlecase(Locale.getDefault()) }
    }
}
