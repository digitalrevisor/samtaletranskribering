package com.example.samtaletranskribering

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ListView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var statusView: TextView
    private lateinit var partialView: TextView
    private lateinit var listView: ListView
    private lateinit var startButton: Button
    private lateinit var newButton: Button
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

    // Talere: 0 = Meg, 1 = Anroper (navn kan endres ved å holde inne en linje)
    private val names = arrayOf("Meg", "Anroper")
    private val speakerColors = intArrayOf(
        Color.parseColor("#2F80ED"),
        Color.parseColor("#E8772E")
    )

    private val entries = mutableListOf<Entry>()
    private lateinit var adapter: TranscriptAdapter
    private var running = false

    // Gjeldende samtale – lagres automatisk for hver ny replikk
    private var sessionName: String? = null
    private var sessionDownloadUri: Uri? = null

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
                TranscriptionService.ACTION_PARTIAL -> showPartial(text)
                TranscriptionService.ACTION_FINAL -> {
                    if (text.isNotBlank()) {
                        val ts = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
                        val level = intent.getFloatExtra(TranscriptionService.EXTRA_LEVEL, 0f)
                        if (sessionName == null) sessionName = TranscriptStore.newSessionName()
                        entries.add(Entry(ts, text.trim(), level))
                        SpeakerClassifier.relabel(entries)
                        adapter.notifyDataSetChanged()
                        listView.setSelection(entries.size - 1)
                        persist()
                    }
                    showPartial("")
                }
                TranscriptionService.ACTION_STATUS -> statusView.text = text
                TranscriptionService.ACTION_STOPPED -> {
                    running = false
                    startButton.text = "Start"
                    showPartial("")
                }
            }
        }
    }

    /** Viser hver replikk med farget talernavn, tid og tekst. */
    private inner class TranscriptAdapter :
        ArrayAdapter<Entry>(this@MainActivity, 0, entries) {
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val tv = (convertView as? TextView) ?: TextView(context).apply {
                val pad = (8 * resources.displayMetrics.density).toInt()
                setPadding(pad, pad, pad, pad)
                textSize = 16f
            }
            val e = entries[position]
            val sb = SpannableStringBuilder()
            val name = names[e.speaker]
            sb.append(name)
            sb.setSpan(StyleSpan(Typeface.BOLD), 0, name.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(
                ForegroundColorSpan(speakerColors[e.speaker]), 0, name.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            val metaStart = sb.length
            sb.append("  ").append(e.time)
            if (e.manual) sb.append("  ✓")
            sb.setSpan(RelativeSizeSpan(0.8f), metaStart, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(ForegroundColorSpan(Color.GRAY), metaStart, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.append("\n").append(e.text)
            tv.text = sb
            return tv
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusView = findViewById(R.id.status)
        partialView = findViewById(R.id.partial)
        listView = findViewById(R.id.transcriptList)
        startButton = findViewById(R.id.startButton)
        newButton = findViewById(R.id.newButton)
        languageSpinner = findViewById(R.id.languageSpinner)

        names[0] = prefs.getString("name0", names[0]) ?: names[0]
        names[1] = prefs.getString("name1", names[1]) ?: names[1]

        adapter = TranscriptAdapter()
        listView.adapter = adapter
        // Trykk: bytt taler på linjen (appen lærer av rettelsen)
        listView.setOnItemClickListener { _, _, pos, _ ->
            val e = entries[pos]
            e.speaker = 1 - e.speaker
            e.manual = true
            SpeakerClassifier.relabel(entries)
            adapter.notifyDataSetChanged()
            persist()
        }
        // Hold inne: gi taleren et navn
        listView.setOnItemLongClickListener { _, _, pos, _ ->
            renameSpeaker(entries[pos].speaker)
            true
        }

        val langAdapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_item, languages.map { it.first }
        )
        langAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        languageSpinner.adapter = langAdapter
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
        newButton.setOnClickListener { confirmNewSession() }

        // Mottakeren registreres her (ikke i onStart) slik at teksten fortsetter
        // å komme inn mens du er i Telefon-appen.
        val filter = IntentFilter().apply {
            addAction(TranscriptionService.ACTION_PARTIAL)
            addAction(TranscriptionService.ACTION_FINAL)
            addAction(TranscriptionService.ACTION_STATUS)
            addAction(TranscriptionService.ACTION_STOPPED)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(receiver, filter)
        }
    }

    override fun onDestroy() {
        try { unregisterReceiver(receiver) } catch (_: Exception) {}
        super.onDestroy()
    }

    private fun showPartial(text: String) {
        partialView.text = if (text.isBlank()) "" else "» $text"
        partialView.visibility = if (text.isBlank()) View.GONE else View.VISIBLE
    }

    private fun renameSpeaker(index: Int) {
        val input = EditText(this).apply {
            setText(names[index])
            setSelection(text.length)
        }
        AlertDialog.Builder(this)
            .setTitle("Navn på taler")
            .setView(input)
            .setPositiveButton("OK") { _, _ ->
                val n = input.text.toString().trim()
                if (n.isNotEmpty()) {
                    names[index] = n
                    prefs.edit().putString("name$index", n).apply()
                    adapter.notifyDataSetChanged()
                    persist()
                }
            }
            .setNegativeButton("Avbryt", null)
            .show()
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
        showPartial("")
    }

    /** Lagrer gjeldende samtale i appen og som kopi i Nedlastinger. */
    private fun persist() {
        val name = sessionName ?: return
        if (entries.isEmpty()) return
        val content = TranscriptStore.format(entries, names)
        try {
            TranscriptStore.saveInternal(this, name, content)
        } catch (_: Exception) {
            statusView.text = "Kunne ikke lagre samtalen"
        }
        sessionDownloadUri = TranscriptStore.saveToDownloads(this, name, content, sessionDownloadUri)
    }

    private fun confirmNewSession() {
        if (entries.isEmpty()) return
        AlertDialog.Builder(this)
            .setTitle("Starte ny samtale?")
            .setMessage("Den nåværende samtalen er lagret og finnes under «Lagrede».")
            .setPositiveButton("Ny samtale") { _, _ ->
                entries.clear()
                sessionName = null
                sessionDownloadUri = null
                adapter.notifyDataSetChanged()
                showPartial("")
            }
            .setNegativeButton("Avbryt", null)
            .show()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, MENU_SAVED, 0, "Lagrede")
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS or MenuItem.SHOW_AS_ACTION_WITH_TEXT)
        menu.add(0, MENU_DOWNLOADS, 1, "Åpne Nedlastinger")
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            MENU_SAVED -> {
                startActivity(Intent(this, SavedActivity::class.java))
                return true
            }
            MENU_DOWNLOADS -> {
                TranscriptStore.openDownloads(this)
                return true
            }
        }
        return super.onOptionsItemSelected(item)
    }

    companion object {
        private const val MENU_SAVED = 1
        private const val MENU_DOWNLOADS = 2
    }
}
