package com.example.samtaletranskribering

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat

/**
 * Forgrunnstjeneste som kjører kontinuerlig tale-til-tekst via Androids
 * SpeechRecognizer (Googles gjenkjenning på telefonen). Den fanger lyd fra
 * mikrofonen og sender løpende resultater videre til MainActivity via kringkasting.
 *
 * Merk om "parallelle kilder": Android lar ikke en vanlig app lese selve
 * samtalestrømmen (VOICE_CALL) på moderne, ikke-rootede telefoner. Den praktiske
 * løsningen er å sette samtalen på HØYTTALER – da plukker mikrofonen opp begge
 * stemmer, og alt blir transkribert her.
 */
class TranscriptionService : Service() {

    companion object {
        const val ACTION_PARTIAL = "com.example.samtaletranskribering.PARTIAL"
        const val ACTION_FINAL = "com.example.samtaletranskribering.FINAL"
        const val ACTION_STATUS = "com.example.samtaletranskribering.STATUS"
        const val EXTRA_TEXT = "text"
        const val EXTRA_LANGUAGE = "language"
        private const val CHANNEL_ID = "transcription"
        private const val NOTIF_ID = 42
    }

    private var recognizer: SpeechRecognizer? = null
    private var listening = false
    private var stopped = false
    private var language = "nb-NO"
    private var audioManager: AudioManager? = null
    private val handler = Handler(Looper.getMainLooper())

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) { broadcast(ACTION_STATUS, "Lytter …") }
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}

        override fun onError(error: Int) {
            listening = false
            if (stopped) return
            // Restart raskt – NO_MATCH/TIMEOUT er normalt i kontinuerlig lytting.
            val delay = when (error) {
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> 400L
                SpeechRecognizer.ERROR_CLIENT -> 400L
                else -> 100L
            }
            scheduleRestart(delay)
        }

        override fun onResults(results: Bundle?) {
            val text = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull().orEmpty()
            if (text.isNotBlank()) broadcast(ACTION_FINAL, text)
            listening = false
            if (!stopped) scheduleRestart(60L)
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val text = partialResults
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull().orEmpty()
            if (text.isNotBlank()) broadcast(ACTION_PARTIAL, text)
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.getStringExtra(EXTRA_LANGUAGE)?.let { language = it }
        stopped = false
        startForegroundSafely()
        enableSpeaker()
        setupRecognizer()
        scheduleRestart(200L)
        return START_STICKY
    }

    /**
     * Slår på høyttaler (beste forsøk) slik at mikrofonen fanger begge stemmer.
     * Under en vanlig mobilsamtale kan operativsystemet overstyre dette; da må
     * brukeren trykke høyttaler manuelt i Telefon-appen.
     */
    private fun enableSpeaker() {
        try {
            val am = getSystemService(AudioManager::class.java) ?: return
            audioManager = am
            if (Build.VERSION.SDK_INT >= 31) {
                val speaker = am.availableCommunicationDevices
                    .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                if (speaker != null) am.setCommunicationDevice(speaker)
            } else {
                @Suppress("DEPRECATION")
                am.isSpeakerphoneOn = true
            }
        } catch (_: Exception) {}
    }

    private fun clearSpeaker() {
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                audioManager?.clearCommunicationDevice()
            } else {
                @Suppress("DEPRECATION")
                audioManager?.isSpeakerphoneOn = false
            }
        } catch (_: Exception) {}
    }

    private fun setupRecognizer() {
        try { recognizer?.destroy() } catch (_: Exception) {}
        recognizer = try {
            if (Build.VERSION.SDK_INT >= 33 &&
                SpeechRecognizer.isOnDeviceRecognitionAvailable(this)) {
                // Gjenkjenning helt på telefonen (ingen nett) når språkpakken finnes.
                SpeechRecognizer.createOnDeviceSpeechRecognizer(this)
            } else {
                SpeechRecognizer.createSpeechRecognizer(this)
            }
        } catch (e: Exception) {
            SpeechRecognizer.createSpeechRecognizer(this)
        }
        recognizer?.setRecognitionListener(listener)
    }

    private fun recognizerIntent(): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, language)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
        }

    private fun scheduleRestart(delayMs: Long) {
        handler.postDelayed({ startListening() }, delayMs)
    }

    private fun startListening() {
        if (stopped || listening) return
        listening = true
        try {
            recognizer?.startListening(recognizerIntent())
        } catch (e: Exception) {
            listening = false
            if (!stopped) scheduleRestart(500L)
        }
    }

    private fun startForegroundSafely() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID, "Transkribering",
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Transkribering aktiv")
            .setContentText("Lytter og transkriberer samtale")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            ServiceCompat.startForeground(
                this, NOTIF_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun broadcast(action: String, text: String) {
        sendBroadcast(
            Intent(action).apply {
                setPackage(packageName)
                putExtra(EXTRA_TEXT, text)
            }
        )
    }

    override fun onDestroy() {
        stopped = true
        handler.removeCallbacksAndMessages(null)
        clearSpeaker()
        try { recognizer?.stopListening() } catch (_: Exception) {}
        try { recognizer?.destroy() } catch (_: Exception) {}
        recognizer = null
        super.onDestroy()
    }
}
