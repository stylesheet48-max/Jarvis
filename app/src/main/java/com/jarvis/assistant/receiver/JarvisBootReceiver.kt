package com.jarvis.assistant.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.jarvis.assistant.automation.accessibility.JarvisAccessibilityService
import com.jarvis.assistant.util.PermissionHelper

/**
 * Handles device boot completion and package updates to verify accessibility
 * health and restore background diagnostics safely within Android 14 restrictions.
 */
class JarvisBootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "JarvisBootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        Log.i(TAG, "Received system broadcast: $action")

        if (action == Intent.ACTION_BOOT_COMPLETED || action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            val isConfigured = PermissionHelper.isAccessibilityPermissionConfiguredInSettings(context)
            val isConnected = PermissionHelper.isAccessibilityServiceConnected()
            Log.i(TAG, "Post-boot accessibility status: ConfiguredInSettings=$isConfigured, Connected=$isConnected")

            if (isConfigured && !isConnected) {
                Log.w(TAG, "Accessibility is enabled in Settings, waiting for Android AccessibilityManager to bind JarvisAccessibilityService")
            } else if (isConnected) {
                Log.i(TAG, "JarvisAccessibilityService bound and active immediately post-boot")
            }
        }
    }
}
