package com.wisp.app

import android.content.Context
import android.content.SharedPreferences

/** Single SharedPreferences file backing everything Wisp persists. */
object Prefs {
    private const val NAME = "wisp"

    fun of(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)
}
