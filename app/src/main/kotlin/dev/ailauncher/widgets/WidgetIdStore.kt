package dev.ailauncher.widgets

import android.content.Context

/** AppWidgetHost itself doesn't expose "list my bound ids", so AiLauncher tracks them. */
class WidgetIdStore(context: Context) {
    private val sp = context.getSharedPreferences("ailauncher_widgets", Context.MODE_PRIVATE)

    fun savedIds(): List<Int> =
        (sp.getStringSet(KEY, emptySet()) ?: emptySet()).mapNotNull { it.toIntOrNull() }

    fun add(id: Int) {
        val current = sp.getStringSet(KEY, emptySet())?.toMutableSet() ?: mutableSetOf()
        current.add(id.toString())
        sp.edit().putStringSet(KEY, current).apply()
    }

    fun remove(id: Int) {
        val current = sp.getStringSet(KEY, emptySet())?.toMutableSet() ?: mutableSetOf()
        current.remove(id.toString())
        sp.edit().putStringSet(KEY, current).apply()
    }

    companion object {
        private const val KEY = "widget_ids"
    }
}
