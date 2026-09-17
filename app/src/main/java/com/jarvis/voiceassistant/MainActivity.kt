package com.jarvis.voiceassistant
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.ActivityCompat
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { setPadding(50, 50, 50, 50); setOrientation(LinearLayout.VERTICAL) }
        val btn1 = Button(this).apply { text = "1. Enable Accessibility Settings"; setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) } }
        val btn2 = Button(this).apply { text = "2. Start Background Listener Service"; setOnClickListener { 
            val intent = Intent(this@MainActivity, JarvisForegroundService::class.java)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
        }}
        root.addView(btn1); root.addView(btn2); setContentView(root)
        ActivityCompat.requestPermissions(this, arrayOf(android.Manifest.permission.RECORD_AUDIO), 1)
    }
}
