package com.vasanth.vaultnote.crypto

object PassphraseStrength {
    /** 0 = too short, 1 = weak, 2 = fair, 3 = good, 4 = strong. */
    fun score(p: String): Int {
        if (p.length < 8) return 0
        var s = 1
        if (p.length >= 12) s++
        if (p.length >= 16) s++
        val classes = listOf(
            p.any { it.isLowerCase() }, p.any { it.isUpperCase() },
            p.any { it.isDigit() }, p.any { !it.isLetterOrDigit() }
        ).count { it }
        if (classes >= 3) s++
        return minOf(s, 4)
    }

    fun label(score: Int) = when (score) {
        0 -> "Too short"; 1 -> "Weak"; 2 -> "Fair"; 3 -> "Good"; else -> "Strong"
    }
}