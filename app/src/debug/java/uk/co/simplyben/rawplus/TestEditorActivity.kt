package uk.co.simplyben.rawplus

import android.app.Activity
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Debug-only host fields for adb-driven IME policy and lifecycle checks. */
class TestEditorActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val fields = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
        }
        fun field(label: String, type: Int, private: Boolean = false) {
            fields.addView(TextView(this).apply { text = label })
            fields.addView(EditText(this).apply {
                hint = label
                contentDescription = label
                inputType = type
                if (private) imeOptions = imeOptions or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
                importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
                isSaveEnabled = false
            }, LinearLayout.LayoutParams(-1, -2))
        }
        field("Plain text", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE)
        field("Password", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        field("Numeric", InputType.TYPE_CLASS_NUMBER)
        field("Private text", InputType.TYPE_CLASS_TEXT, private = true)
        setContentView(ScrollView(this).apply { addView(fields) })
    }
}
