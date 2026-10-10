package com.example.samtaletranskribering

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var statusView: TextView
    private lateinit var transcriptView: TextView
    private lateinit var scroll: ScrollView
    private lateinit var startButton: Button
    private lateinit var saveButton: Button
    private lateinit var languageSpinner: Spinner

    // Visningsnavn → språkkode (BCP-47)
    private val languages = listOf(
        "Norsk bokmål" to "nb-NO",
        "Norsk nynorsk" to "nn-NO",
        "English (US)" to "en-US",
        "English (UK)" to "en-GB",
        "Svenska" to "sv-SE",
        "Dansk" to "da-DK",
        "Suomi" to "fi-FI",
        "Íslenska" to "is-IS",
        "Deutsch" to "de-DE",
        "Nederlands" to "nl-NL",
        "Français" to "fr-FR",
        "Español" to "es-ES",
        "Italiano" to "it-IT",
        "Português (BR)" to "pt-BR",
        "Polski" to "pl-PL",
        "Русский" to "ru-RU",
        "Українська" to "uk-UA",
        "Türkçe" to "tr-TR",
        "العربية" to "ar",
        "हिन्दी" to "hi-IN",
        "中文 (简体)" to "zh-CN",
        "日本語" to "ja-JP",
        "한국어" to "ko-KR"
    )
    private var selectedLanguage = "nb-NO"
    private val prefs by lazy { getSharedPreferences("prefs", MODE_PRIVATE) }

    private val lines = StringBuilder()
    private var partial = ""
    private var running = false

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            val micGranted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
            if (micGranted) startTranscription()
            else Toast.makeText(this, "Mikrofon-tillatelse kreves", Toast.LENGTH_LONG).show()
        }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val text = intent?.getStringExtra(TranscriptionService.EXTRA_TEXT).orEmpty()
            when (intent?.action) {
                TranscriptionService.ACTION_PARTIAL -> { partial = text; render() }
                TranscriptionService.ACTION_FINAL -> {
                    if (text.isNotBlank()) {
                        val ts = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
                        lines.append("[").append(ts).append("] ").append(text.trim()).append("\n")
                    }
                    partial = ""
                    render()
                }
                TranscriptionService.ACTION_STATUS -> statusView.text = text
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusView = findViewById(R.id.status)
        transcriptView = findViewById(R.id.transcript)
        scroll = findViewById(R.id.scroll)
        startButton = findViewById(R.id.startButton)
        saveButton = findViewById(R.id.saveButton)
        languageSpinner = findViewById(R.id.languageSpinner)

        val adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_item, languages.map { it.first }
        )
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        languageSpinner.adapter = adapter
        languageSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                selectedLanguage = languages[pos].second
                prefs.edit().putString("language", selectedLanguage).apply()
                if (running) startTranscription() // bytt språk live ved å restarte tjenesten
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        // Gjenopprett sist valgte språk
        selectedLanguage = prefs.getString("language", "nb-NO") ?: "nb-NO"
        val startIndex = languages.indexOfFirst { it.second == selectedLanguage }.coerceAtLeast(0)
        languageSpinner.setSelection(startIndex)

        startButton.setOnClickListener {
            if (running) stopTranscription() else requestAndStart()
        }
        saveButton.setOnClickListener { saveTranscript() }
        render()
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter().apply {
            addAction(TranscriptionService.ACTION_PARTIAL)
            addAction(TranscriptionService.ACTION_FINAL)
            addAction(TranscriptionService.ACTION_STATUS)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(receiver, filter)
        }
    }

    override fun onStop() {
        super.onStop()
        try { unregisterReceiver(receiver) } catch (_: Exception) {}
    }

    private fun requestAndStart() {
        val needed = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) needed.add(Manifest.permission.POST_NOTIFICATIONS)
        val missing = needed.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) startTranscription()
        else permissionLauncher.launch(missing.toTypedArray())
    }

    private fun startTranscription() {
        val intent = Intent(this, TranscriptionService::class.java)
            .putExtra(TranscriptionService.EXTRA_LANGUAGE, selectedLanguage)
        ContextCompat.startForegroundService(this, intent)
        running = true
        startButton.text = "Stopp"
        statusView.text = "Starter … (slår på høyttaler automatisk)"
    }

    private fun stopTranscription() {
        stopService(Intent(this, TranscriptionService::class.java))
        running = false
        startButton.text = "Start"
        statusView.text = "Stoppet"
    }

    private fun render() {
        val sb = StringBuilder(lines)
        if (partial.isNotBlank()) sb.append("» ").append(partial)
        transcriptView.text = sb.toString()
        scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun saveTranscript() {
        if (lines.isBlank()) {
            Toast.makeText(this, "Ingenting å lagre ennå", Toast.LENGTH_SHORT).show()
            return
        }
        val name = "samtale_" +
            SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date()) + ".txt"
        val dir = getExternalFilesDir(null) ?: filesDir
        val file = File(dir, name)
        file.writeText(lines.toString())
        Toast.makeText(this, "Lagret: ${file.absolutePath}", Toast.LENGTH_LONG).show()
    }
}
