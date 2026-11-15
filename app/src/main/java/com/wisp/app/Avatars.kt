package com.wisp.app

import android.graphics.drawable.GradientDrawable
import android.widget.TextView

/** Letter avatars: a coloured circle with the first letter of the name. */
object Avatars {

    private val palette = listOf(
        0xFF833AB4, 0xFFC13584, 0xFFE1306C, 0xFFFD1D1D,
        0xFFF56040, 0xFFF77737, 0xFF5851DB, 0xFF405DE6
    ).map { it.toInt() }

    fun initial(name: String?): String =
        name?.trim()?.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "?"

    /** Same person, same colour, on every screen — keyed on their code. */
    fun color(key: String?): Int = palette[((key ?: "").hashCode() and 0x7fffffff) % palette.size]

    fun bind(view: TextView, name: String?, key: String?) {
        view.text = initial(name)
        view.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color(key))
        }
    }
}
