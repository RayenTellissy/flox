package com.flox.tv.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import androidx.core.content.IntentCompat

/** Receives install session results: opens the system confirmation, or reports a failure. */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
                if (confirm == null) {
                    Updater.publish(Updater.State.Failed("No installer"))
                    return
                }
                runCatching { context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    .onFailure { Updater.publish(Updater.State.Failed(it.message ?: "No installer")) }
            }
            PackageInstaller.STATUS_SUCCESS -> Updater.publish(Updater.State.Idle)
            else -> {
                val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                Updater.publish(Updater.State.Failed(message ?: "Install cancelled"))
            }
        }
    }
}
