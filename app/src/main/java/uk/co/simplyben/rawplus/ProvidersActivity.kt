@file:Suppress("SetTextI18n")
package uk.co.simplyben.rawplus

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.concurrent.Executors

/** Provider selection never signs in, sends a transcript or requests an edit. */
class ProvidersActivity : Activity() {
    private lateinit var settings: EditingSettings
    private lateinit var auth: ChatGptAuth
    private lateinit var selected: EditingProvider
    private lateinit var activeStatus: TextView
    private lateinit var status: TextView
    private lateinit var chatStatus: TextView
    private lateinit var accountPicker: Spinner
    private lateinit var modelPicker: Spinner
    private lateinit var useProvider: Button
    private lateinit var useAccount: Button
    private lateinit var signInAgain: Button
    private lateinit var loadModels: Button
    private lateinit var saveModel: Button
    private lateinit var signOut: Button
    private val chatButtons = mutableListOf<Button>()
    private val credentialFields = mutableListOf<EditText>()
    private var pickerAccounts = emptyList<ChatGptAccount>()
    private var pickerModels = emptyList<ChatGptModel>()
    private var modelsAccountId: String? = null
    private var busy = false
    private var generation = 0L
    private val cleanupWorker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        settings = EditingSettings(this)
        auth = ChatGptAuth(this)
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
        paragraph("Raw stays on this phone. Choose a provider for Raw+ and Polished. An edit starts only when you request it in the keyboard. Providers never switch automatically.")
        val choices = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        val ids = EditingProvider.entries.associateWith { View.generateViewId() }
        EditingProvider.entries.forEach { provider ->
            choices.addView(RadioButton(this).apply {
                id = ids.getValue(provider)
                text = when (provider) {
                    EditingProvider.CHATGPT -> "ChatGPT plan · sign in"
                    EditingProvider.OPENAI -> "OpenAI API · your API key"
                    EditingProvider.BACKEND -> "Your own backend · advanced"
                }
                setTextColor(Ui.ink(this@ProvidersActivity))
                isSaveEnabled = false
            })
        }
        choices.isSaveEnabled = false
        column.addView(choices, row())
        activeStatus = Ui.text(this, "", 14f).apply { isSaveEnabled = false }
        column.addView(activeStatus, row())

