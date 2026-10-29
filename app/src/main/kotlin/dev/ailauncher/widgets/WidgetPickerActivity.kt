package dev.ailauncher.widgets

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.os.Bundle

/**
 * Standard launcher widget-pick flow: allocate an id, ask the system to let the user choose
 * a widget for it, then (if a config Activity is declared) launch that before persisting.
 */
class WidgetPickerActivity : Activity() {

    private var pendingId: Int = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingId = WidgetHostController.host(this).allocateAppWidgetId()
        val pickIntent = Intent(AppWidgetManager.ACTION_APPWIDGET_PICK).apply {
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, pendingId)
        }
        startActivityForResult(pickIntent, REQ_PICK)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) {
            WidgetHostController.host(this).deleteAppWidgetId(pendingId)
            finish()
            return
        }
        val id = data?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, pendingId) ?: pendingId
        WidgetIdStore(this).add(id)
        finish()
    }

    companion object {
        private const val REQ_PICK = 1

        fun launch(context: Context) {
            context.startActivity(Intent(context, WidgetPickerActivity::class.java))
        }
    }
}
