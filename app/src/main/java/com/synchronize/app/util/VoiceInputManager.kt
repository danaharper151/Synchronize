package com.synchronize.app.util

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class VoiceInputManager(private val context: Context) {

    private val TAG = "VoiceInputManager"

    sealed class VoiceState {
        object Idle : VoiceState()
        object Listening : VoiceState()
        data class Result(val text: String, val macros: MacroParser.ParsedMacros?) : VoiceState()
        data class Error(val message: String) : VoiceState()
    }

    private val _state = MutableStateFlow<VoiceState>(VoiceState.Idle)
    val state: StateFlow<VoiceState> = _state

    private var speechRecognizer: SpeechRecognizer? = null

    // ── Check availability ────────────────────────────────────

    fun isAvailable(): Boolean =
        SpeechRecognizer.isRecognitionAvailable(context)

    // ── Start listening ───────────────────────────────────────

    fun startListening() {
        if (!isAvailable()) {
            _state.value = VoiceState.Error("Speech recognition not available on this device")
            return
        }

        speechRecognizer?.destroy()
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(
                RecognizerIntent.EXTRA_PROMPT,
                "Say: calories, fat, protein, net carbs, fiber"
            )
        }

        speechRecognizer?.setRecognitionListener(object : RecognitionListener {

            override fun onReadyForSpeech(params: Bundle?) {
                Log.d(TAG, "Ready for speech")
                _state.value = VoiceState.Listening
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val partial = partialResults
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                if (!partial.isNullOrBlank()) {
                    Log.d(TAG, "Partial: $partial")
                }
            }

            override fun onResults(results: Bundle?) {
                val text = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()

                if (text.isNullOrBlank()) {
                    _state.value = VoiceState.Error("No speech detected — please try again")
                    return
                }

                Log.d(TAG, "Result: $text")
                val macros = MacroParser.parse(text)
                Log.d(TAG, "Setting state to Result with text: $text")
                _state.value = VoiceState.Result(text, macros)
                Log.d(TAG, "State set successfully")


            }

            override fun onError(error: Int) {
                val message = when (error) {
                    SpeechRecognizer.ERROR_AUDIO            -> "Audio recording error"
                    SpeechRecognizer.ERROR_CLIENT           -> "Client error"
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission denied"
                    SpeechRecognizer.ERROR_NETWORK          -> "Network error"
                    SpeechRecognizer.ERROR_NETWORK_TIMEOUT  -> "Network timeout"
                    SpeechRecognizer.ERROR_NO_MATCH         -> "No speech recognized — try again"
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY  -> "Recognizer busy — try again"
                    SpeechRecognizer.ERROR_SERVER           -> "Server error"
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT   -> "No speech detected"
                    else -> "Unknown error ($error)"
                }
                Log.e(TAG, "Speech error: $message")
                _state.value = VoiceState.Error(message)
            }

            override fun onBeginningOfSpeech() {
                Log.d(TAG, "Speech started")
            }

            override fun onEndOfSpeech() {
                Log.d(TAG, "Speech ended")
            }

            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
            override fun onRmsChanged(rmsdB: Float) {}
        })

        speechRecognizer?.startListening(intent)
    }

    // ── Stop listening ────────────────────────────────────────

    fun stopListening() {
        speechRecognizer?.stopListening()
        _state.value = VoiceState.Idle
    }

    // ── Reset state ───────────────────────────────────────────

    fun reset() {
        _state.value = VoiceState.Idle
    }

    // ── Cleanup ───────────────────────────────────────────────

    fun destroy() {
        speechRecognizer?.destroy()
        speechRecognizer = null
        _state.value = VoiceState.Idle
    }
}