package com.example.samtaletranskribering

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.view.Menu
import android.view.MenuItem
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.io.File

/** Viser én lagret transkripsjon, med farget talernavn. */
class ViewerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PATH = "path"
        private const val MENU_SHARE = 1
        private const val MENU_DELETE = 2
    }

    private lateinit var file: File

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val path = intent.getStringExtra(EXTRA_PATH)
        if (path == null || !File(path).exists()) {
            Toast.makeText(this, "Fant ikke filen", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        file = File(path)
        setContentView(R.layout.activity_viewer)
        title = TranscriptStore.displayTitle(file)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        findViewById<TextView>(R.id.viewerText).text = colorize(file.readText())
    }

    /** Linjer på formen «[12:01:22] Navn: tekst» → navn i farge, tid i grått. */
    private fun colorize(content: String): CharSequence {
        val colors = intArrayOf(Color.parseColor("#2F80ED"), Color.parseColor("#E8772E"))
        val speakerOrder = mutableListOf<String>()
        val pattern = Regex("^\\[([^\\]]*)\\]\\s*([^:]+):\\s?(.*)$")
        val sb = SpannableStringBuilder()
        for (line in content.lines()) {
            if (line.isBlank()) continue
            val m = pattern.find(line)
            if (m == null) {
                sb.append(line).append("\n\n")
                continue
            }
            val (time, name, text) = m.destructured
            if (name !in speakerOrder) speakerOrder.add(name)
            val idx = speakerOrder.indexOf(name)
            val color = if (idx < colors.size) colors[idx] else Color.GRAY
            val start = sb.length
            sb.append(name)
            sb.setSpan(StyleSpan(Typeface.BOLD), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(ForegroundColorSpan(color), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            val metaStart = sb.length
            sb.append("  ").append(time)
            sb.setSpan(RelativeSizeSpan(0.8f), metaStart, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(ForegroundColorSpan(Color.GRAY), metaStart, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.append("\n").append(text).append("\n\n")
        }
        return sb
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, MENU_SHARE, 0, "Del")
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS or MenuItem.SHOW_AS_ACTION_WITH_TEXT)
        menu.add(0, MENU_DELETE, 1, "Slett")
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM or MenuItem.SHOW_AS_ACTION_WITH_TEXT)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            MENU_SHARE -> {
                TranscriptStore.share(this, file)
                return true
            }
            MENU_DELETE -> {
                AlertDialog.Builder(this)
                    .setTitle("Slette samtalen?")
                    .setMessage("Kopien i Nedlastinger slettes også.")
                    .setPositiveButton("Slett") { _, _ ->
                        TranscriptStore.delete(this, file)
                        finish()
                    }
                    .setNegativeButton("Avbryt", null)
                    .show()
                return true
            }
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
