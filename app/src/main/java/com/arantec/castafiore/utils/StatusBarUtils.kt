package com.arantec.castafiore.utils

import android.app.Activity
import android.content.Context
import android.os.Build
import android.view.View
import android.view.Window
import android.view.WindowInsetsController
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import com.arantec.castafiore.R

/**
 * Utility class for consistent status bar color across the app
 * Always uses the fixed dark background color (#121212)
 */
object StatusBarUtils {

    /**
     * Sets the app's fixed status bar color (#121212)
     * @param window The window to modify
     * @param context The context to get resources from
     */
    private fun setAppStatusBarColor(window: Window, context: Context) {
        // Always use the fixed dark background color (#121212)
        val statusBarColor = ContextCompat.getColor(context, R.color.dark_background)

        // Apply status bar color consistently across all API levels
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS)
        window.statusBarColor = statusBarColor

        // Use light icons for dark background
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.setSystemBarsAppearance(
                0, // Clear light status bar flags to show light icons
                WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
            )
        } else {
            var flags = window.decorView.systemUiVisibility
            flags = flags and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
            window.decorView.systemUiVisibility = flags
        }
    }

    /**
     * Sets the fixed status bar color from a Fragment
     * @param fragment The fragment requesting the update
     */
    fun setStatusBarColor(fragment: Fragment) {
        val activity = fragment.requireActivity()
        setAppStatusBarColor(activity.window, activity)
    }

    /**
     * Sets the fixed status bar color from an Activity
     * @param activity The activity requesting the update
     */
    fun setStatusBarColor(activity: Activity) {
        setAppStatusBarColor(activity.window, activity)
    }

    /**
     * Applies only the top system bar inset (status bar height) as additional paddingTop to the given view.
     * This ensures content does not draw under the status bar without introducing bottom insets that caused gaps.
     */
    fun applyStatusBarTopPadding(target: View) {
        // Apply only on Android 14+ where edge-to-edge behavioral changes can cause content overlap
        if (Build.VERSION.SDK_INT < 34) return

        val initialLeft = target.paddingLeft
        val initialTop = target.paddingTop
        val initialRight = target.paddingRight
        val initialBottom = target.paddingBottom

        ViewCompat.setOnApplyWindowInsetsListener(target) { v, insets ->
            val statusBars = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            v.setPadding(initialLeft, initialTop + statusBars.top, initialRight, initialBottom)
            // Return original insets so children can consume as needed
            insets
        }
        // Request insets application
        ViewCompat.requestApplyInsets(target)
    }
}
