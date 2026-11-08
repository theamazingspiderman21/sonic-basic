package com.sonic.assistant

import android.Manifest
import android.app.Activity
import android.app.AlarmManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import java.io.File

/**
 * One-time setup screen only (keys + permissions). After this, S.O.N.I.C is voice only.
 */
class MainActivity : Activity() {
    private lateinit var prefs: Prefs

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 60, 40, 60)
        }
        fun label(t: String) = TextView(this).apply { text = t; textSize = 14f; setPadding(0, 28, 0, 4) }
        fun field(hint: String, value: String) = EditText(this).apply {
            this.hint = hint; setText(value); setSingleLine(true)
        }
        fun button(t: String, onClick: () -> Unit) = Button(this).apply { text = t; setOnClickListener { onClick() } }

        col.addView(TextView(this).apply { text = "S.O.N.I.C setup"; textSize = 26f })
        col.addView(label("Anthropic API key (console.anthropic.com)"))
        val ak = field("sk-ant-...", prefs.anthropicKey); col.addView(ak)
        col.addView(label("Picovoice AccessKey (console.picovoice.ai, free)"))
        val pk = field("wake word key", prefs.porcupineKey); col.addView(pk)
        col.addView(label("Google Maps API key (Directions API enabled) — for traffic routing"))
        val mk = field("optional", prefs.mapsKey); col.addView(mk)
        col.addView(label("Brain model"))
        val bm = field("claude-sonnet-5-5", prefs.brainModel); col.addView(bm)

        col.addView(button("1. Save keys") {
            prefs.anthropicKey = ak.text.toString(); prefs.porcupineKey = pk.text.toString()
            prefs.mapsKey = mk.text.toString()
            prefs.brainModel = bm.text.toString().ifBlank { "claude-sonnet-5-5" }
            toast("Saved")
        })
        col.addView(button("2. Grant mic, contacts, calls, SMS, location") {
            requestPermissions(
                arrayOf(
                    Manifest.permission.RECORD_AUDIO, Manifest.permission.READ_CONTACTS,
                    Manifest.permission.CALL_PHONE, Manifest.permission.SEND_SMS,
                    Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.POST_NOTIFICATIONS
                ), 1
            )
        })
        col.addView(button("3. Enable S.O.N.I.C hands (Accessibility)") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        col.addView(button("4. Allow display over other apps") {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        })
        col.addView(button("5. Allow exact alarms (scheduled tasks)") {
            val am2 = getSystemService(AlarmManager::class.java)
            if (!am2.canScheduleExactAlarms())
                startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName")))
            else toast("Already allowed")
        })
        col.addView(button("6. Start listening") {
            ContextCompat.startForegroundService(this, Intent(this, SonicService::class.java))
            toast("S.O.N.I.C is live. Say the wake word.")
        })
        col.addView(label("Tip: also set battery usage for this app to Unrestricted so Android doesn't stop the listener."))
        setContentView(ScrollView(this).apply {
            addView(col, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        })
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}
