package com.arantec.castafiore.utils

import android.app.Activity
import android.content.Context
import android.view.View
import androidx.annotation.ColorInt
import androidx.annotation.StringRes
import androidx.fragment.app.Fragment
import com.arantec.castafiore.R
import com.google.android.material.snackbar.Snackbar

object SnackbarUtils {
    enum class Type { INFO, SUCCESS, WARNING, ERROR }

    @ColorInt
    private fun backgroundColor(context: Context, type: Type): Int = when (type) {
        Type.SUCCESS -> context.getColor(R.color.success)
        Type.WARNING -> context.getColor(R.color.warning)
        Type.ERROR -> context.getColor(R.color.error)
        Type.INFO -> context.getColor(R.color.info)
    }

    @ColorInt
    private fun textColor(context: Context): Int = context.getColor(R.color.white)

    fun show(
        anchor: View,
        message: CharSequence,
        type: Type = Type.INFO,
        duration: Int = Snackbar.LENGTH_SHORT
    ) {
        val sb = Snackbar.make(anchor, message, duration)
        // Colors
        sb.setBackgroundTint(backgroundColor(anchor.context, type))
        sb.setTextColor(textColor(anchor.context))
        // Allow long messages to wrap without crashing
        val tv = sb.view.findViewById<android.widget.TextView>(com.google.android.material.R.id.snackbar_text)
        tv?.maxLines = 5
        tv?.ellipsize = null
        sb.show()
    }
}

// Fragment extensions for convenience
fun Fragment.snack(
    message: CharSequence,
    type: SnackbarUtils.Type = SnackbarUtils.Type.INFO,
    duration: Int = Snackbar.LENGTH_SHORT
) {
    view?.let { SnackbarUtils.show(it, message, type, duration) }
}

fun Fragment.snack(@StringRes resId: Int, type: SnackbarUtils.Type = SnackbarUtils.Type.INFO, duration: Int = Snackbar.LENGTH_SHORT) {
    snack(getString(resId), type, duration)
}

// Activity extension
fun Activity.snack(
    message: CharSequence,
    type: SnackbarUtils.Type = SnackbarUtils.Type.INFO,
    duration: Int = Snackbar.LENGTH_SHORT
) {
    val root: View = findViewById(android.R.id.content) ?: return
    SnackbarUtils.show(root, message, type, duration)
}
