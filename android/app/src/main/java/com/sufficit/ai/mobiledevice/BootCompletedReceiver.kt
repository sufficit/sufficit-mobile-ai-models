package com.sufficit.ai.mobiledevice

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * "Fica rodando sempre a não ser que o usuário desative manualmente" only holds across a
 * device reboot if something restarts SyncForegroundService — Android does not resurrect
 * foreground services on its own after BOOT_COMPLETED. The local inference endpoint is a
 * device capability, so it starts independently of cloud pairing.
 */
class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        SyncForegroundService.start(context)
    }
}
