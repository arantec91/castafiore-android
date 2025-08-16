package com.arantec.castafiore.ui.lyrics

import android.content.Context
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatTextView
import com.arantec.castafiore.R
import android.graphics.Canvas
import android.graphics.Color

class ProgressLyricsTextView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = android.R.attr.textViewStyle
) : AppCompatTextView(context, attrs, defStyleAttr) {

    private var progress: Float = 0f // 0..1 (ignored for drawing; kept for API compatibility)
    private var active: Boolean = false

    private val colorPrimary by lazy { resources.getColor(R.color.primary, context.theme) }
    private val colorSecondary by lazy { resources.getColor(R.color.text_secondary, context.theme) }

    fun setProgressFraction(value: Float) {
        // Coerce and store but do not trigger redraws; highlighting is full-line only when active
        progress = value.coerceIn(0f, 1f)
    }

    fun setActive(isActive: Boolean) {
        if (this.active == isActive) return
        this.active = isActive
        // Remove scale animations to avoid clipping text outside bounds
        animate().cancel()
        if (isActive) {
            alpha = 1f
            // Subtle glow without overflowing too much
            setShadowLayer(6f, 0f, 0f, colorPrimary)
        } else {
            alpha = 0.85f
            setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT)
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val originalColor = currentTextColor
        // Draw full line in primary when active, secondary otherwise
        val targetColor = if (active) colorPrimary else colorSecondary
        if (originalColor != targetColor) setTextColor(targetColor)
        super.onDraw(canvas)
        // Restore original color to avoid side-effects on future state changes
        if (currentTextColor != originalColor) setTextColor(originalColor)
    }
}
