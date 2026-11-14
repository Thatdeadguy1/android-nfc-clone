package com.will.nfctool

import android.nfc.cardemulation.HostApduService
import android.os.Bundle

/**
 * Emulates an NFC Forum Type 4 Tag over Host Card Emulation.
 * An external reader (or another phone) selects the NDEF application, reads the
 * Capability Container, then reads the NDEF file — exactly as it would a real tag.
 *
 * This is the real, supported form of NFC "emulation" on Android. It does NOT
 * clone a tag's UID or emulate MIFARE Classic — the NFC controller owns the UID
 * and the Crypto1 cipher is not exposed to apps.
 */
class NdefHostApduService : HostApduService() {

    private enum class Selected { NONE, CC, NDEF }
    private var selected = Selected.NONE

    private val okSw = byteArrayOf(0x90.toByte(), 0x00)
    private val fileNotFoundSw = byteArrayOf(0x6A.toByte(), 0x82.toByte())
    private val wrongLengthSw = byteArrayOf(0x67.toByte(), 0x00)
    private val insNotSupportedSw = byteArrayOf(0x6D.toByte(), 0x00)

    // NDEF Type 4 application AID.
    private val selectAppApdu = hexToBytes("00A4040007D276000085010100")
    private val ccFileId = byteArrayOf(0xE1.toByte(), 0x03)
    private val ndefFileId = byteArrayOf(0xE1.toByte(), 0x04)

    // Capability Container: NDEF file E104, max 1024 bytes, read granted, write denied.
    private val capabilityContainer = byteArrayOf(
        0x00, 0x0F,             // CCLEN = 15
        0x20,                   // mapping version 2.0
        0x00, 0x3B,             // MLe (max R-APDU data)
        0x00, 0x34,             // MLc (max C-APDU data)
        0x04, 0x06,             // NDEF File Control TLV, length 6
        0xE1.toByte(), 0x04,    // NDEF file ID
        0x04, 0x00,             // max NDEF file size = 1024
        0x00,                   // read access granted
        0xFF.toByte()           // write access denied (emulated tag is read-only)
    )

    override fun processCommandApdu(apdu: ByteArray?, extras: Bundle?): ByteArray {
        if (apdu == null) return insNotSupportedSw

        // SELECT application by AID
        if (apdu.contentEquals(selectAppApdu)) {
            selected = Selected.NONE
            return okSw
        }

        // SELECT file by ID: 00 A4 00 0C 02 <fid hi> <fid lo>
        if (apdu.size >= 7 && apdu[0] == 0x00.toByte() && apdu[1] == 0xA4.toByte() &&
            apdu[2] == 0x00.toByte() && apdu[3] == 0x0C.toByte() && apdu[4] == 0x02.toByte()
        ) {
            val fid = byteArrayOf(apdu[5], apdu[6])
            return when {
                fid.contentEquals(ccFileId) -> { selected = Selected.CC; okSw }
                fid.contentEquals(ndefFileId) -> { selected = Selected.NDEF; okSw }
                else -> fileNotFoundSw
            }
        }

        // READ BINARY: 00 B0 <offset hi> <offset lo> <le>
        if (apdu.size >= 5 && apdu[0] == 0x00.toByte() && apdu[1] == 0xB0.toByte()) {
            val offset = ((apdu[2].toInt() and 0xFF) shl 8) or (apdu[3].toInt() and 0xFF)
            val le = apdu[4].toInt() and 0xFF
            val file = when (selected) {
                Selected.CC -> capabilityContainer
                Selected.NDEF -> buildNdefFile()
                Selected.NONE -> return fileNotFoundSw
            }
            if (offset > file.size) return wrongLengthSw
            val end = minOf(offset + le, file.size)
            val slice = file.copyOfRange(offset, end)
            return slice + okSw
        }

        return insNotSupportedSw
    }

    override fun onDeactivated(reason: Int) {
        selected = Selected.NONE
    }

    /** NDEF file = 2-byte NLEN (message length) followed by the NDEF message. */
    private fun buildNdefFile(): ByteArray {
        val msg = HcePayloadStore.getMessageBytes(applicationContext)
        val nlen = msg.size
        return byteArrayOf(((nlen shr 8) and 0xFF).toByte(), (nlen and 0xFF).toByte()) + msg
    }

    private fun hexToBytes(s: String): ByteArray {
        val out = ByteArray(s.length / 2)
        for (i in out.indices) {
            out[i] = ((Character.digit(s[i * 2], 16) shl 4) or
                Character.digit(s[i * 2 + 1], 16)).toByte()
        }
        return out
    }
}
