package com.wisp.app

/**
 * The only thing the app needs to know about the backend.
 *
 * No TURN credentials live here — they are minted per call by the Worker at
 * [turnCredentialsUrl] and never ship inside the APK.
 */
object Config {

    /** Host of the Cloudflare Worker (a workers.dev subdomain or a custom domain). */
    const val SIGNALING_HOST = "wisp-signaling.example.workers.dev"

    val signalingUrl: String
        get() = "wss://$SIGNALING_HOST/ws"

    val turnCredentialsUrl: String
        get() = "https://$SIGNALING_HOST/turn-credentials"
}