        val chat = Ui.column(this)
        chat.addView(Ui.heading(this, "ChatGPT plan", 20f), row())
        chat.addView(Ui.text(this, "Sign in with your existing ChatGPT account. No API key is needed. Editing shares your ChatGPT plan's usage limits. Extra credits may be used if you have enabled them in ChatGPT."), row())
        chat.addView(Ui.text(this, "OpenAI receives the original dictated transcript only when you request Raw+ or Polished. Raw+ does not read your ChatGPT conversations or stored history. Signing in, loading models and choosing a provider do not send your draft."), row())
        chatStatus = Ui.text(this, "", 14f).apply { isSaveEnabled = false }
        chat.addView(chatStatus, row())
        fun chatButton(label: String, action: () -> Unit) = Ui.button(this, label, click = action).also {
            chatButtons.add(it)
            chat.addView(it, row())
        }
        chatButton("Continue with ChatGPT") {
            cancelAccountTask()
            startActivity(Intent(this, ChatGptSignInActivity::class.java))
        }
        accountPicker = Spinner(this).apply {
            contentDescription = "Saved ChatGPT accounts"
            isSaveEnabled = false
        }
        chat.addView(accountPicker, row())
        useAccount = chatButton("Use selected account") {
            val account = pickerAccounts.getOrNull(accountPicker.selectedItemPosition - 1)
            if (account == null) {
                status.text = "Choose a saved account first."
            } else try {
                cancelAccountTask()
                auth.selectAccount(account.id)
                clearModels()
                refreshAccounts()
                status.text = "Account selected. Choose a model, then tap Use ChatGPT plan. No edit was requested."
            } catch (_: Exception) { status.text = "Could not select that account. Try signing in again." }
        }
        signInAgain = chatButton("Sign in again to selected account") {
            val account = auth.selectedAccount()
            if (account == null) {
                status.text = "Continue with ChatGPT to register an account first."
            } else {
                cancelAccountTask()
                startActivity(Intent(this, ChatGptSignInActivity::class.java)
                    .putExtra(ChatGptSignInActivity.EXTRA_ACCOUNT_ID, account.id))
            }
        }
        loadModels = chatButton("Load available models") {
            val account = auth.selectedAccount()
            if (account?.connected != true) {
                status.text = "Sign in to the selected account first."
            } else {
                val task = beginAccountTask("Loading the models available to this ChatGPT account. Your draft is not sent.")
                auth.listModels { result ->
                    if (task == generation && !isDestroyed) {
                        setBusy(false)
                        result.fold({ models ->
                            modelsAccountId = account.id
                            pickerModels = models
                            setChoices(modelPicker, listOf("Choose a model") + models.map { it.displayName })
                            chatStatus.text = if (models.isEmpty()) "No editing models were returned for this account."
                                else "Choose a model, then save the choice. No model is selected automatically."
                            refreshButtons()
                        }, { failure ->
                            clearModels()
                            chatStatus.text = failure.message ?: "Could not load available models. Your draft is unchanged."
                        })
                    }
                }
            }
        }
        modelPicker = Spinner(this).apply {
            contentDescription = "Available ChatGPT models"
            isSaveEnabled = false
        }
        chat.addView(modelPicker, row())
        saveModel = chatButton("Save selected model") {
            val model = pickerModels.getOrNull(modelPicker.selectedItemPosition - 1)
            if (model == null || modelsAccountId != auth.selectedAccount()?.id) {
                status.text = "Load models for the selected account and choose one first."
            } else try {
                auth.selectModel(model.slug)
                refreshAccounts()
                status.text = "Model choice saved. Tap Use ChatGPT plan to select this provider. No edit was requested."
            } catch (_: Exception) { status.text = "Could not save that model choice. Load the available models again." }
        }
        chatButton("Manage ChatGPT usage") {
            try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://chatgpt.com/settings/usage"))) }
            catch (_: Exception) { status.text = "No browser is available. Open ChatGPT settings to manage usage." }
        }
        signOut = chatButton("Sign out of selected ChatGPT account") {
            val account = auth.selectedAccount() ?: return@chatButton
            AlertDialog.Builder(this)
                .setTitle("Sign out of ChatGPT?")
                .setMessage("Raw+ will revoke this account's access and remove its saved authorization. Your OpenAI API key and local test history will stay on this phone.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Sign out") { _, _ ->
                    val task = beginAccountTask("Signing out of the selected ChatGPT account…")
                    auth.signOut { result ->
                        if (task == generation && !isDestroyed) {
                            setBusy(false)
                            clearModels()
                            refreshAccounts()
                            status.text = result.fold(
                                { "Signed out. Your API key and local test history are unchanged." },
                                { it.message ?: "Sign-out could not be confirmed. Check the account status above and try again." }
                            )
                        }
                    }
                }.show()
        }
        column.addView(chat, row())

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
        status = Ui.text(this, "", 14f).apply { isSaveEnabled = false }
        column.addView(status, row())
        useProvider = Ui.button(this, "Use provider", true) {
            try {
                if (selected == EditingProvider.CHATGPT) {
                    val account = auth.selectedAccount()
                    require(account?.connected == true && account.planEnabled && !account.selectedModel.isNullOrBlank()) {
                        "Sign in, load the available models and save a model choice before using ChatGPT."
                    }
                    settings.select(EditingProvider.CHATGPT)
                    status.text = "ChatGPT plan selected. Editing shares your ChatGPT usage limits. No edit was requested."
                } else {
                    settings.save(selected, url.text.toString(),
                        if (selected == EditingProvider.OPENAI) apiKey.text.toString() else token.text.toString(), consent.isChecked)
                    status.text = if (consent.isChecked) "${selected.label} selected. Credential saved securely. No API request was made."
                        else "API sharing is off and the saved API credential was removed. Raw still works."
                }
                apiKey.text.clear()
                token.text.clear()
                column.requestFocus()
                refreshAccounts()
            } catch (failure: Exception) {
                status.text = if (failure is IllegalArgumentException) failure.message else "Could not save provider settings securely. Please try again."
            }
        }
        column.addView(useProvider, row())
        column.addView(Ui.button(this, "Forget saved API key or backend token") {
            try {
                settings.forgetCredentials()
                apiKey.text.clear()
                token.text.clear()
                consent.isChecked = false
                column.requestFocus()
                status.text = "Saved API credentials removed. ChatGPT authorization, provider selection and local test history are unchanged."
                refreshAccounts()
            } catch (_: Exception) { status.text = "Could not remove saved API credentials. Please try again." }
        }, row())
        paragraph("Saved API credentials are never displayed. Leave the field blank to reuse a saved key only for its original provider and address. Choosing ChatGPT retains that key. Saving a different API provider replaces the single saved API credential.")
        paragraph("Screenshots work here unless an API credential field is focused or has unsaved contents. Save or clear it, then tap elsewhere. Text for AI editing comes only from your dictated draft; surrounding app text is never read.")
        addRetiredModelCleanup(column)
        fun showProvider() {
            val accountProvider = selected == EditingProvider.CHATGPT
            val direct = selected == EditingProvider.OPENAI
            apiKey.visibility = if (direct) View.VISIBLE else View.GONE
            backend.visibility = if (selected == EditingProvider.BACKEND) View.VISIBLE else View.GONE
            consent.visibility = if (accountProvider) View.GONE else View.VISIBLE
            cloudInfo.visibility = if (accountProvider) View.GONE else View.VISIBLE
            chat.visibility = if (accountProvider) View.VISIBLE else View.GONE
            cloudInfo.text = if (direct)
                "OpenAI receives your original dictated transcript over HTTPS. API credit is billed separately from ChatGPT. Your key is encrypted on the phone and kept out of the APK."
            else "Your backend receives the original transcript and sends it to OpenAI. Enter the backend's separate access token. Remote backends require HTTPS; loopback HTTP is only for USB development."
            consent.text = if (direct) "Allow Raw+ and Polished to send my original dictated transcript directly to OpenAI."
                else "Allow Raw+ and Polished to send my original dictated transcript to my backend and OpenAI."
            useProvider.text = if (accountProvider) "Use ChatGPT plan" else "Save and use ${selected.label}"
            refreshButtons()
        }
        choices.check(ids.getValue(selected))
        choices.setOnCheckedChangeListener { _, id ->
            cancelAccountTask()
            selected = ids.entries.first { it.value == id }.key
            apiKey.text.clear()
            token.text.clear()
            consent.isChecked = settings.savedConsent(selected)
            status.text = "Tap Use below to select ${selected.label}. No provider has changed yet."
            showProvider()
            column.requestFocus()
        }
        clearModels()
        refreshAccounts()
        showProvider()
        column.requestFocus()
        protectCredentials()
    }

    private fun setChoices(spinner: Spinner, labels: List<String>) {
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, labels).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
    }

    /** Reads only local account metadata. Never resets an unsaved API credential or starts network work. */
    private fun refreshAccounts() {
        val account = auth.selectedAccount()
        pickerAccounts = auth.accounts()
        setChoices(accountPicker, listOf("Choose a saved account") + pickerAccounts.map { it.label })
        val current = pickerAccounts.indexOfFirst { it.id == account?.id }
        accountPicker.setSelection(if (current < 0) 0 else current + 1)
        if (modelsAccountId != account?.id || account?.connected != true) clearModels()
        chatStatus.text = when {
            account == null -> "No ChatGPT account is connected. Continue with ChatGPT to sign in."
            !account.connected -> "${account.label}\nSigned out. Sign in again before editing."
            !account.planEnabled -> "${account.label}\nThis account has not authorized access through a ChatGPT plan. Sign in again or use another provider."
            account.selectedModel.isNullOrBlank() -> "${account.label}\nConnected. Load the available models and save your choice."
            else -> "${account.label}\nModel choice: ${account.selectedModel}. Editing uses this account's shared ChatGPT limits."
        }
        refreshActiveStatus(account)
        refreshButtons()
    }

    private fun refreshActiveStatus(account: ChatGptAccount?) {
        activeStatus.text = if (settings.provider == EditingProvider.CHATGPT) {
            if (account?.connected == true && account.planEnabled && !account.selectedModel.isNullOrBlank())
                "Using ChatGPT plan · ${account.label} · ${account.selectedModel}"
            else "Selected provider: ChatGPT plan. Complete sign-in and model selection before editing. Raw still works."
        } else "Active provider: ${settings.provider.label}"
    }

    private fun refreshButtons() {
        if (!::useProvider.isInitialized) return
        val account = auth.selectedAccount()
        val connected = account?.connected == true
        chatButtons.forEach { it.isEnabled = !busy }
        useAccount.isEnabled = !busy && pickerAccounts.isNotEmpty()
        signInAgain.isEnabled = !busy && account != null
        loadModels.isEnabled = !busy && connected
        saveModel.isEnabled = !busy && connected && pickerModels.isNotEmpty()
        signOut.isEnabled = !busy && connected
        accountPicker.isEnabled = !busy && pickerAccounts.isNotEmpty()
        modelPicker.isEnabled = !busy && pickerModels.isNotEmpty()
        useProvider.isEnabled = !busy && (selected != EditingProvider.CHATGPT ||
            (connected && account?.planEnabled == true && !account.selectedModel.isNullOrBlank()))
    }

    private fun clearModels() {
        pickerModels = emptyList()
        modelsAccountId = null
        if (::modelPicker.isInitialized) setChoices(modelPicker, listOf("Load available models first"))
    }

    private fun beginAccountTask(message: String): Long {
        cancelAccountTask()
        chatStatus.text = message
        setBusy(true)
        return generation
    }

    private fun setBusy(value: Boolean) {
        busy = value
        refreshButtons()
    }

    private fun cancelAccountTask() {
        generation++
        if (::auth.isInitialized) auth.cancel()
        setBusy(false)
    }

    private fun addRetiredModelCleanup(column: LinearLayout) {
        val directory = noBackupFilesDir.toPath().resolve("local-model")
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) return
        column.addView(Ui.heading(this, "Downloaded local model", 20f), row())
        val info = Ui.text(this, "Local editing has been removed. You can delete its downloaded model to reclaim phone storage. Your test history and credentials are kept.", 14f)
        column.addView(info, row())
        val remove = Ui.button(this, "Delete downloaded local model") {}
        remove.setOnClickListener {
            remove.isEnabled = false
            info.text = "Deleting the retired local model…"
            cleanupWorker.execute {
                val result = runCatching {
                    // No recursive walk, and no symlink traversal. Only the old app-owned directory is touched.
                    check(!Files.isSymbolicLink(directory) && Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS))
                    val entries = Files.newDirectoryStream(directory).use { it.toList() }
                    check(entries.all { !Files.isDirectory(it, LinkOption.NOFOLLOW_LINKS) })
                    entries.forEach { Files.deleteIfExists(it) }
                    Files.deleteIfExists(directory)
                }
                main.post {
                    if (!isDestroyed) {
                        info.text = if (result.isSuccess) "Downloaded local model deleted. Test history and credentials are unchanged."
                            else "Could not finish deleting the old model. Remaining files were left in app storage."
                        remove.isEnabled = result.isFailure
                    }
                }
            }
        }
        column.addView(remove, row())
    }

    private fun protectCredentials() {
        if (credentialFields.any { it.hasFocus() || it.length() > 0 }) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    override fun onResume() {
        super.onResume()
        if (::auth.isInitialized) refreshAccounts()
    }

    override fun onStop() {
        val interrupted = busy
        cancelAccountTask()
        if (interrupted) chatStatus.text = "Account operation stopped when this screen closed. Recheck the status when you return."
        super.onStop()
    }

    override fun onDestroy() {
        generation++
        if (::auth.isInitialized) auth.close()
        cleanupWorker.shutdownNow()
        super.onDestroy()
    }

    private fun row() = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) }
    private fun dp(value: Int) = Ui.dp(this, value)
}
