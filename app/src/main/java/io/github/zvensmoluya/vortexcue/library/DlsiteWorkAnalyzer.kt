package io.github.zvensmoluya.vortexcue.library

import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale

internal data class DlsiteWorkCandidate(
    val contentId: String,
    val displayName: String,
    val folder: String?,
    val durationMs: Long,
)

internal data class DlsiteWorkPartPlan(
    val primaryContentId: String,
    val equivalentContentIds: List<String>,
    val sectionName: String,
    val variantKey: String,
    val sortKey: String,
    val defaultIncluded: Boolean,
)

internal data class DlsiteWorkPlan(
    val parts: List<DlsiteWorkPartPlan>,
    val profileLabel: String,
    val manifestFingerprint: String,
) {
    val defaultContentIds: Set<String>
        get() = parts.filter(DlsiteWorkPartPlan::defaultIncluded).mapTo(linkedSetOf()) { it.primaryContentId }
}

internal fun analyzeDlsiteWork(candidates: List<DlsiteWorkCandidate>): DlsiteWorkPlan {
    val analyzed = candidates.map(::analyzeCandidate)
    val groups = analyzed.groupBy(AnalyzedCandidate::logicalKey)
    var preferredWav = false
    val parts =
        groups.values.flatMap { group ->
            val wav = group.firstOrNull { it.format == AudioDeliveryFormat.Wav }
            val mp3 = group.firstOrNull { it.format == AudioDeliveryFormat.Mp3 }
            val deliveryGroups =
                if (wav != null && mp3 != null && durationsMatch(wav.durationMs, mp3.durationMs)) {
                    preferredWav = true
                    listOf(listOf(wav, mp3))
                } else {
                    group.map(::listOf)
                }
            deliveryGroups.map { equivalent ->
                val primary = equivalent.firstOrNull { it.format == AudioDeliveryFormat.Wav } ?: equivalent.first()
                DlsiteWorkPartPlan(
                    primaryContentId = primary.contentId,
                    equivalentContentIds = equivalent.map(AnalyzedCandidate::contentId),
                    sectionName = primary.sectionName,
                    variantKey = primary.variantKey,
                    sortKey = primary.sortKey,
                    defaultIncluded = primary.variantKey != "without-se",
                )
            }
        }.sortedWith { left, right -> naturalPathCompare(left.sortKey, right.sortKey) }
    val hasBonus = parts.any { it.sectionName == "特典" }
    val profile = buildList {
        add(if (preferredWav) "WAV 优先" else "原始格式")
        if (parts.any { it.variantKey == "without-se" }) add("通常版")
        if (hasBonus) add("包含特典")
    }.joinToString(" · ")
    val manifest =
        candidates.sortedBy(DlsiteWorkCandidate::contentId).joinToString("\n") {
            "${it.contentId}|${it.folder.orEmpty()}|${it.displayName}|${it.durationMs}"
        }
    return DlsiteWorkPlan(parts, profile, sha256(manifest))
}

private data class AnalyzedCandidate(
    val contentId: String,
    val durationMs: Long,
    val format: AudioDeliveryFormat,
    val logicalKey: String,
    val sectionName: String,
    val variantKey: String,
    val sortKey: String,
)

private enum class AudioDeliveryFormat { Wav, Mp3, Other }

private fun analyzeCandidate(candidate: DlsiteWorkCandidate): AnalyzedCandidate {
    val path = candidate.folder.pathParts()
    val rjIndex = path.indexOfLast { RjCode.containsMatchIn(it.normalized()) }
    val relative = if (rjIndex >= 0) path.drop(rjIndex + 1) else path
    val stem = candidate.displayName.substringBeforeLast('.', candidate.displayName).normalized()
    val extension = candidate.displayName.substringAfterLast('.', "").normalized()
    val format = when (extension) {
        "wav", "wave" -> AudioDeliveryFormat.Wav
        "mp3" -> AudioDeliveryFormat.Mp3
        else -> AudioDeliveryFormat.Other
    }
    val semanticPath = relative.filterNot(::isFormatDirectory)
    val tokens = semanticPath.map(String::normalized)
    val isBonus = tokens.any { BonusDirectory.containsMatchIn(it) }
    val variant = when {
        tokens.any { WithoutSeDirectory.containsMatchIn(it.compact()) } -> "without-se"
        tokens.any { WithSeDirectory.containsMatchIn(it.compact()) } -> "with-se"
        else -> "default"
    }
    val logicalPath = semanticPath.joinToString("/") { it.normalized() }
    return AnalyzedCandidate(
        contentId = candidate.contentId,
        durationMs = candidate.durationMs,
        format = format,
        logicalKey = "$logicalPath\u0000$stem",
        sectionName = if (isBonus) "特典" else "正篇",
        variantKey = variant,
        sortKey = (relative + candidate.displayName).joinToString("/") { it.normalized() },
    )
}

private fun isFormatDirectory(value: String): Boolean {
    val compact = value.normalized().compact()
    return WavDirectory.matches(compact) || Mp3Directory.matches(compact)
}

private fun durationsMatch(left: Long, right: Long): Boolean {
    if (left <= 0L || right <= 0L) return false
    return kotlin.math.abs(left - right) <= maxOf(2_000L, maxOf(left, right) / 100L)
}

private fun String?.pathParts(): List<String> =
    orEmpty().replace('\\', '/').split('/').map(String::trim).filter(String::isNotBlank)

private fun String.normalized(): String =
    Normalizer.normalize(this, Normalizer.Form.NFKC).trim().lowercase(Locale.ROOT)

private fun String.compact(): String = replace(Separator, "")

private fun sha256(value: String): String =
    MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

private fun naturalPathCompare(left: String, right: String): Int {
    val leftParts = NaturalToken.findAll(left).map(MatchResult::value).toList()
    val rightParts = NaturalToken.findAll(right).map(MatchResult::value).toList()
    for (index in 0 until minOf(leftParts.size, rightParts.size)) {
        val l = leftParts[index]
        val r = rightParts[index]
        val compared = if (l.all(Char::isDigit) && r.all(Char::isDigit)) {
            l.trimStart('0').length.compareTo(r.trimStart('0').length).takeIf { it != 0 }
                ?: l.trimStart('0').compareTo(r.trimStart('0'))
        } else {
            l.compareTo(r)
        }
        if (compared != 0) return compared
    }
    return leftParts.size.compareTo(rightParts.size)
}

private val RjCode = Regex("(?:^|[^a-z0-9])rj\\d{5,}(?:[^a-z0-9]|$)")
private val Separator = Regex("[\\s._\\-\\[\\](){}（）［］【】]+")
private val WavDirectory = Regex("(?:\\d+)?wav(?:版|ver|version|format|音源|データ)?")
private val Mp3Directory = Regex("(?:\\d+)?mp3(?:版|ver|version|format|音源|データ)?")
private val BonusDirectory = Regex("特典|bonus|extra|omake|おまけ")
private val WithoutSeDirectory = Regex("(?:se|効果音)(?:なし|無し|抜き)|withoutse")
private val WithSeDirectory = Regex("(?:se|効果音)(?:あり|有り|付き)|withse")
private val NaturalToken = Regex("\\d+|\\D+")
