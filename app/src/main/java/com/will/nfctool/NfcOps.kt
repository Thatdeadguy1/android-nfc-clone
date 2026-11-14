package com.will.nfctool

import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.Tag
import android.nfc.tech.Ndef
import android.nfc.tech.NdefFormatable
import android.nfc.tech.NfcA
import java.io.IOException
import java.security.MessageDigest
import java.util.Locale

/** Result of a read: everything we could learn about the presented tag. */
data class TagInfo(
    val uidHex: String,
    val techList: List<String>,
    val typeLabel: String,
    val maxSizeBytes: Int?,
    val usedBytes: Int?,
    val writable: Boolean?,
    val canMakeReadOnly: Boolean?,
    val ntagModel: String?,         // "NTAG213/215/216" or null if not an NTAG
    val records: List<NdefRecordView>,
    val rawError: String? = null
)

data class NdefRecordView(
    val typeLabel: String,
    val content: String
)

sealed class OpResult {
    data class Ok(val message: String) : OpResult()
    data class Err(val message: String) : OpResult()
}

object NfcOps {

    // ---- Formatting helpers -------------------------------------------------

    fun bytesToHex(bytes: ByteArray): String =
        bytes.joinToString(" ") { String.format("%02X", it) }

    private fun compactHex(bytes: ByteArray): String =
        bytes.joinToString("") { String.format("%02X", it) }

    // ---- READ ---------------------------------------------------------------

    fun readTag(tag: Tag): TagInfo {
        val uid = bytesToHex(tag.id)
        val techs = tag.techList.map { it.substringAfterLast('.') }
        val ntagModel = detectNtagModel(tag)

        var typeLabel = "Unknown / non-NDEF"
        var maxSize: Int? = null
        var used: Int? = null
        var writable: Boolean? = null
        var canReadOnly: Boolean? = null
        val records = mutableListOf<NdefRecordView>()
        var err: String? = null

        val ndef = Ndef.get(tag)
        if (ndef != null) {
            try {
                ndef.connect()
                typeLabel = ndef.type
                maxSize = ndef.maxSize
                writable = ndef.isWritable
                canReadOnly = ndef.canMakeReadOnly()
                val msg: NdefMessage? = ndef.ndefMessage
                if (msg != null) {
                    used = msg.toByteArray().size
                    msg.records.forEach { records.add(describeRecord(it)) }
                } else {
                    used = 0
                }
            } catch (e: Exception) {
                err = "NDEF read failed: ${e.message}"
            } finally {
                runCatching { ndef.close() }
            }
        } else {
            val formatable = NdefFormatable.get(tag)
            typeLabel = if (formatable != null) "Unformatted (NDEF-formatable)" else typeLabel
            writable = formatable != null
        }

        return TagInfo(
            uidHex = uid,
            techList = techs,
            typeLabel = if (ntagModel != null) "$typeLabel ($ntagModel)" else typeLabel,
            maxSizeBytes = maxSize,
            usedBytes = used,
            writable = writable,
            canMakeReadOnly = canReadOnly,
            ntagModel = ntagModel,
            records = records,
            rawError = err
        )
    }

    private fun describeRecord(r: NdefRecord): NdefRecordView {
        return when {
            r.tnf == NdefRecord.TNF_WELL_KNOWN &&
                r.type.contentEquals(NdefRecord.RTD_TEXT) -> {
                NdefRecordView("Text", decodeText(r))
            }
            r.tnf == NdefRecord.TNF_WELL_KNOWN &&
                r.type.contentEquals(NdefRecord.RTD_URI) -> {
                NdefRecordView("URI", r.toUri()?.toString() ?: "(unparseable URI)")
            }
            r.tnf == NdefRecord.TNF_MIME_MEDIA -> {
                NdefRecordView("MIME: ${String(r.type)}", String(r.payload))
            }
            else -> NdefRecordView(
                "TNF ${r.tnf}",
                "payload=${compactHex(r.payload)}"
            )
        }
    }

    private fun decodeText(r: NdefRecord): String {
        val payload = r.payload
        if (payload.isEmpty()) return ""
        val isUtf16 = (payload[0].toInt() and 0x80) != 0
        val langLen = payload[0].toInt() and 0x3F
        val charset = if (isUtf16) Charsets.UTF_16 else Charsets.UTF_8
        return String(payload, langLen + 1, payload.size - langLen - 1, charset)
    }

