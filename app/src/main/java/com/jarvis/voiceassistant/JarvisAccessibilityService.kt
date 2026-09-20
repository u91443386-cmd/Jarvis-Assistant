package com.jarvis.voiceassistant

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.content.ContextCompat
import android.os.Build

class JarvisAccessibilityService : AccessibilityService() {
    companion object {
        private const val TAG = "JarvisAccessibility"

        // Common package names for launching apps by voice
        private val APP_PACKAGES = mapOf(
            "instagram" to "com.instagram.android",
            "insta" to "com.instagram.android",
            "youtube" to "com.google.android.youtube",
            "you tube" to "com.google.android.youtube",
            "you do" to "com.google.android.youtube", // Phonetic fallback
            "whatsapp" to "com.whatsapp",
            "twitter" to "com.twitter.android",
            "x" to "com.twitter.android",
            "facebook" to "com.facebook.katana",
            "chrome" to "com.android.chrome",
            "settings" to "com.android.settings",
            "spotify" to "com.spotify.music",
            "telegram" to "org.telegram.messenger",
            "bgmi" to "com.pubg.imobile",
            "free fire max" to "com.dts.freefiremax",
            "zomato" to "com.application.zomato",
            "swiggy" to "in.swiggy.android",
            "dominos" to "com.Dominos",
            "amazon" to "in.amazon.mShop.android.shopping",
            "blinkit" to "com.grofers.customerapp",
            "cashkaro" to "com.cashkaro",
            "truecaller" to "com.truecaller",
            "termux" to "com.termux",
            "wps office" to "cn.wps.moffice_eng",
            "where is my train" to "com.whereismytrain.android",
            "youtube music" to "com.google.android.apps.youtube.music",
            "photos" to "com.google.android.apps.photos",
            "gallery" to "com.miui.gallery",
            "camera" to "com.android.camera",
            "calculator" to "com.miui.calculator",
            "calendar" to "com.android.calendar",
            "drive" to "com.google.android.apps.docs",
            "files" to "com.google.android.apps.nbu.files",
            "file manager" to "com.mi.android.globalFileexplorer",
            "roblox" to "com.roblox.client",
            "phonepe" to "com.phonepe.app",
            "opera" to "com.opera.browser",
            "onedrive" to "com.microsoft.skydrive",
            "netflix" to "com.netflix.mediaclient",
            "myntra" to "com.myntra.android",
            "myjio" to "com.jio.myjio",
            "lenskart" to "com.lenskart.app",
            "maps" to "com.google.android.apps.maps",
            "meet" to "com.google.android.apps.tachyon",
            "messages" to "com.google.android.apps.messaging",
            "hdfc" to "com.snapwork.hdfc",
            "hotstar" to "in.startv.hotstar",
            "gemini" to "com.google.android.apps.bard",
            "gmail" to "com.google.android.gm",
            "play store" to "com.android.vending",
            "gpay" to "com.google.android.apps.nbu.paisa.user"
        )
    }

    private val handler = Handler(Looper.getMainLooper())

    private val commandReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            // FIX: Checking both new and old intent extra names so it never misses a command
            val text = intent?.getStringExtra("command") 
                ?: intent?.getStringExtra("EXTRA_TEXT") 
                ?: return
            
            Log.d(TAG, "Command received in Accessibility: $text")
            processVoiceCommand(text)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d(TAG, "Accessibility service connected")

        // FIX: Registering for both new and old intent actions
        val filter = IntentFilter().apply {
            addAction("com.jarvis.ACTION_COMMAND")
            addAction("com.jarvis.voiceassistant.VOICE_COMMAND")
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(commandReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(commandReceiver, filter)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(commandReceiver)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun processVoiceCommand(rawCommand: String) {
        if (rawCommand.isBlank()) return
        val lower = rawCommand.lowercase().trim()

        val appName = extractAppName(lower)
        if (appName != null) {
            val packageName = APP_PACKAGES[appName]
            if (packageName != null) {
                launchApp(packageName)

                val query = extractQuery(rawCommand)
                if (query.isNotEmpty()) {
                    handler.postDelayed({
                        findAndClickSearchAndType(query)
                    }, 2500)
                }
                return
            }
        }

        val query = extractQuery(rawCommand)
        if (query.isNotEmpty()) {
            handler.postDelayed({
                findAndClickSearchAndType(query)
            }, 800)
        }
    }

    // FIX: Bulletproof app name extraction
    private fun extractAppName(lowerCommand: String): String? {
        return APP_PACKAGES.keys.firstOrNull { 
            lowerCommand.contains("open $it") || lowerCommand.contains("launch $it")
        }
    }

    private fun extractQuery(rawCommand: String): String {
        val lower = rawCommand.lowercase()
        val searchIndex = lower.indexOf("search")
        if (searchIndex == -1) return ""

        var query = rawCommand.substring(searchIndex + "search".length).trim()
        query = query.replace(Regex("(?i)\\s+(and type|type)\\s*$"), "")
        return query.trim().trim(',', ' ')
    }

    private fun launchApp(packageName: String) {
        try {
            val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(launchIntent)
                Log.d(TAG, "Launching $packageName")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch app", e)
        }
    }

    private fun findAndClickSearchAndType(query: String) {
        val root = rootInActiveWindow ?: return
        val searchNode = findNode(root) { isSearchNode(it) }

        if (searchNode != null) {
            searchNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            searchNode.recycle()
            handler.postDelayed({ setTextInEditText(query) }, 1000)
        } else {
            val editText = findNode(root) { 
                it.className?.toString()?.contains("EditText") == true && it.isVisibleToUser 
            }
            if (editText != null) {
                setTextIntoNode(editText, query)
                editText.recycle()
            }
        }
        root.recycle()
    }

    private fun findNode(root: AccessibilityNodeInfo?, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (root == null) return null
        if (predicate(root)) return AccessibilityNodeInfo.obtain(root)

        for (i in 0 until root.childCount) {
            val child = root.getChild(i) ?: continue
            val result = findNode(child, predicate)
            child.recycle()
            if (result != null) return result
        }
        return null
    }

    private fun isSearchNode(node: AccessibilityNodeInfo): Boolean {
        if (!node.isVisibleToUser) return false
        val text = node.text?.toString()?.lowercase() ?: ""
        val desc = node.contentDescription?.toString()?.lowercase() ?: ""
        val viewId = node.viewIdResourceName?.lowercase() ?: ""
        val className = node.className?.toString()?.lowercase() ?: ""

        // Safe filter: Ignore mic/voice buttons so it doesn't trigger STT instead of text search
        if (desc.contains("voice") || desc.contains("mic") || viewId.contains("voice") || viewId.contains("mic")) {
            return false
        }
        if (text.contains("search") || desc.contains("search")) return true
        if (viewId.contains("search") && !viewId.contains("search_edit_text")) return true
        if (desc.isNotEmpty() && (desc.contains("search") || desc.contains("magnif"))) return true
        if (className.contains("edittext") && node.isClickable) return true
        return false
    }

    private fun setTextInEditText(query: String) {
        val root = rootInActiveWindow ?: return
        val editText = findNode(root) { 
            it.className?.toString()?.contains("EditText") == true && it.isVisibleToUser 
        }
        if (editText != null) {
            setTextIntoNode(editText, query)
            editText.recycle()
        }
        root.recycle()
    }

    private fun setTextIntoNode(node: AccessibilityNodeInfo, text: String) {
        val arguments = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
    }
}
