package com.will.nfctool

import android.content.Context
import android.nfc.NdefMessage

/**
 * Holds the NDEF message the HCE service presents to external readers.
 * Backed by SharedPreferences so it survives the service being recreated.
 */
object HcePayloadStore {
    private const val PREFS = "hce_payload"
    private const val KEY = "ndef_bytes"

    @Volatile
    private var cached: ByteArray? = null

    fun setMessage(ctx: Context, message: NdefMessage) {
        val bytes = message.toByteArray()
        cached = bytes
        ctx.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY, bytes.joinToString(",") { it.toInt().and(0xFF).toString() })
            .apply()
    }

    /** NDEF message bytes (no NLEN prefix). Falls back to a default greeting. */
    fun getMessageBytes(ctx: Context): ByteArray {
        cached?.let { return it }
        val stored = ctx.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, null)
        val bytes = if (stored.isNullOrEmpty()) {
            NfcOps.buildTextMessage("NFC Tool emulated tag").toByteArray()
        } else {
            stored.split(",").map { it.toInt().toByte() }.toByteArray()
        }
        cached = bytes
        return bytes
    }
}
