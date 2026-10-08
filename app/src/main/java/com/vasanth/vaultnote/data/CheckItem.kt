package com.vasanth.vaultnote.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.UUID

@Serializable
data class CheckItem(
    val id: String = UUID.randomUUID().toString().take(8),
    val text: String = "",
    val done: Boolean = false
)

object ChecklistJson {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun decode(s: String): List<CheckItem> =
        try { json.decodeFromString<List<CheckItem>>(s) } catch (e: Exception) { emptyList() }

    fun encode(list: List<CheckItem>): String = json.encodeToString(list)
}