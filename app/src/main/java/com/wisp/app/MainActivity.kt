package com.wisp.app

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.wisp.app.databinding.ActivityMainBinding
import com.wisp.app.databinding.SheetAddBinding
import com.wisp.app.databinding.SheetContactBinding
import com.wisp.app.databinding.SheetSettingsBinding
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Home: people online now, then your contacts. Your code, your name and
 * updates live behind the profile button; adding someone is behind +.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: ContactAdapter
    private lateinit var directoryAdapter: DirectoryAdapter
    private var pendingCall: Contact? = null

    /** The newest release found by the last check, if it's newer than us. */
    private var availableUpdate: Updater.Release? = null
    private var updating = false
    private var settingsSheet: SheetSettingsBinding? = null

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
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyInsets()

        adapter = ContactAdapter(onCall = ::onCallTapped, onOpen = ::openContact)
        binding.contacts.layoutManager = LinearLayoutManager(this)
        binding.contacts.adapter = adapter

        directoryAdapter = DirectoryAdapter(onAdd = ::addDiscovered)
        binding.discovered.layoutManager =
            LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        binding.discovered.adapter = directoryAdapter

        binding.addButton.setOnClickListener { openAddSheet() }
        binding.settingsButton.setOnClickListener { openSettingsSheet() }
        binding.returnToCall.setOnClickListener { startActivity(CallActivity.ongoingIntent(this)) }
        binding.updateBanner.setOnClickListener { availableUpdate?.let { startUpdate(it) } }

        CallService.ensureRunning(this)

        val missing = SetupFlow.missingPermissions(this)
        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing)
        } else {
            SetupFlow.runOnce(this, settingsLauncher)
        }

        observeState()
        showCrashReportIfAny()
        checkForUpdate(quiet = true)
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
    }

    private fun applyInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.homeRoot) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            view.updatePadding(left = bars.left, top = bars.top, right = bars.right, bottom = bars.bottom)
            insets
        }
    }

    // ---- state -------------------------------------------------------------

    private fun observeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
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
                        binding.onlineSection.visibility =
                            if (unsaved.isEmpty()) View.GONE else View.VISIBLE

                        binding.connectionStatus.setText(
                            if (online) R.string.status_connected else R.string.status_connecting
                        )
                        binding.connectionStatus.setCompoundDrawablesRelativeWithIntrinsicBounds(
                            if (online) R.drawable.dot_small_online else R.drawable.dot_small_offline,
                            0, 0, 0
                        )
                        showContacts(presence, online)
                    }
                }
                launch {
                    CallStateHolder.call.collect { call ->
                        val live = call.phase != CallPhase.IDLE
                        binding.returnToCall.visibility = if (live) View.VISIBLE else View.GONE
                        if (live) {
                            binding.returnToCall.text =
                                getString(R.string.return_to_call, call.peerName ?: call.peerCode ?: "")
                        }
                    }
                }
            }
        }
    }

    private fun refreshList() {
        showContacts(CallStateHolder.presence.value, CallStateHolder.signalingOnline.value)
    }

    private fun showContacts(presence: Map<String, Boolean>, online: Boolean) {
        val rows = ContactStore.all(this).map { contact ->
            ContactRow(contact, online && presence[contact.code] == true)
        }.sortedWith(compareByDescending<ContactRow> { it.online }.thenBy { it.contact.name.lowercase() })
        adapter.submitList(rows)
        binding.emptyHint.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
    }

    // ---- sheets --------------------------------------------------------------

    private fun openSettingsSheet() {
        val sheet = SheetSettingsBinding.inflate(layoutInflater)
        val dialog = BottomSheetDialog(this)
        dialog.setContentView(sheet.root)
        settingsSheet = sheet

        val myCode = DeviceIdentity.myCode(this)
        Avatars.bind(sheet.avatar, DeviceIdentity.myName(this).ifBlank { getString(R.string.you) }, myCode)
        sheet.myCode.text = myCode
        sheet.copyCode.setOnClickListener { copyCode(myCode) }

        sheet.myName.setText(DeviceIdentity.myName(this))
        sheet.myName.setOnEditorActionListener { view, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                commitName(view.text.toString())
                view.clearFocus()
            }
            false
        }

        sheet.versionText.text = getString(R.string.version_label, Updater.currentVersion)
        renderUpdateSection()
        sheet.updateButton.setOnClickListener {
            val update = availableUpdate
            if (update != null) startUpdate(update) else checkForUpdate(quiet = false)
        }

        dialog.setOnDismissListener {
            commitName(sheet.myName.text.toString())
            settingsSheet = null
        }
        dialog.show()
    }

    private fun openAddSheet() {
        val sheet = SheetAddBinding.inflate(layoutInflater)
        val dialog = BottomSheetDialog(this)
        dialog.setContentView(sheet.root)
        sheet.addConfirm.setOnClickListener {
            if (addContact(sheet.addCode.text.toString(), sheet.addName.text.toString())) dialog.dismiss()
        }
        sheet.addName.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) sheet.addConfirm.performClick()
            false
        }
        dialog.show()
        sheet.addCode.requestFocus()
    }

    private fun openContact(contact: Contact) {
        val sheet = SheetContactBinding.inflate(layoutInflater)
        val dialog = BottomSheetDialog(this)
        dialog.setContentView(sheet.root)
        Avatars.bind(sheet.avatar, contact.name, contact.code)
        sheet.name.text = contact.name
        sheet.code.text = contact.code
        sheet.callButton.setOnClickListener {
            dialog.dismiss()
            onCallTapped(contact)
        }
        sheet.removeButton.setOnClickListener {
            dialog.dismiss()
            confirmRemove(contact)
        }
        dialog.show()
    }

    // ---- updates ---------------------------------------------------------------

    private fun checkForUpdate(quiet: Boolean) {
        if (updating) return
        if (!quiet) {
            settingsSheet?.let { sheet ->
                sheet.updateStatus.visibility = View.VISIBLE
                sheet.updateStatus.setText(R.string.checking_updates)
                sheet.updateButton.isEnabled = false
            }
        }
        lifecycleScope.launch {
            when (val result = Updater.check()) {
                is Updater.Check.Available -> availableUpdate = result.release
                Updater.Check.UpToDate -> {
                    availableUpdate = null
                    if (!quiet) settingsSheet?.updateStatus?.setText(R.string.up_to_date)
                }
                is Updater.Check.Failed -> {
                    if (!quiet) {
                        settingsSheet?.updateStatus?.text = getString(R.string.update_failed, result.reason)
                    }
                }
            }
            settingsSheet?.updateButton?.isEnabled = true
            renderUpdateSection()
        }
    }

    private fun renderUpdateSection() {
        val update = availableUpdate
        binding.updateBanner.visibility = if (update != null && !updating) View.VISIBLE else View.GONE
        update?.let { binding.updateBanner.text = getString(R.string.update_banner, it.version) }

        val sheet = settingsSheet ?: return
        if (update != null && !updating) {
            sheet.updateStatus.visibility = View.VISIBLE
            sheet.updateStatus.text = getString(R.string.update_available, update.version)
            sheet.updateButton.setText(R.string.update_now)
        } else if (!updating) {
            sheet.updateButton.setText(R.string.check_updates)
        }
    }

    private fun startUpdate(release: Updater.Release) {
        if (updating) return
        if (!Updater.canInstall(this)) {
            AlertDialog.Builder(this)
                .setTitle(R.string.allow_installs_title)
                .setMessage(R.string.allow_installs_message)
                .setPositiveButton(R.string.open_settings) { _, _ ->
                    runCatching { startActivity(Updater.installPermissionIntent(this)) }
                }
                .setNegativeButton(R.string.not_now, null)
                .show()
            return
        }
        updating = true
        renderUpdateSection()
        showUpdateProgress(0)
        lifecycleScope.launch {
            try {
                val apk = Updater.download(this@MainActivity, release) { percent -> showUpdateProgress(percent) }
                settingsSheet?.updateStatus?.setText(R.string.installing_update)
                Toast.makeText(this@MainActivity, R.string.installing_update, Toast.LENGTH_SHORT).show()
                Updater.install(this@MainActivity, apk)
            } catch (error: Exception) {
                val reason = error.message ?: error.javaClass.simpleName
                Toast.makeText(
                    this@MainActivity,
                    getString(R.string.update_failed, reason),
                    Toast.LENGTH_LONG
                ).show()
                settingsSheet?.updateStatus?.text = getString(R.string.update_failed, reason)
            } finally {
                updating = false
                settingsSheet?.updateProgress?.visibility = View.GONE
                settingsSheet?.updateButton?.isEnabled = true
                renderUpdateSection()
            }
        }
    }

    private fun showUpdateProgress(percent: Int) {
        val text = getString(R.string.downloading_update, percent)
        binding.updateBanner.visibility = View.VISIBLE
        binding.updateBanner.text = text
        settingsSheet?.let { sheet ->
            sheet.updateStatus.visibility = View.VISIBLE
            sheet.updateStatus.text = text
            sheet.updateProgress.visibility = View.VISIBLE
            sheet.updateProgress.progress = percent
            sheet.updateButton.isEnabled = false
        }
    }

    // ---- crash report ------------------------------------------------------------

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

    // ---- contacts ----------------------------------------------------------------

    /**
     * The name is what the other phone shows instead of a code, so it is
     * republished as soon as editing finishes rather than needing a save button.
     */
    private fun commitName(typed: String) {
        val name = typed.trim()
        if (name.isEmpty() || name == DeviceIdentity.myName(this)) return
        DeviceIdentity.setMyName(this, name)
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

    /** Returns true when the contact was saved, so the sheet can close. */
    private fun addContact(rawCode: String, rawName: String): Boolean {
        val code = DeviceIdentity.normalize(rawCode)
        val name = rawName.trim().ifEmpty { code }

        when {
            !DeviceIdentity.isValid(code) ->
                Toast.makeText(this, R.string.invalid_code, Toast.LENGTH_SHORT).show()

            code == DeviceIdentity.myCode(this) ->
                Toast.makeText(this, R.string.cant_add_self, Toast.LENGTH_SHORT).show()

            !ContactStore.add(this, Contact(code, name)) ->
                Toast.makeText(this, R.string.already_added, Toast.LENGTH_SHORT).show()

            else -> {
                Toast.makeText(this, getString(R.string.contact_added, name), Toast.LENGTH_SHORT).show()
                CallService.refreshContacts(this)
                CallService.askToBeAdded(this, code)
                refreshList()
                return true
            }
        }
        return false
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

    private fun onCallTapped(contact: Contact) {
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
