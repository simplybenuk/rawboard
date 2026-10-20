@file:Suppress("SetTextI18n")
package uk.co.simplyben.rawplus

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.text.InputType
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class SetupActivity : Activity() {
    private lateinit var deviceStatus: TextView
    private lateinit var speechStatus: TextView
    private var diagnostics: SpeechRecognizer? = null
    private var diagnosticGeneration = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val column = Ui.column(this).apply {
            setPadding(dp(20), dp(16), dp(20), dp(24))
            setBackgroundColor(Ui.paper(this@SetupActivity))
            isFocusableInTouchMode = true
        }
        val scroll = ScrollView(this).apply { addView(column) }
        scroll.setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        setContentView(scroll)
        scroll.post { Ui.applyWindowAppearance(window, this) }
        fun paragraph(text: String) = column.addView(Ui.text(this, text), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
        fun button(text: String, click: () -> Unit) = column.addView(Ui.button(this, text, click = click), LinearLayout.LayoutParams(-1, dp(48)).apply { bottomMargin = dp(8) })
        column.addView(Ui.heading(this, "Raw+ Keyboard", 28f))
        paragraph("Remove hesitation. Preserve personality.")
        paragraph("1. Allow the microphone.\n2. Enable Raw+ in Android's keyboard settings.\n3. Select it in the keyboard picker.")
        deviceStatus = Ui.text(this, "", 14f)
        column.addView(deviceStatus, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
        button("1 · Allow microphone") {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                refreshStatus()
            } else requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        }
        button("2 · Enable keyboard") { startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) }
        button("3 · Select keyboard / return to Gboard") {
            getSystemService(InputMethodManager::class.java).showInputMethodPicker()
        }
        paragraph("Keep Gboard enabled. Inside Raw+, tap Gboard to switch directly when Android allows it, or Keyboards to open the system picker. Selecting a keyboard always remains your choice.")
        column.addView(Ui.heading(this, "On-device English", 20f))
        speechStatus = Ui.text(this, "Checking on-device recognizer…", 14f)
        column.addView(speechStatus, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        button("Check speech support") { checkSpeechSupport() }
        button("Download en-GB speech model") {
            if (Build.VERSION.SDK_INT < 33) {
                speechStatus.text = "Use your device speech settings to download an English language model."
            } else try {
                val engine = diagnostics ?: SpeechRecognizer.createOnDeviceSpeechRecognizer(this).also { diagnostics = it }
                engine.triggerModelDownload(recognitionIntent())
                speechStatus.text = "Model download requested. It may need network access or device confirmation. Recheck support after it finishes. No microphone has started."
            } catch (_: RuntimeException) {
                speechStatus.text = "The recognizer could not request a model. Check the device's speech recognition settings."
            }
        }
        paragraph("Only Android's explicit on-device recognizer is used. There is no online audio fallback. Pause handling and session length depend on the installed recognizer. Each recording is capped at two minutes. Tap Dictate more to append another recording.")
        column.addView(Ui.heading(this, "Editing providers", 20f))
        paragraph("Raw stays on device. Choose your ChatGPT plan, an OpenAI API key, or your own backend for Raw+ and Polished. Providers explains sign-in, model choice and sharing. Selecting a provider does not start an edit.")
        button("Open Providers") { startActivity(Intent(this, ProvidersActivity::class.java)) }
        column.addView(Ui.heading(this, "Try the keyboard", 20f))
        paragraph("Select the Raw+ keyboard above, then tap this field. Each dictation starts in Raw and stays on device. Tap a point in the original transcript to place the blinking cursor, then Dictate here to add a paragraph. AI previews first switch to the original before you place the cursor. Choose Raw+ or Polished after speaking to request an edit from your selected provider. Insert adds text without submitting it. Use the expand button for longer drafts and the cog for options.")
        column.addView(EditText(this).apply {
            hint = "Tap here to try dictation"
            minLines = 3
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
            isSaveEnabled = false
            setTextColor(Ui.ink(this@SetupActivity))
            setHintTextColor(Ui.hint(this@SetupActivity))
        }, LinearLayout.LayoutParams(-1, -2))
        column.addView(Ui.heading(this, "Local test history", 20f))
        paragraph("For this test, local history is on by default. It keeps confirmed original dictation, AI versions and the text you insert, encrypted on this phone. Nothing is uploaded automatically. Open history to compare, export, delete or turn logging off. It keeps up to 100 entries for 30 days. There are no audio recordings, host-app contents or API credentials in the log. Raw+ cannot see whether you later publish or send inserted text.")
        button("Open local test history") { startActivity(Intent(this, HistoryActivity::class.java)) }
        column.addView(Ui.heading(this, "Privacy", 20f))
        paragraph("The microphone starts only after your tap. Raw+ never reads text around the cursor, stores audio files, or sends host-app text to a server. Password, private and non-text fields disable dictation. A host app must correctly label sensitive fields; Raw+ cannot detect every secret in an ordinary text field.")
        paragraph("The working draft clears when you hide or switch the keyboard, change fields, or insert. Confirmed text also goes to encrypted local test history while logging is on. Interrupted speech must be confirmed before it enters that history or can be inserted. There is no clipboard capture or automatic posting. Clear draft does not delete history; use Clear all history for that.")
        paragraph("AI editing sends your original dictated transcript to the selected provider only after you request Raw+ or Polished. Audio stays on the phone. ChatGPT editing shares your ChatGPT plan's limits and may use extra credits if you enabled them in ChatGPT. API editing is billed separately. Requests ask OpenAI not to store Responses state, but provider data policies still apply. An optional backend host may have its own logs. Review before inserting.")
        paragraph("ChatGPT authorization and your API key or backend token are stored encrypted on this device, never in the APK or transcript history. Raw+ does not access your existing ChatGPT conversations. Providers lets you sign out or forget API credentials separately without deleting local history. Keep your phone secure and revoke access in your OpenAI account if it is compromised.")
        paragraph("You can take screenshots of the keyboard and setup for feedback. The Providers screen blocks screenshots while a credential field is focused or has unsaved contents. Saved credentials are never displayed.")
        paragraph("If permission was permanently denied, open Android Settings → Apps → Raw+ Keyboard → Permissions → Microphone. Returning from setup always requires a fresh tap to record.")
        column.requestFocus()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
        checkSpeechSupport()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refreshStatus()
    }

    private fun refreshStatus() {
        val manager = getSystemService(InputMethodManager::class.java)
        val enabled = manager.enabledInputMethodList.any { it.packageName == packageName }
        val mic = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        deviceStatus.text = "Microphone ${if (mic) "allowed" else "not allowed"}. Raw+ keyboard ${if (enabled) "enabled" else "not enabled"}."
    }

    private fun checkSpeechSupport() {
        val generation = ++diagnosticGeneration
        diagnostics?.destroy()
        diagnostics = null
        try {
            if (!SpeechRecognizer.isOnDeviceRecognitionAvailable(this)) {
                speechStatus.text = "No public on-device recognizer is available. Raw+ cannot dictate on this configuration. Gboard's own voice features do not guarantee support for other keyboards."
                return
            }
            speechStatus.text = "An on-device recognizer is available. Checking en-GB…"
            if (Build.VERSION.SDK_INT < 33) {
                speechStatus.text = "An on-device recognizer is available. Language support must be tested by dictating."
                return
            }
            val engine = SpeechRecognizer.createOnDeviceSpeechRecognizer(this).also { diagnostics = it }
            engine.checkRecognitionSupport(recognitionIntent(), mainExecutor, object : RecognitionSupportCallback {
                override fun onSupportResult(support: RecognitionSupport) {
                    if (generation != diagnosticGeneration) return
                    val installed = support.installedOnDeviceLanguages.any { it.equals("en-GB", true) }
                    speechStatus.text = when {
                        installed -> "en-GB is installed for on-device recognition. Test dictation with networking disabled to verify this device."
                        support.pendingOnDeviceLanguages.any { it.equals("en-GB", true) } -> "en-GB is downloading. Recheck after it finishes."
                        support.supportedOnDeviceLanguages.any { it.equals("en-GB", true) } -> "en-GB is supported but not installed. Request a speech model download."
                        else -> "Recognizer available, but en-GB is not reported installed. Check speech settings and try downloading the model."
                    }
                }
                override fun onError(error: Int) {
                    if (generation == diagnosticGeneration) speechStatus.text = "Recognizer available, but it could not report language support, error $error. A real dictation test is still needed."
                }
            })
        } catch (_: RuntimeException) {
            speechStatus.text = "Could not check on-device speech support. Check the device's speech recognition settings."
        }
    }

    private fun recognitionIntent() = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-GB")
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)

    override fun onDestroy() {
        diagnosticGeneration++
        diagnostics?.destroy()
        diagnostics = null
        super.onDestroy()
    }

    private fun dp(value: Int) = Ui.dp(this, value)
}
