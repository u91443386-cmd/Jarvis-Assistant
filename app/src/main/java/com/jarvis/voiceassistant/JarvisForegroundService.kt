package com.jarvis.voiceassistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import ai.picovoice.porcupine.Porcupine
import java.io.File
import java.io.FileOutputStream

class JarvisForegroundService : Service() {

    companion object {
        const val ACTION_START = "com.jarvis.voiceassistant.START"
        const val ACTION_STOP = "com.jarvis.voiceassistant.STOP"
        const val ACTION_VOICE_COMMAND = "com.jarvis.voiceassistant.VOICE_COMMAND"
        const val EXTRA_TEXT = "text"
        const val CHANNEL_ID = "jarvis_voice_channel"
        const val NOTIFICATION_ID = 1001
        private const val TAG = "JarvisForegroundService"
    }

    private var porcupine: Porcupine? = null
    private var audioRecord: AudioRecord? = null
    private var speechRecognizer: SpeechRecognizer? = null
    private val handler = Handler(Looper.getMainLooper())

    @Volatile
    private var wakeLoopRunning = false
    private var beepMuted = false

    private var originalMusicVolume = 0
    private var originalSystemVolume = 0

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        
        val notification = buildNotification("Listening for 'Jarvis'...")
        
        // BUG FIX: Android 10+ (aur khaas kar Android 14) ke liye Microphone ServiceType zaroori hai
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        
        copyAssets()

        Thread {
            initializePorcupine()
            startWakeWordLoop()
        }.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        wakeLoopRunning = false
        unmuteRecognitionBeep()

        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null

        speechRecognizer?.destroy()
        speechRecognizer = null

        try { porcupine?.delete() } catch (e: Exception) {}
        porcupine = null

        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Jarvis Assistant",
                NotificationManager.IMPORTANCE_LOW
            )
            channel.description = "Continuous offline wake-word detection"
            channel.setSound(null, null)
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
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

    private fun copyAssets() {
        copyAsset("porcupine_params.pv")
        copyAsset("jarvis_android.ppn")
    }

    private fun copyAsset(fileName: String) {
        val dest = File(filesDir, fileName)
        if (dest.exists()) return
        try {
            assets.open(fileName).use { input ->
                FileOutputStream(dest).use { output -> input.copyTo(output) }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy asset: $fileName", e)
        }
    }

    private fun initializePorcupine() {
        try {
            val modelPath = File(filesDir, "porcupine_params.pv").absolutePath
            val keywordPath = File(filesDir, "jarvis_android.ppn").absolutePath

            porcupine = Porcupine.Builder()
                .setKeywordPath(keywordPath)
                .setModelPath(modelPath)
                .setSensitivity(0.7f)
                .build(applicationContext)
        } catch (e: Exception) {
            Log.e(TAG, "Porcupine init failed", e)
            stopSelf()
        }
    }

    private fun startWakeWordLoop() {
        val ppn = porcupine ?: return
        if (wakeLoopRunning) return

        val sampleRate = ppn.sampleRate
        val frameLength = ppn.frameLength

        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val bufferSize = maxOf(minBuf, frameLength * 2 * 2)

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize
        )

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            stopSelf()
            return
        }

        audioRecord?.startRecording()
        wakeLoopRunning = true
        val pcm = ShortArray(frameLength)

        try {
            while (wakeLoopRunning) {
                val read = audioRecord?.read(pcm, 0, frameLength) ?: -1
                if (read > 0) {
                    val keywordIndex = ppn.process(pcm)
                    if (keywordIndex >= 0) {
                        Log.i(TAG, "Jarvis wake word detected!")
                        handler.post {
                            stopWakeWordLoop()
                            startGoogleSpeechAfterWakeWord()
                        }
                        break
                    }
                }
            }
        } catch (e: Exception) {
            handler.post { restartWakeWordLoop() }
        }
    }

    private fun stopWakeWordLoop() {
        wakeLoopRunning = false
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
    }

    private fun restartWakeWordLoop() {
        if (wakeLoopRunning) return
        Thread {
            if (porcupine == null) initializePorcupine()
            startWakeWordLoop()
        }.start()
    }

    private fun startGoogleSpeechAfterWakeWord() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            restartWakeWordLoop()
            return
        }

        muteRecognitionBeep()

        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    handler.postDelayed({ unmuteRecognitionBeep() }, 800)
                    updateNotification("Listening to command...")
                }
                override fun onBeginningOfSpeech() { unmuteRecognitionBeep() }
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() { unmuteRecognitionBeep() }
                override fun onError(error: Int) {
                    unmuteRecognitionBeep()
                    destroyRecognizer()
                    restartWakeWordLoop()
                    updateNotification("Listening for 'Jarvis'...")
                }
                override fun onResults(results: Bundle?) {
                    unmuteRecognitionBeep()
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    if (!matches.isNullOrEmpty()) {
                        val command = matches[0].lowercase()
                        val intent = Intent(ACTION_VOICE_COMMAND)
                        intent.setPackage(packageName)
                        intent.putExtra(EXTRA_TEXT, command)
                        sendBroadcast(intent)
                    }
                    destroyRecognizer()
                    restartWakeWordLoop()
                    updateNotification("Listening for 'Jarvis'...")
                }
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
        }
        speechRecognizer?.startListening(intent)
    }

    private fun destroyRecognizer() {
        try { speechRecognizer?.destroy() } catch (e: Exception) {}
        speechRecognizer = null
    }

    private fun muteRecognitionBeep() {
        if (beepMuted) return
        try {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            originalMusicVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            originalSystemVolume = audioManager.getStreamVolume(AudioManager.STREAM_SYSTEM)
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
            audioManager.setStreamVolume(AudioManager.STREAM_SYSTEM, 0, 0)
            beepMuted = true
        } catch (e: Exception) {}
    }

    private fun unmuteRecognitionBeep() {
        if (!beepMuted) return
        try {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, originalMusicVolume, 0)
            audioManager.setStreamVolume(AudioManager.STREAM_SYSTEM, originalSystemVolume, 0)
        } catch (e: Exception) {}
        finally { beepMuted = false }
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(text))
    }
}
