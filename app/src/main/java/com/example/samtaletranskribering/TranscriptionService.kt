package com.example.samtaletranskribering

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ComponentName
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
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat

/**
 * Forgrunnstjeneste som kjører kontinuerlig tale-til-tekst via Googles
 * talegjenkjenning på telefonen (SpeechRecognizer). Resultater sendes til
 * MainActivity via kringkasting.
 *
 * Merk: Android lar ikke en vanlig app lese selve samtalestrømmen. Samtalen
 * settes derfor på HØYTTALER, slik at mikrofonen fanger begge stemmer.
 */
class TranscriptionService : Service() {

    companion object {
        const val ACTION_PARTIAL = "com.example.samtaletranskribering.PARTIAL"
        const val ACTION_FINAL = "com.example.samtaletranskribering.FINAL"
        const val ACTION_STATUS = "com.example.samtaletranskribering.STATUS"
        const val ACTION_STOPPED = "com.example.samtaletranskribering.STOPPED"
        const val EXTRA_TEXT = "text"
        const val EXTRA_LANGUAGE = "language"
        const val EXTRA_LEVEL = "level"
        private const val CHANNEL_ID = "transcription"
        private const val NOTIF_ID = 42
        private const val GOOGLE_APP = "com.google.android.googlequicksearchbox"
        private const val MAX_FAILURES = 5
    }

    /** En talegjenkjenningstjeneste appen kan prøve. */
    private data class Engine(
        val label: String,
        val component: ComponentName?,
        val onDevice: Boolean = false
    )

    private var engines: List<Engine> = emptyList()
    private var engineIndex = 0
    private var failures = 0

    private var recognizer: SpeechRecognizer? = null
    private var listening = false
    private var stopped = false
    private var language = "nb-NO"
    private var audioManager: AudioManager? = null
    private val mutedStreams = mutableListOf<Int>()
    private val handler = Handler(Looper.getMainLooper())

    // Lydnivå for replikken som pågår – brukes til å skille talere.
    private val allSamples = ArrayList<Float>()
    private val speechSamples = ArrayList<Float>()
    private var inSpeech = false

    /** Snittet av den sterkeste halvdelen av målingene = hvor kraftig stemmen var. */
    private fun utteranceLevel(): Float {
        val src = if (speechSamples.isNotEmpty()) speechSamples else allSamples
        if (src.isEmpty()) return 0f
        val sorted = src.sortedDescending()
        val top = sorted.take(maxOf(1, sorted.size / 2))
        return top.average().toFloat()
    }

