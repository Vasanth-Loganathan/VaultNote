package com.vasanth.vaultnote.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

@Serializable
data class BoardColumn(val id: String = UUID.randomUUID().toString(), val name: String)

object BoardJson {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun encode(columns: List<BoardColumn>): String = json.encodeToString(columns)

    fun decode(s: String): List<BoardColumn> =
        runCatching { json.decodeFromString<List<BoardColumn>>(s) }.getOrDefault(emptyList())

    fun defaultColumns() = listOf("To do", "Doing", "Done").map { BoardColumn(name = it) }
}