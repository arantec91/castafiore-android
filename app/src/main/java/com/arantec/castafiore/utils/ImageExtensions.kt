package com.arantec.castafiore.utils

import android.widget.ImageView
import com.arantec.castafiore.R

/**
 * Extension functions for ImageView to handle placeholders and image loading
 */

/**
 * Set a placeholder image
 */
fun ImageView.placeholder(placeholderRes: Int = R.drawable.ic_music_note) {
    this.setImageResource(placeholderRes)
}

/**
 * Load image into ImageView (basic implementation)
 */
fun String.into(imageView: ImageView) {
    // Basic implementation - in a real app you'd use Glide, Picasso, etc.
    // For now, just set a placeholder
    imageView.placeholder()
}
