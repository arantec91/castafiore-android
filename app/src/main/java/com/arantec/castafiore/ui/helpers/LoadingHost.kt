package com.arantec.castafiore.ui.helpers

/**
 * Implement this in the hosting Activity (or a parent Fragment) that controls the
 * global loading overlay so child fragments can delegate loading UI.
 */
interface LoadingHost {
    /** Show or hide the global loading overlay. */
    fun showGlobalLoading(show: Boolean)

    /** Optional: whether the global overlay is currently visible. */
    fun isGlobalLoadingVisible(): Boolean = false
}

