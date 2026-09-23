package com.jarvis.voiceassistant

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class JarvisAccessibilityService : AccessibilityService() {
    companion object {
        private const val TAG = "JarvisAccessibility"

        private val APP_PACKAGES = mapOf(
            "instagram" to "com.instagram.android",
            "youtube" to "com.google.android.youtube",
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
            val text = intent?.getStringExtra("command") 
                ?: intent?.getStringExtra("text") 
                ?: intent?.getStringExtra(JarvisForegroundService.EXTRA_TEXT) 
                ?: return
            processVoiceCommand(text)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        val filter = IntentFilter(JarvisForegroundService.ACTION_VOICE_COMMAND)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(commandReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                registerReceiver(commandReceiver, filter)
            }
        } catch (e: Exception) {}
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(commandReceiver) } catch (e: Exception) {}
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

    private fun extractAppName(lowerCommand: String): String? {
        val openIndex = lowerCommand.indexOf("open")
        val launchIndex = lowerCommand.indexOf("launch")
        val startIndex = maxOf(openIndex, launchIndex)
        if (startIndex == -1) return null

        val after = lowerCommand.substring(startIndex).trim()
        val words = after.split(Regex("\\s+"))
        if (words.size < 2) return null

        val candidate = words[1].trim(',', ' ')
        return APP_PACKAGES.keys.firstOrNull {
            candidate.contains(it) || it.contains(candidate)
        }
    }

    private fun extractQuery(rawCommand: String): String {
        val lower = rawCommand.lowercase()
        val searchIndex = lower.indexOf("search")
        if (searchIndex == -1) return ""

        var query = rawCommand.substring(searchIndex + "search".length).trim()
        query = query.replace(Regex("(?i)\\s+(and type|type)\\s*$"), "")
        query = query.trim().trim(',', ' ')
        return query
    }

    private fun launchApp(packageName: String) {
        try {
            val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(launchIntent)
            }
        } catch (e: Exception) {}
    }

    private fun findAndClickSearchAndType(query: String) {
        val root = rootInActiveWindow
        if (root == null) {
            handler.postDelayed({ findAndClickSearchAndType(query) }, 700)
            return
        }

        val searchNode = findNode(root) { node -> isSearchNode(node) }

        if (searchNode != null) {
            searchNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            searchNode.recycle()
            // Fix: Wait for 1 second, then run the smart typing logic
            handler.postDelayed({ setTextInEditText(query, 0) }, 1000)
        } else {
            val editText = findNode(root) { node ->
                node.className?.toString()?.contains("EditText") == true && node.isVisibleToUser
            }
            if (editText != null) {
                setTextIntoNode(editText, query)
                editText.recycle()
            }
        }
        root.recycle()
    }

    // Fix: Added a retryCount to support Double-Tap (like clicking bottom tab, then top bar in Instagram)
    private fun setTextInEditText(query: String, retryCount: Int) {
        val root = rootInActiveWindow
        if (root == null) {
            if (retryCount < 3) handler.postDelayed({ setTextInEditText(query, retryCount + 1) }, 700)
            return
        }

        val editText = findNode(root) { node ->
            node.className?.toString()?.contains("EditText") == true && node.isVisibleToUser
        }

        if (editText != null) {
            // Agar seedha typing box mil gaya (e.g. YouTube), toh type kar do
            setTextIntoNode(editText, query)
            editText.recycle()
        } else {
            // Agar typing box nahi mila (e.g. Instagram Explore page), toh upar wale search button ko dhoondh kar click karo
            val secondarySearchNode = findNode(root) { node -> isSearchNode(node) }
            if (secondarySearchNode != null && retryCount < 2) {
                secondarySearchNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                secondarySearchNode.recycle()
                // Click karne ke baad 1 second ruko aur phir se type karne ki koshish karo
                handler.postDelayed({ setTextInEditText(query, retryCount + 1) }, 1000)
            }
        }
        root.recycle()
    }

    private fun findNode(
        root: AccessibilityNodeInfo?,
        predicate: (AccessibilityNodeInfo) -> Boolean
    ): AccessibilityNodeInfo? {
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

        if (desc.contains("voice") || desc.contains("mic") || viewId.contains("voice") || viewId.contains("mic")) {
            return false
        }

        if (text.contains("search") || desc.contains("search")) return true
        if (viewId.contains("search") && !viewId.contains("search_edit_text")) return true
        if (desc.isNotEmpty() && (desc.contains("search") || desc.contains("magnif"))) return true
        if (className.contains("edittext") && node.isClickable) return true

        return false
    }

    private fun setTextIntoNode(node: AccessibilityNodeInfo, text: String) {
        val arguments = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
    }
}
