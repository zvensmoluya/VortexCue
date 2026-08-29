package io.github.zvensmoluya.vortexcue.text

enum class SourcePatchOperation {
    Drop,
    Replace,
}

data class SourceTextPatch(
    val cueId: String,
    val operation: SourcePatchOperation,
    val replacementText: String? = null,
) {
    init {
        require(cueId.isNotBlank())
        when (operation) {
            SourcePatchOperation.Drop -> require(replacementText == null)
            SourcePatchOperation.Replace -> require(!replacementText.isNullOrBlank())
        }
    }
}

data class SourceStyleProfile(
    val contentMode: String? = null,
    val register: String? = null,
    val politeness: String? = null,
    val utteranceShape: String? = null,
    val preserveTraits: List<String> = emptyList(),
)

data class SourceEntityMention(
    val entityId: String,
    val kind: String,
    val observedForms: List<String>,
    val spellingStatus: String? = null,
    val evidenceCueIds: List<String> = emptyList(),
) {
    init {
        require(entityId.isNotBlank())
        require(kind.isNotBlank())
        require(observedForms.isNotEmpty() && observedForms.none(String::isBlank))
    }
}

data class SourceAliasCluster(
    val entityId: String,
    val forms: List<String>,
    val evidenceCueIds: List<String> = emptyList(),
) {
    init {
        require(entityId.isNotBlank())
        require(forms.size >= 2 && forms.none(String::isBlank))
    }
}

data class SourceAddressingFunction(
    val sourceForm: String,
    val function: String,
    val evidenceCueIds: List<String> = emptyList(),
) {
    init {
        require(sourceForm.isNotBlank())
        require(function.isNotBlank())
    }
}

data class SourceContext(
    val styleProfile: SourceStyleProfile = SourceStyleProfile(),
    val entityMentions: List<SourceEntityMention> = emptyList(),
    val aliasClusters: List<SourceAliasCluster> = emptyList(),
    val addressingFunctions: List<SourceAddressingFunction> = emptyList(),
) {
    fun isEmpty(): Boolean =
        styleProfile == SourceStyleProfile() &&
            entityMentions.isEmpty() &&
            aliasClusters.isEmpty() &&
            addressingFunctions.isEmpty()

    companion object {
        val Empty = SourceContext()
    }
}

data class SourcePreparationArtifact(
    val inputSourceHash: String,
    val policyVersion: Int,
    val patches: List<SourceTextPatch>,
    val sourceContext: SourceContext,
) {
    init {
        require(Sha256.matches(inputSourceHash))
        require(policyVersion > 0)
        require(patches.map(SourceTextPatch::cueId).distinct().size == patches.size)
    }

    private companion object {
        val Sha256 = Regex("[0-9a-f]{64}")
    }
}