    private fun engineLabel(): String = engines.getOrNull(engineIndex)?.label ?: "?"

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            allSamples.clear(); speechSamples.clear(); inSpeech = false
            broadcast(ACTION_STATUS, "Lytter … (${engineLabel()})")
        }
        override fun onBeginningOfSpeech() { inSpeech = true }
        override fun onRmsChanged(rmsdB: Float) {
            allSamples.add(rmsdB)
            if (inSpeech) speechSamples.add(rmsdB)
        }
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() { inSpeech = false }

        override fun onError(error: Int) {
            listening = false
            if (stopped) return
            when (error) {
                // Normalt ved stillhet – bare lytt videre.
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
                SpeechRecognizer.ERROR_NO_MATCH -> {
                    failures = 0
                    scheduleRestart(250L)
                }
                // Denne tjenesten kan ikke brukes – prøv neste.
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS, 12, 13 ->
                    nextEngine(errorText(error))
                else -> {
                    failures++
                    if (failures >= MAX_FAILURES) {
                        nextEngine(errorText(error))
                        return
                    }
                    broadcast(ACTION_STATUS, "${errorText(error)} – prøver igjen …")
                    if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY ||
                        error == SpeechRecognizer.ERROR_CLIENT || error == 11) {
                        createRecognizer()
                    }
                    scheduleRestart(minOf(8000L, 500L shl (failures - 1)))
                }
            }
        }

        override fun onResults(results: Bundle?) {
            val text = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull().orEmpty()
            if (text.isNotBlank()) broadcast(ACTION_FINAL, text, utteranceLevel())
            failures = 0
            listening = false
            if (!stopped) scheduleRestart(60L)
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val text = partialResults
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull().orEmpty()
            if (text.isNotBlank()) {
                failures = 0
                broadcast(ACTION_PARTIAL, text)
            }
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.getStringExtra(EXTRA_LANGUAGE)?.let { language = it }
        handler.removeCallbacksAndMessages(null)
        stopped = false
        failures = 0
        startForegroundSafely()
        enableSpeaker()
        muteBeeps()

        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            broadcast(ACTION_STATUS,
                "Stoppet: Fant ingen talegjenkjenning. Installer/oppdater Google-appen fra Play Butikk.")
            stopFully()
            return START_NOT_STICKY
        }

        engines = findEngines()
        engineIndex = 0
        createRecognizer()
        scheduleRestart(300L)
        // Ikke START_STICKY: Android 14+ tillater ikke å starte mikrofontjenesten på nytt i bakgrunnen.
        return START_NOT_STICKY
    }

    /** Googles tjeneste først, deretter telefonens standard, til slutt gjenkjenning på enheten. */
    @Suppress("DEPRECATION")
    private fun findEngines(): List<Engine> {
        val list = mutableListOf<Engine>()
        val services = try {
            packageManager.queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE), 0)
        } catch (_: Exception) {
            emptyList()
        }
        val google = services.filter { it.serviceInfo.packageName == GOOGLE_APP }
        val otherGoogle = services.filter {
            it.serviceInfo.packageName != GOOGLE_APP &&
                it.serviceInfo.packageName.startsWith("com.google.")
        }
        for (s in google + otherGoogle) {
            list.add(Engine("Google", ComponentName(s.serviceInfo.packageName, s.serviceInfo.name)))
        }
        list.add(Engine("telefonens standard", null))
        if (Build.VERSION.SDK_INT >= 33) {
            try {
                if (SpeechRecognizer.isOnDeviceRecognitionAvailable(this)) {
                    list.add(Engine("på enheten", null, onDevice = true))
                }
            } catch (_: Exception) {}
        }
        return list
    }

    private fun createRecognizer() {
        try { recognizer?.destroy() } catch (_: Exception) {}
        recognizer = null
        listening = false
        val engine = engines.getOrNull(engineIndex) ?: return
        recognizer = try {
            when {
                engine.onDevice && Build.VERSION.SDK_INT >= 33 ->
                    SpeechRecognizer.createOnDeviceSpeechRecognizer(this)
                engine.component != null ->
                    SpeechRecognizer.createSpeechRecognizer(this, engine.component)
                else -> SpeechRecognizer.createSpeechRecognizer(this)
            }
        } catch (_: Exception) {
            null
        }
        recognizer?.setRecognitionListener(listener)
    }

    private fun nextEngine(reason: String) {
        engineIndex++
        failures = 0
        if (engineIndex >= engines.size) {
            broadcast(ACTION_STATUS,
                "Stoppet: $reason. Sjekk internett, og at Google-appen er installert og oppdatert.")
            stopFully()
            return
        }
        broadcast(ACTION_STATUS, "$reason – prøver ${engineLabel()} …")
        createRecognizer()
        scheduleRestart(500L)
    }

    private fun errorText(e: Int): String = when (e) {
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Nettverket svarer ikke (1)"
        SpeechRecognizer.ERROR_NETWORK -> "Ingen nettforbindelse (2)"
        SpeechRecognizer.ERROR_AUDIO -> "Får ikke tilgang til mikrofonen (3)"
        SpeechRecognizer.ERROR_SERVER -> "Feil hos talegjenkjenningen (4)"
        SpeechRecognizer.ERROR_CLIENT -> "Klientfeil (5)"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Talegjenkjenningen er opptatt (8)"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Mangler mikrofontillatelse (9)"
        10 -> "For mange forespørsler (10)"
        11 -> "Mistet forbindelsen til tjenesten (11)"
        12 -> "Språket støttes ikke av ${engineLabel()} (12)"
        13 -> "Språket er ikke lastet ned for ${engineLabel()} (13)"
        else -> "Feil $e"
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

    /**
     * Demper pipelyden talegjenkjenningen spiller ved hver omstart. Ringelyden
     * røres ikke, så innkommende anrop høres fortsatt. Gjenopprettes ved stopp.
     */
    private fun muteBeeps() {
        val am = audioManager ?: getSystemService(AudioManager::class.java) ?: return
        audioManager = am
        val ringWasMuted = try { am.isStreamMute(AudioManager.STREAM_RING) } catch (_: Exception) { false }
        for (s in intArrayOf(
            AudioManager.STREAM_MUSIC,
            AudioManager.STREAM_SYSTEM,
            AudioManager.STREAM_NOTIFICATION
        )) {
            try {
                if (am.isStreamMute(s)) continue
                am.adjustStreamVolume(s, AudioManager.ADJUST_MUTE, 0)
                mutedStreams.add(s)
                // Hvis varsler og ringelyd er koblet sammen, må ikke ringelyden dempes.
                if (s == AudioManager.STREAM_NOTIFICATION && !ringWasMuted &&
                    am.isStreamMute(AudioManager.STREAM_RING)) {
                    am.adjustStreamVolume(s, AudioManager.ADJUST_UNMUTE, 0)
                    mutedStreams.remove(s)
                }
            } catch (_: Exception) {}
        }
    }

    private fun restoreSound() {
        val am = audioManager ?: return
        for (s in mutedStreams) {
            try { am.adjustStreamVolume(s, AudioManager.ADJUST_UNMUTE, 0) } catch (_: Exception) {}
        }
        mutedStreams.clear()
    }

    private fun recognizerIntent(): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, language)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
        }

    private fun scheduleRestart(delayMs: Long) {
        handler.postDelayed({ startListening() }, delayMs)
    }

    private fun startListening() {
        if (stopped || listening) return
        val r = recognizer
        if (r == null) {
            nextEngine("Kunne ikke starte ${engineLabel()}")
            return
        }
        listening = true
        try {
            r.startListening(recognizerIntent())
        } catch (e: Exception) {
            listening = false
            failures++
            if (failures >= MAX_FAILURES) nextEngine("Kunne ikke starte ${engineLabel()}")
            else scheduleRestart(1000L)
        }
    }

    private fun stopFully() {
        stopped = true
        handler.removeCallbacksAndMessages(null)
        sendBroadcast(Intent(ACTION_STOPPED).setPackage(packageName))
        stopSelf()
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
            .setSilent(true)
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

    private fun broadcast(action: String, text: String, level: Float? = null) {
        sendBroadcast(
            Intent(action).apply {
                setPackage(packageName)
                putExtra(EXTRA_TEXT, text)
                if (level != null) putExtra(EXTRA_LEVEL, level)
            }
        )
    }

    override fun onDestroy() {
        stopped = true
        handler.removeCallbacksAndMessages(null)
        clearSpeaker()
        restoreSound()
        try { recognizer?.stopListening() } catch (_: Exception) {}
        try { recognizer?.destroy() } catch (_: Exception) {}
        recognizer = null
        super.onDestroy()
    }
}
