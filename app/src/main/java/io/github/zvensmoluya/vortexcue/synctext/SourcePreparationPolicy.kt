package io.github.zvensmoluya.vortexcue.synctext

import android.content.Context
import io.github.zvensmoluya.vortexcue.R
import java.security.MessageDigest

data class SourcePreparationPolicy(
    val version: Int,
    val promptId: String,
    val systemPrompt: String,
    val promptSha256: String,
)

class SourcePreparationPolicyRegistry private constructor(
    private val loadAsset: () -> ByteArray,
) {
    fun resolve(): SourcePreparationPolicy {
        val bytes = loadAsset()
        val actual = sha256(bytes)
        if (!actual.equals(PromptSha256, ignoreCase = true)) {
            throw PolicyIntegrityException("Prompt asset $PromptId failed integrity validation")
        }
        return SourcePreparationPolicy(
            version = PolicyVersion,
            promptId = PromptId,
            systemPrompt = bytes.toString(Charsets.UTF_8),
            promptSha256 = actual,
        )
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    companion object {
        const val PolicyVersion = 1
        const val PromptId = "source-preparation.system.v1"
        const val PromptSha256 = "dcf4ee6bd866c35ea5c261fd33ba7b9f1ac353b8d64bea190ebb97ad0ce702e0"

        fun fromContext(context: Context): SourcePreparationPolicyRegistry {
            val resources = context.applicationContext.resources
            return SourcePreparationPolicyRegistry {
                resources.openRawResource(R.raw.source_preparation_system_v1).use { it.readBytes() }
            }
        }

        internal fun forTesting(loader: () -> ByteArray): SourcePreparationPolicyRegistry =
            SourcePreparationPolicyRegistry(loader)
    }
}
