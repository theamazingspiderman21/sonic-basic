package com.sonic.assistant

import kotlinx.coroutines.delay
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * Basic brain: fixed voice patterns, no AI, no API key, no cost.
 * handle() returns the spoken reply, or null when it does not understand
 * (the caller then falls back to the Claude brain, if a key is set).
 */
class CommandRouter(private val tools: Tools) {

    /** Button text to press when the user says "confirm" (cab bookings). */
    private var pendingConfirm: String? = null

    suspend fun handle(raw: String): String? {
        val s0 = raw.replace(Regex("\\s+"), " ").trim()
        val t0 = s0.lowercase(Locale.ROOT).replace(".", "").replace(",", " ")
            .replace(Regex("\\s+"), " ").trim()

        // ---- spoken confirmation for a pending action (cab) ----
        val btn = pendingConfirm
        if (btn != null) {
            if (Regex("^(yes|yeah|yep|yup|confirm|book it|go ahead|do it|okay|ok|sure)\\b").containsMatchIn(t0)) {
                pendingConfirm = null
                val done = press(btn) || press("Book")
                return if (done) "Done." else "I couldn't find that button. Please tap it yourself."
            }
            pendingConfirm = null
            if (Regex("^(no|nope|cancel|stop)\\b").containsMatchIn(t0)) return "Okay, cancelled."
        }

        // ---- strip polite openers ----
        val opener = "^(?:hey |ok |okay )?(?:sonic |jarvis )?(?:please |can you |could you |would you )*"
        val t = t0.replace(Regex(opener), "").trim()
        val s = s0.replace(Regex(opener, RegexOption.IGNORE_CASE), "").trim()

        if (Regex("what can you do|what do you do|^help$").containsMatchIn(t)) return HELP

        // ---- cab ----
        val cabM = Regex("\\b(?:book|get|order|schedule|need|call)\\s+(?:me\\s+)?(?:an?\\s+)?(uber|ola|cab|taxi)\\b").find(t)
        if (cabM != null) return cab(cabM.groupValues[1], t)

        // ---- routes ----
        val fast = Regex(
            "(?:(?:fastest|shortest|quickest|least traffic|less traffic|low traffic)\\s+(?:route|path|way)|best\\s+(?:route|path))\\s+(?:to|for)\\s+(.+)$"
        ).find(t)
        if (fast != null) return route(fast.groupValues[1].trim(), true)
        val nav = Regex("\\b(?:navigate to|take me to|directions to|drive to|route to|how do i get to|how to get to)\\s+(.+)$").find(t)
        if (nav != null) return route(nav.groupValues[1].trim(), false)

        // ---- messages (keeps the original capitalisation of the text) ----
        val msg = Regex(
            "^(?:send|whatsapp|message|text|tell)\\s+(?:a\\s+)?(?:message\\s+)?(?:to\\s+)?(.+?)\\s+(?:on whatsapp\\s+|by sms\\s+|via sms\\s+|over sms\\s+)?(?:saying|that|says)\\s+(.+)$",
            RegexOption.IGNORE_CASE
        ).find(s)
        if (msg != null) {
            val contact = msg.groupValues[1].trim()
            val text = msg.groupValues[2].trim()
            val sms = t.contains("sms") || t.contains("text message")
            return run(if (sms) "send_sms" else "send_whatsapp", "contact" to contact, "text" to text)
        }

        // ---- share what is on screen ----
        val apps = "whatsapp|telegram|gmail|instagram|messages"
        val shA = Regex("^share (?:this|it)(?: \\w+)? (?:with|to) (.+?) (?:on|via|in|using) ($apps)$").find(t)
        if (shA != null) return share(shA.groupValues[2], shA.groupValues[1].trim())
        val shB = Regex("^share (?:this|it)(?: \\w+)? (?:on|via|in|using) ($apps)(?: (?:with|to) (.+))?$").find(t)
        if (shB != null) return share(shB.groupValues[1], shB.groupValues[2].trim().ifBlank { null })

        // ---- YouTube ----
        val ytPatterns = listOf(
            Regex("(?:search|find|play|look up|watch)\\s+(?:for\\s+)?(.+?)\\s+on youtube"),
            Regex("youtube\\s+(?:and\\s+)?(?:search|find|play|look up)(?:\\s+for)?\\s+(.+)"),
            Regex("(?:search|find|play)\\s+youtube\\s+for\\s+(.+)")
        )
        for (p in ytPatterns) {
            val m = p.find(t)
            if (m != null) return run("search_youtube", "query" to m.groupValues[1].trim())
        }

        // ---- alarm / timer ----
        val alarm = Regex("(?:alarm|wake me up)(?:\\s+(?:for|at|by))?\\s+(\\d{1,2})(?:[: ](\\d{2}))?\\s*(am|pm)?").find(t)
        if (alarm != null) {
            val h = to24(alarm.groupValues[1].toInt(), alarm.groupValues[3].ifBlank { null }).coerceIn(0, 23)
            val m = alarm.groupValues[2].ifBlank { "0" }.toInt().coerceIn(0, 59)
            return run("set_alarm", "hour" to h, "minute" to m, "label" to "Sonic")
        }
        val timer = Regex("timer(?:\\s+for)?\\s+(\\d+)\\s*(second|sec|minute|min|hour|hr)").find(t)
            ?: Regex("(\\d+)\\s*(second|sec|minute|min|hour|hr)s?\\s+timer").find(t)
        if (timer != null) {
            val n = timer.groupValues[1].toInt()
            val unit = timer.groupValues[2]
            val secs = if (unit.startsWith("h")) n * 3600 else if (unit.startsWith("m")) n * 60 else n
            return run("set_timer", "seconds" to secs, "label" to "Sonic")
        }

        // ---- call ----
        val call = Regex("^(?:call|phone|ring|dial)\\s+(.+)$").find(t)
        if (call != null) return run("call_contact", "contact" to call.groupValues[1].trim())

        // ---- open an app (last, it is the broadest) ----
        val open = Regex("^(?:open|launch|start)\\s+(?:the\\s+)?(.+?)(?:\\s+app)?$").find(t)
        if (open != null) {
            val r = run("open_app", "name" to open.groupValues[1].trim())
            return if (r.startsWith("No installed")) null else r
        }

        return null
    }

