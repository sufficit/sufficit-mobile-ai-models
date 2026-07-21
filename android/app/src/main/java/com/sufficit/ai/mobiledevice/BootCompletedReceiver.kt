package com.sufficit.ai.mobiledevice

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * "Fica rodando sempre a não ser que o usuário desative manualmente" only holds across a
 * device reboot if something restarts SyncForegroundService — Android does not resurrect
 * foreground services on its own after BOOT_COMPLETED. Only restarts if the device was
 * actually paired before the reboot; a fresh/unpaired install has nothing to resume.
 */
class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        if (PairingStore(context).isPaired()) {
            SyncForegroundService.start(context)
        }
    }
}
