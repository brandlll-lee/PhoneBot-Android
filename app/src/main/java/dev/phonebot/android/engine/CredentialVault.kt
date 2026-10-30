package dev.phonebot.android.engine

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONArray
import org.json.JSONObject

class CredentialVault(context: Context) {
    private val directory = File(context.noBackupFilesDir, "credentials").apply { mkdirs() }
    private fun file(provider: String): AtomicFile {
        require(provider.matches(Regex("[a-z0-9-]{1,80}"))) { "服务商 ID 无效" }
        return AtomicFile(File(directory, "$provider.bin"))
    }
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return (store.getKey("phonebot.credentials", null) as? SecretKey) ?: KeyGenerator.getInstance("AES", "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("phonebot.credentials", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build())
        }.generateKey()
    }
    @Synchronized fun read(provider: String): JSONObject? {
        val entry = file(provider)
        if (!entry.baseFile.exists()) return null
        val bytes = entry.readFully()
        require(bytes.size >= 28) { "凭据文件损坏" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        return JSONObject(String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8))
    }
    @Synchronized fun write(provider: String, credential: JSONObject) {
        require(credential.optString("type") in setOf("api_key", "oauth")) { "凭据类型无效" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val bytes = cipher.iv + cipher.doFinal(credential.toString().toByteArray(Charsets.UTF_8))
        val entry = file(provider)
        val stream = entry.startWrite()
        try { stream.write(bytes); entry.finishWrite(stream) }
        catch (error: Exception) { entry.failWrite(stream); throw error }
    }
    @Synchronized fun delete(provider: String) = file(provider).delete()
    @Synchronized fun list(): JSONArray = JSONArray().apply {
        directory.listFiles()?.filter { it.extension == "bin" }?.sortedBy { it.name }?.forEach { entry ->
            val provider = entry.nameWithoutExtension
            val credential = read(provider)
            if (credential != null) put(JSONObject().put("providerId", provider).put("type", credential.getString("type")))
        }
    }
}
