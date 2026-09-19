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

        // Common package names for launching apps by voice
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
            "telegram" to "org.telegram.messenger"
        )

        // Substrings commonly present in search icon/bar resource IDs
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

        // Register receiver for commands from JarvisForegroundService
        val filter = IntentFilter(JarvisForegroundService.ACTION_VOICE_COMMAND)
        ContextCompat.registerReceiver(
            this,
            commandReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // We react to broadcast commands, no need to parse events here.
    }

    override fun onInterrupt() {
        Log.w(TAG, "Accessibility service interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(commandReceiver)
    }

    /**
     * Main entry point from voice broadcasts.
     * Examples:
     * - "open instagram" -> launch Instagram
     * - "open instagram search utkarsh_0114" -> launch Instagram, tap search, type utkarsh_0114
     * - "search utkarsh_0114" -> in current app, tap search, type utkarsh_0114
     */
    private fun processVoiceCommand(rawCommand: String) {
        if (rawCommand.isBlank()) return
        val lower = rawCommand.lowercase().trim()

        // Extract app name if command says "open <app>"
        val appName = extractAppName(lower)
        if (appName != null) {
            val packageName = APP_PACKAGES[appName]
            if (packageName != null) {
                launchApp(packageName)

                val query = extractQuery(rawCommand)
                handler.postDelayed({
                    findAndClickSearchAndType(query)
                }, 2500)  // Wait for app to load
                return
            }
        }

        // If no app launch, just search in current screen
        val query = extractQuery(rawCommand)
        if (query.isNotEmpty()) {
            handler.postDelayed({
                findAndClickSearchAndType(query)
            }, 800)
        } else {
            Log.d(TAG, "No actionable query found")
        }
    }

    /**
     * Extracts the app name after "open" or "launch".
     * Returns null if none found.
     */
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

    /**
     * Extracts the search query from voice command.
     * Everything after "search", and also strips trailing "and type".
     */
    private fun extractQuery(rawCommand: String): String {
        val lower = rawCommand.lowercase()
        val searchIndex = lower.indexOf("search")
        if (searchIndex == -1) return ""

        var query = rawCommand.substring(searchIndex + "search".length).trim()

        // Remove trailing filler like "and type", "type"
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

    /**
     * After the app is open, recursively search for a search icon/bar and click it.
     * Then set text in the focused EditText.
     */
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

            // Wait for edit text to appear after clicking search
            handler.postDelayed({
                setTextInEditText(query)
            }, 1000)
        } else {
            // If no search icon/bar, look for an EditText directly
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

    /**
     * Recursively traverses the accessibility node tree.
     * Returns a copy of the first node that matches [predicate].
     */
    private fun findNode(
        root: AccessibilityNodeInfo?,
        predicate: (AccessibilityNodeInfo) -> Boolean
    ): AccessibilityNodeInfo? {
        if (root == null) return null

        if (predicate(root)) {
            // Return a copy to avoid lifecycle issues after recycling siblings
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

    /**
     * Checks whether an [AccessibilityNodeInfo] represents a search icon or bar.
     */
    private fun isSearchNode(node: AccessibilityNodeInfo): Boolean {
        if (!node.isVisibleToUser) return false

        val text = node.text?.toString()?.lowercase() ?: ""
        val desc = node.contentDescription?.toString()?.lowercase() ?: ""
        val viewId = node.viewIdResourceName?.lowercase() ?: ""
        val className = node.className?.toString()?.lowercase() ?: ""

        // Text or content description mentions search
        if (text.contains("search") || desc.contains("search")) return true

        // Common view ID patterns
        if (viewId.contains("search") && !viewId.contains("search_edit_text")) return true

        // Magnifying glass description hints
        if (desc.isNotEmpty() && (desc.contains("search") || desc.contains("magnif"))) return true

        // A visible, clickable EditText is also a valid search bar
        if (className.contains("edittext") && node.isClickable) return true

        return false
    }

    /**
     * Finds the currently focused/visible EditText and sets the query text.
     */
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

    /**
     * Performs ACTION_SET_TEXT on the given EditText node.
     */
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
