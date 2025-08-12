package com.arantec.castafiore.utils

import android.app.Activity
import android.graphics.Color
import android.os.Build
import android.view.View
import android.view.Window
import androidx.fragment.app.Fragment

/**
 * Simplified utility class for consistent status bar color across the app
 */
object StatusBarUtils {
    
    // Predetermined status bar color for the entire app
    private val APP_STATUS_BAR_COLOR = Color.parseColor("#121212")
    
    /**
     * Sets the app's standard status bar color
     * @param window The window to modify
     */
    private fun setAppStatusBarColor(window: Window) {
        if (Build.VERSION.SDK_INT < 34) {
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
            window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS)
            window.statusBarColor = APP_STATUS_BAR_COLOR
        }

        val decorView = window.decorView
        // Always use dark icons since we're using a dark status bar
        var flags = decorView.systemUiVisibility
        flags = flags and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
        decorView.systemUiVisibility = flags
    }
    
    /**
     * Sets the standard status bar color from a Fragment
     * @param fragment The fragment requesting the update
     */
    fun setStatusBarColor(fragment: Fragment) {
        setAppStatusBarColor(fragment.requireActivity().window)
    }
    
    /**
     * Sets the standard status bar color from an Activity
     * @param activity The activity requesting the update
     */
    fun setStatusBarColor(activity: Activity) {
        setAppStatusBarColor(activity.window)
    }
}