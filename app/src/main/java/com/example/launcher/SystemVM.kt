package com.example.launcher

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel

class SystemVM(private val context: Application) : AndroidViewModel(context) {
    fun expandNotificationShade() {
        runCatching {
            val service = context.getSystemService("statusbar")
            val clazz = Class.forName("android.app.StatusBarManager")
            val method = clazz.getMethod("expandNotificationsPanel")
            method.invoke(service)
        }.onFailure {
            Log.w("SystemVM", "Could not expand notification shade", it)
        }
    }
}
