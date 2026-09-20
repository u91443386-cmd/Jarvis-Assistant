package com.jarvis.voiceassistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.util.Log
import org.json.JSONObject

import org.vosk.Model
import org.vosk.Recognizer

import java.io.File
import java.io.FileOutputStream

class JarvisForegroundService : Service() {

    companion object {
        // PACKAGE NAMES THEEK KIYE GAYE HAIN
        const val ACTION_START = "com.jarvis.voiceassistant.START"
        const val ACTION_STOP = "com.jarvis.voiceassistant.STOP"
        const val ACTION_VOICE_COMMAND = "com.jarvis.voiceassistant.VOICE_COMMAND"
        const val EXTRA_TEXT = "text"
        const val CHANNEL_ID = "jarvis_voice_channel"
        const val NOTIFICATION_ID = 1001

        private const val SAMPLE_RATE = 8000
        private const val HOTWORD = "jarvis"
        private const val TAG = "JarvisForegroundService"
    }

    private var audioRecord: AudioRecord? = null
    private var recognizer: Recognizer? = null
    private var model: Model? = null
    private var listenThread: Thread? = null

    @Volatile
    private var isListening = false
    private var lastSentCommand = ""

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Starting Jarvis..."))
        prepareModelAndStartListening()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                // Already started in onCreate
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        isListening = false
        listenThread?.interrupt()

        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null

        recognizer?.close()
        recognizer = null

        model?.close()
        model = null

        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Jarvis Voice Assistant",
                NotificationManager.IMPORTANCE_LOW
            )
            channel.description = "Continuous offline voice recognition"
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        return builder
            .setContentTitle("Jarvis")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    /**
     * Copies the Vosk model from assets to filesDir/model, then starts listening.
     */
    private fun prepareModelAndStartListening() {
        try {
            val modelDir = File(filesDir, "model")
            if (!modelDir.exists() || modelDir.listFiles()?.isEmpty() != false) {
                copyAssetFolder(this, "model", modelDir)
            }

            
            model = Model(modelDir.absolutePath)
            recognizer = Recognizer(model, SAMPLE_RATE.toFloat())

            isListening = true
            listenThread = Thread { runListeningLoop() }
            listenThread?.start()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize Vosk", e)
            stopSelf()
        }
    }

    /**
     * Reads PCM audio in 16 kHz mono and feeds it to Vosk.
     * This is completely silent – no system beep, no music ducking.
     */
    private fun runListeningLoop() {
        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        if (minBuffer == AudioRecord.ERROR || minBuffer == AudioRecord.ERROR_BAD_VALUE) {
            Log.e(TAG, "Invalid audio buffer size")
            stopSelf()
            return
        }

        val bufferSize = maxOf(minBuffer * 2, 4096)

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION, // Use VOICE_RECOGNITION internally
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize
        )

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord initialization failed")
            stopSelf()
            return
        }

        audioRecord?.startRecording()
        val buffer = ShortArray(minBuffer)
        Log.d(TAG, "Listening silently...")

        while (isListening && !Thread.currentThread().isInterrupted) {
            val read = audioRecord?.read(buffer, 0, buffer.size) ?: -1
            if (read > 0) {
                val recognizer = this.recognizer ?: continue
                val ended = recognizer.acceptWaveForm(buffer, read)
                if (ended) {
                    val resultJson = recognizer.result
                    if (resultJson != null) {
                        val text = parseText(resultJson)
                        handleRecognizedText(text, isFinal = true)
                    }
                }
            }
        }
    }

    private fun parseText(json: String): String {
        return try {
            JSONObject(json).optString("text", "")
        } catch (e: Exception) {
            ""
        }
    }

    /**
     * Checks for the hotword and extracts the command after it.
     * Only final results are used to avoid duplicate triggers.
     */
    private fun handleRecognizedText(text: String, isFinal: Boolean) {
        if (text.isBlank() || !isFinal) return

        val lower = text.lowercase().trim()
        if (lower.contains(HOTWORD)) {
            val command = text.substringAfter(HOTWORD, "").trim()
            if (command.isNotEmpty() && command != lastSentCommand) {
                lastSentCommand = command
                sendCommandBroadcast(command)

                val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                manager.notify(NOTIFICATION_ID, buildNotification("Command: $command"))
            }
        }
    }

    private fun sendCommandBroadcast(text: String) {
        val intent = Intent(ACTION_VOICE_COMMAND)
        intent.setPackage(packageName) // Restrict to our own app
        intent.putExtra(EXTRA_TEXT, text)
        sendBroadcast(intent)
    }

    /**
     * Recursively copies a folder from assets to the destination directory.
     */
    private fun copyAssetFolder(context: Context, assetPath: String, destination: File) {
        val assetManager = context.assets
        val files = assetManager.list(assetPath) ?: return
        destination.mkdirs()

        for (file in files) {
            val fullPath = if (assetPath.isEmpty()) file else "$assetPath/$file"
            val outFile = File(destination, file)

            if (assetManager.list(fullPath).isNullOrEmpty()) {
                // It's a file
                assetManager.open(fullPath).use { input ->
                    FileOutputStream(outFile).use { output ->
                        input.copyTo(output)
                    }
                }
            } else {
                // It's a directory
                copyAssetFolder(context, fullPath, outFile)
            }
        }
    }
}
