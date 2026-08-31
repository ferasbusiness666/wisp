package com.wisp.app

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class CallPhase {
    IDLE,
    /** We placed a call and are waiting for the other side to pick up. */
    OUTGOING,
    /** Their offer arrived; the device is ringing. */
    INCOMING,
    /** Answered on both sides, media is still being negotiated. */
    CONNECTING,
    /** Media flowing. */
    ACTIVE,
    /** ICE went transient-down; the grace timer is running. */
    RECONNECTING
}

data class CallUi(
    val phase: CallPhase = CallPhase.IDLE,
    val peerCode: String? = null,
    val peerName: String? = null,
    val micMuted: Boolean = false,
    val cameraOn: Boolean = true,
    val volumePercent: Int = 100,
    val connectedAtMs: Long = 0L,
    val endReason: String? = null
)

/**
 * The single place both activities read call and presence state from. The
 * service is the only writer.
 */
object CallStateHolder {

    private val _call = MutableStateFlow(CallUi())
    val call: StateFlow<CallUi> = _call

    private val _presence = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val presence: StateFlow<Map<String, Boolean>> = _presence

    private val _directory = MutableStateFlow<List<Contact>>(emptyList())
    val directory: StateFlow<List<Contact>> = _directory

    private val _signalingOnline = MutableStateFlow(false)
    val signalingOnline: StateFlow<Boolean> = _signalingOnline

    /** Live engine for the current call, so the UI can attach renderers. */
    @Volatile
    var engine: CallEngine? = null

    internal fun update(block: (CallUi) -> CallUi) {
        _call.value = block(_call.value)
    }

    internal fun setPresence(states: Map<String, Boolean>) {
        _presence.value = states
    }

    internal fun mergePresence(states: Map<String, Boolean>) {
        _presence.value = _presence.value + states
    }

    internal fun setDirectory(people: List<Contact>) {
        _directory.value = people
    }

    internal fun setSignalingOnline(online: Boolean) {
        _signalingOnline.value = online
    }
}
