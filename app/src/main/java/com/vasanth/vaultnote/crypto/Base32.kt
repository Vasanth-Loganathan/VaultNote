package com.vasanth.vaultnote.crypto

import java.io.ByteArrayOutputStream

object Base32 {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

    fun encode(data: ByteArray): String {
        val sb = StringBuilder()
        var buffer = 0
        var bits = 0
        for (b in data) {
            buffer = (buffer shl 8) or (b.toInt() and 0xFF)
            bits += 8
            while (bits >= 5) {
                sb.append(ALPHABET[(buffer shr (bits - 5)) and 31])
                bits -= 5
            }
            buffer = buffer and ((1 shl bits) - 1)
        }
        if (bits > 0) sb.append(ALPHABET[(buffer shl (5 - bits)) and 31])
        return sb.toString()
    }

    /** Accepts lower case, spaces and dashes. Returns null on invalid characters. */
    fun decode(text: String): ByteArray? {
        val clean = text.uppercase().filter { it != '-' && !it.isWhitespace() }.trimEnd('=')
        val out = ByteArrayOutputStream()
        var buffer = 0
        var bits = 0
        for (ch in clean) {
            val v = ALPHABET.indexOf(ch)
            if (v < 0) return null
            buffer = (buffer shl 5) or v
            bits += 5
            if (bits >= 8) {
                out.write((buffer shr (bits - 8)) and 0xFF)
                bits -= 8
                buffer = buffer and ((1 shl bits) - 1)
            }
        }
        return out.toByteArray()
    }
}

object RecoveryKey {
    /** 32 random bytes -> XXXX-XXXX-... (13 groups). */
    fun format(bytes: ByteArray): String = Base32.encode(bytes).chunked(4).joinToString("-")

    fun parse(text: String): ByteArray? {
        val b = Base32.decode(text) ?: return null
        return if (b.size == 32) b else null
    }
}