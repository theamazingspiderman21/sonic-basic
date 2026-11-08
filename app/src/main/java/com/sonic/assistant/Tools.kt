package com.sonic.assistant

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.telephony.SmsManager
import kotlinx.coroutines.delay
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import java.util.Calendar

class ToolResult(val text: String, val image: String? = null)

/** Every capability the brain can call. Add a tool = add a definition + a branch in run(). */
class Tools(private val ctx: Context, private val prefs: Prefs) {
    lateinit var brain: Brain
    private val http = OkHttpClient()

    // ---------------------------------------------------------------- definitions
    private fun def(name: String, desc: String, vararg props: Pair<String, String>, required: List<String> = props.map { it.first }): JSONObject {
        val p = JSONObject()
        props.forEach { (k, v) ->
            val type = if (k in setOf("hour", "minute", "seconds")) "integer" else if (k == "on") "boolean" else "string"
            p.put(k, JSONObject().put("type", type).put("description", v))
        }
        return JSONObject().put("name", name).put("description", desc).put(
            "input_schema", JSONObject().put("type", "object").put("properties", p).put("required", JSONArray(required))
        )
    }

    fun definitions(): JSONArray = JSONArray().apply {
        put(def("open_app", "Open an installed app by its name.", "name" to "App name, e.g. WhatsApp, Uber, Chrome"))
        put(def("open_url", "Open a web link in the browser.", "url" to "Full https URL"))
        put(def("search_youtube", "Search YouTube for videos and show results.", "query" to "Search text"))
        put(def("send_whatsapp", "Open a WhatsApp chat with a saved contact with the message filled in and press Send.", "contact" to "Contact name as saved", "text" to "Message text"))
        put(def("send_sms", "Send an SMS to a saved contact.", "contact" to "Contact name", "text" to "Message text"))
        put(def("call_contact", "Call a saved contact.", "contact" to "Contact name"))
        put(def("set_alarm", "Set a clock alarm.", "hour" to "0-23", "minute" to "0-59", "label" to "Label", required = listOf("hour", "minute")))
        put(def("set_timer", "Start a countdown timer.", "seconds" to "Duration in seconds", "label" to "Label", required = listOf("seconds")))
        put(def("navigate", "Start Google Maps navigation to a place.", "destination" to "Place or address", "mode" to "d driving, w walking, b bicycling, l two-wheeler", required = listOf("destination")))
        put(def("best_route", "Compare live-traffic routes from the current location and report the fastest with its time and road. Use before navigate when the user asks for least traffic.", "destination" to "Place or address"))
        put(def("book_cab", "Prepare a cab ride in Uber or Ola. If 'time' is given it is scheduled and Sonic wakes itself then; otherwise it starts now. The final confirm always needs the user's spoken yes.", "provider" to "uber or ola", "pickup" to "Pickup address or 'current location'", "drop" to "Drop address", "time" to "HH:mm 24h, optional", "date" to "YYYY-MM-DD, optional (default next occurrence of time)", required = listOf("provider", "drop")))
        put(def("schedule_task", "Schedule an instruction for Sonic to carry out later by itself.", "time" to "HH:mm 24h", "instruction" to "What to do then, self-contained", "date" to "YYYY-MM-DD optional", required = listOf("time", "instruction")))
        put(def("read_screen", "Read the text and tappable elements currently on screen."))
        put(def("see_screen", "Take a screenshot and look at it. Use for charts, dashboards, images or when read_screen is thin."))
        put(def("tap", "Tap the on-screen element with this text or description.", "text" to "Visible label"))
        put(def("type_text", "Type into the focused or first input field.", "text" to "Text to enter"))
        put(def("scroll", "Scroll the current list or page.", "direction" to "down or up"))
        put(def("press", "Press a system button.", "button" to "back, home, recents or notifications"))
        put(def("set_guide_mode", "Turn screen-share guide mode on or off. On: a fresh screenshot is attached to every user message.", "on" to "true or false"))
        put(def("remember", "Save a lasting fact about the user.", "key" to "Short name e.g. home_address, favourite_cab_app", "value" to "The fact"))
        put(def("forget", "Delete a remembered fact.", "key" to "Name"))
    }

