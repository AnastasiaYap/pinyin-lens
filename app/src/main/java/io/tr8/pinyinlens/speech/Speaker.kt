package io.tr8.pinyinlens.speech

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

/**
 * Speaks Chinese through the system engine.
 *
 * Initialisation is asynchronous, so anything requested before the engine is
 * ready is held and spoken once it is — otherwise the first tap after opening
 * the sheet would silently do nothing.
 */
class Speaker(context: Context, private val onUnavailable: () -> Unit) {

    private var engine: TextToSpeech? = null
    private var ready = false
    private var pending: String? = null

    init {
        engine = TextToSpeech(context.applicationContext) { status ->
            val tts = engine
            if (status != TextToSpeech.SUCCESS || tts == null) {
                onUnavailable()
                return@TextToSpeech
            }
            val result = tts.setLanguage(Locale.SIMPLIFIED_CHINESE)
            if (result == TextToSpeech.LANG_MISSING_DATA ||
                result == TextToSpeech.LANG_NOT_SUPPORTED
            ) {
                onUnavailable()
                return@TextToSpeech
            }
            ready = true
            pending?.let { speak(it) }
            pending = null
        }
    }

    fun speak(text: String) {
        if (text.isBlank()) return
        val tts = engine
        if (tts == null || !ready) {
            pending = text
            return
        }
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, UTTERANCE_ID)
    }

    fun release() {
        engine?.stop()
        engine?.shutdown()
        engine = null
        ready = false
    }

    private companion object {
        const val UTTERANCE_ID = "pinyin-lens"
    }
}
