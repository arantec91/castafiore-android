package com.arantec.castafiore.utils

import android.app.Activity
import android.content.Context
import android.os.Build
import android.view.View
import android.view.Window
import android.view.WindowInsetsController
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.arantec.castafiore.R

/**
 * Utility class for consistent status bar color across the app
 * regardless of API level or light/dark mode
 */
object StatusBarUtils {

    /**
     * Sets the app's standard status bar color
     * @param window The window to modify
     * @param context The context to get resources from
     */
    private fun setAppStatusBarColor(window: Window, context: Context) {
        // Use theme resource color (currently #121212)
        val statusBarColor = ContextCompat.getColor(context, R.color.dark_background)

        // Apply status bar color consistently across all API levels
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS)
        window.statusBarColor = statusBarColor

        // Ensure status bar icons are not light (so they are visible on dark background)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.setSystemBarsAppearance(
                0, // Clear any light status bar flags
                WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
            )
        } else {
            var flags = window.decorView.systemUiVisibility
            flags = flags and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
            window.decorView.systemUiVisibility = flags
        }
    }

    /**
     * Sets the standard status bar color from a Fragment
     * @param fragment The fragment requesting the update
     */
    fun setStatusBarColor(fragment: Fragment) {
        val activity = fragment.requireActivity()
        setAppStatusBarColor(activity.window, activity)
    }

    /**
     * Sets the standard status bar color from an Activity
     * @param activity The activity requesting the update
     */
    fun setStatusBarColor(activity: Activity) {
        setAppStatusBarColor(activity.window, activity)
    }

    /**
     * Sets a custom status bar color from a Fragment
     * @param fragment The fragment requesting the update
     * @param color The custom color to apply
     */
    fun setStatusBarColor(fragment: Fragment, color: Int) {
        val activity = fragment.requireActivity()
        setCustomStatusBarColor(activity.window, color)
    }

    /**
     * Sets a custom status bar color from an Activity
     * @param activity The activity requesting the update
     * @param color The custom color to apply
     */
    fun setStatusBarColor(activity: Activity, color: Int) {
        setCustomStatusBarColor(activity.window, color)
    }

    /**
     * Sets a custom status bar color
     * @param window The window to modify
     * @param color The custom color to apply
     */
    private fun setCustomStatusBarColor(window: Window, color: Int) {
        // Apply custom status bar color consistently across all API levels
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS)
        window.statusBarColor = color

        // Determine if icons should be light or dark based on color brightness
        val isDark = isColorDark(color)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (isDark) {
                // Dark background - use light icons
                window.insetsController?.setSystemBarsAppearance(
                    0, // Clear light status bar flags for light icons
                    WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                )
            } else {
                // Light background - use dark icons
                window.insetsController?.setSystemBarsAppearance(
                    WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS,
                    WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                )
            }
        } else {
            var flags = window.decorView.systemUiVisibility
            if (isDark) {
                // Dark background - use light icons (clear the light status bar flag)
                flags = flags and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
            } else {
                // Light background - use dark icons (set the light status bar flag)
                flags = flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            }
            window.decorView.systemUiVisibility = flags
        }
    }

    /**
     * Determines if a color is dark based on its luminance
     * @param color The color to analyze
     * @return true if the color is dark, false if it's light
     */
    private fun isColorDark(color: Int): Boolean {
        val red = android.graphics.Color.red(color)
        val green = android.graphics.Color.green(color)
        val blue = android.graphics.Color.blue(color)

        // Calculate luminance using the standard formula
        val luminance = (0.299 * red + 0.587 * green + 0.114 * blue) / 255.0

        // Colors with luminance < 0.5 are considered dark
        return luminance < 0.5
    }
}
