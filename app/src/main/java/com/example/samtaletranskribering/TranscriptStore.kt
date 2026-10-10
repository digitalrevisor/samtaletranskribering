package com.example.samtaletranskribering

import android.app.DownloadManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Lagring av transkripsjoner:
 *  - inne i appen (filesDir/transcripts) – vises under «Lagrede» i menyen
 *  - kopi i Nedlastinger/Samtaletranskribering (Android 10+) – synlig i Mine filer
 */
object TranscriptStore {
    private const val DIR = "transcripts"
    const val DOWNLOAD_SUBDIR = "Samtaletranskribering"

    fun dir(ctx: Context): File = File(ctx.filesDir, DIR).apply { mkdirs() }

    fun newSessionName(): String =
        "samtale_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".txt"

    fun format(entries: List<Entry>, names: Array<String>): String =
        entries.joinToString("\n") { "[${it.time}] ${names[it.speaker]}: ${it.text}" } + "\n"

    fun saveInternal(ctx: Context, name: String, content: String) {
        File(dir(ctx), name).writeText(content)
    }

    fun list(ctx: Context): List<File> =
        dir(ctx).listFiles { f -> f.isFile && f.name.endsWith(".txt") }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()

    /** «10.10.2026 kl. 17:45» ut fra filnavnet. */
    fun displayTitle(file: File): String {
        val stamp = file.name.removePrefix("samtale_").removeSuffix(".txt")
        val date = try {
            SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).parse(stamp)
        } catch (_: Exception) {
            null
        } ?: Date(file.lastModified())
        return SimpleDateFormat("dd.MM.yyyy 'kl.' HH:mm", Locale.getDefault()).format(date)
    }

    /**
     * Skriver (eller overskriver) en kopi i Nedlastinger/Samtaletranskribering.
     * Returnerer Uri slik at samme fil kan oppdateres mens samtalen pågår.
     */
    fun saveToDownloads(
        ctx: Context, name: String, content: String, existing: Uri?, retry: Boolean = true
    ): Uri? {
        if (Build.VERSION.SDK_INT < 29) return null
        val resolver = ctx.contentResolver
        return try {
            var uri = existing ?: loadUri(ctx, name)
            if (uri == null) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                    put(
                        MediaStore.MediaColumns.RELATIVE_PATH,
                        Environment.DIRECTORY_DOWNLOADS + "/" + DOWNLOAD_SUBDIR
                    )
                }
                uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: return null
                saveUri(ctx, name, uri)
            }
            resolver.openOutputStream(uri, "wt")?.use { it.write(content.toByteArray()) }
            uri
        } catch (_: Exception) {
            // Filen kan ha blitt slettet i Mine filer – lag en ny én gang.
            forgetUri(ctx, name)
            if (retry) saveToDownloads(ctx, name, content, null, retry = false) else null
        }
    }

    /** Sletter transkripsjonen i appen og kopien i Nedlastinger. */
    fun delete(ctx: Context, file: File) {
        val uri = loadUri(ctx, file.name)
        if (uri != null && Build.VERSION.SDK_INT >= 29) {
            try { ctx.contentResolver.delete(uri, null, null) } catch (_: Exception) {}
        }
        forgetUri(ctx, file.name)
        file.delete()
    }

    fun share(ctx: Context, file: File) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Samtale " + displayTitle(file))
            putExtra(Intent.EXTRA_TEXT, file.readText())
        }
        ctx.startActivity(Intent.createChooser(send, "Del transkripsjon"))
    }

    /** Åpner Nedlastinger (eller Samsung Mine filer). */
    fun openDownloads(ctx: Context) {
        val candidates = listOf(
            Intent(DownloadManager.ACTION_VIEW_DOWNLOADS),
            ctx.packageManager.getLaunchIntentForPackage("com.sec.android.app.myfiles")
        )
        for (i in candidates) {
            if (i == null) continue
            try {
                ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                Toast.makeText(
                    ctx, "Filene ligger i Nedlastinger/$DOWNLOAD_SUBDIR", Toast.LENGTH_LONG
                ).show()
                return
            } catch (_: Exception) {}
        }
        Toast.makeText(ctx, "Fant ingen filbehandler", Toast.LENGTH_LONG).show()
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("downloads", Context.MODE_PRIVATE)
    private fun loadUri(ctx: Context, name: String): Uri? =
        prefs(ctx).getString(name, null)?.let { Uri.parse(it) }
    private fun saveUri(ctx: Context, name: String, uri: Uri) =
        prefs(ctx).edit().putString(name, uri.toString()).apply()
    private fun forgetUri(ctx: Context, name: String) =
        prefs(ctx).edit().remove(name).apply()
}
