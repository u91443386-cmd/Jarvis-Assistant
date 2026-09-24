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
import android.os.Process
import android.os.Vibrator
import android.os.VibrationEffect
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
    
    // FIX 2: Synchronized thread-safe access
    @Volatile private var audioRecord: AudioRecord? = null
    private var speechRecognizer: SpeechRecognizer? = null
    
    private val handler = Handler(Looper.getMainLooper())
    private val audioLock = Any() // Thread lock for AudioRecord

    @Volatile private var wakeLoopRunning = false
    @Volatile private var beepMuted = false // FIX 4: Made Volatile
    @Volatile private var isServiceDestroyed = false // FIX 5: Cancellation flag

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        
        val notification = buildNotification("Listening for 'Jarvis'...")
        
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Foreground Service start prevented from crashing app", e)
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
        isServiceDestroyed = true
        wakeLoopRunning = false
        
        // FIX 7: Remove all delayed callbacks to prevent memory leak
        handler.removeCallbacksAndMessages(null)
        
        // Always unmute on destroy to prevent permanent global mute
        unmuteRecognitionBeep()

        // FIX 2 & 8: Safe cleanup within try-catch and locks
        try {
            synchronized(audioLock) {
                audioRecord?.stop()
                audioRecord?.release()
                audioRecord = null
            }
        } catch (e: Exception) {}

        try {
            speechRecognizer?.destroy()
            speechRecognizer = null
        } catch (e: Exception) {}

        try {
            porcupine?.delete()
            porcupine = null
        } catch (e: Exception) {}

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
        // FIX 6: Added proper intent flags for new task
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent, PendingIntent.FLAG_IMMUTABLE
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
        } catch (e: Exception) {}
    }

    private fun initializePorcupine() {
        if (isServiceDestroyed) return
        try {
            val modelPath = File(filesDir, "porcupine_params.pv").absolutePath
            val keywordPath = File(filesDir, "jarvis_android.ppn").absolutePath

            porcupine = Porcupine.Builder()
                .setKeywordPath(keywordPath)
                .setModelPath(modelPath)
                .setSensitivity(0.85f)
                .build(applicationContext)
        } catch (e: Exception) {
            stopSelf()
        }
    }

    private fun startWakeWordLoop() {
        if (isServiceDestroyed || wakeLoopRunning) return
        
        val ppn = porcupine ?: return
        val sampleRate = ppn.sampleRate
        val frameLength = ppn.frameLength

        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val bufferSize = maxOf(minBuf, frameLength * 2 * 2)

        try {
            synchronized(audioLock) {
                audioRecord = AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize
                )
                
                // FIX 9: Release resources if not initialized
                if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                    audioRecord?.release()
                    audioRecord = null
                    stopSelf()
                    return
                }

                // FIX 1: startRecording inside crash shield
                audioRecord?.startRecording()
            }
        } catch (e: Exception) {
            stopSelf()
            return
        }

        wakeLoopRunning = true
        val pcm = ShortArray(frameLength)
        
        // Micro-optimization: Give audio thread priority
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)

        try {
            while (wakeLoopRunning && !isServiceDestroyed) {
                var totalRead = 0
                // FIX 3: Prevent partial reads causing false detections
                while (totalRead < frameLength && wakeLoopRunning) {
                    val read = audioRecord?.read(pcm, totalRead, frameLength - totalRead) ?: -1
                    if (read < 0) throw Exception("Audio read error")
                    if (read == 0) continue // Rare but safe
                    totalRead += read
                }

                if (totalRead == frameLength) {
                    val keywordIndex = ppn.process(pcm)
                    if (keywordIndex >= 0) {
                        try {
                            val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                vibrator.vibrate(VibrationEffect.createOneShot(150, VibrationEffect.DEFAULT_AMPLITUDE))
                            } else {
                                @Suppress("DEPRECATION")
                                vibrator.vibrate(150)
                            }
                        } catch (e: Exception) {}

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
        synchronized(audioLock) {
            try {
                audioRecord?.stop()
                audioRecord?.release()
                audioRecord = null
            } catch (e: Exception) {}
        }
    }

    private fun restartWakeWordLoop() {
        if (wakeLoopRunning || isServiceDestroyed) return
        Thread {
            if (porcupine == null) initializePorcupine()
            startWakeWordLoop()
        }.start()
    }

    private fun startGoogleSpeechAfterWakeWord() {
        if (!SpeechRecognizer.isRecognitionAvailable(this) || isServiceDestroyed) {
            restartWakeWordLoop()
            return
        }

        muteRecognitionBeep()

        try {
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
        } catch (e: Exception) {
            unmuteRecognitionBeep()
            restartWakeWordLoop()
        }
    }

    private fun destroyRecognizer() {
        try { speechRecognizer?.destroy() } catch (e: Exception) {}
        speechRecognizer = null
    }

    private fun muteRecognitionBeep() {
        if (beepMuted) return
        try {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0)
                audioManager.adjustStreamVolume(AudioManager.STREAM_SYSTEM, AudioManager.ADJUST_MUTE, 0)
                audioManager.adjustStreamVolume(AudioManager.STREAM_NOTIFICATION, AudioManager.ADJUST_MUTE, 0)
                audioManager.adjustStreamVolume(AudioManager.STREAM_ALARM, AudioManager.ADJUST_MUTE, 0)
            } else {
                @Suppress("DEPRECATION")
                audioManager.setStreamMute(AudioManager.STREAM_MUSIC, true)
                @Suppress("DEPRECATION")
                audioManager.setStreamMute(AudioManager.STREAM_SYSTEM, true)
                @Suppress("DEPRECATION")
                audioManager.setStreamMute(AudioManager.STREAM_NOTIFICATION, true)
                @Suppress("DEPRECATION")
                audioManager.setStreamMute(AudioManager.STREAM_ALARM, true)
            }
            beepMuted = true
        } catch (e: Exception) {}
    }

    private fun unmuteRecognitionBeep() {
        if (!beepMuted) return
        try {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0)
                audioManager.adjustStreamVolume(AudioManager.STREAM_SYSTEM, AudioManager.ADJUST_UNMUTE, 0)
                audioManager.adjustStreamVolume(AudioManager.STREAM_NOTIFICATION, AudioManager.ADJUST_UNMUTE, 0)
                audioManager.adjustStreamVolume(AudioManager.STREAM_ALARM, AudioManager.ADJUST_UNMUTE, 0)
            } else {
                @Suppress("DEPRECATION")
                audioManager.setStreamMute(AudioManager.STREAM_MUSIC, false)
                @Suppress("DEPRECATION")
                audioManager.setStreamMute(AudioManager.STREAM_SYSTEM, false)
                @Suppress("DEPRECATION")
                audioManager.setStreamMute(AudioManager.STREAM_NOTIFICATION, false)
                @Suppress("DEPRECATION")
                audioManager.setStreamMute(AudioManager.STREAM_ALARM, false)
            }
        } catch (e: Exception) {}
        finally { beepMuted = false }
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(text))
    }
}
