package com.kalemnot.app.data

import android.app.PendingIntent
import android.content.Context
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.coroutines.resume

sealed class AuthResult {
    class Token(val token: String) : AuthResult()
    class NeedsConsent(val pending: PendingIntent) : AuthResult()
    object Failed : AuthResult()
}

object DriveAuth {
    private val SCOPE = Scope("https://www.googleapis.com/auth/drive.file")

    /** Erişim jetonu alır; kullanıcı onayı gerekiyorsa NeedsConsent döner. */
    suspend fun authorize(ctx: Context): AuthResult = suspendCancellableCoroutine { cont ->
        val req = AuthorizationRequest.builder().setRequestedScopes(listOf(SCOPE)).build()
        Identity.getAuthorizationClient(ctx).authorize(req)
            .addOnSuccessListener { r ->
                val pi = r.pendingIntent
                val tok = r.accessToken
                val res: AuthResult = if (r.hasResolution() && pi != null) {
                    AuthResult.NeedsConsent(pi)
                } else if (tok != null) {
                    AuthResult.Token(tok)
                } else {
                    AuthResult.Failed
                }
                if (cont.isActive) cont.resume(res)
            }
            .addOnFailureListener {
                if (cont.isActive) cont.resume(AuthResult.Failed)
            }
    }
}

/** Drive v3 REST istemcisi (yalnızca uygulamanın kendi oluşturduğu dosyalara erişir). */
class DriveClient(private val token: String) {
    class Remote(val id: String, val noteId: String, val noteUpdated: Long)

    private val api = "https://www.googleapis.com/drive/v3"
    private val upload = "https://www.googleapis.com/upload/drive/v3"

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    private fun conn(method: String, url: String): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 20000
        c.readTimeout = 120000
        c.setRequestProperty("Authorization", "Bearer $token")
        if (method == "PATCH") {
            c.requestMethod = "POST"
            c.setRequestProperty("X-HTTP-Method-Override", "PATCH")
        } else {
            c.requestMethod = method
        }
        return c
    }

    private fun check(c: HttpURLConnection) {
        val code = c.responseCode
        if (code !in 200..299) {
            val err = try {
                c.errorStream?.bufferedReader()?.use { it.readText() }
            } catch (e: Exception) {
                null
            }
            throw IOException("Drive HTTP $code ${err ?: ""}".take(300))
        }
    }

    private fun readJson(c: HttpURLConnection): JSONObject =
        JSONObject(c.inputStream.bufferedReader().use { it.readText() })

    private fun getJson(url: String): JSONObject {
        val c = conn("GET", url)
        check(c)
        return readJson(c)
    }

    fun findFolder(): String? {
        val q = "name='KalemNot' and mimeType='application/vnd.google-apps.folder' and trashed=false"
        val o = getJson("$api/files?q=${enc(q)}&fields=files(id)&pageSize=10")
        val a = o.optJSONArray("files")
        return if (a != null && a.length() > 0) a.getJSONObject(0).getString("id") else null
    }

    fun createFolder(): String {
        val c = conn("POST", "$api/files?fields=id")
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
        val body = JSONObject()
            .put("name", "KalemNot")
            .put("mimeType", "application/vnd.google-apps.folder")
            .toString().toByteArray()
        c.outputStream.use { it.write(body) }
        check(c)
        return readJson(c).getString("id")
    }

    fun list(folderId: String): List<Remote> {
        val out = ArrayList<Remote>()
        var pageToken: String? = null
        do {
            val q = "'$folderId' in parents and trashed=false"
            var url = "$api/files?q=${enc(q)}&fields=nextPageToken,files(id,appProperties)&pageSize=200"
            if (pageToken != null) url += "&pageToken=" + enc(pageToken)
            val o = getJson(url)
            val a = o.optJSONArray("files") ?: JSONArray()
            for (i in 0 until a.length()) {
                val f = a.getJSONObject(i)
                val props = f.optJSONObject("appProperties") ?: continue
                val noteId = props.optString("noteId", "")
                if (noteId.isEmpty()) continue
                val upd = props.optString("noteUpdated", "0").toLongOrNull() ?: 0L
                out.add(Remote(f.getString("id"), noteId, upd))
            }
            pageToken = if (o.has("nextPageToken")) o.getString("nextPageToken") else null
        } while (pageToken != null)
        return out
    }

    fun uploadNote(folderId: String, existingId: String?, name: String, noteId: String, updated: Long, file: File) {
        val boundary = "kalemnot" + System.nanoTime()
        val meta = JSONObject()
            .put("name", name)
            .put(
                "appProperties",
                JSONObject().put("noteId", noteId).put("noteUpdated", updated.toString()),
            )
        if (existingId == null) meta.put("parents", JSONArray().put(folderId))
        val head = ("--$boundary\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n" +
            meta.toString() + "\r\n--$boundary\r\nContent-Type: application/zip\r\n\r\n").toByteArray()
        val tail = "\r\n--$boundary--".toByteArray()

        val url = if (existingId == null) "$upload/files?uploadType=multipart&fields=id"
        else "$upload/files/$existingId?uploadType=multipart&fields=id"
        val c = conn(if (existingId == null) "POST" else "PATCH", url)
        c.doOutput = true
        c.setRequestProperty("Content-Type", "multipart/related; boundary=$boundary")
        c.setFixedLengthStreamingMode(head.size.toLong() + file.length() + tail.size.toLong())
        c.outputStream.use { os ->
            os.write(head)
            file.inputStream().use { it.copyTo(os) }
            os.write(tail)
        }
        check(c)
        c.inputStream.close()
    }

    fun download(id: String, dest: File) {
        val c = conn("GET", "$api/files/$id?alt=media")
        check(c)
        c.inputStream.use { ins -> dest.outputStream().use { ins.copyTo(it) } }
    }

    fun delete(id: String) {
        val c = conn("DELETE", "$api/files/$id")
        val code = c.responseCode
        if (code !in 200..299 && code != 404) throw IOException("Drive HTTP $code")
    }
}
