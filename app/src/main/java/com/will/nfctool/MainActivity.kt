package com.will.nfctool

import android.content.Intent
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    private var nfcAdapter: NfcAdapter? = null

    // Mode + inputs are read from the NFC reader-mode callback (a binder thread),
    // so they are kept as @Volatile here and mirrored from Compose state.
    @Volatile private var mode: Mode = Mode.READ
    @Volatile private var writeText: String = ""
    @Volatile private var writeUri: String = ""
    @Volatile private var writeIsUri: Boolean = false
    @Volatile private var password: String = ""
    @Volatile private var protectReadToo: Boolean = false

    // Compose-visible state.
    private val statusLine = mutableStateOf("Tap a tag to begin.")
    private val lastTag = mutableStateOf<TagInfo?>(null)
    private val busy = mutableStateOf(false)

    enum class Mode { READ, WRITE, ERASE, LOCK, UNLOCK, PERMLOCK, EMULATE }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        setContent { AppUi() }
    }

    override fun onResume() {
        super.onResume()
        applyNfcMode()
    }

    override fun onPause() {
        super.onPause()
        nfcAdapter?.disableReaderMode(this)
    }

    /**
     * Reader mode turns the phone into a reader (for read/write/erase/lock).
     * For EMULATE we must NOT be in reader mode — then the phone presents the
     * HCE card to an external reader instead.
     */
    private fun applyNfcMode() {
        val adapter = nfcAdapter ?: return
        if (mode == Mode.EMULATE) {
            adapter.disableReaderMode(this)
            statusLine.value = "Emulating — hold another reader/phone to this one's back."
        } else {
            val flags = NfcAdapter.FLAG_READER_NFC_A or
                NfcAdapter.FLAG_READER_NFC_B or
                NfcAdapter.FLAG_READER_NFC_F or
                NfcAdapter.FLAG_READER_NFC_V or
                NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK
            adapter.enableReaderMode(this, ::onTag, flags, null)
        }
    }

    private fun onTag(tag: Tag) {
        // Reader callback arrives on a binder thread; hop to Main for all state writes.
        lifecycleScope.launch {
            if (busy.value) return@launch
            busy.value = true
            val (status, info) = withContext(Dispatchers.IO) { handleTag(tag) }
            info?.let { lastTag.value = it }
            statusLine.value = status
            busy.value = false
        }
    }

    private fun handleTag(tag: Tag): Pair<String, TagInfo?> {
        return when (mode) {
            Mode.READ -> {
                val info = NfcOps.readTag(tag)
                "Read OK — UID ${info.uidHex}" to info
            }
            Mode.WRITE -> {
                val msg = if (writeIsUri) NfcOps.buildUriMessage(writeUri.trim())
                          else NfcOps.buildTextMessage(writeText)
                resultText(NfcOps.writeMessage(tag, msg)) to null
            }
            Mode.ERASE -> resultText(NfcOps.erase(tag)) to null
            Mode.LOCK -> resultText(NfcOps.lockWithPassword(tag, password, protectReadToo)) to null
            Mode.UNLOCK -> resultText(NfcOps.unlockPassword(tag, password)) to null
            Mode.PERMLOCK -> resultText(NfcOps.makePermanentReadOnly(tag)) to null
            Mode.EMULATE -> ("Emulating — this phone is acting as a tag." to null)
        }
    }

    private fun resultText(r: OpResult): String = when (r) {
        is OpResult.Ok -> "✓ ${r.message}"
        is OpResult.Err -> "✗ ${r.message}"
    }

    private fun setMode(m: Mode) {
        mode = m
        applyNfcMode()
        if (m != Mode.EMULATE && m != Mode.READ) {
            statusLine.value = "Ready — tap a tag to ${m.name.lowercase()}."
        } else if (m == Mode.READ) {
            statusLine.value = "Tap a tag to read."
        }
    }

    // ---- UI -----------------------------------------------------------------

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun AppUi() {
        var selectedTab by remember { mutableStateOf(0) }
        val tabs = listOf(
            Triple("Read", Icons.Filled.Search, Mode.READ),
            Triple("Write", Icons.Filled.Edit, Mode.WRITE),
            Triple("Erase", Icons.Filled.Delete, Mode.ERASE),
            Triple("Lock", Icons.Filled.Lock, Mode.LOCK),
            Triple("Emulate", Icons.Filled.Share, Mode.EMULATE),
        )

        MaterialTheme(colorScheme = darkColorScheme()) {
            Surface(modifier = Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxSize()) {
                    TopAppBar(title = { Text("NFC Tool") })
                    ScrollableTabRow(selectedTabIndex = selectedTab, edgePadding = 8.dp) {
                        tabs.forEachIndexed { i, (label, icon, m) ->
                            Tab(
                                selected = selectedTab == i,
                                onClick = { selectedTab = i; setMode(m) },
                                text = { Text(label) },
                                icon = { Icon(icon, contentDescription = label) }
                            )
                        }
                    }

                    if (nfcAdapter == null) {
                        Banner("This device has no NFC adapter.")
                    } else if (nfcAdapter?.isEnabled == false) {
                        Banner("NFC is OFF. Enable it in system settings.") {
                            startActivity(Intent(Settings.ACTION_NFC_SETTINGS))
                        }
                    }

                    StatusCard()

                    Column(
                        Modifier
                            .weight(1f)
                            .verticalScroll(rememberScrollState())
                            .padding(16.dp)
                    ) {
                        when (tabs[selectedTab].third) {
                            Mode.READ -> ReadPane()
                            Mode.WRITE -> WritePane()
                            Mode.ERASE -> ErasePane()
                            Mode.LOCK -> LockPane()
                            Mode.EMULATE -> EmulatePane()
                            else -> {}
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun StatusCard() {
        Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Row(
                Modifier.padding(16.dp).fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (busy.value) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                }
                Text(statusLine.value, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }

    @Composable
    private fun Banner(text: String, onClick: (() -> Unit)? = null) {
        Card(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
        ) {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Warning, null)
                Spacer(Modifier.width(8.dp))
                Text(text, Modifier.weight(1f))
                if (onClick != null) TextButton(onClick = onClick) { Text("Settings") }
            }
        }
    }

    @Composable
    private fun ReadPane() {
        Text("Hold a tag to the back of the phone.", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(12.dp))
        val info = lastTag.value
        if (info != null) {
            SelectionField("UID", info.uidHex)
            SelectionField("Type", info.typeLabel)
            SelectionField("Tech", info.techList.joinToString(", "))
            info.maxSizeBytes?.let {
                SelectionField("Capacity", "${info.usedBytes ?: 0} / $it bytes used")
            }
            info.writable?.let { SelectionField("Writable", if (it) "yes" else "no (read-only)") }
            info.ntagModel?.let { SelectionField("Chip", it) }
            if (info.records.isEmpty()) {
                Text("No NDEF records.", style = MaterialTheme.typography.bodySmall)
            } else {
                Spacer(Modifier.height(8.dp))
                Text("Records", style = MaterialTheme.typography.titleSmall)
                info.records.forEachIndexed { i, r ->
                    SelectionField("${i + 1}. ${r.typeLabel}", r.content)
                }
            }
            info.rawError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }

    @Composable
    private fun WritePane() {
        var isUri by remember { mutableStateOf(false) }
        var text by remember { mutableStateOf("") }
        Row(verticalAlignment = Alignment.CenterVertically) {
            FilterChip(selected = !isUri, onClick = { isUri = false; writeIsUri = false }, label = { Text("Text") })
            Spacer(Modifier.width(8.dp))
            FilterChip(selected = isUri, onClick = { isUri = true; writeIsUri = true }, label = { Text("URL / URI") })
        }
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = text,
            onValueChange = {
                text = it
                if (isUri) writeUri = it else writeText = it
            },
            label = { Text(if (isUri) "https://… or tel:… etc." else "Text to write") },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Set your content, then tap the tag. New content replaces what's there.",
            style = MaterialTheme.typography.bodySmall
        )
    }

    @Composable
    private fun ErasePane() {
        Text("Tap a tag to overwrite it with an empty record.", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            "This only works if the tag is still writable. A password-locked or " +
                "permanently-locked tag can't be erased.",
            style = MaterialTheme.typography.bodySmall
        )
    }

    @Composable
    private fun LockPane() {
        var pwd by remember { mutableStateOf("") }
        var readToo by remember { mutableStateOf(false) }

        Text("Reversible password lock", style = MaterialTheme.typography.titleSmall)
        Text(
            "NTAG213/215/216 only. Protects the tag with a password so nobody else " +
                "can rewrite it. Remember the password — you need it to unlock.",
            style = MaterialTheme.typography.bodySmall
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = pwd,
            onValueChange = { pwd = it; password = it },
            label = { Text("Password") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth()
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = readToo, onCheckedChange = { readToo = it; protectReadToo = it })
            Text("Also require password to READ", style = MaterialTheme.typography.bodySmall)
        }
        Row {
            Button(onClick = { setMode(Mode.LOCK) }, enabled = pwd.isNotBlank()) {
                Icon(Icons.Filled.Lock, null); Spacer(Modifier.width(6.dp)); Text("Arm lock")
            }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = { setMode(Mode.UNLOCK) }, enabled = pwd.isNotBlank()) {
                Icon(Icons.Filled.Refresh, null); Spacer(Modifier.width(6.dp)); Text("Arm unlock")
            }
        }

        Spacer(Modifier.height(20.dp))
        HorizontalDivider()
        Spacer(Modifier.height(12.dp))

        Text("Permanent lock", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.error)
        Text(
            "Makes the tag read-only FOREVER. No password, no undo, works on most tag " +
                "types. Only use on a tag you never want to change again.",
            style = MaterialTheme.typography.bodySmall
        )
        Spacer(Modifier.height(8.dp))
        var confirm by remember { mutableStateOf(false) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = confirm, onCheckedChange = { confirm = it })
            Text("I understand this is irreversible", style = MaterialTheme.typography.bodySmall)
        }
        Button(
            onClick = { setMode(Mode.PERMLOCK) },
            enabled = confirm,
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
        ) {
            Icon(Icons.Filled.Warning, null); Spacer(Modifier.width(6.dp)); Text("Arm permanent lock")
        }
    }

    @Composable
    private fun EmulatePane() {
        val ctx = this@MainActivity
        var isUri by remember { mutableStateOf(false) }
        var text by remember { mutableStateOf("NFC Tool emulated tag") }

        Text("Host Card Emulation (Type 4 NDEF)", style = MaterialTheme.typography.titleSmall)
        Text(
            "This phone presents itself as a readable NDEF tag. Hold another phone or " +
                "reader to the back of this one. Note: this emulates an NDEF payload, " +
                "not a cloned UID or a MIFARE Classic card — Android can't do those.",
            style = MaterialTheme.typography.bodySmall
        )
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            FilterChip(selected = !isUri, onClick = { isUri = false }, label = { Text("Text") })
            Spacer(Modifier.width(8.dp))
            FilterChip(selected = isUri, onClick = { isUri = true }, label = { Text("URL / URI") })
        }
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            label = { Text(if (isUri) "URI to present" else "Text to present") },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        Button(onClick = {
            val msg = if (isUri) NfcOps.buildUriMessage(text.trim()) else NfcOps.buildTextMessage(text)
            HcePayloadStore.setMessage(ctx, msg)
            statusLine.value = "Payload set. Emulating — hold a reader to this phone."
        }) {
            Icon(Icons.Filled.Share, null); Spacer(Modifier.width(6.dp)); Text("Set payload & emulate")
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "Tip: if another NFC app grabs the tap instead, set this app as the default " +
                "for 'Other' HCE in Settings → Connected devices → NFC → Contactless payments / Other.",
            style = MaterialTheme.typography.bodySmall
        )
    }

    @Composable
    private fun SelectionField(label: String, value: String) {
        Column(Modifier.padding(vertical = 4.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            Text(value, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
        }
    }
}
