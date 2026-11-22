package org.schabi.newpipe.bilai.engine

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import androidx.preference.PreferenceManager
import org.schabi.newpipe.R
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Stores only the YouTube session header needed by the extractor. The key cannot leave this device. */
object BilaiAccountStore {
    private const val ALIAS = "bilaitube.youtube.session.v1"
    private const val CIPHER = "AES/GCM/NoPadding"
    private const val IV_BYTES = 12

    @JvmStatic
    @Synchronized
    fun load(context: Context): String? {
        val file = file(context)
        if (file.baseFile.exists()) {
            return runCatching {
                val encrypted = file.readFully()
                require(encrypted.size > IV_BYTES)
                val cipher = Cipher.getInstance(CIPHER)
                cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, encrypted, 0, IV_BYTES))
                String(cipher.doFinal(encrypted, IV_BYTES, encrypted.size - IV_BYTES), Charsets.UTF_8)
            }.getOrNull()
        }
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val legacyKey = context.getString(R.string.youtube_cookies_key)
        val legacy = prefs.getString(legacyKey, null)?.takeIf { it.isNotBlank() } ?: return null
        if (save(context, legacy)) {
            prefs.edit().remove(legacyKey).commit()
            return legacy
        }
        return null
    }

    @JvmStatic
    @Synchronized
    fun save(context: Context, cookies: String): Boolean = runCatching {
        require(cookies.isNotBlank())
        val cipher = Cipher.getInstance(CIPHER)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        require(cipher.iv.size == IV_BYTES)
        val encrypted = cipher.iv + cipher.doFinal(cookies.toByteArray(Charsets.UTF_8))
        val file = file(context)
        val out = file.startWrite()
        try {
            out.write(encrypted)
            file.finishWrite(out)
        } catch (failure: Exception) {
            file.failWrite(out)
            throw failure
        }
        true
    }.getOrDefault(false)

    @JvmStatic
    @Synchronized
    fun clear(context: Context) {
        file(context).delete()
        PreferenceManager.getDefaultSharedPreferences(context).edit()
            .remove(context.getString(R.string.youtube_cookies_key)).commit()
    }

    private fun file(context: Context) = AtomicFile(File(context.noBackupFilesDir, "youtube-session"))

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build())
            generateKey()
        }
    }
}
