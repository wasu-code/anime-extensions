package eu.kanade.tachiyomi.animeextension.all.cloudstream

import android.content.Context
import androidx.preference.EditTextPreference
import androidx.preference.SwitchPreferenceCompat

/**
 * A preference that shows an OK/Cancel alert dialog.
 * @param onConfirm Called when user clicks OK/positive button
 * @param key
 * @param title
 * @param summary
 * @param dialogTitle
 * @param dialogMessage
 * @param showDivider
 */
class ConfirmActionPreference(
    context: Context,
) : EditTextPreference(context) {
    init {
        // Called when OK is clicked
        setOnPreferenceChangeListener { _, _ ->
            onConfirm()
            false
        }

        setOnBindEditTextListener {
            it.run {
                // Disable text input
                inputType = android.text.InputType.TYPE_NULL
                isEnabled = false
                isFocusable = false
                if (!showDivider) visibility = android.view.View.GONE // Hide the input field
            }
        }
    }

    var onConfirm: () -> Unit = {}
    var showDivider: Boolean = false
}

/**
 * @param onClick Called when user clicks the button
 * @param key
 * @param title
 * @param summary
 */
class ButtonPreference(
    context: Context,
) : SwitchPreferenceCompat(context) {
    init {
        setDefaultValue(false)
        setOnPreferenceChangeListener { _, _ ->
            onClick()
            false // prevent from switching to toggled state
        }
    }

    var onClick: () -> Unit = {}
}

class PreferenceDivider(
    context: Context,
) : EditTextPreference(context) {
    init {
        setEnabled(false)
    }

    var bigText: String?
        set(value) { title = value }
        get() = title.toString()

    var smallText: String?
        set(value) { summary = value }
        get() = summary.toString()
}
