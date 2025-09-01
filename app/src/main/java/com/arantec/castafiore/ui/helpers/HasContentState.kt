package com.arantec.castafiore.ui.helpers

/** Implement on fragments so the Activity can decide whether to show a global loading overlay. */
interface HasContentState {
    /** Return true when the fragment already has something meaningful rendered. */
    fun hasContent(): Boolean
}

