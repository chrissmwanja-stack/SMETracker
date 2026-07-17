package com.example.smetracker.notifications

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import com.example.smetracker.data.remote.sync.SyncEngine

/**
 * Manages the local notification shown to owners when there are sales or
 * inventory items waiting for cost/profit reconciliation.
 *
 * This is "local only" — it's triggered by the [SyncEngine] observing the
 * Room database while the app is alive. It does NOT wake the app from the
 * background; that would require FCM.
 */
object ReconciliationNotifier {
    private const val CHANNEL_ID = "reconciliation_pending"
    private const val NOTIFICATION_ID = 101

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Reconciliation Reminders"
            val descriptionText = "Notifications for items pending owner review"
            val importance = NotificationManager.IMPORTANCE_DEFAULT
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
            }
            val notificationManager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    @SuppressLint("MissingPermission")
    fun notifyPending(context: Context, count: Int) {
        if (count <= 0) {
            clear(context)
            return
        }

        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            // Using a system drawable as a fallback since no custom notification 
            // icon exists in res/drawable yet.
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Reconciliation Required")
            .setContentText("You have $count items waiting for review.")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setOnlyAlertOnce(true)
            .setAutoCancel(false)

        notificationManager.notify(NOTIFICATION_ID, builder.build())
    }

    fun clear(context: Context) {
        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(NOTIFICATION_ID)
    }
}
