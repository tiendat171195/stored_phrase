package io.github.tiendat171195.storedphrase.core.text

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.text.Normalizer

/** UTF-8 conversion for secrets without going through String; intermediate buffers are wiped. */
object Utf8 {

    /** @throws IllegalArgumentException for text that is not valid Unicode (e.g. a lone surrogate). */
    fun encode(chars: CharArray): ByteArray {
        if (chars.isEmpty()) return ByteArray(0)
        val encoder = Charsets.UTF_8.newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val buffer = try {
            encoder.encode(CharBuffer.wrap(chars))
        } catch (e: CharacterCodingException) {
            throw IllegalArgumentException("Text is not valid Unicode")
        }
        val out = ByteArray(buffer.remaining())
        buffer.get(out)
        if (buffer.hasArray()) buffer.array().fill(0)
        return out
    }

    /** @throws CharacterCodingException for bytes that are not valid UTF-8. */
    fun decode(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset): CharArray {
        if (length == 0) return CharArray(0)
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val buffer = decoder.decode(ByteBuffer.wrap(bytes, offset, length))
        val out = CharArray(buffer.remaining())
        buffer.get(out)
        if (buffer.hasArray()) buffer.array().fill('\u0000')
        return out
    }

    /**
     * Bytes fed to a password KDF: NFKD-normalised UTF-8, so the same passphrase typed on
     * different keyboards (composed vs decomposed accents) derives the same key.
     * ASCII input is used as is, without creating any String.
     */
    fun passphraseBytes(passphrase: CharArray): ByteArray {
        if (passphrase.all { it.code < 0x80 }) return encode(passphrase)
        val normalized = Normalizer.normalize(CharBuffer.wrap(passphrase), Normalizer.Form.NFKD).toCharArray()
        try {
            return encode(normalized)
        } finally {
            normalized.fill('\u0000')
        }
    }
}
