package vasyl.titles.privileged

import android.app.Dialog
import android.os.Build
import android.os.Bundle
import android.text.TextUtils
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.text.HtmlCompat
import androidx.fragment.app.DialogFragment
import vasyl.titles.R

/**
 * What "Use Shizuku / root" changes, in full: the permissions it grants, the global touch opacity
 * limit (Android 12+), the security cost, when the limit is restored and how to restore it by hand.
 */
class PrivilegedHelperInfoDialog : DialogFragment() {

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        var text: CharSequence = HtmlCompat.fromHtml(
            getString(R.string.privileged_helper_info), HtmlCompat.FROM_HTML_MODE_LEGACY
        )
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            text = TextUtils.concat(text, "\n\n", getString(R.string.privileged_helper_info_no_limit))
        }
        val dialog = AlertDialog.Builder(requireContext())
            .setTitle(R.string.privileged_helper_info_title)
            .setMessage(text)
            .setPositiveButton(android.R.string.ok, null)
            .create()
        // The adb command can be selected and copied.
        dialog.setOnShowListener {
            dialog.findViewById<TextView>(android.R.id.message)?.setTextIsSelectable(true)
        }
        return dialog
    }

    companion object {
        const val TAG = "privileged_helper_info"
    }
}
