@file:Suppress("SetTextI18n")
package uk.co.simplyben.rawplus

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import java.util.UUID
import java.util.concurrent.Executors
import uk.co.simplyben.rawplus.speech.LocalDictation

class RawPlusIme : InputMethodService(), LocalDictation.Listener {
    private val draft = Draft()
    private val editor = EditClient { EditingRules.load(this) }
    private val localEditor = lazy { LlamaEditor(this) { EditingRules.load(this) } }
    private var resultPromptVersion = EditProtocol.PROMPT_VERSION
    private val history by lazy { HistoryStore(applicationContext) }
    private val historyExecutor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var historySession = UUID.randomUUID().toString()
    private var historyWarning = false
    private var destroyed = false
    private lateinit var dictation: LocalDictation
    private lateinit var panel: KeyboardPanel
    private lateinit var status: TextView
    private lateinit var preview: CursorPreview
    private lateinit var previewLabel: TextView
    private lateinit var wordCount: TextView
    private lateinit var previewScroll: FollowingScrollView
    private lateinit var latest: Button
    private lateinit var expand: ImageButton
    private lateinit var microphone: Button
    private lateinit var cancel: ImageButton
    private lateinit var insert: Button
    private lateinit var retry: ImageButton
    private lateinit var keep: Button
    private val modeButtons = mutableMapOf<EditMode, Button>()
    private var captureState = LocalDictation.State.IDLE
    private var activeEditor = false
    private var sensitive = true
    private var showOriginal = false
    private var expanded = false
    private var compactLayout = false
    private var popup: PopupMenu? = null
    private var notice = "Dictate on device. Choose Raw+ or Polished only when you want an AI edit."

    override fun onCreate() {
        setTheme(R.style.KeyboardTheme)
        super.onCreate()
        dictation = LocalDictation(this, this)
    }

    // Expansion grows our preview inside the IME. Never use Android's extracted host editor.
    override fun onEvaluateFullscreenMode() = false

    override fun onCreateInputView(): View {
        window?.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        if (::panel.isInitialized) panel.keepScreenOn = false
        popup?.dismiss()
        panel = KeyboardPanel(this).apply { expanded = this@RawPlusIme.expanded }

        val header = Ui.row(this).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(Ui.heading(this, "Raw+ Keyboard", 16f).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, -2, 1f))
        expand = Ui.iconButton(this, R.drawable.ic_expand, "Expand preview") {
            expanded = !expanded
            panel.expanded = expanded
            updateExpandButton()
        }
        header.addView(expand, LinearLayout.LayoutParams(dp(48), dp(48)))
        val settings = Ui.iconButton(this, R.drawable.ic_settings, "Keyboard options") { }
        settings.setOnClickListener { showOptions(settings) }
        header.addView(settings, LinearLayout.LayoutParams(dp(48), dp(48)))
        panel.addView(header, LinearLayout.LayoutParams(-1, dp(48)))

