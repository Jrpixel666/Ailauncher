package dev.ailauncher.widgets

import android.appwidget.AppWidgetProviderInfo
import android.widget.FrameLayout
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Home surface where bound AppWidgetHostViews live, plus a "+" affordance that hands off
 * to [WidgetPickerActivity] (standard ACTION_APPWIDGET_PICK flow) to bind a new one.
 */
@Composable
fun WidgetSurface() {
    val context = LocalContext.current
    val widgetIds = remember { mutableStateListOf<Int>().apply { addAll(loadSavedWidgetIds(context)) } }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize().padding(12.dp)) {
            items(widgetIds) { widgetId ->
                val info: AppWidgetProviderInfo? = WidgetHostController.manager(context).getAppWidgetInfo(widgetId)
                if (info != null) {
                    AndroidView(
                        modifier = Modifier.fillMaxWidth().height(180.dp).padding(bottom = 12.dp),
                        factory = {
                            WidgetHostController.host(context).createView(context, widgetId, info).apply {
                                setAppWidget(widgetId, info)
                            }
                        },
                    )
                }
            }
        }

        FloatingActionButton(
            onClick = { WidgetPickerActivity.launch(context) },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        ) {
            Icon(Icons.Filled.Add, contentDescription = "Add widget")
        }
    }
}

private fun loadSavedWidgetIds(context: android.content.Context): List<Int> =
    WidgetIdStore(context).savedIds()
