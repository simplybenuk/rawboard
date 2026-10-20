package uk.co.simplyben.rawplus

import android.content.Context

object EditingRules {
    fun load(context: Context): String = listOf("SKILL.md", "editing-notes.txt").joinToString("\n\n") { name ->
        context.assets.open("raw-plus/$name").bufferedReader(Charsets.UTF_8).use { it.readText() }
    }
}
