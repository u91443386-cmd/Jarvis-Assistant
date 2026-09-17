package com.jarvis.voiceassistant
import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.content.pm.PackageManager
import android.view.accessibility.AccessibilityEvent
class JarvisAccessibilityService : AccessibilityService() {
    override fun onServiceConnected() { super.onServiceConnected(); JarvisServiceHolder.instance = this }
    override fun onDestroy() { super.onDestroy(); JarvisServiceHolder.instance = null }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}
    fun openAppByName(name: String): Boolean {
        val pm = packageManager
        for (app in pm.getInstalledApplications(PackageManager.GET_META_DATA)) {
            if (pm.getApplicationLabel(app).toString().equals(name, ignoreCase = true)) {
                val intent = pm.getLaunchIntentForPackage(app.packageName)?.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                if (intent != null) { startActivity(intent); return true }
            }
        }
        return false
    }
}
