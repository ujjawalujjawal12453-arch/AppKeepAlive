package com.autotap.app

import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

// Google AI (Gemini) ke liye chhota madadgaar.
object Gemini {

    // Key se model ki list leta hai aur sabse accha "flash" model apne aap chunta hai.
    // Network use karta hai, isliye background thread me hi chalao. Fail ho to null.
    fun pickModel(key: String): String? {
        try {
            val url = URL("https://generativelanguage.googleapis.com/v1beta/models?pageSize=200")
            val c = url.openConnection() as HttpURLConnection
            c.requestMethod = "GET"
            c.connectTimeout = 15000
            c.readTimeout = 20000
            c.setRequestProperty("x-goog-api-key", key)
            if (c.responseCode !in 200..299) return null
            val txt = c.inputStream.bufferedReader().readText()
            val arr = JSONObject(txt).optJSONArray("models") ?: return null

            val names = ArrayList<String>()
            for (i in 0 until arr.length()) {
                val m = arr.getJSONObject(i)
                val name = m.optString("name").removePrefix("models/")
                val methods = m.optJSONArray("supportedGenerationMethods") ?: continue
                var canGenerate = false
                for (j in 0 until methods.length()) {
                    if (methods.getString(j) == "generateContent") canGenerate = true
                }
                if (!canGenerate) continue
                if (!name.contains("flash")) continue
                val skip = listOf("lite", "image", "tts", "live", "audio", "thinking", "exp", "robotics", "computer")
                if (skip.any { name.contains(it) }) continue
                names.add(name)
            }
            if (names.isEmpty()) return null

            // pehle pakke (preview/latest ke bina), phir sabse naya version, phir chhota naam
            return names.sortedWith(
                compareBy<String>(
                    { it.contains("preview") },
                    { it.contains("latest") },
                    { -versionOf(it) },
                    { it.length }
                )
            ).first()
        } catch (e: Exception) {
            return null
        }
    }

    private fun versionOf(name: String): Double {
        val m = Regex("(\\d+(?:\\.\\d+)?)").find(name)
        return m?.value?.toDoubleOrNull() ?: 0.0
    }
}
