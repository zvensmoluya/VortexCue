package io.github.zvensmoluya.vortexcue.library

import java.text.Normalizer
import java.util.Locale

internal data class DiscoveryWorkPath(
    val stablePath: String,
    val displayName: String,
    val disambiguatedDisplayName: String,
    val locationLeaf: String,
)

internal data class DiscoverySelectionCandidate(
    val contentId: String,
    val displayName: String,
    val folder: String?,
)

internal fun defaultDiscoverySelection(candidates: List<DiscoverySelectionCandidate>): Set<String> {
    val variants = candidates.associateWith { it.explicitFormatVariant() }
    val wavKeys =
        variants.values.mapNotNull { variant ->
            variant?.takeIf { it.format == AudioFormat.Wav }?.trackKey
        }.toSet()
    return variants.mapNotNullTo(linkedSetOf()) { (candidate, variant) ->
        candidate.contentId.takeUnless { variant?.format == AudioFormat.Mp3 && variant.trackKey in wavKeys }
    }
}

internal fun resolveDiscoveryWorkPath(folder: String?): DiscoveryWorkPath {
    val pathParts = folder.normalizedPathParts()
    if (pathParts.isEmpty()) return OtherAudioPath

    val rjRootIndex = pathParts.indexOfLast { RjCode.containsMatchIn(it.normalizedToken()) }
    val workParts =
        if (rjRootIndex >= 0) {
            pathParts.take(rjRootIndex + 1)
        } else {
            inferWorkRoot(pathParts)
        }
    val displayName = workParts.last()
    return DiscoveryWorkPath(
        stablePath = workParts.joinToString("/") { it.normalizedKeyPart() },
        displayName = displayName,
        disambiguatedDisplayName = workParts.takeLast(2).joinToString(" / "),
        locationLeaf = displayName,
    )
}

private fun inferWorkRoot(pathParts: List<String>): List<String> {
    var rootSize = pathParts.size
    while (rootSize > 0 && pathParts[rootSize - 1].isTechnicalVariantDirectory()) {
        rootSize -= 1
    }
    if (rootSize == pathParts.size || rootSize == 0) return pathParts

    val candidate = pathParts.take(rootSize)
    return if (candidate.last().normalizedToken() in GenericContainerDirectories) pathParts else candidate
}

private fun String.isTechnicalVariantDirectory(): Boolean {
    val token = normalizedToken().replace(TechnicalSeparator, "")
    return explicitAudioFormatDirectory(token) != null ||
        FormatDirectory.matches(token) ||
        QualityDirectory.matches(token) ||
        FormatQualityDirectory.matches(token) ||
        SeVariantDirectory.matches(token)
}

private fun DiscoverySelectionCandidate.explicitFormatVariant(): ExplicitFormatVariant? {
    val extensionIndex = displayName.lastIndexOf('.')
    if (extensionIndex <= 0 || extensionIndex == displayName.lastIndex) return null
    val format =
        when (displayName.substring(extensionIndex + 1).normalizedToken()) {
            "wav", "wave" -> AudioFormat.Wav
            "mp3" -> AudioFormat.Mp3
            else -> return null
        }
    val pathParts = folder.normalizedPathParts()
    val formatDirectoryIndex = pathParts.indexOfLast { part ->
        explicitAudioFormatDirectory(part.normalizedToken().replace(TechnicalSeparator, "")) == format
    }
    if (formatDirectoryIndex < 0) return null

    val logicalFolder =
        pathParts.filterIndexed { index, _ -> index != formatDirectoryIndex }
            .joinToString("/") { it.normalizedKeyPart() }
    val logicalName = displayName.substring(0, extensionIndex).normalizedToken()
    return ExplicitFormatVariant(format, "$logicalFolder\u0000$logicalName")
}

private fun String?.normalizedPathParts(): List<String> =
    orEmpty()
        .replace('\\', '/')
        .split('/')
        .map(String::trim)
        .filter(String::isNotBlank)

private fun explicitAudioFormatDirectory(token: String): AudioFormat? =
    when {
        WavFormatDirectory.matches(token) -> AudioFormat.Wav
        Mp3FormatDirectory.matches(token) -> AudioFormat.Mp3
        else -> null
    }

private fun String.normalizedToken(): String =
    Normalizer.normalize(this, Normalizer.Form.NFKC).trim().lowercase(Locale.ROOT)

private fun String.normalizedKeyPart(): String = normalizedToken()

private enum class AudioFormat {
    Wav,
    Mp3,
}

private data class ExplicitFormatVariant(
    val format: AudioFormat,
    val trackKey: String,
)

private val OtherAudioPath =
    DiscoveryWorkPath(
        stablePath = "_other",
        displayName = "其他音频",
        disambiguatedDisplayName = "其他音频",
        locationLeaf = "其他音频",
    )

private val RjCode = Regex("(?:^|[^a-z0-9])rj\\d{5,}(?:[^a-z0-9]|$)")
private val TechnicalSeparator = Regex("[\\s._\\-\\[\\](){}（）［］【】]+")
private val AudioFormatDirectorySuffix =
    Regex("(?:(?:版|ver|version|format|音源|データ))?(?:(?:(?:16|24|32)bit(?:(?:44(?:1)?|48|88(?:2)?|96|192)k?hz?)?)|(?:(?:128|192|256|320)kbps))?(?:(?:版|ver|version|format|音源|データ))?")
private val WavFormatDirectory = Regex("(?:wav|wave)${AudioFormatDirectorySuffix.pattern}")
private val Mp3FormatDirectory = Regex("mp3${AudioFormatDirectorySuffix.pattern}")
private val FormatDirectory = Regex("(?:wav|wave|mp3|flac|m4a|aac|ogg|opus)(?:版|ver|version|format|音源)?")
private val QualityDirectory =
    Regex("(?:(?:16|24|32)bit(?:(?:44(?:1)?|48|88(?:2)?|96|192)k?hz?)?|(?:44(?:1)?|48|88(?:2)?|96|192)khz|hires|highresolution|lossless|ハイレゾ|高音質)")
private val FormatQualityDirectory =
    Regex("(?:wav|wave|mp3|flac|m4a|aac|ogg|opus)(?:(?:16|24|32)bit)?(?:(?:44(?:1)?|48|88(?:2)?|96|192)k?hz?)?(?:版|ver|version|format|音源)?")
private val SeVariantDirectory =
    Regex("(?:se(?:あり|有り?|付き|なし|無し?|抜き)|効果音(?:あり|有り?|付き|なし|無し?|抜き)|withse|withoutse)")

private val GenericContainerDirectories =
    setOf(
        "download",
        "downloads",
        "music",
        "audio",
        "voice",
        "dlsite",
        "doujin",
        "同人",
        "音声",
    )
