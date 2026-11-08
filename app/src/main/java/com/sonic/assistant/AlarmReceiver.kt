package com.sonic.assistant

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

/** Fired by AlarmManager at the scheduled time; hands the instruction to the running service. */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val i = Intent(context, SonicService::class.java)
            .setAction(SonicService.ACTION_RUN_TASK)
            .putExtra(SonicService.EXTRA_INSTRUCTION, intent.getStringExtra(SonicService.EXTRA_INSTRUCTION))
        ContextCompat.startForegroundService(context, i)
    }
}
