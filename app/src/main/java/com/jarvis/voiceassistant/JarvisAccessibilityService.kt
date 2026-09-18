package com.jarvis.voiceassistant

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class JarvisAccessibilityService : AccessibilityService() {

    private val commandReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val command = intent?.getStringExtra("command") ?: return
            
            // Screen Actions
            if (command.contains("scroll down")) {
                rootInActiveWindow?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            } 
            else if (command.contains("scroll up")) {
                rootInActiveWindow?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
            } 
            else if (command.contains("go back")) {
                performGlobalAction(GLOBAL_ACTION_BACK)
            } 
            else if (command.contains("home")) {
                performGlobalAction(GLOBAL_ACTION_HOME)
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        // Signal pakadne ke liye Receiver register karna
        val filter = IntentFilter("JARVIS_ACTION")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(commandReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(commandReceiver, filter)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Yahan se aap screen par kya likha hai wo padh sakte hain
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        unregisterReceiver(commandReceiver)
        super.onDestroy()
    }
}
