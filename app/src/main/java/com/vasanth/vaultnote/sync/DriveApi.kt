package com.vasanth.vaultnote.sync

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/** Minimal Drive v3 client for the hidden appDataFolder. Blocking: call from Dispatchers.IO. */
class DriveApi(private val client: OkHttpClient, private val token: String) {

    private val json = Json { ignoreUnknownKeys = true }

    private fun <T> exec(request: Request, parse: (Response) -> T): T {
        val authed = request.newBuilder().header("Authorization", "Bearer $token").build()
        client.newCall(authed).execute().use { resp ->
            if (!resp.isSuccessful) throw DriveException(resp.code, resp.body?.string().orEmpty())
            return parse(resp)
        }
    }

    private fun text(r: Response) = r.body!!.string()

    fun about(): About =
        exec(Request.Builder().url("$BASE/about?fields=user(emailAddress),storageQuota").get().build()) {
            json.decodeFromString<About>(text(it))
        }

    /** Note: getStartPageToken has no "spaces" parameter. spaces is applied in changes(). */
    fun startPageToken(): String =
        exec(Request.Builder().url("$BASE/changes/startPageToken").get().build()) {
            json.decodeFromString<StartToken>(text(it)).startPageToken
        }

    fun listFiles(name: String? = null): List<DriveFile> {
        val out = mutableListOf<DriveFile>()
        var page: String? = null
        do {
            val url = "$BASE/files".toHttpUrl().newBuilder()
                .addQueryParameter("spaces", "appDataFolder")
                .addQueryParameter("pageSize", "1000")
                .addQueryParameter("fields", "nextPageToken,files(id,name,size,modifiedTime,appProperties)")
                .apply {
                    name?.let { addQueryParameter("q", "name = '$it'") }
                    page?.let { addQueryParameter("pageToken", it) }
                }
                .build()
            val res = exec(Request.Builder().url(url).get().build()) {
                json.decodeFromString<FileList>(text(it))
            }
            out += res.files
            page = res.nextPageToken
        } while (page != null)
        return out
    }

    fun changes(pageToken: String): ChangeList {
        val url = "$BASE/changes".toHttpUrl().newBuilder()
            .addQueryParameter("pageToken", pageToken)
            .addQueryParameter("spaces", "appDataFolder")
            .addQueryParameter("includeRemoved", "true")
            .addQueryParameter("pageSize", "1000")
            .addQueryParameter(
                "fields",
                "nextPageToken,newStartPageToken,changes(fileId,removed,file(id,name,modifiedTime,appProperties))"
            )
            .build()
        return exec(Request.Builder().url(url).get().build()) { json.decodeFromString<ChangeList>(text(it)) }
    }

    fun download(fileId: String): ByteArray =
        exec(Request.Builder().url("$BASE/files/$fileId?alt=media").get().build()) { it.body!!.bytes() }

    /** u = the note's updatedAt, stored in appProperties so other devices can compare cheaply. */
    fun create(name: String, content: ByteArray, u: Long?): DriveFile {
        val meta = metaJson(name, withParent = true, u = u)
        val req = Request.Builder()
            .url("$UPLOAD/files?uploadType=multipart&fields=id,modifiedTime")
            .post(multipart(meta, content)).build()
        return exec(req) { json.decodeFromString<DriveFile>(text(it)) }
    }

    fun update(fileId: String, content: ByteArray, u: Long?): DriveFile {
        val meta = metaJson(null, withParent = false, u = u)
        val req = Request.Builder()
            .url("$UPLOAD/files/$fileId?uploadType=multipart&fields=id,modifiedTime")
            .patch(multipart(meta, content)).build()
        return exec(req) { json.decodeFromString<DriveFile>(text(it)) }
    }

    /** 404 counts as success (already gone). */
    fun delete(fileId: String) = deleteUrl("$BASE/files/$fileId")

    /** Keeps only the newest revision so old versions do not eat Drive space. Best effort. */
    fun pruneRevisions(fileId: String) {
        val revs = exec(
            Request.Builder().url("$BASE/files/$fileId/revisions?fields=revisions(id,modifiedTime)").get().build()
        ) { json.decodeFromString<RevisionList>(text(it)) }.revisions
        revs.sortedBy { it.modifiedTime }.dropLast(1).forEach {
            deleteUrl("$BASE/files/$fileId/revisions/${it.id}")
        }
    }

    private fun deleteUrl(url: String) {
        val req = Request.Builder().url(url).delete().build()
            .newBuilder().header("Authorization", "Bearer $token").build()
        client.newCall(req).execute().use {
            if (!it.isSuccessful && it.code != 404) throw DriveException(it.code, it.body?.string().orEmpty())
        }
    }

    private fun metaJson(name: String?, withParent: Boolean, u: Long?): String = buildJsonObject {
        name?.let { put("name", it) }
        if (withParent) putJsonArray("parents") { add("appDataFolder") }
        if (u != null) putJsonObject("appProperties") { put("u", u.toString()) }
    }.toString()

    private fun multipart(meta: String, content: ByteArray): MultipartBody =
        MultipartBody.Builder().setType("multipart/related".toMediaType())
            .addPart(meta.toRequestBody("application/json; charset=UTF-8".toMediaType()))
            .addPart(content.toRequestBody("application/octet-stream".toMediaType()))
            .build()

    companion object {
        private const val BASE = "https://www.googleapis.com/drive/v3"
        private const val UPLOAD = "https://www.googleapis.com/upload/drive/v3"
    }
}