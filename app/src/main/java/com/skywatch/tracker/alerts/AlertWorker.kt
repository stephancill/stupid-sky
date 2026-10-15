package com.skywatch.tracker.alerts

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.skywatch.tracker.R
import com.skywatch.tracker.data.FlightData
import com.skywatch.tracker.data.Settings
import com.skywatch.tracker.ui.MainActivity
import com.skywatch.tracker.ui.Units
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

/**
 * Every 15 minutes (WorkManager's minimum), checks each watchlist entry and notifies when a
 * matching aircraft is airborne. Re-alerts for the same aircraft at most every 6 hours.
 */
class AlertWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val settings = Settings(applicationContext)
        val entries = settings.watchlist.first()
        if (entries.isEmpty()) return Result.success()
        val now = System.currentTimeMillis()

        for (entry in entries) {
            val hits = runCatching { FlightData.lookup(entry.kind, entry.value) }
                .getOrElse { return Result.retry() }
                .filter { it.hasPosition && !it.onGround }
            for (ac in hits) {
                val id = "${entry.encode()}|${ac.hex}"
                if (now - settings.lastAlerted(id) < REALERT_MS) continue
                settings.markAlerted(id, now)
                val alt = ac.altitude?.let(Units::altitude) ?: "airborne"
                notify(
                    id.hashCode(),
                    "${ac.label} is flying",
                    "${entry.display} · ${ac.typeCode ?: "?"} · $alt",
                    ac.hex,
                )
            }
        }
        return Result.success()
    }

    private fun notify(id: Int, title: String, text: String, hex: String) {
        val ctx = applicationContext
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED && android.os.Build.VERSION.SDK_INT >= 33
        ) return
        ensureChannel(ctx)
        val intent = Intent(ctx, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_HEX, hex)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pi = PendingIntent.getActivity(
            ctx, id, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_logo)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(ctx).notify(id, n)
    }

    companion object {
        private const val CHANNEL = "watchlist"
        private const val WORK = "watchlist-alerts"
        private val REALERT_MS = TimeUnit.HOURS.toMillis(6)

        fun ensureChannel(ctx: Context) {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Watchlist alerts", NotificationManager.IMPORTANCE_DEFAULT),
            )
        }

        fun schedule(ctx: Context) {
            val req = PeriodicWorkRequestBuilder<AlertWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, req)
        }
    }
}
