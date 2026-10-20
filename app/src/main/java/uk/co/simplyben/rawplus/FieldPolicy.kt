package uk.co.simplyben.rawplus

import android.text.InputType
import android.view.inputmethod.EditorInfo

object FieldPolicy {
    fun blocked(inputType: Int, imeOptions: Int): Boolean {
        val kind = inputType and InputType.TYPE_MASK_CLASS
        val variation = inputType and InputType.TYPE_MASK_VARIATION
        return kind != InputType.TYPE_CLASS_TEXT ||
            variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
            variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
            variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD ||
            // Private/incognito editors get no dictation or external AI in this prototype.
            imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING != 0
    }
}
