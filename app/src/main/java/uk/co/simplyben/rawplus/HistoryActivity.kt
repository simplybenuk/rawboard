@file:Suppress("SetTextI18n")
package uk.co.simplyben.rawplus

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.WindowInsets
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/** User-opened local feedback journal. Export requires a separate tap and document-picker choice. */
class HistoryActivity : Activity() {
    private lateinit var store: HistoryStore
    private lateinit var entriesView: LinearLayout
    private lateinit var status: TextView
    private lateinit var enabled: CheckBox
    private lateinit var export: Button
    private lateinit var clear: Button
    private var updatingToggle = false
    private var pendingOperations = 0
    private val busy: Boolean get() = pendingOperations > 0
    private var destroyed = false
    private var awaitingExport = false
    private val executor = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = HistoryStore(this)
        awaitingExport = savedInstanceState?.getBoolean("awaitingExport") ?: false
        val column = Ui.column(this).apply {
            setPadding(dp(20), dp(16), dp(20), dp(24))
            setBackgroundColor(Ui.paper(this@HistoryActivity))
        }
        val scroll = ScrollView(this).apply { addView(column) }
        scroll.setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        setContentView(scroll)
        scroll.post { Ui.applyWindowAppearance(window, this) }
        column.addView(Ui.button(this, "Back") { finish() }, row())
        column.addView(Ui.heading(this, "Local test history", 26f), row())
        paragraph(column, "Keep the original dictation beside each AI edit and the text you inserted. This helps us compare changes while you test. Inserted text does not mean it was published or sent.")
        paragraph(column, "History is encrypted on this phone. It records no audio, API keys or text from other apps. Nothing is uploaded automatically. Up to 100 entries are kept for 30 days, within a 4 MiB limit. Older entries expire when history is next opened or used.")
        enabled = CheckBox(this).apply {
            text = "Keep local test history"
            setTextColor(Ui.ink(this@HistoryActivity))
            isChecked = store.enabled
            setOnCheckedChangeListener { _, checked ->
                if (!updatingToggle) {
                    try {
                        store.enabled = checked
                        status.text = if (checked) "Future confirmed dictation, AI edits and insertions will be logged locally."
                            else "Logging is off. Existing entries remain until you clear them or they expire."
                    } catch (_: Exception) {
                        updatingToggle = true
                        isChecked = store.enabled
                        updatingToggle = false
                        status.text = "Could not change local history settings. Please try again."
                    }
                }
            }
        }
        column.addView(enabled, row())
        paragraph(column, "Turn this off before dictating anything you do not want to retain. Turning it off keeps existing entries. Clear all removes history on this phone. Files you export must be deleted separately.")
        export = Ui.button(this, "Export history as JSON") { confirmExport() }
        clear = Ui.button(this, "Clear all history") { confirmClear() }
        column.addView(export, row())
        column.addView(clear, row())
        status = Ui.text(this, "Loading local history...", 14f)
        column.addView(status, row())
        entriesView = Ui.column(this)
        column.addView(entriesView, LinearLayout.LayoutParams(-1, -2))
        updateControls()
    }

    override fun onResume() {
        super.onResume()
        if (::entriesView.isInitialized && !busy && !awaitingExport) refresh()
    }

    private fun refresh() {
        work({ store.read() to store.lastError }) { result ->
            showEntries(result.first)
            status.text = result.second ?: if (result.first.isEmpty()) "No local history yet."
                else "${result.first.size} entries. Tap one to compare its original and result."
        }
    }

    private fun showEntries(entries: List<HistoryEntry>) {
        entriesView.removeAllViews()
        entries.asReversed().forEach { entry ->
            val action = eventLabel(entry.event)
            val timestamp = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(entry.timestamp))
            val excerpt = entry.text.replace('\n', ' ').take(120)
            entriesView.addView(Ui.button(this, "$timestamp · ${entry.mode.label} · $action\n$excerpt") {
                showEntry(entry)
            }.apply {
                gravity = android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(12), dp(12), dp(12))
            }, row())
        }
    }

    private fun showEntry(entry: HistoryEntry) {
        entriesView.removeAllViews()
        entriesView.addView(Ui.button(this, "Back to entries") { refresh() }, row())
        entriesView.addView(Ui.heading(this, "${entry.mode.label} · ${eventLabel(entry.event)}", 20f), row())
        paragraph(entriesView, DateFormat.getDateTimeInstance().format(Date(entry.timestamp)))
        paragraph(entriesView, "Prompt version ${entry.promptVersion}\nSession ${entry.sessionId}")
        entriesView.addView(Ui.heading(this, "Original dictation", 18f), row())
        paragraph(entriesView, entry.original, selectable = true)
        entriesView.addView(Ui.heading(this, if (entry.event == "inserted") "Inserted text" else "Result", 18f), row())
        paragraph(entriesView, entry.text, selectable = true)
        if (entry.event == "inserted") paragraph(entriesView, "This is the text passed to the app's field. Raw+ does not know whether you later changed, published or sent it.")
    }

    private fun confirmClear() {
        AlertDialog.Builder(this)
            .setTitle("Clear all local test history?")
            .setMessage("This deletes the encrypted entries on this phone. It does not delete files you have already exported.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Clear all") { _, _ ->
                work({ store.clear() to store.lastError }) { result ->
                    if (result.first) {
                        entriesView.removeAllViews()
                        status.text = "Local history cleared. Logging is ${if (store.enabled) "still on" else "off"}."
                    } else status.text = result.second ?: "Could not clear local history."
                }
            }.show()
    }

    private fun confirmExport() {
        AlertDialog.Builder(this)
            .setTitle("Export your test history?")
            .setMessage("The JSON file contains your dictated words and edits in plain text. Choose a private destination. You can select Google Drive in Android's file picker, then share the file when you want feedback.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Choose destination") { _, _ ->
                val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.UK).format(Date())
                val intent = Intent(Intent.ACTION_CREATE_DOCUMENT)
                    .addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("application/json")
                    .putExtra(Intent.EXTRA_TITLE, "rawplus-test-history-$timestamp.json")
                try {
                    awaitingExport = true
                    updateControls()
                    startActivityForResult(intent, EXPORT_REQUEST)
                } catch (_: Exception) {
                    awaitingExport = false
                    updateControls()
                    status.text = "Could not open Android's file picker. No history was exported."
                }
            }.show()
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != EXPORT_REQUEST) return
        awaitingExport = false
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) {
            updateControls()
            status.text = "Export cancelled. Local history is unchanged."
            return
        }
        work({
            // Read now, after the picker returns. Never export a pre-picker or deleted snapshot.
            val json = store.exportJson()
            if (json == null) "Could not read history for export. No transcript was written. Clear unreadable history to start a new log."
            else runCatching {
                contentResolver.openOutputStream(uri, "wt")?.use { output ->
                    output.write(json.toByteArray(Charsets.UTF_8))
                    output.flush()
                } ?: throw IllegalStateException("No export destination")
                "History exported. The file contains plain text. It remains wherever you chose to save it."
            }.getOrElse {
                "Export failed. The chosen destination may contain a partial file; delete it before retrying. Local history is unchanged."
            }
        }) { message ->
            status.text = message
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("awaitingExport", awaitingExport)
        super.onSaveInstanceState(outState)
    }

    private fun <T> work(action: () -> T, done: (T) -> Unit) {
        if (destroyed) return
        pendingOperations++
        updateControls()
        try {
            executor.execute {
                val result = runCatching(action)
                runOnUiThread {
                    pendingOperations--
                    if (!destroyed) {
                        updateControls()
                        result.fold(done) {
                            status.text = "Could not complete the history action. Your dictation and API settings are unchanged."
                        }
                    }
                }
            }
        } catch (_: RejectedExecutionException) {
            pendingOperations--
            if (!destroyed) {
                updateControls()
                status.text = "Could not start the history action. Please reopen local history and try again."
            }
        }
    }

    private fun updateControls() {
        if (!::enabled.isInitialized) return
        enabled.isEnabled = !busy
        export.isEnabled = !busy && !awaitingExport
        clear.isEnabled = !busy && !awaitingExport
    }

    override fun onDestroy() {
        destroyed = true
        executor.shutdown()
        super.onDestroy()
    }

    private fun eventLabel(event: String) = when (event) {
        "dictated" -> "Dictated"
        "edited" -> "AI edit"
        "inserted" -> "Inserted"
        else -> "Saved"
    }

    private fun paragraph(parent: LinearLayout, value: String, selectable: Boolean = false) {
        parent.addView(Ui.text(this, value).apply { setTextIsSelectable(selectable) }, row())
    }
    private fun row() = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) }
    private fun dp(value: Int) = Ui.dp(this, value)

    companion object { private const val EXPORT_REQUEST = 1 }
}
