package com.arantec.castafiore.utils

import android.app.Activity
import android.content.Context
import android.os.Build
import android.view.View
import android.view.Window
import android.view.WindowInsetsController
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import com.arantec.castafiore.R

/**
 * Utility class for consistent status bar color across the app.
 * - Provides fixed dark color helpers
 * - Provides dynamic color helpers with automatic icon contrast
 */
object StatusBarUtils {

    /**
     * Sets the app's fixed status bar color (#121212)
     * @param window The window to modify
     * @param context The context to get resources from
     */
    private fun setAppStatusBarColor(window: Window, context: Context) {
        val statusBarColor = ContextCompat.getColor(context, R.color.dark_background)
        applyStatusBarColor(window, statusBarColor)
    }

    /**
     * Applies a specific status bar color with automatic icon contrast (light/dark)
     */
    private fun applyStatusBarColor(window: Window, color: Int) {
        // Ensure we draw behind system bars
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS)
        window.statusBarColor = color

        // Decide icon color based on background luminance (light bg -> dark icons)
        val isLightBackground = try {
            ColorUtils.calculateLuminance(color) > 0.5
        } catch (_: Throwable) {
            false
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val controller = window.insetsController
            if (controller != null) {
                val appearance = if (isLightBackground) WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS else 0
                controller.setSystemBarsAppearance(
                    appearance,
                    WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                )
            }
        } else {
            var flags = window.decorView.systemUiVisibility
            flags = if (isLightBackground) {
                flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            } else {
                flags and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
            }
            window.decorView.systemUiVisibility = flags
        }
    }

    /**
     * Sets the fixed status bar color from a Fragment
     */
    fun setStatusBarColor(fragment: Fragment) {
        val activity = fragment.requireActivity()
        setAppStatusBarColor(activity.window, activity)
    }

    /**
     * Sets the fixed status bar color from an Activity
     */
    fun setStatusBarColor(activity: Activity) {
        setAppStatusBarColor(activity.window, activity)
    }

    /**
     * Sets a dynamic status bar color from a Fragment with automatic icon contrast
     */
    fun setStatusBarColor(fragment: Fragment, color: Int) {
        val activity = fragment.requireActivity()
        applyStatusBarColor(activity.window, color)
    }

    /**
     * Sets a dynamic status bar color from an Activity with automatic icon contrast
     */
    fun setStatusBarColor(activity: Activity, color: Int) {
        applyStatusBarColor(activity.window, color)
    }

    /**
     * Applies a specific color to BOTH status and navigation bars with automatic icon contrast.
     * 
     * Android version handling:
     * - Android 14 and below: Direct color assignment works
     * - Android 15 (API 35): Requires windowOptOutEdgeToEdgeEnforcement=true in theme
     * - Android 16+ (API 36+): Uses enableEdgeToEdge() with SystemBarStyle (must be called from Activity)
     * 
     * NOTE: For Android 16+, the Activity must call enableEdgeToEdge() BEFORE setContentView().
     * This method only sets the colors and icon appearance for older Android versions.
     */
    private fun applySystemBarsColor(window: Window, color: Int) {
        // Ensure we draw behind system bars
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS)
        window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION)

        // Set the colors directly
        window.statusBarColor = color
        window.navigationBarColor = color

        val isLightBackground = try {
            ColorUtils.calculateLuminance(color) > 0.5
        } catch (_: Throwable) {
            false
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.let { controller ->
                var appearance = 0
                if (isLightBackground) {
                    appearance = appearance or WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                    appearance = appearance or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
                }
                controller.setSystemBarsAppearance(
                    appearance,
                    WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
                )
            }
        } else {
            var flags = window.decorView.systemUiVisibility
            flags = if (isLightBackground) {
                flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            } else {
                flags and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv() and View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR.inv()
            }
            window.decorView.systemUiVisibility = flags
        }
    }
    
    /**
     * Applies edge-to-edge with transparent system bars for Android 16+.
     * This makes the system bars fully transparent so the app's background color shows through.
     * This method must be called from a ComponentActivity BEFORE setContentView().
     * 
     * The app's layout background color will show through the transparent bars.
     * Icon colors are automatically adjusted based on the detectDarkMode parameter.
     * 
     * @param activity The ComponentActivity to apply edge-to-edge to
     * @param detectDarkMode If true, uses light icons (for dark backgrounds). If false, uses dark icons (for light backgrounds).
     */
    fun applyEdgeToEdgeWithTransparentBars(activity: ComponentActivity, detectDarkMode: Boolean) {
        if (Build.VERSION.SDK_INT >= 36) {
            // Use fully transparent bars (0x00000000 = transparent black)
            // detectDarkMode controls icon colors:
            // - true = light icons (for dark backgrounds)
            // - false = dark icons (for light backgrounds)
            val statusBarStyle = androidx.activity.SystemBarStyle.auto(
                lightScrim = android.graphics.Color.TRANSPARENT,
                darkScrim = android.graphics.Color.TRANSPARENT,
                detectDarkMode = { detectDarkMode }
            )
            
            val navigationBarStyle = androidx.activity.SystemBarStyle.auto(
                lightScrim = android.graphics.Color.TRANSPARENT,
                darkScrim = android.graphics.Color.TRANSPARENT,
                detectDarkMode = { detectDarkMode }
            )
            
            // Enable edge-to-edge with fully transparent system bars
            activity.enableEdgeToEdge(
                statusBarStyle = statusBarStyle,
                navigationBarStyle = navigationBarStyle
            )
        }
    }

    /**
     * Applies a dynamic status bar color for Android 16+ (API 36+) while keeping navigation bar transparent.
     * For older Android versions, uses the standard applyStatusBarColor method.
     * 
     * This is specifically designed for fragments that need dynamic status bar colors based on image palette
     * while maintaining the default navigation bar appearance.
     * 
     * @param fragment The Fragment to apply the status bar color to
     * @param color The color to apply to the status bar
     */
    fun setDynamicStatusBarForAndroid16(fragment: Fragment, color: Int) {
        val activity = fragment.requireActivity()
        
        if (Build.VERSION.SDK_INT >= 36) {
            // For Android 16+, use enableEdgeToEdge with dynamic status bar color
            if (activity is ComponentActivity) {
                // Determine if we need light or dark icons based on the color luminance
                val isLightBackground = try {
                    ColorUtils.calculateLuminance(color) > 0.5
                } catch (_: Throwable) {
                    false
                }
                
                // Create status bar style with the dynamic color
                // detectDarkMode = !isLightBackground means:
                // - If background is light, use dark icons (detectDarkMode = false)
                // - If background is dark, use light icons (detectDarkMode = true)
                val statusBarStyle = androidx.activity.SystemBarStyle.auto(
                    lightScrim = color,
                    darkScrim = color,
                    detectDarkMode = { !isLightBackground }
                )
                
                // Keep navigation bar transparent (default app behavior)
                val navigationBarStyle = androidx.activity.SystemBarStyle.auto(
                    lightScrim = android.graphics.Color.TRANSPARENT,
                    darkScrim = android.graphics.Color.TRANSPARENT,
                    detectDarkMode = { true } // Dark mode for navigation bar (light icons)
                )
                
                // Apply edge-to-edge with dynamic status bar and transparent navigation bar
                activity.enableEdgeToEdge(
                    statusBarStyle = statusBarStyle,
                    navigationBarStyle = navigationBarStyle
                )
            }
        } else {
            // For older Android versions, use the standard method
            applyStatusBarColor(activity.window, color)
        }
    }

    /**
     * Sets BOTH system bars color from a Fragment
     */
    fun setSystemBarsColor(fragment: Fragment, color: Int) {
        val activity = fragment.requireActivity()
        applySystemBarsColor(activity.window, color)
    }

    /**
     * Sets BOTH system bars color from an Activity
     */
    fun setSystemBarsColor(activity: Activity, color: Int) {
        applySystemBarsColor(activity.window, color)
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
