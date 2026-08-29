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
        const val PolicyVersion = 2
        const val PromptId = "source-preparation.system.v2"
        const val PromptSha256 = "ed3801e4a778918037d3e4db5e05570fcf8b27e7f2d2ec50a4830eb0e538d1e6"

        fun fromContext(context: Context): SourcePreparationPolicyRegistry {
            val resources = context.applicationContext.resources
            return SourcePreparationPolicyRegistry {
                resources.openRawResource(R.raw.source_preparation_system_v2).use { it.readBytes() }
            }
        }

        internal fun forTesting(loader: () -> ByteArray): SourcePreparationPolicyRegistry =
            SourcePreparationPolicyRegistry(loader)
    }
}