    // ---------------------------------------------------------------- dispatcher
    suspend fun run(name: String, a: JSONObject): ToolResult {
        return try {
        when (name) {
            "open_app" -> ToolResult(openApp(a.getString("name")))
            "open_url" -> { launch(Intent(Intent.ACTION_VIEW, Uri.parse(a.getString("url")))); ToolResult("Opened.") }
            "search_youtube" -> {
                val q = a.getString("query")
                val i = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=" + enc(q)))
                    .setPackage("com.google.android.youtube")
                if (i.resolveActivity(ctx.packageManager) == null) i.setPackage(null)
                launch(i); ToolResult("YouTube results for '$q' are open. Use read_screen to list them or tap to play one.")
            }
            "send_whatsapp" -> sendWhatsApp(a.getString("contact"), a.getString("text"))
            "send_sms" -> sendSms(a.getString("contact"), a.getString("text"))
            "call_contact" -> {
                val n = findNumber(a.getString("contact")) ?: return ToolResult("No contact matching ${a.getString("contact")}.")
                launch(Intent(Intent.ACTION_CALL, Uri.parse("tel:$n"))); ToolResult("Calling.")
            }
            "set_alarm" -> {
                launch(Intent(AlarmClock.ACTION_SET_ALARM).putExtra(AlarmClock.EXTRA_HOUR, a.getInt("hour"))
                    .putExtra(AlarmClock.EXTRA_MINUTES, a.getInt("minute")).putExtra(AlarmClock.EXTRA_MESSAGE, a.optString("label", "Sonic"))
                    .putExtra(AlarmClock.EXTRA_SKIP_UI, true))
                ToolResult("Alarm set for ${a.getInt("hour")}:${"%02d".format(a.getInt("minute"))}.")
            }
            "set_timer" -> {
                launch(Intent(AlarmClock.ACTION_SET_TIMER).putExtra(AlarmClock.EXTRA_LENGTH, a.getInt("seconds"))
                    .putExtra(AlarmClock.EXTRA_MESSAGE, a.optString("label", "Sonic")).putExtra(AlarmClock.EXTRA_SKIP_UI, true))
                ToolResult("Timer started.")
            }
            "navigate" -> {
                val i = Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=${enc(a.getString("destination"))}&mode=${a.optString("mode", "d")}"))
                    .setPackage("com.google.android.apps.maps")
                launch(i); ToolResult("Navigation started.")
            }
            "best_route" -> ToolResult(bestRoute(a.getString("destination")))
            "book_cab" -> bookCab(a)
            "schedule_task" -> ToolResult(schedule(a.getString("time"), a.optString("date").ifBlank { null }, a.getString("instruction")))
            "read_screen" -> ToolResult(a11y()?.readScreen() ?: NO_A11Y)
            "see_screen" -> screenshotBase64()?.let { ToolResult("Screenshot attached.", it) } ?: ToolResult("Could not capture the screen. $NO_A11Y")
            "tap" -> ToolResult(if (a11y()?.tapText(a.getString("text")) == true) "Tapped." else "Could not find or tap '${a.getString("text")}'.")
            "type_text" -> ToolResult(if (a11y()?.typeText(a.getString("text")) == true) "Typed." else "No input field found.")
            "scroll" -> ToolResult(if (a11y()?.scroll(a.getString("direction") != "up") == true) "Scrolled." else "Nothing to scroll.")
            "press" -> ToolResult(if (a11y()?.press(a.getString("button")) == true) "Done." else "Failed.")
            "set_guide_mode" -> { brain.guideMode = a.getBoolean("on"); ToolResult("Guide mode ${if (brain.guideMode) "on" else "off"}.") }
            "remember" -> { prefs.remember(a.getString("key"), a.getString("value")); ToolResult("Saved.") }
            "forget" -> { prefs.forget(a.getString("key")); ToolResult("Forgotten.") }
            else -> ToolResult("Unknown tool $name")
        }
        } catch (e: Exception) {
            ToolResult("Tool $name failed: ${e.message}")
        }
    }

    // ---------------------------------------------------------------- helpers
    private fun a11y() = SonicAccessibilityService.instance
    private val NO_A11Y = "Accessibility service is off. Ask the user to enable S.O.N.I.C hands in Accessibility settings."
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    private fun launch(i: Intent) { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }

    suspend fun screenshotBase64(): String? = a11y()?.screenshotBase64()

    private suspend fun openApp(name: String): String {
        val pm = ctx.packageManager
        val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(main, 0)
        val q = name.lowercase().trim()
        val hit = apps.firstOrNull { it.loadLabel(pm).toString().lowercase() == q }
            ?: apps.firstOrNull { it.loadLabel(pm).toString().lowercase().contains(q) }
            ?: return "No installed app called $name."
        launch(pm.getLaunchIntentForPackage(hit.activityInfo.packageName)!!)
        delay(1200)
        return "Opened ${hit.loadLabel(pm)}."
    }

