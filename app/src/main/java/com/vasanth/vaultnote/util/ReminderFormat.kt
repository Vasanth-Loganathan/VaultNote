package com.vasanth.vaultnote.util

import android.content.Context
import android.text.format.DateUtils

object ReminderFormat {
    fun text(ctx: Context, at: Long, repeat: String?): String {
        val whenText = DateUtils.formatDateTime(
            ctx, at,
            DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_MONTH
        )
        val suffix = when (repeat) {
            "daily" -> " · Daily"
            "weekly" -> " · Weekly"
            else -> ""
        }
        return whenText + suffix
    }
}