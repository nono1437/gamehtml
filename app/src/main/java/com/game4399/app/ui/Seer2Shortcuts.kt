package com.game4399.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import com.game4399.app.R

private data class Seer2Shortcut(
    @StringRes val title: Int,
    @StringRes val instructions: Int,
    @StringRes val searchTerm: Int? = null
)

internal fun showSeer2Shortcuts(context: Context, openCatalog: (() -> Unit)? = null) {
    val shortcuts = listOf(
        Seer2Shortcut(R.string.seer2_unlock_title, R.string.seer2_unlock_path),
        Seer2Shortcut(R.string.seer2_team_title, R.string.seer2_team_path, R.string.seer2_team_search),
        Seer2Shortcut(R.string.seer2_pet_title, R.string.seer2_pet_path, R.string.seer2_pet_search)
    )
    val menu = AlertDialog.Builder(context)
        .setTitle(R.string.seer2_shortcuts)
        .setItems(shortcuts.map { context.getString(it.title) }.toTypedArray()) { _, index ->
            val shortcut = shortcuts[index]
            val detail = AlertDialog.Builder(context)
                .setTitle(shortcut.title)
                .setMessage(shortcut.instructions)
                .setNegativeButton(R.string.back) { _, _ -> showSeer2Shortcuts(context, openCatalog) }
                .setPositiveButton(R.string.close, null)
            shortcut.searchTerm?.let { term ->
                detail.setNeutralButton(R.string.seer2_copy_search_term) { _, _ ->
                    val text = context.getString(term)
                    context.getSystemService(ClipboardManager::class.java)
                        .setPrimaryClip(ClipData.newPlainText(text, text))
                    Toast.makeText(context, context.getString(R.string.seer2_search_term_copied, text), Toast.LENGTH_SHORT).show()
                }
            }
            detail.show()
        }
        .setNegativeButton(R.string.close, null)
    if (openCatalog != null) {
        menu.setPositiveButton(R.string.seer2_catalog_open) { _, _ -> openCatalog() }
    }
    menu.show()
}
