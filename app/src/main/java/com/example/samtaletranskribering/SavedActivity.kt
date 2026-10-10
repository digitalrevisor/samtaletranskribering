package com.example.samtaletranskribering

import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.io.File

/** Liste over lagrede transkripsjoner. Trykk = åpne, hold inne = slett. */
class SavedActivity : AppCompatActivity() {

    private data class Item(val file: File, val title: String, val subtitle: String)

    private lateinit var listView: ListView
    private lateinit var emptyView: TextView
    private val items = mutableListOf<Item>()
    private lateinit var adapter: ArrayAdapter<Item>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_saved)
        title = "Lagrede transkripsjoner"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        listView = findViewById(R.id.savedList)
        emptyView = findViewById(R.id.emptyText)
        listView.emptyView = emptyView

        adapter = object : ArrayAdapter<Item>(
            this, android.R.layout.simple_list_item_2, android.R.id.text1, items
        ) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = super.getView(position, convertView, parent)
                val item = items[position]
                v.findViewById<TextView>(android.R.id.text1).text = item.title
                v.findViewById<TextView>(android.R.id.text2).apply {
                    text = item.subtitle
                    maxLines = 2
                }
                return v
            }
        }
        listView.adapter = adapter

        listView.setOnItemClickListener { _, _, pos, _ ->
            startActivity(
                Intent(this, ViewerActivity::class.java)
                    .putExtra(ViewerActivity.EXTRA_PATH, items[pos].file.absolutePath)
            )
        }
        listView.setOnItemLongClickListener { _, _, pos, _ ->
            confirmDelete(items[pos])
            true
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        items.clear()
        for (f in TranscriptStore.list(this)) {
            val lines = try { f.readLines().filter { it.isNotBlank() } } catch (_: Exception) { emptyList() }
            val preview = lines.firstOrNull()?.replace(Regex("^\\[[^\\]]*\\]\\s*"), "").orEmpty()
            items.add(Item(f, TranscriptStore.displayTitle(f), "${lines.size} replikker · $preview"))
        }
        adapter.notifyDataSetChanged()
    }

    private fun confirmDelete(item: Item) {
        AlertDialog.Builder(this)
            .setTitle("Slette samtalen?")
            .setMessage("${item.title}\n\nKopien i Nedlastinger slettes også.")
            .setPositiveButton("Slett") { _, _ ->
                TranscriptStore.delete(this, item.file)
                refresh()
            }
            .setNegativeButton("Avbryt", null)
            .show()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, MENU_DOWNLOADS, 0, "Åpne Nedlastinger")
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM or MenuItem.SHOW_AS_ACTION_WITH_TEXT)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == MENU_DOWNLOADS) {
            TranscriptStore.openDownloads(this)
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    companion object {
        private const val MENU_DOWNLOADS = 1
    }
}
