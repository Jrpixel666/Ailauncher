package dev.ailauncher.widgets

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.content.Context

/**
 * AiLauncher's AppWidgetHost. A launcher is the one app class allowed to bind arbitrary
 * widgets from other apps; this wraps the standard AppWidgetHost/AppWidgetManager APIs so
 * widget picking + hosting works the same way it does in AOSP Launcher3.
 */
object WidgetHostController {
    private const val HOST_ID = 1024
    private var host: AppWidgetHost? = null

    fun manager(context: Context): AppWidgetManager = AppWidgetManager.getInstance(context)

    fun host(context: Context): AppWidgetHost =
        host ?: AppWidgetHost(context.applicationContext, HOST_ID).also {
            host = it
            it.startListening()
        }

    fun stop() {
        host?.stopListening()
    }
}
