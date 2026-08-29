package com.wisp.app

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.webrtc.PeerConnection
import java.util.concurrent.TimeUnit

/**
 * Fetches short-lived ICE servers from the Worker right before a call starts.
 *
 * The Worker mints them from Cloudflare Realtime TURN with a long TTL (a day),
 * so a call never needs a mid-call credential refresh. If the endpoint is
 * unreachable we still return public STUN so a plain P2P call can work.
 */
object IceServers {

    private const val TAG = "WispIce"

    private val FALLBACK = listOf(
        "stun:stun.l.google.com:19302",
        "stun:stun1.l.google.com:19302"
    )

    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    suspend fun fetch(myCode: String): List<PeerConnection.IceServer> = withContext(Dispatchers.IO) {
        val fromWorker = runCatching { request(myCode) }
            .onFailure { Log.w(TAG, "turn-credentials failed: ${it.message}") }
            .getOrDefault(emptyList())

        val servers = fromWorker.toMutableList()
        // Google's public STUN is kept alongside Cloudflare's for redundancy:
        // if the Worker or Cloudflare's TURN API is having a bad day, host and
        // server-reflexive candidates still get gathered.
        FALLBACK.forEach { url ->
            servers += PeerConnection.IceServer.builder(url).createIceServer()
        }
        servers
    }

    private fun request(myCode: String): List<PeerConnection.IceServer> {
        val request = Request.Builder()
            .url("${Config.turnCredentialsUrl}?code=$myCode")
            .get()
            .build()

        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                Log.w(TAG, "turn-credentials HTTP ${response.code}")
                return emptyList()
            }
            val body = response.body?.string().orEmpty()
            return parse(body)
        }
    }

    /**
     * Accepts both shapes Cloudflare/our Worker may hand back: an `iceServers`
     * array, or a single `iceServers` object.
     */
    private fun parse(body: String): List<PeerConnection.IceServer> {
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return emptyList()
        val entries = when (val node = root.opt("iceServers")) {
            is JSONArray -> (0 until node.length()).mapNotNull { node.optJSONObject(it) }
            is JSONObject -> listOf(node)
            else -> emptyList()
        }

        return entries.mapNotNull { entry ->
            val urls = mutableListOf<String>()
            when (val u = entry.opt("urls")) {
                is JSONArray -> (0 until u.length()).forEach { i -> urls += u.optString(i) }
                is String -> urls += u
            }
            val clean = urls.filter { it.isNotBlank() }
            if (clean.isEmpty()) return@mapNotNull null

            PeerConnection.IceServer.builder(clean)
                .setUsername(entry.optString("username", ""))
                .setPassword(entry.optString("credential", ""))
                .createIceServer()
        }
    }
}
