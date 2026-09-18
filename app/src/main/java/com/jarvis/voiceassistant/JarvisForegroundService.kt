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
import android.util.Log
import androidx.core.app.NotificationCompat

class JarvisForegroundService : Service() {

    private var speechRecognizer: SpeechRecognizer? = null
    private var recognizerIntent: Intent? = null
    private var audioManager: AudioManager? = null

    override fun onCreate() {
        super.onCreate()
        // Audio Manager set kiya taaki beep sound band kar sakein
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
        // Mic start hone se pehle system aawaz MUTE kar do taaki Beep na baje
        audioManager?.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0)

        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        recognizerIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN")
        }

        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                // Jaise hi sunna shuru ho jaye, aawaz wapas UNMUTE kar do
                audioManager?.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0)
            }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) {
                speechRecognizer?.destroy()
                startListening() // Restart loop
            }

            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) {
                    val command = matches[0].lowercase()
                    Log.d("Jarvis", "Command Heard: $command")
                    processCommand(command)
                }
                speechRecognizer?.destroy()
                startListening() // Restart loop
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
            intent.putExtra("command", command)
            sendBroadcast(intent)
        }
    }

    private fun openAppByName(appName: String) {
        val pm = packageManager
        // QUERY_ALL_PACKAGES permission ke baad ye line properly saare apps dhoondhegi
        val packages = pm.getInstalledApplications(android.content.pm.PackageManager.GET_META_DATA)
        
        for (packageInfo in packages) {
            val name = pm.getApplicationLabel(packageInfo).toString().lowercase()
            if (name.contains(appName)) {
                val intent = pm.getLaunchIntentForPackage(packageInfo.packageName)
                if (intent != null) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(intent)
                    return 
                }
            }
        }
    }

    override fun onDestroy() {
        // App band hone par sure karein ki volume unmute rahe
        audioManager?.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0)
        speechRecognizer?.destroy()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
