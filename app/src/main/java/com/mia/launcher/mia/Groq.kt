package com.mia.launcher.mia

import com.mia.launcher.data.Store
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Conversación libre con el plan gratuito de Groq (compatible con OpenAI). Opcional. */
object Groq {
    private val history = ArrayDeque<Pair<String, String>>()

    private fun systemPrompt(): String {
        val user = Store.settings.userName.takeIf { it.isNotBlank() }?.let { "El usuario se llama $it. " } ?: ""
        val poses = Pose.entries.joinToString(", ") { it.name.lowercase() }
        return "Eres Mia, la asistente que vive en el launcher del teléfono del usuario. " +
            "Personalidad: alegre, traviesa, tierna y un poquito coqueta, pero siempre respetuosa. " +
            "Hablas español de México, de forma natural y breve (máximo 2 o 3 oraciones, sin listas ni emojis). " + user +
            "Empieza SIEMPRE tu respuesta con tu emoción entre corchetes, eligiendo una de: [$poses]. " +
            "Ejemplo: [feliz] ¡Claro que sí! ... " +
            "Si te piden una acción del teléfono que no puedes hacer desde la plática, sugiere decirla como comando, por ejemplo «pon una alarma a las 7»."
    }

    suspend fun chat(userText: String): Reply? = withContext(Dispatchers.IO) {
        val key = Store.settings.groqKey.trim()
        if (key.isEmpty()) return@withContext null
        try {
            val messages = JSONArray().put(JSONObject().put("role", "system").put("content", systemPrompt()))
            history.forEach { (r, c) -> messages.put(JSONObject().put("role", r).put("content", c)) }
            messages.put(JSONObject().put("role", "user").put("content", userText))
            val body = JSONObject()
                .put("model", Store.settings.groqModel.ifBlank { "llama-3.3-70b-versatile" })
                .put("messages", messages)
                .put("temperature", 0.8)
                .put("max_tokens", 220)

            val c = URL("https://api.groq.com/openai/v1/chat/completions").openConnection() as HttpURLConnection
            c.requestMethod = "POST"
            c.connectTimeout = 10000; c.readTimeout = 20000
            c.doOutput = true
            c.setRequestProperty("Authorization", "Bearer $key")
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(body.toString().toByteArray()) }
            if (c.responseCode !in 200..299) {
                c.disconnect()
                return@withContext if (c.responseCode == 429)
                    Reply("Platiqué demasiado por hoy y llegué al límite gratis. Los comandos siguen funcionando.", Pose.AGOTADA)
                else null
            }
            val txt = c.inputStream.bufferedReader().readText()
            c.disconnect()
            val content = JSONObject(txt).getJSONArray("choices").getJSONObject(0)
                .getJSONObject("message").getString("content").trim()
            val m = Regex("^\\s*\\[([^\\]]+)]\\s*").find(content)
            val pose = Pose.fromTag(m?.groupValues?.get(1)) ?: Pose.NORMAL
            val clean = (if (m != null) content.substring(m.range.last + 1) else content).trim()
            history.addLast("user" to userText)
            history.addLast("assistant" to content)
            while (history.size > 8) history.removeFirst()
            Reply(clean.ifBlank { "…" }, pose)
        } catch (e: Exception) {
            null
        }
    }
}