    private fun findNumber(name: String): String? {
        ctx.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?", arrayOf("%$name%"), null
        )?.use { if (it.moveToFirst()) return it.getString(0).replace(Regex("[^+0-9]"), "") }
        return null
    }

    private suspend fun sendWhatsApp(contact: String, text: String): ToolResult {
        val n = findNumber(contact) ?: return ToolResult("No contact matching $contact.")
        val digits = n.removePrefix("+")
        launch(Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$digits?text=${enc(text)}")).setPackage("com.whatsapp"))
        delay(3000)
        val ok = a11y()?.tapText("Send") ?: false
        return ToolResult(if (ok) "Message sent to $contact." else "Chat is open with the message typed, but I could not press Send. Check with read_screen.")
    }

    private fun sendSms(contact: String, text: String): ToolResult {
        val n = findNumber(contact) ?: return ToolResult("No contact matching $contact.")
        val sms = ctx.getSystemService(SmsManager::class.java)
        sms.sendMultipartTextMessage(n, null, sms.divideMessage(text), null, null)
        return ToolResult("SMS sent to $contact.")
    }

    private fun lastLocation(): String? {
        val lm = ctx.getSystemService(LocationManager::class.java)
        return try {
            lm.getProviders(true).mapNotNull { lm.getLastKnownLocation(it) }.maxByOrNull { it.time }
                ?.let { "${it.latitude},${it.longitude}" }
        } catch (e: SecurityException) { null }
    }

    private fun bestRoute(dest: String): String {
        if (prefs.mapsKey.isBlank()) return "No Google Maps key set. Fall back to navigate."
        val origin = lastLocation() ?: return "I can't get the current location."
        val url = "https://maps.googleapis.com/maps/api/directions/json?origin=$origin&destination=${enc(dest)}" +
            "&departure_time=now&alternatives=true&traffic_model=best_guess&key=${prefs.mapsKey}"
        val body = http.newCall(Request.Builder().url(url).build()).execute().use { it.body?.string() ?: "{}" }
        val j = JSONObject(body)
        val routes = j.optJSONArray("routes") ?: return "Maps error: ${j.optString("status")}"
        if (routes.length() == 0) return "No route found."
        val rows = (0 until routes.length()).map {
            val r = routes.getJSONObject(it); val leg = r.getJSONArray("legs").getJSONObject(0)
            val traffic = leg.optJSONObject("duration_in_traffic") ?: leg.getJSONObject("duration")
            Triple(r.optString("summary"), traffic.getInt("value"), leg.getJSONObject("distance").getString("text"))
        }.sortedBy { it.second }
        return rows.joinToString("; ") { "via ${it.first}: ${it.second / 60} min, ${it.third}" } +
            ". Fastest is via ${rows[0].first}."
    }

    private suspend fun bookCab(a: JSONObject): ToolResult {
        val provider = a.getString("provider").lowercase()
        val drop = a.getString("drop"); val pickup = a.optString("pickup", "current location")
        val time = a.optString("time").ifBlank { null }
        if (time != null) {
            val instr = "Book an $provider cab from $pickup to $drop. Open the app with the book_cab tool (no time), pick the cheapest suitable ride type, " +
                "enter the destination, and STOP before the final confirm button. Tell the user the price and ETA out loud and only press confirm after they say yes."
            return ToolResult(schedule(time, a.optString("date").ifBlank { null }, instr) + " I will wake up then and ask you to confirm before booking.")
        }
        if (provider == "uber") {
            val link = "uber://?action=setPickup&pickup=my_location&dropoff[formatted_address]=${enc(drop)}"
            val i = Intent(Intent.ACTION_VIEW, Uri.parse(link)).setPackage("com.ubercab")
            if (i.resolveActivity(ctx.packageManager) != null) { launch(i); delay(2500); return ToolResult("Uber opened with the drop set. Use read_screen to see ride options and prices, then ask the user to confirm.") }
        }
        val msg = openApp(if (provider == "ola") "Ola" else "Uber")
        return ToolResult("$msg Now use read_screen, tap and type_text to enter the drop '$drop' and choose a ride; stop before confirming and ask the user.")
    }

    private fun schedule(hhmm: String, date: String?, instruction: String): String {
        val (h, m) = hhmm.split(":").map { it.trim().toInt() }
        val cal = Calendar.getInstance().apply {
            if (date != null) { val d = date.split("-").map { it.toInt() }; set(d[0], d[1] - 1, d[2]) }
            set(Calendar.HOUR_OF_DAY, h); set(Calendar.MINUTE, m); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            if (date == null && timeInMillis <= System.currentTimeMillis()) add(Calendar.DAY_OF_YEAR, 1)
        }
        val pi = PendingIntent.getBroadcast(
            ctx, (cal.timeInMillis % Int.MAX_VALUE).toInt(),
            Intent(ctx, AlarmReceiver::class.java).putExtra(SonicService.EXTRA_INSTRUCTION, instruction),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val am = ctx.getSystemService(AlarmManager::class.java)
        if (am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, cal.timeInMillis, pi)
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, cal.timeInMillis, pi)
        return "Scheduled for ${java.text.SimpleDateFormat("EEE d MMM HH:mm", java.util.Locale.getDefault()).format(cal.time)}."
    }

}