    // ---- WRITE --------------------------------------------------------------

    fun buildTextMessage(text: String): NdefMessage =
        NdefMessage(arrayOf(NdefRecord.createTextRecord(Locale.ENGLISH.language, text)))

    fun buildUriMessage(uri: String): NdefMessage =
        NdefMessage(arrayOf(NdefRecord.createUri(uri)))

    fun writeMessage(tag: Tag, message: NdefMessage): OpResult {
        val ndef = Ndef.get(tag)
        if (ndef != null) {
            return try {
                ndef.connect()
                if (!ndef.isWritable) return OpResult.Err("Tag is read-only — cannot write.")
                val size = message.toByteArray().size
                if (size > ndef.maxSize)
                    return OpResult.Err("Message is $size bytes but tag holds only ${ndef.maxSize}.")
                ndef.writeNdefMessage(message)
                OpResult.Ok("Wrote $size bytes to tag.")
            } catch (e: Exception) {
                OpResult.Err("Write failed: ${e.message}")
            } finally {
                runCatching { ndef.close() }
            }
        }
        // Tag not yet NDEF-formatted — format and write in one step.
        val formatable = NdefFormatable.get(tag)
            ?: return OpResult.Err("Tag is neither NDEF nor NDEF-formatable.")
        return try {
            formatable.connect()
            formatable.format(message)
            OpResult.Ok("Formatted tag and wrote message.")
        } catch (e: Exception) {
            OpResult.Err("Format+write failed: ${e.message}")
        } finally {
            runCatching { formatable.close() }
        }
    }

    // ---- ERASE --------------------------------------------------------------

    /** Overwrites with a single empty NDEF record. */
    fun erase(tag: Tag): OpResult {
        val empty = NdefMessage(arrayOf(NdefRecord(NdefRecord.TNF_EMPTY, ByteArray(0), ByteArray(0), ByteArray(0))))
        val r = writeMessage(tag, empty)
        return when (r) {
            is OpResult.Ok -> OpResult.Ok("Tag erased (empty NDEF written).")
            is OpResult.Err -> r
        }
    }

    // ---- PERMANENT READ-ONLY (irreversible) ---------------------------------

    fun makePermanentReadOnly(tag: Tag): OpResult {
        val ndef = Ndef.get(tag) ?: return OpResult.Err("Tag is not NDEF — cannot lock this way.")
        return try {
            ndef.connect()
            if (!ndef.canMakeReadOnly()) return OpResult.Err("This tag can't be made permanently read-only.")
            if (ndef.makeReadOnly())
                OpResult.Ok("Tag is now PERMANENTLY read-only. This cannot be undone.")
            else
                OpResult.Err("makeReadOnly() returned false — tag may not support it.")
        } catch (e: Exception) {
            OpResult.Err("Permanent lock failed: ${e.message}")
        } finally {
            runCatching { ndef.close() }
        }
    }

    // ---- NTAG PASSWORD LOCK (reversible) ------------------------------------
    // NTAG21x: write-protect user memory with a 32-bit password (PWD) + PACK,
    // AUTH0 (first protected page) and ACCESS (PROT bit). Reversible with PWD_AUTH.

    private const val CMD_GET_VERSION = 0x60
    private const val CMD_READ = 0x30
    private const val CMD_WRITE = 0xA2
    private const val CMD_PWD_AUTH = 0x1B

    private data class NtagLayout(val model: String, val cfg0: Int, val cfg1: Int, val pwd: Int, val pack: Int)

    private fun detectNtagModel(tag: Tag): String? {
        val nfcA = NfcA.get(tag) ?: return null
        return try {
            nfcA.connect()
            val v = nfcA.transceive(byteArrayOf(CMD_GET_VERSION.toByte()))
            layoutFromVersion(v)?.model
        } catch (e: Exception) {
            null
        } finally {
            runCatching { nfcA.close() }
        }
    }

    private fun layoutFromVersion(v: ByteArray): NtagLayout? {
        // GET_VERSION byte 6 = storage size code.
        if (v.size < 8) return null
        return when (v[6].toInt() and 0xFF) {
            0x0F -> NtagLayout("NTAG213", cfg0 = 0x29, cfg1 = 0x2A, pwd = 0x2B, pack = 0x2C)
            0x11 -> NtagLayout("NTAG215", cfg0 = 0x83, cfg1 = 0x84, pwd = 0x85, pack = 0x86)
            0x13 -> NtagLayout("NTAG216", cfg0 = 0xE3, cfg1 = 0xE4, pwd = 0xE5, pack = 0xE6)
            else -> null
        }
    }