        val modes = Ui.row(this)
        modeButtons.clear()
        EditMode.entries.forEachIndexed { index, mode ->
            val button = Ui.button(this, mode.label) { chooseMode(mode) }.apply {
                setSingleLine()
                setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL))
            }
            modeButtons[mode] = button
            modes.addView(button, LinearLayout.LayoutParams(0, -1, 1f).apply {
                if (index > 0) marginStart = dp(6)
            })
        }
        panel.addView(modes, LinearLayout.LayoutParams(-1, dp(44)).apply { bottomMargin = dp(8) })

        val card = Ui.column(this).apply { background = Ui.background(Ui.surface(this@RawPlusIme), this@RawPlusIme) }
        val previewHeader = Ui.row(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), 0, dp(8), 0)
        }
        previewLabel = Ui.text(this, "Raw · on device", 12f).apply {
            setTextColor(Ui.muted(this@RawPlusIme))
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        wordCount = Ui.text(this, "", 12f).apply { setTextColor(Ui.muted(this@RawPlusIme)) }
        latest = Ui.button(this, "Latest ↓") { previewScroll.followLatest() }.apply {
            textSize = 12f
            setSingleLine()
            visibility = View.GONE
        }
        previewHeader.addView(previewLabel, LinearLayout.LayoutParams(0, -2, 1f))
        previewHeader.addView(wordCount, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) })
        previewHeader.addView(latest, LinearLayout.LayoutParams(dp(78), dp(48)))
        card.addView(previewHeader, LinearLayout.LayoutParams(-1, dp(48)))
        preview = CursorPreview(this).apply {
            textSize = 17f
            setTextColor(Ui.ink(this@RawPlusIme))
            setPadding(dp(12), 0, dp(12), dp(12))
            setLineSpacing(dp(3).toFloat(), 1f)
            isFocusable = false
            onCaretTap = { offset -> placeDictationCursor(offset) }
        }
        previewScroll = FollowingScrollView(this).apply {
            addView(preview)
            isFillViewport = true
            onFollowingChanged = { following -> latest.visibility = if (following || compactLayout) View.GONE else View.VISIBLE }
        }
        card.addView(previewScroll, LinearLayout.LayoutParams(-1, 0, 1f))
        panel.addView(card, LinearLayout.LayoutParams(-1, 0, 1f))

        val feedback = Ui.row(this).apply { gravity = Gravity.CENTER_VERTICAL }
        status = Ui.text(this, notice, 12f).apply {
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            setTextColor(Ui.muted(this@RawPlusIme))
            maxLines = 3
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER_VERTICAL
        }
        retry = Ui.iconButton(this, R.drawable.ic_retry, "Retry AI edit with selected provider") { requestEdit(); render() }
        feedback.addView(status, LinearLayout.LayoutParams(0, -2, 1f))
        feedback.addView(retry, LinearLayout.LayoutParams(dp(48), dp(48)))
        panel.addView(feedback, LinearLayout.LayoutParams(-1, dp(48)))

        keep = Ui.button(this, "Keep words", true) {
            if (draft.insert(draft.unconfirmed)) {
                draft.select(EditMode.RAW)
                recordHistory("dictated", EditMode.RAW, draft.rawText())
                notice = "Unfinished words kept in Raw. Check them before inserting."
            } else notice = "Draft limit reached. Insert or clear the existing text first."
            render()
        }.apply {
            contentDescription = "Keep unfinished words after checking them"
            setSingleLine()
        }

        val actions = Ui.row(this)
        microphone = Ui.button(this, "Dictate", true) {
            if (captureState != LocalDictation.State.IDLE) dictation.stop()
            else if (activeEditor && !sensitive && draft.unconfirmed.isBlank()) {
                cancelEditing()
                // Each recording returns to local Raw. An AI edit always needs another explicit tap.
                draft.select(EditMode.RAW)
                showOriginal = false
                previewScroll.followLatest()
                dictation.start()
            }
        }.apply { textSize = 16f; setSingleLine(); ellipsize = TextUtils.TruncateAt.END }
        cancel = Ui.iconButton(this, R.drawable.ic_close, "Cancel unfinished dictation. Keep confirmed words") {
            if (draft.pending) {
                cancelEditing()
                draft.select(EditMode.RAW)
                showOriginal = false
                notice = "Editing cancelled. Original words kept in Raw."
            } else {
                dictation.cancel()
                draft.partial = ""
                draft.unconfirmed = ""
                notice = "Dictation cancelled. Confirmed words kept."
            }
            render()
        }
        insert = Ui.button(this, "Insert", true) { insertSelectedText() }.apply {
            textSize = 14f
            setSingleLine()
        }
        actions.addView(microphone, LinearLayout.LayoutParams(0, -1, 1f))
        actions.addView(cancel, LinearLayout.LayoutParams(dp(48), -1).apply { marginStart = dp(6) })
        actions.addView(insert, LinearLayout.LayoutParams(dp(128), -1).apply { marginStart = dp(8) })
        actions.addView(keep, LinearLayout.LayoutParams(dp(112), -1).apply { marginStart = dp(8) })
        panel.addView(actions, LinearLayout.LayoutParams(-1, dp(56)))

        // A short landscape window uses the same mode choices in the cog menu.
        panel.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            val compact = panel.height - panel.paddingTop - panel.paddingBottom < dp(310)
            compactLayout = compact
            val visibility = if (compact) View.GONE else View.VISIBLE
            if (modes.visibility != visibility) modes.visibility = visibility
            val statusLines = if (compact) 2 else 3
            if (status.maxLines != statusLines) status.maxLines = statusLines
            fun rowHeight(view: View, value: Int) {
                if (view.layoutParams.height != dp(value)) {
                    view.layoutParams = view.layoutParams.apply { height = dp(value) }
                }
            }
            rowHeight(previewHeader, if (compact) 32 else 48)
            rowHeight(feedback, if (compact) 36 else 48)
            // Latest is still available in the cog when the compact toolbar is too short.
            latest.visibility = if (compact || previewScroll.following) View.GONE else View.VISIBLE
        }
        panel.post { window?.window?.let { Ui.applyWindowAppearance(it, this) } }
        updateExpandButton()
        render()
        return panel
    }

    private fun updateExpandButton() {
        expand.setImageResource(if (expanded) R.drawable.ic_collapse else R.drawable.ic_expand)
        val label = if (expanded) "Collapse preview" else "Expand preview"
        expand.contentDescription = label
        expand.tooltipText = label
    }

    private fun placeDictationCursor(offset: Int) {
        if (!activeEditor || sensitive || captureState != LocalDictation.State.IDLE ||
            draft.pending || draft.unconfirmed.isNotBlank() || draft.original.isBlank()) return
        previewScroll.pauseFollowing()
        if (draft.mode != EditMode.RAW && !showOriginal && draft.selectedText != null) {
            // Edited text has no reliable character-for-character map to the original.
            // Show the source first rather than guessing a position or editing AI output.
            showOriginal = true
            notice = "Original transcript shown. Tap where you want the next paragraph."
            render()
            previewScroll.reviewFromStart()
            return
        }
        draft.setCursor(offset)
        notice = "Next dictation will add a paragraph at the blinking cursor."
        render()
        preview.positionChanged()
    }

    private fun clearDraft() {
        draft.clear()
        resultPromptVersion = EditProtocol.PROMPT_VERSION
        historySession = UUID.randomUUID().toString()
    }

    private fun recordHistory(
        event: String,
        mode: EditMode,
        text: String,
        original: String = draft.original,
        session: String = historySession,
        promptVersion: String = EditProtocol.PROMPT_VERSION
    ) {
        if (destroyed || sensitive || original.isBlank()) return
        // All arguments are immutable snapshots of this keyboard's own dictated draft.
        // The epoch prevents queued events from reappearing after Clear or off/on.
        val epoch = try { history.generation } catch (_: Exception) {
            historyWarning = true
            return
        }
        historyExecutor.execute {
            val saved = runCatching {
                history.record(session, event, original, mode, text, promptVersion,
                    expectedGeneration = epoch)
            }.getOrDefault(false)
            main.post {
                if (!destroyed) {
                    historyWarning = !saved
                    render()
                }
            }
        }
    }

    private fun chooseMode(mode: EditMode) {
        if (!activeEditor || sensitive || captureState != LocalDictation.State.IDLE || draft.unconfirmed.isNotBlank()) return
        if (mode != EditMode.RAW && draft.original.isBlank()) return
        showOriginal = false
        cancelEditing()
        draft.select(mode)
        requestEdit()
        render()
        previewScroll.reviewFromStart()
    }

    private fun showOptions(anchor: View) {
        popup?.dismiss()
        popup = PopupMenu(this, anchor).also { options ->
            fun item(label: String, enabled: Boolean = true, action: () -> Unit) {
                options.menu.add(label).apply {
                    isEnabled = enabled
                    setOnMenuItemClickListener { action(); true }
                }
            }
            val canChoose = activeEditor && !sensitive && captureState == LocalDictation.State.IDLE && draft.unconfirmed.isBlank()
            EditMode.entries.forEach { mode ->
                val qualifier = if (EditingSettings(this).provider == EditingProvider.LLAMA) "llama.cpp · on device" else "uses API credit"
                item(if (mode == EditMode.RAW) "Raw · on device" else "${mode.label} · $qualifier",
                    canChoose && (mode == EditMode.RAW || draft.original.isNotBlank())) { chooseMode(mode) }
            }
            item(if (showOriginal) "Show ${draft.mode.label} preview" else "Show original transcript", draft.original.isNotBlank()) {
                showOriginal = !showOriginal
                render()
                previewScroll.reviewFromStart()
            }
            item("Jump to latest words", draft.original.isNotBlank() || draft.partial.isNotBlank()) { previewScroll.followLatest() }
            item("Dictate at end", canChoose && draft.original.isNotBlank()) {
                draft.setCursor(draft.original.length)
                showOriginal = true
                render()
                previewScroll.followLatest()
            }
            item("Clear draft", draft.original.isNotBlank() || draft.partial.isNotBlank() || draft.unconfirmed.isNotBlank()) {
                stopWork()
                clearDraft()
                showOriginal = false
                previewScroll.followLatest()
                notice = "Draft cleared. Dictate on device when ready."
                render()
            }
            item("Providers") {
                stopWork()
                startActivity(Intent(this, ProvidersActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            item("Setup") {
                stopWork()
                startActivity(Intent(this, SetupActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            item("Local test history") {
                stopWork()
                startActivity(Intent(this, HistoryActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            item("Return to Gboard") { returnToGboard() }
            item("Choose keyboard") { showKeyboardPicker() }
            options.show()
        }
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        activeEditor = false
        stopWork()
        clearDraft()
        sensitive = attribute == null || FieldPolicy.blocked(attribute.inputType, attribute.imeOptions)
        showOriginal = false
        expanded = false
        if (::panel.isInitialized) panel.expanded = false
        if (::expand.isInitialized) updateExpandButton()
        notice = if (sensitive) "Dictation is off for password, private or non-text fields. Use a normal keyboard."
            else "Raw stays on device. Tap text to place the dictation cursor. AI edits only when chosen."
        render()
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        activeEditor = true
        render()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        endEditor()
        // We never compose text. The default implementation calls finishComposingText.
    }

    override fun onFinishInput() {
        endEditor()
    }

    override fun onWindowHidden() {
        endEditor()
        super.onWindowHidden()
    }

    override fun onDestroy() {
        activeEditor = false
        destroyed = true
        if (::panel.isInitialized) panel.keepScreenOn = false
        popup?.dismiss()
        dictation.destroy()
        editor.close()
        if (localEditor.isInitialized()) localEditor.value.close()
        clearDraft()
        historyExecutor.shutdown()
        super.onDestroy()
    }

    override fun onState(state: LocalDictation.State, message: String) {
        captureState = state
        notice = message
        // Never request an AI edit from recognition callbacks.
        render()
    }

    override fun onPartial(text: String) { draft.partial = text; render() }

    override fun onFinal(text: String) {
        if (!activeEditor || sensitive) return
        cancelEditing()
        if (!draft.insert(text)) {
            dictation.cancel()
            notice = "Draft limit reached. Existing words kept. Insert or clear them before continuing."
        } else recordHistory("dictated", EditMode.RAW, draft.rawText())
        render()
    }

    override fun onInterrupted(partial: String, message: String) {
        if (!activeEditor || sensitive) return
        draft.partial = ""
        draft.unconfirmed = partial
        notice = message
        render()
    }

    private fun requestEdit() {
        if (draft.mode == EditMode.RAW) {
            notice = "Raw stays on device. Review, then tap Insert."
            return
        }
        if (!activeEditor || sensitive || captureState != LocalDictation.State.IDLE ||
            draft.unconfirmed.isNotBlank() || draft.original.isBlank()) return
        val config = EditingSettings(this).read()
        if (config == null) {
            notice = "AI editing is off. Select Raw to insert, or open Providers to configure editing."
            return
        }
        val request = draft.request()
        notice = when (config.provider) {
            EditingProvider.OPENAI -> "Editing ${draft.mode.label} with your OpenAI API credit. Your original transcript is sent."
            EditingProvider.BACKEND -> "Editing ${draft.mode.label}. Your original transcript goes to your backend and OpenAI."
            EditingProvider.LLAMA -> "Editing ${draft.mode.label} on this phone with llama.cpp. This may take a minute. No API credit used."
        }
        val completed: (Result<String>) -> Unit = completed@ { result ->
            if (!activeEditor || sensitive) return@completed
            result.onSuccess { text ->
                if (draft.accept(request, text)) {
                    resultPromptVersion = when (config.provider) {
                        EditingProvider.OPENAI -> EditProtocol.PROMPT_VERSION
                        EditingProvider.LLAMA -> "${EditProtocol.PROMPT_VERSION};llama.cpp;qwen2.5-1.5b-q4_k_m"
                        EditingProvider.BACKEND -> "backend-unverified"
                    }
                    recordHistory("edited", request.mode, text, request.transcript,
                        promptVersion = resultPromptVersion)
                    notice = "Review ${draft.mode.label}, then tap Insert."
                    render()
                    previewScroll.reviewFromStart()
                }
            }.onFailure { failure ->
                if (draft.fail(request)) { notice = failure.message ?: "Editing failed. Select Raw or retry."; render() }
            }
        }
        if (config.provider == EditingProvider.LLAMA) localEditor.value.edit(request, completed)
        else editor.edit(config, request, completed)
    }

    private fun insertSelectedText() {
        if (!activeEditor || sensitive || captureState != LocalDictation.State.IDLE ||
            draft.pending || draft.unconfirmed.isNotBlank()) return
        val text = draft.selectedText?.takeIf { it.isNotBlank() } ?: return
        val original = draft.original
        val mode = draft.mode
        val session = historySession
        val promptVersion = if (mode == EditMode.RAW) EditProtocol.PROMPT_VERSION else resultPromptVersion
        // commitText is the sole host write. No surrounding text reads, editor actions or key events.
        val committed = try { currentInputConnection?.commitText(text, 1) == true } catch (_: RuntimeException) { false }
        if (committed) {
            recordHistory("inserted", mode, text, original, session, promptVersion)
            cancelEditing()
            clearDraft()
            showOriginal = false
            notice = "Text inserted. Nothing was submitted."
        } else notice = "The app did not accept the text. Draft kept. Try Insert again."
        render()
    }

    private fun stopWork() {
        dictation.cancel()
        cancelEditing()
        draft.invalidate()
        if (::panel.isInitialized) panel.keepScreenOn = false
    }

    private fun cancelEditing() {
        editor.cancel()
        if (localEditor.isInitialized()) localEditor.value.cancel()
    }

    private fun endEditor() {
        activeEditor = false
        popup?.dismiss()
        stopWork()
        clearDraft()
        showOriginal = false
        render()
    }

    private fun returnToGboard() {
        stopWork()
        val manager = getSystemService(InputMethodManager::class.java)
        val gboard = manager.enabledInputMethodList.firstOrNull { it.packageName == "com.google.android.inputmethod.latin" }
        if (gboard != null) {
            try { switchInputMethod(gboard.id); return } catch (_: RuntimeException) { /* Picker remains available. */ }
        }
        showKeyboardPicker()
    }

    private fun showKeyboardPicker() {
        stopWork()
        getSystemService(InputMethodManager::class.java).showInputMethodPicker()
        notice = "Choose a keyboard in Android's picker."
        render()
    }

    private fun render() {
        if (!::insert.isInitialized || !::panel.isInitialized) return
        val capturing = captureState != LocalDictation.State.IDLE
        val usable = activeEditor && !sensitive
        val localEditing = EditingSettings(this).provider == EditingProvider.LLAMA
        panel.keepScreenOn = usable && (capturing || (localEditing && draft.pending))
        status.text = if (sensitive) "Dictation is off for this private or non-text field. Use a normal keyboard."
            else if (captureState == LocalDictation.State.LISTENING) "Listening on device. Screen stays awake."
            else notice
        if (historyWarning && !sensitive) status.append(" Local history could not save. Your draft is safe.")
        status.tooltipText = status.text
        modeButtons.forEach { (mode, button) ->
            val selected = mode == draft.mode
            button.isEnabled = usable && !capturing && draft.unconfirmed.isBlank() && (mode == EditMode.RAW || draft.original.isNotBlank())
            button.isSelected = selected
            button.setTextColor(if (selected) Ui.onPrimary(this) else Ui.ink(this))
            button.background = Ui.controlBackground(if (selected) Ui.green(this) else Ui.secondary(this), this)
            button.contentDescription = mode.label + (if (selected) ", selected. " else ". ") +
                (if (mode == EditMode.RAW) "On device" else if (localEditing) "llama.cpp on this device" else "AI edit. Uses API credit")
            button.alpha = if (button.isEnabled) 1f else 0.45f
        }
        val result = draft.selectedText
        val sourceView = capturing || draft.unconfirmed.isNotBlank() || showOriginal ||
            draft.mode == EditMode.RAW || result.isNullOrBlank()
        val provisional = if (draft.unconfirmed.isNotBlank()) draft.unconfirmed else if (capturing) draft.partial else ""
        previewLabel.text = when {
            draft.unconfirmed.isNotBlank() -> "Unfinished · check before keeping"
            capturing -> "Dictating at cursor"
            showOriginal -> "Original · tap for cursor"
            draft.mode == EditMode.RAW -> "Raw · tap for cursor"
            else -> "${draft.mode.label} preview"
        }
        val displayedText = when {
            sensitive -> "Use a normal keyboard for this field."
            provisional.isNotBlank() -> draft.previewWith(provisional)
            capturing -> draft.original.ifBlank { "Listening for your words…" }
            sourceView && draft.original.isNotBlank() -> draft.original
            !result.isNullOrBlank() -> result
            else -> "Speak naturally. Tap your words later to add a paragraph at the cursor."
        }
        preview.caretOffset = if (usable && sourceView && (draft.original.isNotBlank() || provisional.isNotBlank()))
            draft.previewCursorWith(provisional) else null
        preview.caretInteractive = usable && !capturing && !draft.pending &&
            draft.unconfirmed.isBlank() && draft.original.isNotBlank()
        previewScroll.followTargetOffset = preview.caretOffset
        previewScroll.updateText(preview, displayedText, followChanges = capturing || draft.mode == EditMode.RAW)
        val source = listOf(draft.original, draft.partial, draft.unconfirmed).filter { it.isNotBlank() }.joinToString(" ")
        val words = if (source.isBlank()) 0 else source.trim().split(Regex("\\s+")).size
        wordCount.text = if (words == 0) "" else "$words words"
        keep.visibility = if (draft.unconfirmed.isNotBlank()) View.VISIBLE else View.GONE
        insert.visibility = if (draft.unconfirmed.isBlank()) View.VISIBLE else View.GONE
        keep.isEnabled = usable && !capturing
        microphone.text = when (captureState) {
            LocalDictation.State.IDLE -> if (draft.original.isBlank()) "Dictate"
                else if (draft.cursor < draft.original.length) "Dictate here" else "Dictate more"
            LocalDictation.State.STARTING -> "Starting…"
            LocalDictation.State.LISTENING -> "Stop"
            LocalDictation.State.FINALIZING -> "Finishing…"
        }
        microphone.contentDescription = if (capturing) "Microphone active. Stop dictation" else "Start on-device dictation. Raw mode"
        microphone.setCompoundDrawablesWithIntrinsicBounds(if (capturing) R.drawable.ic_stop else R.drawable.ic_mic, 0, 0, 0)
        microphone.compoundDrawablePadding = dp(6)
        val microphonePrimary = capturing || draft.original.isBlank()
        val microphoneInk = if (microphonePrimary) Ui.onPrimary(this) else Ui.ink(this)
        microphone.setTextColor(microphoneInk)
        microphone.compoundDrawableTintList = ColorStateList.valueOf(microphoneInk)
        microphone.background = Ui.controlBackground(
            if (capturing) Ui.recording(this) else if (microphonePrimary) Ui.green(this) else Ui.secondary(this), this
        )
        microphone.isEnabled = usable && draft.unconfirmed.isBlank() && captureState != LocalDictation.State.FINALIZING
        cancel.visibility = if (capturing || draft.unconfirmed.isNotBlank() || draft.pending) View.VISIBLE else View.GONE
        cancel.isEnabled = usable && (capturing || draft.unconfirmed.isNotBlank() || draft.pending)
        cancel.contentDescription = if (draft.pending) "Cancel AI editing. Keep original words in Raw"
            else "Cancel unfinished dictation. Keep confirmed words"
        retry.visibility = if (usable && !capturing && !draft.pending && draft.original.isNotBlank() &&
            draft.mode != EditMode.RAW && result.isNullOrBlank() && draft.unconfirmed.isBlank()) View.VISIBLE else View.GONE
        retry.contentDescription = if (localEditing) "Retry llama.cpp on device" else "Retry AI edit. Uses API credit"
        insert.isEnabled = usable && !capturing && !draft.pending && draft.unconfirmed.isBlank() && !result.isNullOrBlank()
        insert.text = "Insert ${draft.mode.label}"
        insert.contentDescription = "Insert ${draft.mode.label}. Does not send or post"
        listOf(microphone, cancel, insert, keep).forEach { it.alpha = if (it.isEnabled) 1f else 0.45f }
    }

    private fun dp(value: Int) = Ui.dp(this, value)
}
