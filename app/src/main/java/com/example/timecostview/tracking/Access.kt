package com.example.timecostview.tracking

import android.app.AppOpsManager
import android.content.Context
import android.os.Process

object Access {
    @Suppress("DEPRECATION")
    fun usage(context: Context): Boolean = context.getSystemService(AppOpsManager::class.java)
        .checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName) == AppOpsManager.MODE_ALLOWED
}
