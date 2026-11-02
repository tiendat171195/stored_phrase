package io.github.tiendat171195.storedphrase.security

import android.app.Activity
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager

/**
 * Window-level hardening applied to every screen of the app.
 */
object SecureWindow {

    /** Call in onCreate before setContent. */
    fun applyTo(activity: Activity) {
        val window = activity.window
        // No screenshots, screen recording, casting to insecure displays or Recents thumbnails.
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        // Hide non-system overlays drawn by other apps (tapjacking / fake prompts).
        window.setHideOverlayWindows(true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            activity.setRecentsScreenshotEnabled(false)
        }
        val decor = window.decorView
        // Keep autofill services and content capture (screen understanding features) away.
        decor.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        decor.importantForContentCapture = View.IMPORTANT_FOR_CONTENT_CAPTURE_NO_EXCLUDE_DESCENDANTS
    }

    /** Call right after setContent, once the content view exists. */
    fun hardenContent(activity: Activity) {
        val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        // Drop touches that arrive while another window covers ours.
        content.filterTouchesWhenObscured = true
        val root = content.getChildAt(0) ?: return
        root.filterTouchesWhenObscured = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Only real accessibility tools (e.g. TalkBack) may read this UI.
            root.setAccessibilityDataSensitive(View.ACCESSIBILITY_DATA_SENSITIVE_YES)
        }
    }
}
