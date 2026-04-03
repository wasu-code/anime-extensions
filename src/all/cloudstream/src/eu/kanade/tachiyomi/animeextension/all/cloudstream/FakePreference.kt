package eu.kanade.tachiyomi.animeextension.all.cloudstream

import android.content.Context
import androidx.preference.EditTextPreference

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
