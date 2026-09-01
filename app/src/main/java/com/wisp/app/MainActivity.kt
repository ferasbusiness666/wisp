package com.wisp.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.wisp.app.databinding.ActivityMainBinding
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** Home screen: my code, add-a-contact, and the contact list. */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: ContactAdapter
    private lateinit var directoryAdapter: DirectoryAdapter
    private var pendingCall: Contact? = null

    private val settingsLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            val contact = pendingCall
            pendingCall = null
            val granted = SetupFlow.hasCallPermissions(this)
            if (granted && contact != null) {
                dial(contact)
            } else if (!granted && result.isNotEmpty()) {
                Toast.makeText(this, R.string.need_camera_mic, Toast.LENGTH_LONG).show()
            }
            // The background-reliability prompts wait until the permission
            // dialogs are out of the way, so they aren't stacked on top.
            if (contact == null) SetupFlow.runOnce(this, settingsLauncher)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val myCode = DeviceIdentity.myCode(this)
        binding.myCode.text = myCode
        binding.copyCode.setOnClickListener { copyCode(myCode) }

        adapter = ContactAdapter(onCall = ::onContactTapped, onRemove = ::confirmRemove)
        binding.contacts.layoutManager = LinearLayoutManager(this)
        binding.contacts.adapter = adapter

        directoryAdapter = DirectoryAdapter(onAdd = ::addDiscovered)
        binding.discovered.layoutManager = LinearLayoutManager(this)
        binding.discovered.adapter = directoryAdapter

        setUpNameField()

        binding.addButton.setOnClickListener { addContact() }

        CallService.ensureRunning(this)

        val missing = SetupFlow.missingPermissions(this)
        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing)
        } else {
            SetupFlow.runOnce(this, settingsLauncher)
        }

        observeState()
        showCrashReportIfAny()
    }

    /**
     * There is no crash reporting service behind a sideloaded app, so the last
     * fatal error is surfaced here as copyable text. Without this a crash is
     * just an app that vanished, with nothing to act on.
     */
    private fun showCrashReportIfAny() {
        // A native crash leaves no Java stack trace, so an unfinished step is
        // the only evidence of where the process died.
        val unfinished = CrashLog.unfinishedStep(this)
        val report = CrashLog.lastCrash(this)
        val exit = CrashLog.lastExitReason(this)

        if (report == null && unfinished != null) {
            CrashLog.clearStep(this)
            val detail = listOfNotNull("stopped during: $unfinished", exit).joinToString("\n\n")
            AlertDialog.Builder(this)
                .setTitle(R.string.crash_title)
                .setMessage(getString(R.string.crash_step_message, detail))
                .setPositiveButton(R.string.crash_copy) { _, _ -> copyToClipboard(detail) }
                .setNegativeButton(R.string.crash_dismiss, null)
                .show()
            return
        }
        if (report == null) return
        val full = listOfNotNull(
            report,
            unfinished?.let { "stopped during: $it" },
            exit
        ).joinToString("\n\n")
        CrashLog.clearStep(this)
        AlertDialog.Builder(this)
            .setTitle(R.string.crash_title)
            .setMessage(getString(R.string.crash_message, full))
            .setPositiveButton(R.string.crash_copy) { _, _ ->
                copyToClipboard(full)
                CrashLog.clear(this)
            }
            .setNegativeButton(R.string.crash_dismiss) { _, _ -> CrashLog.clear(this) }
            .show()
    }

    override fun onPause() {
        super.onPause()
        commitName()
    }

    override fun onResume() {
        super.onResume()
        refreshList()
        CallStateHolder.consumeEndReason()?.let {
            Toast.makeText(this, it, Toast.LENGTH_LONG).show()
        }
        // The directory is otherwise only pushed when somebody joins or leaves,
        // so a screen opened in between would show nothing.
        CallService.refreshDirectory(this)
        // If a call is already live (e.g. we came back from the launcher during
        // one), jump straight back into it.
        if (CallStateHolder.call.value.phase != CallPhase.IDLE) {
            startActivity(CallActivity.ongoingIntent(this))
        }
    }

    private fun observeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(
                    CallStateHolder.presence,
                    CallStateHolder.signalingOnline,
                    CallStateHolder.directory,
                    CallStateHolder.contactsRevision
                ) { presence, online, directory, _ ->
                    Triple(presence, online, directory)
                }.collect { (presence, online, directory) ->
                    val saved = ContactStore.codes(this@MainActivity).toSet()
                    val unsaved = directory.filterNot { it.code in saved }
                    directoryAdapter.submitList(unsaved)
                    // Keep the card up even when empty: a section that vanishes
                    // reads as broken, where "nobody else is online" is an answer.
                    binding.discoveredCard.visibility = android.view.View.VISIBLE
                    binding.discovered.visibility =
                        if (unsaved.isEmpty()) android.view.View.GONE
                        else android.view.View.VISIBLE
                    binding.discoveredEmpty.visibility =
                        if (unsaved.isEmpty()) android.view.View.VISIBLE
                        else android.view.View.GONE
                    binding.discoveredEmpty.setText(
                        if (online) R.string.nobody_online else R.string.status_connecting
                    )

                    binding.connectionStatus.setText(
                        if (online) R.string.status_connected else R.string.status_connecting
                    )
                    adapter.submitList(
                        ContactStore.all(this@MainActivity).map { contact ->
                            ContactRow(contact, online && presence[contact.code] == true)
                        }
                    )
                    binding.emptyHint.visibility =
                        if (adapter.itemCount == 0) android.view.View.VISIBLE
                        else android.view.View.GONE
                }
            }
        }
    }

    private fun refreshList() {
        val presence = CallStateHolder.presence.value
        val online = CallStateHolder.signalingOnline.value
        adapter.submitList(
            ContactStore.all(this).map { ContactRow(it, online && presence[it.code] == true) }
        )
        binding.emptyHint.visibility =
            if (ContactStore.all(this).isEmpty()) android.view.View.VISIBLE
            else android.view.View.GONE
    }

    /**
     * The name is what the other phone shows instead of a code, so it is
     * republished as soon as editing finishes rather than needing a save button.
     */
    private fun setUpNameField() {
        binding.myName.setText(DeviceIdentity.myName(this))
        binding.myName.setOnEditorActionListener { view, _, _ ->
            commitName()
            view.clearFocus()
            true
        }
        binding.myName.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) commitName() }
    }

    private fun commitName() {
        val typed = binding.myName.text.toString().trim()
        if (typed.isEmpty() || typed == DeviceIdentity.myName(this)) return
        DeviceIdentity.setMyName(this, typed)
        CallService.republishName(this)
    }

    /** One tap saves someone we can already see online — no code to type. */
    private fun addDiscovered(person: Contact) {
        if (ContactStore.add(this, person)) {
            Toast.makeText(this, getString(R.string.contact_added, person.name), Toast.LENGTH_SHORT).show()
            CallService.refreshContacts(this)
            // Saving is mutual, so they don't have to add us separately.
            CallService.askToBeAdded(this, person.code)
            refreshList()
        }
    }

    private fun copyToClipboard(text: String) {
        val clipboard = getSystemService(ClipboardManager::class.java)
        clipboard?.setPrimaryClip(ClipData.newPlainText("Wisp", text))
        Toast.makeText(this, R.string.crash_copied, Toast.LENGTH_SHORT).show()
    }

    private fun copyCode(code: String) {
        val clipboard = getSystemService(ClipboardManager::class.java) ?: return
        clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.app_name), code))
        Toast.makeText(this, R.string.code_copied, Toast.LENGTH_SHORT).show()
    }

    private fun addContact() {
        val code = DeviceIdentity.normalize(binding.addCode.text.toString())
        val name = binding.addName.text.toString().trim().ifEmpty { code }

        when {
            !DeviceIdentity.isValid(code) ->
                Toast.makeText(this, R.string.invalid_code, Toast.LENGTH_SHORT).show()

            code == DeviceIdentity.myCode(this) ->
                Toast.makeText(this, R.string.cant_add_self, Toast.LENGTH_SHORT).show()

            !ContactStore.add(this, Contact(code, name)) ->
                Toast.makeText(this, R.string.already_added, Toast.LENGTH_SHORT).show()

            else -> {
                binding.addCode.text?.clear()
                binding.addName.text?.clear()
                // Adding them here doesn't add us there — same as saving a phone
                // number. Both sides add each other once.
                Toast.makeText(this, getString(R.string.contact_added, name), Toast.LENGTH_SHORT).show()
                CallService.refreshContacts(this)
                CallService.askToBeAdded(this, code)
                refreshList()
            }
        }
    }

    private fun confirmRemove(contact: Contact) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.remove_title, contact.name))
            .setMessage(R.string.remove_message)
            .setPositiveButton(R.string.remove) { _, _ ->
                ContactStore.remove(this, contact.code)
                CallService.refreshContacts(this)
                refreshList()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun onContactTapped(contact: Contact) {
        if (!SetupFlow.hasCallPermissions(this)) {
            pendingCall = contact
            permissionLauncher.launch(SetupFlow.missingPermissions(this))
            return
        }
        dial(contact)
    }

    private fun dial(contact: Contact) {
        if (CallStateHolder.call.value.phase != CallPhase.IDLE) {
            Toast.makeText(this, R.string.already_in_call, Toast.LENGTH_SHORT).show()
            return
        }
        // The service checks the socket and reconnects before sending the offer.
        CallService.placeCall(this, contact.code, contact.name)
        startActivity(CallActivity.outgoingIntent(this, contact.code, contact.name))
    }
}
