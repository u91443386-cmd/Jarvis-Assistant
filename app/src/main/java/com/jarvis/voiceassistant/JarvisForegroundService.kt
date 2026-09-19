package com.jarvis.voiceassistant

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.widget.Toast
import androidx.core.app.NotificationCompat

class JarvisForegroundService : Service() {

    private var speechRecognizer: SpeechRecognizer? = null
    private var recognizerIntent: Intent? = null
    private var audioManager: AudioManager? = null

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        startMyForeground()
        startListening()
    }

    private fun startMyForeground() {
        val channelId = "jarvis_id"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Jarvis Engine", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
        val notif = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Jarvis Active")
            .setContentText("Listening for commands...")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .build()
        startForeground(1, notif)
    }

    private fun startListening() {
        audioManager?.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0)

        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        recognizerIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN")
        }

        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                audioManager?.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0)
            }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) {
                speechRecognizer?.destroy()
                startListening()
            }

            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) {
                    val command = matches[0].lowercase().trim()
                    
                    // Ye line screen par dikhayegi ki Jarvis ne kya suna
                    Toast.makeText(applicationContext, "Heard: $command", Toast.LENGTH_SHORT).show()
                    
                    processCommand(command)
                }
                speechRecognizer?.destroy()
                startListening()
            }
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        speechRecognizer?.startListening(recognizerIntent)
    }

    private fun processCommand(command: String) {
        if (command.startsWith("open ")) {
            val appName = command.replace("open ", "").trim()
            openAppByName(appName)
        } 
        else if (command.contains("scroll down") || command.contains("scroll up") || command.contains("go back") || command.contains("home")) {
            val intent = Intent("JARVIS_ACTION")
            // 🔥 Ye line add ki hai taaki Background se Accessibility ko signal mil sake
            intent.setPackage(packageName) 
            intent.putExtra("command", command)
            sendBroadcast(intent)
        }
    }

    private fun openAppByName(appName: String) {
        val pm = packageManager
        val packages = pm.getInstalledApplications(android.content.pm.PackageManager.GET_META_DATA)
        
        for (packageInfo in packages) {
            val name = pm.getApplicationLabel(packageInfo).toString().lowercase()
            if (name.contains(appName)) {
                val intent = pm.getLaunchIntentForPackage(packageInfo.packageName)
                if (intent != null) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    try {
                        startActivity(intent)
                    } catch (e: Exception) {
                        Toast.makeText(applicationContext, "App open block hua!", Toast.LENGTH_SHORT).show()
                    }
                    return 
                }
            }
        }
    }

    override fun onDestroy() {
        audioManager?.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0)
        speechRecognizer?.destroy()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
