package com.finrein.pals.utils

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.graphics.BitmapFactory
import android.os.Build
import androidx.core.app.NotificationCompat
import com.finrein.pals.R

object NotificationHelper {

    private const val DEFAULT_CHANNEL_ID = "default_app_channel"
    private const val DEFAULT_CHANNEL_NAME = "General Notifications"

    fun getBaseBuilder(
        context: Context,
        channelId: String = DEFAULT_CHANNEL_ID,
        channelName: String = DEFAULT_CHANNEL_NAME
    ): NotificationCompat.Builder {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Ensure notification channel is created (Android 8.0+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (notificationManager.getNotificationChannel(channelId) == null) {
                val channel = NotificationChannel(
                    channelId,
                    channelName,
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Notifications for PALZEE"
                }
                notificationManager.createNotificationChannel(channel)
            }
        }

        val isDarkMode = (context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES

        // Decode full-color app icon for notification drawer (natively resolves drawable / drawable-night)
        val coloredIconBitmap = try {
            BitmapFactory.decodeResource(context.resources, R.drawable.pal_colored_notification_logo)
                ?: BitmapFactory.decodeResource(context.resources, R.drawable.pal_circular_logo)
        } catch (e: Exception) {
            null
        }

        val accentColor = if (isDarkMode) 0xFF121318.toInt() else 0xFF5218ED.toInt()

        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_status_bar_silhouette) // Mandatory monochrome for status bar
            .setColor(accentColor)
            .setAutoCancel(true)

        if (coloredIconBitmap != null) {
            builder.setLargeIcon(coloredIconBitmap) // Full-color icon for notification drawer
        }

        return builder
    }
}
