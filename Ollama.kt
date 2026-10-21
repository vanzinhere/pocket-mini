package com.pocklet.companion

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class OllamaException(message: String) : Exception(message)

object Ollama {
    private const val BASE = "https://ollama.com"

    private const val SYSTEM_PROMPT =
        "Kamu asisten di perangkat kecil dengan layar 160x128 piksel. " +
        "Jawab singkat, maksimal 4 kalimat, dalam bahasa yang sama dengan pertanyaan, " +
        "dan hanya memakai karakter ASCII."

    fun chat(
        apiKey: String,
        model: String,
        question: String,
        history: List<Pair<String, String>>
    ): String {
        if (apiKey.isBlank()) throw OllamaException("API key belum diisi")
        if (model.isBlank()) throw OllamaException("Model belum dipilih")

        val messages = JSONArray()
        messages.put(JSONObject().put("role", "system").put("content", SYSTEM_PROMPT))
        for ((q, a) in history) {
            messages.put(JSONObject().put("role", "user").put("content", q))
            messages.put(JSONObject().put("role", "assistant").put("content", a))
        }
        messages.put(JSONObject().put("role", "user").put("content", question))

        val body = JSONObject()
            .put("model", model)
            .put("messages", messages)
            .put("stream", false)
            .put("options", JSONObject().put("num_predict", 400))

        val json = call("$BASE/api/chat", apiKey, body.toString())
        return json.optJSONObject("message")?.optString("content").orEmpty()
    }

    fun listModels(apiKey: String): List<String> {
        if (apiKey.isBlank()) throw OllamaException("API key belum diisi")
        val json = call("$BASE/api/tags", apiKey, null)
        val arr = json.optJSONArray("models") ?: return emptyList()
        val names = mutableListOf<String>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val name = o.optString("model").ifBlank { o.optString("name") }
            if (name.isNotBlank()) names.add(name)
        }
        return names.sorted()
    }

    private fun call(url: String, apiKey: String, body: String?): JSONObject {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 60_000
            setRequestProperty("Authorization", "Bearer $apiKey")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "PockletCompanion/1.0 (Android)")
            if (body != null) {
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
        }
        try {
            if (body != null) {
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            if (code !in 200..299) {
                val detail = conn.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                    .orEmpty().take(80).replace('\n', ' ')
                throw OllamaException("Ollama HTTP $code${if (detail.isNotBlank()) ": $detail" else ""}")
            }
            val text = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            return JSONObject(text)
        } finally {
            conn.disconnect()
        }
    }
}
