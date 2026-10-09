package com.vasanth.vaultnote.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

const val MAX_ATTACHMENTS = 10

@Serializable
data class Attachment(val id: String, val w: Int = 0, val h: Int = 0, val size: Int = 0)

object AttachmentJson {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun encode(list: List<Attachment>): String = json.encodeToString(list)

    fun decode(s: String): List<Attachment> =
        runCatching { json.decodeFromString<List<Attachment>>(s) }.getOrDefault(emptyList())
}