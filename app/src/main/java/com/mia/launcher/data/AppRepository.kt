package com.mia.launcher.data

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.os.Process
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap

data class AppInfo(
    val key: String,
    val label: String,
    val packageName: String,
    val component: ComponentName,
    val icon: ImageBitmap,
)

object AppRepository {
    /** Última lista cargada; Mia la usa para «abre …». */
    @Volatile
    var cache: List<AppInfo> = emptyList()
        private set

    fun load(ctx: Context): List<AppInfo> {
        val la = ctx.getSystemService(LauncherApps::class.java)
        val list = la.getActivityList(null, Process.myUserHandle())
            .filter { it.componentName.packageName != ctx.packageName }
            .map { info ->
                val cn = info.componentName
                AppInfo(
                    key = cn.packageName + "/" + cn.className,
                    label = info.label?.toString() ?: cn.packageName,
                    packageName = cn.packageName,
                    component = cn,
                    icon = info.getBadgedIcon(0).toBitmap(144, 144).asImageBitmap(),
                )
            }
            .sortedBy { it.label.lowercase() }
        cache = list
        return list
    }

    fun launch(ctx: Context, app: AppInfo) {
        try {
            val la = ctx.getSystemService(LauncherApps::class.java)
            la.startMainActivity(app.component, Process.myUserHandle(), null, null)
        } catch (e: Exception) {
            ctx.packageManager.getLaunchIntentForPackage(app.packageName)?.let {
                it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ctx.startActivity(it)
            }
        }
    }

    /** Paquete de la app predeterminada para un intent (teléfono, cámara, etc.). */
    fun defaultPackageFor(ctx: Context, intent: Intent): String? = try {
        ctx.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
            ?.activityInfo?.packageName?.takeIf { it != "android" }
    } catch (e: Exception) {
        null
    }
}
