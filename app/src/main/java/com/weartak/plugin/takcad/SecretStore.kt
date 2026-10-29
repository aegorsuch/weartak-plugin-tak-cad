// SPDX-License-Identifier: GPL-3.0-or-later
package com.weartak.plugin.takcad

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

interface RoutingCredentials {
    fun read(): String
    fun configured(): Boolean
    fun write(value: String)
    fun clear()
}

class SecretStore(context: Context) : RoutingCredentials {
    private val preferences = context.getSharedPreferences("routing", Context.MODE_PRIVATE)
    private val alias = "${context.packageName}.ors"

    @Synchronized override fun read(): String {
        val stored = preferences.getString("key", null) ?: return ""
        val parts = stored.split(":")
        require(parts.size == 2) { "Stored routing credential invalid; replace key" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)))
        return cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)).toString(Charsets.UTF_8)
    }

    override fun configured(): Boolean = preferences.contains("key")

    @Synchronized override fun write(value: String) {
        require(value.isNotBlank() && value.length <= 4096 && value.none { it.isISOControl() }) { "Invalid routing key" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val encoded = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
            Base64.encodeToString(encrypted, Base64.NO_WRAP)
        check(preferences.edit().putString("key", encoded).commit()) { "Could not save encrypted routing key" }
    }

    @Synchronized override fun clear() {
        check(preferences.edit().remove("key").commit()) { "Could not remove routing key" }
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (store.containsAlias(alias)) return store.getKey(alias, null) as SecretKey
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
}
