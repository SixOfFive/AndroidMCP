package com.sixoffive.androidmcp.server

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Handles the Allow / Deny taps on an approval notification. */
class ApprovalReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ApprovalManager.ACTION) return
        val id = intent.getStringExtra("req") ?: return
        val allow = intent.getBooleanExtra("allow", false)
        ApprovalManager.resolve(id, allow)
    }
}
