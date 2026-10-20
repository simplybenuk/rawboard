package uk.co.simplyben.rawplus

import android.text.InputType
import android.view.inputmethod.EditorInfo
import org.junit.Assert.*
import org.junit.Test

class FieldPolicyTest {
    @Test fun `passwords and private fields never enable dictation`() {
        listOf(InputType.TYPE_TEXT_VARIATION_PASSWORD, InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD).forEach {
            assertTrue(FieldPolicy.blocked(InputType.TYPE_CLASS_TEXT or it, 0))
        }
        assertTrue(FieldPolicy.blocked(InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD, 0))
        assertTrue(FieldPolicy.blocked(InputType.TYPE_CLASS_TEXT, EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING))
    }

    @Test fun `ordinary app message email and comment fields are allowed`() {
        listOf(InputType.TYPE_TEXT_VARIATION_NORMAL, InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
            InputType.TYPE_TEXT_VARIATION_EMAIL_SUBJECT, InputType.TYPE_TEXT_VARIATION_LONG_MESSAGE,
            InputType.TYPE_TEXT_VARIATION_WEB_EDIT_TEXT).forEach {
            assertFalse(FieldPolicy.blocked(InputType.TYPE_CLASS_TEXT or it or InputType.TYPE_TEXT_FLAG_MULTI_LINE, 0))
        }
    }

    @Test fun `phone numeric date and untyped fields use normal keyboard`() {
        listOf(InputType.TYPE_CLASS_NUMBER, InputType.TYPE_CLASS_PHONE,
            InputType.TYPE_CLASS_DATETIME, InputType.TYPE_NULL).forEach {
            assertTrue(FieldPolicy.blocked(it, 0))
        }
    }
}
