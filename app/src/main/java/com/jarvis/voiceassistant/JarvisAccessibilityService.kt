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

        private val SEARCH_ID_HINTS = listOf(
            "search", "action_search", "search_tab", "menu_search",
            "search_box", "search_edit_text"
        )
    }
    
    private val handler = Handler(Looper.getMainLooper())

    private val commandReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val text = intent?.getStringExtra(JarvisForegroundService.EXTRA_TEXT) ?: return
            processVoiceCommand(text)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d(TAG, "Accessibility service connected")
        val filter = IntentFilter(JarvisForegroundService.ACTION_VOICE_COMMAND)
        ContextCompat.registerReceiver(
            this,
            commandReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {
        Log.w(TAG, "Accessibility service interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(commandReceiver)
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
                handler.postDelayed({
                    findAndClickSearchAndType(query)
                }, 2500)
                return
            }
        }

        val query = extractQuery(rawCommand)
        if (query.isNotEmpty()) {
            handler.postDelayed({
                findAndClickSearchAndType(query)
            }, 800)
        } else {
            Log.d(TAG, "No actionable query found")
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
                Log.d(TAG, "Launching $packageName")
            } else {
                Log.w(TAG, "No launch intent for $packageName")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch app", e)
        }
    }

    private fun findAndClickSearchAndType(query: String) {
        val root = rootInActiveWindow
        if (root == null) {
            handler.postDelayed({ findAndClickSearchAndType(query) }, 700)
            return
        }

        val searchNode = findNode(root) { node ->
            isSearchNode(node)
        }

        if (searchNode != null) {
            Log.d(TAG, "Found search node: ${searchNode.viewIdResourceName}, text=${searchNode.text}")
            searchNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            searchNode.recycle()

            handler.postDelayed({
                setTextInEditText(query)
            }, 1000)
        } else {
            val editText = findNode(root) { node ->
                node.className?.toString()?.contains("EditText") == true && node.isVisibleToUser
            }
            if (editText != null) {
                setTextIntoNode(editText, query)
                editText.recycle()
            } else {
                Log.w(TAG, "No search UI found")
            }
        }
        root.recycle()
    }

    private fun findNode(
        root: AccessibilityNodeInfo?,
        predicate: (AccessibilityNodeInfo) -> Boolean
    ): AccessibilityNodeInfo? {
        if (root == null) return null
        if (predicate(root)) {
            return AccessibilityNodeInfo.obtain(root)
        }
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

        // Yahi aapka original logic hai (mic button ignore kiye bina)
        if (text.contains("search") || desc.contains("search")) return true
        if (viewId.contains("search") && !viewId.contains("search_edit_text")) return true
        if (desc.isNotEmpty() && (desc.contains("search") || desc.contains("magnif"))) return true
        if (className.contains("edittext") && node.isClickable) return true

        return false
    }

    private fun setTextInEditText(query: String) {
        val root = rootInActiveWindow
        if (root == null) {
            handler.postDelayed({ setTextInEditText(query) }, 700)
            return
        }

        val editText = findNode(root) { node ->
            node.className?.toString()?.contains("EditText") == true && node.isVisibleToUser
        }

        if (editText != null) {
            setTextIntoNode(editText, query)
            editText.recycle()
        } else {
            Log.w(TAG, "No EditText found")
        }
        root.recycle()
    }

    private fun setTextIntoNode(node: AccessibilityNodeInfo, text: String) {
        val arguments = Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                text
            )
        }
        node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
        Log.d(TAG, "Set text: $text")
    }
}
