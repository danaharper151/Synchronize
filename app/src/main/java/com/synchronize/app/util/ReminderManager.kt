package com.synchronize.app.util

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.synchronize.app.MainActivity
import java.util.Calendar

// ── Notification channel constants ────────────────────────────

const val CHANNEL_ID       = "synchronize_reminders"
const val CHANNEL_NAME     = "Ketone Reading Reminders"
const val CHANNEL_DESC     = "Daily reminders for blood ketone readings"

// ── Reading schedule ──────────────────────────────────────────

data class ReadingReminder(
    val id: Int,
    val context: String,
    val hour: Int,
    val minute: Int,
    val label: String,
    val message: String
)

val READING_SCHEDULE = listOf(
    ReadingReminder(
        id = 1, context = "FASTING",
        hour = 7, minute = 30,
        label = "Fasting Reading",
        message = "Time for your fasting BHB reading — before coffee or food"
    ),
    ReadingReminder(
        id = 2, context = "PRE_LUNCH",
        hour = 11, minute = 30,
        label = "Pre-Lunch Reading",
        message = "Pre-lunch BHB reading — log before eating"
    ),
    ReadingReminder(
        id = 3, context = "POST_LUNCH",
        hour = 13, minute = 30,
        label = "Post-Lunch Reading",
        message = "Post-lunch BHB reading — 90 minutes after eating"
    ),
    ReadingReminder(
        id = 4, context = "PRE_DINNER",
        hour = 17, minute = 30,
        label = "Pre-Dinner Reading",
        message = "Pre-dinner BHB reading — log before eating"
    ),
    ReadingReminder(
        id = 5, context = "PRE_SLEEP",
        hour = 21, minute = 30,
        label = "Pre-Sleep Reading",
        message = "Pre-sleep BHB reading — last reading of the day"
    )
)

// ── Reminder Manager ──────────────────────────────────────────

class ReminderManager(private val context: Context) {

    private val TAG = "ReminderManager"
    private val alarmManager =
        context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    // ── Create notification channel ───────────────────────────

    fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = CHANNEL_DESC
            enableVibration(true)
        }
        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(channel)
        Log.d(TAG, "Notification channel created")
    }

    // ── Schedule all daily reminders ──────────────────────────

    fun scheduleAllReminders() {
        createNotificationChannel()
        READING_SCHEDULE.forEach { reminder ->
            scheduleReminder(reminder)
        }
        Log.d(TAG, "All ${READING_SCHEDULE.size} reminders scheduled")
    }

    // ── Schedule a single reminder ────────────────────────────

    fun scheduleReminder(reminder: ReadingReminder) {
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            putExtra("reminder_id",      reminder.id)
            putExtra("reminder_label",   reminder.label)
            putExtra("reminder_message", reminder.message)
            putExtra("reminder_context", reminder.context)
        }

        val pendingIntent = PendingIntent.getBroadcast(
            context,
            reminder.id,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val calendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, reminder.hour)
            set(Calendar.MINUTE, reminder.minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            // If time has already passed today, schedule for tomorrow
            if (timeInMillis <= System.currentTimeMillis()) {
                add(Calendar.DAY_OF_YEAR, 1)
            }
        }

        try {
            alarmManager.setRepeating(
                AlarmManager.RTC_WAKEUP,
                calendar.timeInMillis,
                AlarmManager.INTERVAL_DAY,
                pendingIntent
            )
            Log.d(TAG, "Scheduled: ${reminder.label} at " +
                    "${reminder.hour}:${reminder.minute.toString().padStart(2, '0')}")
        } catch (e: SecurityException) {
            Log.e(TAG, "Permission denied for exact alarms — using inexact", e)
            alarmManager.setInexactRepeating(
                AlarmManager.RTC_WAKEUP,
                calendar.timeInMillis,
                AlarmManager.INTERVAL_DAY,
                pendingIntent
            )
        }
    }

    // ── Cancel all reminders ──────────────────────────────────

    fun cancelAllReminders() {
        READING_SCHEDULE.forEach { reminder ->
            val intent = Intent(context, ReminderReceiver::class.java)
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                reminder.id,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            alarmManager.cancel(pendingIntent)
        }
        Log.d(TAG, "All reminders cancelled")
    }

    // ── Check if reminders are enabled ────────────────────────

    fun areRemindersScheduled(): Boolean {
        val intent = Intent(context, ReminderReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            READING_SCHEDULE.first().id,
            intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
        return pendingIntent != null
    }
}

// ── Broadcast Receiver ────────────────────────────────────────

class ReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        // Re-schedule reminders after device reboot
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            ReminderManager(context).scheduleAllReminders()
            return
        }

        val id      = intent.getIntExtra("reminder_id", 0)
        val label   = intent.getStringExtra("reminder_label") ?: "Ketone Reading"
        val message = intent.getStringExtra("reminder_message") ?: "Time for your reading"

        showNotification(context, id, label, message)
    }

    private fun showNotification(
        context: Context,
        id: Int,
        title: String,
        message: String
    ) {
        val tapIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val tapPendingIntent = PendingIntent.getActivity(
            context, id, tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(tapPendingIntent)
            .setVibrate(longArrayOf(0, 250, 250, 250))
            .build()

        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(id, notification)

        android.util.Log.d("ReminderReceiver", "Notification shown: $title")
    }
}