    /** Derive 4 password bytes + 2 PACK bytes deterministically from a user string. */
    private fun derivePwdAndPack(secret: String): Pair<ByteArray, ByteArray> {
        val digest = MessageDigest.getInstance("SHA-256").digest(secret.toByteArray(Charsets.UTF_8))
        val pwd = digest.copyOfRange(0, 4)
        val pack = digest.copyOfRange(4, 6)
        return pwd to pack
    }

    private fun NfcA.writePage(page: Int, data: ByteArray) {
        require(data.size == 4)
        transceive(byteArrayOf(CMD_WRITE.toByte(), page.toByte(), data[0], data[1], data[2], data[3]))
    }

    private fun NfcA.readPage(page: Int): ByteArray {
        // Returns 16 bytes (4 pages); first 4 are the requested page.
        return transceive(byteArrayOf(CMD_READ.toByte(), page.toByte()))
    }

    fun lockWithPassword(tag: Tag, secret: String, protectReadToo: Boolean = false): OpResult {
        if (secret.isBlank()) return OpResult.Err("Password is empty.")
        val nfcA = NfcA.get(tag) ?: return OpResult.Err("Tag is not NfcA — password lock unsupported.")
        return try {
            nfcA.connect()
            val layout = layoutFromVersion(nfcA.transceive(byteArrayOf(CMD_GET_VERSION.toByte())))
                ?: return OpResult.Err("Not an NTAG213/215/216 — password lock only works on those.")

            val (pwd, pack) = derivePwdAndPack(secret)

            // 1. Password and PACK first, while memory is still open.
            nfcA.writePage(layout.pwd, pwd)
            nfcA.writePage(layout.pack, byteArrayOf(pack[0], pack[1], 0, 0))

            // 2. ACCESS byte (CFG1 byte 0): PROT bit7 => read+write vs write-only.
            val cfg1 = nfcA.readPage(layout.cfg1).copyOf(4)
            cfg1[0] = if (protectReadToo) (cfg1[0].toInt() or 0x80).toByte()
                      else (cfg1[0].toInt() and 0x7F).toByte()
            nfcA.writePage(layout.cfg1, cfg1)

            // 3. AUTH0 (CFG0 byte 3) LAST: first page requiring auth. 0x04 = all user data.
            val cfg0 = nfcA.readPage(layout.cfg0).copyOf(4)
            cfg0[3] = 0x04
            nfcA.writePage(layout.cfg0, cfg0)

            OpResult.Ok("${layout.model} write-protected. Keep your password — you need it to unlock or rewrite.")
        } catch (e: IOException) {
            OpResult.Err("Lock failed (tag moved?): ${e.message}")
        } catch (e: Exception) {
            OpResult.Err("Lock failed: ${e.message}")
        } finally {
            runCatching { nfcA.close() }
        }
    }

    fun unlockPassword(tag: Tag, secret: String): OpResult {
        val nfcA = NfcA.get(tag) ?: return OpResult.Err("Tag is not NfcA.")
        return try {
            nfcA.connect()
            val layout = layoutFromVersion(nfcA.transceive(byteArrayOf(CMD_GET_VERSION.toByte())))
                ?: return OpResult.Err("Not an NTAG213/215/216.")
            val (pwd, _) = derivePwdAndPack(secret)

            // Authenticate; wrong password makes the tag NACK (transceive throws).
            nfcA.transceive(byteArrayOf(CMD_PWD_AUTH.toByte(), pwd[0], pwd[1], pwd[2], pwd[3]))

            // Disable protection: AUTH0 = 0xFF (no page protected).
            val cfg0 = nfcA.readPage(layout.cfg0).copyOf(4)
            cfg0[3] = 0xFF.toByte()
            nfcA.writePage(layout.cfg0, cfg0)

            OpResult.Ok("Password removed — tag is writable again.")
        } catch (e: Exception) {
            OpResult.Err("Unlock failed — wrong password or incompatible tag. (${e.message})")
        } finally {
            runCatching { nfcA.close() }
        }
    }
}
