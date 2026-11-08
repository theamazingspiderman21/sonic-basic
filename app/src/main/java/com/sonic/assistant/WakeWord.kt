package com.sonic.assistant

import android.content.Context
import ai.picovoice.porcupine.Porcupine
import ai.picovoice.porcupine.PorcupineManager
import ai.picovoice.porcupine.PorcupineManagerCallback
import java.io.File

/**
 * On-device wake word. Drop a custom "Sonic" model (sonic.ppn, trained free at console.picovoice.ai)
 * into app/src/main/assets/. Without it, falls back to the built-in "Jarvis" keyword.
 */
class WakeWord(private val ctx: Context, private val prefs: Prefs, private val onWake: () -> Unit) {
    private var manager: PorcupineManager? = null

    fun start(): String? {
        if (prefs.porcupineKey.isBlank()) return "Picovoice key missing"
        stop()
        return try {
            val cb = PorcupineManagerCallback { onWake() }
            val b = PorcupineManager.Builder().setAccessKey(prefs.porcupineKey).setSensitivity(0.7f)
            val hasCustom = runCatching { ctx.assets.open("sonic.ppn").close() }.isSuccess
            if (hasCustom) {
                val f = File(ctx.filesDir, "sonic.ppn")
                ctx.assets.open("sonic.ppn").use { i -> f.outputStream().use { o -> i.copyTo(o) } }
                b.setKeywordPath(f.absolutePath)
            } else {
                b.setKeyword(Porcupine.BuiltInKeyword.JARVIS)
            }
            manager = b.build(ctx, cb)
            manager?.start()
            null
        } catch (e: Exception) {
            "Wake word failed: ${e.message}"
        }
    }

    /** Must release the mic before SpeechRecognizer can use it. */
    fun stop() {
        runCatching { manager?.stop(); manager?.delete() }
        manager = null
    }
}
