package com.vasanth.vaultnote.util

import androidx.appcompat.app.AppCompatDelegate

object ThemeHelper {
    fun apply(value: String?) {
        AppCompatDelegate.setDefaultNightMode(
            when (value) {
                "light" -> AppCompatDelegate.MODE_NIGHT_NO
                "dark" -> AppCompatDelegate.MODE_NIGHT_YES
                else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
        )
    }
}