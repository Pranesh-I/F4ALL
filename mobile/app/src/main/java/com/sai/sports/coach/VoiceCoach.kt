package com.sai.sports.coach

import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/**
 * Speaks coaching cues through the phone's text-to-speech engine.
 *
 * The language is the one the app is showing, taken from [context]'s
 * configuration. If the phone has no voice for it, this stays silent and
 * reports [available] = false rather than falling back to English: an athlete
 * who chose Tamil because they do not read English will not follow spoken
 * English either, and a voice they cannot understand is worse than none.
 *
 * [speak] is safe from any thread — the pose callback calls it directly.
 */
class VoiceCoach(
    context: Context,
    private val onAvailability: (Boolean) -> Unit = {}
) : TextToSpeech.OnInitListener {

    private val locale: Locale = context.resources.configuration.locales[0]

    @Volatile
    var available = false
        private set

    /** The athlete's switch. Checked on every utterance so it takes effect mid-set. */
    @Volatile
    var enabled = true

    private val utteranceIds = AtomicInteger()

    private val engine = TextToSpeech(context.applicationContext, this)

    override fun onInit(status: Int) {

        if (status != TextToSpeech.SUCCESS) {
            Log.w(TAG, "Text-to-speech failed to start: $status")
            publish(false)
            return
        }

        val result = engine.setLanguage(locale)
        publish(result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED)
    }

    private fun publish(isAvailable: Boolean) {
        available = isAvailable
        onAvailability(isAvailable)
    }

    /**
     * [urgent] cuts off whatever is being said: advice about the rep in
     * progress is worthless once it arrives a rep late.
     */
    fun speak(text: String, urgent: Boolean) {
        if (!available || !enabled || text.isBlank()) return
        engine.speak(
            text,
            if (urgent) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD,
            null,
            "cue-${utteranceIds.incrementAndGet()}"
        )
    }

    fun stop() {
        if (available) engine.stop()
    }

    fun shutdown() {
        engine.stop()
        engine.shutdown()
    }

    private companion object {
        const val TAG = "VoiceCoach"
    }
}

/** Whether the athlete wants spoken coaching. On by default; remembered across attempts. */
object VoicePreference {

    private const val PREFERENCES = "voice_coach"
    private const val KEY_ENABLED = "enabled"

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ENABLED, enabled)
            .apply()
    }
}
