package io.github.zvensmoluya.vortexcue.subtitle

data class SubtitleCue(
    val startMs: Long,
    val endMs: Long,
    val text: String,
    val stableId: String? = null,
) {
    init {
        require(startMs >= 0) { "startMs must not be negative" }
        require(endMs > startMs) { "endMs must be greater than startMs" }
        require(stableId == null || stableId.isNotBlank()) { "stableId must not be blank" }
    }
}
