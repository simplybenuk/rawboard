@file:Suppress("SetTextI18n")
package uk.co.simplyben.rawplus

import android.app.Activity
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Non-exported screen. Only a short-lived loopback listener receives the OAuth callback. */
class ChatGptSignInActivity : Activity() {
    private lateinit var auth: ChatGptAuth
    private lateinit var status: TextView
    private lateinit var retry: Button
    private lateinit var cancel: Button
    private lateinit var usage: Button
    private var busy = false
    private var activityGeneration = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        auth = ChatGptAuth(this)
        val column = Ui.column(this).apply {
            setBackgroundColor(Ui.paper(this@ChatGptSignInActivity))
            setPadding(dp(20), dp(16), dp(20), dp(24))
        }
        val scroll = ScrollView(this).apply { addView(column) }
        scroll.setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        setContentView(scroll)
        scroll.post { Ui.applyWindowAppearance(window, this) }
        column.addView(Ui.heading(this, "Connect ChatGPT", 26f), row())
        column.addView(Ui.text(this, "Continue in your normal browser. Raw+ never sees your password. Return here with Android's Back button after approval."), row())
        column.addView(Ui.text(this, "Raw+ asks to use your ChatGPT plan for Raw+ and Polished edits. Each edit sends your original dictation to OpenAI and uses your plan's shared allowance or credits under your ChatGPT settings. Raw does not request an edit. Signing in does not select this provider or send a draft."), row())
        status = Ui.text(this, "Preparing a private sign-in…").apply { isSaveEnabled = false }
        column.addView(status, row())
        retry = Ui.button(this, "Continue with ChatGPT", true) { begin() }
        column.addView(retry, row())
        usage = Ui.button(this, "Manage ChatGPT usage") {
            try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(USAGE_URL))) }
            catch (_: Exception) { status.text = "Open ChatGPT Settings, then Usage, to manage this app's access and limits." }
        }.apply { visibility = View.GONE }
        column.addView(usage, row())
        cancel = Ui.button(this, "Cancel sign-in") {
            activityGeneration++
            auth.cancel()
            busy = false
            finish()
        }
        column.addView(cancel, row())
        // A recreated process has lost PKCE/state and its socket. Never try to reconstruct them.
        if (savedInstanceState == null) begin() else {
            status.text = "The previous sign-in could not be resumed. Start again to create a fresh secure attempt."
            retry.isEnabled = true
        }
    }

    private fun begin() {
        if (busy) return
        busy = true
        val attempt = ++activityGeneration
        retry.isEnabled = false
        retry.text = "Continue with ChatGPT"
        cancel.text = "Cancel sign-in"
        status.text = "Opening ChatGPT in your browser. Complete approval, then press Back to return here."
        auth.beginSignIn(intent.getStringExtra(EXTRA_ACCOUNT_ID), ready = { result ->
            if (!current(attempt)) return@beginSignIn
            result.onSuccess { url ->
                try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                catch (_: Exception) {
                    auth.cancel()
                    failed("Could not open a browser. Install or enable a browser and try again.")
                }
            }.onFailure { failure -> failed(failure.message ?: "Could not begin ChatGPT sign-in.") }
        }, complete = { result ->
            if (!current(attempt)) return@beginSignIn
            busy = false
            result.onSuccess { account ->
                retry.visibility = View.GONE
                cancel.text = "Back to Providers"
                usage.visibility = View.VISIBLE
                val welcome = account.planEnabled && auth.consumePlanWelcome()
                status.text = if (welcome) {
                    "You're using your ChatGPT plan. Connected ${account.label}. Back in Providers, load the account's models, choose one, then tap Use ChatGPT. Your ChatGPT settings control access and shared usage limits."
                } else if (account.planEnabled) {
                    "Connected ${account.label}. Back in Providers, load models, choose one, then tap Use ChatGPT."
                } else {
                    "Signed in as ${account.label}, but ChatGPT plan use was not granted. Continue with this account again from Providers and approve plan access before editing."
                }
            }.onFailure { failure -> failed(failure.message ?: "Could not complete ChatGPT sign-in.") }
        })
    }

    private fun failed(message: String) {
        busy = false
        status.text = message
        retry.isEnabled = true
        retry.visibility = View.VISIBLE
        cancel.text = "Back to Providers"
    }

    private fun current(attempt: Long) = !isFinishing && !isDestroyed && activityGeneration == attempt

    // Opening the browser normally stops this Activity. Keep the listener alive until destruction,
    // explicit cancellation, a completed callback or the ten-minute deadline.
    override fun onDestroy() {
        activityGeneration++
        auth.close()
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        Ui.applyWindowAppearance(window, this)
    }

    private fun dp(value: Int) = Ui.dp(this, value)
    private fun row() = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(12) }

    companion object {
        const val EXTRA_ACCOUNT_ID = "account_id"
        const val USAGE_URL = "https://chatgpt.com/settings/usage"
    }
}
