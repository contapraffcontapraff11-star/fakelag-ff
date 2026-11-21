package com.fakelag.ff

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == "android.intent.action.QUICKBOOT_POWERON") {
            val svc = Intent(ctx, FakeLagVpnService::class.java)
            svc.action = FakeLagVpnService.CMD_ENABLE
            ctx.startForegroundService(svc)
        }
    }
}
