package com.sonic.assistant

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Claude Messages API client with a tool-use loop. */
class Brain(private val prefs: Prefs, private val tools: Tools) {
    private val http = OkHttpClient.Builder()
        .readTimeout(120, TimeUnit.SECONDS)
        .connectTimeout(20, TimeUnit.SECONDS)
        .build()
    private val history = JSONArray()
    @Volatile var guideMode = false

    fun newSession() {
        while (history.length() > 0) history.remove(0)
    }

    suspend fun respond(userText: String): String = withContext(Dispatchers.IO) {
        if (prefs.anthropicKey.isBlank()) return@withContext "I need an Anthropic API key. Open the Sonic app once to add it."
        stripOldImages()
        val content = JSONArray()
        if (guideMode) tools.screenshotBase64()?.let { content.put(imageBlock(it)) }
        content.put(JSONObject().put("type", "text").put("text", userText))
        history.put(JSONObject().put("role", "user").put("content", content))

        repeat(10) {
            val resp = try { call() } catch (e: Exception) {
                return@withContext "I couldn't reach my brain: ${e.message?.take(80)}"
            }
            if (resp.has("error")) {
                return@withContext "API error: ${resp.getJSONObject("error").optString("message").take(120)}"
            }
            val blocks = resp.getJSONArray("content")
            history.put(JSONObject().put("role", "assistant").put("content", blocks))

            val results = JSONArray()
            val spoken = StringBuilder()
            for (i in 0 until blocks.length()) {
                val b = blocks.getJSONObject(i)
                when (b.getString("type")) {
                    "text" -> spoken.append(b.getString("text")).append(' ')
                    "tool_use" -> {
                        val out = tools.run(b.getString("name"), b.getJSONObject("input"))
                        val rc = JSONArray()
                        out.image?.let { rc.put(imageBlock(it)) }
                        rc.put(JSONObject().put("type", "text").put("text", out.text))
                        results.put(
                            JSONObject().put("type", "tool_result")
                                .put("tool_use_id", b.getString("id"))
                                .put("content", rc)
                        )
                    }
                }
            }
            if (results.length() == 0) {
                // A long web search can pause the turn; just continue it.
                if (resp.optString("stop_reason") == "pause_turn") return@repeat
                return@withContext spoken.toString().trim()
            }
            history.put(JSONObject().put("role", "user").put("content", results))
        }
        "That took too many steps. Tell me where to pick up."
    }

    private fun call(): JSONObject {
        val body = JSONObject()
            .put("model", prefs.brainModel)
            .put("max_tokens", 1024)
            .put("system", Prompts.system(prefs.memoryDump(), guideMode))
            .put("tools", tools.definitions())
            .put("messages", history)
        val req = Request.Builder()
            .url("https://api.anthropic.com/v1/messages")
            .header("x-api-key", prefs.anthropicKey)
            .header("anthropic-version", "2023-06-01")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(req).execute().use { r -> return JSONObject(r.body?.string() ?: "{}") }
    }

    private fun imageBlock(b64: String) = JSONObject().put("type", "image").put(
        "source", JSONObject().put("type", "base64").put("media_type", "image/jpeg").put("data", b64)
    )

    /** Keep only the newest screenshot to control cost. */
    private fun stripOldImages() {
        for (m in 0 until history.length()) {
            val c = history.getJSONObject(m).opt("content") as? JSONArray ?: continue
            removeImages(c)
        }
    }

    private fun removeImages(arr: JSONArray) {
        var i = 0
        while (i < arr.length()) {
            val o = arr.optJSONObject(i)
            if (o != null && o.optString("type") == "image") { arr.remove(i); continue }
            if (o != null && o.optString("type") == "tool_result") {
                (o.opt("content") as? JSONArray)?.let { removeImages(it) }
            }
            i++
        }
    }
}
