package com.sonic.assistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Always-on foreground service: wake word -> voice conversation loop -> back to wake word. */
class SonicService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var prefs: Prefs
    private lateinit var voice: VoiceIO
    private lateinit var brain: Brain
    private lateinit var tools: Tools
    private lateinit var router: CommandRouter
    private lateinit var wake: WakeWord
    @Volatile private var busy = false

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        voice = VoiceIO(this).also { it.init() }
        tools = Tools(this, prefs)
        router = CommandRouter(tools)
        brain = Brain(prefs, tools).also { tools.brain = it }
        wake = WakeWord(this, prefs) { runSession(null) }
        startForegroundCompat()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_RUN_TASK -> runSession(intent.getStringExtra(EXTRA_INSTRUCTION))
            ACTION_WAKE_NOW -> runSession(null)
            else -> if (!busy) {
                val err = wake.start()
                if (err != null) scope.launch { voice.speak(err) }
            }
        }
        return START_STICKY
    }

    /** One full conversation. [initial] is a scheduled instruction instead of a spoken wake. */
    private fun runSession(initial: String?) {
        if (busy) return
        busy = true
        scope.launch {
            wake.stop()
            brain.newSession()
            try {
                if (initial != null) {
                    if (initial.startsWith("LOCAL_CAB|")) {
                        voice.speak(router.runScheduledCab(initial))
                    } else {
                        voice.speak(brain.respond("SCHEDULED TASK, the user set this earlier. Do it now: $initial"))
                    }
                } else {
                    voice.speak("Yes?")
                }
                var silent = 0
                while (silent < 2) {
                    val heard = voice.listen()
                    if (heard.isNullOrBlank()) { silent++; continue }
                    silent = 0
                    if (isDismiss(heard)) { voice.speak("Standing by."); break }
                    val reply = router.handle(heard)
                        ?: if (prefs.anthropicKey.isBlank()) CommandRouter.BASIC_FALLBACK else brain.respond(heard)
                    voice.speak(reply)
                }
            } catch (e: Exception) {
                voice.speak("Something broke: ${e.message?.take(60)}")
            } finally {
                brain.guideMode = false
                busy = false
                wake.start()
            }
        }
    }

    private fun isDismiss(s: String): Boolean {
        val t = s.lowercase()
        return listOf("that's all", "thats all", "goodbye", "stop listening", "never mind", "nevermind", "go to sleep", "thank you sonic", "thanks sonic")
            .any { t.contains(it) }
    }

    private fun startForegroundCompat() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH, "S.O.N.I.C", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val wakeNow = PendingIntent.getService(
            this, 1, Intent(this, SonicService::class.java).setAction(ACTION_WAKE_NOW), PendingIntent.FLAG_IMMUTABLE
        )
        val n = Notification.Builder(this, CH)
            .setContentTitle("S.O.N.I.C is listening")
            .setContentText("Say the wake word, or tap to talk")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(wakeNow)
            .addAction(Notification.Action.Builder(null, "Settings", open).build())
            .setOngoing(true)
            .build()
        startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
    }

    override fun onDestroy() {
        wake.stop(); voice.shutdown(); scope.cancel(); super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val CH = "sonic"
        const val ACTION_RUN_TASK = "com.sonic.RUN_TASK"
        const val ACTION_WAKE_NOW = "com.sonic.WAKE_NOW"
        const val EXTRA_INSTRUCTION = "instruction"
    }
}
