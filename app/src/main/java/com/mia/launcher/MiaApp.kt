package com.mia.launcher

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.mia.launcher.data.Store
import com.mia.launcher.mia.MiaController

class MiaApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Store.init(this)
        MiaController.init(this)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(WAKE_CHANNEL, "Escucha de «Oye Mia»", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Aviso fijo mientras Mia escucha su nombre"
                setShowBadge(false)
            }
        )
    }

    companion object {
        const val WAKE_CHANNEL = "mia_wake"
    }
}
