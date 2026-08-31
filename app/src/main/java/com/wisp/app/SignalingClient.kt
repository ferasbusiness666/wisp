package com.wisp.app

import android.os.Handler
import android.os.Looper
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.math.min
import kotlin.math.pow

/**
 * Persistent WebSocket to the Cloudflare Worker.
 *
 * Every callback is delivered on the main thread so the service and the call
 * engine never have to think about which thread they are on. Reconnects happen
 * automatically with exponential backoff while [wanted] is true.
 */
class SignalingClient(
    private val myCode: String,
    private val listener: Listener
) {

    interface Listener {
        fun onSignalingOpen()
        fun onSignalingClosed()
        fun onPresence(states: Map<String, Boolean>)
        fun onDirectory(people: List<Contact>)
        fun onAddMe(code: String, name: String)
        fun onOffer(from: String, sdp: String)
        fun onAnswer(from: String, sdp: String)
        fun onRemoteIce(from: String, mid: String?, index: Int, candidate: String)
        fun onBye(from: String, reason: String?)
        fun onIceRestartRequest(from: String)
        fun onPeerUnavailable(code: String, reason: String?)
    }

    companion object {
        private const val TAG = "WispSignaling"
        private const val MAX_BACKOFF_MS = 30_000L
        private const val NORMAL_CLOSE = 1000
    }

    private val main = Handler(Looper.getMainLooper())

    private val http = OkHttpClient.Builder()
        .pingInterval(25, TimeUnit.SECONDS)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private var socket: WebSocket? = null
    private var attempt = 0
    private var reconnectScheduled = false

    /** True while we want a live socket; false after [disconnect]. */
    @Volatile
    var wanted = false
        private set

    @Volatile
    var isOpen = false
        private set

    private var watching: List<String> = emptyList()
    private var myName: String = ""

    fun connect() {
        wanted = true
        if (isOpen || socket != null) return
        openSocket()
    }

    fun disconnect() {
        wanted = false
        main.removeCallbacksAndMessages(null)
        reconnectScheduled = false
        isOpen = false
        socket?.close(NORMAL_CLOSE, "bye")
        socket = null
    }

    /**
     * Called when we know the network changed or before placing a call: if the
     * socket is not up, stop waiting out the backoff and retry right now.
     */
    fun reconnectNow() {
        if (!wanted) return
        if (isOpen) return
        main.removeCallbacksAndMessages(null)
        reconnectScheduled = false
        attempt = 0
        socket?.cancel()
        socket = null
        openSocket()
    }

    fun watch(codes: List<String>) {
        watching = codes
        if (isOpen) sendWatch()
    }

    /** Publishes the name other devices should show for us. */
    fun identify(name: String) {
        myName = name
        if (isOpen) sendIdentify()
    }

    private fun sendIdentify() {
        send(JSONObject().put("type", "identify").put("name", myName))
        send(JSONObject().put("type", "directory"))
    }

    private fun openSocket() {
        val url = "${Config.signalingUrl}?code=$myCode"
        Log.i(TAG, "connecting to $url")
        val request = Request.Builder().url(url).build()
        socket = http.newWebSocket(request, SocketListener())
    }

    private fun scheduleReconnect() {
        if (!wanted || reconnectScheduled) return
        reconnectScheduled = true
        val delay = min(MAX_BACKOFF_MS, (1000L * 2.0.pow(attempt.toDouble())).toLong())
        attempt = min(attempt + 1, 5)
        Log.i(TAG, "reconnecting in ${delay}ms")
        main.postDelayed({
            reconnectScheduled = false
            if (wanted && !isOpen) openSocket()
        }, delay)
    }

    private fun sendWatch() {
        val codes = JSONArray().also { array -> watching.forEach { array.put(it) } }
        send(JSONObject().put("type", "watch").put("codes", codes))
    }

    // ---- outbound ----------------------------------------------------------

    fun sendOffer(to: String, sdp: String) =
        send(JSONObject().put("type", "offer").put("to", to).put("sdp", sdp))

    fun sendAnswer(to: String, sdp: String) =
        send(JSONObject().put("type", "answer").put("to", to).put("sdp", sdp))

    fun sendIce(to: String, mid: String?, index: Int, candidate: String) =
        send(
            JSONObject()
                .put("type", "ice")
                .put("to", to)
                .put("mid", mid)
                .put("index", index)
                .put("candidate", candidate)
        )

    fun sendBye(to: String, reason: String) =
        send(JSONObject().put("type", "bye").put("to", to).put("reason", reason))

    /** Asks the other side to save us too, so adding only has to happen once. */
    fun sendAddMe(to: String) =
        send(JSONObject().put("type", "add-me").put("to", to))

    /** Re-reads who is online; the list is otherwise only pushed on changes. */
    fun requestDirectory() {
        send(JSONObject().put("type", "directory"))
    }

    fun requestIceRestart(to: String) =
        send(JSONObject().put("type", "ice-restart").put("to", to))

    private fun send(message: JSONObject): Boolean {
        val ws = socket ?: return false
        if (!isOpen) return false
        return ws.send(message.toString())
    }

    // ---- inbound -----------------------------------------------------------

    private inner class SocketListener : WebSocketListener() {

        override fun onOpen(webSocket: WebSocket, response: Response) {
            main.post {
                if (webSocket !== socket) return@post
                Log.i(TAG, "socket open")
                isOpen = true
                attempt = 0
                sendWatch()
                sendIdentify()
                listener.onSignalingOpen()
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            main.post {
                if (webSocket !== socket) return@post
                handle(text)
            }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = fail(webSocket)

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.w(TAG, "socket failure: ${t.message}")
            fail(webSocket)
        }

        private fun fail(webSocket: WebSocket) {
            main.post {
                if (webSocket !== socket) return@post
                val wasOpen = isOpen
                isOpen = false
                socket = null
                if (wasOpen) listener.onSignalingClosed()
                scheduleReconnect()
            }
        }
    }

    private fun handle(text: String) {
        val json = runCatching { JSONObject(text) }.getOrNull() ?: return
        when (json.optString("type")) {
            "presence" -> {
                val states = HashMap<String, Boolean>()
                json.optJSONObject("states")?.let { obj ->
                    obj.keys().forEach { key -> states[key] = obj.optBoolean(key) }
                }
                val single = json.optString("code")
                if (single.isNotEmpty()) states[single] = json.optBoolean("online")
                if (states.isNotEmpty()) listener.onPresence(states)
            }
            "directory" -> {
                val array = json.optJSONArray("people")
                val people = buildList {
                    for (i in 0 until (array?.length() ?: 0)) {
                        val entry = array?.optJSONObject(i) ?: continue
                        val code = entry.optString("code").takeIf { it.isNotEmpty() } ?: continue
                        val name = entry.optString("name").takeIf { it.isNotBlank() } ?: code
                        add(Contact(code, name))
                    }
                }
                listener.onDirectory(people)
            }
            "offer" -> listener.onOffer(json.optString("from"), json.optString("sdp"))
            "answer" -> listener.onAnswer(json.optString("from"), json.optString("sdp"))
            "ice" -> listener.onRemoteIce(
                json.optString("from"),
                json.optString("mid").takeIf { it.isNotEmpty() },
                json.optInt("index", 0),
                json.optString("candidate")
            )
            "bye" -> listener.onBye(
                json.optString("from"),
                json.optString("reason").takeIf { it.isNotEmpty() }
            )
            "ice-restart" -> listener.onIceRestartRequest(json.optString("from"))
            "add-me" -> {
                val code = json.optString("from")
                val name = json.optString("name").takeIf { it.isNotBlank() } ?: code
                if (code.isNotEmpty()) listener.onAddMe(code, name)
            }
            "unavailable" -> listener.onPeerUnavailable(
                json.optString("to"),
                json.optString("reason").takeIf { it.isNotEmpty() }
            )
            "hello" -> Log.i(TAG, "registered as ${json.optString("code")}")
            "error" -> Log.w(TAG, "server error: ${json.optString("message")}")
        }
    }
}
