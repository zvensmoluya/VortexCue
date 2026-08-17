package io.github.zvensmoluya.vortexcue.synctext

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

interface CredentialStore {
    suspend fun put(credentialId: String, secret: String)

    suspend fun get(credentialId: String): String

    suspend fun delete(credentialId: String)

    suspend fun contains(credentialId: String): Boolean

    suspend fun pruneExcept(credentialIds: Set<String>) = Unit
}

class CredentialUnavailableException(message: String, cause: Throwable? = null) :
    IOException(message, cause)

class AndroidKeystoreCredentialStore(context: Context) : CredentialStore {
    private val directory =
        File(context.noBackupFilesDir, "sync_text/credentials").apply(File::mkdirs)
    private val mutex = Mutex()

    override suspend fun put(credentialId: String, secret: String) {
        require(secret.isNotBlank()) { "Credential must not be blank" }
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val id = requireSafeId(credentialId)
                val cipher = Cipher.getInstance(Transformation)
                cipher.init(Cipher.ENCRYPT_MODE, masterKey())
                cipher.updateAAD(id.toByteArray(Charsets.UTF_8))
                val encrypted = cipher.doFinal(secret.toByteArray(Charsets.UTF_8))
                val bytes =
                    ByteArrayOutputStream().use { buffer ->
                        DataOutputStream(buffer).use { output ->
                            output.write(Magic)
                            output.writeByte(cipher.iv.size)
                            output.write(cipher.iv)
                            output.writeInt(encrypted.size)
                            output.write(encrypted)
                        }
                        buffer.toByteArray()
                    }
                val atomic = AtomicFile(file(id))
                val stream = atomic.startWrite()
                try {
                    stream.write(bytes)
                    atomic.finishWrite(stream)
                } catch (error: Throwable) {
                    atomic.failWrite(stream)
                    throw error
                }
            }
        }
    }

    override suspend fun get(credentialId: String): String =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val id = requireSafeId(credentialId)
                val stored = file(id)
                if (!stored.isFile || stored.length() > MaxCredentialFileBytes) {
                    throw CredentialUnavailableException("Credential is unavailable")
                }
                try {
                    DataInputStream(stored.inputStream().buffered()).use { input ->
                        val magic = ByteArray(Magic.size).also(input::readFully)
                        if (!magic.contentEquals(Magic)) throw IOException("Unknown credential format")
                        val nonceLength = input.readUnsignedByte()
                        if (nonceLength !in 12..16) throw IOException("Invalid credential nonce")
                        val nonce = ByteArray(nonceLength).also(input::readFully)
                        val encryptedLength = input.readInt()
                        if (encryptedLength !in 17..MaxCredentialFileBytes) {
                            throw IOException("Invalid credential payload")
                        }
                        val encrypted = ByteArray(encryptedLength).also(input::readFully)
                        val cipher = Cipher.getInstance(Transformation)
                        cipher.init(Cipher.DECRYPT_MODE, masterKey(), GCMParameterSpec(128, nonce))
                        cipher.updateAAD(id.toByteArray(Charsets.UTF_8))
                        cipher.doFinal(encrypted).toString(Charsets.UTF_8)
                    }
                } catch (error: CredentialUnavailableException) {
                    throw error
                } catch (error: Throwable) {
                    throw CredentialUnavailableException("Credential cannot be decrypted", error)
                }
            }
        }

    override suspend fun delete(credentialId: String) {
        withContext(Dispatchers.IO) {
            mutex.withLock { file(requireSafeId(credentialId)).delete() }
        }
    }

    override suspend fun contains(credentialId: String): Boolean =
        withContext(Dispatchers.IO) { file(requireSafeId(credentialId)).isFile }

    override suspend fun pruneExcept(credentialIds: Set<String>) {
        val retained = credentialIds.mapTo(mutableSetOf(), ::requireSafeId)
        withContext(Dispatchers.IO) {
            mutex.withLock {
                directory.listFiles()
                    ?.filter { it.isFile && it.name.endsWith(".bin") }
                    ?.forEach { candidate ->
                        val id = candidate.name.removeSuffix(".bin")
                        if (id !in retained) candidate.delete()
                    }
            }
        }
    }

    private fun file(credentialId: String) = File(directory, "$credentialId.bin")

    private fun masterKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KeyStoreName).apply { load(null) }
        (keyStore.getKey(KeyAlias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KeyStoreName)
        generator.init(
            KeyGenParameterSpec.Builder(
                KeyAlias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .setUserAuthenticationRequired(false)
                .build(),
        )
        return generator.generateKey()
    }

    private fun requireSafeId(value: String): String {
        require(SafeId.matches(value)) { "Unsafe credential identifier" }
        return value
    }

    companion object {
        private const val KeyStoreName = "AndroidKeyStore"
        private const val KeyAlias = "vortexcue.sync_text.master.v1"
        private const val Transformation = "AES/GCM/NoPadding"
        private const val MaxCredentialFileBytes = 64 * 1024
        private val Magic = byteArrayOf('V'.code.toByte(), 'S'.code.toByte(), 'C'.code.toByte(), 1)
        private val SafeId = Regex("[A-Za-z0-9_-]{1,128}")
    }
}

fun maskCredential(secret: String): String {
    val suffix = secret.takeLast(4)
    return if (secret.length <= 4) "••••" else "•••• $suffix"
}
