package com.example.samtaletranskribering

import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors

/**
 * Oversetter bokmål → nynorsk med Apertium (apertium.org, gratis og åpen kildekode).
 * Googles talegjenkjenning har ingen nynorsk-modell, så talen gjenkjennes som bokmål
 * og teksten oversettes etterpå. Bare teksten sendes – ikke lyden.
 */
object NynorskTranslator {
    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    /** Kaller callback på hovedtråden med nynorsk tekst, eller null ved feil. */
    fun translate(text: String, callback: (String?) -> Unit) {
        executor.execute {
            val result = try {
                val url = URL(
                    "https://apertium.org/apy/translate?langpair=" +
                        URLEncoder.encode("nob|nno", "UTF-8") +
                        "&markUnknown=no&q=" + URLEncoder.encode(text, "UTF-8")
                )
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 10_000
                conn.readTimeout = 15_000
                try {
                    if (conn.responseCode != 200) {
                        null
                    } else {
                        val body = conn.inputStream.bufferedReader().use { it.readText() }
                        JSONObject(body).optJSONObject("responseData")
                            ?.optString("translatedText")
                            ?.takeIf { it.isNotBlank() }
                    }
                } finally {
                    conn.disconnect()
                }
            } catch (_: Exception) {
                null
            }
            main.post { callback(result) }
        }
    }
}
