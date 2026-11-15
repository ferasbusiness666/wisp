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
    /** Front camera previews are mirrored; the back camera's must not be. */
    val frontCamera: Boolean = true,
    val volumePercent: Int = 100,
    val connectedAtMs: Long = 0L,
    val endReason: String? = null,
    /**
     * Bumped whenever a local or remote video track appears. Tracks live on the
     * engine, not in this state, so without it an otherwise-unchanged state
     * would never reach the screen and the new track would never be attached.
     */
    val mediaVersion: Int = 0
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

    /** Bumped when the service changes the contact list, so the UI reloads. */
    private val _contactsRevision = MutableStateFlow(0)
    val contactsRevision: StateFlow<Int> = _contactsRevision

    private val _signalingOnline = MutableStateFlow(false)
    val signalingOnline: StateFlow<Boolean> = _signalingOnline

    /** Live engine for the current call, so the UI can attach renderers. */
    @Volatile
    var engine: CallEngine? = null

    /**
     * Reads the reason the last call ended and clears it, so a failure is
     * reported to the user exactly once.
     */
    fun consumeEndReason(): String? {
        val reason = _call.value.endReason ?: return null
        _call.value = _call.value.copy(endReason = null)
        return reason
    }

    internal fun update(block: (CallUi) -> CallUi) {
        _call.value = block(_call.value)
    }

    internal fun setPresence(states: Map<String, Boolean>) {
        _presence.value = states
    }

    internal fun mergePresence(states: Map<String, Boolean>) {
        _presence.value = _presence.value + states
    }

    internal fun bumpContacts() {
        _contactsRevision.value = _contactsRevision.value + 1
    }

    internal fun setDirectory(people: List<Contact>) {
        _directory.value = people
    }

    internal fun setSignalingOnline(online: Boolean) {
        _signalingOnline.value = online
    }
}
