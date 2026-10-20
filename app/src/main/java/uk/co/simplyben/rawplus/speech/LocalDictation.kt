package uk.co.simplyben.rawplus.speech

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * Explicit, bounded on-device dictation. Silence and segmented-session extras are hints;
 * the installed recognizer can ignore them. Neither long pauses nor unlimited listening
 * are guaranteed. The caller accumulates onFinal segments and owns interrupted drafts.
 * All entry points must run on the main thread. No capture or retry starts automatically.
 */
class LocalDictation(context: Context, private val listener: Listener) {
    enum class State { STARTING, LISTENING, FINALIZING, IDLE }

    interface Listener {
        fun onState(state: State, message: String)
        fun onPartial(text: String)
        fun onFinal(text: String)
        fun onInterrupted(partial: String, message: String)
    }

    private val context = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var generation = 0L
    private var active = false
    private var destroyed = false
    private var state = State.IDLE
    private var partial = ""
    private var sawSegment = false
    private var ready = false
    private var useSegments = Build.VERSION.SDK_INT >= 33
    private var sessionUsesSegments = false
    private var sessionLimit: Runnable? = null
    private var finalizationLimit: Runnable? = null

    fun start() {
        requireMainThread()
        if (destroyed || active) return
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            listener.onInterrupted("", "Microphone permission is required. Open setup to allow it.")
            emitState(State.IDLE, "Microphone permission required")
            return
        }
        generation++
        val token = generation
        partial = ""
        sawSegment = false
        ready = false
        sessionUsesSegments = useSegments
        active = true
        emitState(State.STARTING, "Starting on-device dictation")
        if (!isCurrent(token)) return
        try {
            if (!SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
                interrupt(token, "On-device speech recognition is unavailable. No online fallback is used.")
                return
            }
            val engine = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            recognizer = engine
            engine.setRecognitionListener(callbacks(token))
            sessionLimit = Runnable { if (isCurrent(token)) finishListening(token, "Session limit reached. Finishing dictation.") }
                .also { handler.postDelayed(it, 120_000L) }
            engine.startListening(recognitionIntent())
        } catch (_: SecurityException) {
            interrupt(token, "Microphone access was denied. Check permission and the microphone privacy setting.")
        } catch (_: UnsupportedOperationException) {
            interrupt(token, "On-device speech recognition is unavailable. No online fallback is used.")
        } catch (_: RuntimeException) {
            interrupt(token, "The on-device recognizer could not start. Tap the microphone to try again.")
        }
    }

    fun stop() {
        requireMainThread()
        if (!destroyed && active) finishListening(generation, "Finishing dictation")
    }

    /** Abandons the provisional hypothesis and prevents callbacks from a previous editor. */
    fun cancel() {
        requireMainThread()
        if (destroyed) return
        release()
        partial = ""
        listener.onPartial("")
        emitState(State.IDLE, "Dictation cancelled")
    }

    fun destroy() {
        requireMainThread()
        if (destroyed) return
        destroyed = true
        release()
        partial = ""
    }

    private fun recognitionIntent() = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-GB")
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        if (Build.VERSION.SDK_INT >= 33) {
            putExtra(RecognizerIntent.EXTRA_MASK_OFFENSIVE_WORDS, false)
            if (sessionUsesSegments) {
                putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 8_000)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 60_000)
            }
        }
    }

    private fun callbacks(token: Long) = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            if (!isCurrent(token)) return
            ready = true
            if (state != State.FINALIZING) emitState(State.LISTENING, "Listening on device")
        }

        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit

        override fun onEndOfSpeech() {
            if (!isCurrent(token)) return
            // End of speech is not a terminal callback. Segmented recognition may continue.
            if (!sessionUsesSegments) awaitFinal(token, "Finishing dictation")
        }

        override fun onPartialResults(partialResults: Bundle?) {
            if (!isCurrent(token)) return
            val hypothesis = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                ?: return
            partial = hypothesis
            listener.onPartial(partial)
        }

        override fun onSegmentResults(segmentResults: Bundle) {
            if (!isCurrent(token)) return
            val text = bestText(segmentResults)
            if (text.isEmpty()) return
            sawSegment = true
            partial = ""
            listener.onFinal(text)
            if (isCurrent(token)) listener.onPartial("")
        }

        override fun onResults(results: Bundle?) {
            if (!isCurrent(token)) return
            // Some engines send a whole-session result after segments. Never insert it twice.
            if (sawSegment) {
                complete(token)
            } else {
                val text = bestText(results)
                if (text.isEmpty()) interrupt(token, "No final transcript was returned. The draft was preserved.")
                else complete(token, text)
            }
        }

        override fun onEndOfSegmentedSession() {
            if (isCurrent(token)) complete(token)
        }

        override fun onError(error: Int) {
            if (!isCurrent(token)) return
            val rejectedInitialSegments = Build.VERSION.SDK_INT >= 33 && sessionUsesSegments && !ready && !sawSegment && partial.isEmpty() &&
                (error == SpeechRecognizer.ERROR_CLIENT || error == SpeechRecognizer.ERROR_CANNOT_CHECK_SUPPORT)
            if (rejectedInitialSegments) {
                useSegments = false
                interrupt(token, "The recognizer rejected the segmented request. Tap again to try standard on-device dictation.")
                return
            }
            val message = when (error) {
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone access was denied. Check setup and microphone privacy settings."
                SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> "The on-device recognizer does not support en-GB."
                SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "The en-GB on-device speech model is unavailable. Download it in device speech settings."
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "The speech recognizer is busy. Tap again when it is available."
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech was detected. Tap the microphone to start again."
                SpeechRecognizer.ERROR_NO_MATCH -> "No final transcript was recognized. The draft was preserved."
                SpeechRecognizer.ERROR_AUDIO -> "Audio capture was interrupted. The draft was preserved."
                else -> "On-device dictation stopped with error $error. The draft was preserved."
            }
            interrupt(token, message)
        }
    }

    private fun finishListening(token: Long, message: String) {
        if (!isCurrent(token) || state == State.FINALIZING) return
        awaitFinal(token, message)
        if (!isCurrent(token)) return
        try {
            recognizer?.stopListening()
        } catch (_: RuntimeException) {
            interrupt(token, "The recognizer could not finish. The draft was preserved.")
        }
    }

    private fun awaitFinal(token: Long, message: String) {
        if (!isCurrent(token) || state == State.FINALIZING) return
        emitState(State.FINALIZING, message)
        if (!isCurrent(token)) return
        finalizationLimit = Runnable {
            interrupt(token, "The recognizer did not finish in time. The draft was preserved.")
        }.also { handler.postDelayed(it, 8_000L) }
    }

    private fun complete(token: Long, finalText: String? = null) {
        if (!isCurrent(token)) return
        val unfinished = if (finalText == null) partial else ""
        release()
        val terminalGeneration = generation
        partial = ""
        if (!finalText.isNullOrEmpty()) listener.onFinal(finalText)
        if (destroyed || generation != terminalGeneration) return
        listener.onPartial("")
        if (destroyed || generation != terminalGeneration) return
        if (unfinished.isNotEmpty()) listener.onInterrupted(unfinished, "The session ended before the remaining draft was finalized.")
        if (!destroyed && generation == terminalGeneration) emitState(State.IDLE, "Dictation stopped")
    }

    private fun interrupt(token: Long, message: String) {
        if (!isCurrent(token)) return
        val unfinished = partial
        release()
        val terminalGeneration = generation
        partial = ""
        listener.onInterrupted(unfinished, message)
        if (!destroyed && generation == terminalGeneration) emitState(State.IDLE, message)
    }

    private fun release() {
        active = false
        generation++
        sessionLimit?.let(handler::removeCallbacks)
        finalizationLimit?.let(handler::removeCallbacks)
        sessionLimit = null
        finalizationLimit = null
        val engine = recognizer
        recognizer = null
        try { engine?.cancel() } catch (_: RuntimeException) { /* Already disconnected. */ }
        try { engine?.destroy() } catch (_: RuntimeException) { /* Already disconnected. */ }
    }

    private fun isCurrent(token: Long) = !destroyed && active && token == generation

    private fun emitState(next: State, message: String) {
        if (destroyed) return
        state = next
        listener.onState(next, message)
    }

    private fun bestText(bundle: Bundle?) = bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()

    private fun requireMainThread() {
        check(Looper.myLooper() == Looper.getMainLooper()) { "LocalDictation must be called on the main thread" }
    }
}
