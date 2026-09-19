package com.jarvis.voiceassistant

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private val RECORD_AUDIO_REQUEST = 1001

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 1. Open accessibility settings so the user can enable JarvisAccessibilityService
        findViewById<Button>(R.id.btn_accessibility).setOnClickListener {
            openAccessibilitySettings()
        }

        // 2. Start foreground service with Vosk (after requesting mic permission)
        findViewById<Button>(R.id.btn_start).setOnClickListener {
            startVoiceServiceWithPermissionCheck()
        }

        // 3. Stop the service completely
        findViewById<Button>(R.id.btn_stop).setOnClickListener {
            stopVoiceService()
        }
    }

    private fun openAccessibilitySettings() {
        try {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        } catch (e: Exception) {
            Toast.makeText(this, "Cannot open accessibility settings", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startVoiceServiceWithPermissionCheck() {
        val permissions = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13+ notification permission is required for foreground service notifications
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isEmpty()) {
            startVoiceService()
        } else {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), RECORD_AUDIO_REQUEST)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == RECORD_AUDIO_REQUEST) {
            if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                startVoiceService()
            } else {
                Toast.makeText(this, "Microphone permission is required", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun startVoiceService() {
        val intent = Intent(this, JarvisForegroundService::class.java)
        intent.action = JarvisForegroundService.ACTION_START
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun stopVoiceService() {
        val intent = Intent(this, JarvisForegroundService::class.java)
        intent.action = JarvisForegroundService.ACTION_STOP
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }
}
