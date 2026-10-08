package com.vasanth.vaultnote.sync

import kotlinx.serialization.Serializable

@Serializable
data class DriveFile(
    val id: String = "",
    val name: String? = null,
    val size: String? = null,
    val modifiedTime: String? = null,
    val appProperties: Map<String, String>? = null
)

@Serializable data class FileList(val nextPageToken: String? = null, val files: List<DriveFile> = emptyList())
@Serializable data class Change(val fileId: String? = null, val removed: Boolean = false, val file: DriveFile? = null)
@Serializable data class ChangeList(
    val nextPageToken: String? = null,
    val newStartPageToken: String? = null,
    val changes: List<Change> = emptyList()
)
@Serializable data class StartToken(val startPageToken: String)
@Serializable data class DriveUser(val emailAddress: String? = null)
@Serializable data class StorageQuota(val limit: String? = null, val usage: String? = null)
@Serializable data class About(val user: DriveUser? = null, val storageQuota: StorageQuota? = null)
@Serializable data class Revision(val id: String, val modifiedTime: String? = null)
@Serializable data class RevisionList(val revisions: List<Revision> = emptyList())

/** An HTTP error from Drive (not a network failure). */
class DriveException(val code: Int, val body: String) : Exception("Drive error $code") {
    val isQuota get() = code == 403 && body.contains("storageQuotaExceeded")
}