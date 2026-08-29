package io.github.zvensmoluya.vortexcue.synctext

import android.content.Context
import androidx.annotation.RawRes
import io.github.zvensmoluya.vortexcue.R
import java.security.MessageDigest

data class TranslationPolicy(
    val policyId: String,
    val version: Int,
    val originRevision: String,
    val systemPromptId: String,
    val systemPrompt: String,
    val systemPromptSha256: String,
    val stylePromptId: String?,
    val stylePrompt: String?,
    val stylePromptSha256: String?,
    val requestSchemaVersion: Int = 1,
    val batchPolicyId: String = "published_source_120_v2",
    val contextPolicyId: String = "readonly_source_context_80_40_v2",
    val validationPolicyId: String = "strict_ids_v1",
    val recoveryPolicyId: String = "bounded_recovery_v1",
)

class PolicyIntegrityException(message: String) : IllegalStateException(message)

class TranslationPolicyRegistry private constructor(
    private val loadAsset: (PromptAsset) -> ByteArray,
) {
    fun resolve(targetLanguage: String): TranslationPolicy {
        val normalized = LanguageTags.normalize(targetLanguage)
        val system = verifiedAsset(SystemAsset)
        val style = if (LanguageTags.isSimplifiedChinese(normalized)) verifiedAsset(SimplifiedChineseStyleAsset) else null
        return TranslationPolicy(
            policyId =
                if (style == null) GenericPolicyId else SimplifiedChinesePolicyId,
            version = PolicyVersion,
            originRevision = OriginRevision,
            systemPromptId = SystemAsset.id,
            systemPrompt = system.text,
            systemPromptSha256 = system.sha256,
            stylePromptId = style?.asset?.id,
            stylePrompt = style?.text,
            stylePromptSha256 = style?.sha256,
        )
    }

    fun verifyAll() {
        verifiedAsset(SystemAsset)
        verifiedAsset(SimplifiedChineseStyleAsset)
    }

    private fun verifiedAsset(asset: PromptAsset): LoadedPromptAsset {
        val bytes = loadAsset(asset)
        val actual = sha256(bytes)
        if (!actual.equals(asset.sha256, ignoreCase = true)) {
            throw PolicyIntegrityException("Prompt asset ${asset.id} failed integrity validation")
        }
        return LoadedPromptAsset(asset, bytes.toString(Charsets.UTF_8), actual)
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    companion object {
        const val OriginRevision = "vortexcue-source-preparation-v1"
        const val PolicyVersion = 2
        const val GenericPolicyId = "vortexcue.subtitle.generic.v2"
        const val SimplifiedChinesePolicyId = "vortexcue.subtitle.zh-CN.v2"

        private val SystemAsset =
            PromptAsset(
                id = "system.v2",
                resourceId = R.raw.translation_system_v2,
                sha256 = "0caef8f6f9f2a28e951196c67b8852c4307c9232596786549e7ea702e5352029",
            )
        private val SimplifiedChineseStyleAsset =
            PromptAsset(
                id = "style.zh-CN.v2",
                resourceId = R.raw.translation_style_zh_cn_v2,
                sha256 = "a52812c8a42e6411e9acdd57bce85a38b0a158c09724f01beed57ee418bd81ae",
            )

        fun fromContext(context: Context): TranslationPolicyRegistry {
            val resources = context.applicationContext.resources
            return TranslationPolicyRegistry { asset ->
                resources.openRawResource(asset.resourceId).use { it.readBytes() }
            }
        }

        internal fun forTesting(loader: (String) -> ByteArray): TranslationPolicyRegistry =
            TranslationPolicyRegistry { asset -> loader(asset.id) }
    }
}

private data class PromptAsset(
    val id: String,
    @param:RawRes val resourceId: Int,
    val sha256: String,
)

private data class LoadedPromptAsset(
    val asset: PromptAsset,
    val text: String,
    val sha256: String,
)
