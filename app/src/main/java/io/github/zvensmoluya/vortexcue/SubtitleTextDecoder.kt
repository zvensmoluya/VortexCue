package io.github.zvensmoluya.vortexcue

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

object SubtitleTextDecoder {
    fun decode(bytes: ByteArray): String {
        return try {
            when {
                bytes.startsWith(0xEF, 0xBB, 0xBF) -> decode(bytes, StandardCharsets.UTF_8, 3)
                bytes.startsWith(0xFF, 0xFE) -> decode(bytes, StandardCharsets.UTF_16LE, 2)
                bytes.startsWith(0xFE, 0xFF) -> decode(bytes, StandardCharsets.UTF_16BE, 2)
                else ->
                    try {
                        decode(bytes, StandardCharsets.UTF_8, 0)
                    } catch (_: CharacterCodingException) {
                        decode(bytes, Charset.forName("GB18030"), 0)
                    }
            }
        } catch (_: CharacterCodingException) {
            throw IllegalArgumentException("字幕编码不是有效的 UTF-8、GB18030 或带 BOM 的 UTF-16")
        }
    }

    private fun decode(bytes: ByteArray, charset: Charset, offset: Int): String =
        charset
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes, offset, bytes.size - offset))
            .toString()

    private fun ByteArray.startsWith(vararg prefix: Int): Boolean =
        size >= prefix.size && prefix.indices.all { index -> this[index].toInt() and 0xFF == prefix[index] }
}