    /** Called by the service when a scheduled cab fires. */
    suspend fun runScheduledCab(instr: String): String {
        val p = instr.split("|")
        val provider = p.getOrElse(1) { "uber" }
        val drop = p.getOrElse(2) { "" }
        return "Time to go. " + openCab(provider, drop)
    }

    // ------------------------------------------------------------ helpers

    private suspend fun run(tool: String, vararg args: Pair<String, Any>): String {
        val o = JSONObject()
        for ((k, v) in args) o.put(k, v)
        return tools.run(tool, o).text
    }

    private suspend fun press(label: String): Boolean = run("tap", "text" to label).startsWith("Tapped")

    private fun to24(h: Int, ampm: String?): Int {
        var hh = h
        if (ampm == "pm" && hh < 12) hh += 12
        if (ampm == "am" && hh == 12) hh = 0
        return hh
    }

    private fun tomorrowDate(): String {
        val c = Calendar.getInstance()
        c.add(Calendar.DAY_OF_YEAR, 1)
        return SimpleDateFormat("yyyy-MM-dd", Locale.US).format(c.time)
    }

    private suspend fun cab(word: String, t: String): String {
        val provider = if (word == "ola") "ola" else "uber"
        var rest = t
        var time: String? = null
        val tm = Regex("\\b(?:at|by)\\s+(\\d{1,2})(?:[: ](\\d{2}))?\\s*(am|pm)?\\b").find(rest)
        if (tm != null) {
            val h = to24(tm.groupValues[1].toInt(), tm.groupValues[3].ifBlank { null }).coerceIn(0, 23)
            val m = tm.groupValues[2].ifBlank { "0" }.toInt().coerceIn(0, 59)
            time = "%02d:%02d".format(h, m)
            rest = rest.replace(tm.value, " ")
        }
        val tomorrow = rest.contains("tomorrow")
        rest = rest.replace(Regex("\\b(tomorrow|today|tonight)\\b"), " ").replace(Regex("\\s+"), " ").trim()
        val drop = Regex("\\bto\\s+(.+)$").find(rest)?.groupValues?.get(1)?.trim()
            ?.replace(Regex("\\s+(please|thanks|thank you)$"), "")
        if (drop.isNullOrBlank()) return "Where to? Say something like: book an Uber to the airport."

        if (time != null) {
            val args = JSONObject().put("time", time).put("instruction", "LOCAL_CAB|$provider|$drop")
            if (tomorrow) args.put("date", tomorrowDate())
            val r = tools.run("schedule_task", args).text
            return "$r I will open $provider to $drop then, and ask you to confirm."
        }
        return openCab(provider, drop)
    }

    private suspend fun openCab(provider: String, drop: String): String {
        val r = run("book_cab", "provider" to provider, "drop" to drop)
        if (r.contains("No installed")) return "I couldn't find $provider on this phone."
        pendingConfirm = "Confirm"
        return if (provider == "uber")
            "Uber is open with the drop set to $drop. Check the ride and price, then say confirm and I will press the button."
        else
            "Ola is open. I can only fill in the destination for Uber right now, so set the drop yourself, then say confirm."
    }

    private suspend fun route(dest0: String, fast: Boolean): String {
        val dest = dest0.replace(Regex("^(?:reach|get to|go to)\\s+"), "").trim()
        var prefix = ""
        if (fast) {
            val r = run("best_route", "destination" to dest)
            prefix = if (r.contains("Fastest is")) "$r "
            else "I can't compare live traffic without a Maps key, so I'm opening Google Maps, which picks a quick route. "
        }
        run("navigate", "destination" to dest)
        return prefix + "Starting navigation to $dest."
    }

    private suspend fun share(app: String, contact: String?): String {
        val name = app.replaceFirstChar { it.uppercase() }
        if (!press("Share")) return "I couldn't find a Share button on this screen."
        delay(1500)
        if (!press(app)) return "The share sheet is open but I can't see $name. Pick it yourself."
        delay(1500)
        if (contact == null) return "$name is open. Pick the contact on screen."
        if (!press(contact)) return "$name is open but I couldn't find $contact. Pick them yourself."
        delay(1000)
        return if (press("Send")) "Shared with $contact on $name." else "Contact selected, but I couldn't press Send."
    }

    companion object {
        const val HELP =
            "I can open apps, search YouTube, message people on WhatsApp or SMS, call, set alarms and timers, " +
                "find the fastest route, share what's on screen, and book an Uber for now or a set time. " +
                "Add a Claude key for anything more complex."
        const val BASIC_FALLBACK =
            "I only know basic commands so far, like open an app, search YouTube, message someone, set an alarm, " +
                "find a route or book a cab. Add a Claude key to unlock the full brain."
    }
}
