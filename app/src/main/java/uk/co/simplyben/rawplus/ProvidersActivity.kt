@file:Suppress("SetTextI18n")
package uk.co.simplyben.rawplus

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView

class ProvidersActivity : Activity() {
    private val llama by lazy { LlamaEditor(this) { EditingRules.load(this) } }
    private var llamaCreated = false
    private lateinit var models: LocalModelStore
    private lateinit var settings: EditingSettings
    private lateinit var selected: EditingProvider
    private lateinit var status: TextView
    private lateinit var localStatus: TextView
    private lateinit var progress: ProgressBar
    private lateinit var cancel: Button
    private lateinit var save: Button
    private val modelButtons = mutableListOf<Button>()
    private val credentialFields = mutableListOf<EditText>()
    private var busy = false
    private var generation = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        settings = EditingSettings(this)
        models = LocalModelStore(this)
        selected = settings.provider
        val column = Ui.column(this).apply {
            isFocusableInTouchMode = true
            setPadding(dp(20), dp(16), dp(20), dp(24))
            setBackgroundColor(Ui.paper(this@ProvidersActivity))
        }
        val scroll = ScrollView(this).apply { addView(column) }
        scroll.setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime() or WindowInsets.Type.displayCutout())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        setContentView(scroll)
        scroll.post { Ui.applyWindowAppearance(window, this) }
        fun paragraph(text: String) = column.addView(Ui.text(this, text), row())
        column.addView(Ui.button(this, "Back") { finish() }, row())
        column.addView(Ui.heading(this, "Editing providers", 26f), row())
        paragraph("Raw always works locally. Choose which provider handles Raw+ and Polished. Nothing switches providers automatically, and dictation never requests an AI edit by itself.")
        val choices = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        val ids = EditingProvider.entries.associateWith { View.generateViewId() }
        EditingProvider.entries.forEach { provider ->
            choices.addView(RadioButton(this).apply {
                id = ids.getValue(provider)
                text = when (provider) {
                    EditingProvider.OPENAI -> "OpenAI · your API key"
                    EditingProvider.LLAMA -> "llama.cpp · on this device · experimental"
                    EditingProvider.BACKEND -> "Your own backend · advanced"
                }
                setTextColor(Ui.ink(this@ProvidersActivity))
            })
        }
        column.addView(choices, row())
        val cloudInfo = Ui.text(this, "")
        column.addView(cloudInfo, row())
        fun credential(hintText: String) = EditText(this).apply {
            hint = hintText
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            imeOptions = android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING or android.view.inputmethod.EditorInfo.IME_ACTION_DONE
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
            isSaveEnabled = false
            setTextColor(Ui.ink(this@ProvidersActivity))
            setHintTextColor(Ui.hint(this@ProvidersActivity))
            credentialFields.add(this)
            setOnFocusChangeListener { _, _ -> protectCredentials() }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = protectCredentials()
                override fun afterTextChanged(s: Editable?) = Unit
            })
        }
        val apiKey = credential("OpenAI API key · starts with sk-")
        column.addView(apiKey, row())
        val backend = Ui.column(this)
        val url = EditText(this).apply {
            hint = "Backend URL · https://your-server.example"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
            isSaveEnabled = false
            setText(settings.savedUrl(EditingProvider.BACKEND))
            setTextColor(Ui.ink(this@ProvidersActivity))
            setHintTextColor(Ui.hint(this@ProvidersActivity))
        }
        val token = credential("Backend access token · not an OpenAI key")
        url.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { token.text.clear() }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        backend.addView(url, row())
        backend.addView(token, row())
        column.addView(backend, row())
        val consent = CheckBox(this).apply {
            isChecked = settings.savedConsent(selected)
            isSaveEnabled = false
            setTextColor(Ui.ink(this@ProvidersActivity))
        }
        column.addView(consent, row())
        val local = Ui.column(this)
        local.addView(Ui.heading(this, "Local model", 20f), row())
        local.addView(Ui.text(this, "${LocalModelStore.NAME}, 1.12 GB download, Apache 2.0. This model runs inside Raw+ through llama.cpp. Your text stays on this phone, without an API key, API credit or usage metrics."), row())
        local.addView(Ui.text(this, "This is a Pixel test. The model uses extra RAM and may be slow or warm the phone during CPU editing. Start with a short paragraph. Raw remains available on failure; another provider is never used automatically."), row())
        local.addView(Ui.text(this, "Prefer Wi-Fi. Keep this screen open during download or import. Leaving cancels setup and discards the partial file. Alternatively, download the exact model in your browser and import it. Imports must match its verified checksum."), row())
        localStatus = Ui.text(this, models.statusText(), 14f)
        localStatus.isSaveEnabled = false
        local.addView(localStatus, row())
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            visibility = View.GONE
            contentDescription = "Model setup progress"
        }
        local.addView(progress, row())
        fun modelButton(label: String, action: () -> Unit) = Ui.button(this, label, click = action).also {
            modelButtons.add(it)
            local.addView(it, row())
        }
        modelButton("Download model · 1.12 GB") {
            val task = beginLocalTask("Downloading the model. Keep this screen open. Nothing from your draft is sent.")
            models.download({ bytes, total -> showProgress(task, "Downloading", bytes, total) }) { result ->
                finishLocalTask(task, result, "Model downloaded and verified. Test the sample before using it in the keyboard.")
            }
        }
        modelButton("Download in browser instead") {
            try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(ModelFilePolicy.MODEL_PAGE))) }
            catch (_: Exception) { localStatus.text = "No browser is available. Use Download model or import the exact model file." }
        }
        modelButton("Import downloaded model file") {
            try {
                startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }, IMPORT_MODEL)
            } catch (_: Exception) { localStatus.text = "Could not open the Android file picker. Try the direct model download." }
        }
        modelButton("Test llama.cpp with sample text") {
            if (models.modelFile() == null) {
                localStatus.text = "Download or import the model first. Raw still works."
            } else {
                val task = beginLocalTask("Testing a short sample on this phone. The first load may take a while.")
                llamaCreated = true
                val sample = EditRequest(System.nanoTime(), EditMode.RAW_PLUS,
                    "um I guess I guess this is kind of useful but I'm not sure yet")
                llama.edit(sample) { result ->
                    if (task == generation && !isDestroyed) {
                        setBusy(false)
                        localStatus.text = result.fold(
                            { "Settings sample result:\n$it\n\nNext, select Use llama.cpp and test Raw+ inside LinkedIn. Keyboard speed and stability still need that test." },
                            { it.message ?: "The model could not edit the sample. Raw is still available." }
                        )
                    }
                }
            }
        }
        modelButton("Delete local model") {
            val task = beginLocalTask("Removing the local model…")
            models.delete { result ->
                finishLocalTask(task, result, "Local model deleted. Raw still works. If llama.cpp is selected, download or import before editing again.")
            }
        }
        cancel = Ui.button(this, "Cancel model setup or sample") {
            cancelLocalTask()
            localStatus.text = "Cancelled. ${models.statusText()}"
        }.apply { isEnabled = false }
        local.addView(cancel, row())
        column.addView(local, row())
        status = Ui.text(this, "Active provider: ${settings.provider.label}", 14f)
        column.addView(status, row())
        save = Ui.button(this, "Use provider", true) {
            try {
                if (selected == EditingProvider.LLAMA) {
                    require(models.modelFile() != null) { "Download or import the model before selecting llama.cpp. Raw still works." }
                    settings.select(selected)
                } else settings.save(selected, url.text.toString(),
                    if (selected == EditingProvider.OPENAI) apiKey.text.toString() else token.text.toString(), consent.isChecked)
                apiKey.text.clear()
                token.text.clear()
                column.requestFocus()
                status.text = if (selected == EditingProvider.LLAMA)
                    "llama.cpp selected for a local keyboard test. Your saved cloud credential is retained. No sample or AI edit was run."
                else if (consent.isChecked) "${selected.label} selected. Credential saved securely. No API request was made."
                else "External AI sharing is off and saved cloud credentials were removed. Raw still works."
            } catch (failure: Exception) {
                status.text = if (failure is IllegalArgumentException) failure.message else "Could not save provider settings securely. Please try again."
            }
        }
        column.addView(save, row())
        column.addView(Ui.button(this, "Forget saved cloud credential") {
            try {
                settings.forgetCredentials()
                apiKey.text.clear()
                token.text.clear()
                consent.isChecked = false
                column.requestFocus()
                status.text = "Saved cloud credentials removed. The provider selection and local test history are unchanged."
            } catch (_: Exception) { status.text = "Could not remove saved credentials. Please try again." }
        }, row())
        paragraph("Saved credentials are never displayed. Leave the field blank to reuse a saved key only for its original provider and address. Selecting llama.cpp retains that key. Saving a different cloud provider replaces the single saved cloud credential.")
        paragraph("Screenshots work here unless a credential field is focused or has unsaved contents. Save or clear it, then tap elsewhere. Text for AI editing comes only from your dictated draft; surrounding app text is never read.")
        fun showProvider() {
            val direct = selected == EditingProvider.OPENAI
            val remote = selected.external
            apiKey.visibility = if (direct) View.VISIBLE else View.GONE
            backend.visibility = if (selected == EditingProvider.BACKEND) View.VISIBLE else View.GONE
            consent.visibility = if (remote) View.VISIBLE else View.GONE
            cloudInfo.visibility = if (remote) View.VISIBLE else View.GONE
            local.visibility = if (remote) View.GONE else View.VISIBLE
            cloudInfo.text = if (direct)
                "OpenAI receives your original dictated transcript over HTTPS. API credit is billed separately from ChatGPT. Your key is encrypted on the phone and kept out of the APK."
            else "Your backend receives the original transcript and sends it to OpenAI. Enter the backend's separate access token. Remote backends require HTTPS; loopback HTTP is only for USB development."
            consent.text = if (direct) "Allow Raw+ and Polished to send my original dictated transcript directly to OpenAI."
                else "Allow Raw+ and Polished to send my original dictated transcript to my backend and OpenAI."
            save.text = if (remote) "Save and use ${selected.label}" else "Use llama.cpp · experimental"
        }
        choices.check(ids.getValue(selected))
        choices.setOnCheckedChangeListener { _, id ->
            cancelLocalTask()
            selected = ids.entries.first { it.value == id }.key
            apiKey.text.clear()
            token.text.clear()
            consent.isChecked = settings.savedConsent(selected)
            status.text = "Active provider: ${settings.provider.label}. Save below to use ${selected.label}."
            localStatus.text = models.statusText()
            showProvider()
            column.requestFocus()
        }
        showProvider()
        column.requestFocus()
        protectCredentials()
    }

    private fun beginLocalTask(message: String): Long {
        cancelLocalTask()
        localStatus.text = message
        setBusy(true)
        return generation
    }

    private fun showProgress(task: Long, action: String, bytes: Long, total: Long) {
        if (task != generation || isDestroyed) return
        progress.isIndeterminate = false
        progress.progress = (bytes * 1000 / total.coerceAtLeast(1)).toInt()
        localStatus.text = "$action · ${bytes / 1_000_000} / ${total / 1_000_000} MB. Keep this screen open."
    }

    private fun finishLocalTask(task: Long, result: Result<Unit>, success: String) {
        if (task != generation || isDestroyed) return
        setBusy(false)
        localStatus.text = result.fold({ success }, { it.message ?: "Model setup failed. Raw is still available." })
    }

    private fun setBusy(value: Boolean) {
        busy = value
        if (value) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        modelButtons.forEach { it.isEnabled = !value }
        cancel.isEnabled = value
        save.isEnabled = !value
        progress.visibility = if (value) View.VISIBLE else View.GONE
        progress.isIndeterminate = value
    }

    private fun cancelLocalTask() {
        generation++
        models.cancel()
        if (llamaCreated) llama.cancel()
        if (::save.isInitialized) setBusy(false)
    }

    @Deprecated("Platform activity result API is retained for this dependency-light prototype.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != IMPORT_MODEL || resultCode != RESULT_OK || selected != EditingProvider.LLAMA) return
        val uri = data?.data ?: return
        val task = beginLocalTask("Importing and verifying the model. Keep this screen open.")
        models.importModel(uri, { bytes, total -> showProgress(task, "Importing", bytes, total) }) { result ->
            finishLocalTask(task, result, "Model imported and verified. Test the sample before using it in the keyboard.")
        }
    }

    private fun protectCredentials() {
        if (credentialFields.any { it.hasFocus() || it.length() > 0 }) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    override fun onStop() {
        val interrupted = busy
        cancelLocalTask()
        if (interrupted) localStatus.text = "Setup or sample stopped when this screen closed. ${models.statusText()}"
        super.onStop()
    }

    override fun onDestroy() {
        generation++
        models.close()
        if (llamaCreated) llama.close()
        super.onDestroy()
    }

    private fun row() = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) }
    private fun dp(value: Int) = Ui.dp(this, value)
    companion object { private const val IMPORT_MODEL = 4 }
}
