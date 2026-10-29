package dev.ailauncher.launcher.components

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

data class AppEntry(val label: String, val packageName: String)

@Composable
fun AppDrawer() {
    val context = LocalContext.current
    val apps = remember {
        val pm = context.packageManager
        pm.getInstalledApplications(PackageManager.GET_META_DATA)
            .filter { pm.getLaunchIntentForPackage(it.packageName) != null }
            .map { AppEntry(it.loadLabel(pm).toString(), it.packageName) }
            .sortedBy { it.label.lowercase() }
    }

    LazyVerticalGrid(columns = GridCells.Fixed(4), modifier = Modifier.fillMaxSize().padding(12.dp)) {
        items(apps) { app ->
            Column(
                modifier = Modifier
                    .padding(8.dp)
                    .clickable {
                        context.packageManager.getLaunchIntentForPackage(app.packageName)?.let {
                            context.startActivity(it)
                        }
                    },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(app.label, maxLines = 1)
            }
        }
    }
}
